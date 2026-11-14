package com.example.pleosconnect.ui

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.VoicePhase
import com.example.pleosconnect.domain.*
import com.example.pleosconnect.domain.rest.RestStatus
import com.example.pleosconnect.integration.SimulationScenario

data class DriveMateUiState(
    val profileName: String = "가상 운전자 1",
    val scenario: SimulationScenario = SimulationScenario.NORMAL,
    val foreground: Boolean = false,
    val busy: Boolean = false,
    val driver: DriverStatus = DriverStatus(),
    val safety: SafetyResponse = SafetyResponse(),
    val mode: NormalModeStatus = NormalModeStatus(),
    val rest: RestStatus = RestStatus(),
    val guidance: RestGuidanceStatus = RestGuidanceStatus(),
    val route: RouteProgress? = null,
    val pendingCount: Int = 0,
    val voicePhase: VoicePhase = VoicePhase.IDLE,
    val prompt: String = "아직 시작하지 않았습니다.",
    val mediaCommand: MediaCommand? = null,
    val failure: FailureReason? = null,
    val voiceFailure: FailureReason? = null,
    val notice: String? = null,
    val localProfiles: Boolean = false,
    val profilesReady: Boolean = true,
    val profileWorking: Boolean = false,
    val profiles: List<DriverPreferences> = emptyList(),
    val selectedProfileId: String? = null,
    val preferences: DriverPreferences? = null,
    val profileFailure: FailureReason? = null,
    val profileOperation: Int = 0,
    val liveNavigation: Boolean = false,
    val realVoice: Boolean = false,
) {
    val canManageProfiles: Boolean get() = foreground && profilesReady && !profileWorking
    val canAct: Boolean get() = !liveNavigation && foreground && !busy && voicePhase == VoicePhase.IDLE && !profileWorking &&
        (!localProfiles || profilesReady && selectedProfileId != null)
    val failures: List<FailureReason> get() = listOfNotNull(failure, voiceFailure, safety.failure, mode.failure, rest.failure, guidance.failure).distinct()
}
