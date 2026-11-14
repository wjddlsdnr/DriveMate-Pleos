package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.logic.MonotonicClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NearbyDiscoveryResult {
    data class Found(val count: Int) : NearbyDiscoveryResult
    data object Failed : NearbyDiscoveryResult
}
fun interface NearbyDiscoveryTransport {
    suspend fun discover(request: NearbySearchConsentGate.Request, token: String): NearbyDiscoveryResult
}
data class NearbyDiscoveryState(val review: Boolean = false, val busy: Boolean = false,
    val count: Int? = null, val notice: String = "전송 전 동의가 필요합니다.", val remainingSeconds: Int = 0)

/** No background start, automatic retry, location persistence or navigation mutation. */
class NearbyDiscoveryController(private val scope: CoroutineScope, private val clock: MonotonicClock,
    private val context: () -> RestSearchContext?, private val allowed: () -> Boolean,
    private val transport: NearbyDiscoveryTransport) {
    private val gate = NearbySearchConsentGate(clock)
    private val mutable = MutableStateFlow(NearbyDiscoveryState())
    val state = mutable.asStateFlow()
    private var offer: NearbySearchConsentGate.Offer? = null
    private var token = ""
    private var operation: Job? = null
    private var reviewTimer: Job? = null
    private var generation = 0L
    fun prepare(sessionToken: String) {
        cancel()
        if (!allowed() || !sessionToken.matches(Regex("[a-f0-9]{64}"))) {
            mutable.value = NearbyDiscoveryState(notice = "정차·프로필·연결 상태와 중계 토큰을 확인해 주세요."); return
        }
        val current = context()
        offer = current?.let(gate::prepare)
        if (offer == null) { mutable.value = NearbyDiscoveryState(notice = "최신 위치가 없습니다. 내비 연결 후 다시 준비해 주세요."); return }
        token = sessionToken
        val selected = requireNotNull(offer)
        mutable.value = NearbyDiscoveryState(review = true, remainingSeconds = 30,
            notice = "준비 시점에 고정한 위치 한 건의 위도·경도를 PC 중계를 통해 TMAP에 1회 전송합니다. 반경 5km의 휴게소를 검색합니다.")
        reviewTimer = scope.launch {
            while (offer === selected) {
                val seconds = ((selected.expiresAtMillis - clock.nowMillis()).coerceAtLeast(0) + 999) / 1000
                if (seconds == 0L) {
                    gate.cancel(); offer = null; token = ""
                    mutable.value = NearbyDiscoveryState(notice = "동의 시간이 만료됐습니다. 전송 내용 확인을 다시 눌러 주세요.")
                    break
                }
                mutable.value = mutable.value.copy(remainingSeconds = seconds.toInt())
                delay(1_000)
            }
        }
    }
    fun confirmFromUi() {
        val selected = offer ?: return
        val current = context()
        if (!allowed() || current == null) { cancel(); return }
        val permit = gate.confirmFromUi(selected.id, current)
        val request = permit?.let { gate.consume(it, current) }
        if (request == null) {
            val reason = gate.rejection
            cancel()
            mutable.value = NearbyDiscoveryState(notice = when (reason) {
                NearbySearchConsentGate.Rejection.ROUTE_CHANGED -> "경로 정보가 갱신되어 전송하지 않았습니다. 다시 준비해 주세요."
                NearbySearchConsentGate.Rejection.STALE_CONTEXT -> "최신 위치 수신이 늦어 전송하지 않았습니다. 다시 준비해 주세요."
                NearbySearchConsentGate.Rejection.EXPIRED -> "동의 시간이 만료되어 전송하지 않았습니다. 다시 준비해 주세요."
                else -> "동의 확인에 실패하여 전송하지 않았습니다. 다시 준비해 주세요."
            })
            return
        }
        val credential = token
        reviewTimer?.cancel(); reviewTimer = null
        offer = null; token = ""
        val epoch = ++generation
        mutable.value = NearbyDiscoveryState(busy = true, notice = "검증 전 주변 장소를 조회하고 있습니다.")
        operation = scope.launch {
            // Dispatch may have been queued while connection/profile/route changed.
            val dispatchContext = context()
            if (!allowed() || dispatchContext == null || dispatchContext.routeId != current.routeId ||
                dispatchContext.routeRevision != current.routeRevision || clock.nowMillis() >= selected.expiresAtMillis) {
                this@NearbyDiscoveryController.cancel(); return@launch
            }
            val result = try { withTimeout(20_000) { transport.discover(request, credential) } }
            catch (e: TimeoutCancellationException) { NearbyDiscoveryResult.Failed }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { NearbyDiscoveryResult.Failed }
            if (epoch != generation) return@launch
            val latest = context()
            if (!allowed() || latest == null || latest.routeId != current.routeId || latest.routeRevision != current.routeRevision) {
                this@NearbyDiscoveryController.cancel(); return@launch
            }
            mutable.value = when (result) {
                is NearbyDiscoveryResult.Found -> NearbyDiscoveryState(count = result.count,
                    notice = "장소 ${result.count}건 발견. 진행 방향·진입·주차는 미검증이며 경로는 변경하지 않습니다.")
                NearbyDiscoveryResult.Failed -> NearbyDiscoveryState(notice = "검색에 실패했습니다. 연결·동의·중계 상태를 확인해 주세요.")
            }
        }
    }
    fun cancel() {
        generation++; operation?.cancel(); operation = null; reviewTimer?.cancel(); reviewTimer = null
        gate.cancel(); offer = null; token = ""
        mutable.value = NearbyDiscoveryState(notice = "위치 전송을 취소했습니다. 이미 전송된 정보는 회수할 수 없습니다.")
    }
}
