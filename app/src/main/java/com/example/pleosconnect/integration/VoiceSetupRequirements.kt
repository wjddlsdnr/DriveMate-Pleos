package com.example.pleosconnect.integration

import com.example.pleosconnect.data.media.LocalAudioState
import com.example.pleosconnect.pleos.vehicle.VehicleReadUiState

/** Same prerequisites in UI and command handler. Never relax a failed prerequisite. */
fun voiceSetupRequirements(foreground: Boolean, liveNavigation: Boolean, profileId: String?,
    media: LiveMediaUiState, local: LocalAudioState, vehicle: VehicleReadUiState): List<String> = buildList {
    if (!foreground) add("앱을 화면에 열어 주세요.")
    if (!liveNavigation) add("위 내비게이션 카드에서 ‘실제 내비 연결하기’를 눌러 주세요. 차량 내비 안내 시작과는 별도입니다.")
    if (profileId == null) add("사용자 프로필을 등록하거나 선택해 주세요.")
    if (!media.preferencesReady || media.profileId != profileId)
        add("선택한 프로필의 음악 설정을 아직 확인하지 못했습니다. 오류가 있으면 프로필을 다시 선택해 주세요.")
    if (media.preferredAppId != null) {
        if (media.selected?.id != media.preferredAppId || media.root == null)
            add("저장한 기본 음악 앱을 연결해 주세요. 로컬 테스트를 하려면 기본 음악 앱 설정을 해제해 주세요.")
    } else if (!local.selected) add("‘테스트 음 선택 · 8초’ 또는 오디오 파일 선택을 눌러 주세요. 재생할 필요는 없습니다.")
    if (!mediaSetupAllowed(vehicle)) add("차량 조회를 켜고 최신 P단·속도 0 조회를 확인해 주세요. 미수신·오래된 값이면 연결할 수 없습니다.")
}
