package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.RestStopSearch
import org.junit.Assert.*
import org.junit.Test

class RestRouteEvidenceTest {
    private val request = RestStopSearch("search", "route", 1)
    private val place = NavigationPlace("테스트 휴게소", 37.4, 127.1)
    private val evidence = RestRouteEvidence(request, place, "fixture-ex", "fixture-tmap", "test-only-route-provider",
        true, true, 100, 60_100)

    @Test fun evidenceMustBindToExactSearchRouteRevisionAndPlace() {
        assertTrue(evidence.validFor(request, place, 101))
        assertFalse(evidence.validFor(request.copy(requestId = "next-search"), place, 101))
        assertFalse(evidence.validFor(request.copy(routeId = "next-route"), place, 101))
        assertFalse(evidence.validFor(request.copy(routeRevision = 2), place, 101))
        assertFalse(evidence.validFor(request, place.copy(latitude = 37.5), 101))
        assertFalse(evidence.validFor(request, place.copy(poiId = "other-provider-id"), 101))
    }
    @Test fun futureExpiredAndExcessiveLifetimeProofsAreRejected() {
        assertFalse(evidence.validFor(request, place, 99))
        assertFalse(evidence.validFor(request, place, 60_100))
        assertFalse(evidence.copy(validUntilMillis = 60_101).validFor(request, place, 101))
        assertFalse(evidence.copy(validUntilMillis = 100).validFor(request, place, 100))
        assertFalse(evidence.copy(checkedAtMillis = -1).validFor(request, place, 101))
    }
    @Test fun nameAgreementOrNumericCoordinatesCannotStandInForIdentityAndCrsProof() {
        assertFalse(evidence.copy(coordinateSystemVerified = false).validFor(request, place, 101))
        assertFalse(evidence.copy(identityVerified = false).validFor(request, place, 101))
        assertFalse(evidence.copy(exSourceId = "").validFor(request, place, 101))
        assertFalse(evidence.copy(verificationSource = "bad\nsource").validFor(request, place, 101))
    }
}
