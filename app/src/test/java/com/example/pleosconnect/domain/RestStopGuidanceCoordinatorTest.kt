package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class RestStopGuidanceCoordinatorTest {
    private class Fixture(val scope: TestScope) {
        var offset = 1L
        val clock = MonotonicClock { scope.testScheduler.currentTime + offset }
        val safety = MutableStateFlow(SafetyResponse(DriverStatus(state = DriverState.HIGH_RISK, report = ConditionReport.SEVERE), restStopNeeded = true))
        val route = MutableStateFlow<RouteProgress?>(RouteProgress("trip", 2, 120.minutes, 1))
        val searches = mutableListOf<RestStopSearch>()
        val additions = mutableListOf<Triple<RestStopSearch, RestStopCandidate, DriverConsent>>()
        var findResult: OperationResult<List<RestStopCandidate>> = OperationResult.Success(listOf(
            RestStopCandidate("near", "첫 휴게소"), RestStopCandidate("far", "다음 휴게소")))
        var findGate: CompletableDeferred<Unit>? = null
        var findException: Exception? = null
        var onFind: () -> Unit = {}
        val finder = object : RestStopFinder {
            override suspend fun find(request: RestStopSearch): OperationResult<List<RestStopCandidate>> {
                searches += request; findGate?.await(); onFind()
                findException?.let { throw it }
                return findResult
            }
        }
        var addResult: OperationResult<Unit> = OperationResult.Success(Unit)
        var addGate: CompletableDeferred<Unit>? = null
        var onAdd: () -> Unit = {}
        val navigation = object : NavigationController {
            override val route = this@Fixture.route
            override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent): OperationResult<Unit> {
                additions += Triple(search, candidate, consent); onAdd(); addGate?.await(); return addResult
            }
            override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit> = error("Must preserve destination")
            override suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit> = error("No automatic resume")
        }
        val prompts = mutableListOf<String>()
        var listens = 0
        var stops = 0
        var spoken: OperationResult<Unit> = OperationResult.Success(Unit)
        var answer: RecognitionResult = RecognitionResult.Text("네")
        var listenGate: CompletableDeferred<RecognitionResult>? = null
        var speakGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var onListen: () -> Unit = {}
        val voice = object : VoiceService {
            override val phase = MutableStateFlow(VoicePhase.IDLE)
            override suspend fun speak(text: String): OperationResult<Unit> { prompts += text; speakGate?.await(); return spoken }
            override suspend fun listen(timeout: Duration): RecognitionResult { listens++; onListen(); return listenGate?.await() ?: answer }
            override suspend fun stop() { stops++; stopGate?.await() }
            override suspend fun close() = Unit
        }
        val coordinator = RestStopGuidanceCoordinator(safety, finder, navigation, voice, clock)
        val status get() = coordinator.status.value
        fun reroute() { offset++; route.value = route.value!!.copy(revision = route.value!!.revision + 1, observedAtMillis = clock.nowMillis()) }
    }

    @Test fun addsFirstBValidatedCandidateOnlyAfterExplicitConsentAndKeepsDestination() = runTest {
        val f = Fixture(this); f.coordinator.request()
        assertEquals(RestGuidancePhase.ADDED, f.status.phase)
        val (search, candidate, consent) = f.additions.single()
        assertEquals("trip", search.routeId); assertEquals(2L, search.routeRevision)
        assertTrue(search.requestId.isNotBlank())
        assertEquals("near", candidate.id)
        assertEquals(ConsentScope.REST_WAYPOINT, consent.scope)
        assertEquals(candidate.id, consent.targetId)
        assertTrue(f.prompts.single().contains("기존 목적지는 유지"))
        assertEquals(1, f.listens); assertEquals(1, f.stops)
    }
    @Test fun needlessRestNormalOrRestingNeverSearches() = runTest {
        listOf(DriverState.NORMAL, DriverState.RESTING, DriverState.CAUTION).forEach {
            val f = Fixture(this); f.safety.value = f.safety.value.copy(driver = DriverStatus(state = it))
            f.coordinator.request(); assertTrue(f.searches.isEmpty()); assertTrue(f.prompts.isEmpty())
        }
        val f = Fixture(this); f.safety.value = f.safety.value.copy(restStopNeeded = false)
        f.coordinator.request(); assertTrue(f.searches.isEmpty())
    }
    @Test fun missingOrExpiredRouteNeverSearches() = runTest {
        val f = Fixture(this); f.route.value = null; f.coordinator.request()
        assertTrue(f.searches.isEmpty())
        f.route.value = RouteProgress("arrived", 0, Duration.ZERO, 1); f.coordinator.request()
        assertTrue(f.searches.isEmpty())
        f.route.value = RouteProgress("expired", 0, 1.minutes, 1); f.offset = 60_001; f.coordinator.request()
        assertTrue(f.searches.isEmpty())
    }
    @Test fun emptyResultGivesAdviceWithoutAskingConsentOrChangingRoute() = runTest {
        val f = Fixture(this); f.findResult = OperationResult.Success(emptyList()); f.coordinator.request()
        assertEquals(RestGuidancePhase.EMPTY, f.status.phase)
        assertEquals(0, f.listens); assertTrue(f.additions.isEmpty())
        assertTrue(f.prompts.single().contains("안전한 곳"))
    }
    @Test fun invalidCandidateIdsAndNamesCannotBeUsed() = runTest {
        listOf(listOf(RestStopCandidate("", "이름")), listOf(RestStopCandidate("id", "")),
            listOf(RestStopCandidate("id", "첫"), RestStopCandidate("id", "다른"))).forEach {
            val f = Fixture(this); f.findResult = OperationResult.Success(it); f.coordinator.request()
            assertEquals(FailureReason.INVALID_REQUEST, f.status.failure)
            assertTrue(f.additions.isEmpty()); assertEquals(0, f.listens)
        }
    }
    @Test fun serviceFailureAndSecurityExceptionAreDistinct() = runTest {
        val f = Fixture(this); f.findResult = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        f.coordinator.request(); assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.status.failure)
        val denied = Fixture(this); denied.findException = SecurityException()
        denied.coordinator.request(); assertEquals(FailureReason.PERMISSION_DENIED, denied.status.failure)
        assertTrue(f.additions.isEmpty()); assertTrue(denied.additions.isEmpty())
    }
    @Test fun stalledSearchTimesOutAndCannotStartConsent() = runTest {
        val f = Fixture(this); f.findGate = CompletableDeferred(); f.coordinator.request()
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(FailureReason.TIMEOUT, f.status.failure)
        assertEquals(0, f.listens); assertTrue(f.additions.isEmpty())
    }
    @Test fun failedTtsNeverStartsSttOrNavigation() = runTest {
        val f = Fixture(this); f.spoken = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        f.coordinator.request()
        assertEquals(RestGuidancePhase.FAILED, f.status.phase)
        assertEquals(0, f.listens); assertTrue(f.additions.isEmpty())
    }
    @Test fun rejectionAndAmbiguousTextAreNeverConsent() = runTest {
        listOf("아니", "아니요", "네 아니요", "안내해줄까?", "괜찮아", "").forEach {
            val f = Fixture(this); f.answer = RecognitionResult.Text(it); f.coordinator.request()
            assertEquals(RestGuidancePhase.DECLINED, f.status.phase)
            assertTrue(f.additions.isEmpty())
        }
    }
    @Test fun silenceAndSpeechErrorsDoNotAuthorizeWaypointOrAlterDriverState() = runTest {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable).forEach {
            val f = Fixture(this); val before = f.safety.value; f.answer = it; f.coordinator.request()
            assertTrue(f.additions.isEmpty()); assertEquals(before, f.safety.value)
            assertEquals(if (it == RecognitionResult.NoResponse) RestGuidancePhase.NO_RESPONSE else RestGuidancePhase.FAILED, f.status.phase)
        }
    }
    @Test fun consentListeningDeadlineIsBounded() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred(); f.coordinator.request()
        assertEquals(10_000L, testScheduler.currentTime)
        assertEquals(FailureReason.TIMEOUT, f.status.failure)
        assertTrue(f.additions.isEmpty())
    }
    @Test fun routeChangeWhileSearchingCancelsOldQueryWithoutCandidateSpeech() = runTest {
        val f = Fixture(this); f.findGate = CompletableDeferred()
        val task = launch { f.coordinator.request() }; runCurrent(); f.reroute(); runCurrent(); task.join()
        assertEquals(RestGuidancePhase.STALE, f.status.phase)
        f.findGate!!.complete(Unit); runCurrent()
        assertTrue(f.prompts.isEmpty()); assertTrue(f.additions.isEmpty())
    }
    @Test fun routeChangeWhileListeningInvalidatesAffirmativeAnswer() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val task = launch { f.coordinator.request() }; runCurrent()
        assertEquals(1, f.listens)
        f.reroute(); runCurrent(); task.join()
        f.listenGate!!.complete(RecognitionResult.Text("네")); runCurrent()
        assertEquals(RestGuidancePhase.STALE, f.status.phase)
        assertTrue(f.additions.isEmpty())
    }
    @Test fun synchronousRouteChangeJustBeforePositiveResultIsAlsoRejected() = runTest {
        val f = Fixture(this); f.onListen = { f.reroute() }; f.coordinator.request()
        assertEquals(RestGuidancePhase.STALE, f.status.phase)
        assertTrue(f.additions.isEmpty())
    }
    @Test fun routeCancellationAndArrivalInvalidatePendingDialogue() = runTest {
        repeat(2) { mode ->
            val f = Fixture(this); f.listenGate = CompletableDeferred()
            val task = launch { f.coordinator.request() }; runCurrent()
            f.route.value = if (mode == 0) null else f.route.value!!.copy(remaining = Duration.ZERO)
            runCurrent(); task.join()
            assertEquals(RestGuidancePhase.STALE, f.status.phase); assertTrue(f.additions.isEmpty())
        }
    }
    @Test fun newConditionObservationOrRecoveryCancelsPendingConsent() = runTest {
        repeat(2) { mode ->
            val f = Fixture(this); f.listenGate = CompletableDeferred()
            val task = launch { f.coordinator.request() }; runCurrent()
            f.safety.value = if (mode == 0) f.safety.value.copy(restStopNeeded = false)
                else f.safety.value.copy(driver = f.safety.value.driver.copy(lastConfirmedAtMillis = 100))
            runCurrent(); task.join()
            assertEquals(RestGuidancePhase.STALE, f.status.phase); assertTrue(f.additions.isEmpty())
        }
    }
    @Test fun duplicatesDuringSearchAndAfterSuccessAreIgnored() = runTest {
        val f = Fixture(this); f.findGate = CompletableDeferred()
        val task = launch { f.coordinator.request() }; runCurrent()
        f.coordinator.request(); assertEquals(1, f.searches.size)
        f.findGate!!.complete(Unit); task.join()
        f.coordinator.request(); f.coordinator.request(retry = true)
        assertEquals(1, f.searches.size); assertEquals(1, f.additions.size)
    }
    @Test fun explicitRetryAfterDeclineRequiresNewSearchIdAndConsent() = runTest {
        val f = Fixture(this); f.answer = RecognitionResult.Text("아니"); f.coordinator.request()
        f.coordinator.request(); assertEquals(1, f.searches.size)
        f.answer = RecognitionResult.Text("네"); f.coordinator.request(retry = true)
        assertEquals(2, f.searches.size)
        assertNotEquals(f.searches[0].requestId, f.searches[1].requestId)
        assertEquals(2, f.listens); assertEquals(1, f.additions.size)
    }
    @Test fun navigationFailureIsUncertainAndCannotBeAutomaticallyRetried() = runTest {
        val f = Fixture(this); f.addResult = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        f.coordinator.request(); assertEquals(RestGuidancePhase.UNCERTAIN, f.status.phase)
        f.coordinator.request(retry = true); assertEquals(1, f.additions.size); assertEquals(1, f.searches.size)
    }
    @Test fun navigationTimeoutIsUncertainAndNoDuplicateMutationFollows() = runTest {
        val f = Fixture(this); f.addGate = CompletableDeferred(); f.coordinator.request()
        assertEquals(15_000L, testScheduler.currentTime)
        assertEquals(RestGuidancePhase.UNCERTAIN, f.status.phase)
        f.coordinator.request(retry = true); assertEquals(1, f.additions.size)
    }
    @Test fun successfulWaypointMayPublishItsOwnNewRouteRevision() = runTest {
        val f = Fixture(this); f.onAdd = { f.reroute() }; f.coordinator.request()
        assertEquals(RestGuidancePhase.ADDED, f.status.phase); assertEquals(1, f.additions.size)
        f.coordinator.request(); f.coordinator.request(retry = true)
        assertEquals(1, f.searches.size); assertEquals(1, f.additions.size)
    }
    @Test fun cancelAndCloseReleaseVoiceAndRejectLateConsent() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val task = launch { f.coordinator.request() }; runCurrent(); f.coordinator.cancel()
        assertTrue(task.isCancelled); assertEquals(RestGuidancePhase.CANCELLED, f.status.phase)
        assertEquals(1, f.stops)
        f.listenGate!!.complete(RecognitionResult.Text("네")); runCurrent(); assertTrue(f.additions.isEmpty())
        f.coordinator.close(); f.coordinator.close(); f.coordinator.request(retry = true)
        assertEquals(1, f.searches.size)
    }
    @Test fun callerCancellationDuringNavigationIsUncertain() = runTest {
        val f = Fixture(this); f.addGate = CompletableDeferred()
        val task = launch { f.coordinator.request() }; runCurrent(); assertEquals(1, f.additions.size)
        task.cancelAndJoin(); assertEquals(RestGuidancePhase.UNCERTAIN, f.status.phase)
        f.coordinator.request(retry = true); assertEquals(1, f.additions.size)
    }
    @Test fun expiredCandidateCannotBeAccepted() = runTest {
        val f = Fixture(this); f.onListen = { f.offset += 61_000 }; f.coordinator.request()
        assertEquals(RestGuidancePhase.STALE, f.status.phase); assertTrue(f.additions.isEmpty())
    }
    @Test fun uncertainMutationCannotBeRetriedEvenIfRouteRevisionChanges() = runTest {
        val f = Fixture(this); f.addResult = OperationResult.Failure(FailureReason.TIMEOUT)
        f.coordinator.request(); f.reroute(); f.coordinator.request(retry = true)
        assertEquals(1, f.additions.size); assertEquals(1, f.searches.size)
        assertEquals(RestGuidancePhase.UNCERTAIN, f.status.phase)
    }
    @Test fun freshRevisionAfterStaleSearchRequiresNewSearchAndFreshConsent() = runTest {
        val f = Fixture(this); f.findGate = CompletableDeferred()
        val old = launch { f.coordinator.request() }; runCurrent(); f.reroute(); runCurrent(); old.join()
        f.findGate = null; f.coordinator.request()
        assertEquals(RestGuidancePhase.ADDED, f.status.phase)
        assertEquals(3L, f.additions.single().first.routeRevision)
        assertEquals(2, f.searches.size)
        assertNotEquals(f.searches[0].requestId, f.searches[1].requestId)
    }
    @Test fun hangingPlaybackTimesOutWithoutStartingListening() = runTest {
        val f = Fixture(this); f.speakGate = CompletableDeferred(); f.coordinator.request()
        assertEquals(30_000L, testScheduler.currentTime)
        assertEquals(FailureReason.TIMEOUT, f.status.failure)
        assertEquals(0, f.listens); assertTrue(f.additions.isEmpty())
    }
    @Test fun newGuidanceCannotStartUntilCancelledVoiceCleanupHasCompleted() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred(); f.stopGate = CompletableDeferred()
        val old = launch { f.coordinator.request() }; runCurrent()
        f.reroute(); runCurrent()
        assertEquals(1, f.stops); assertFalse(old.isCompleted)
        f.coordinator.request(); assertEquals(1, f.searches.size)
        f.stopGate!!.complete(Unit); old.join()
        f.stopGate = null; f.listenGate = null
        f.coordinator.request()
        assertEquals(2, f.searches.size); assertEquals(RestGuidancePhase.ADDED, f.status.phase)
    }
}
