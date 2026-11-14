package com.example.pleosconnect.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.domain.RestGuidancePhase
import com.example.pleosconnect.integration.*
import com.example.pleosconnect.data.profile.ProfileDraft
import com.example.pleosconnect.ui.profile.ProfileActions
import com.example.pleosconnect.ui.profile.ProfilePanel
import com.example.pleosconnect.ui.navigation.NavigationPanel
import com.example.pleosconnect.pleos.navigation.NavigationPlace

@Composable
fun DriveMateScreen(
    state: DriveMateUiState,
    onAction: (SimulationAction) -> Unit,
    onScenario: (SimulationScenario) -> Unit,
    onSwitchProfile: () -> Unit,
    onRegisterProfile: (ProfileDraft) -> Unit = {},
    onSelectProfile: (String) -> Unit = {},
    onSaveProfile: (String, ProfileDraft) -> Unit = { _, _ -> },
    onDeleteProfile: (String) -> Unit = {},
    onReloadProfiles: () -> Unit = {},
    navigationState: LiveNavigationUiState = LiveNavigationUiState(),
    onLiveMode: (Boolean) -> Unit = {},
    onLiveDestination: (NavigationPlace) -> Unit = {},
    onLiveResume: () -> Unit = {},
    onLoadNavigationDestinations: () -> Unit = {},
    vehicleState: com.example.pleosconnect.pleos.vehicle.VehicleReadUiState = com.example.pleosconnect.pleos.vehicle.VehicleReadUiState(),
    onVehicleRead: (Boolean) -> Unit = {},
    vehicleControlState: com.example.pleosconnect.pleos.vehicle.VehicleControlUiState = com.example.pleosconnect.pleos.vehicle.VehicleControlUiState(),
    onPrepareVehicleControl: () -> Unit = {},
    onPrepareVehicleSetting: (com.example.pleosconnect.pleos.vehicle.VehicleSettingKey, Double) -> Unit = { _, _ -> },
    onRestoreVehicleSetting: (String) -> Unit = {},
    onConfirmVehicleSetting: (String) -> Unit = {},
    onCancelVehicleSetting: () -> Unit = {},
    onStopVehicleControl: () -> Unit = {},
    mediaState: LiveMediaUiState = LiveMediaUiState(),
    mediaActions: com.example.pleosconnect.ui.media.MediaActions = com.example.pleosconnect.ui.media.MediaActions(),
    localAudio: com.example.pleosconnect.data.media.LocalAudioState = com.example.pleosconnect.data.media.LocalAudioState(),
    onChooseAudio: () -> Unit = {}, onPlayAudio: () -> Unit = {}, onStopAudio: () -> Unit = {},
    onChooseTestAudio: (() -> Unit)? = null,
    liveVoiceAvailable: Boolean = false, liveVoiceEnabled: Boolean = false,
    onVoiceMode: (Boolean) -> Unit = {}, onVoiceCheck: (Boolean) -> Unit = {},
    voiceConnectionNotice: String? = null,
    nearbyAvailable: Boolean = false,
    nearbyState: com.example.pleosconnect.pleos.navigation.NearbyDiscoveryState = com.example.pleosconnect.pleos.navigation.NearbyDiscoveryState(),
    onPrepareNearby: (String) -> Unit = {}, onConfirmNearby: () -> Unit = {}, onCancelNearby: () -> Unit = {},
) {
    val profileActions = ProfileActions(onRegisterProfile, onSelectProfile, onSaveProfile, onDeleteProfile, onReloadProfiles)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(androidx.compose.ui.res.stringResource(com.example.pleosconnect.R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                    Text("운전자 상태에 맞춘 주행 지원", style = MaterialTheme.typography.bodyLarge)
                }
                Text(if (state.realVoice) "실제 내비·음성 연결" else if (state.liveNavigation) "실제 내비 연동 점검" else "시뮬레이션 모드", Modifier.testTag("simulation-banner"),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Text(if (state.realVoice) "실제 마이크·음성 응답을 사용합니다. 뉴스와 음성 차량 변경은 미지원이며 경로 변경에는 별도 동의와 유효한 후보가 필요합니다."
                    else if (state.liveNavigation) "내비게이션을 실제 연결합니다. 실제 음성은 별도로 켠 뒤 출발 확인 버튼으로 시작합니다. 차량 기능과 음악 앱도 아래에서 별도로 준비합니다."
                    else "주행 체험의 응답·동의는 가상입니다. 실제 마이크는 연결하지 않습니다. 차량 기능과 실제 음악 앱은 아래에서 별도로 준비합니다. 프로필은 기기에 저장됩니다.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            if (navigationState.available) NavigationPanel(state, navigationState, onLiveMode, onLiveDestination, onLiveResume, onLoadNavigationDestinations)
            if (nearbyAvailable) com.example.pleosconnect.ui.navigation.NearbyDiscoveryPanel(nearbyState,
                state.foreground && state.selectedProfileId != null && navigationState.enabled && !liveVoiceEnabled && mediaSetupAllowed(vehicleState),
                onPrepareNearby, onConfirmNearby, onCancelNearby)
            if (vehicleState.available) com.example.pleosconnect.ui.vehicle.VehicleReadPanel(vehicleState, state.foreground, onVehicleRead)
            if (vehicleControlState.available) com.example.pleosconnect.ui.vehicle.VehicleControlPanel(vehicleControlState,
                state.foreground && vehicleState.enabled && state.selectedProfileId != null,
                onPrepareVehicleControl, onPrepareVehicleSetting, onRestoreVehicleSetting, onConfirmVehicleSetting,
                onCancelVehicleSetting, onStopVehicleControl)
            if (liveVoiceAvailable) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("실제 음성 · 사용자 음악 설정 연결", style = MaterialTheme.typography.titleLarge)
                    Text("실제 내비 모드와 프로필이 필요합니다. 기본 음악 앱을 저장하고 연결하거나, 기본 앱 없이 로컬 파일을 선택하세요. 켜기만 해서는 마이크를 열지 않습니다.")
                    Text("출발 상태 질문에 답한 뒤 모드 질문에 ‘음악 틀어줘’라고 말하세요. 기본 앱이 없으면 ‘로컬 파일 틀어줘’만 지원합니다. 뉴스·음성 차량 변경은 미지원입니다.")
                    Text("질문·청취 전에 선택한 재생을 중단합니다. 중단을 확인하지 못하면 음성을 시작하지 않습니다. 질문 뒤 자동 음악 재개와 백그라운드 음성 재개는 없습니다.")
                    val requirements = voiceSetupRequirements(state.foreground, state.liveNavigation, state.selectedProfileId,
                        mediaState, localAudio, vehicleState)
                    OutlinedButton({ onVoiceMode(!liveVoiceEnabled) }, enabled = state.foreground &&
                        (liveVoiceEnabled || requirements.isEmpty())) {
                        Text(if (liveVoiceEnabled) "실제 음성 연결 끄기" else "실제 음성 연결 켜기")
                    }
                    if (!liveVoiceEnabled) requirements.forEach { Text("연결 준비: $it", color = MaterialTheme.colorScheme.error) }
                    voiceConnectionNotice?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                    if (liveVoiceEnabled) Text("다음 단계: 아래 ‘음성 출발 확인’을 눌러 질문을 시작하세요.")
                    if (liveVoiceEnabled && state.route == null) Text("출발 확인 대기: 내비에서 안내 중인 경로와 남은 시간을 아직 확인하지 못했습니다. 내비 안내가 진행 중인지 위 내비 카드에서 확인해 주세요.", color = MaterialTheme.colorScheme.error)
                    Text("음성 상태: ${state.voicePhase} · 실제 음성 ${if (liveVoiceEnabled) "켜짐" else "꺼짐"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val canAsk = liveVoiceEnabled && state.realVoice && state.foreground && !state.busy && state.route != null
                        Button({ onVoiceCheck(true) }, enabled = canAsk) { Text("음성 출발 확인") }
                        OutlinedButton({ onVoiceCheck(false) }, enabled = canAsk) { Text("음성 상태 다시 확인") }
                    }
                    Text("출발 모드 질문은 경로·세션별 한 번입니다. 다시 시험하려면 음성 연결을 껐다 켜고 출발 확인을 누르세요.")
                }
            }
            if (localAudio.available) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("로컬 오디오 · 실제 재생", style = MaterialTheme.typography.titleLarge)
                    Text("파일 선택 후 재생 버튼으로 시작합니다. 다른 오디오가 포커스를 요청하거나 앱이 백그라운드로 이동하면 중단하며 자동 재개하지 않습니다.")
                    Text("파일과 위치는 프로필에 저장하지 않습니다. 뉴스는 미지원이며 실제 음성 흐름은 위에서 별도로 연결하세요.")
                    val setup = !liveVoiceEnabled && state.foreground && mediaSetupAllowed(vehicleState)
                    if (onChooseTestAudio != null) {
                        Text("기기에서 파일 선택을 지원하지 않으면 개발용 테스트 음으로 재생·중단을 확인하세요. 실제 음악·뉴스 콘텐츠가 아닙니다.")
                        OutlinedButton(onChooseTestAudio, enabled = setup && !localAudio.preparing) { Text("테스트 음 선택 · 8초") }
                    }
                    if (!setup) Text("파일 선택·재생 시작은 차량 조회를 켜고 최신 P단·정지 신호를 확인한 뒤 가능합니다.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onChooseAudio, enabled = setup && !localAudio.preparing) { Text("오디오 파일 선택") }
                        Button(onPlayAudio, enabled = setup && localAudio.selected && !localAudio.preparing && !localAudio.playing) { Text("선택한 파일 재생") }
                        OutlinedButton(onStopAudio, enabled = state.foreground && (localAudio.preparing || localAudio.playing)) { Text("로컬 재생 중단") }
                    }
                    Text(localAudio.notice)
                    localAudio.failure?.let { Text("확인 필요: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
            if (mediaState.available) com.example.pleosconnect.ui.media.MediaPanel(mediaState,
                !liveVoiceEnabled && state.foreground && mediaSetupAllowed(vehicleState), state.foreground,
                (state.preferences?.artists.orEmpty() + state.preferences?.genres.orEmpty()).firstOrNull().orEmpty(), mediaActions)
            BoxWithConstraints {
                if (maxWidth >= 800.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            StatusCard(state)
                            SessionControls(state, onAction)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ScenarioCard(state, onScenario, onSwitchProfile, profileActions)
                            DetailCard(state)
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusCard(state)
                        ScenarioCard(state, onScenario, onSwitchProfile, profileActions)
                        SessionControls(state, onAction)
                        DetailCard(state)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: DriveMateUiState) = Panel(if (state.realVoice) "현재 상태 · 실제 음성 응답" else "현재 상태 · 시뮬레이션") {
    Text(driverStatusLabel(state.driver), Modifier.testTag("driver-state"), style = MaterialTheme.typography.headlineMedium)
    Text(if (state.driver.report == ConditionReport.UNCONFIRMED) "운전자 응답이 확인되지 않았습니다." else if (state.realVoice) "실제 음성 응답에 따른 상태이며 생체 졸음 감지가 아닙니다." else "미리 정한 응답에 따른 결과입니다.")
    HorizontalDivider()
    Text("안전 주차: 미확인", Modifier.testTag("parking-state"), style = MaterialTheme.typography.titleMedium)
    Text("휴식 타이머는 안전 주차를 확인한 뒤 사용할 수 있습니다.")
    state.failures.forEach { Text("확인 필요: ${failureLabel(it)}", color = MaterialTheme.colorScheme.error) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun ScenarioCard(state: DriveMateUiState, onScenario: (SimulationScenario) -> Unit, onSwitchProfile: () -> Unit,
    profileActions: ProfileActions) = Panel("프로필과 체험 설정") {
    var expanded by remember { mutableStateOf(false) }
    if (state.localProfiles) ProfilePanel(state, profileActions)
    else {
        Text(state.profileName, style = MaterialTheme.typography.titleLarge)
        Text("가상 프로필은 메모리에만 보관됩니다.")
        OutlinedButton(onClick = onSwitchProfile, enabled = state.foreground, modifier = Modifier.heightIn(min = 56.dp)) { Text("가상 운전자 전환 · 세션 초기화") }
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !state.liveNavigation && state.foreground && !state.profileWorking,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("scenario-menu")) {
            Text("응답 시나리오: ${state.scenario.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SimulationScenario.entries.forEach { scenario ->
                DropdownMenuItem(text = { Text(scenario.label) }, onClick = { expanded = false; onScenario(scenario) })
            }
        }
    }
    Text(state.scenario.description)
    Text("시나리오 선택 시 이전 세션을 종료합니다. 시작 버튼으로 진행하세요.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SessionControls(state: DriveMateUiState, onAction: (SimulationAction) -> Unit) = Panel("가상 주행 체험") {
    if (state.realVoice) Text("실제 음성은 위 음성 연결 영역을 사용하세요. 이 가상 주행 버튼들은 비활성입니다.")
    else if (state.liveNavigation) Text("실제 내비만 켠 경우 가상 응답과 자동 질문을 실행하지 않습니다. 음성은 위에서 별도로 연결하세요.")
    Button(onClick = { onAction(SimulationAction.START) }, enabled = state.canAct,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("start-simulation")) {
        Text(when {
            state.localProfiles && state.selectedProfileId == null -> "프로필을 먼저 등록·선택해 주세요"
            state.route == null -> "가상 목적지 설정에 동의하고 시작"
            else -> "출발 확인 · 최초 1회만 실행"
        })
    }
    val ready = state.canAct && state.route != null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Control("상태 다시 확인", ready, Modifier.weight(1f)) { onAction(SimulationAction.CHECK) }
        Control("조용한 모드", ready, Modifier.weight(1f)) { onAction(SimulationAction.QUIET) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Control("가상 음악", ready, Modifier.weight(1f)) { onAction(SimulationAction.MUSIC) }
        Control("가상 뉴스", ready, Modifier.weight(1f)) { onAction(SimulationAction.NEWS) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Control("가상 말동무", ready, Modifier.weight(1f)) { onAction(SimulationAction.COMPANION) }
        Control("휴식 가능 여부", ready, Modifier.weight(1f)) { onAction(SimulationAction.REST) }
    }
    if (state.busy) Text(if (state.realVoice) "실제 음성 흐름 진행 중…" else "미리 정한 응답으로 확인 중입니다…", Modifier.testTag("simulation-busy"))
    Text("앱 복귀 시 대화는 자동으로 재개하지 않습니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DetailCard(state: DriveMateUiState) = Panel(if (state.realVoice) "진행 상황 · 실제 음성/선택한 음악" else "진행 상황 · 가상 주행 체험") {
    Text("현재 안내", style = MaterialTheme.typography.titleMedium)
    if (!state.busy && state.guidance.phase == RestGuidancePhase.ADDED)
        Text("가상 휴식 경유지 추가를 완료했습니다. 실제 경로는 변경되지 않았습니다.")
    else Text(state.prompt)
    HorizontalDivider()
    Text("목적지: ${when { state.route == null -> "이 앱에서 확인한 경로 없음"; state.realVoice -> "실제 내비 경로 · 실제 음성 연결"; state.liveNavigation -> "실제 내비 경로 · 음성 꺼짐"; else -> "가상 목적지 · 경로 안내 미실행" }}")
    Text("모드: ${modeLabel(state.mode.mode)}")
    Text("휴식 장소: ${when (state.guidance.phase) {
        RestGuidancePhase.ADDED -> "가상 경유지 추가됨 · 실제 경로 변경 없음"
        RestGuidancePhase.SEARCHING -> "가상 후보 검색 중"
        RestGuidancePhase.ASKING -> "미리 정한 동의 확인 중"
        RestGuidancePhase.EMPTY -> "후보 없음"
        RestGuidancePhase.FAILED -> "검색 또는 경유지 요청 실패"
        else -> "요청 없음 또는 대기"
    }}")
    Text("휴식 남은 시간: ${state.rest.remainingMillis / 60_000}분")
    Text("예약된 상태 확인: ${state.pendingCount}개")
    Text("미디어: ${if (state.realVoice) "사용자 설정의 음악 포트 사용 · 실제 재생 상태는 음악 카드/외부 앱에서 확인" else when (state.mediaCommand) {
        is MediaCommand.PlayMusic -> "가상 음악 명령 수신 · 소리 없음"
        MediaCommand.PlayNewsAudio -> "가상 뉴스 명령 수신 · 소리 없음"
        MediaCommand.Stop -> "가상 중단 명령 수신"
        MediaCommand.Next -> "가상 다음 곡 명령 수신"
        null -> "명령 없음"
    }}")
}

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
@Composable private fun Control(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 56.dp)) { Text(label) }
}
internal fun driverStatusLabel(status: DriverStatus): String {
    if (status.report == ConditionReport.UNCONFIRMED && status.state in listOf(null, DriverState.NORMAL)) return "미확인"
    return when (status.state) {
    null -> "미확인"
    DriverState.NORMAL -> "정상 응답"
    DriverState.CAUTION -> "주의 · 피로 응답"
    DriverState.WARNING -> "경고 · 피로 지속"
    DriverState.HIGH_RISK -> "고위험 의심 · 휴식 권고"
    DriverState.RESTING -> "휴식 중"
    DriverState.RECOVERY_CHECK -> "회복 재확인"
    }
}
private fun modeLabel(mode: DrivingMode?): String = when (mode) {
    null -> "선택 전"
    DrivingMode.QUIET -> "조용한 운전"
    DrivingMode.MUSIC -> "음악"
    DrivingMode.COMPANION -> "말동무"
    DrivingMode.NEWS_AUDIO -> "뉴스·오디오"
}
private fun failureLabel(reason: FailureReason): String = when (reason) {
    FailureReason.UNSUPPORTED -> "지원되지 않는 기능"
    FailureReason.PERMISSION_DENIED -> "권한 거부"
    FailureReason.SERVICE_UNAVAILABLE -> "서비스 이용 불가"
    FailureReason.TIMEOUT -> "응답 시간 초과"
    FailureReason.CONSENT_REQUIRED -> "명시적 동의 필요"
    FailureReason.MANUAL_OVERRIDE -> "수동 변경 설정 유지"
    FailureReason.STALE_REQUEST -> "오래된 요청 · 다시 확인 필요"
    FailureReason.INVALID_REQUEST -> "요청 확인 필요"
}
