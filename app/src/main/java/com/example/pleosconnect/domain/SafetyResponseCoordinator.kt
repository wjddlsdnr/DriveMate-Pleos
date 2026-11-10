package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.scheduler.FollowUpScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration

data class SafetyResponse(
    val driver: DriverStatus = DriverStatus(),
    val restStopNeeded: Boolean = false,
    val failure: FailureReason? = null,
    val adjustment: VehicleAdjustment? = null,
    val manualOverride: Boolean = false,
)

/** One coordinator per driver session. Caller owns lifecycle and manual-override collection.
 * No real SDK calls, route search, automatic speech loop or emergency/seat commands.
 */
class SafetyResponseCoordinator(
    private val driver: RiskStateManager,
    private val scheduler: FollowUpScheduler,
    private val voice: VoiceService,
    private val vehicle: VehicleController,
    private val media: MediaController,
    private val companion: ConversationController,
    private val clock: MonotonicClock,
    private val assist: VehicleAssist = VehicleAssist.VENTILATION,
) {
    private val switching = Mutex()
    private var active: Deferred<Unit>? = null
    private var lastReceived = -1L
    private var lastClock = -1L
    private var closed = false
    private var assistAttempted = false
    private var manualBlocked = false
    private val consumedProposalIds = mutableSetOf<String>()
    private val mutableResponse = MutableStateFlow(SafetyResponse(driver.status.value))
    val response = mutableResponse.asStateFlow()

    suspend fun onConditionAnswer(answer: RecognitionResult, receivedAtMillis: Long) = coroutineScope {
        val task = switching.withLock {
            require(receivedAtMillis in 0..now())
            if (closed || receivedAtMillis <= lastReceived) return@coroutineScope
            active?.cancelAndJoin()
            lastReceived = receivedAtMillis
            attempt { scheduler.cancelFollowUps(); OperationResult.Success(Unit) }
            driver.onConditionAnswer(answer, receivedAtMillis)
            if (answer == RecognitionResult.NoResponse) driver.onRepeatedNoResponse()
            val status = driver.status.value
            val needRest = status.state in setOf(DriverState.WARNING, DriverState.HIGH_RISK) ||
                (status.state == DriverState.RECOVERY_CHECK && response.value.restStopNeeded)
            mutableResponse.value = response.value.copy(driver = status, restStopNeeded = needRest, failure = null)
            async(start = CoroutineStart.LAZY) {
                try { respond(answer, status) }
                finally { withContext(NonCancellable) { attempt { voice.stop(); OperationResult.Success(Unit) } } }
            }.also { active = it; it.start() }
        }
        task.await()
    }

    private suspend fun respond(answer: RecognitionResult, status: DriverStatus) {
        if (status.state == DriverState.RESTING) return
        // Safety speech preempts any listening/playback owned by the shared voice service.
        attempt { voice.stop(); OperationResult.Success(Unit) }
        if (status.state in setOf(DriverState.WARNING, DriverState.HIGH_RISK)) {
            followUp(CheckKind.RISK_FOLLOW_UP, 1)
            supervisorScope {
                val stopMedia = async(start = CoroutineStart.UNDISPATCHED) { attempt { media.execute(MediaCommand.Stop) } }
                attempt(1.seconds) { companion.pause(); OperationResult.Success(Unit) }
                attempt { voice.stop(); OperationResult.Success(Unit) }
                say(if (status.report == ConditionReport.UNCONFIRMED)
                    "운전자 상태가 확인되지 않습니다. 가능하면 안전한 곳에 정차해 주세요. 휴식 장소 안내가 필요합니다."
                else "졸음이 계속되거나 운전이 어려운 상태입니다. 지금 안전한 곳에서 휴식하세요. 휴식 장소 안내가 필요합니다.")
                stopMedia.await()
            }
            return
        }
        if (status.report == ConditionReport.UNCONFIRMED) {
            followUp(CheckKind.RISK_FOLLOW_UP, 1)
            say(if (answer == RecognitionResult.NoResponse)
                "운전자 상태가 확인되지 않습니다. 괜찮으신가요? 가능하면 안전한 곳에 정차해 주세요."
            else "음성으로 상태를 확인하지 못했습니다. 현재 상태를 다시 확인하겠습니다.")
            return
        }
        when (status.state) {
            DriverState.CAUTION -> {
                followUp(CheckKind.RISK_FOLLOW_UP, 5)
                if (say("안전한 곳에서 잠시 휴식하시는 것을 권장합니다. 환기는 휴식을 대신하지 않습니다.")) offerAssist(status)
            }
            DriverState.RECOVERY_CHECK -> {
                followUp(CheckKind.RECOVERY, 2)
                say("회복 상태를 추가로 확인하겠습니다. 무리하지 말고 필요하면 휴식하세요.")
            }
            DriverState.NORMAL -> { if (!manualBlocked) assistAttempted = false }
            else -> Unit
        }
    }

    private suspend fun offerAssist(expected: DriverStatus) {
        if (assistAttempted || manualBlocked) return
        assistAttempted = true
        val issuedAt = now()
        val proposal = when (val result = attempt { vehicle.propose(assist) }) {
            is OperationResult.Success -> result.value
            is OperationResult.Failure -> { say("환기 보조를 사용할 수 없습니다. 안전한 곳에서 휴식해 주세요."); return }
        }
        if (proposal.id.isBlank() || proposal.assist != assist) { fail(FailureReason.INVALID_REQUEST); return }
        if (!say("${proposal.description} 이 차량 설정 변경에 동의하시나요?")) return
        val answer = try {
            withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { RecognitionResult.PermissionDenied }
        catch (_: Exception) { RecognitionResult.ServiceUnavailable }
        val positive = answer is RecognitionResult.Text && answer.value.replace(Regex("\\s+"), "")
            .trimEnd('.', '!') in setOf("응", "네", "예", "동의해", "동의합니다", "좋아", "해주세요")
        if (!positive) {
            when (answer) {
                RecognitionResult.PermissionDenied -> fail(FailureReason.PERMISSION_DENIED)
                RecognitionResult.Timeout -> fail(FailureReason.TIMEOUT)
                RecognitionResult.ServiceUnavailable, RecognitionResult.RecognitionFailure -> fail(FailureReason.SERVICE_UNAVAILABLE)
                else -> Unit
            }
            return
        }
        currentCoroutineContext().ensureActive()
        val confirmedAt = now()
        if (manualBlocked || driver.status.value != expected || confirmedAt - issuedAt > 60_000 ||
            !consumedProposalIds.add(proposal.id)) {
            fail(FailureReason.STALE_REQUEST); return
        }
        when (val result = attempt {
            vehicle.apply(proposal, DriverConsent(ConsentScope.VEHICLE_ASSIST, proposal.id, confirmedAt))
        }) {
            is OperationResult.Success -> mutableResponse.value = response.value.copy(adjustment = result.value)
            is OperationResult.Failure -> say("차량 설정 변경을 확인하지 못했습니다. 안전한 곳에서 휴식해 주세요.")
        }
    }

    suspend fun observeManualOverrides() {
        vehicle.manualOverrides.collect {
            switching.withLock {
                if (!closed) {
                    active?.cancelAndJoin()
                    manualBlocked = true
                    mutableResponse.value = response.value.copy(manualOverride = true)
                }
            }
        }
    }

    suspend fun cancel() = switching.withLock {
        active?.cancelAndJoin(); active = null
        attempt { scheduler.cancelFollowUps(); OperationResult.Success(Unit) }
    }
    suspend fun close() = switching.withLock {
        if (!closed) {
            closed = true
            active?.cancelAndJoin()
            active = null
            attempt { scheduler.cancelFollowUps(); OperationResult.Success(Unit) }
            withContext(NonCancellable) { attempt { voice.stop(); OperationResult.Success(Unit) } }
        }
    }

    private suspend fun say(text: String): Boolean =
        attempt { voice.speak(text) } is OperationResult.Success

    private suspend fun followUp(kind: CheckKind, minutes: Int) {
        attempt { scheduler.scheduleFollowUp(kind, minutes.minutes); OperationResult.Success(Unit) }
    }

    private suspend fun <T> attempt(timeout: Duration = 30.seconds, block: suspend () -> OperationResult<T>): OperationResult<T> {
        val result = try { withTimeoutOrNull(timeout) { block() } ?: OperationResult.Failure(FailureReason.TIMEOUT) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        if (result is OperationResult.Failure) fail(result.reason)
        return result
    }
    private fun fail(reason: FailureReason) {
        if (reason == FailureReason.MANUAL_OVERRIDE) manualBlocked = true
        mutableResponse.value = response.value.copy(failure = reason, manualOverride = manualBlocked)
    }
    @Synchronized private fun now(): Long {
        val value = clock.nowMillis()
        require(value >= 0 && value >= lastClock)
        lastClock = value
        return value
    }
}
