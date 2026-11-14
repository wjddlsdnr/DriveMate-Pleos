package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.logic.MonotonicClock
import java.util.UUID

/** B-internal, one-use consent for transmitting a specific coordinate pair to TMAP via a relay.
 * Does not authorize navigation changes or certify that a discovered place is safe.
 */
class NearbySearchConsentGate(private val clock: MonotonicClock) {
    enum class Rejection { NONE, ROUTE_CHANGED, STALE_CONTEXT, EXPIRED, INVALID_PERMIT }
    var rejection: Rejection = Rejection.NONE
        private set
    class Offer internal constructor(val id: String, val expiresAtMillis: Long) {
        val recipient: String get() = "TMAP via 쉼길 relay"
        val purpose: String get() = "Nearby rest-place discovery"
    }
    class Permit internal constructor(internal val id: String)
    class Request internal constructor(val latitude: Double, val longitude: Double) {
        val radiusKm: Int get() = 5
        override fun toString() = "NearbySearchRequest(redacted,radiusKm=5)"
    }
    private data class Pending(val offer: Offer, val context: RestSearchContext, var permit: Permit? = null)
    private var pending: Pending? = null

    /** UI must display recipient, purpose, coordinate-transmission scope and expiry before confirmation. */
    @Synchronized fun prepare(context: RestSearchContext): Offer? {
        pending = null
        rejection = Rejection.NONE
        val now = clock.nowMillis()
        if (!fresh(context, now) || now > Long.MAX_VALUE - 30_000) return null
        val offer = Offer(UUID.randomUUID().toString(), now + 30_000)
        pending = Pending(offer, context)
        return offer
    }
    /** Call only for an explicit positive UI action, never from a fake voice answer or automatically. */
    @Synchronized fun confirmFromUi(offerId: String, current: RestSearchContext): Permit? {
        val item = pending ?: return null
        if (!matches(item, offerId, current)) { pending = null; return null }
        if (item.permit != null) return null
        return Permit(item.offer.id).also { item.permit = it }
    }
    /** Consume immediately before HTTP dispatch; object identity prevents forged/copied permits. */
    @Synchronized fun consume(permit: Permit, current: RestSearchContext): Request? {
        val item = pending ?: return null
        pending = null
        if (item.permit !== permit) { rejection = Rejection.INVALID_PERMIT; return null }
        if (!matches(item, permit.id, current)) return null
        return Request(item.context.location.latitude, item.context.location.longitude)
    }
    @Synchronized fun cancel() { pending = null }
    private fun matches(item: Pending, id: String, current: RestSearchContext): Boolean {
        val now = clock.nowMillis()
        rejection = when {
            item.offer.id != id -> Rejection.INVALID_PERMIT
            item.context.routeId != current.routeId || item.context.routeRevision != current.routeRevision -> Rejection.ROUTE_CHANGED
            now >= item.offer.expiresAtMillis || now < item.context.observedAtMillis || now - item.context.observedAtMillis >= 35_000 -> Rejection.EXPIRED
            !fresh(current, now) -> Rejection.STALE_CONTEXT
            else -> Rejection.NONE
        }
        return rejection == Rejection.NONE
    }
    private fun fresh(context: RestSearchContext, now: Long) = context.routeId.isNotBlank() &&
        context.routeRevision >= 0 && context.observedAtMillis >= 0 && now >= context.observedAtMillis &&
        now - context.observedAtMillis < 5_000 && context.location.valid()
}
