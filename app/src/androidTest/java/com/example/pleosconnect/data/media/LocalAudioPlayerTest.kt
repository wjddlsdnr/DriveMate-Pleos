package com.example.pleosconnect.data.media

import android.util.Log
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Generated quiet tone only. No user's music files, no vehicle setters, no fake playback status. */
@RunWith(AndroidJUnit4::class)
class LocalAudioPlayerTest {
    @Test fun actualDecoderProgressesAndBackgroundStopsWithoutAutoResume(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val uri = DebugAudioSample.uri(target)
        val file = File(target.cacheDir, "b5-generated-audio/drivemate-sample.wav")
        var local: LocalAudioPlayer? = null
        val audio = target.getSystemService(AudioManager::class.java)
        var competing: AudioFocusRequest? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                withContext(Dispatchers.Main) {
                    local = LocalAudioPlayer(target)
                    val player = requireNotNull(local)
                    player.select(uri)
                    assertTrue(player.state.value.selected)
                    assertFalse(player.state.value.playing)
                    player.foreground()
                    player.play()
                }
                withTimeout(10_000) { requireNotNull(local).state.first { it.playing || it.failure != null } }
                withContext(Dispatchers.Main) { assertTrue(requireNotNull(local).state.value.toString(), requireNotNull(local).state.value.playing) }
                withTimeout(2_500) {
                    while (withContext(Dispatchers.Main) { (requireNotNull(local).positionMillis() ?: 0) < 100 }) delay(50)
                }
                withContext(Dispatchers.Main) {
                    val player = requireNotNull(local)
                    assertTrue((player.positionMillis() ?: 0) >= 100)
                    player.background()
                    assertFalse(player.state.value.playing)
                    assertNull(player.positionMillis())
                    player.foreground()
                    assertFalse(player.state.value.playing)
                    player.play()
                }
                withTimeout(10_000) { requireNotNull(local).state.first { it.playing || it.failure != null } }
                withContext(Dispatchers.Main) {
                    assertTrue(requireNotNull(local).state.value.playing)
                    competing = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener({}, Handler(Looper.getMainLooper())).build()
                    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audio.requestAudioFocus(requireNotNull(competing)))
                }
                withTimeout(3_000) { requireNotNull(local).state.first { !it.playing } }
                withContext(Dispatchers.Main) {
                    assertNull(requireNotNull(local).positionMillis())
                    audio.abandonAudioFocusRequest(requireNotNull(competing)); competing = null
                }
                delay(100)
                assertFalse(requireNotNull(local).state.value.playing)
                Log.i("DriveMateB5Local", "nativePositionAdvanced=true,backgroundReleased=true,focusLossStops=true,autoResume=false")
            }
        } finally {
            withContext(Dispatchers.Main) { local?.close() }
            competing?.let { audio.abandonAudioFocusRequest(it) }
            file.delete() // This test owns only the generated fixture file.
        }
    }
}
