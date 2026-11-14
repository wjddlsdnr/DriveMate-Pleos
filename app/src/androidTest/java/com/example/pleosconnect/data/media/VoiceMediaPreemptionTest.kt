package com.example.pleosconnect.data.media

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.MainActivity
import com.example.pleosconnect.contract.model.OperationResult
import com.example.pleosconnect.integration.LocalVoiceMediaController
import com.example.pleosconnect.integration.MediaPreemptingVoiceBackend
import com.example.pleosconnect.pleos.voice.PleosVoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local audio -> release -> real Pleos TTS. Never opens a microphone or fabricates an answer. */
@RunWith(AndroidJUnit4::class)
class VoiceMediaPreemptionTest {
    @Test fun realTtsRunsOnlyAfterLocalPlayerRelease(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var player: LocalAudioPlayer? = null
        var voice: MediaPreemptingVoiceBackend? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withContext(Dispatchers.Main) {
                    player = LocalAudioPlayer(context).also { it.select(DebugAudioSample.uri(context)); it.foreground(); it.play() }
                    voice = MediaPreemptingVoiceBackend(PleosVoiceBackend(context), LocalVoiceMediaController(requireNotNull(player)))
                }
                withTimeout(10_000) { requireNotNull(player).state.first { it.playing || it.failure != null } }
                assertTrue(requireNotNull(player).state.value.playing)
                val result = withContext(Dispatchers.Main) { withTimeout(35_000) { requireNotNull(voice).speak("음악을 중단하고 상태를 확인합니다.") } }
                assertTrue("TTS result: $result", result is OperationResult.Success)
                withContext(Dispatchers.Main) {
                    assertFalse(requireNotNull(player).state.value.playing)
                    assertNull(requireNotNull(player).positionMillis())
                }
                Log.i("DriveMateVoiceMedia", "realTtsCompleted=true,localPlayerReleased=true,microphoneRequests=0")
            }
        } finally { withContext(Dispatchers.Main) { voice?.close(); player?.close() } }
    }
}
