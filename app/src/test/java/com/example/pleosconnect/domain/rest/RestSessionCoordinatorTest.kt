package com.example.pleosconnect.domain.rest

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class RestSessionCoordinatorTest {
    private class Fixture(val scheduler: TestCoroutineScheduler) {
        var parking = SafeParking.VERIFIED
        val clock = MonotonicClock { scheduler.currentTime + 10 }
        val parked = SafeParkingCheck { parking }
        val driver = DefaultDriverStateManager(clock, parked)
        val foreground = MutableStateFlow(true)
        val route = MutableStateFlow<RouteProgress?>(RouteProgress("route", 1, 60.minutes, 0))
        var requests = 0
        var consent: DriverConsent? = null
        var navResult: OperationResult<Unit> = OperationResult.Success(Unit)
        val nav = object : NavigationController {
            override val route = this@Fixture.route
            override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit> = error("B only")
            override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent): OperationResult<Unit> = error("Phase 6 only")
            override suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit> {
                requests++; this@Fixture.consent = consent; return navResult
            }
        }
        val answers = ArrayDeque<RecognitionResult>()
        val prompts = mutableListOf<String>()
        var listenGate: CompletableDeferred<Unit>? = null
        var speechGate: CompletableDeferred<Unit>? = null
        var onListen: () -> Unit = {}
        var stops = 0
        var cleanupFails = false
        var speechResult: OperationResult<Unit> = OperationResult.Success(Unit)
        val voice = object : VoiceService {
            override val phase = MutableStateFlow(VoicePhase.IDLE)
            override suspend fun speak(text: String): OperationResult<Unit> { prompts += text; speechGate?.await(); return speechResult }
            override suspend fun listen(timeout: Duration): RecognitionResult { onListen(); listenGate?.await(); return answers.removeFirstOrNull() ?: RecognitionResult.NoResponse }
            override suspend fun stop() { stops++; if (cleanupFails) error("cleanup") }
            override suspend fun close() = error("Shared voice")
        }
        var checkpoint: RestCheckpoint? = null
        val store = object : RestSessionStore {

            override fun load() = checkpoint
            override fun save(value: RestCheckpoint?) { checkpoint = value }
        }
        var owned = false
        val owner = RestVoiceOwner { block -> check(!owned); owned = true; try { block() } finally { owned = false } }
        fun create() = RestSessionCoordinator(clock, parked, driver, nav, voice, foreground, owner, store)
        val c = create()
        fun answer(vararg text: String) { answers.addAll(text.map { RecognitionResult.Text(it) }) }
        suspend fun expire() { assertTrue(c.start(1)); scheduler.advanceTimeBy(60_000); c.tick() }
    }
    @Test fun unverifiedParkingBlocksTimerAndVoice() = runTest {
        for (parking in listOf(SafeParking.UNKNOWN, SafeParking.NOT_PARKED)) {
            val f = Fixture(testScheduler); f.parking = parking
            assertFalse(f.c.start(20)); f.c.command(); assertTrue(f.prompts.isEmpty()); assertNull(f.driver.status.value.state)
        }
    }
    @Test fun voiceSetsTimerAndRemainingAndExtension() = runTest {
        val f = Fixture(testScheduler); f.answer("20분"); f.c.command()
        assertEquals(DriverState.RESTING, f.driver.status.value.state)
        advanceTimeBy(60_000); f.answer("남은 시간"); f.c.command()
        assertTrue(f.prompts.last().contains("19분"))
        f.answer("5분 연장"); f.c.command(); assertEquals(24 * 60_000L, f.c.status.value.remainingMillis)
    }
    @Test fun cancelTimerRequiresRecoveryAndDoesNotNavigate() = runTest {
        val f = Fixture(testScheduler); f.c.start(1); f.answer("취소"); f.c.command()
        assertEquals(DriverState.RECOVERY_CHECK, f.driver.status.value.state); assertEquals(0, f.requests)
        advanceTimeBy(120_000); f.c.tick(); assertEquals(0, f.requests)
    }
    @Test fun expiryNeverAutomaticallyNormalOrResume() = runTest {
        val f = Fixture(testScheduler); f.expire()
        assertEquals(DriverState.RECOVERY_CHECK, f.driver.status.value.state)
        assertEquals(RestPhase.NO_RESPONSE, f.c.status.value.phase); assertEquals(0, f.requests)
        val size = f.prompts.size; f.c.tick(); assertEquals(size, f.prompts.size)
    }
    @Test fun recoveryNeedsTwoAnswersThenSeparateConsent() = runTest {
        val f = Fixture(testScheduler); f.answer("괜찮아", "안 졸려"); f.expire()
        assertEquals(RestPhase.READY_TO_RESUME, f.c.status.value.phase); assertEquals(0, f.requests)
        f.answer("네"); f.c.resume(); f.c.resume()
        assertEquals(1, f.requests); assertEquals(ConsentScope.RESUME_ROUTE, f.consent?.scope)
        assertNull(f.checkpoint); assertEquals(RestPhase.RESUMED, f.c.status.value.phase)
    }
    @Test fun negativeFatigueAnswersStillRequireTwoChecksAndSeparateResumeConsent() = runTest {
        val f = Fixture(testScheduler); f.answer("아니요", "아니"); f.expire()
        assertEquals(RestPhase.READY_TO_RESUME, f.c.status.value.phase)
        assertEquals(0, f.requests)
        f.answer("아니요"); f.c.resume()
        assertEquals(RestPhase.DECLINED, f.c.status.value.phase); assertEquals(0, f.requests)
    }
    @Test fun oneNormalThenSilenceDoesNotResume() = runTest {
        val f = Fixture(testScheduler); f.answer("괜찮아"); f.expire(); f.c.resume()
        assertEquals(0, f.requests); assertEquals(DriverState.RECOVERY_CHECK, f.driver.status.value.state)
    }
    @Test fun fatigueAndAmbiguousAnswersRemainUnfit() = runTest {
        for (text in listOf("계속 졸려", "너무 졸려", "모르겠어", "괜찮지만 졸려")) {
            val f = Fixture(testScheduler); f.answer(text); f.expire(); f.c.resume()
            assertNotEquals(RestPhase.READY_TO_RESUME, f.c.status.value.phase); assertEquals(0, f.requests)
        }
    }
    @Test fun recognitionErrorsAreDistinctAndNotSleep() = runTest {
        for (answer in listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied, RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable)) {
            val f = Fixture(testScheduler); f.answers += answer; f.expire()
            assertEquals(answer, f.c.status.value.answer)
            assertEquals(DriverState.RECOVERY_CHECK, f.driver.status.value.state)
            assertEquals(if (answer == RecognitionResult.NoResponse) 1 else 0, f.driver.status.value.consecutiveNoResponses)
            assertEquals(0, f.requests)
        }
    }
    @Test fun declineAndAmbiguousConsentNeverNavigate() = runTest {
        for (answer in listOf("아니", "글쎄", "네 아니")) {
            val f = Fixture(testScheduler); f.answer("괜찮아", "괜찮아"); f.expire(); f.answer(answer); f.c.resume()
            assertEquals(0, f.requests)
        }
    }
    @Test fun rerouteDuringConsentInvalidatesRequest() = runTest {
        val f = Fixture(testScheduler); f.answer("괜찮아", "괜찮아"); f.expire()
        f.onListen = { f.route.value = f.route.value!!.copy(revision = 2) }; f.answer("네"); f.c.resume()
        assertEquals(0, f.requests); assertEquals(FailureReason.STALE_REQUEST, f.c.status.value.failure)
    }
    @Test fun failedResumeIsNotAutomaticallyRetried() = runTest {
        val f = Fixture(testScheduler); f.answer("괜찮아", "괜찮아"); f.expire()
        f.navResult = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE); f.answer("네"); f.c.resume(); f.c.resume()
        assertEquals(1, f.requests); assertEquals(RestPhase.UNCERTAIN, f.c.status.value.phase)
    }
    @Test fun backgroundDefersAlarmUntilExplicitForegroundTick() = runTest {
        val f = Fixture(testScheduler); f.c.start(1); f.foreground.value = false
        advanceTimeBy(60_000); f.c.tick(); assertTrue(f.prompts.isEmpty())
        assertFalse(f.checkpoint!!.alarmConsumed)
        f.foreground.value = true; f.c.onForeground(); assertTrue(f.prompts.isEmpty())
        f.c.tick(); assertEquals(1, f.prompts.count { it.contains("끝났습니다") })
    }
    @Test fun restoreFreezesRemainingAndRequiresParking() = runTest {
        val f = Fixture(testScheduler); f.c.start(20); advanceTimeBy(60_000); f.c.tick()
        advanceTimeBy(100_000); val restored = f.create(); f.parking = SafeParking.UNKNOWN
        assertFalse(restored.restore()); f.parking = SafeParking.VERIFIED; assertTrue(restored.restore())
        assertEquals(19 * 60_000L, restored.status.value.remainingMillis)
    }
    @Test fun consumedAlarmNeverReplaysAfterRecreation() = runTest {
        val f = Fixture(testScheduler); f.expire(); val restored = f.create()
        assertTrue(restored.restore()); val count = f.prompts.size; restored.tick()
        assertEquals(count, f.prompts.size); assertEquals(RestPhase.RECOVERY, restored.status.value.phase)
    }
    @Test fun unconsumedExpiredAlarmSurvivesRecreation() = runTest {
        val f = Fixture(testScheduler); f.c.start(1); f.foreground.value = false
        advanceTimeBy(60_000); f.c.tick(); f.foreground.value = true
        val restored = f.create(); assertTrue(restored.restore()); restored.tick()
        assertEquals(1, f.prompts.count { it.contains("끝났습니다") })
    }
    @Test fun foregroundLossCancelsListeningAndStopsVoice() = runTest {
        val f = Fixture(testScheduler); f.listenGate = CompletableDeferred()
        val job = launch { f.c.command() }; runCurrent(); f.foreground.value = false
        advanceTimeBy(100); runCurrent(); job.join(); assertEquals(1, f.stops); assertFalse(f.owned)
    }
    @Test fun cancelWaitsForVoiceAndDoesNotCancelTimer() = runTest {
        val f = Fixture(testScheduler); f.c.start(20); f.listenGate = CompletableDeferred()
        val job = launch { f.c.command() }; runCurrent(); f.c.cancelVoice(); job.join()
        assertEquals(1, f.stops); assertEquals(RestPhase.RUNNING, f.c.status.value.phase)
    }
    @Test fun parkingLossCancelsListening() = runTest {
        val f = Fixture(testScheduler); f.listenGate = CompletableDeferred()
        val job = launch { f.c.command() }; runCurrent(); f.parking = SafeParking.NOT_PARKED
        advanceTimeBy(100); runCurrent(); job.join(); assertEquals(1, f.stops)
    }
    @Test fun ttsCompletesBeforeListeningAndTimeoutIsNotSilence() = runTest {
        val f = Fixture(testScheduler); f.speechGate = CompletableDeferred()
        var listens = 0; f.onListen = { listens++ }
        val job = launch { f.c.command() }; runCurrent(); assertEquals(0, listens)
        advanceTimeBy(30_000); runCurrent(); job.join()
        assertEquals(FailureReason.TIMEOUT, f.c.status.value.failure); assertEquals(0, listens)
    }
    @Test fun duplicateCommandsDoNotOverlapVoice() = runTest {
        val f = Fixture(testScheduler); f.listenGate = CompletableDeferred()
        val first = launch { f.c.command() }; runCurrent(); f.c.command()
        assertEquals(1, f.prompts.size); f.c.cancelVoice(); first.join()
    }
    @Test fun invalidDurationsAndDuplicateStartsRejected() = runTest {
        val f = Fixture(testScheduler); assertFalse(f.c.start(0)); assertFalse(f.c.start(181))
        assertTrue(f.c.start(180)); assertFalse(f.c.start(1)); assertFalse(f.c.extend(1))
    }
    @Test fun expiredSilenceCanBeExtendedWithoutDeclaringRecovery() = runTest {
        val f = Fixture(testScheduler); f.expire(); assertTrue(f.c.extend(5))
        assertEquals(DriverState.RESTING, f.driver.status.value.state); assertEquals(300_000L, f.c.status.value.remainingMillis)
    }
    @Test fun staleNormalNeverAuthorizesResume() = runTest {
        val f = Fixture(testScheduler); f.answer("괜찮아", "괜찮아"); f.expire()
        advanceTimeBy(60_001); f.answer("네"); f.c.resume(); assertEquals(0, f.requests)
    }
    @Test fun cleanupFailureIsNotClearedByForeground() = runTest {
        val f = Fixture(testScheduler); f.cleanupFails = true; f.answer("20분"); f.c.command()
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.c.status.value.failure)
        val size = f.prompts.size; f.c.onForeground(); f.c.command(); assertEquals(size, f.prompts.size)
    }
    @Test fun idleCancellationCannotCreateRecoverySession() = runTest {
        val f = Fixture(testScheduler); f.c.cancelTimer(); f.c.checkRecovery()
        assertEquals(RestPhase.IDLE, f.c.status.value.phase); assertTrue(f.prompts.isEmpty())
    }
    @Test fun durationGrammarDoesNotGuessAmbiguity() {
        assertEquals(20, RestSessionCoordinator.parseMinutes("이십분"))
        assertEquals(5, RestSessionCoordinator.parseMinutes("5분연장해줘"))
        for (text in listOf("0분", "181분", "20분아니10분", "대충", "-1분")) assertNull(RestSessionCoordinator.parseMinutes(text))
    }
    @Test fun fileStoreRoundTripAndCorruptionAndClear() {
        val dir = Files.createTempDirectory("rest-test").toFile()
        try {
            val file = java.io.File(dir, "session"); val store = FileRestSessionStore(file)
            assertNull(store.load()); store.save(RestCheckpoint(1234, false))
            assertEquals(RestCheckpoint(1234, false), FileRestSessionStore(file).load())
            store.save(RestCheckpoint(0, true)); assertEquals(RestCheckpoint(0, true), store.load())
            file.writeText("bad"); assertNull(store.load()); store.save(null); assertFalse(file.exists())
        } finally { dir.deleteRecursively() }
    }
}
