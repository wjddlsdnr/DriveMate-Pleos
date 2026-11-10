package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.scheduler.DefaultCheckScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class SafetyResponseCoordinatorTest {
    private class FakeVoice(val events: MutableList<String>) : VoiceService {
        override val phase = MutableStateFlow(VoicePhase.IDLE)
        val prompts = mutableListOf<String>()
        var answer: RecognitionResult = RecognitionResult.Text("네")
        var gate: CompletableDeferred<RecognitionResult>? = null
        var speechFailure: FailureReason? = null
        var onListen: () -> Unit = {}
        var listens = 0
        override suspend fun speak(text: String): OperationResult<Unit> {
            events += "speak"; prompts += text
            return speechFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
        }
        override suspend fun listen(timeout: Duration): RecognitionResult {
            events += "listen"; listens++; onListen()
            return gate?.await() ?: answer
        }
        override suspend fun stop() { events += "voiceStop" }
        override suspend fun close() = Unit
    }
    private class FakeVehicle(val events: MutableList<String>) : VehicleController {
        override val manualOverrides = MutableSharedFlow<VehicleAdjustment>()
        var proposed: OperationResult<VehicleAssistProposal> = OperationResult.Success(
            VehicleAssistProposal("ventilation-1", VehicleAssist.VENTILATION, "창문 환기를 제안합니다."))
        var applied: OperationResult<VehicleAdjustment> = OperationResult.Success(VehicleAdjustment("change-1"))
        var applyGate: CompletableDeferred<Unit>? = null
        var proposals = 0
        var proposeException: Exception? = null
        val consents = mutableListOf<DriverConsent>()
        override suspend fun propose(assist: VehicleAssist): OperationResult<VehicleAssistProposal> {
            events += "propose"; proposals++
            proposeException?.let { throw it }
            return proposed
        }
        override suspend fun apply(proposal: VehicleAssistProposal, consent: DriverConsent): OperationResult<VehicleAdjustment> {
            events += "apply"; consents += consent; applyGate?.await(); return applied
        }
        override suspend fun restore(adjustment: VehicleAdjustment, consent: DriverConsent): OperationResult<Unit> =
            error("Phase 5 must never restore automatically")
    }
    private class Fixture(val scope: TestScope) {
        var offset = 1L
        val clock = MonotonicClock { scope.testScheduler.currentTime + offset }
        val events = mutableListOf<String>()
        val voice = FakeVoice(events)
        val vehicle = FakeVehicle(events)
        val driver = DefaultDriverStateManager(clock, SafeParkingCheck { SafeParking.VERIFIED })
        val scheduler = DefaultCheckScheduler(clock).also {
            it.onRouteUpdated(RouteProgress("test-trip", 0, 120.minutes, clock.nowMillis()))
            it.takeDue()
        }
        var mediaResult: OperationResult<Unit> = OperationResult.Success(Unit)
        var mediaGate: CompletableDeferred<Unit>? = null
        val media = object : MediaController {
            override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
                assertEquals(MediaCommand.Stop, command)
                events += "mediaStop"; mediaGate?.await(); return mediaResult
            }
        }
        val companion = object : ConversationController {
            override val phase = MutableStateFlow(ConversationPhase.IDLE)
            override suspend fun start() = Unit
            override suspend fun requestConditionCheck(kind: CheckKind) = Unit
            override suspend fun pause() { events += "companionPause" }
            override suspend fun close() = Unit
        }
        val coordinator = SafetyResponseCoordinator(driver, scheduler, voice, vehicle, media, companion, clock)
        suspend fun input(result: RecognitionResult) { offset++; coordinator.onConditionAnswer(result, clock.nowMillis()) }
        suspend fun text(value: String) = input(RecognitionResult.Text(value))
        val state get() = coordinator.response.value
        val followups get() = scheduler.pending.value.filter { it.kind != CheckKind.PERIODIC }
    }

    @Test fun cautionRequestsVehicleOnlyAfterCompletedPromptAndExplicitConsent() = runTest {
        val f = Fixture(this); f.text("조금 졸려")
        assertEquals(DriverState.CAUTION, f.state.driver.state)
        assertEquals(1, f.vehicle.consents.size)
        val consent = f.vehicle.consents.single()
        assertEquals(ConsentScope.VEHICLE_ASSIST, consent.scope)
        assertEquals("ventilation-1", consent.targetId)
        assertTrue(f.events.indexOf("listen") < f.events.indexOf("apply"))
        assertTrue(f.events.indexOf("propose") < f.events.indexOf("listen"))
        assertEquals(CheckKind.RISK_FOLLOW_UP, f.followups.single().kind)
        assertEquals(f.clock.nowMillis() + 300_000, f.followups.single().dueAtMillis)
        assertNotNull(f.state.adjustment)
        assertFalse(f.state.restStopNeeded)
    }
    @Test fun negativeAmbiguousOrUnrecognizedConsentNeverApplies() = runTest {
        listOf("아니", "아니요", "싫어", "네 아니요", "동의할까?", "응급", "잘 모르겠어", "").forEach {
            val f = Fixture(this); f.voice.answer = RecognitionResult.Text(it); f.text("졸려")
            assertTrue(f.vehicle.consents.isEmpty())
            assertEquals(0, f.state.driver.consecutiveNoResponses)
        }
    }
    @Test fun consentSilenceAndErrorsAreNotConditionNoResponse() = runTest {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.ServiceUnavailable, RecognitionResult.RecognitionFailure).forEach {
            val f = Fixture(this); f.voice.answer = it; f.text("졸려")
            assertTrue(f.vehicle.consents.isEmpty())
            assertEquals(DriverState.CAUTION, f.state.driver.state)
            assertEquals(0, f.state.driver.consecutiveNoResponses)
        }
    }
    @Test fun unsupportedVehicleStillRecommendsRestAndSchedulesFollowup() = runTest {
        val f = Fixture(this); f.vehicle.proposed = OperationResult.Failure(FailureReason.UNSUPPORTED)
        f.text("졸려")
        assertEquals(0, f.voice.listens)
        assertTrue(f.vehicle.consents.isEmpty())
        assertEquals(FailureReason.UNSUPPORTED, f.state.failure)
        assertTrue(f.voice.prompts.any { "사용할 수 없습니다" in it })
        assertEquals(1, f.followups.size)
    }
    @Test fun failedPlaybackNeverStartsConsentListening() = runTest {
        val f = Fixture(this); f.voice.speechFailure = FailureReason.PERMISSION_DENIED
        f.text("졸려")
        assertEquals(0, f.voice.listens)
        assertEquals(0, f.vehicle.proposals)
        assertEquals(FailureReason.PERMISSION_DENIED, f.state.failure)
        assertEquals(1, f.followups.size)
    }
    @Test fun failedApplyIsReportedAndNeverAutomaticallyRetried() = runTest {
        val f = Fixture(this); f.vehicle.applied = OperationResult.Failure(FailureReason.MANUAL_OVERRIDE)
        f.text("졸려"); f.text("계속 졸려")
        assertEquals(1, f.vehicle.consents.size)
        assertEquals(1, f.vehicle.proposals)
        assertTrue(f.state.restStopNeeded)
    }
    @Test fun repeatedNoResponseBecomesUnconfirmedHighRiskAndNeedsRecoveryChecks() = runTest {
        val f = Fixture(this); f.text("괜찮아")
        val confirmed = f.state.driver.lastConfirmedAtMillis
        f.input(RecognitionResult.NoResponse)
        assertEquals(DriverState.NORMAL, f.state.driver.state)
        assertFalse(f.state.restStopNeeded)
        f.input(RecognitionResult.NoResponse)
        assertEquals(DriverState.HIGH_RISK, f.state.driver.state)
        assertEquals(ConditionReport.UNCONFIRMED, f.state.driver.report)
        assertEquals(2, f.state.driver.consecutiveNoResponses)
        assertEquals(confirmed, f.state.driver.lastConfirmedAtMillis)
        assertTrue(f.state.restStopNeeded)
        assertTrue("mediaStop" in f.events && "companionPause" in f.events)
        f.text("괜찮아")
        assertEquals(DriverState.RECOVERY_CHECK, f.state.driver.state)
        assertTrue(f.state.restStopNeeded)
        f.text("괜찮아")
        assertEquals(DriverState.NORMAL, f.state.driver.state)
        assertFalse(f.state.restStopNeeded)
    }
    @Test fun microphoneAndServiceErrorsNeverBecomeNoResponseOrSleep() = runTest {
        val f = Fixture(this)
        repeat(3) {
            listOf(RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
                RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable).forEach { f.input(it) }
        }
        assertNull(f.state.driver.state)
        assertEquals(0, f.state.driver.consecutiveNoResponses)
        assertFalse(f.state.restStopNeeded)
        assertFalse("mediaStop" in f.events)
    }
    @Test fun timeoutBreaksConsecutiveNoResponseSequence() = runTest {
        val f = Fixture(this)
        f.input(RecognitionResult.NoResponse); f.input(RecognitionResult.Timeout); f.input(RecognitionResult.NoResponse)
        assertEquals(1, f.state.driver.consecutiveNoResponses)
        assertNull(f.state.driver.state)
    }
    @Test fun severeAnswerPreemptsPendingConsentAndNeverAppliesOldProposal() = runTest {
        val f = Fixture(this); f.voice.gate = CompletableDeferred()
        val caution = launch { f.text("졸려") }; runCurrent()
        assertEquals(1, f.voice.listens)
        f.text("너무 졸려")
        assertTrue(caution.isCancelled)
        assertTrue(f.vehicle.consents.isEmpty())
        assertEquals(DriverState.HIGH_RISK, f.state.driver.state)
        assertTrue(f.state.restStopNeeded)
        assertTrue(f.voice.prompts.last().contains("지금 안전한 곳"))
        f.voice.gate!!.complete(RecognitionResult.Text("네"))
        runCurrent(); assertTrue(f.vehicle.consents.isEmpty())
    }
    @Test fun slowMediaStopDoesNotDelayImmediateSafetySpeech() = runTest {
        val f = Fixture(this); f.mediaGate = CompletableDeferred()
        val action = launch { f.text("운전 못 하겠어") }; runCurrent()
        assertTrue(f.state.restStopNeeded)
        assertTrue(f.voice.prompts.any { "지금 안전한 곳" in it })
        assertEquals(0L, testScheduler.currentTime)
        advanceUntilIdle(); action.join()
        assertEquals(FailureReason.TIMEOUT, f.state.failure)
    }
    @Test fun mediaFailureDoesNotHideRestNeedOrSuppressWarning() = runTest {
        val f = Fixture(this); f.mediaResult = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        f.text("계속 졸려")
        assertTrue(f.state.restStopNeeded)
        assertTrue(f.voice.prompts.any { "휴식" in it })
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.state.failure)
    }
    @Test fun recoveryAndNormalRemoveObsoleteRiskFollowupsOnly() = runTest {
        val f = Fixture(this); f.text("졸려"); f.text("괜찮아")
        assertEquals(CheckKind.RECOVERY, f.followups.single().kind)
        f.text("괜찮아")
        assertTrue(f.followups.isEmpty())
        assertTrue(f.scheduler.pending.value.any { it.kind == CheckKind.PERIODIC })
    }
    @Test fun duplicateAndStaleConditionEventsHaveNoSideEffects() = runTest {
        val f = Fixture(this); f.text("졸려")
        val count = f.events.size
        f.coordinator.onConditionAnswer(RecognitionResult.Text("너무 졸려"), f.clock.nowMillis())
        f.coordinator.onConditionAnswer(RecognitionResult.Text("너무 졸려"), f.clock.nowMillis() - 1)
        assertEquals(count, f.events.size)
        assertEquals(DriverState.CAUTION, f.state.driver.state)
    }
    @Test fun manualOverrideCancelsConsentAndBlocksReapplicationWithoutChangingRisk() = runTest {
        val f = Fixture(this)
        backgroundScope.launch { f.coordinator.observeManualOverrides() }; runCurrent()
        f.voice.gate = CompletableDeferred()
        val action = launch { f.text("졸려") }; runCurrent()
        f.vehicle.manualOverrides.emit(VehicleAdjustment("driver-change")); runCurrent()
        assertTrue(action.isCancelled)
        assertEquals(DriverState.CAUTION, f.state.driver.state)
        assertTrue(f.state.manualOverride)
        assertTrue(f.vehicle.consents.isEmpty())
        f.text("괜찮아"); f.text("괜찮아"); f.text("졸려")
        assertEquals(1, f.vehicle.proposals)
    }
    @Test fun expiredConsentCannotChangeVehicle() = runTest {
        val f = Fixture(this); f.voice.onListen = { f.offset += 61_000 }
        f.text("졸려")
        assertTrue(f.vehicle.consents.isEmpty())
        assertEquals(FailureReason.STALE_REQUEST, f.state.failure)
    }
    @Test fun consumedProposalIdCannotBeReusedInNewFatigueEpisode() = runTest {
        val f = Fixture(this); f.text("졸려"); f.text("괜찮아"); f.text("괜찮아"); f.text("졸려")
        assertEquals(1, f.vehicle.consents.size)
        assertEquals(FailureReason.STALE_REQUEST, f.state.failure)
    }
    @Test fun cancelAndCloseDiscardPendingConsentAndFollowups() = runTest {
        val f = Fixture(this); f.voice.gate = CompletableDeferred()
        val action = launch { f.text("졸려") }; runCurrent()
        f.coordinator.cancel()
        assertTrue(action.isCancelled)
        assertTrue(f.followups.isEmpty())
        f.coordinator.close(); f.coordinator.close()
        val count = f.events.size
        f.text("너무 졸려")
        assertEquals(count, f.events.size)
        assertTrue(f.vehicle.consents.isEmpty())
    }
    @Test fun callerCancellationDuringApplyDoesNotRetryMutation() = runTest {
        val f = Fixture(this); f.vehicle.applyGate = CompletableDeferred()
        val action = launch { f.text("졸려") }; runCurrent()
        assertEquals(1, f.vehicle.consents.size)
        action.cancelAndJoin()
        f.text("계속 졸려")
        assertEquals(1, f.vehicle.consents.size)
    }
    @Test fun restingIgnoresConditionAndDoesNotRunVehicleOrMediaActions() = runTest {
        val f = Fixture(this); f.driver.onRestStarted(); f.text("너무 졸려")
        assertEquals(DriverState.RESTING, f.state.driver.state)
        assertTrue(f.voice.prompts.isEmpty())
        assertEquals(0, f.vehicle.proposals)
        assertFalse("mediaStop" in f.events)
    }
    @Test fun negativeOrFutureTimestampCannotMutateState() = runTest {
        val f = Fixture(this)
        for (stamp in listOf(-1L, 100L)) {
            try { f.coordinator.onConditionAnswer(RecognitionResult.NoResponse, stamp); fail("invalid time accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertNull(f.state.driver.state)
        assertTrue(f.events.isEmpty())
    }
    @Test fun escalationHookRejectsErrorsAndSingleSilence() = runTest {
        val f = Fixture(this)
        f.driver.onRepeatedNoResponse(); assertNull(f.driver.status.value.state)
        f.input(RecognitionResult.NoResponse); f.driver.onRepeatedNoResponse()
        assertNull(f.driver.status.value.state)
        f.input(RecognitionResult.Timeout); f.driver.onRepeatedNoResponse()
        assertNull(f.driver.status.value.state)
    }
    @Test fun consentDeadlineIsBoundedAndDoesNotCountAsConditionSilence() = runTest {
        val f = Fixture(this); f.voice.gate = CompletableDeferred()
        f.text("졸려")
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(FailureReason.TIMEOUT, f.state.failure)
        assertEquals(0, f.state.driver.consecutiveNoResponses)
        assertTrue(f.vehicle.consents.isEmpty())
    }
    @Test fun vehicleSecurityExceptionIsPermissionFailureNotSuccessfulAssist() = runTest {
        val f = Fixture(this); f.vehicle.proposeException = SecurityException("not authorized")
        f.text("졸려")
        assertEquals(FailureReason.PERMISSION_DENIED, f.state.failure)
        assertTrue(f.vehicle.consents.isEmpty())
        assertTrue(f.voice.prompts.any { "휴식" in it })
    }
    @Test fun stateChangeDuringConsentInvalidatesPendingVehicleAction() = runTest {
        val f = Fixture(this); f.voice.onListen = { f.driver.onRestStarted() }
        f.text("졸려")
        assertTrue(f.vehicle.consents.isEmpty())
        assertEquals(FailureReason.STALE_REQUEST, f.state.failure)
    }
    @Test fun invalidProposalCannotRequestConsentOrApply() = runTest {
        val f = Fixture(this)
        f.vehicle.proposed = OperationResult.Success(VehicleAssistProposal("", VehicleAssist.CLIMATE, "변경"))
        f.text("졸려")
        assertEquals(FailureReason.INVALID_REQUEST, f.state.failure)
        assertEquals(0, f.voice.listens)
        assertTrue(f.vehicle.consents.isEmpty())
    }
    @Test fun manualOverrideFailureAlsoBlocksNewAutomaticAssistEpisode() = runTest {
        val f = Fixture(this)
        f.vehicle.applied = OperationResult.Failure(FailureReason.MANUAL_OVERRIDE)
        f.text("졸려"); f.text("괜찮아"); f.text("괜찮아"); f.text("졸려")
        assertTrue(f.state.manualOverride)
        assertEquals(1, f.vehicle.proposals)
        assertEquals(1, f.vehicle.consents.size)
    }
}
