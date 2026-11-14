package com.example.pleosconnect.integration

import android.content.Context
import android.os.SystemClock
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.pleos.vehicle.*
import kotlinx.coroutines.*

/** Separate opt-in UI connection. No automatic actions from simulation or lifecycle callbacks. */
class LiveVehicleControlIntegration(
    private val backend: VehicleSettingsBackend,
    clock: MonotonicClock,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val controller = ConsentedVehicleSettings(backend, clock)
    val state = controller.state
    private var active = false
    private var closed = false
    private var work: Job? = null
    suspend fun foreground() { active = true }
    fun suspendCommands() { active = false; controller.invalidate(); work?.cancel() }
    fun cancelCommands() { controller.invalidate(); work?.cancel() }
    suspend fun stop() {
        controller.invalidate()
        work?.cancelAndJoin(); work = null
        backend.close()
    }
    suspend fun background() { active = false; stop() }
    fun prepareConnection(profileId: String?) = launch {
        controller.invalidate(); backend.close(); controller.open(profileId)
    }
    fun prepare(key: VehicleSettingKey, target: Double) = launch { controller.prepare(key, target) }
    fun restore(id: String) = launch { controller.prepareRestore(id) }
    fun confirm(id: String) = launch { controller.confirmFromUi(id) }
    fun cancelReview() { controller.cancelReview() }
    private fun launch(block: suspend () -> Unit) {
        if (closed || !active || work?.isActive == true) return
        controller.setBusy(true)
        work = scope.launch {
            try { withTimeout(30_000) { block() } }
            catch (timeout: TimeoutCancellationException) { controller.fail(FailureReason.TIMEOUT) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { controller.fail(FailureReason.PERMISSION_DENIED) }
            catch (_: Exception) { controller.fail(FailureReason.SERVICE_UNAVAILABLE) }
            finally { controller.setBusy(false) }
        }
    }
    suspend fun close() {
        if (closed) return
        closed = true; active = false
        withContext(NonCancellable) { try { stop() } finally { scope.cancel() } }
    }
    companion object {
        fun create(context: Context) = LiveVehicleControlIntegration(PleosVehicleSettingsBackend(context.applicationContext),
            MonotonicClock { SystemClock.elapsedRealtime() })
    }
}
