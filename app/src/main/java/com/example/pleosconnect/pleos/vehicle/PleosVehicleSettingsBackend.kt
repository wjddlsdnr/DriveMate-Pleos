package com.example.pleosconnect.pleos.vehicle

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*

/** Typed SDK commands, with no automatic multi-setting action or rollback. */
class PleosVehicleSettingsBackend(context: Context) : VehicleSettingsBackend {
    private val application = context.applicationContext
    private val readings = PleosVehicleReadBackend(application)
    private var vehicle: Vehicle? = null
    private val removals = mutableListOf<() -> Unit>()
    private var epoch = 0L
    private fun sdk() = vehicle ?: Vehicle(application).also { it.initialize(); vehicle = it }
    private fun seat(key: VehicleSettingKey) = SeatArea(*key.area.split(", ").map { SeatValue.valueOf(it) }.toTypedArray())
    private fun window(key: VehicleSettingKey) = WindowArea(*key.area.split(", ").map { WindowValue.valueOf(it) }.toTypedArray())
    private fun permitted(kind: VehicleSettingKind) = application.checkSelfPermission(
        if (kind == VehicleSettingKind.CLIMATE_POWER) "pleos.car.permission.CONTROL_CAR_CLIMATE"
        else "pleos.car.permission.CONTROL_CAR_WINDOWS") == PackageManager.PERMISSION_GRANTED

    override suspend fun inspect(): VehicleControlInspection {
        val snapshot = readings.read()
        val now = SystemClock.elapsedRealtime()
        val settings = buildList {
            if (permitted(VehicleSettingKind.CLIMATE_POWER) && snapshot.climatePower.fresh(now)) snapshot.climatePower.value.orEmpty().forEach { area ->
                if (area.readWrite && area.fresh(now) && area.value in setOf("켜짐", "꺼짐"))
                    add(VehicleSetting(VehicleSettingKey(VehicleSettingKind.CLIMATE_POWER, area.area),
                        if (area.value == "켜짐") 1.0 else 0.0, 0.0, 1.0, requireNotNull(area.observedAtMillis)))
            }
            if (permitted(VehicleSettingKind.WINDOW_POSITION) && snapshot.windows.fresh(now)) snapshot.windows.value.orEmpty().forEach { area ->
                val value = area.value?.toDoubleOrNull(); val minimum = area.minimum?.toDoubleOrNull(); val maximum = area.maximum?.toDoubleOrNull()
                if (area.readWrite && area.fresh(now) && value != null && minimum != null && maximum != null) {
                    val setting = VehicleSetting(VehicleSettingKey(VehicleSettingKind.WINDOW_POSITION, area.area), value, minimum, maximum,
                        requireNotNull(area.observedAtMillis))
                    if (setting.accepts(value)) add(setting)
                }
            }
        }
        return VehicleControlInspection(snapshot, settings)
    }
    override suspend fun monitor(settings: List<VehicleSetting>, listener: (VehicleSettingChange) -> Unit) = withContext(Dispatchers.Main.immediate) {
        val old = removals.toList(); removals.clear(); epoch++
        old.forEach { runCatching(it) }
        val sdk = sdk(); val token = epoch
        settings.forEach { setting ->
            val key = setting.key
            fun emit(value: Double?) { if (epoch == token) listener(VehicleSettingChange(key, value)) }
            when (key.kind) {
                VehicleSettingKind.CLIMATE_POWER -> {
                    val area = seat(key)
                    val events = object : HvacPowerStateListener {
                        override fun onHvacPowerStateUpdated(value: Boolean?) { emit(value?.let { if (it) 1.0 else 0.0 }) }
                        override fun onFailed(e: Exception) { emit(null) }
                    }
                    sdk.getHVAC().registerHvacPowerState(area, events)
                    removals += { sdk.getHVAC().unregisterHvacPowerState(area, events) }
                }
                VehicleSettingKind.WINDOW_POSITION -> {
                    val area = window(key)
                    val events = object : WindowPositionListener {
                        override fun onWindowPositionUpdated(value: Int?) { emit(value?.toDouble()) }
                        override fun onFailed(e: Exception) { emit(null) }
                    }
                    sdk.getWindow().registerWindowPosition(area, events)
                    removals += { sdk.getWindow().unregisterWindowPosition(area, events) }
                }
            }
        }
    }
    override suspend fun write(key: VehicleSettingKey, value: Double): OperationResult<Unit> = withContext(Dispatchers.Main.immediate) {
        if (!permitted(key.kind)) return@withContext OperationResult.Failure(FailureReason.PERMISSION_DENIED)
        val sdk = sdk()
        val result = CompletableDeferred<OperationResult<Unit>>()
        try {
            val fail: (Exception) -> Unit = { result.complete(OperationResult.Failure(
                if (it is SecurityException) FailureReason.PERMISSION_DENIED else FailureReason.SERVICE_UNAVAILABLE)) }
            when (key.kind) {
                VehicleSettingKind.CLIMATE_POWER -> {
                    require(value in setOf(0.0, 1.0))
                    val area = seat(key)
                    sdk.getHVAC().setHvacPower(area, value == 1.0, { actualArea, actual ->
                        result.complete(if (actualArea == area && actual == (value == 1.0)) OperationResult.Success(Unit)
                            else OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE))
                    }, fail)
                }
                VehicleSettingKind.WINDOW_POSITION -> {
                    require(value.isFinite() && value == value.toInt().toDouble())
                    val area = window(key)
                    sdk.getWindow().setWindowPosition(area, value.toInt(), { actualArea, actual ->
                        result.complete(if (actualArea == area && actual?.toDouble() == value) OperationResult.Success(Unit)
                            else OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE))
                    }, fail)
                }
            }
            withTimeoutOrNull(5_000) { result.await() } ?: OperationResult.Failure(FailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        finally { result.cancel() }
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        epoch++
        val old = removals.toList(); removals.clear()
        old.forEach { runCatching(it) }
        val sdk = vehicle; vehicle = null
        try { sdk?.release() } finally { readings.close() }
        Unit
    }
}
