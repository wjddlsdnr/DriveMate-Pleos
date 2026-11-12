package com.example.pleosconnect.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pleosconnect.MainActivity
import com.example.pleosconnect.pleos.vehicle.*
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import com.example.pleosconnect.ui.vehicle.VehicleControlPanel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Isolated callbacks only. No native setting is changed by these UI tests. */
@RunWith(AndroidJUnit4::class)
class VehicleControlUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val key = VehicleSettingKey(VehicleSettingKind.CLIMATE_POWER, "ROW_1_LEFT")
    @Test fun reviewAndCancelDoNotConfirmAndRestoreNeedsAnotherConfirmation() {
        val confirmations = AtomicInteger()
        compose.activityRule.scenario.onActivity { host -> host.setContent { PleosConnectTheme {
            var state by remember { mutableStateOf(VehicleControlUiState(available = true, enabled = true,
                settings = listOf(VehicleSetting(key, 0.0, 0.0, 1.0, 100)))) }
            VehicleControlPanel(state, true, {}, { requested, target ->
                state = state.copy(review = VehicleChangeReview("change-review", requested, 0.0, target))
            }, { id -> state = state.copy(review = VehicleChangeReview("restore-review", key, 1.0, 0.0, true, id)) }, {
                confirmations.incrementAndGet()
                state = if (it == "change-review") state.copy(review = null,
                    changes = listOf(VehicleOwnedChange("owned", key, 0.0, 1.0, true)))
                else state.copy(review = null, changes = emptyList())
            }, { state = state.copy(review = null) }, {})
        } } }
        compose.onNodeWithTag("vehicle-control-target-0").performClick()
        assertEquals(0, confirmations.get())
        compose.onNodeWithTag("vehicle-control-cancel").performClick()
        assertEquals(0, confirmations.get())
        compose.onNodeWithTag("vehicle-control-target-0").performClick()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        assertEquals(1, confirmations.get())
        compose.onNodeWithTag("vehicle-restore-owned").performClick()
        assertEquals(1, confirmations.get())
        compose.onNodeWithText("원래 값 복원 동의").assertExists()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        assertEquals(2, confirmations.get())
    }
    @Test fun missingPrerequisitesDisableConnectionAndConfirmation() {
        compose.activityRule.scenario.onActivity { host -> host.setContent { PleosConnectTheme {
            var state by remember { mutableStateOf(VehicleControlUiState(available = true, enabled = true,
                review = VehicleChangeReview("test", key, 0.0, 1.0))) }
            VehicleControlPanel(state, false,
                { fail("No connection") }, { _, _ -> fail("No preparation") }, {}, { fail("No confirmation") },
                { state = state.copy(review = null) }, {})
        } } }
        compose.onNodeWithTag("vehicle-control-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("vehicle-control-cancel").performClick()
        compose.onNodeWithTag("vehicle-control-prepare").assertIsNotEnabled()
    }
}
