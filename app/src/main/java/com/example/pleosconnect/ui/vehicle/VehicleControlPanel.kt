package com.example.pleosconnect.ui.vehicle

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.pleos.vehicle.*

@Composable
fun VehicleControlPanel(state: VehicleControlUiState, ready: Boolean, prepareConnection: () -> Unit,
    prepare: (VehicleSettingKey, Double) -> Unit, restore: (String) -> Unit, confirm: (String) -> Unit, cancel: () -> Unit,
    stop: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("실제 차량 설정 · 요청마다 직접 동의", style = MaterialTheme.typography.titleMedium)
            Text("먼저 차량 조회와 속도 권한을 켜고 프로필을 선택하세요. P단·속도 0·주차 브레이크를 재확인한 뒤 한 항목씩 변경합니다.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = prepareConnection, enabled = ready && !state.busy,
                    modifier = Modifier.heightIn(min = 56.dp).testTag("vehicle-control-prepare")) { Text("설정 변경 준비 · 조회만") }
                if (state.enabled) OutlinedButton(onClick = stop, modifier = Modifier.testTag("vehicle-control-stop")) { Text("제어 연결 닫기") }
            }
            if (state.busy) Text("확인 중입니다. 자동 재시도하지 않습니다.")
            state.notice?.let { Text(it) }
            state.failure?.let { Text(controlFailure(it), modifier = Modifier.testTag("vehicle-control-failure")) }
            if (state.enabled) {
                Text("온도 조절은 현재 조회 오류로 제외합니다. 창문은 열림 방향을 추정하지 않고 개발 검증용 위치값을 직접 지정합니다.")
                if (state.settings.isEmpty()) Text("원본 조회와 읽기·쓰기 지원이 함께 확인된 항목이 없습니다.")
                state.settings.forEachIndexed { index, setting ->
                    val key = setting.key
                    Text(settingTitle(key))
                    if (key.kind == VehicleSettingKind.CLIMATE_POWER) {
                        Text("현재: ${settingValue(key, setting.value)}")
                        OutlinedButton(onClick = { prepare(key, if (setting.value == 1.0) 0.0 else 1.0) },
                            enabled = ready && !state.busy && state.changes.none { it.key == key },
                            modifier = Modifier.testTag("vehicle-control-target-$index").semantics { contentDescription = "설정 검토 · ${settingTitle(key)}" }) { Text("전원 변경 내용 검토") }
                    } else {
                        var target by remember(key) { mutableStateOf("") }
                        OutlinedTextField(value = target, onValueChange = { target = it }, singleLine = true,
                            label = { Text("목표 위치값 (${setting.minimum.toInt()}~${setting.maximum.toInt()})") },
                            modifier = Modifier.fillMaxWidth().testTag("vehicle-control-value-$index").semantics {
                                contentDescription = "창문 목표 위치값 · ${areaLabel(key.area)}"
                            })
                        Text("현재 위치값: ${setting.value.toInt()} · 백분율이나 열림 방향으로 자동 해석하지 않습니다.")
                        val value = target.toDoubleOrNull()
                        OutlinedButton(onClick = { value?.let { prepare(key, it) } }, enabled = ready && !state.busy &&
                            value != null && setting.accepts(value) && value != setting.value && state.changes.none { it.key == key },
                            modifier = Modifier.testTag("vehicle-control-target-$index").semantics { contentDescription = "설정 검토 · ${settingTitle(key)}" }) { Text("위치값 변경 내용 검토") }
                    }
                }
                state.changes.forEach { change ->
                    Text("${settingTitle(change.key)}: ${settingValue(change.key, change.original)} → ${settingValue(change.key, change.applied)}")
                    change.reason?.let { Text(it) }
                    OutlinedButton(onClick = { restore(change.id) }, enabled = ready && !state.busy && change.restorable,
                        modifier = Modifier.testTag("vehicle-restore-${change.id}")) { Text("원래 값 복원 내용 검토") }
                }
            }
        }
    }
    state.review?.let { review ->
        AlertDialog(onDismissRequest = cancel,
            title = { Text(if (review.restoring) "원래 값 복원 동의" else "실제 차량 설정 변경 동의") },
            text = { Text("${settingTitle(review.key)}\n${settingValue(review.key, review.original)} → ${settingValue(review.key, review.target)}\n" +
                "이 요청은 실제 차량 설정을 변경합니다. 자동 복원하지 않습니다. 동의하시나요?") },
            confirmButton = { TextButton(onClick = { confirm(review.id) }, enabled = ready && !state.busy,
                modifier = Modifier.testTag("vehicle-control-confirm")) { Text(if (review.restoring) "동의하고 복원" else "동의하고 변경") } },
            dismissButton = { TextButton(onClick = cancel, modifier = Modifier.testTag("vehicle-control-cancel")) { Text("취소") } })
    }
}
private fun settingTitle(key: VehicleSettingKey) = (if (key.kind == VehicleSettingKind.CLIMATE_POWER) "공조 전원" else "창문 위치값") + " · " + areaLabel(key.area)
private fun settingValue(key: VehicleSettingKey, value: Double) = if (key.kind == VehicleSettingKind.CLIMATE_POWER) {
    if (value == 1.0) "켜짐" else "꺼짐"
} else value.toInt().toString()
private fun controlFailure(value: FailureReason) = when (value) {
    FailureReason.INVALID_REQUEST -> "프로필·최신 P단·속도 0·주차 브레이크와 목표값을 확인해 주세요. 안전한 주차 장소를 확정하는 기능은 아닙니다."
    FailureReason.CONSENT_REQUIRED -> "새로운 확인 화면에서 이 요청에 직접 동의해야 합니다."
    FailureReason.STALE_REQUEST -> "차량 상태나 동의가 달라졌습니다. 내용을 새로 확인해 주세요."
    FailureReason.MANUAL_OVERRIDE -> "외부 또는 출처 미확인 변경으로 복원할 수 없습니다."
    FailureReason.PERMISSION_DENIED -> "차량 조회 또는 제어 권한이 없습니다."
    FailureReason.UNSUPPORTED -> "원본 조회·지원 범위·변경 알림이 확인되지 않아 사용할 수 없습니다."
    FailureReason.TIMEOUT -> "요청 시간이 초과됐습니다. 적용 여부를 차량에서 확인하세요."
    else -> "차량 서비스 결과를 확인하지 못했습니다. 자동 재시도하지 않습니다."
}
