package com.example.pleosconnect.ui.profile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.data.profile.ProfileDraft
import com.example.pleosconnect.ui.DriveMateUiState

data class ProfileActions(
    val register: (ProfileDraft) -> Unit,
    val select: (String) -> Unit,
    val save: (String, ProfileDraft) -> Unit,
    val delete: (String) -> Unit,
    val reload: () -> Unit,
)

@Composable
fun ProfilePanel(state: DriveMateUiState, actions: ProfileActions) {
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pendingOperation by remember { mutableStateOf<Int?>(null) }
    val selected = state.preferences
    LaunchedEffect(state.profileOperation) {
        if (pendingOperation != null && pendingOperation != state.profileOperation) {
            if (state.profileFailure == null) { editing = false; creating = false; deleting = false }
            pendingOperation = null
        }
    }
    Text(state.profileName, style = MaterialTheme.typography.titleLarge)
    Text("프로필은 이 기기에만 저장됩니다. 이름은 본인 인증 수단이 아닙니다.", style = MaterialTheme.typography.bodySmall)
    if (state.profileWorking) Text("프로필을 처리하고 있습니다…")
    if (!state.profilesReady) {
        Text(if (state.profileFailure == null) "저장된 프로필을 불러오고 있습니다." else "프로필을 불러오지 못했습니다. 기존 저장 파일은 유지됩니다.")
        if (state.profileFailure != null) OutlinedButton(onClick = actions.reload,
            enabled = state.foreground && !state.profileWorking) { Text("불러오기 다시 시도") }
    }
    state.profileFailure?.let {
        Text(when (it) {
            FailureReason.INVALID_REQUEST -> "이름은 1~60자, 각 취향은 최대 100자·20개, 프로필은 최대 20개까지 입력할 수 있습니다."
            FailureReason.STALE_REQUEST -> "프로필이 변경되었습니다. 다시 선택해 주세요."
            else -> "프로필 처리에 실패했습니다. 저장 완료로 처리하지 않았습니다. 다시 시도해 주세요."
        }, color = MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { choosing = true }, enabled = state.canManageProfiles && state.profiles.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("select-profile")) { Text("저장된 프로필 선택") }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                state.profiles.forEachIndexed { index, profile ->
                    DropdownMenuItem(text = { Text("${index + 1}. ${profile.displayName}${if (profile.profileId == state.selectedProfileId) " · 선택됨" else ""}") },
                        onClick = { choosing = false; actions.select(profile.profileId) },
                        modifier = Modifier.testTag("profile-choice-${profile.profileId}"))
                }
            }
        }
        OutlinedButton(onClick = { creating = true }, enabled = state.canManageProfiles,
            modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("new-profile")) { Text("새 프로필 등록") }
    }
    if (selected != null) {
        Text("음악 취향: ${selected.genres.ifEmpty { listOf("미설정") }.joinToString(", ")}")
        Text("콘텐츠 취향: ${selected.contentPreferences.ifEmpty { listOf("미설정") }.joinToString(", ")}")
        Text("최근 선택 모드: ${when (selected.previousMode) {
            DrivingMode.QUIET -> "조용한 운전"
            DrivingMode.MUSIC -> "음악"
            DrivingMode.COMPANION -> "말동무"
            DrivingMode.NEWS_AUDIO -> "뉴스·오디오"
            null -> "없음"
        }}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true }, enabled = state.canManageProfiles,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("프로필·취향 수정") }
            OutlinedButton(onClick = { deleting = true }, enabled = state.canManageProfiles,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("선택한 프로필 삭제") }
        }
    }
    Text("등록·선택·수정·삭제하면 현재 체험을 종료하고 세션을 초기화합니다.", style = MaterialTheme.typography.bodySmall)
    if (creating || editing && selected != null) {
        key(if (creating) "new" else selected!!.profileId) {
            ProfileEditor(if (creating) null else selected, state.profileWorking || pendingOperation != null,
                failed = pendingOperation == null && state.profileFailure != null,
                onDismiss = { if (!state.profileWorking && pendingOperation == null) { creating = false; editing = false } },
                onSave = { draft ->
                    pendingOperation = state.profileOperation
                    if (creating) actions.register(draft) else actions.save(selected!!.profileId, draft)
                })
        }
    }
    if (deleting && selected != null) AlertDialog(onDismissRequest = { if (!state.profileWorking) deleting = false },
        title = { Text("프로필을 삭제할까요?") },
        text = { Text("${selected.displayName} 프로필과 저장된 취향을 삭제합니다. 현재 체험도 종료하며 자동으로 다른 사용자를 선택하지 않습니다.") },
        confirmButton = { TextButton(enabled = !state.profileWorking && pendingOperation == null, onClick = {
            pendingOperation = state.profileOperation; actions.delete(selected.profileId)
        }) { Text("삭제") } },
        dismissButton = { TextButton(enabled = !state.profileWorking && pendingOperation == null, onClick = { deleting = false }) { Text("취소") } })
}

@Composable
private fun ProfileEditor(profile: DriverPreferences?, busy: Boolean, failed: Boolean, onDismiss: () -> Unit, onSave: (ProfileDraft) -> Unit) {
    var name by remember { mutableStateOf(profile?.displayName.orEmpty()) }
    var genres by remember { mutableStateOf(profile?.genres?.joinToString(", ").orEmpty()) }
    var artists by remember { mutableStateOf(profile?.artists?.joinToString(", ").orEmpty()) }
    var playlists by remember { mutableStateOf(profile?.playlists?.joinToString(", ").orEmpty()) }
    var content by remember { mutableStateOf(profile?.contentPreferences?.joinToString(", ").orEmpty()) }
    var voice by remember { mutableStateOf(profile?.voiceGuidancePreference.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "새 프로필 등록" else "프로필·취향 수정") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("이름과 선택한 취향만 저장합니다. 음성 원문과 건강 상태는 저장하지 않습니다.")
                if (failed) Text("저장하지 못했습니다. 입력 내용을 확인하거나 다시 시도해 주세요.", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(name, { name = it }, label = { Text("이름 또는 별명") }, singleLine = true, enabled = !busy,
                    modifier = Modifier.testTag("profile-name"))
                OutlinedTextField(genres, { genres = it }, label = { Text("음악 장르 · 쉼표로 구분") }, enabled = !busy, modifier = Modifier.testTag("profile-genres"))
                OutlinedTextField(artists, { artists = it }, label = { Text("선호 가수") }, enabled = !busy)
                OutlinedTextField(playlists, { playlists = it }, label = { Text("플레이리스트") }, enabled = !busy)
                OutlinedTextField(content, { content = it }, label = { Text("뉴스·콘텐츠 취향") }, enabled = !busy, modifier = Modifier.testTag("profile-content"))
                OutlinedTextField(voice, { voice = it }, label = { Text("음성 안내 선호도") }, enabled = !busy)
                Text("취향은 쉼표로 나눠 입력하세요. 실제 콘텐츠와 음성 설정 반영은 후속 단계입니다.")
            }
        },
        confirmButton = { TextButton(enabled = !busy && name.trim().length in 1..60,
            onClick = { onSave(ProfileDraft(name, genres.parts(), artists.parts(), playlists.parts(), content.parts(), voice)) },
            modifier = Modifier.testTag("save-profile")) { Text(if (profile == null) "등록하고 선택" else "저장") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } })
}
private fun String.parts() = split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
