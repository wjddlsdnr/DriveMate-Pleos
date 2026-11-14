package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

data class VerifiedRestPlace(val place: NavigationPlace, val directionMatches: Boolean, val reachable: Boolean,
    val legalParking: Boolean, val evidence: RestRouteEvidence? = null)
fun interface RestStopDataSource {
    suspend fun search(request: RestStopSearch): OperationResult<List<VerifiedRestPlace>>
}

/** No production data source is available in NaviHelper. Never substitutes simulation candidates. */
class VerifiedRestStopFinder(private val navigation: PleosNavigationController, private val source: RestStopDataSource? = null,
    private val timeoutMillis: Long = 8_000) : RestStopFinder {
    init { require(timeoutMillis > 0) }
    override suspend fun find(request: RestStopSearch): OperationResult<List<RestStopCandidate>> {
        val current = navigation.route.value
        if (current == null || current.routeId != request.routeId || current.revision != request.routeRevision)
            return OperationResult.Failure(FailureReason.STALE_REQUEST)
        val provider = source ?: return OperationResult.Failure(FailureReason.UNSUPPORTED)
        val found = try {
            withTimeoutOrNull(timeoutMillis) { provider.search(request) }
                ?: OperationResult.Failure(FailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        val latest = navigation.route.value
        if (latest == null || latest.routeId != request.routeId || latest.revision != request.routeRevision)
            return OperationResult.Failure(FailureReason.STALE_REQUEST)
        return when (found) {
            is OperationResult.Failure -> found
            is OperationResult.Success -> {
                val verified = found.value.asSequence()
                    .filter { it.directionMatches && it.reachable && it.legalParking && it.place.valid() &&
                        it.evidence?.validFor(request, it.place, navigation.evidenceTimeMillis()) == true }
                    .distinctBy { it.place.latitude to it.place.longitude }.take(5).toList()
                val expiresAt = verified.minOfOrNull { requireNotNull(it.evidence).validUntilMillis }
                    ?: navigation.evidenceTimeMillis()
                navigation.registerRestCandidates(request, verified.map { it.place }, expiresAt)?.let { OperationResult.Success(it) }
                    ?: OperationResult.Failure(FailureReason.STALE_REQUEST)
            }
        }
    }
}
