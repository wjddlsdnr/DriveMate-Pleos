package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.minutes

/** In-memory route only. Never opens navigation or changes a real destination. */
class FakeNavigationController(private val clock: MonotonicClock) : NavigationController {
    private val mutableRoute = MutableStateFlow<RouteProgress?>(null)
    override val route = mutableRoute.asStateFlow()
    var failure: FailureReason? = null
    private val consents = FakeConsentValidator(clock)
    private val searches = mutableMapOf<String, Pair<RestStopSearch, List<RestStopCandidate>>>()
    var waypointCount = 0; private set
    var resumeCount = 0; private set

    internal fun register(search: RestStopSearch, candidates: List<RestStopCandidate>) {
        searches[search.requestId] = search to candidates
    }
    fun clearRoute() { mutableRoute.value = null; searches.clear() }
    override suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit> {
        if (request.requestId.isBlank() || request.spokenName.isBlank()) return OperationResult.Failure(FailureReason.INVALID_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        consents.consume(consent, ConsentScope.DESTINATION, request.requestId)?.let { return OperationResult.Failure(it) }
        searches.clear()
        mutableRoute.value = RouteProgress(request.requestId, 1, 60.minutes, clock.nowMillis())
        return OperationResult.Success(Unit)
    }
    override suspend fun addRestWaypoint(search: RestStopSearch, candidate: RestStopCandidate, consent: DriverConsent): OperationResult<Unit> {
        val current = route.value
        val registered = searches[search.requestId]
        if (current == null || search.routeId != current.routeId || search.routeRevision != current.revision ||
            registered?.first != search || candidate !in registered.second) return OperationResult.Failure(FailureReason.STALE_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        consents.consume(consent, ConsentScope.REST_WAYPOINT, candidate.id)?.let { return OperationResult.Failure(it) }
        searches.remove(search.requestId)
        waypointCount++
        mutableRoute.value = current.copy(revision = current.revision + 1, observedAtMillis = clock.nowMillis())
        return OperationResult.Success(Unit)
    }
    override suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit> {
        val current = route.value
        if (current == null || current.routeId != routeId) return OperationResult.Failure(FailureReason.STALE_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        consents.consume(consent, ConsentScope.RESUME_ROUTE, routeId)?.let { return OperationResult.Failure(it) }
        resumeCount++
        mutableRoute.value = current.copy(revision = current.revision + 1, observedAtMillis = clock.nowMillis())
        return OperationResult.Success(Unit)
    }
}
