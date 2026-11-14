package com.example.pleosconnect.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pleosconnect.MainActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith

/** B1 fake-only UI tests. No actual STT, navigation, parking or vehicle SDK assertions. */
@RunWith(AndroidJUnit4::class)
class DriveMateScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: DriveMateViewModel

    // Pleos Home moves launched activities into its task view asynchronously.
    // Wait for the real app hierarchy instead of assuming Activity launch means first draw.
    @Before fun awaitScreen() {
        model = DriveMateViewModel() // Isolated in-memory B1 fixture, never the user's local profile file.
        compose.activityRule.scenario.onActivity { host -> host.setContent {
            val state by model.state.collectAsState()
            PleosConnectTheme { DriveMateScreen(state, model::act, { model.reset(it) }, { model.reset(switchProfile = true) }) }
        } }
        model.setForeground(true)
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithTag("simulation-banner").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }
    @After fun closeFixture() { runBlocking { model.closeAndJoin() } }

    @Test fun initialScreenIsExplicitlySimulationAndUnknown() {
        compose.onNodeWithTag("simulation-banner").assertTextEquals("시뮬레이션 모드")
        compose.onNodeWithTag("driver-state").assertTextEquals("미확인")
        compose.onNodeWithTag("parking-state").assertTextEquals("안전 주차: 미확인")
    }
    private fun startNormal() {
        compose.onNodeWithTag("start-simulation").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("정상 응답").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("simulation-busy").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun normalFlowAndDriverSwitchResetStatus() {
        startNormal()
        compose.onNodeWithTag("driver-state").assertTextEquals("정상 응답")
        compose.onNodeWithText("가상 운전자 전환 · 세션 초기화").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("가상 운전자 2").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("driver-state").assertTextEquals("미확인")
    }
    @Test fun permissionFailureIsVisibleAndStateStaysUnconfirmed() {
        compose.onNodeWithTag("scenario-menu").performScrollTo().performClick()
        compose.onNodeWithText("마이크 권한 거부").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("start-simulation").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("확인 필요: 권한 거부").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("driver-state").assertTextEquals("미확인")
        compose.onNodeWithTag("parking-state").assertTextEquals("안전 주차: 미확인")
    }
    @Test fun backgroundAndForegroundKeepDriverWithoutRestartingSession() {
        startNormal()
        compose.runOnIdle { model.setForeground(false); model.setForeground(true) }
        compose.waitForIdle()
        compose.onNodeWithTag("driver-state").assertTextEquals("정상 응답")
        compose.onNodeWithTag("simulation-banner").assertExists()
    }
}
