package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*

/** Only B's explicit on-screen confirmation path holds this issuer. A receives just the port. */
class NavigationConsentGate(private val clock: MonotonicClock) {
    private data class Grant(val consent: DriverConsent, val routeRevision: Long?)
    private val grants = mutableListOf<Grant>()
    @Synchronized fun confirmFromUi(scope: ConsentScope, targetId: String, routeRevision: Long?): DriverConsent {
        val consent = DriverConsent(scope, targetId, clock.nowMillis())
        grants.clear() // Single outstanding UI action, even for repeated taps in the same millisecond.
        grants += Grant(consent, routeRevision)
        return consent
    }
    @Synchronized fun consume(value: DriverConsent, scope: ConsentScope, target: String, revision: Long?): FailureReason? {
        if (value.scope != scope || value.targetId != target) return FailureReason.CONSENT_REQUIRED
        val index = grants.indexOfFirst { it.consent == value && it.routeRevision == revision }
        if (index < 0) return FailureReason.CONSENT_REQUIRED
        grants.removeAt(index)
        val now = clock.nowMillis()
        return if (value.confirmedAtMillis < 0 || now < value.confirmedAtMillis || now - value.confirmedAtMillis > 30_000)
            FailureReason.STALE_REQUEST else null
    }
    @Synchronized fun clear() { grants.clear() }
}
