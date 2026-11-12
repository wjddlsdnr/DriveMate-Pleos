package com.example.pleosconnect.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pleosconnect.MainActivity
import com.example.pleosconnect.pleos.vehicle.*
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import com.example.pleosconnect.ui.vehicle.VehicleReadPanel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleReadUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun optInRequiresButtonAndParkDoesNotDisplaySafe() {
        val requests = mutableListOf<Boolean>()
        compose.activityRule.scenario.onActivity { host -> host.setContent { PleosConnectTheme {
            VehicleReadPanel(VehicleReadUiState(available = true, enabled = true, nowMillis = 100,
                snapshot = VehicleReadSnapshot(gear = VehicleReading(ReadGear.PARK, 100),
                    speedMetersPerSecond = VehicleReading(0f, 100), parkingBrake = VehicleReading(true, 100))), true) { requests += it }
        } } }
        compose.onNodeWithTag("vehicle-parking-status").assertTextEquals("안전 주차: 미확인")
        assertTrue(requests.isEmpty())
        compose.onNodeWithTag("vehicle-read-toggle").performClick()
        assertEquals(listOf(false), requests)
    }
    @Test fun backgroundPanelCannotEnableReads() {
        compose.activityRule.scenario.onActivity { host -> host.setContent { PleosConnectTheme {
            VehicleReadPanel(VehicleReadUiState(available = true), false) { fail("Must not enable in background") }
        } } }
        compose.onNodeWithTag("vehicle-read-toggle").assertIsNotEnabled()
    }
}
