package com.example.pleosconnect.pleos.vehicle

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.MainActivity
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Opt-in EMULATOR ONLY. Default test runs skip; no actuator command without the approved exact plan argument. */
@RunWith(AndroidJUnit4::class)
class B4NativeAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val powerArea = "ROW_1_LEFT, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT"
    private val power = VehicleSettingKey(VehicleSettingKind.CLIMATE_POWER, powerArea)
    private val window = VehicleSettingKey(VehicleSettingKind.WINDOW_POSITION, "ROW_1_LEFT")
    private fun snapshot(): VehicleControlInspection = runBlocking {
        val backend = PleosVehicleSettingsBackend(context)
        try { withTimeout(15_000) { backend.inspect() } } finally { backend.close() }
    }
    private fun requireStationary(data: VehicleControlInspection) {
        val now = SystemClock.elapsedRealtime()
        assertTrue("Gear must be fresh PARK", data.signals.gear.fresh(now) && data.signals.gear.value == ReadGear.PARK)
        assertTrue("Parking brake must be engaged; NEVER injected by this test", data.signals.parkingBrake.fresh(now) && data.signals.parkingBrake.value == true)
        assertTrue("Speed permission and fresh zero speed required", data.signals.speedMetersPerSecond.fresh(now) && data.signals.speedMetersPerSecond.value == 0f)
    }
    private fun waitFor(tag: String) { compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun restoreTag(): String {
        val matcher = SemanticsMatcher("owned restoration record") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("vehicle-restore-") == true
        }
        compose.waitUntil(20_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().size == 1 }
        return compose.onAllNodes(matcher).fetchSemanticsNodes().single().config[SemanticsProperties.TestTag]
    }
    private fun assertValue(key: VehicleSettingKey, expected: Double) {
        val data = snapshot(); requireStationary(data)
        assertEquals(expected, data.settings.single { it.key == key }.value, 0.0)
        if (key.kind == VehicleSettingKind.CLIMATE_POWER && expected == 1.0) {
            Log.i("DriveMateB4Acceptance", "powerOnTemperatureValues=${data.signals.climate.value?.count { it.value != null && it.problem == null }}")
        }
    }
    @Test fun approvedPowerAndWindowChangeAndFreshConsentRestoreThroughRealUi() {
        Assume.assumeTrue("Explicit approved plan required", InstrumentationRegistry.getArguments().getString("b4Consent") ==
            "power0-1-0-window0-1-0")
        assertEquals("Emulator only", "ranchu", Build.HARDWARE)
        val before = snapshot(); requireStationary(before)
        assertEquals(0.0, before.settings.single { it.key == power }.value, 0.0)
        assertEquals(0.0, before.settings.single { it.key == window }.value, 0.0)
        waitFor("vehicle-read-toggle")
        compose.onNodeWithTag("vehicle-read-toggle").performScrollTo().performClick()
        waitFor("vehicle-control-prepare")
        compose.onNodeWithTag("vehicle-control-prepare").performScrollTo().performClick()
        waitFor("vehicle-control-target-0")
        compose.onNode(hasContentDescription("설정 검토 · 공조 전원", substring = true)).performScrollTo().performClick()
        waitFor("vehicle-control-confirm")
        compose.onNodeWithText("꺼짐 → 켜짐", substring = true).assertExists()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        val powerRestore = restoreTag()
        compose.waitUntil(20_000) { compose.onNodeWithTag(powerRestore).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
        assertValue(power, 1.0)
        compose.onNodeWithTag(powerRestore).performScrollTo().performClick()
        waitFor("vehicle-control-confirm")
        compose.onNodeWithText("원래 값 복원 동의").assertExists()
        compose.onNodeWithText("켜짐 → 꺼짐", substring = true).assertExists()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(powerRestore).fetchSemanticsNodes().isEmpty() }
        assertValue(power, 0.0)
        Log.i("DriveMateB4Acceptance", "powerChangeVerified=true,powerRestoreVerified=true")
        // Select by exact physical area label, never by a provider's iteration order.
        assertValue(window, 0.0)
        compose.onNodeWithContentDescription("창문 목표 위치값 · 1열 왼쪽").performScrollTo().performTextInput("1")
        compose.onNodeWithContentDescription("설정 검토 · 창문 위치값 · 1열 왼쪽").performScrollTo().performClick()
        waitFor("vehicle-control-confirm")
        compose.onNodeWithText("창문 위치값 · 1열 왼쪽", substring = true).assertExists()
        compose.onNodeWithText("0 → 1", substring = true).assertExists()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        val windowRestore = restoreTag()
        compose.waitUntil(20_000) { compose.onNodeWithTag(windowRestore).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
        assertValue(window, 1.0)
        compose.onNodeWithTag(windowRestore).performScrollTo().performClick()
        waitFor("vehicle-control-confirm")
        compose.onNodeWithText("원래 값 복원 동의").assertExists()
        compose.onNodeWithText("1 → 0", substring = true).assertExists()
        compose.onNodeWithTag("vehicle-control-confirm").performClick()
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(windowRestore).fetchSemanticsNodes().isEmpty() }
        assertValue(window, 0.0)
        Log.i("DriveMateB4Acceptance", "windowChangeVerified=true,windowRestoreVerified=true")
        // No finally rollback: if a step is uncertain, stop and require a fresh user decision.
    }
}
