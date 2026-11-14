package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.logic.MonotonicClock
import org.junit.Assert.*
import org.junit.Test

class NearbySearchConsentGateTest {
    private var now = 100L
    private val gate = NearbySearchConsentGate(MonotonicClock { now })
    private val context = RestSearchContext("local-fixture", 1, NavigationLocation(37.0, 127.0, 0.0, "native-fixture", null), 100)
    @Test fun explicitConsentProducesMinimalOneUseRequest() {
        val offer = gate.prepare(context)!!
        val permit = gate.confirmFromUi(offer.id, context)!!
        val request = gate.consume(permit, context)!!
        assertEquals(5, request.radiusKm)
        assertEquals(37.0, request.latitude, 0.0)
        assertFalse(request.toString().contains("37.0"))
        assertNull(gate.consume(permit, context))
    }
    @Test fun noConfirmationOrForgedPermitNeverDispatches() {
        val offer = gate.prepare(context)!!
        assertNull(gate.consume(NearbySearchConsentGate.Permit(offer.id), context))
    }
    @Test fun changedRouteInvalidatesConfirmation() {
        for (changed in listOf(context.copy(routeRevision = 2), context.copy(routeId = "other"))) {
            val offer = gate.prepare(context)!!
            assertNull(gate.confirmFromUi(offer.id, changed))
            assertEquals(NearbySearchConsentGate.Rejection.ROUTE_CHANGED, gate.rejection)
        }
    }
    @Test fun refreshedPositionKeepsOriginalSnapshotForThirtySecondReview() {
        val offer = gate.prepare(context)!!
        now = 20_100
        val latest = context.copy(location = context.location.copy(latitude = 38.0), observedAtMillis = now)
        val permit = gate.confirmFromUi(offer.id, latest)!!
        assertEquals(37.0, gate.consume(permit, latest)!!.latitude, 0.0)
    }
    @Test fun thirtySecondDeadlineRejectsEvenFreshLocation() {
        val offer = gate.prepare(context)!!
        now = 30_100
        assertNull(gate.confirmFromUi(offer.id, context.copy(observedAtMillis = now)))
        assertEquals(NearbySearchConsentGate.Rejection.EXPIRED, gate.rejection)
    }
    @Test fun expiryAndCancellationBlockAlreadyConfirmedRequest() {
        var offer = gate.prepare(context)!!
        var permit = gate.confirmFromUi(offer.id, context)!!
        now = 5_100; assertNull(gate.consume(permit, context))
        now = 100; offer = gate.prepare(context)!!; permit = gate.confirmFromUi(offer.id, context)!!
        gate.cancel(); assertNull(gate.consume(permit, context))
    }
    @Test fun newOfferAndChangedContextCannotReuseOldConsent() {
        val offer = gate.prepare(context)!!
        val permit = gate.confirmFromUi(offer.id, context)!!
        gate.prepare(context)
        assertNull(gate.consume(permit, context))
        val next = gate.prepare(context)!!
        val nextPermit = gate.confirmFromUi(next.id, context)!!
        assertNull(gate.consume(nextPermit, context.copy(routeRevision = 2)))
    }
    @Test fun futureExpiredAndInvalidCoordinatesNeverOffered() {
        assertNull(gate.prepare(context.copy(observedAtMillis = 101)))
        assertNull(gate.prepare(context.copy(location = context.location.copy(latitude = Double.NaN))))
        now = 5_100; assertNull(gate.prepare(context))
    }
}
