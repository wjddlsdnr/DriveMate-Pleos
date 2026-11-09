package com.example.pleosconnect.contract.integration

import com.example.pleosconnect.contract.model.DriverConsent
import com.example.pleosconnect.contract.model.DriverPreferences
import com.example.pleosconnect.contract.model.OperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** B supplies a new revision on reroute/ETA update; null means no active route. */
data class RouteProgress(
    val routeId: String,
    val revision: Long,
    val remaining: Duration,
    val observedAtMillis: Long,
)

data class DestinationRequest(val requestId: String, val spokenName: String)

/** B must filter candidates for direction, access and legal parking before returning them. */
data class RestStopCandidate(val id: String, val name: String)
data class RestStopSearch(val requestId: String, val routeId: String, val routeRevision: Long)

interface RestStopFinder {
    suspend fun find(request: RestStopSearch): OperationResult<List<RestStopCandidate>>
}

/** B-owned implementation; A never invokes NaviHelper directly. */
interface NavigationController {
    val route: StateFlow<RouteProgress?>
    suspend fun setDestination(request: DestinationRequest, consent: DriverConsent): OperationResult<Unit>
    /** Validate that the candidate and consent belong to this search and active route revision. */
    suspend fun addRestWaypoint(
        search: RestStopSearch,
        candidate: RestStopCandidate,
        consent: DriverConsent,
    ): OperationResult<Unit>
    suspend fun resumeRoute(routeId: String, consent: DriverConsent): OperationResult<Unit>
}

sealed interface MediaCommand {
    data class PlayMusic(val query: String) : MediaCommand
    data object Stop : MediaCommand
    data object Next : MediaCommand
    data object PlayNewsAudio : MediaCommand
}

interface MediaController {
    suspend fun execute(command: MediaCommand): OperationResult<Unit>
}

/** Only requests for B to resolve supported settings and propose to the driver. */
enum class VehicleAssist { VENTILATION, CLIMATE }
data class VehicleAssistProposal(val id: String, val assist: VehicleAssist, val description: String)
/** B keeps the original settings privately; A receives only an opaque restoration handle. */
data class VehicleAdjustment(val id: String)

interface VehicleController {
    val manualOverrides: Flow<VehicleAdjustment>
    suspend fun propose(assist: VehicleAssist): OperationResult<VehicleAssistProposal>
    /** Capture settings BEFORE mutation. Unsupported/denied requests must change nothing. */
    suspend fun apply(proposal: VehicleAssistProposal, consent: DriverConsent): OperationResult<VehicleAdjustment>
    /** Restore only app-owned changes; skip manually overridden settings, require fresh consent. */
    suspend fun restore(adjustment: VehicleAdjustment, consent: DriverConsent): OperationResult<Unit>
}

/** A sees preferences only. B owns full profile/seat data and persistence. */
interface DriverProfileRepository {
    val selectedProfile: StateFlow<DriverPreferences?>
    suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit>
}
