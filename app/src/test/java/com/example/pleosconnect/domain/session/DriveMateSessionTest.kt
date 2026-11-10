package com.example.pleosconnect.domain.session

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import com.example.pleosconnect.domain.rest.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class DriveMateSessionTest {
    private class Fixture(val scope: CoroutineScope, val time: TestCoroutineScheduler) {
        var parking = SafeParking.NOT_PARKED
        val route = MutableStateFlow<RouteProgress?>(RouteProgress("trip", 1, 60.minutes, 0))
        val profile = MutableStateFlow<DriverPreferences?>(DriverPreferences("A", "테스트"))
        val answers = ArrayDeque<RecognitionResult>()
        val prompts = mutableListOf<String>()
        val events = mutableListOf<String>()
        val mediaCommands = mutableListOf<MediaCommand>()
        var listening = false
        var speaking = false
        var gate: CompletableDeferred<Unit>? = null
        var stops = 0
        var searches = 0
        var waypoints = 0
        var resumes = 0
        var assists = 0
        var saved = 0
        var cleanupFails = false
        var speechFailure: FailureReason? = null
        var speechGate: CompletableDeferred<Unit>? = null
        var onListen: () -> Unit = {}
        val phase = MutableStateFlow(VoicePhase.IDLE)
        val voice = object : VoiceService {
            override val phase = this@Fixture.phase
            override suspend fun speak(text: String): OperationResult<Unit> {
                check(!speaking && !listening); speaking = true; phase.value = VoicePhase.SPEAKING
                events += "speak"; prompts += text
                try { speechGate?.await(); return speechFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit) }
                finally { speaking = false; phase.value = VoicePhase.IDLE; events += "ttsDone" }
            }
            override suspend fun listen(timeout: Duration): RecognitionResult {
                check(!speaking && !listening); listening = true; phase.value = VoicePhase.LISTENING; events += "listen"
                try { onListen(); gate?.await(); return answers.removeFirstOrNull() ?: RecognitionResult.NoResponse }
                finally { listening = false; phase.value = VoicePhase.IDLE }
            }
            override suspend fun stop() { stops++; events += "stop"; if (cleanupFails) error("cleanup") }
            override suspend fun close() = error("App owner closes shared voice")
        }
        val nav = object : NavigationController {
            override val route = this@Fixture.route
            override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit> = error("B only")
            override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent): OperationResult<Unit> {
                assertEquals(ConsentScope.REST_WAYPOINT, consent.scope); waypoints++
                return OperationResult.Success(Unit)
            }
            override suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit> {
                assertEquals(ConsentScope.RESUME_ROUTE, consent.scope); resumes++; return OperationResult.Success(Unit)
            }
        }
        val vehicle = object : VehicleController {
            override val manualOverrides = MutableSharedFlow<VehicleAdjustment>()
            override suspend fun propose(assist: VehicleAssist) = OperationResult.Success(VehicleAssistProposal("assist", assist, "환기할까요?"))
            override suspend fun apply(proposal: VehicleAssistProposal, consent: DriverConsent): OperationResult<VehicleAdjustment> {
                assists++; return OperationResult.Success(VehicleAdjustment("adjustment"))
            }
            override suspend fun restore(adjustment: VehicleAdjustment, consent: DriverConsent): OperationResult<Unit> = error("No automatic restoration")
        }
        var checkpoint: RestCheckpoint? = null
        val session = DriveMateSession(scope, voice, nav,
            object : RestStopFinder { override suspend fun find(request: RestStopSearch): OperationResult<List<RestStopCandidate>> {
                searches++; return OperationResult.Success(listOf(RestStopCandidate("rest", "테스트 휴게소")))
            } }, vehicle,
            object : MediaController { override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
                mediaCommands += command; return OperationResult.Success(Unit)
            } },
            object : DriverProfileRepository {
                override val selectedProfile = profile
                override suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit> {
                    saved++; profile.value = preferences; return OperationResult.Success(Unit)
                }
            }, SafeParkingCheck { parking }, MonotonicClock { time.currentTime + 10 },
            object : RestSessionStore { override fun load() = checkpoint; override fun save(value: RestCheckpoint?) { checkpoint = value } })
        fun answer(vararg values: String) { answers.addAll(values.map { RecognitionResult.Text(it) }) }
    }
    @Test fun fullFakeTripFromDepartureThroughRestAndExplicitResume() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.answer("괜찮아", "조용히"); f.session.initialCheck()
        assertEquals(DriverState.NORMAL, f.session.driverStatus.value.state)
        assertEquals(DrivingMode.QUIET, f.session.modeStatus.value.mode)
        val size = f.prompts.size; f.session.tick(); assertEquals(size, f.prompts.size)
        advanceTimeBy(20 * 60_000); f.answer("조금 피곤해", "네"); f.session.tick()
        assertEquals(DriverState.CAUTION, f.session.driverStatus.value.state); assertEquals(1, f.assists)
        advanceTimeBy(5 * 60_000); f.answer("계속 졸려", "네"); f.session.tick()
        assertEquals(DriverState.WARNING, f.session.driverStatus.value.state)
        assertEquals(1, f.searches); assertEquals(1, f.waypoints)
        f.answer("20분"); f.session.restCommand(); assertNotEquals(DriverState.RESTING, f.session.driverStatus.value.state)
        f.parking = SafeParking.VERIFIED; f.answer("20분"); f.session.restCommand()
        assertEquals(DriverState.RESTING, f.session.driverStatus.value.state); assertTrue(f.session.pendingChecks.value.isEmpty())
        // The rejected parking command never listened; remove its unused scripted answer.
        f.answers.clear(); f.answer("괜찮아", "괜찮아")
        advanceTimeBy(20 * 60_000); f.session.tick()
        assertEquals(RestPhase.READY_TO_RESUME, f.session.restStatus.value.phase); assertEquals(0, f.resumes)
        assertEquals(DriverState.NORMAL, f.session.safetyStatus.value.driver.state)
        f.answer("네"); f.session.resumeAfterRest(); f.session.resumeAfterRest()
        assertEquals(1, f.resumes); assertNull(f.checkpoint)
        assertTrue(f.mediaCommands.contains(MediaCommand.Stop))
        f.session.close()
    }
    @Test fun severeAnswerImmediatelySearchesWithoutWaitingForSchedule() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.answer("너무 졸려", "아니"); f.session.initialCheck()
        assertEquals(DriverState.HIGH_RISK, f.session.driverStatus.value.state)
        assertEquals(1, f.searches); assertEquals(0, f.waypoints); f.session.close()
    }
    @Test fun safetyPreemptsCompanionAndModeChangeCancelsDialogue() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.answer("괜찮아", "말동무"); f.session.initialCheck()
        f.gate = CompletableDeferred(); val chat = launch { f.session.companionChat() }; runCurrent()
        assertTrue(f.listening)
        f.gate = null; f.answer("너무 졸려", "아니"); f.session.conditionCheck(); chat.join()
        assertEquals(DriverState.HIGH_RISK, f.session.driverStatus.value.state); assertFalse(f.listening)
        f.session.close()
    }
    @Test fun backgroundCancelsAndDoesNotAutomaticallyResume() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.gate = CompletableDeferred(); val work = launch { f.session.initialCheck() }; runCurrent()
        f.session.onBackground(); work.join(); val size = f.prompts.size
        f.session.initialCheck(); assertEquals(size, f.prompts.size)
        f.session.onForeground(); runCurrent(); assertEquals(size, f.prompts.size)
        assertFalse(f.listening); f.session.close()
    }
    @Test fun profileChangePermanentlyInvalidatesSession() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.gate = CompletableDeferred(); val work = launch { f.session.initialCheck() }; runCurrent()
        f.profile.value = DriverPreferences("B", "다른 사용자"); runCurrent(); work.join()
        val size = f.prompts.size; f.session.onForeground(); f.session.initialCheck()
        assertEquals(size, f.prompts.size); assertFalse(f.listening); f.session.close()
    }
    @Test fun sttFailureAndSilenceAreNotConfirmedSleep() = runTest {
        for (answer in listOf(RecognitionResult.Timeout, RecognitionResult.PermissionDenied, RecognitionResult.ServiceUnavailable, RecognitionResult.NoResponse)) {
            val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answers += answer; f.session.initialCheck()
            assertEquals(ConditionReport.UNCONFIRMED, f.session.driverStatus.value.report)
            assertNotEquals(DriverState.HIGH_RISK, f.session.driverStatus.value.state)
            assertEquals(if (answer == RecognitionResult.NoResponse) 1 else 0, f.session.driverStatus.value.consecutiveNoResponses)
            assertEquals(0, f.searches); f.session.close()
        }
    }
    @Test fun cancelledRouteNeverTriggersAnotherQuestion() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "조용히"); f.session.initialCheck()
        f.route.value = null; runCurrent(); val count = f.prompts.size
        advanceTimeBy(30 * 60_000); f.session.tick(); assertEquals(count, f.prompts.size); f.session.close()
    }
    @Test fun cleanupFailureBlocksFurtherWork() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.cleanupFails = true
        f.answer("괜찮아", "조용히"); f.session.initialCheck(); val count = f.prompts.size
        f.session.initialCheck(); assertEquals(count, f.prompts.size); f.session.close()
    }
    @Test fun modeSwitchPreemptsCompanionWithoutOverlappingVoice() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "말동무"); f.session.initialCheck()
        f.gate = CompletableDeferred(); val chat = launch { f.session.companionChat() }; runCurrent()
        f.session.modeCommand("오늘은 조용히"); chat.join()
        assertEquals(DrivingMode.QUIET, f.session.modeStatus.value.mode); assertFalse(f.listening); f.session.close()
    }
    @Test fun duePeriodicSafetyCheckPreemptsCompanion() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "말동무"); f.session.initialCheck()
        advanceTimeBy(20 * 60_000 - 10)
        f.gate = CompletableDeferred(); val chat = launch { f.session.companionChat() }; runCurrent()
        f.gate = null; f.answer("조금 피곤해", "아니"); advanceTimeBy(20); f.session.tick(); chat.join()
        assertEquals(DriverState.CAUTION, f.session.driverStatus.value.state); f.session.close()
    }
    @Test fun initialQuestionIsNotRepeatedForSameTrip() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "조용히"); f.session.initialCheck()
        val count = f.prompts.size; f.session.initialCheck(); assertEquals(count, f.prompts.size); f.session.close()
    }
    @Test fun companionFatigueRequestsConditionConfirmation() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "말동무"); f.session.initialCheck()
        f.answer("너무 졸려", "너무 졸려", "아니"); f.session.companionChat()
        assertEquals(DriverState.HIGH_RISK, f.session.driverStatus.value.state); assertEquals(1, f.searches); f.session.close()
    }
    @Test fun ttsCancellationNeverStartsListening() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.speechGate = CompletableDeferred()
        val task = launch { f.session.initialCheck() }; runCurrent(); assertTrue(f.speaking)
        f.session.onBackground(); task.join(); assertFalse(f.events.contains("listen")); assertFalse(f.speaking); f.session.close()
    }
    @Test fun cancelledRouteCancelsPendingMicrophoneUse() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.gate = CompletableDeferred()
        val task = launch { f.session.initialCheck() }; runCurrent(); f.route.value = null; runCurrent(); task.join()
        assertFalse(f.listening); f.session.close()
    }
    @Test fun repeatedSilenceEscalatesButTimeoutDoesNotCountAsSilence() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answers += RecognitionResult.NoResponse; f.session.initialCheck()
        f.answers += RecognitionResult.Timeout; f.session.conditionCheck()
        assertEquals(0, f.session.driverStatus.value.consecutiveNoResponses)
        f.answers += RecognitionResult.NoResponse; f.session.conditionCheck()
        f.answers += RecognitionResult.NoResponse; f.answer("아니"); f.session.conditionCheck()
        assertEquals(DriverState.HIGH_RISK, f.session.driverStatus.value.state)
        assertEquals(ConditionReport.UNCONFIRMED, f.session.driverStatus.value.report); f.session.close()
    }
    @Test fun closeIsIdempotentAndPermanentlyBlocksFurtherSpeech() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.session.close(); f.session.close()
        f.session.onForeground(); f.session.initialCheck(); assertTrue(f.prompts.isEmpty())
    }
    @Test fun parentScopeCancellationCancelsActiveListening() = runTest {
        val parent = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val f = Fixture(parent, testScheduler); runCurrent(); f.gate = CompletableDeferred()
        val task = launch { f.session.initialCheck() }; runCurrent(); parent.cancel(); runCurrent(); task.join()
        assertFalse(f.listening); f.session.close()
    }
    private suspend fun TestScope.verifyQuestionAfterRest(newRevision: Boolean) {
        val f = Fixture(backgroundScope, testScheduler); runCurrent()
        f.answer("괜찮아", "조용히"); f.session.initialCheck()
        f.parking = SafeParking.VERIFIED; f.answer("1분"); f.session.restCommand()
        assertTrue(f.session.pendingChecks.value.isEmpty())
        advanceTimeBy(30_000)
        if (newRevision) {
            f.route.value = f.route.value!!.copy(revision = 2, remaining = 40.minutes, observedAtMillis = currentTime + 10)
            runCurrent()
        }
        assertTrue(f.session.pendingChecks.value.isEmpty())
        advanceTimeBy(30_000); f.answer("괜찮아", "괜찮아"); f.session.tick()
        assertEquals(RestPhase.READY_TO_RESUME, f.session.restStatus.value.phase)
        assertTrue(f.session.pendingChecks.value.isEmpty()); assertEquals(0, f.resumes)
        f.answer("네"); f.session.resumeAfterRest(); runCurrent()
        assertEquals(1, f.resumes)
        val pending = f.session.pendingChecks.value
        assertTrue("Resume must restore periodic questions", pending.isNotEmpty())
        assertTrue(pending.all { it.kind == CheckKind.PERIODIC && it.dueAtMillis > currentTime + 10 })
        val count = f.prompts.size
        f.session.resumeAfterRest(); f.session.tick()
        assertEquals(count, f.prompts.size); assertEquals(pending, f.session.pendingChecks.value)
        val confirmed = f.session.driverStatus.value.lastConfirmedAtMillis!!
        advanceTimeBy(pending.first().dueAtMillis - (currentTime + 10))
        f.answer("괜찮아"); f.session.tick()
        assertEquals(count + 1, f.prompts.size)
        assertTrue(f.prompts.last().contains("졸리거나 피곤"))
        assertTrue(f.session.driverStatus.value.lastConfirmedAtMillis!! > confirmed)
        f.session.tick(); assertEquals(count + 1, f.prompts.size)
        f.session.close()
    }
    @Test fun sameRouteAndRevisionAskAgainAfterRest() = runTest { verifyQuestionAfterRest(false) }
    @Test fun sameRouteWithNewRevisionDuringRestAsksAgain() = runTest { verifyQuestionAfterRest(true) }
    @Test fun cancelledRouteDuringRestNeverRecreatesQuestions() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.answer("괜찮아", "조용히"); f.session.initialCheck()
        f.parking = SafeParking.VERIFIED; f.answer("1분"); f.session.restCommand()
        f.route.value = null; runCurrent()
        advanceTimeBy(60_000); f.answer("괜찮아", "괜찮아"); f.session.tick()
        f.session.resumeAfterRest(); assertEquals(0, f.resumes); assertTrue(f.session.pendingChecks.value.isEmpty())
        val count = f.prompts.size; advanceTimeBy(30 * 60_000); f.session.tick()
        assertEquals(count, f.prompts.size); f.session.close()
    }
    @Test fun ordinaryTickDoesNotInterruptSafetyDialogue() = runTest {
        val f = Fixture(backgroundScope, testScheduler); runCurrent(); f.gate = CompletableDeferred()
        val check = launch { f.session.initialCheck() }; runCurrent(); val count = f.prompts.size
        f.session.tick(); assertEquals(count, f.prompts.size); assertTrue(f.listening)
        f.session.cancel(); check.join(); f.session.close()
    }
}
