package com.example.pleosconnect.integration

import android.content.Context
import android.os.SystemClock
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.pleos.vehicle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Opt-in read-only connection; never exposes Vehicle SDK control methods to A or UI. */
class LiveVehicleReadIntegration(
    private val backend: VehicleReadBackend,
    private val clock: MonotonicClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(VehicleReadUiState(available = true))
    val state = mutableState.asStateFlow()
    val parking = VehicleSafeParkingCheck({ state.value.takeIf { it.enabled && active }?.snapshot }, clock)
    private var active = false
    private var closed = false
    private var polling: Job? = null
    private var aging: Job? = null
    suspend fun setEnabled(enabled: Boolean) {
        stop()
        mutableState.update { it.copy(enabled = enabled) }
        start()
    }
    suspend fun foreground() { active = true; start() }
    suspend fun background() { active = false; stop() }
    private fun start() {
        if (closed || !active || !state.value.enabled || polling != null) return
        aging = scope.launch {
            while (isActive) {
                mutableState.update { it.copy(nowMillis = clock.nowMillis(), parking = parking.current()) }
                delay(1_000)
            }
        }
        polling = scope.launch {
            while (isActive) {
                mutableState.update { it.copy(busy = true) }
                try {
                    val snapshot = withTimeoutOrNull(9_000) { backend.read() }
                        ?: unavailable(VehicleReadProblem.TIMEOUT)
                    mutableState.update { it.copy(snapshot = snapshot, busy = false, nowMillis = clock.nowMillis()) }
                    mutableState.update { it.copy(parking = parking.current()) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableState.update { it.copy(snapshot = unavailable(VehicleReadProblem.SERVICE_UNAVAILABLE), busy = false) } }
                delay(3_000)
            }
        }
    }
    private fun unavailable(problem: VehicleReadProblem) = VehicleReadSnapshot(
        VehicleReading(problem = problem), VehicleReading(problem = problem), VehicleReading(problem = problem),
        VehicleReading(problem = problem), VehicleReading(problem = problem), VehicleReading(problem = problem), VehicleReading(problem = problem))
    private suspend fun stop() {
        polling?.cancelAndJoin(); polling = null
        aging?.cancelAndJoin(); aging = null
        try { backend.close() } finally {
            mutableState.update { it.copy(busy = false, snapshot = VehicleReadSnapshot(), parking = com.example.pleosconnect.domain.SafeParking.UNKNOWN) }
        }
    }
    suspend fun close() {
        if (closed) return
        closed = true; active = false
        withContext(NonCancellable) { try { stop() } finally { scope.cancel() } }
    }
    companion object {
        fun create(context: Context) = LiveVehicleReadIntegration(PleosVehicleReadBackend(context.applicationContext),
            MonotonicClock { SystemClock.elapsedRealtime() })
    }
}
