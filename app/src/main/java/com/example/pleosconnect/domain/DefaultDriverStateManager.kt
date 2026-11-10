package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.ConditionReport
import com.example.pleosconnect.contract.model.DriverState
import com.example.pleosconnect.contract.model.DriverStatus
import com.example.pleosconnect.contract.voice.RecognitionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SafeParking { VERIFIED, NOT_PARKED, UNKNOWN }
fun interface SafeParkingCheck { fun current(): SafeParking }

/** In-memory domain state only. Does not schedule, speak, navigate or control a vehicle. */
class DefaultDriverStateManager(
    private val clock: MonotonicClock,
    private val parking: SafeParkingCheck = SafeParkingCheck { SafeParking.UNKNOWN },
    private val classifier: ConditionClassifier = ConditionClassifier(),
) : RiskStateManager {
    private val mutableStatus = MutableStateFlow(DriverStatus())
    override val status = mutableStatus.asStateFlow()
    private var lastEventAt = -1L
    private var riskFloor = 0
    private var normalConfirmations = 0

    @Synchronized override fun onRepeatedNoResponse() {
        if (status.value.state == DriverState.RESTING ||
            status.value.report != ConditionReport.UNCONFIRMED || status.value.consecutiveNoResponses < 2) return
        riskFloor = 3
        normalConfirmations = 0
        mutableStatus.value = status.value.copy(state = DriverState.HIGH_RISK, followUpRequired = true)
    }

    @Synchronized override fun onConditionAnswer(answer: RecognitionResult, receivedAtMillis: Long) {
        require(receivedAtMillis >= 0)
        if (receivedAtMillis <= lastEventAt || status.value.state == DriverState.RESTING) return
        lastEventAt = receivedAtMillis
        val previous = status.value
        val report = if (answer is RecognitionResult.Text) classifier.classify(answer.value)
            else ConditionReport.UNCONFIRMED
        if (report == ConditionReport.UNCONFIRMED) {
            normalConfirmations = 0
            mutableStatus.value = previous.copy(
                report = report,
                followUpRequired = true,
                consecutiveNoResponses = if (answer == RecognitionResult.NoResponse)
                    (previous.consecutiveNoResponses.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 0,
            )
            return
        }
        val next = when (report) {
            ConditionReport.NORMAL -> {
                if (riskFloor == 0 && previous.state != DriverState.RECOVERY_CHECK) DriverState.NORMAL
                else {
                    normalConfirmations++
                    if (normalConfirmations >= 2) { riskFloor = 0; DriverState.NORMAL }
                    else DriverState.RECOVERY_CHECK
                }
            }
            else -> {
                normalConfirmations = 0
                val observedRisk = when (report) {
                    ConditionReport.SEVERE -> 3
                    ConditionReport.PERSISTENT_FATIGUE -> 2
                    else -> if (riskFloor >= 1) 2 else 1
                }
                riskFloor = maxOf(riskFloor, observedRisk)
                when (riskFloor) {
                    3 -> DriverState.HIGH_RISK
                    2 -> DriverState.WARNING
                    else -> DriverState.CAUTION
                }
            }
        }
        mutableStatus.value = previous.copy(
            state = next, report = report, lastConfirmedAtMillis = receivedAtMillis,
            consecutiveNoResponses = 0, followUpRequired = next != DriverState.NORMAL,
        )
    }

    @Synchronized override fun onRestStarted() {
        if (status.value.state == DriverState.RESTING) return
        val safe = try { parking.current() == SafeParking.VERIFIED } catch (_: Exception) { false }
        if (!safe) return
        markLifecycleBoundary()
        normalConfirmations = 0
        mutableStatus.value = status.value.copy(
            state = DriverState.RESTING, report = ConditionReport.UNCONFIRMED,
            consecutiveNoResponses = 0, followUpRequired = false,
        )
    }

    @Synchronized override fun onRestEnded() {
        if (status.value.state != DriverState.RESTING) return
        markLifecycleBoundary()
        normalConfirmations = 0
        mutableStatus.value = status.value.copy(
            state = DriverState.RECOVERY_CHECK, report = ConditionReport.UNCONFIRMED,
            consecutiveNoResponses = 0, followUpRequired = true,
        )
    }

    private fun markLifecycleBoundary() {
        val now = clock.nowMillis()
        require(now >= 0)
        lastEventAt = maxOf(lastEventAt, now)
    }
}
