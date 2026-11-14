package com.example.pleosconnect.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.integration.LiveNavigationUiState
import com.example.pleosconnect.pleos.navigation.NavigationPlace
import com.example.pleosconnect.ui.DriveMateUiState

@Composable
fun NavigationPanel(driver: DriveMateUiState, state: LiveNavigationUiState, onMode: (Boolean) -> Unit,
    onDestination: (NavigationPlace) -> Unit, onResume: () -> Unit, onLoadDestinations: () -> Unit) {
    var editing by remember(driver.selectedProfileId) { mutableStateOf(false) }
    var resuming by remember(driver.selectedProfileId) { mutableStateOf(false) }
    var choosingSaved by remember(driver.selectedProfileId) { mutableStateOf(false) }
    var savedChoice by remember(driver.selectedProfileId) { mutableStateOf<NavigationPlace?>(null) }
    var lastPlace by remember(driver.selectedProfileId) { mutableStateOf<NavigationPlace?>(null) }
    val ready = driver.foreground && driver.selectedProfileId != null && !driver.profileWorking &&
        state.connected && state.readReady && state.termsAccepted == true && !state.busy && !state.reconnectRequired
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("내비게이션 연결", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onMode(!state.enabled) }, enabled = driver.foreground && !driver.profileWorking,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("live-navigation-mode")) {
                    Text(if (state.enabled) "실제 연결 해제 · 시뮬레이션으로" else "실제 내비 연결하기")
                }
                Text(when { !state.enabled -> "현재는 가상 주행입니다."; state.connected -> "내비 서비스 연결됨"; else -> "연결 확인 중 또는 연결 불가" },
                    Modifier.padding(top = 16.dp))
            }
            if (state.enabled) {
                Text("연결을 해제하거나 앱을 벗어나도 차량 내비의 경로를 자동 취소하지 않습니다.")
                if (state.connected && !state.readReady) Text("내비의 최신 상태 응답을 기다리고 있습니다.")
                if (state.termsAccepted == false) Text("차량 내비게이션에서 이용약관 동의와 초기 설정을 먼저 완료해 주세요.")
                state.failure?.let { Text(when (it) {
                    FailureReason.PERMISSION_DENIED -> "내비 권한이 없어 요청할 수 없습니다."
                    FailureReason.TIMEOUT -> "결과 확인 시간이 초과됐습니다. 실제 경로 변경 여부는 내비 화면에서 확인하세요."
                    FailureReason.CONSENT_REQUIRED -> "이 화면에서 직접 동의한 요청만 실행합니다."
                    FailureReason.STALE_REQUEST -> "프로필 또는 경로가 변경됐습니다. 다시 연결하고 확인해 주세요."
                    FailureReason.UNSUPPORTED -> "현재 지원하지 않는 요청입니다."
                    FailureReason.INVALID_REQUEST -> "목적지 정보나 내비 상태를 확인해 주세요."
                    else -> "내비 서비스를 이용할 수 없습니다. 연결을 해제한 뒤 다시 시도해 주세요."
                }, color = MaterialTheme.colorScheme.error) }
                if (state.reconnectRequired) Text("결과가 불확실해 추가 요청을 막았습니다. 연결을 해제한 뒤 다시 연결해 주세요.")
                Text("확인된 목적지: ${state.destinationName ?: "없음"} · 남은 시간: ${state.remainingMinutes?.let { "${it}분" } ?: "미확인"}")
                Text("휴식 장소 검색은 안전한 장소 데이터가 연결되지 않아 사용할 수 없습니다.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLoadDestinations, enabled = ready,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("load-navigation-favorites")) { Text("차량 내비 즐겨찾기 불러오기") }
                    Box {
                        OutlinedButton(onClick = { choosingSaved = true }, enabled = ready && state.savedDestinations?.isNotEmpty() == true,
                            modifier = Modifier.heightIn(min = 56.dp).testTag("choose-navigation-favorite")) { Text("즐겨찾기에서 목적지 선택") }
                        DropdownMenu(expanded = choosingSaved, onDismissRequest = { choosingSaved = false }) {
                            state.savedDestinations.orEmpty().forEachIndexed { index, place ->
                                DropdownMenuItem(text = { Text(place.name) }, onClick = { choosingSaved = false; savedChoice = place },
                                    modifier = Modifier.testTag("navigation-favorite-$index"))
                            }
                        }
                    }
                }
                if (state.savedDestinations?.isEmpty() == true) Text("차량 내비에 저장된 즐겨찾기가 없습니다. 차량 내비에서 장소를 저장하거나 좌표로 테스트하세요.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = true }, enabled = ready, modifier = Modifier.heightIn(min = 56.dp)
                        .testTag("live-destination")) { Text("좌표로 목적지 설정") }
                    OutlinedButton(onClick = { resuming = true }, enabled = ready && state.route != null,
                        modifier = Modifier.heightIn(min = 56.dp).testTag("live-resume")) { Text("기존 목적지 재요청") }
                }
                if (state.busy) Text("실제 내비의 완료 응답을 확인하고 있습니다…")
                if (driver.selectedProfileId == null) Text("목적지를 설정하려면 먼저 프로필을 등록·선택하세요.")
            }
        }
    }
    if (editing) DestinationDialog(lastPlace, ready, onDismiss = { editing = false }, onConfirm = { place ->
        lastPlace = place; editing = false; onDestination(place)
    })
    if (resuming) AlertDialog(onDismissRequest = { resuming = false }, title = { Text("기존 목적지로 다시 요청할까요?") },
        text = { Text("이 앱에서 확인한 기존 목적지로 실제 경로를 다시 요청합니다. 운전자의 회복이나 안전 주차를 확인한 것으로 처리하지 않습니다.") },
        confirmButton = { TextButton(enabled = ready && state.route != null, onClick = { resuming = false; onResume() }) { Text("경로 재요청에 동의") } },
        dismissButton = { TextButton(onClick = { resuming = false }) { Text("취소") } })
    savedChoice?.let { place -> AlertDialog(onDismissRequest = { savedChoice = null }, title = { Text("이 목적지로 실제 경로를 변경할까요?") },
        text = { Text("${place.name}\n${place.address}\n현재 안내 중인 경로가 있다면 이 목적지로 변경될 수 있습니다.") },
        confirmButton = { TextButton(enabled = ready, onClick = { savedChoice = null; onDestination(place) },
            modifier = Modifier.testTag("confirm-navigation-favorite")) { Text("이 목적지로 변경에 동의") } },
        dismissButton = { TextButton(onClick = { savedChoice = null }) { Text("취소") } }) }
}

@Composable
private fun DestinationDialog(initial: NavigationPlace?, ready: Boolean, onDismiss: () -> Unit, onConfirm: (NavigationPlace) -> Unit) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var latitude by remember { mutableStateOf(initial?.latitude?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(initial?.longitude?.toString().orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var poiId by remember { mutableStateOf(initial?.poiId.orEmpty()) }
    val place = NavigationPlace(name.trim(), latitude.toDoubleOrNull() ?: Double.NaN,
        longitude.toDoubleOrNull() ?: Double.NaN, address.trim(), poiId.trim())
    AlertDialog(onDismissRequest = onDismiss, title = { Text("실제 목적지 설정에 동의") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("현재 안내 중인 경로가 있다면 아래 목적지로 변경될 수 있습니다. 장소 검색 서비스는 아직 연결되지 않아 좌표로 입력합니다.")
            OutlinedTextField(name, { name = it }, label = { Text("목적지 이름") }, singleLine = true, modifier = Modifier.testTag("live-place-name"))
            OutlinedTextField(latitude, { latitude = it }, label = { Text("위도 · -90~90") }, singleLine = true, modifier = Modifier.testTag("live-latitude"))
            OutlinedTextField(longitude, { longitude = it }, label = { Text("경도 · -180~180") }, singleLine = true, modifier = Modifier.testTag("live-longitude"))
            OutlinedTextField(address, { address = it }, label = { Text("주소 · 선택") }, singleLine = true)
            OutlinedTextField(poiId, { poiId = it }, label = { Text("장소 ID · 선택") }, singleLine = true)
            Text("입력한 목적지로 실제 내비게이션 경로를 요청하는 데 동의하나요?")
        }
    }, confirmButton = { TextButton(enabled = ready && place.valid(), onClick = { onConfirm(place) }, modifier = Modifier.testTag("live-destination-confirm")) { Text("이 목적지로 변경에 동의") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}
