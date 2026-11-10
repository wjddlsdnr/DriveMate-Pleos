package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.integration.DriverProfileRepository
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class LimitedCompanionControllerTest {
    private class Fixture {
        val safety = MutableStateFlow(SafetyResponse(DriverStatus(state = DriverState.NORMAL,
            report = ConditionReport.NORMAL, followUpRequired = false)))
        val mode = MutableStateFlow(NormalModeStatus(NormalModePhase.READY, DrivingMode.COMPANION))
        val foreground = MutableStateFlow(true)
        val profile = MutableStateFlow<DriverPreferences?>(DriverPreferences("test-A", "테스트"))
        val profiles = object : DriverProfileRepository {
            override val selectedProfile = profile
            override suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit> = error("No preference storage in companion")
        }
        val prompts = mutableListOf<String>()
        val events = mutableListOf<String>()
        val answers = ArrayDeque<RecognitionResult>(listOf(RecognitionResult.Text("음악"), RecognitionResult.Text("재즈")))
        var speechFailure: FailureReason? = null
        var speechDelay = 0L
        var listenDelay = 0L
        var speechGate: CompletableDeferred<Unit>? = null
        var listenGate: CompletableDeferred<RecognitionResult>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var speechException: Exception? = null
        var onListen: () -> Unit = {}
        var onSpeak: () -> Unit = {}
        var cleanupFails = false
        var listens = 0
        var stops = 0
        val voicePhase = MutableStateFlow(VoicePhase.IDLE)
        val voice = object : VoiceService {
            override val phase = voicePhase
            override suspend fun speak(text: String): OperationResult<Unit> {
                events += "speak"; prompts += text; onSpeak()
                speechException?.let { throw it }
                speechGate?.await(); delay(speechDelay)
                return speechFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
            }
            override suspend fun listen(timeout: Duration): RecognitionResult {
                events += "listen"; listens++; onListen(); delay(listenDelay)
                return listenGate?.await() ?: answers.removeFirstOrNull() ?: RecognitionResult.NoResponse
            }
            override suspend fun stop() {
                events += "stopBegin"; stops++; stopGate?.await()
                if (cleanupFails) error("cleanup failed")
                events += "stopDone"
            }
            override suspend fun close() = error("Shared voice must not be permanently closed")
        }
        val checks = mutableListOf<CheckKind>()
        var onCheck: () -> Unit = {}
        val controller = LimitedCompanionController(safety, mode, profiles, foreground, voice) {
            events += "conditionCheck"; checks += it; onCheck()
        }
        fun danger() { safety.value = SafetyResponse(DriverStatus(state = DriverState.HIGH_RISK, report = ConditionReport.SEVERE), restStopNeeded = true) }
    }

    @Test fun musicConversationHasTwoAnswersAndFixedEnding() = runTest {
        val f = Fixture(); f.controller.start()
        assertEquals(2, f.listens); assertEquals(3, f.prompts.size)
        assertTrue(f.prompts.last().contains("재즈"))
        assertEquals(CompanionStage.ENDED, f.controller.status.value.stage)
        assertEquals(2, f.controller.status.value.completedTurns)
        assertEquals(ConversationPhase.IDLE, f.controller.phase.value)
        assertEquals("stopDone", f.events.last())
    }
    @Test fun sceneryContextIsLocalAndDoesNotInventCurrentLocation() = runTest {
        val f = Fixture(); f.answers.clear(); f.answers.addAll(listOf(RecognitionResult.Text("풍경"), RecognitionResult.Text("바다")))
        f.controller.start(); assertTrue(f.prompts.last().contains("바다"))
        assertEquals(2, f.listens)
        assertTrue(f.prompts.none { "현재 위치" in it || "현재 날씨" in it })
    }
    @Test fun unknownFirstAnswerEndsWithoutRepeatedListening() = runTest {
        val f = Fixture(); f.answers.clear(); f.answers += RecognitionResult.Text("자유롭게 이야기해봐")
        f.controller.start(); assertEquals(1, f.listens)
        assertTrue(f.prompts.last().contains("정해진")); assertEquals(CompanionStage.ENDED, f.controller.status.value.stage)
    }
    @Test fun unknownSecondAnswerCannotExtendConversation() = runTest {
        val f = Fixture(); f.answers.clear(); f.answers.addAll(listOf(RecognitionResult.Text("음악"), RecognitionResult.Text("다른 질문")))
        f.controller.start(); assertEquals(2, f.listens); assertEquals(3, f.prompts.size)
    }
    @Test fun explicitStopEndsImmediatelyWithoutAnotherPrompt() = runTest {
        listOf("그만", "대화 그만", "조용히", "말동무 그만", "중단").forEach {
            val f = Fixture(); f.answers.clear(); f.answers += RecognitionResult.Text(it)
            f.controller.start(); assertEquals(1, f.listens); assertEquals(1, f.prompts.size)
            assertEquals(CompanionStage.ENDED, f.controller.status.value.stage)
        }
    }
    @Test fun fatigueStopsCompanionAndRequestsConditionCheckWithoutDiagnosing() = runTest {
        listOf("조금 졸려", "계속 졸려", "너무 졸려").forEach {
            val f = Fixture(); val original = f.safety.value
            f.answers.clear(); f.answers += RecognitionResult.Text(it); f.controller.start()
            assertTrue(f.controller.status.value.needsConditionCheck)
            assertEquals(original, f.safety.value); assertEquals(1, f.listens)
            assertTrue(f.checks.isEmpty()) // Upper owner performs check AFTER cleanup, never reentrantly.
        }
    }
    @Test fun allNonNormalStatesAndUnconfirmedObservationCannotStart() = runTest {
        DriverState.entries.filter { it != DriverState.NORMAL }.forEach {
            val f = Fixture(); f.safety.value = SafetyResponse(DriverStatus(state = it)); f.controller.start()
            assertTrue(f.prompts.isEmpty()); assertEquals(0, f.listens); assertEquals(0, f.stops)
        }
        val f = Fixture(); f.safety.value = f.safety.value.copy(driver = f.safety.value.driver.copy(report = ConditionReport.UNCONFIRMED))
        f.controller.start(); assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage)
    }
    @Test fun companionModeMustBeExplicitAndReady() = runTest {
        DrivingMode.entries.filter { it != DrivingMode.COMPANION }.forEach {
            val f = Fixture(); f.mode.value = NormalModeStatus(NormalModePhase.READY, it); f.controller.start()
            assertTrue(f.prompts.isEmpty())
        }
        val f = Fixture(); f.mode.value = NormalModeStatus(NormalModePhase.ASKING, DrivingMode.COMPANION)
        f.controller.start(); assertEquals(0, f.listens)
    }
    @Test fun backgroundAtStartupCannotUseMicrophone() = runTest {
        val f = Fixture(); f.foreground.value = false; f.controller.start()
        assertTrue(f.prompts.isEmpty()); assertEquals(0, f.listens)
    }
    @Test fun dangerDuringTtsCancelsBeforeAnyListening() = runTest {
        val f = Fixture(); f.speechGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent(); f.danger(); runCurrent(); task.join()
        assertEquals(0, f.listens); assertEquals(1, f.stops)
        assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage)
    }
    @Test fun unknownStateDuringListeningDiscardsLateReply() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        f.safety.value = f.safety.value.copy(driver = f.safety.value.driver.copy(report = ConditionReport.UNCONFIRMED))
        runCurrent(); task.join(); f.listenGate!!.complete(RecognitionResult.Text("음악")); runCurrent()
        assertEquals(1, f.prompts.size); assertEquals(1, f.stops)
    }
    @Test fun modeChangeDuringListeningEndsSession() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        f.mode.value = NormalModeStatus(NormalModePhase.READY, DrivingMode.QUIET); runCurrent(); task.join()
        assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage); assertEquals(1, f.stops)
    }
    @Test fun profileSwitchCancelsOldUserDialogue() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        f.profile.value = DriverPreferences("test-B", "다른 테스트"); runCurrent(); task.join()
        assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage); assertEquals(1, f.prompts.size)
    }
    @Test fun backgroundTransitionStopsAndForegroundDoesNotAutoResume() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        f.foreground.value = false; runCurrent(); task.join()
        val count = f.prompts.size; f.foreground.value = true; runCurrent()
        assertEquals(count, f.prompts.size); assertEquals(1, f.stops)
    }
    @Test fun explicitLifecycleLatchRequiresForegroundReleaseAndNewStart() = runTest {
        val f = Fixture(); f.controller.onBackground(); f.controller.start()
        assertEquals(0, f.listens)
        f.controller.onForeground(); runCurrent(); assertTrue(f.prompts.isEmpty())
        f.controller.start(); assertEquals(2, f.listens)
    }
    @Test fun duplicateStartsCannotOverlap() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val first = launch { f.controller.start() }; runCurrent(); f.controller.start()
        assertEquals(1, f.listens); assertEquals(1, f.prompts.size)
        first.cancelAndJoin()
    }
    @Test fun failedTtsCannotStartStt() = runTest {
        val f = Fixture(); f.speechFailure = FailureReason.SERVICE_UNAVAILABLE; f.controller.start()
        assertEquals(0, f.listens); assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.controller.status.value.failure)
    }
    @Test fun permissionExceptionIsDistinctFromSilence() = runTest {
        val f = Fixture(); f.speechException = SecurityException(); f.controller.start()
        assertEquals(FailureReason.PERMISSION_DENIED, f.controller.status.value.failure); assertEquals(0, f.listens)
    }
    @Test fun recognitionFailuresNeverRequestSleepDiagnosis() = runTest {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable, RecognitionResult.Text(" ")).forEach {
            val f = Fixture(); val original = f.safety.value; f.answers.clear(); f.answers += it; f.controller.start()
            assertFalse(f.controller.status.value.needsConditionCheck); assertEquals(original, f.safety.value)
            assertEquals(1, f.listens); assertEquals(1, f.prompts.size)
        }
    }
    @Test fun stalledListeningHasVirtualDeadline() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred(); f.controller.start()
        assertEquals(10_000L, testScheduler.currentTime); assertEquals(FailureReason.TIMEOUT, f.controller.status.value.failure)
        assertEquals(1, f.stops)
    }
    @Test fun stalledTtsHasVirtualDeadlineAndNeverListens() = runTest {
        val f = Fixture(); f.speechGate = CompletableDeferred(); f.controller.start()
        assertEquals(30_000L, testScheduler.currentTime); assertEquals(0, f.listens)
        assertEquals(FailureReason.TIMEOUT, f.controller.status.value.failure)
    }
    @Test fun entireSessionIsBoundedBySixtySeconds() = runTest {
        val f = Fixture(); f.speechDelay = 25_000; f.listenDelay = 8_000; f.controller.start()
        assertEquals(60_000L, testScheduler.currentTime)
        assertEquals(FailureReason.TIMEOUT, f.controller.status.value.failure); assertEquals(1, f.stops)
    }
    @Test fun callerCancellationWaitsForVoiceCleanup() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred(); f.stopGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent(); task.cancel(); runCurrent()
        assertFalse(task.isCompleted); assertEquals(1, f.stops)
        f.stopGate!!.complete(Unit); task.join(); assertTrue(task.isCancelled)
    }
    @Test fun safetyPriorityWaitsForCleanupAndBlocksNewCompanionStart() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred(); f.stopGate = CompletableDeferred()
        val talk = launch { f.controller.start() }; runCurrent()
        val safetyGate = CompletableDeferred<Unit>()
        val alert = launch { f.controller.withSafetyPriority { f.events += "safetySpeech"; safetyGate.await() } }
        runCurrent(); assertFalse("safetySpeech" in f.events)
        f.controller.start(); assertEquals(1, f.listens)
        f.stopGate!!.complete(Unit); runCurrent()
        assertTrue(talk.isCancelled); assertEquals("safetySpeech", f.events.last())
        assertTrue(f.events.indexOf("stopDone") < f.events.indexOf("safetySpeech"))
        f.controller.start(); assertEquals(1, f.listens)
        safetyGate.complete(Unit); alert.join()
        assertEquals(1, f.prompts.size) // No auto-resume.
    }
    @Test fun conditionCheckDelegationRunsOnlyAfterCompanionStops() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        f.controller.requestConditionCheck(CheckKind.RISK_FOLLOW_UP)
        assertTrue(task.isCancelled); assertEquals(listOf(CheckKind.RISK_FOLLOW_UP), f.checks)
        assertTrue(f.events.indexOf("stopDone") < f.events.indexOf("conditionCheck"))
    }
    @Test fun closeIsPermanentAndDoesNotCloseSharedVoice() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent(); f.controller.close(); f.controller.close()
        assertTrue(task.isCancelled); assertEquals(ConversationPhase.CLOSED, f.controller.phase.value)
        f.controller.start(); f.controller.requestConditionCheck(CheckKind.PERIODIC)
        assertEquals(1, f.listens); assertTrue(f.checks.isEmpty())
    }
    @Test fun safetyGateIsReleasedAfterFailingSafetyCallback() = runTest {
        val f = Fixture()
        try { f.controller.withSafetyPriority { error("safety callback failed") } } catch (_: IllegalStateException) { }
        f.controller.start(); assertEquals(2, f.listens)
    }
    @Test fun cleanupFailureIsVisible() = runTest {
        val f = Fixture(); f.cleanupFails = true; f.controller.start()
        assertEquals(CompanionStage.FAILED, f.controller.status.value.stage)
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.controller.status.value.failure)
        val count = f.prompts.size; f.controller.start(); assertEquals(count, f.prompts.size)
        var entered = false
        try { f.controller.withSafetyPriority { entered = true } } catch (_: IllegalStateException) { }
        assertFalse(entered)
    }
    @Test fun unavailableVoiceAfterCleanupAlsoPreventsUnsafeHandoff() = runTest {
        val f = Fixture(); f.voicePhase.value = VoicePhase.UNAVAILABLE; f.controller.start()
        var entered = false
        try { f.controller.withSafetyPriority { entered = true } } catch (_: IllegalStateException) { }
        assertFalse(entered); assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage)
    }
    @Test fun noExtraSessionCanStartWhileCancellationCleanupIsPending() = runTest {
        val f = Fixture(); f.listenGate = CompletableDeferred(); f.stopGate = CompletableDeferred()
        val old = launch { f.controller.start() }; runCurrent(); old.cancel(); runCurrent()
        f.controller.start(); assertEquals(1, f.listens)
        f.stopGate!!.complete(Unit); old.join()
    }
    @Test fun safetyJobsAreSerializedAndCanReenterPauseWithoutDeadlock() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>(); val order = mutableListOf<Int>()
        val first = launch { f.controller.withSafetyPriority { f.controller.pause(); order += 1; gate.await() } }
        runCurrent()
        val second = launch { f.controller.withSafetyPriority { order += 2 } }; runCurrent()
        assertEquals(listOf(1), order)
        gate.complete(Unit); first.join(); second.join(); assertEquals(listOf(1, 2), order)
    }
    @Test fun sttStartsOnlyAfterActualTtsCompletion() = runTest {
        val f = Fixture(); f.speechGate = CompletableDeferred()
        val task = launch { f.controller.start() }; runCurrent()
        assertEquals(0, f.listens); assertEquals(ConversationPhase.ASKING, f.controller.phase.value)
        f.speechGate!!.complete(Unit); task.join()
        assertEquals(listOf("speak", "listen", "speak", "listen", "speak", "stopBegin", "stopDone"), f.events)
    }
    @Test fun reportedFatigueBlocksNewChatUntilFreshNormalCheckCompletes() = runTest {
        val f = Fixture(); f.answers.clear(); f.answers += RecognitionResult.Text("졸려")
        f.controller.start(); val count = f.prompts.size
        f.controller.start(); assertEquals(count, f.prompts.size)
        assertTrue(f.controller.status.value.needsConditionCheck)
        f.controller.requestConditionCheck(CheckKind.RECOVERY)
        f.controller.start(); assertEquals(count, f.prompts.size) // Unchanged stale NORMAL is insufficient.
        f.onCheck = { f.safety.value = f.safety.value.copy(driver = f.safety.value.driver.copy(lastConfirmedAtMillis = 100)) }
        f.controller.requestConditionCheck(CheckKind.RECOVERY)
        assertEquals(count, f.prompts.size) // No automatic restart.
        f.answers.addAll(listOf(RecognitionResult.Text("풍경"), RecognitionResult.Text("산")))
        f.controller.start(); assertEquals(count + 3, f.prompts.size)
    }
    @Test fun busySharedVoiceIsNeitherClaimedNorStoppedByCompanion() = runTest {
        listOf(VoicePhase.SPEAKING, VoicePhase.LISTENING).forEach {
            val f = Fixture(); f.voicePhase.value = it; f.controller.start()
            assertTrue(f.prompts.isEmpty()); assertEquals(0, f.listens); assertEquals(0, f.stops)
            assertEquals(CompanionStage.BLOCKED, f.controller.status.value.stage)
        }
    }
}
