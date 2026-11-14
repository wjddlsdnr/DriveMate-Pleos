package com.example.pleosconnect.ui

import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.MainActivity
import com.example.pleosconnect.data.media.DebugAudioSample
import com.example.pleosconnect.integration.voiceSetupRequirements
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

/** Read-only navigation/vehicle connections. Toggle voice only; never asks/listens or changes a route. */
class VoiceModeActivationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun realCompositionCanEnableVoiceWithoutStartingConversation(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Manual active-route fixture required", InstrumentationRegistry.getArguments().getString("b5ActiveRoute") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var model: DriveMateViewModel? = null
        run {
            compose.activityRule.scenario.onActivity { model = ViewModelProvider(it)[DriveMateViewModel::class.java] }
            val vm = requireNotNull(model)
            try {
                withTimeout(10_000) { vm.state.first { it.foreground && it.profilesReady && it.selectedProfileId != null } }
                withContext(Dispatchers.Main) { vm.setLiveNavigation(true); vm.setVehicleRead(true); vm.selectLocalAudio(DebugAudioSample.uri(context)) }
                withTimeout(15_000) {
                    while (withContext(Dispatchers.Main) {
                        voiceSetupRequirements(vm.state.value.foreground, vm.state.value.liveNavigation, vm.state.value.selectedProfileId,
                            vm.mediaState.value, vm.localAudioState.value, vm.vehicleState.value).isNotEmpty()
                    }) delay(100)
                }
                compose.onNodeWithText("실제 음성 연결 켜기").performScrollTo().assertIsEnabled().performClick()
                withTimeout(10_000) { vm.liveVoiceEnabled.first { it } }
                withTimeout(10_000) { vm.state.first { it.realVoice && it.foreground } }
                assertTrue(vm.liveVoiceEnabled.value)
                compose.onNodeWithText("실제 음성 연결 끄기").assertExists()
                // Requires an already-running native guidance route, never creates one for the test.
                withTimeout(15_000) { vm.state.first { it.route != null && it.realVoice && it.foreground } }
                compose.onNodeWithText("음성 출발 확인").assertIsEnabled()
            } finally { withContext(Dispatchers.Main) { vm.setLiveVoice(false) } }
        }
    }
}
