package com.example.pleosconnect.integration

import android.content.Context
import android.os.SystemClock
import com.example.pleosconnect.contract.integration.RouteProgress
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.pleos.navigation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LiveNavigationUiState(val available: Boolean = false, val enabled: Boolean = false,
    val connected: Boolean = false, val busy: Boolean = false, val reconnectRequired: Boolean = false,
    val failure: FailureReason? = null, val route: RouteProgress? = null, val destinationName: String? = null,
    val remainingMinutes: Long? = null, val readReady: Boolean = false, val termsAccepted: Boolean? = null,
    val savedDestinations: List<NavigationPlace>? = null)

/** Independent SDK lifetime. A's mock dialogue never receives authority to mutate live navigation. */
class LiveNavigationIntegration(
    private val clock: MonotonicClock,
    backendFactory: (CoroutineScope) -> NavigationBackend,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val gate = NavigationConsentGate(clock)
    val controller = PleosNavigationController(backendFactory(scope), clock, gate)
    val finder = VerifiedRestStopFinder(controller)
    val voiceNavigation = controller.observedVoiceRoute
    val voiceFinder = object : com.example.pleosconnect.contract.integration.RestStopFinder {
        override suspend fun find(request: com.example.pleosconnect.contract.integration.RestStopSearch) = OperationResult.Failure(FailureReason.UNSUPPORTED)
    }
    private val mutableState = MutableStateFlow(LiveNavigationUiState(available = true))
    val state = mutableState.asStateFlow()
    val enabled get() = state.value.enabled
    private var active = false
    private var operation: Job? = null
    private var connection: Job? = null
    private var closed = false
    init {
        scope.launch { controller.status.collect { status -> mutableState.update { it.copy(connected = status.connected,
            busy = status.busy || operation?.isActive == true, failure = status.failure, reconnectRequired = status.reconnectRequired,
            readReady = status.readReady, termsAccepted = status.termsAccepted) } } }
        scope.launch { controller.route.collect { route -> mutableState.update {
            it.copy(route = route, destinationName = if (route == null) null else it.destinationName, remainingMinutes = remaining(route))
        } } }
        scope.launch { controller.savedDestinations.collect { places -> mutableState.update { it.copy(savedDestinations = places) } } }
    }
    fun bindProfile(id: String?) { controller.bindProfile(id) }
    suspend fun setEnabled(value: Boolean) {
        pause()
        mutableState.update { it.copy(enabled = value, failure = null) }
        if (active && value) startConnection()
    }
    suspend fun foreground() { active = true; if (enabled && connection == null) startConnection() }
    suspend fun background() { active = false; pause() }
    suspend fun beforeProfileChange() { pause() }
    fun afterProfileChange() { if (active && enabled && connection == null) startConnection() }
    private fun startConnection() {
        if (closed) return
        connection = scope.launch {
            try {
                controller.connect()
                while (isActive) {
                    delay(5_000)
                    controller.refresh()
                    mutableState.update { it.copy(remainingMinutes = remaining(controller.route.value)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failure = FailureReason.SERVICE_UNAVAILABLE) } }
        }
    }
    private fun remaining(route: RouteProgress?): Long? = route?.let {
        ((it.remaining.inWholeMilliseconds - (clock.nowMillis() - it.observedAtMillis)).coerceAtLeast(0) + 59_999) / 60_000
    }
    private suspend fun pause() {
        operation?.cancelAndJoin(); operation = null
        connection?.cancelAndJoin(); connection = null
        controller.disconnect()
        mutableState.update { it.copy(busy = false) }
    }
    fun destinationConfirmedFromUi(place: NavigationPlace) = launchOperation {
        if (!place.valid()) return@launchOperation OperationResult.Failure(FailureReason.INVALID_REQUEST)
        val request = controller.registerDestination(place)
        val consent = gate.confirmFromUi(ConsentScope.DESTINATION, request.requestId, null)
        controller.setDestination(request, consent).also { result ->
            if (result is OperationResult.Success) mutableState.update { it.copy(destinationName = place.name) }
        }
    }
    fun resumeConfirmedFromUi() = launchOperation {
        val route = controller.route.value ?: return@launchOperation OperationResult.Failure(FailureReason.STALE_REQUEST)
        controller.resumeRoute(route.routeId, gate.confirmFromUi(ConsentScope.RESUME_ROUTE, route.routeId, route.revision))
    }
    fun loadSavedDestinations() = launchOperation { controller.loadSavedDestinations() }
    private fun launchOperation(block: suspend () -> OperationResult<Unit>) {
        if (closed || !active || !enabled || !state.value.connected || !state.value.readReady || state.value.termsAccepted != true ||
            state.value.busy || operation?.isActive == true) return
        mutableState.update { it.copy(busy = true, failure = null) }
        operation = scope.launch {
            try {
                val result = block()
                if (result is OperationResult.Failure) mutableState.update { it.copy(failure = result.reason) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failure = FailureReason.SERVICE_UNAVAILABLE) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) { pause(); scope.cancel() }
    }
    companion object {
        fun create(context: Context) = LiveNavigationIntegration(MonotonicClock { SystemClock.elapsedRealtime() },
            { scope -> PleosNaviBackend(context.applicationContext, scope) })
    }
}
