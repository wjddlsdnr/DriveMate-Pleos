package com.example.pleosconnect.pleos.vehicle

import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.contract.model.OperationResult

enum class VehicleSettingKind { CLIMATE_POWER, WINDOW_POSITION }
data class VehicleSettingKey(val kind: VehicleSettingKind, val area: String)
data class VehicleSetting(val key: VehicleSettingKey, val value: Double, val minimum: Double, val maximum: Double,
    val observedAtMillis: Long) {
    fun accepts(target: Double) = target.isFinite() && minimum.isFinite() && maximum.isFinite() && minimum <= maximum &&
        target in minimum..maximum && target == kotlin.math.round(target) &&
        (key.kind != VehicleSettingKind.CLIMATE_POWER || target in setOf(0.0, 1.0))
}
data class VehicleControlInspection(val signals: VehicleReadSnapshot, val settings: List<VehicleSetting>)
data class VehicleSettingChange(val key: VehicleSettingKey, val value: Double?)
data class VehicleChangeReview(val id: String, val key: VehicleSettingKey, val original: Double, val target: Double,
    val restoring: Boolean = false, val adjustmentId: String? = null)
data class VehicleOwnedChange(val id: String, val key: VehicleSettingKey, val original: Double, val applied: Double,
    val restorable: Boolean, val reason: String? = null)
data class VehicleControlUiState(val available: Boolean = false, val enabled: Boolean = false, val busy: Boolean = false,
    val settings: List<VehicleSetting> = emptyList(), val review: VehicleChangeReview? = null,
    val changes: List<VehicleOwnedChange> = emptyList(), val failure: FailureReason? = null, val notice: String? = null)

interface VehicleSettingsBackend {
    suspend fun inspect(): VehicleControlInspection
    suspend fun monitor(settings: List<VehicleSetting>, listener: (VehicleSettingChange) -> Unit)
    suspend fun write(key: VehicleSettingKey, value: Double): OperationResult<Unit>
    suspend fun close()
}
