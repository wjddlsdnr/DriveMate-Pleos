package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*

/** Synthetic candidates only; does not assert any real location is safe to park. */
class FakeRestStopFinder(private val navigation: FakeNavigationController) : RestStopFinder {
    var failure: FailureReason? = null
    var candidates = listOf(RestStopCandidate("simulation-rest", "가상 휴식 장소"))
    override suspend fun find(request: RestStopSearch): OperationResult<List<RestStopCandidate>> {
        val route = navigation.route.value
        if (route == null || route.routeId != request.routeId || route.revision != request.routeRevision)
            return OperationResult.Failure(FailureReason.STALE_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        val result = candidates.toList()
        navigation.register(request, result)
        return OperationResult.Success(result)
    }
}
