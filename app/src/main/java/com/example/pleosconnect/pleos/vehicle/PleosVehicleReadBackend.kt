package com.example.pleosconnect.pleos.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.*

/** Actual read/capability APIs only. No setting, seat movement or raw payload logging. */
class PleosVehicleReadBackend(context: Context) : VehicleReadBackend {
    private val application = context.applicationContext
    private var vehicle: Vehicle? = null
    private var car: Car? = null

    private suspend fun <T> query(call: ((T?) -> Unit, (Exception) -> Unit) -> Unit): VehicleReading<T> =
        withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(3_000) {
                val result = CompletableDeferred<VehicleReading<T>>()
                try {
                    val success: (T?) -> Unit = { value ->
                        result.complete(if (value == null) VehicleReading(problem = VehicleReadProblem.INVALID_DATA)
                            else VehicleReading(value, SystemClock.elapsedRealtime()))
                    }
                    val failure: (Exception) -> Unit = { error ->
                        result.complete(VehicleReading(problem = if (error is SecurityException)
                            VehicleReadProblem.PERMISSION_DENIED else VehicleReadProblem.SERVICE_UNAVAILABLE))
                    }
                    try { call(success, failure) } catch (error: Exception) { failure(error) }
                    result.await()
                } finally { result.cancel() }
            } ?: VehicleReading(problem = VehicleReadProblem.TIMEOUT)
        }

    private suspend fun ensureCar(): Car? = withContext(Dispatchers.Main.immediate) {
        car?.takeIf { it.isConnected } ?: withTimeoutOrNull(3_000) {
            val result = CompletableDeferred<Car>()
            try {
                car?.disconnect()
                car = Car.createCar(application, Handler(Looper.getMainLooper()), Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                    if (ready) {
                        result.complete(connected)
                    }
                }
                result.await()
            } finally { result.cancel() }
        }
    }

    private suspend fun <T : Any> property(service: Car?, id: Int, type: Class<T>): VehicleReading<T> = try {
        if (service == null) VehicleReading(problem = VehicleReadProblem.TIMEOUT)
        else withContext(Dispatchers.IO) {
            val manager = service.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null) VehicleReading(problem = VehicleReadProblem.SERVICE_UNAVAILABLE)
            else {
                val value = manager.getProperty(type, id, 0)
                if (value == null || value.status != CarPropertyValue.STATUS_AVAILABLE)
                    VehicleReading(problem = VehicleReadProblem.SERVICE_UNAVAILABLE)
                else VehicleReading(value.value, SystemClock.elapsedRealtime())
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { VehicleReading(problem = VehicleReadProblem.PERMISSION_DENIED) }
    catch (_: IllegalArgumentException) { VehicleReading(problem = VehicleReadProblem.UNSUPPORTED) }
    catch (_: Exception) { VehicleReading(problem = VehicleReadProblem.SERVICE_UNAVAILABLE) }

    override suspend fun read(): VehicleReadSnapshot = withContext(Dispatchers.Main.immediate) {
        val sdk = vehicle ?: Vehicle(application).also { it.initialize(); vehicle = it }
        // Connect AAOS once before parallel property reads, avoiding duplicate Car clients.
        val nativeCar = try { ensureCar() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        coroutineScope {
            val gear = async { query<VehicleGear> { ok, fail -> sdk.getGear().getCurrentGearSelectionState(ok, fail) } }
            val brake = async {
                val domain = sdk.getBrake()
                val capability = query<CapabilityConfig> { ok, fail -> domain.checkBrakePedalStatusCapabilities({ ok(it) }, fail) }
                if (capability.value == null) VehicleReading<Boolean>(problem = capability.problem)
                else if (capability.value.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE))
                    VehicleReading(problem = VehicleReadProblem.UNSUPPORTED)
                else query<Boolean> { ok, fail -> domain.getCurrentBrakePedalStatus(ok, fail) }
            }
            val speed = async { property(nativeCar, VehiclePropertyIds.PERF_VEHICLE_SPEED, Float::class.javaObjectType).let {
                if (it.value != null && !it.value.isFinite()) VehicleReading<Float>(problem = VehicleReadProblem.INVALID_DATA) else it
            } }
            val parking = async { property(nativeCar, VehiclePropertyIds.PARKING_BRAKE_ON, Boolean::class.javaObjectType) }
            val climate = async { climate(sdk) }
            val windows = async { windows(sdk) }
            val power = async { power(sdk) }
            val rawGear = gear.await()
            VehicleReadSnapshot(rawGear.let { read -> VehicleReading(read.value?.let {
                when (it) {
                    VehicleGear.GEAR_PARK -> ReadGear.PARK
                    VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_1, VehicleGear.GEAR_2, VehicleGear.GEAR_3,
                    VehicleGear.GEAR_4, VehicleGear.GEAR_5, VehicleGear.GEAR_6, VehicleGear.GEAR_7,
                    VehicleGear.GEAR_8, VehicleGear.GEAR_9 -> ReadGear.DRIVE
                    VehicleGear.GEAR_REVERSE -> ReadGear.REVERSE
                    VehicleGear.GEAR_NEUTRAL -> ReadGear.NEUTRAL
                    else -> ReadGear.OTHER
                }
            }, read.observedAtMillis, read.problem) }, speed.await(), parking.await(), brake.await(), climate.await(), windows.await(), power.await())
        }
    }

    private suspend fun power(sdk: Vehicle): VehicleReading<List<VehicleAreaValue>> {
        val hvac = sdk.getHVAC()
        val capability = query<Pair<CapabilityConfig, List<SeatArea>>> { ok, fail ->
            hvac.checkHvacPowerCapabilities({ config, areas -> ok(config to areas) }, fail)
        }
        val data = capability.value ?: return VehicleReading(problem = capability.problem)
        if (data.first.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE) || data.second.isEmpty())
            return VehicleReading(problem = VehicleReadProblem.UNSUPPORTED)
        val values = coroutineScope { data.second.map { area -> async {
            val read = query<Boolean> { ok, fail -> hvac.getHvacPowerState(area, ok, fail) }
            VehicleAreaValue(area.values.joinToString { it.name }, read.value?.let { if (it) "켜짐" else "꺼짐" },
                null, null, read.problem, data.first.accessType == AccessType.READ_WRITE, read.observedAtMillis)
        } }.awaitAll() }
        return VehicleReading(values, SystemClock.elapsedRealtime())
    }
    private suspend fun climate(sdk: Vehicle): VehicleReading<List<VehicleAreaValue>> {
        val hvac = sdk.getHVAC()
        val capability = query<Pair<CapabilityConfig, Map<SeatArea, AreaBounds<Float>>>> { ok, fail ->
            hvac.checkHvacTemperatureCapabilities({ config, areas, _ -> ok(config to areas) }, fail)
        }
        val data = capability.value ?: return VehicleReading(problem = capability.problem)
        if (data.first.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE) || data.second.isEmpty())
            return VehicleReading(problem = VehicleReadProblem.UNSUPPORTED)
        val values = coroutineScope { data.second.entries.map { (area, bounds) -> async {
            val read = query<Float> { ok, fail -> hvac.getHvacTemperature(area, TemperatureUnit.CELSIUS, ok, fail) }
            val invalid = read.value?.let { !it.isFinite() || !bounds.minValue.isFinite() || !bounds.maxValue.isFinite() ||
                bounds.minValue > bounds.maxValue || it !in bounds.minValue..bounds.maxValue } == true
            VehicleAreaValue(area.values.joinToString { it.name }, if (invalid) null else read.value?.toString(),
                bounds.minValue.toString(), bounds.maxValue.toString(), if (invalid) VehicleReadProblem.INVALID_DATA else read.problem,
                data.first.accessType == AccessType.READ_WRITE, read.observedAtMillis)
        } }.awaitAll() }
        return VehicleReading(values, SystemClock.elapsedRealtime())
    }
    private suspend fun windows(sdk: Vehicle): VehicleReading<List<VehicleAreaValue>> {
        val windows = sdk.getWindow()
        val capability = query<Pair<CapabilityConfig, Map<WindowArea, AreaBounds<Int>>>> { ok, fail ->
            windows.checkWindowPositionCapabilities({ config, areas -> ok(config to areas) }, fail)
        }
        val data = capability.value ?: return VehicleReading(problem = capability.problem)
        if (data.first.accessType !in setOf(AccessType.READ, AccessType.READ_WRITE) || data.second.isEmpty())
            return VehicleReading(problem = VehicleReadProblem.UNSUPPORTED)
        val values = coroutineScope { data.second.entries.map { (area, bounds) -> async {
            val read = query<Int> { ok, fail -> windows.getCurrentWindowPosition(area, ok, fail) }
            val invalid = bounds.minValue > bounds.maxValue || read.value?.let { it !in bounds.minValue..bounds.maxValue } == true
            VehicleAreaValue(area.values.joinToString { it.name }, if (invalid) null else read.value?.toString(),
                bounds.minValue.toString(), bounds.maxValue.toString(), if (invalid) VehicleReadProblem.INVALID_DATA else read.problem,
                data.first.accessType == AccessType.READ_WRITE, read.observedAtMillis)
        } }.awaitAll() }
        return VehicleReading(values, SystemClock.elapsedRealtime())
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        val sdk = vehicle; vehicle = null
        val currentCar = car; car = null
        try { sdk?.release() } finally { currentCar?.disconnect() }
        Unit
    }
}
