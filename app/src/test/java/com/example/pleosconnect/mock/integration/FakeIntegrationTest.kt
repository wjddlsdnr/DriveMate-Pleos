package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FakeIntegrationTest {
    private var now = 100L
    private val clock = MonotonicClock { now }
    private fun consent(scope: ConsentScope, target: String) = DriverConsent(scope, target, now)
    private suspend fun route(nav: FakeNavigationController) {
        assertTrue(nav.setDestination(DestinationRequest("trip", "가상 목적지"), consent(ConsentScope.DESTINATION, "trip")) is OperationResult.Success)
    }
    @Test fun destinationRequiresMatchingFreshSingleUseConsentAndPreservesRouteOnFailure() = runTest {
        val nav = FakeNavigationController(clock)
        val request = DestinationRequest("trip", "가상 목적지")
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), nav.setDestination(request, consent(ConsentScope.RESUME_ROUTE, "trip")))
        assertNull(nav.route.value)
        val approved = consent(ConsentScope.DESTINATION, "trip")
        assertTrue(nav.setDestination(request, approved) is OperationResult.Success)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), nav.setDestination(request, approved))
        val before = nav.route.value
        now += 31_000
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), nav.setDestination(request.copy(requestId = "new"), approved.copy(targetId = "new")))
        assertEquals(before, nav.route.value)
    }
    @Test fun searchMustBeCurrentAndWaypointMustBelongToRegisteredSearch() = runTest {
        val nav = FakeNavigationController(clock); route(nav)
        val finder = FakeRestStopFinder(nav)
        val search = RestStopSearch("search", "trip", 1)
        val candidate = (finder.find(search) as OperationResult.Success).value.first()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), nav.addRestWaypoint(search, candidate.copy(id = "other"), consent(ConsentScope.REST_WAYPOINT, "other")))
        assertTrue(nav.addRestWaypoint(search, candidate, consent(ConsentScope.REST_WAYPOINT, candidate.id)) is OperationResult.Success)
        assertEquals(2L, nav.route.value!!.revision)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), finder.find(search))
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), nav.addRestWaypoint(search, candidate, consent(ConsentScope.REST_WAYPOINT, candidate.id)))
        assertEquals(1, nav.waypointCount)
    }
    @Test fun cancelledRouteCannotBeResumedAndSearchFailureIsNotSuccess() = runTest {
        val nav = FakeNavigationController(clock); route(nav)
        val finder = FakeRestStopFinder(nav)
        finder.failure = FailureReason.SERVICE_UNAVAILABLE
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), finder.find(RestStopSearch("s", "trip", 1)))
        nav.clearRoute()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), nav.resumeRoute("trip", consent(ConsentScope.RESUME_ROUTE, "trip")))
        assertEquals(0, nav.resumeCount)
    }
    @Test fun unsupportedVehicleDoesNotApplyAndManualOverrideBlocksRestore() = runTest {
        val vehicle = FakeVehicleController(clock)
        vehicle.failure = FailureReason.UNSUPPORTED
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), vehicle.propose(VehicleAssist.VENTILATION))
        assertEquals(0, vehicle.appliedCount)
        vehicle.failure = null
        val proposal = (vehicle.propose(VehicleAssist.VENTILATION) as OperationResult.Success).value
        assertEquals(OperationResult.Failure(FailureReason.CONSENT_REQUIRED), vehicle.apply(proposal, consent(ConsentScope.VEHICLE_ASSIST, "other")))
        val applied = (vehicle.apply(proposal, consent(ConsentScope.VEHICLE_ASSIST, proposal.id)) as OperationResult.Success).value
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), vehicle.apply(proposal, consent(ConsentScope.VEHICLE_ASSIST, proposal.id)))
        vehicle.simulateManualOverride(applied)
        assertEquals(OperationResult.Failure(FailureReason.MANUAL_OVERRIDE), vehicle.restore(applied, consent(ConsentScope.RESTORE_VEHICLE, applied.id)))
    }
    @Test fun profileRejectsOtherDriverAndMediaFailureDoesNotRecordSuccessfulCommand() = runTest {
        val profile = DriverPreferences("A", "가상 A")
        val profiles = FakeDriverProfileRepository(profile)
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), profiles.savePreferences(profile.copy(profileId = "B")))
        assertEquals(profile, profiles.selectedProfile.value)
        profiles.failure = FailureReason.SERVICE_UNAVAILABLE
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), profiles.savePreferences(profile.copy(genres = listOf("발라드"))))
        val media = FakeMediaController()
        media.failure = FailureReason.PERMISSION_DENIED
        assertEquals(OperationResult.Failure(FailureReason.PERMISSION_DENIED), media.execute(MediaCommand.PlayNewsAudio))
        assertNull(media.lastCommand.value)
    }
}
