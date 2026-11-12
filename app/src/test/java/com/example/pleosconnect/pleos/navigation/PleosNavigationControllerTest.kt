package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.integration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PleosNavigationControllerTest {
    private class Backend : NavigationBackend {
        val listeners = mutableListOf<(NavigationEvent) -> Unit>()
        var requests = 0; var waypoints = 0; var disconnects = 0; var queries = 0; var bookmarkQueries = 0
        var connectionResult: OperationResult<Unit> = OperationResult.Success(Unit)
        override suspend fun connect(listener: (NavigationEvent) -> Unit): OperationResult<Unit> { listeners += listener; return connectionResult }
        override fun refresh() { queries++ }
        override fun requestDestination(place: NavigationPlace) { requests++ }
        override fun requestWaypoint(place: NavigationPlace, index: Int) { waypoints++ }
        override fun requestSavedDestinations() { bookmarkQueries++ }
        override suspend fun disconnect() { disconnects++ }
        fun emit(event: NavigationEvent) { listeners.last()(event) }
    }
    private class Fixture(val test: TestScope) {
        val clock = MonotonicClock { test.testScheduler.currentTime + 10 }
        val gate = NavigationConsentGate(clock)
        val backend = Backend()
        val nav = PleosNavigationController(backend, clock, gate, timeoutMillis = 200)
        val destination = NavigationPlace("테스트 목적지", 37.4, 127.1, poiId = "test-poi")
        suspend fun connect() { nav.bindProfile("A"); nav.connect(); backend.emit(NavigationEvent.RouteState(false, true)) }
        fun consent(scope: ConsentScope, id: String, revision: Long? = null) = gate.confirmFromUi(scope, id, revision)
        fun evidence(place: NavigationPlace): RestRouteEvidence {
            val current = nav.route.value!!
            return RestRouteEvidence(RestStopSearch("search", current.routeId, current.revision), place,
                "fixture-ex", "fixture-tmap", "test-only-route-provider", true, true,
                clock.nowMillis(), clock.nowMillis() + 60_000)
        }
        fun confirm(place: NavigationPlace = destination, points: List<NavigationPlace> = emptyList(), seconds: Int = 3600) {
            backend.emit(NavigationEvent.RouteStarted)
            backend.emit(NavigationEvent.RoutingCompleted)
            backend.emit(NavigationEvent.RouteState(true, true))
            backend.emit(NavigationEvent.Destination(place, seconds))
            backend.emit(NavigationEvent.Waypoints(points))
        }
        suspend fun establish() {
            val request = nav.registerDestination(destination)
            val job = test.backgroundScope.async { nav.setDestination(request, consent(ConsentScope.DESTINATION, request.requestId)) }
            test.runCurrent(); confirm()
            assertTrue(job.await() is OperationResult.Success)
        }
    }
    @Test fun observedGuidanceSurvivesPollingButNeverBecomesOwnedControlRoute() = runTest {
        val f = Fixture(this); f.connect()
        f.backend.emit(NavigationEvent.RouteState(false, true, guidanceMode = true))
        f.backend.emit(NavigationEvent.Destination(f.destination, 3_600))
        val observed = requireNotNull(f.nav.observedVoiceRoute.route.value)
        assertNull(f.nav.route.value)
        advanceTimeBy(5_000)
        f.backend.emit(NavigationEvent.RouteState(false, true, guidanceMode = true))
        f.backend.emit(NavigationEvent.Destination(f.destination, 3_595))
        assertEquals(observed.routeId, f.nav.observedVoiceRoute.route.value?.routeId)
        assertNull(f.nav.route.value); assertEquals(0, f.backend.requests)
        f.nav.disconnect()
        f.backend.emit(NavigationEvent.RouteState(false, true, guidanceMode = true))
        f.backend.emit(NavigationEvent.Destination(f.destination, 3_590))
        assertNull(f.nav.observedVoiceRoute.route.value)
    }
    @Test fun sdkRequestAloneIsNotSuccessAndEtaIsSeconds() = runTest {
        val f = Fixture(this); f.connect()
        val request = f.nav.registerDestination(f.destination)
        val work = backgroundScope.async { f.nav.setDestination(request, f.consent(ConsentScope.DESTINATION, request.requestId)) }
        runCurrent()
        assertFalse(work.isCompleted); assertNull(f.nav.route.value)
        f.confirm()
        assertTrue(work.await() is OperationResult.Success)
        assertEquals(3_600_000L, f.nav.route.value!!.remaining.inWholeMilliseconds)
        assertEquals(request.requestId, f.nav.route.value!!.routeId)
        assertEquals(1, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun mockedOrForgedConsentCannotChangeRealNavigation() = runTest {
        val f = Fixture(this); f.connect()
        val request = f.nav.registerDestination(f.destination)
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), f.nav.setDestination(request,
            DriverConsent(ConsentScope.DESTINATION, request.requestId, f.clock.nowMillis())))
        assertEquals(0, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun connectionWithoutFreshReadableStateOrNativeTermsDoesNotAuthorizeMutation() = runTest {
        val f = Fixture(this); f.nav.bindProfile("A"); f.nav.connect()
        var request = f.nav.registerDestination(f.destination)
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), f.nav.setDestination(request,
            f.consent(ConsentScope.DESTINATION, request.requestId)))
        f.backend.emit(NavigationEvent.RouteState(false, false))
        request = f.nav.registerDestination(f.destination)
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), f.nav.setDestination(request,
            f.consent(ConsentScope.DESTINATION, request.requestId)))
        f.backend.emit(NavigationEvent.RouteState(false, true))
        advanceTimeBy(10_001)
        request = f.nav.registerDestination(f.destination)
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), f.nav.setDestination(request,
            f.consent(ConsentScope.DESTINATION, request.requestId)))
        assertEquals(0, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun completeWithoutStartOrMatchingDestinationTimesOutAndOldCallbackIsIgnored() = runTest {
        val f = Fixture(this); f.connect()
        val old = f.backend.listeners.last()
        val request = f.nav.registerDestination(f.destination)
        val work = backgroundScope.async { f.nav.setDestination(request, f.consent(ConsentScope.DESTINATION, request.requestId)) }
        runCurrent()
        f.backend.emit(NavigationEvent.RoutingCompleted)
        f.backend.emit(NavigationEvent.RouteState(true, true))
        f.backend.emit(NavigationEvent.Destination(f.destination, 3600))
        f.backend.emit(NavigationEvent.Waypoints(emptyList()))
        advanceTimeBy(201); runCurrent()
        assertEquals(OperationResult.Failure(FailureReason.TIMEOUT), work.await())
        assertTrue(f.nav.status.value.reconnectRequired)
        f.nav.connect(); f.backend.emit(NavigationEvent.RouteState(false, true))
        old(NavigationEvent.RouteStarted); old(NavigationEvent.RoutingCompleted)
        old(NavigationEvent.RouteState(true, true)); old(NavigationEvent.Destination(f.destination, 3600))
        assertNull(f.nav.route.value)
        f.nav.disconnect()
    }
    @Test fun aDifferentDestinationNeverCompletesSuccessfully() = runTest {
        val f = Fixture(this); f.connect()
        val request = f.nav.registerDestination(f.destination)
        val work = backgroundScope.async { f.nav.setDestination(request, f.consent(ConsentScope.DESTINATION, request.requestId)) }
        runCurrent(); f.confirm(f.destination.copy(longitude = 128.0))
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), work.await())
        assertNull(f.nav.route.value); assertTrue(f.nav.status.value.reconnectRequired)
        f.nav.disconnect()
    }
    @Test fun callbackFromOtherProfileAndCancelledRequestCannotReviveRoute() = runTest {
        val f = Fixture(this); f.connect()
        val callback = f.backend.listeners.last()
        val request = f.nav.registerDestination(f.destination)
        val work = backgroundScope.async { f.nav.setDestination(request, f.consent(ConsentScope.DESTINATION, request.requestId)) }
        runCurrent(); f.nav.bindProfile("B"); runCurrent()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), work.await())
        callback(NavigationEvent.RouteStarted); callback(NavigationEvent.RoutingCompleted)
        callback(NavigationEvent.Destination(f.destination, 3600))
        assertNull(f.nav.route.value)
        f.nav.connect(); f.backend.emit(NavigationEvent.RouteState(false, true))
        val next = f.nav.registerDestination(f.destination)
        val cancelled = backgroundScope.async { f.nav.setDestination(next, f.consent(ConsentScope.DESTINATION, next.requestId)) }
        runCurrent(); cancelled.cancelAndJoin()
        assertTrue(f.nav.status.value.reconnectRequired)
        assertNull(f.nav.route.value)
        f.nav.disconnect()
    }
    @Test fun ordinaryCountdownDoesNotContinuouslyRescheduleButMaterialEtaChangeDoes() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val initial = f.nav.route.value!!
        advanceTimeBy(5_000)
        f.backend.emit(NavigationEvent.Destination(f.destination, 3595))
        assertEquals(initial, f.nav.route.value)
        f.backend.emit(NavigationEvent.Destination(f.destination, 4000))
        assertEquals(initial.routeId, f.nav.route.value!!.routeId)
        assertTrue(f.nav.route.value!!.revision > initial.revision)
        f.backend.emit(NavigationEvent.Ended)
        assertNull(f.nav.route.value)
        f.backend.emit(NavigationEvent.Destination(f.destination, 4000))
        assertNull(f.nav.route.value)
        f.nav.disconnect()
    }
    @Test fun nativeFavoritesLoadWithoutChangingRouteAndSurviveInactiveRouteRefresh() = runTest {
        val f = Fixture(this); f.connect()
        val work = backgroundScope.async { f.nav.loadSavedDestinations() }
        runCurrent()
        assertEquals(1, f.backend.bookmarkQueries)
        assertEquals(0, f.backend.requests)
        f.backend.emit(NavigationEvent.SavedDestinations(listOf(f.destination, f.destination.copy(latitude = Double.NaN))))
        assertTrue(work.await() is OperationResult.Success)
        assertEquals(listOf(f.destination), f.nav.savedDestinations.value)
        f.backend.emit(NavigationEvent.RouteState(false, true))
        assertEquals(listOf(f.destination), f.nav.savedDestinations.value)
        f.nav.bindProfile("B")
        assertNull(f.nav.savedDestinations.value)
        assertEquals(0, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun timedOutFavoriteQueryDoesNotPublishLateLocationsAfterReconnect() = runTest {
        val f = Fixture(this); f.connect()
        val old = f.backend.listeners.last()
        val work = backgroundScope.async { f.nav.loadSavedDestinations() }
        runCurrent(); advanceTimeBy(6_001); runCurrent()
        assertEquals(OperationResult.Failure(FailureReason.TIMEOUT), work.await())
        assertTrue(f.nav.status.value.reconnectRequired)
        f.nav.connect()
        old(NavigationEvent.SavedDestinations(listOf(f.destination)))
        assertNull(f.nav.savedDestinations.value)
        assertEquals(0, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun unavailableSafeRestDataIsNotReplacedWithFakeCandidates() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED),
            VerifiedRestStopFinder(f.nav).find(RestStopSearch("search", route.routeId, route.revision)))
        f.nav.disconnect()
    }
    @Test fun restCandidatesAreFilteredBoundToRevisionAndRequireSeparateUiConsent() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val rest = NavigationPlace("테스트 휴식 장소", 37.5, 127.2, poiId = "test-rest")
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource { OperationResult.Success(listOf(
            VerifiedRestPlace(rest, true, true, true, f.evidence(rest)), VerifiedRestPlace(rest.copy(name = "역방향"), false, true, true),
            VerifiedRestPlace(rest.copy(name = "주차 불명"), true, true, false))) })
        val search = RestStopSearch("search", route.routeId, route.revision)
        val candidate = (finder.find(search) as OperationResult.Success).value.single()
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), f.nav.addRestWaypoint(search, candidate,
            DriverConsent(ConsentScope.REST_WAYPOINT, candidate.id, f.clock.nowMillis())))
        val work = backgroundScope.async { f.nav.addRestWaypoint(search, candidate,
            f.consent(ConsentScope.REST_WAYPOINT, candidate.id, route.revision)) }
        runCurrent(); f.confirm(points = listOf(rest))
        assertTrue(work.await() is OperationResult.Success)
        assertEquals(1, f.backend.waypoints)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), f.nav.addRestWaypoint(search, candidate,
            f.consent(ConsentScope.REST_WAYPOINT, candidate.id, route.revision)))
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), f.nav.resumeRoute(route.routeId,
            f.consent(ConsentScope.RESUME_ROUTE, route.routeId, f.nav.route.value!!.revision)))
        f.nav.disconnect()
    }
    @Test fun optimisticFlagsWithoutBoundRouteEvidenceCannotCreateCandidates() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val request = RestStopSearch("search", route.routeId, route.revision)
        val place = NavigationPlace("검증 대기 장소", 37.5, 127.2)
        val proof = f.evidence(place)
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource { OperationResult.Success(listOf(
            VerifiedRestPlace(place, true, true, true),
            VerifiedRestPlace(place, true, true, true, proof.copy(identityVerified = false)),
            VerifiedRestPlace(place, true, true, true, proof.copy(coordinateSystemVerified = false)),
            VerifiedRestPlace(place, true, true, true, proof.copy(search = request.copy(routeRevision = route.revision + 1))),
            VerifiedRestPlace(place, true, true, true, proof.copy(search = request.copy(requestId = "other-search"))),
            VerifiedRestPlace(place.copy(longitude = 128.0), true, true, true, proof))) })
        assertTrue((finder.find(request) as OperationResult.Success).value.isEmpty())
        assertEquals(0, f.backend.waypoints)
        f.nav.disconnect()
    }
    @Test fun candidateCannotOutliveProviderEvidenceEvenWithFreshUiConsent() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val request = RestStopSearch("search", route.routeId, route.revision)
        val place = NavigationPlace("검증 대기 장소", 37.5, 127.2)
        val proof = f.evidence(place).copy(validUntilMillis = f.clock.nowMillis() + 10)
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource {
            OperationResult.Success(listOf(VerifiedRestPlace(place, true, true, true, proof)))
        })
        val candidate = (finder.find(request) as OperationResult.Success).value.single()
        advanceTimeBy(10)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), f.nav.addRestWaypoint(request, candidate,
            f.consent(ConsentScope.REST_WAYPOINT, candidate.id, route.revision)))
        assertEquals(0, f.backend.waypoints)
        f.nav.disconnect()
    }
    @Test fun evidenceThatExpiresDuringSearchCannotCreateCandidates() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val place = NavigationPlace("검증 대기 장소", 37.5, 127.2)
        val proof = f.evidence(place).copy(validUntilMillis = f.clock.nowMillis() + 10)
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource {
            delay(10)
            OperationResult.Success(listOf(VerifiedRestPlace(place, true, true, true, proof)))
        })
        assertTrue((finder.find(RestStopSearch("search", route.routeId, route.revision)) as OperationResult.Success).value.isEmpty())
        assertEquals(0, f.backend.waypoints)
        f.nav.disconnect()
    }
    @Test fun restProviderTimeoutErrorsAndCancellationRemainDistinct() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val request = RestStopSearch("search", route.routeId, route.revision)
        suspend fun find(source: RestStopDataSource) = VerifiedRestStopFinder(f.nav, source, 100).find(request)
        assertEquals(OperationResult.Failure(FailureReason.TIMEOUT), find { delay(101); OperationResult.Success(emptyList()) })
        assertEquals(OperationResult.Failure(FailureReason.PERMISSION_DENIED), find { throw SecurityException() })
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), find { throw IllegalStateException() })
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), find { OperationResult.Failure(FailureReason.UNSUPPORTED) })
        val work = backgroundScope.async { find { awaitCancellation() } }
        runCurrent(); work.cancelAndJoin()
        assertTrue(work.isCancelled)
        assertEquals(route, f.nav.route.value)
        assertEquals(0, f.backend.waypoints)
        f.nav.disconnect()
    }
    @Test fun restResultsAreUniqueBoundedAndStaleFailuresDoNotLeakIntoNewRoute() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val request = RestStopSearch("search", route.routeId, route.revision)
        val entries = (0..9).map {
            val place = NavigationPlace("장소 $it", 37.0 + it / 100.0, 127.0)
            VerifiedRestPlace(place, true, true, true, f.evidence(place))
        }
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource { OperationResult.Success(listOf(entries.first(), entries.first()) + entries) })
        val results = (finder.find(request) as OperationResult.Success).value
        assertEquals(5, results.size)
        assertEquals(5, results.map { it.name }.distinct().size)
        val late = VerifiedRestStopFinder(f.nav, RestStopDataSource { delay(10); OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) })
        val work = backgroundScope.async { late.find(request) }
        runCurrent(); f.backend.emit(NavigationEvent.Ended); advanceTimeBy(11); runCurrent()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), work.await())
        f.nav.disconnect()
    }
    @Test fun liveNavigationCannotFallBackToSimulatedRestFinder() = runTest {
        val f = Fixture(this)
        try {
            DriveMateAppGraph(backgroundScope, DriverPreferences("A", "운전자"), SimulationScenario.NORMAL, f.clock,
                navigationOverride = f.nav)
            fail("An explicit live rest finder is required")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun lateRestSearchAfterRevisionChangeIsRejected() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        val gate = CompletableDeferred<Unit>()
        val finder = VerifiedRestStopFinder(f.nav, RestStopDataSource { gate.await(); OperationResult.Success(emptyList()) })
        val work = backgroundScope.async { finder.find(RestStopSearch("search", route.routeId, route.revision)) }
        runCurrent(); f.backend.emit(NavigationEvent.Destination(f.destination, 4500)); gate.complete(Unit)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), work.await())
        f.nav.disconnect()
    }
    @Test fun explicitResumeKeepsTripIdentityAndPreservesExistingWaypointsByRefusingUnsafeReset() = runTest {
        val f = Fixture(this); f.connect(); f.establish()
        val route = f.nav.route.value!!
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), f.nav.resumeRoute(route.routeId,
            DriverConsent(ConsentScope.RESUME_ROUTE, route.routeId, f.clock.nowMillis())))
        val work = backgroundScope.async { f.nav.resumeRoute(route.routeId, f.consent(ConsentScope.RESUME_ROUTE, route.routeId, route.revision)) }
        runCurrent(); f.confirm(seconds = 3400)
        assertTrue(work.await() is OperationResult.Success)
        assertEquals(route.routeId, f.nav.route.value!!.routeId)
        assertEquals(2, f.backend.requests)
        f.nav.disconnect()
    }
    @Test fun serviceAndPermissionFailuresStayFailures() = runTest {
        val f = Fixture(this)
        f.backend.connectionResult = OperationResult.Failure(FailureReason.PERMISSION_DENIED)
        f.connect()
        assertFalse(f.nav.status.value.connected)
        assertEquals(FailureReason.PERMISSION_DENIED, f.nav.status.value.failure)
        f.backend.connectionResult = OperationResult.Success(Unit); f.nav.connect(); f.backend.emit(NavigationEvent.RouteState(false, true))
        val request = f.nav.registerDestination(f.destination)
        val work = backgroundScope.async { f.nav.setDestination(request, f.consent(ConsentScope.DESTINATION, request.requestId)) }
        runCurrent(); f.backend.emit(NavigationEvent.Failed(FailureReason.SERVICE_UNAVAILABLE))
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), work.await())
        assertEquals(FailureReason.SERVICE_UNAVAILABLE, f.nav.status.value.failure)
        f.nav.disconnect()
    }
    @Test fun gateRejectsExpiredRevokedReplayedAndWrongRevisionGrants() = runTest {
        val f = Fixture(this)
        val consent = f.consent(ConsentScope.DESTINATION, "target")
        advanceTimeBy(30_001)
        assertEquals(FailureReason.STALE_REQUEST, f.gate.consume(consent, ConsentScope.DESTINATION, "target", null))
        val current = f.consent(ConsentScope.REST_WAYPOINT, "rest", 1)
        assertEquals(FailureReason.CONSENT_REQUIRED, f.gate.consume(current, ConsentScope.REST_WAYPOINT, "rest", 2))
        assertNull(f.gate.consume(current, ConsentScope.REST_WAYPOINT, "rest", 1))
        assertEquals(FailureReason.CONSENT_REQUIRED, f.gate.consume(current, ConsentScope.REST_WAYPOINT, "rest", 1))
        val twice = f.consent(ConsentScope.DESTINATION, "target"); f.consent(ConsentScope.DESTINATION, "target")
        assertNull(f.gate.consume(twice, ConsentScope.DESTINATION, "target", null))
        assertEquals(FailureReason.CONSENT_REQUIRED, f.gate.consume(twice, ConsentScope.DESTINATION, "target", null))
        val revoked = f.consent(ConsentScope.DESTINATION, "new"); f.gate.clear()
        assertEquals(FailureReason.CONSENT_REQUIRED, f.gate.consume(revoked, ConsentScope.DESTINATION, "new", null))
    }
    @Test fun actualNavigationPortReachesASessionButNeverRunsMockDriverAnswers() = runTest {
        val f = Fixture(this); f.connect()
        val owner = DriveMateSessionOwner(backgroundScope, { scope, profile, scenario ->
            DriveMateAppGraph(scope, profile, scenario, f.clock, navigationOverride = f.nav, finderOverride = VerifiedRestStopFinder(f.nav))
        })
        runCurrent(); owner.foreground(); owner.submit(SimulationAction.START); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, f.backend.requests)
        assertEquals(ConditionReport.UNCONFIRMED, owner.state.value.driver.report)
        assertTrue(owner.state.value.liveNavigation)
        f.establish(); runCurrent()
        assertEquals(f.nav.route.value, owner.state.value.route)
        assertFalse(owner.state.value.canAct)
        owner.close(); f.nav.disconnect()
    }
}
