package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ObservedVoiceRouteTest {
    private var now = 100L
    private val subject = ObservedVoiceRoute(MonotonicClock { now })
    private val place = NavigationPlace("fixture", 37.5, 127.0)
    private val location = NavigationLocation(37.5, 127.0, 42.0, "native-fixture", 0)
    private fun guiding() {
        subject.accept(NavigationEvent.RouteState(false, true, guidanceMode = true))
        subject.accept(NavigationEvent.Destination(place, 3_600))
    }
    @Test fun locationNeedsFreshRouteAndNeverProvesCorrespondence() {
        subject.accept(NavigationEvent.Location(location)); assertNull(subject.searchContext())
        guiding(); subject.accept(NavigationEvent.Location(location))
        val context = requireNotNull(subject.searchContext())
        assertEquals(subject.route.value!!.routeId, context.routeId)
        assertEquals(subject.route.value!!.revision, context.routeRevision)
        assertFalse(context.routeCorrespondenceVerified); assertFalse(context.headingUnitVerified)
        assertFalse(context.toString().contains("native-fixture"))
        assertFalse(location.toString().contains("37.5"))
    }
    @Test fun expiredLocationCannotBeReusedByFreshDestination() {
        guiding(); subject.accept(NavigationEvent.Location(location))
        now += 5_001
        subject.accept(NavigationEvent.RouteState(false, true, guidanceMode = true))
        subject.accept(NavigationEvent.Destination(place, 3_595))
        assertNull(subject.searchContext())
    }
    @Test fun rerouteAndEtaRevisionRequireNewLocation() {
        guiding(); subject.accept(NavigationEvent.Location(location))
        subject.accept(NavigationEvent.Destination(place, 1_800)); assertNull(subject.searchContext())
        subject.accept(NavigationEvent.Location(location)); assertNotNull(subject.searchContext())
        subject.accept(NavigationEvent.RoutingCompleted); assertNull(subject.searchContext())
    }
    @Test fun invalidOrTerminatedLocationIsNeverExposed() {
        guiding()
        for (invalid in listOf(location.copy(latitude = Double.NaN), location.copy(longitude = 181.0),
            location.copy(rawHeading = Double.POSITIVE_INFINITY), location.copy(nativeRouteId = ""),
            location.copy(currentRouteIndex = -1))) {
            subject.accept(NavigationEvent.Location(invalid)); assertNull(subject.searchContext())
        }
        subject.accept(NavigationEvent.Location(location)); subject.accept(NavigationEvent.Ended)
        assertNull(subject.searchContext())
        subject.accept(NavigationEvent.Location(location)); assertNull(subject.searchContext())
    }
    @Test fun existingGuidanceIsObservableWithoutAnyDestinationRequest() {
        subject.accept(NavigationEvent.Destination(place, 3_600)); assertNull(subject.route.value)
        subject.accept(NavigationEvent.RouteState(true, true)); assertNotNull(subject.route.value)
        val route = requireNotNull(subject.route.value)
        now += 5_000; subject.accept(NavigationEvent.Destination(place, 3_595))
        assertEquals(route, subject.route.value)
    }
    @Test fun expiredInactiveAndUnacceptedSignalsNeverEnableVoiceRoute() {
        subject.accept(NavigationEvent.RouteState(true, true)); now += 10_001
        subject.accept(NavigationEvent.Destination(place, 3_600)); assertNull(subject.route.value)
        subject.accept(NavigationEvent.RouteState(true, false)); assertNull(subject.route.value)
        subject.accept(NavigationEvent.RouteState(true, true)); assertNull(subject.route.value)
        subject.accept(NavigationEvent.Destination(place, 3_600)); assertNotNull(subject.route.value)
        subject.accept(NavigationEvent.Ended); assertNull(subject.route.value)
    }
    @Test fun observedRouteNeverGrantsMutationAuthority() = runTest {
        subject.accept(NavigationEvent.RouteState(true, true)); subject.accept(NavigationEvent.Destination(place, 3_600))
        val route = requireNotNull(subject.route.value)
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), subject.resumeRoute(route.routeId,
            DriverConsent(ConsentScope.RESUME_ROUTE, route.routeId, now)))
        assertEquals(OperationResult.Failure(FailureReason.UNSUPPORTED), subject.setDestination(DestinationRequest("r", "fixture"),
            DriverConsent(ConsentScope.DESTINATION, "r", now)))
    }
    @Test fun inconsistentRoutingFlagRequiresGuidanceModeAndFreshDestinationForReadOnlyRoute() {
        subject.accept(NavigationEvent.RouteState(false, true, guidanceMode = true))
        assertNull(subject.route.value)
        subject.accept(NavigationEvent.Destination(place, 3_600))
        assertNotNull(subject.route.value)
        subject.accept(NavigationEvent.RouteState(false, true, guidanceMode = false))
        assertNull(subject.route.value)
    }
}
