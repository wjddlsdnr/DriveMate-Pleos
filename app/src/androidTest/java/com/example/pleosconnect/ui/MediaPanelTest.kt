package com.example.pleosconnect.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.pleosconnect.data.media.*
import com.example.pleosconnect.integration.LiveMediaUiState
import com.example.pleosconnect.ui.media.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MediaPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun missingStationarySignalsDisablePlaybackButNotStop() {
        compose.setContent { MaterialTheme {
            MediaPanel(LiveMediaUiState(available = true, selected = MediaApp("app/service", "Test")),
                interactive = false, foreground = true, query = "jazz", actions = MediaActions())
        } }
        compose.onNodeWithText("프로필 검색어로 실제 재생 요청").assertIsNotEnabled()
        compose.onNodeWithText("앱에서 시작한 재생 중단").assertIsEnabled()
        compose.onNodeWithText("뉴스 서비스는 미연결입니다. 로그인은 해당 음악 앱에서 직접 완료하세요.").assertExists()
    }
    @Test fun connectingRequiresExplicitSelectionAndNeverDispatchesPlay() {
        var selected = 0
        var played = 0
        compose.setContent { MaterialTheme {
            MediaPanel(LiveMediaUiState(available = true, apps = listOf(MediaApp("app/service", "Test"))),
                interactive = true, foreground = true, query = "jazz",
                actions = MediaActions(select = { selected++ }, play = { played++ }, command = { played++ }))
        } }
        compose.runOnIdle { assertEquals(0, selected); assertEquals(0, played) }
        compose.onNodeWithText("연결 · Test").performClick()
        compose.runOnIdle { assertEquals(1, selected); assertEquals(0, played) }
    }
}
