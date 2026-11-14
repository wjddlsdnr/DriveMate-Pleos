package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-memory assistance only, without vehicle values, permissions or SDK calls. */
class FakeVehicleController(clock: MonotonicClock) : VehicleController {
    private val overrides = MutableSharedFlow<VehicleAdjustment>(extraBufferCapacity = 8)
    override val manualOverrides = overrides.asSharedFlow()
    private val consents = FakeConsentValidator(clock)
    private val proposals = mutableMapOf<String, VehicleAssistProposal>()
    private val adjustments = mutableSetOf<VehicleAdjustment>()
    private val manuallyChanged = mutableSetOf<VehicleAdjustment>()
    private var nextId = 0
    var failure: FailureReason? = null
    var appliedCount = 0; private set
    override suspend fun propose(assist: VehicleAssist): OperationResult<VehicleAssistProposal> {
        failure?.let { return OperationResult.Failure(it) }
        val proposal = VehicleAssistProposal("fake-assist-${++nextId}", assist, "시뮬레이션 환기를 적용할까요? 실제 차량은 변경되지 않습니다.")
        proposals[proposal.id] = proposal
        return OperationResult.Success(proposal)
    }
    override suspend fun apply(proposal: VehicleAssistProposal, consent: DriverConsent): OperationResult<VehicleAdjustment> {
        if (proposals[proposal.id] != proposal) return OperationResult.Failure(FailureReason.STALE_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        consents.consume(consent, ConsentScope.VEHICLE_ASSIST, proposal.id)?.let { return OperationResult.Failure(it) }
        proposals.remove(proposal.id)
        val adjustment = VehicleAdjustment("fake-adjustment-${++nextId}")
        adjustments += adjustment
        appliedCount++
        return OperationResult.Success(adjustment)
    }
    suspend fun simulateManualOverride(adjustment: VehicleAdjustment) {
        if (adjustment in adjustments) { manuallyChanged += adjustment; overrides.emit(adjustment) }
    }
    override suspend fun restore(adjustment: VehicleAdjustment, consent: DriverConsent): OperationResult<Unit> {
        if (adjustment !in adjustments) return OperationResult.Failure(FailureReason.STALE_REQUEST)
        if (adjustment in manuallyChanged) return OperationResult.Failure(FailureReason.MANUAL_OVERRIDE)
        failure?.let { return OperationResult.Failure(it) }
        consents.consume(consent, ConsentScope.RESTORE_VEHICLE, adjustment.id)?.let { return OperationResult.Failure(it) }
        adjustments.remove(adjustment)
        return OperationResult.Success(Unit)
    }
}
