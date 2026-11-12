package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** Read-only scheduling evidence. Does NOT grant destination/waypoint/resume ownership. */
class ObservedVoiceRoute(private val clock: MonotonicClock) : NavigationController {
    private val mutable = MutableStateFlow<RouteProgress?>(null)
    override val route = mutable.asStateFlow()
    private var activeAt: Long? = null
    private var destinationAt: Long? = null
    private var destination: NavigationEvent.Destination? = null
    private var identity: NavigationPlace? = null
    fun clear() { activeAt = null; destinationAt = null; destination = null; identity = null; mutable.value = null }
    fun expire() {
        val now = clock.nowMillis()
        if (activeAt?.let { now - it in 0..10_000 } != true || destinationAt?.let { now - it in 0..10_000 } != true) {
            mutable.value = null; identity = null
        }
    }
    fun accept(event: NavigationEvent) {
        val now = clock.nowMillis()
        when (event) {
            NavigationEvent.Ended, NavigationEvent.RouteStarted, NavigationEvent.RoutingCompleted,
            NavigationEvent.WaypointsChanged, is NavigationEvent.Failed -> { clear(); return }
            is NavigationEvent.RouteState -> {
                if (!event.guidanceMode || !event.termsAccepted) { clear(); return }
                activeAt = now
            }
            is NavigationEvent.Destination -> {
                if (!event.place.valid() || event.remainingSeconds <= 0) { clear(); return }
                destination = event; destinationAt = now
            }
            else -> return
        }
        expire()
        if (activeAt?.let { now - it in 0..10_000 } != true || destinationAt?.let { now - it in 0..10_000 } != true) return
        val eta = destination ?: return
        val previous = route.value
        if (identity != eta.place || previous == null) {
            identity = eta.place
            mutable.value = RouteProgress("observed-${UUID.randomUUID()}", 1, eta.remainingSeconds.seconds, now)
        } else if (abs(previous.remaining.inWholeMilliseconds - (now - previous.observedAtMillis) - eta.remainingSeconds * 1000L) > 60_000) {
            mutable.value = previous.copy(revision = previous.revision + 1, remaining = eta.remainingSeconds.seconds, observedAtMillis = now)
        }
    }
    override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent) = OperationResult.Failure(FailureReason.UNSUPPORTED)
    override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent) = OperationResult.Failure(FailureReason.UNSUPPORTED)
    override suspend fun resumeRoute(routeId: String, consent: DriverConsent) = OperationResult.Failure(FailureReason.UNSUPPORTED)
}
