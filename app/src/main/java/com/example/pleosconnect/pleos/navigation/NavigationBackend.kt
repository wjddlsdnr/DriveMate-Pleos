package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.model.*

/** B-owned values. Coordinates are resolved explicitly; a name is never silently geocoded. */
data class NavigationPlace(val name: String, val latitude: Double, val longitude: Double,
    val address: String = "", val poiId: String = "", val poiSubId: String = "", val poiProvider: String = "") {
    fun valid() = name.isNotBlank() && name.length <= 100 && latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        listOf(name, address, poiId, poiSubId, poiProvider).all { it.length <= 300 && it.none(Char::isISOControl) }
}

sealed interface NavigationEvent {
    data object RouteStarted : NavigationEvent
    data object RoutingCompleted : NavigationEvent
    data object WaypointsChanged : NavigationEvent
    data object Ended : NavigationEvent
    data class RouteState(val active: Boolean, val termsAccepted: Boolean, val guidanceMode: Boolean = active) : NavigationEvent
    data class Destination(val place: NavigationPlace, val remainingSeconds: Int) : NavigationEvent
    data class Waypoints(val places: List<NavigationPlace>) : NavigationEvent
    data class SavedDestinations(val places: List<NavigationPlace>) : NavigationEvent
    data class Failed(val reason: FailureReason) : NavigationEvent
}

interface NavigationBackend {
    suspend fun connect(listener: (NavigationEvent) -> Unit): OperationResult<Unit>
    fun refresh()
    fun requestDestination(place: NavigationPlace)
    fun requestWaypoint(place: NavigationPlace, index: Int)
    fun requestSavedDestinations()
    suspend fun disconnect()
}
