package com.example.pleosconnect.pleos.vehicle

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.abs

/** B UI-only commands. Preset A voice replies never receive this controller or grant authority. */
class ConsentedVehicleSettings(private val backend: VehicleSettingsBackend, private val clock: MonotonicClock) {
    private val guard = Any()
    private val mutableState = MutableStateFlow(VehicleControlUiState(available = true))
    val state = mutableState.asStateFlow()
    private var profile: String? = null
    private var epoch = 0L
    private var revision = 0L
    private var monitoring = false
    private var tainted = false
    private var pending: Pair<VehicleSettingKey, Double>? = null
    private var conflict = false
    private val observed = mutableMapOf<VehicleSettingKey, Double>()
    private data class Plan(val review: VehicleChangeReview, val profile: String, val epoch: Long, val revision: Long, val issued: Long)
    private var plan: Plan? = null

    fun invalidate() = synchronized(guard) {
        val uncertain = pending != null
        epoch++; profile = null; monitoring = false; plan = null; pending = null; observed.clear()
        mutableState.value = VehicleControlUiState(available = true,
            notice = if (uncertain) "요청 결과 미확인 · 일부 변경이 적용됐을 수 있습니다. 자동 복원하지 않습니다." else "연결을 닫아도 차량 설정은 자동 복원하지 않습니다.")
    }
    suspend fun open(profileId: String?) {
        invalidate()
        if (profileId.isNullOrBlank()) return fail(FailureReason.INVALID_REQUEST)
        val token = synchronized(guard) { profile = profileId; tainted = false; epoch }
        val data = backend.inspect()
        synchronized(guard) {
            if (token != epoch) return
            observed.putAll(data.settings.associate { it.key to it.value })
            monitoring = true
            mutableState.update { it.copy(enabled = true, settings = data.settings, notice = "변경 전 대상과 값을 확인하고 각 요청에 동의해야 합니다.") }
        }
        try { backend.monitor(data.settings) { event -> onChange(token, event) } }
        catch (error: Exception) {
            invalidate()
            withContext(NonCancellable) { backend.close() }
            throw error
        }
    }
    private fun onChange(token: Long, event: VehicleSettingChange) = synchronized(guard) {
        if (token != epoch || profile == null) return
        val old = observed[event.key]
        if (event.value != null && event.value.isFinite()) observed[event.key] = event.value
        if (event.value == null || !event.value.isFinite()) monitoring = false
        mutableState.update { state -> state.copy(settings = state.settings.mapNotNull {
            if (it.key != event.key) it else event.value?.takeIf(it::accepts)?.let { value ->
                it.copy(value = value, observedAtMillis = clock.nowMillis())
            }
        }) }
        if (pending?.first == event.key) {
            if (event.value != pending?.second) conflict = true
        } else {
            val owned = state.value.changes.firstOrNull { it.key == event.key }
            val invalidOrChanged = event.value == null || !event.value.isFinite() ||
                (if (owned != null) event.value != owned.applied else old != event.value)
            if (invalidOrChanged) {
                revision++; plan = null
                mutableState.update { value -> value.copy(review = null, changes = value.changes.map {
                    if (it.key == event.key) it.copy(restorable = false, reason = "외부 또는 출처 미확인 변경 감지") else it
                }) }
            }
        }
    }
    private fun stationary(data: VehicleControlInspection): Boolean {
        val now = clock.nowMillis(); val snapshot = data.signals
        return snapshot.gear.fresh(now) && snapshot.gear.value == ReadGear.PARK &&
            snapshot.parkingBrake.fresh(now) && snapshot.parkingBrake.value == true &&
            snapshot.speedMetersPerSecond.fresh(now) && snapshot.speedMetersPerSecond.value?.let { it.isFinite() && abs(it) <= 0.05f } == true
    }
    private fun usable(setting: VehicleSetting, target: Double) = setting.accepts(setting.value) && setting.accepts(target) &&
        setting.observedAtMillis >= 0 && clock.nowMillis() - setting.observedAtMillis in 0..5_000
    suspend fun prepare(key: VehicleSettingKey, target: Double) {
        if (!ready()) return fail(FailureReason.SERVICE_UNAVAILABLE)
        val token = epoch
        val data = backend.inspect()
        synchronized(guard) {
            if (token != epoch || !ready()) return
            mutableState.update { it.copy(settings = data.settings) }
            val current = data.settings.singleOrNull { it.key == key }
            if (current == null || !usable(current, target)) return fail(FailureReason.UNSUPPORTED)
            if (!stationary(data)) return fail(FailureReason.INVALID_REQUEST)
            if (current.value == target || state.value.changes.any { it.key == key }) return fail(FailureReason.INVALID_REQUEST)
            observed[key] = current.value
            stage(VehicleChangeReview(UUID.randomUUID().toString(), key, current.value, target))
        }
    }
    suspend fun prepareRestore(id: String) {
        if (!ready()) return fail(FailureReason.SERVICE_UNAVAILABLE)
        val token = epoch
        val data = backend.inspect()
        synchronized(guard) {
            if (token != epoch || !ready()) return
            val owned = state.value.changes.singleOrNull { it.id == id }
            val current = owned?.let { change -> data.settings.singleOrNull { it.key == change.key } }
            if (owned == null || !owned.restorable || current == null || current.value != owned.applied)
                return fail(FailureReason.MANUAL_OVERRIDE)
            if (!usable(current, owned.original) || !stationary(data)) return fail(FailureReason.INVALID_REQUEST)
            stage(VehicleChangeReview(UUID.randomUUID().toString(), owned.key, owned.applied, owned.original,
                restoring = true, adjustmentId = owned.id))
        }
    }
    private fun stage(review: VehicleChangeReview) {
        plan = Plan(review, requireNotNull(profile), epoch, revision, clock.nowMillis())
        mutableState.update { it.copy(review = review, failure = null) }
    }
    fun cancelReview() = synchronized(guard) { plan = null; mutableState.update { it.copy(review = null) } }
    /** Called only by the explicit confirmation button of the exact currently displayed review. */
    suspend fun confirmFromUi(reviewId: String) {
        val action = synchronized(guard) {
            val current = plan; plan = null; mutableState.update { it.copy(review = null) }
            if (current == null || current.review.id != reviewId || !valid(current)) return fail(FailureReason.CONSENT_REQUIRED)
            current
        }
        val before = backend.inspect()
        val review = action.review
        val ownedId = review.adjustmentId ?: review.id
        synchronized(guard) {
            val current = before.settings.singleOrNull { it.key == review.key }
            if (!valid(action) || !stationary(before) || current == null || !usable(current, review.target) || current.value != review.original)
                return fail(FailureReason.STALE_REQUEST)
            if (review.restoring && state.value.changes.none { it.id == ownedId && it.restorable }) return fail(FailureReason.MANUAL_OVERRIDE)
            pending = review.key to review.target; conflict = false
            if (!review.restoring) mutableState.update { it.copy(changes = it.changes +
                VehicleOwnedChange(review.id, review.key, review.original, review.target, false, "변경 결과 확인 중")) }
            mutableState.update { it.copy(busy = true) }
        }
        var successful = false
        try {
            val result = backend.write(review.key, review.target)
            if (result is OperationResult.Failure) { fail(result.reason); return }
            val after = backend.inspect()
            synchronized(guard) {
                if (action.epoch != epoch) return
                val actual = after.settings.singleOrNull { it.key == review.key }
                if (conflict || !monitoring || !stationary(after) || actual == null || !usable(actual, review.target) || actual.value != review.target) {
                    fail(if (conflict) FailureReason.MANUAL_OVERRIDE else FailureReason.SERVICE_UNAVAILABLE); return
                }
                successful = true
                observed[review.key] = actual.value
                mutableState.update { state -> state.copy(settings = after.settings, failure = null,
                    changes = if (review.restoring) state.changes.filterNot { it.id == ownedId }
                        else state.changes.map { if (it.id == ownedId) it.copy(restorable = true, reason = null) else it },
                    notice = if (review.restoring) "새 동의 후 원래 값으로 복원됐음을 확인했습니다." else "변경 후 값을 다시 읽어 확인했습니다. 복원에도 별도 동의가 필요합니다.") }
            }
        } finally {
            synchronized(guard) {
                if (action.epoch == epoch) {
                    pending = null
                    if (!successful) {
                        tainted = true
                        mutableState.update { state -> state.copy(changes = state.changes.map {
                            if (it.id == ownedId) it.copy(restorable = false, reason = "결과 미확인 · 자동 복원 없음") else it
                        }, notice = "변경 결과를 확정할 수 없습니다. 자동 재시도·자동 복원하지 않습니다. 연결을 다시 준비해 주세요.") }
                    }
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }
    private fun valid(plan: Plan) = ready() && plan.profile == profile && plan.epoch == epoch && plan.revision == revision &&
        clock.nowMillis() - plan.issued in 0..30_000
    private fun ready() = state.value.enabled && profile != null && monitoring && !tainted
    fun setBusy(value: Boolean) { mutableState.update { it.copy(busy = value) } }
    fun fail(reason: FailureReason) = synchronized(guard) { mutableState.update { it.copy(failure = reason, review = null) }; plan = null }
}
