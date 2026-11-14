package com.example.pleosconnect.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.pleos.navigation.NearbyDiscoveryState

@Composable
fun NearbyDiscoveryPanel(state: NearbyDiscoveryState, enabled: Boolean,
    prepare: (String) -> Unit, confirm: () -> Unit, cancel: () -> Unit) {
    var token by remember { mutableStateOf("") }
    LaunchedEffect(enabled, state.busy, state.notice) {
        if (!enabled || state.busy || state.notice.startsWith("위치 전송을 취소했습니다")) token = ""
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("주변 휴식 장소 · 개발 검증", style = MaterialTheme.typography.titleLarge)
            Text("정차 중에만 사용하세요. 현재 위도·경도를 PC 중계를 통해 TMAP에 전송합니다. 검색 결과의 진행 방향·진입·주차는 미검증이며 경로는 변경하지 않습니다.")
            if (!state.review && !state.busy) {
                OutlinedTextField(token, { token = it.take(64) }, label = { Text("PC 중계 세션 토큰 · 저장하지 않음") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.testTag("nearby-token"))
                OutlinedButton({ prepare(token) }, enabled = enabled && token.length == 64,
                    modifier = Modifier.testTag("nearby-prepare")) { Text("전송 내용 확인") }
            }
            Text(state.notice, Modifier.testTag("nearby-notice"))
            if (state.review) {
                Text("준비 시점의 위치 한 건을 전송합니다. 동의 가능 시간 ${state.remainingSeconds}초. 경로 변경·만료 시 다시 준비해야 합니다.")
                Button(confirm, enabled = enabled, modifier = Modifier.testTag("nearby-confirm")) { Text("위치 전송에 동의하고 1회 검색") }
            }
            if (state.review || state.busy) OutlinedButton({ token = ""; cancel() }, modifier = Modifier.testTag("nearby-cancel")) { Text("취소") }
        }
    }
}
