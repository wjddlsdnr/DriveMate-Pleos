package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*

/** Simulation policy only: 30 seconds, single use. Not a Pleos authorization policy. */
internal class FakeConsentValidator(private val clock: MonotonicClock) {
    private val consumed = mutableSetOf<DriverConsent>()
    fun consume(consent: DriverConsent, scope: ConsentScope, target: String): FailureReason? {
        if (consent.scope != scope || consent.targetId != target) return FailureReason.CONSENT_REQUIRED
        val now = clock.nowMillis()
        if (consent.confirmedAtMillis < 0 || consent.confirmedAtMillis > now ||
            now - consent.confirmedAtMillis > 30_000 || consent in consumed) return FailureReason.STALE_REQUEST
        consumed += consent
        return null
    }
}
