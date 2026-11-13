package com.example.pleosconnect.domain.rest

import com.example.pleosconnect.contract.integration.NavigationController
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/** Wire to LimitedCompanionController.withSafetyPriority; all safety voice owners share it. */
fun interface RestVoiceOwner { suspend fun run(block: suspend () -> Unit) }
data class RestCheckpoint(val remainingMillis: Long, val alarmConsumed: Boolean)
interface RestSessionStore {
    fun load(): RestCheckpoint?
    /** Must atomically persist before returning; implementations must not store personal data. */
    fun save(value: RestCheckpoint?)
}
enum class RestPhase { IDLE, RUNNING, RECOVERY, READY_TO_RESUME, RESUMED, DECLINED, UNCERTAIN, NO_RESPONSE, FAILED }
data class RestStatus(val phase: RestPhase = RestPhase.IDLE, val remainingMillis: Long = 0,
    val answer: RecognitionResult? = null, val failure: FailureReason? = null)

/** A-only orchestration. No OS alarm, vehicle SDK, UI or profile persistence. */
class RestSessionCoordinator(
    private val clock: MonotonicClock,
    private val parking: SafeParkingCheck,
    private val driver: DriverStateManager,
    private val navigation: NavigationController,
    private val voice: VoiceService,
    private val foreground: StateFlow<Boolean>,
    private val owner: RestVoiceOwner,
    private val store: RestSessionStore,
) {
    private val lock = Mutex()
    private val voiceLock = Mutex()
    @Volatile private var active: Job? = null
    private var deadline: Long? = null
    private var alarmConsumed = false
    private var lastClock = -1L
    @Volatile private var blocked = false
    @Volatile private var cleanupBroken = false
    private val mutableStatus = MutableStateFlow(RestStatus())
    val status = mutableStatus.asStateFlow()
    private fun now(): Long = clock.nowMillis().also { require(it >= 0 && it >= lastClock); lastClock = it }
    private fun parked() = try { parking.current() == SafeParking.VERIFIED } catch (_: Exception) { false }
    private fun allowed() = !blocked && !cleanupBroken && foreground.value && parked()
    private fun publish(phase: RestPhase, remaining: Long = 0) { mutableStatus.value = RestStatus(phase, remaining) }
    private fun save() { store.save(RestCheckpoint(status.value.remainingMillis, alarmConsumed)) }

    suspend fun start(minutes: Int): Boolean = lock.withLock {
        if (!allowed() || minutes !in 1..180 || deadline != null) return@withLock false
        driver.onRestStarted()
        if (driver.status.value.state != DriverState.RESTING) return@withLock false
        val remaining = minutes * 60_000L
        deadline = Math.addExact(now(), remaining); alarmConsumed = false
        publish(RestPhase.RUNNING, remaining); save(); true
    }
    /** Restoration freezes time lost while the process was absent; never restores health or consent. */
    suspend fun restore(): Boolean = lock.withLock {
        if (!allowed() || status.value.phase != RestPhase.IDLE) return@withLock false
        val value = store.load() ?: return@withLock false
        if (value.remainingMillis !in 0..10_800_000L) { store.save(null); return@withLock false }
        driver.onRestStarted()
        if (driver.status.value.state != DriverState.RESTING) return@withLock false
        alarmConsumed = value.alarmConsumed
        if (value.remainingMillis == 0L && value.alarmConsumed) { driver.onRestEnded(); publish(RestPhase.RECOVERY) }
        else { deadline = Math.addExact(now(), value.remainingMillis); publish(RestPhase.RUNNING, value.remainingMillis) }
        true
    }
    suspend fun tick() {
        var announce = false
        lock.withLock {
            val end = deadline ?: return@withLock
            val left = (end - now()).coerceAtLeast(0)
            publish(RestPhase.RUNNING, left); save()
            if (left == 0L && allowed()) {
                deadline = null
                announce = !alarmConsumed; alarmConsumed = true; save()
                driver.onRestEnded(); publish(RestPhase.RECOVERY)
            }
        }
        if (announce) speech { say("휴식 시간이 끝났습니다. 운전 가능 여부는 다시 확인해야 합니다."); recoveryAnswers() }
    }
    suspend fun extend(minutes: Int): Boolean = lock.withLock {
        if (!allowed() || minutes !in 1..180 || status.value.phase in setOf(RestPhase.IDLE, RestPhase.RESUMED)) return@withLock false
        val left = deadline?.let { (it - now()).coerceAtLeast(0) } ?: 0
        val total = left + minutes * 60_000L
        if (total > 10_800_000L) return@withLock false
        driver.onRestStarted()
        if (driver.status.value.state != DriverState.RESTING) return@withLock false
        deadline = Math.addExact(now(), total); alarmConsumed = false
        publish(RestPhase.RUNNING, total); save(); true
    }
    suspend fun cancelTimer() = lock.withLock {
        if (status.value.phase == RestPhase.IDLE || status.value.phase == RestPhase.RESUMED) return@withLock
        deadline = null; alarmConsumed = true; store.save(RestCheckpoint(0, true))
        driver.onRestEnded(); publish(RestPhase.RECOVERY)
    }
    suspend fun cancelVoice() { blocked = true; active?.cancelAndJoin() }
    fun onForeground() { blocked = false } // Explicit commands only; no automatic speech.

    /** One bounded voice command per explicit invocation. */
    suspend fun command() = speech {
        val answer = ask("휴식 시간을 몇 분으로 설정할까요? 남은 시간, 연장, 취소도 말씀할 수 있습니다.")
        if (answer !is RecognitionResult.Text) { record(answer); return@speech }
        val text = answer.value.replace(Regex("\\s+"), "").trimEnd('.', '!')
        when {
            text in setOf("취소", "타이머취소", "알람해제") -> { cancelTimer(); say("타이머를 취소했습니다. 운전 전 상태 확인이 필요합니다.") }
            text in setOf("남은시간", "얼마남았어", "남은시간알려줘") -> {
                lock.withLock {
                    deadline?.let { publish(RestPhase.RUNNING, (it - now()).coerceAtLeast(0)); save() }
                }
                say("남은 휴식 시간은 ${(status.value.remainingMillis + 59_999) / 60_000}분입니다.")
            }
            else -> {
                val minutes = parseMinutes(text)
                val success = minutes != null && if (text.endsWith("연장") || text.endsWith("연장해줘")) extend(minutes) else start(minutes)
                say(if (success) "휴식 타이머를 설정했습니다." else "시간을 확인하지 못했습니다. 1분에서 180분 사이로 다시 요청해 주세요.")
            }
        }
    }
    suspend fun checkRecovery() = speech {
        if (deadline != null || status.value.phase !in setOf(RestPhase.RECOVERY, RestPhase.NO_RESPONSE, RestPhase.FAILED, RestPhase.DECLINED) || driver.status.value.state == DriverState.RESTING) return@speech
        recoveryAnswers()
    }
    private suspend fun recoveryAnswers() {
        repeat(2) {
            val answer = ask("현재 졸리거나 피곤하신가요?")
            delay(1) // Do not reuse the lifecycle boundary timestamp.
            driver.onConditionAnswer(com.example.pleosconnect.domain.ConditionQuestion.FATIGUE.interpret(answer), now())
            record(answer)
            val current = driver.status.value
            if (current.report != ConditionReport.NORMAL) return
            if (current.state == DriverState.NORMAL && !current.followUpRequired) {
                publish(RestPhase.READY_TO_RESUME); return
            }
            // Distinct observation time even when Fake responds instantaneously.
            delay(1)
        }
    }
    suspend fun resume() = speech {
        if (status.value.phase != RestPhase.READY_TO_RESUME || !normal()) return@speech
        val route = navigation.route.value ?: return@speech
        val issuedAt = now()
        if (route.routeId.isBlank() || route.revision < 0) return@speech
        if (driver.status.value.lastConfirmedAtMillis?.let { now() - it in 0..60_000 } != true) return@speech
        val answer = ask("기존 목적지 안내를 다시 시작할까요?")
        if (answer !is RecognitionResult.Text) { record(answer); return@speech }
        val yes = answer.value.replace(Regex("\\s+"), "").trimEnd('.', '!') in setOf("네", "예", "응", "재개해줘", "안내해줘")
        if (!yes) { publish(RestPhase.DECLINED); return@speech }
        if (!allowed() || !normal() || now() - issuedAt > 60_000 || navigation.route.value?.let { it.routeId != route.routeId || it.revision != route.revision } != false) {
            mutableStatus.value = status.value.copy(phase = RestPhase.UNCERTAIN, failure = FailureReason.STALE_REQUEST); return@speech
        }
        publish(RestPhase.UNCERTAIN) // Consume consent before invoking B; never automatically retry.
        val result = withTimeoutOrNull(15.seconds) {
            navigation.resumeRoute(route.routeId, DriverConsent(ConsentScope.RESUME_ROUTE, route.routeId, now()))
        } ?: OperationResult.Failure(FailureReason.TIMEOUT)
        if (result is OperationResult.Success) { store.save(null); publish(RestPhase.RESUMED) }
        else mutableStatus.value = status.value.copy(failure = (result as OperationResult.Failure).reason)
    }
    private fun normal() = driver.status.value.let { it.state == DriverState.NORMAL && it.report == ConditionReport.NORMAL && !it.followUpRequired && it.lastConfirmedAtMillis?.let { at -> now() - at in 0..60_000 } == true }
    private fun record(answer: RecognitionResult) {
        mutableStatus.value = status.value.copy(answer = answer, phase = when (answer) {
            RecognitionResult.NoResponse -> RestPhase.NO_RESPONSE
            RecognitionResult.Timeout, RecognitionResult.PermissionDenied, RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable -> RestPhase.FAILED
            else -> RestPhase.RECOVERY
        }, failure = when (answer) {
            RecognitionResult.Timeout -> FailureReason.TIMEOUT
            RecognitionResult.PermissionDenied -> FailureReason.PERMISSION_DENIED
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable -> FailureReason.SERVICE_UNAVAILABLE
            else -> null
        })
    }
    private suspend fun say(text: String) {
        check(allowed())
        val result = withTimeoutOrNull(30.seconds) { voice.speak(text) } ?: OperationResult.Failure(FailureReason.TIMEOUT)
        if (result is OperationResult.Failure) throw VoiceFailure(result.reason)
        check(allowed())
    }
    private suspend fun ask(text: String): RecognitionResult {
        say(text)
        val answer = withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout
        check(allowed())
        return answer
    }
    private suspend fun speech(block: suspend () -> Unit) {
        if (!voiceLock.tryLock()) return
        try {
            if (!allowed()) return
            owner.run {
                coroutineScope {
                    if (!allowed() || voice.phase.value != VoicePhase.IDLE) return@coroutineScope
                    active = currentCoroutineContext().job
                    val job = active!!
                    val watcher = launch { while (allowed()) { delay(100) }; job.cancel() }
                    try { block() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: VoiceFailure) { mutableStatus.value = status.value.copy(phase = RestPhase.FAILED, failure = failure.reason) }
                    catch (_: SecurityException) { record(RecognitionResult.PermissionDenied) }
                    catch (_: Exception) { record(RecognitionResult.ServiceUnavailable) }
                    finally {
                        watcher.cancel()
                        withContext(NonCancellable) {
                            try { voice.stop() } catch (_: Exception) { cleanupBroken = true }
                            if (voice.phase.value == VoicePhase.UNAVAILABLE) cleanupBroken = true
                            if (cleanupBroken) mutableStatus.value = status.value.copy(phase = RestPhase.FAILED, failure = FailureReason.SERVICE_UNAVAILABLE)
                        }
                        active = null
                    }
                }
            }
        } finally { voiceLock.unlock() }
    }
    private class VoiceFailure(val reason: FailureReason) : RuntimeException()
    companion object {
        fun parseMinutes(text: String): Int? {
            val match = Regex("^(\\d{1,3}|일|이|삼|사|오|육|칠|팔|구|십|이십|삼십)분(?:연장|연장해줘)?$").matchEntire(text) ?: return null
            val token = match.groupValues[1]
            return (token.toIntOrNull() ?: mapOf("일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5, "육" to 6, "칠" to 7, "팔" to 8, "구" to 9, "십" to 10, "이십" to 20, "삼십" to 30)[token])?.takeIf { it in 1..180 }
        }
    }
}
