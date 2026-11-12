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
