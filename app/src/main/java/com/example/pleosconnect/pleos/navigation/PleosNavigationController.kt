package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

data class NavigationConnectionStatus(val connected: Boolean = false, val busy: Boolean = false,
    val failure: FailureReason? = null, val reconnectRequired: Boolean = false,
    val readReady: Boolean = false, val termsAccepted: Boolean? = null)

/** SDK requests become success only after fresh matching state callbacks. No real POI safety inference. */
class PleosNavigationController(
    private val backend: NavigationBackend,
    private val clock: MonotonicClock,
    private val consentGate: NavigationConsentGate,
    private val timeoutMillis: Long = 20_000,
) : NavigationController {
    private val guard = Any()
    private val mutableRoute = MutableStateFlow<RouteProgress?>(null)
    override val route = mutableRoute.asStateFlow()
    private val mutableStatus = MutableStateFlow(NavigationConnectionStatus())
    val status = mutableStatus.asStateFlow()
    val observedVoiceRoute = ObservedVoiceRoute(clock)
    private val mutableSavedDestinations = MutableStateFlow<List<NavigationPlace>?>(null)
    val savedDestinations = mutableSavedDestinations.asStateFlow()
    private var bookmarkRequest: CompletableDeferred<OperationResult<Unit>>? = null
    private var foreground = false
    private var profileId: String? = null
    private var generation = 0L
    private var original: NavigationPlace? = null
    private var originalRouteId: String? = null
    private var knownWaypoints: List<NavigationPlace>? = null
    private var forceRevision = false
    private var lastEta: NavigationEvent.Destination? = null
    private var lastStateAt: Long? = null
    private var termsAccepted: Boolean? = null
    private val destinations = mutableMapOf<String, NavigationPlace>()
    private data class Candidate(val search: RestStopSearch, val value: RestStopCandidate, val place: NavigationPlace,
        val issued: Long, val validUntilMillis: Long)
    private val candidates = mutableMapOf<String, Candidate>()
    private enum class Kind { DESTINATION, WAYPOINT, RESUME }
    private class Attempt(val kind: Kind, val target: NavigationPlace, val routeId: String, val waypoint: NavigationPlace? = null,
        val baselineWaypoints: List<NavigationPlace> = emptyList()) {
        val result = CompletableDeferred<OperationResult<Unit>>()
        var started = false; var completed = false; var active = false
        var destination: NavigationEvent.Destination? = null
        var waypoints: List<NavigationPlace>? = null
    }
    private var pending: Attempt? = null

    init { require(timeoutMillis > 0) }

    suspend fun connect() {
        val epoch = synchronized(guard) {
            check(pending == null && bookmarkRequest == null) { "Cancel the active request before reconnecting" }
            invalidateOwnership(); mutableSavedDestinations.value = null
            foreground = true; lastStateAt = null; termsAccepted = null; ++generation
        }
        val result = try { withTimeoutOrNull(8_000) { backend.connect { event -> onEvent(epoch, event) } }
            ?: OperationResult.Failure(FailureReason.TIMEOUT) }
        catch (cancelled: CancellationException) { withContext(NonCancellable) { disconnect() }; throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        synchronized(guard) {
            if (epoch != generation || !foreground) return
            mutableStatus.value = NavigationConnectionStatus(connected = result is OperationResult.Success,
                failure = (result as? OperationResult.Failure)?.reason, readReady = lastStateAt != null, termsAccepted = termsAccepted)
        }
        if (result is OperationResult.Success) refresh()
        else backend.disconnect()
    }
    fun bindProfile(id: String?) = synchronized(guard) {
        if (profileId != id) {
            pending?.result?.complete(OperationResult.Failure(FailureReason.STALE_REQUEST))
            bookmarkRequest?.complete(OperationResult.Failure(FailureReason.STALE_REQUEST))
            profileId = id; invalidateOwnership()
            mutableSavedDestinations.value = null
            generation++
            lastStateAt = null; termsAccepted = null
            mutableStatus.value = NavigationConnectionStatus()
        }
    }
    fun registerDestination(place: NavigationPlace): DestinationRequest = synchronized(guard) {
        require(place.valid())
        destinations.clear()
        val id = UUID.randomUUID().toString()
        destinations[id] = place
        DestinationRequest(id, place.name)
    }
    fun refresh() {
        synchronized(guard) { observedVoiceRoute.expire() }
        if (!status.value.connected) return
        synchronized(guard) {
            if (lastStateAt == null || clock.nowMillis() - requireNotNull(lastStateAt) !in 0..10_000)
                mutableStatus.update { it.copy(readReady = false) }
        }
        try { backend.refresh() }
        catch (_: SecurityException) { fail(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { fail(FailureReason.SERVICE_UNAVAILABLE) }
    }
    suspend fun disconnect() {
        synchronized(guard) {
            foreground = false; generation++
            lastStateAt = null; termsAccepted = null
            pending?.result?.complete(OperationResult.Failure(FailureReason.STALE_REQUEST)); pending = null
            bookmarkRequest?.complete(OperationResult.Failure(FailureReason.STALE_REQUEST)); bookmarkRequest = null
            invalidateOwnership()
            mutableSavedDestinations.value = null
            mutableStatus.value = NavigationConnectionStatus()
        }
        withContext(NonCancellable) { backend.disconnect() }
    }
    suspend fun loadSavedDestinations(): OperationResult<Unit> {
        val request = synchronized(guard) {
            preflight()?.let { return OperationResult.Failure(it) }
            CompletableDeferred<OperationResult<Unit>>().also { bookmarkRequest = it; busy() }
        }
        var reason: FailureReason? = null
        return try {
            backend.requestSavedDestinations()
            (withTimeoutOrNull(6_000) { request.await() } ?: OperationResult.Failure(FailureReason.TIMEOUT)).also {
                if (it is OperationResult.Failure) reason = it.reason
            }
        } catch (cancelled: CancellationException) { reason = FailureReason.STALE_REQUEST; throw cancelled }
        catch (_: SecurityException) { reason = FailureReason.PERMISSION_DENIED; OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { reason = FailureReason.SERVICE_UNAVAILABLE; OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        finally {
            synchronized(guard) {
                if (bookmarkRequest === request) bookmarkRequest = null
                mutableStatus.update { it.copy(busy = false) }
                if (reason != null) {
                    generation++; invalidateOwnership(); mutableSavedDestinations.value = null
                    mutableStatus.value = NavigationConnectionStatus(failure = reason, reconnectRequired = true)
                }
            }
            if (reason != null) withContext(NonCancellable) { backend.disconnect() }
        }
    }
    override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit> {
        val attempt = synchronized(guard) {
            preflight()?.let { return OperationResult.Failure(it) }
            val place = destinations.remove(request.requestId) ?: return OperationResult.Failure(FailureReason.STALE_REQUEST)
            if (place.name != request.spokenName) return OperationResult.Failure(FailureReason.INVALID_REQUEST)
            consentGate.consume(consent, ConsentScope.DESTINATION, request.requestId, null)?.let { return OperationResult.Failure(it) }
            Attempt(Kind.DESTINATION, place, request.requestId).also { pending = it; busy() }
        }
        return execute(attempt) { backend.requestDestination(attempt.target) }
    }
    override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent): OperationResult<Unit> {
        val attempt = synchronized(guard) {
            preflight()?.let { return OperationResult.Failure(it) }
            val current = route.value ?: return OperationResult.Failure(FailureReason.STALE_REQUEST)
            val registered = candidates[candidate.id] ?: return OperationResult.Failure(FailureReason.STALE_REQUEST)
            if (registered.search != search || registered.value != candidate || search.routeId != current.routeId ||
                search.routeRevision != current.revision || clock.nowMillis() - registered.issued !in 0..60_000 ||
                clock.nowMillis() >= registered.validUntilMillis)
                return OperationResult.Failure(FailureReason.STALE_REQUEST)
            val waypoints = knownWaypoints ?: return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
            if (waypoints.size >= 2) return OperationResult.Failure(FailureReason.UNSUPPORTED)
            consentGate.consume(consent, ConsentScope.REST_WAYPOINT, candidate.id, current.revision)?.let { return OperationResult.Failure(it) }
            candidates.clear()
            Attempt(Kind.WAYPOINT, original ?: return OperationResult.Failure(FailureReason.STALE_REQUEST), current.routeId,
                registered.place, waypoints.toList()).also { pending = it; busy() }
        }
        return execute(attempt) { backend.requestWaypoint(requireNotNull(attempt.waypoint), attempt.baselineWaypoints.size) }
    }
    override suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit> {
        val attempt = synchronized(guard) {
            preflight()?.let { return OperationResult.Failure(it) }
            val current = route.value ?: return OperationResult.Failure(FailureReason.STALE_REQUEST)
            if (current.routeId != routeId || originalRouteId != routeId) return OperationResult.Failure(FailureReason.STALE_REQUEST)
            // Do not erase any remaining waypoint, including changes made in the native Navigation app.
            if (knownWaypoints?.isEmpty() != true) return OperationResult.Failure(FailureReason.UNSUPPORTED)
            consentGate.consume(consent, ConsentScope.RESUME_ROUTE, routeId, current.revision)?.let { return OperationResult.Failure(it) }
            Attempt(Kind.RESUME, original ?: return OperationResult.Failure(FailureReason.STALE_REQUEST), routeId)
                .also { pending = it; busy() }
        }
        // SDK has no resume primitive. This explicitly re-requests the verified original destination.
        return execute(attempt) { backend.requestDestination(attempt.target) }
    }
    internal fun registerRestCandidates(search: RestStopSearch, places: List<NavigationPlace>,
        validUntilMillis: Long): List<RestStopCandidate>? = synchronized(guard) {
        val current = route.value ?: return null
        if (current.routeId != search.routeId || current.revision != search.routeRevision) return null
        candidates.clear()
        if (clock.nowMillis() >= validUntilMillis) return emptyList()
        places.map { place ->
            val candidate = RestStopCandidate(UUID.randomUUID().toString(), place.name)
            candidates[candidate.id] = Candidate(search, candidate, place, clock.nowMillis(), validUntilMillis)
            candidate
        }
    }
    internal fun evidenceTimeMillis(): Long = clock.nowMillis()
    private suspend fun execute(attempt: Attempt, request: () -> Unit): OperationResult<Unit> {
        var uncertainReason: FailureReason? = null
        return try {
            request()
            (withTimeoutOrNull(timeoutMillis) { attempt.result.await() }
                ?: OperationResult.Failure(FailureReason.TIMEOUT)).also {
                    if (it is OperationResult.Failure) uncertainReason = it.reason
                }
        } catch (cancelled: CancellationException) { uncertainReason = FailureReason.STALE_REQUEST; throw cancelled }
        catch (_: SecurityException) { uncertainReason = FailureReason.PERMISSION_DENIED; OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { uncertainReason = FailureReason.SERVICE_UNAVAILABLE; OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        finally {
            synchronized(guard) {
                if (pending === attempt) pending = null
                mutableStatus.update { it.copy(busy = false) }
                if (uncertainReason != null) {
                    generation++; invalidateOwnership(); mutableSavedDestinations.value = null
                    mutableStatus.value = NavigationConnectionStatus(failure = uncertainReason, reconnectRequired = true)
                }
            }
            if (uncertainReason != null) withContext(NonCancellable) { backend.disconnect() }
        }
    }
    private fun preflight(): FailureReason? = when {
        profileId == null -> FailureReason.INVALID_REQUEST
        !foreground || !status.value.connected || status.value.reconnectRequired -> FailureReason.SERVICE_UNAVAILABLE
        lastStateAt == null || clock.nowMillis() - requireNotNull(lastStateAt) !in 0..10_000 -> FailureReason.SERVICE_UNAVAILABLE
        termsAccepted != true -> FailureReason.CONSENT_REQUIRED
        pending != null || bookmarkRequest != null -> FailureReason.STALE_REQUEST
        else -> null
    }
    private fun busy() { mutableStatus.value = status.value.copy(busy = true, failure = null) }
    private fun fail(reason: FailureReason) = synchronized(guard) {
        mutableStatus.update { it.copy(failure = reason, connected = if (pending == null) false else it.connected,
            reconnectRequired = pending == null || it.reconnectRequired) }
        pending?.result?.complete(OperationResult.Failure(reason))
        bookmarkRequest?.complete(OperationResult.Failure(reason))
        if (pending == null) invalidateOwnership()
    }
    private fun onEvent(epoch: Long, event: NavigationEvent) {
        var query = false
        synchronized(guard) {
            if (epoch != generation || !foreground) return
            observedVoiceRoute.accept(event)
            val attempt = pending
            if (attempt?.result?.isCompleted == true && event != NavigationEvent.Ended) return
            when (event) {
                is NavigationEvent.Location -> Unit // Read-only context is held by observedVoiceRoute only.
                NavigationEvent.RouteStarted -> {
                    consentGate.clear(); candidates.clear(); forceRevision = true
                    if (attempt != null) attempt.started = true
                }
                NavigationEvent.RoutingCompleted, NavigationEvent.WaypointsChanged -> {
                    if (attempt != null) {
                        if (event == NavigationEvent.WaypointsChanged && attempt.kind == Kind.WAYPOINT) attempt.started = true
                        attempt.completed = true; attempt.active = false; attempt.destination = null; attempt.waypoints = null
                    }
                    forceRevision = true; query = true
                }
                NavigationEvent.Ended -> {
                    attempt?.result?.complete(OperationResult.Failure(FailureReason.STALE_REQUEST))
                    invalidateOwnership()
                }
                is NavigationEvent.Failed -> fail(event.reason)
                is NavigationEvent.SavedDestinations -> if (bookmarkRequest != null) {
                    mutableSavedDestinations.value = event.places.filter { it.valid() }.distinct().take(20)
                    bookmarkRequest?.complete(OperationResult.Success(Unit))
                }
                is NavigationEvent.RouteState -> {
                    lastStateAt = clock.nowMillis(); termsAccepted = event.termsAccepted
                    mutableStatus.update { it.copy(readReady = true, termsAccepted = event.termsAccepted) }
                    if (attempt != null && attempt.completed) attempt.active = event.active && event.termsAccepted
                    if (attempt == null && !event.active) {
                        invalidateOwnership(clearObserved = !event.guidanceMode)
                    }
                }
                is NavigationEvent.Destination -> {
                    if (!event.place.valid() || event.remainingSeconds <= 0) return
                    if (attempt != null && attempt.completed) attempt.destination = event
                    else if (attempt == null && original != null) {
                        if (!samePlace(requireNotNull(original), event.place)) invalidateOwnership()
                        else { lastEta = event; publishEta(event, forceRevision); forceRevision = false }
                    }
                }
                is NavigationEvent.Waypoints -> {
                    val previous = knownWaypoints
                    knownWaypoints = event.places
                    if (attempt != null && attempt.completed) attempt.waypoints = event.places
                    else if (attempt == null && original != null && previous != null && previous != event.places) {
                        // An external route edit is not covered by our old consents or ownership.
                        invalidateOwnership()
                    }
                }
            }
            if (attempt != null && attempt.started && attempt.completed && attempt.active) {
                val destination = attempt.destination
                val waypoints = attempt.waypoints
                if (destination != null && waypoints != null) {
                    if (!samePlace(destination.place, attempt.target)) {
                        attempt.result.complete(OperationResult.Failure(FailureReason.STALE_REQUEST)); invalidateOwnership()
                    } else if ((attempt.kind != Kind.WAYPOINT && waypoints.isEmpty()) ||
                        (attempt.kind == Kind.WAYPOINT && waypoints.size == attempt.baselineWaypoints.size + 1 &&
                            attempt.baselineWaypoints.indices.all { samePlace(waypoints[it], attempt.baselineWaypoints[it]) } &&
                            samePlace(waypoints.last(), requireNotNull(attempt.waypoint)))) {
                        original = attempt.target; originalRouteId = attempt.routeId; lastEta = destination
                        publishEta(destination, true); forceRevision = false
                        attempt.result.complete(OperationResult.Success(Unit))
                    }
                }
            }
        }
        if (query) refresh()
    }
    private fun publishEta(value: NavigationEvent.Destination, force: Boolean) {
        val id = originalRouteId ?: return
        val now = clock.nowMillis()
        val current = route.value
        if (now < 0 || current != null && now < current.observedAtMillis) { fail(FailureReason.INVALID_REQUEST); return }
        val predicted = current?.let { (it.remaining.inWholeMilliseconds - (now - it.observedAtMillis)).coerceAtLeast(0) }
        if (current == null || force || predicted == null || abs(value.remainingSeconds * 1000L - predicted) > 60_000) {
            mutableRoute.value = RouteProgress(id, (current?.revision ?: 0) + 1, value.remainingSeconds.seconds, now)
            candidates.clear(); consentGate.clear()
        }
    }
    private fun invalidateOwnership(clearObserved: Boolean = true) {
        if (clearObserved) observedVoiceRoute.clear()
        original = null; originalRouteId = null; knownWaypoints = null; lastEta = null
        forceRevision = false; mutableRoute.value = null; destinations.clear(); candidates.clear(); consentGate.clear()
    }
    private fun samePlace(a: NavigationPlace, b: NavigationPlace) = abs(a.latitude - b.latitude) <= 0.00001 &&
        abs(a.longitude - b.longitude) <= 0.00001 && (a.poiId.isBlank() || b.poiId == a.poiId) &&
        (a.poiSubId.isBlank() || a.poiSubId == b.poiSubId) && (a.poiProvider.isBlank() || a.poiProvider == b.poiProvider)
}
