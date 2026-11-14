package com.example.pleosconnect.pleos.vehicle

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.domain.SafeParking
import com.example.pleosconnect.domain.SafeParkingCheck
import kotlin.math.abs

enum class VehicleReadProblem { UNSUPPORTED, PERMISSION_DENIED, SERVICE_UNAVAILABLE, TIMEOUT, INVALID_DATA }
data class VehicleReading<T>(val value: T? = null, val observedAtMillis: Long? = null, val problem: VehicleReadProblem? = null) {
    fun fresh(now: Long) = value != null && problem == null && observedAtMillis?.let { it >= 0 && now - it in 0..5_000 } == true
}
enum class ReadGear { PARK, DRIVE, REVERSE, NEUTRAL, OTHER }
data class VehicleAreaValue(val area: String, val value: String?, val minimum: String?, val maximum: String?,
    val problem: VehicleReadProblem? = null, val readWrite: Boolean = false, val observedAtMillis: Long? = null) {
    fun fresh(now: Long) = value != null && problem == null && observedAtMillis?.let { it >= 0 && now - it in 0..5_000 } == true
}
data class VehicleReadSnapshot(
    val gear: VehicleReading<ReadGear> = VehicleReading(),
    val speedMetersPerSecond: VehicleReading<Float> = VehicleReading(),
    val parkingBrake: VehicleReading<Boolean> = VehicleReading(),
    val brakePedal: VehicleReading<Boolean> = VehicleReading(),
    val climate: VehicleReading<List<VehicleAreaValue>> = VehicleReading(),
    val windows: VehicleReading<List<VehicleAreaValue>> = VehicleReading(),
    val climatePower: VehicleReading<List<VehicleAreaValue>> = VehicleReading(),
)
data class VehicleReadUiState(val available: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false,
    val snapshot: VehicleReadSnapshot = VehicleReadSnapshot(), val parking: SafeParking = SafeParking.UNKNOWN,
    val nowMillis: Long = 0)

interface VehicleReadBackend {
    suspend fun read(): VehicleReadSnapshot
    suspend fun close()
}

/** Signals can disprove parking; they do not prove a legal/safe rest location. Never returns VERIFIED. */
class VehicleSafeParkingCheck(private val snapshot: () -> VehicleReadSnapshot?, private val clock: MonotonicClock) : SafeParkingCheck {
    override fun current(): SafeParking {
        val state = snapshot() ?: return SafeParking.UNKNOWN
        val now = clock.nowMillis()
        val speed = state.speedMetersPerSecond
        if (speed.fresh(now) && speed.value?.isFinite() == true && abs(requireNotNull(speed.value)) > 0.05f)
            return SafeParking.NOT_PARKED
        val gear = state.gear
        if (gear.fresh(now) && gear.value in setOf(ReadGear.DRIVE, ReadGear.REVERSE, ReadGear.NEUTRAL))
            return SafeParking.NOT_PARKED
        return SafeParking.UNKNOWN
    }
}
