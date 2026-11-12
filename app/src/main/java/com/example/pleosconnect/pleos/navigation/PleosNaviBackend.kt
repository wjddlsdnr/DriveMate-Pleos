package com.example.pleosconnect.pleos.navigation

import android.content.Context
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.constants.*
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Actual NaviHelper 3.0.5 calls. No raw coordinates, POI names or service payloads are logged. */
class PleosNaviBackend(context: Context, private val scope: CoroutineScope,
    private val routeDiagnostic: ((Boolean, String, Boolean) -> Unit)? = null) : NavigationBackend {
    private val application = context.applicationContext
    private var helper: NaviHelper? = null
    private var listener: NaviHelperEventListener? = null
    private var epoch = 0L
    override suspend fun connect(listener: (NavigationEvent) -> Unit): OperationResult<Unit> = withContext(Dispatchers.Main.immediate) {
        disconnect()
        val token = ++epoch
        suspendCancellableCoroutine { continuation ->
            val sdk = NaviHelper(application)
            helper = sdk
            fun deliver(event: NavigationEvent) { scope.launch {
                if (token == epoch && helper === sdk) listener(event)
            } }
            val events = object : NaviHelperEventListener {
                override fun onRouteStarted(routeStartInfo: RouteStartInfo) = deliver(NavigationEvent.RouteStarted)
                override fun onRoutingCompleted() = deliver(NavigationEvent.RoutingCompleted)
                override fun onReRouteEnded() = deliver(NavigationEvent.RoutingCompleted)
                override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = deliver(NavigationEvent.WaypointsChanged)
                override fun onRouteCancelled() = deliver(NavigationEvent.Ended)
                override fun onRouteEnded() = deliver(NavigationEvent.Ended)
                override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) = deliver(NavigationEvent.Ended)
                override fun onDestinationChanged() { scope.launch { if (token == epoch) refresh() } }
                override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
                    runCatching { routeDiagnostic?.invoke(routeStateInfo.isRouting, routeStateInfo.naviMode.toString(), routeStateInfo.termsAgreement) }
                    val guiding = routeStateInfo.naviMode in setOf(NaviMode.ROUTE_GUIDING, NaviMode.SIMULATION_GUIDING)
                    val active = routeStateInfo.isRouting && guiding
                    deliver(NavigationEvent.RouteState(active, routeStateInfo.termsAgreement, guiding))
                    // Read-only queries may confirm guidance evidence even when isRouting conflicts.
                    // Mutation acknowledgement below the controller still requires strict active=true.
                    if (guiding && routeStateInfo.termsAgreement) scope.launch {
                        if (token == epoch && helper === sdk) { sdk.getDestinationInfo(); sdk.getWaypointInfo() }
                    }
                }
                override fun onDestinationInfo(destinationInfo: DestinationInfo) = deliver(NavigationEvent.Destination(
                    NavigationPlace(destinationInfo.name, destinationInfo.latitude, destinationInfo.longitude, destinationInfo.address,
                        destinationInfo.poiId, destinationInfo.poiSubId.orEmpty(), destinationInfo.poiProvider), destinationInfo.remainTime))
                override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = deliver(NavigationEvent.Waypoints(waypointInfoList.map {
                    NavigationPlace(it.name, it.latitude, it.longitude, it.address, it.poiId, it.poiSubId.orEmpty(), it.poiProvider)
                }))
                override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = deliver(NavigationEvent.SavedDestinations(bookmarkInfoList.map {
                    NavigationPlace(it.name, it.latitude, it.longitude, it.address, it.poiId, it.poiSubId.orEmpty(), it.poiProvider)
                }))
                override fun onError(errorCode: NaviErrorCode) = deliver(NavigationEvent.Failed(when (errorCode) {
                    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE, NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> FailureReason.INVALID_REQUEST
                    else -> FailureReason.SERVICE_UNAVAILABLE
                }))
            }
            this@PleosNaviBackend.listener = events
            try {
                sdk.initialize(onConnected = { scope.launch {
                    if (token == epoch && continuation.isActive) {
                        try { sdk.addListener(events); continuation.resume(OperationResult.Success(Unit)) }
                        catch (_: SecurityException) { continuation.resume(OperationResult.Failure(FailureReason.PERMISSION_DENIED)) }
                        catch (_: Exception) { continuation.resume(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
                    }
                } }, onFailed = { error -> scope.launch {
                    if (token == epoch && continuation.isActive) continuation.resume(OperationResult.Failure(
                        if (error is SecurityException) FailureReason.PERMISSION_DENIED else FailureReason.SERVICE_UNAVAILABLE))
                } })
            } catch (_: SecurityException) { if (continuation.isActive) continuation.resume(OperationResult.Failure(FailureReason.PERMISSION_DENIED)) }
            catch (_: Exception) { if (continuation.isActive) continuation.resume(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
        }
    }
    override fun refresh() {
        val sdk = helper ?: return
        sdk.getRouteStateInfo() // Destination/waypoint queries follow only a confirmed active route.
    }
    override fun requestDestination(place: NavigationPlace) {
        requireNotNull(helper).requestRoute(RouteInfo(latitude = place.latitude, longitude = place.longitude,
            poiName = place.name, poiId = place.poiId, address = place.address, poiSubId = place.poiSubId,
            poiProvider = place.poiProvider))
    }
    override fun requestWaypoint(place: NavigationPlace, index: Int) {
        require(index in 0..1)
        requireNotNull(helper).addWaypoint(RequestWaypointInfo(latitude = place.latitude, longitude = place.longitude,
            poiName = place.name, waypointIndex = if (index == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
            poiId = place.poiId, address = place.address, poiSubId = place.poiSubId, poiProvider = place.poiProvider))
    }
    override fun requestSavedDestinations() { requireNotNull(helper).getBookmarkInfo() }
    override suspend fun disconnect() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        epoch++
        val sdk = helper; val events = listener
        helper = null; listener = null
        if (sdk != null) try { if (events != null) sdk.removeListener(events) } finally { sdk.release() }
    }
}
