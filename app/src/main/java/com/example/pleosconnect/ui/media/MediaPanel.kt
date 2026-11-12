package com.example.pleosconnect.ui.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.integration.LiveMediaUiState

data class MediaActions(val refresh: () -> Unit = {}, val select: (String) -> Unit = {},
    val browse: (String) -> Unit = {}, val play: (String) -> Unit = {},
    val command: (MediaCommand) -> Unit = {}, val disconnect: () -> Unit = {}, val saveDefault: (String?) -> Unit = {})

@Composable
fun MediaPanel(state: LiveMediaUiState, interactive: Boolean, foreground: Boolean, query: String,
    actions: MediaActions) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("실제 음악 앱 연결 · 개발 검증", style = MaterialTheme.typography.titleLarge)
        Text("선택한 외부 앱이 재생과 오디오 포커스를 처리합니다. 실제 음성 흐름은 위에서 별도로 연결하세요. 가상 주행 체험은 실제 음악을 제어하지 않습니다.")
        Text("공개 미디어 서비스를 제공하는 설치 앱만 표시됩니다. 앱마다 로그인·검색·재생 지원 여부가 다릅니다.")
        Text("뉴스 서비스는 미연결입니다. 로그인은 해당 음악 앱에서 직접 완료하세요.")
        if (!interactive) Text("목록·재생 조작은 차량 조회를 켜고 최신 P단·정지 신호가 있을 때 가능합니다. 안전한 주차 장소를 확인했다는 의미는 아닙니다.")
        OutlinedButton(actions.refresh, enabled = interactive && !state.busy) { Text("설치된 음악 앱 조회") }
        Text("이 프로필의 기본 음악 앱: ${state.apps.firstOrNull { it.id == state.preferredAppId }?.label ?: if (state.preferredAppId == null) "미설정" else "설치된 앱 조회로 확인하세요"}")
        if (state.preferredAppId != null) OutlinedButton({ actions.saveDefault(null) }, enabled = interactive && !state.busy) { Text("기본 음악 앱 설정 해제") }
        state.apps.take(10).forEach { app ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ actions.select(app.id) }, enabled = interactive && !state.busy) { Text("연결 · ${app.label}") }
                OutlinedButton({ actions.saveDefault(app.id) }, enabled = interactive && !state.busy && state.profileId != null) { Text("기본 앱으로 저장 · ${app.label}") }
            }
        }
        state.selected?.let { app ->
            Text("선택한 앱: ${app.label}")
            Text("프로필 검색어: ${query.ifBlank { "저장된 가수·장르가 없습니다" }}")
            Button({ actions.command(MediaCommand.PlayMusic(query)) }, enabled = interactive && !state.busy && query.isNotBlank()) {
                Text("프로필 검색어로 실제 재생 요청")
            }
            state.root?.let { root -> OutlinedButton({ actions.browse(root) }, enabled = interactive && !state.busy) { Text("최상위 목록") } }
            state.entries.forEach { entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.title, Modifier.weight(1f))
                    if (entry.browsable) OutlinedButton({ actions.browse(entry.id) }, enabled = interactive && !state.busy) { Text("열기") }
                    if (entry.playable) Button({ actions.play(entry.id) }, enabled = interactive && !state.busy) { Text("재생") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ actions.command(MediaCommand.Next) }, enabled = interactive && !state.busy) { Text("다음 곡") }
                OutlinedButton({ actions.command(MediaCommand.Stop) }, enabled = foreground && !state.busy) { Text("앱에서 시작한 재생 중단") }
                OutlinedButton(actions.disconnect, enabled = foreground) { Text("연결 해제") }
            }
        }
        Text(if (state.busy) "서비스 응답 확인 중…" else state.notice)
        state.failure?.let { Text("확인 필요: $it", color = MaterialTheme.colorScheme.error) }
    } }
}
