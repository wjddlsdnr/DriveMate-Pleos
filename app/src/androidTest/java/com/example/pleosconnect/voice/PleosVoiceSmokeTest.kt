package com.example.pleosconnect.voice

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.contract.voice.VoicePhase
import com.example.pleosconnect.pleos.voice.PleosVoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/** REAL SDK only. Explicit instrumentation arguments select potentially audible tests.
 * No raw transcript is logged. Never included in release APK or B's UI.
 * -e voiceScenario probe|speak|listen|ask|cancel|overlap
 */
@RunWith(AndroidJUnit4::class)
class PleosVoiceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun record(message: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "VOICE_SMOKE ${SystemClock.elapsedRealtime()} $message\n")
        })
    }

    @Test fun realSdkScenario() = runBlocking {
        val scenario = InstrumentationRegistry.getArguments().getString("voiceScenario") ?: "probe"
        require(scenario in setOf("probe", "speak", "listen", "ask", "cancel", "overlap"))
        listOf("pleos.car.permission.STT_SERVICE", "pleos.car.permission.TTS_SERVICE").forEach {
            val granted = context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            record("permission=$it granted=$granted")
        }
        if (scenario == "probe") return@runBlocking
        val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val service = SerializedVoiceService(PleosVoiceBackend(context) { event ->
            val count = seen.merge(event.event, 1) { old, increment -> old + increment }!!
            if (count <= 3) record("callback=${event.event} length=${event.textLength}")
        }, 12.seconds)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            service.phase.collect { record("phase=$it") }
        }
        try {
            when (scenario) {
                "speak" -> record("speakResult=${service.speak("드라이브메이트 음성 테스트입니다.")}")
                "listen" -> record("listenResult=${redacted(service.listen(40.seconds))}")
                "ask" -> {
                    val result = service.ask("음성 테스트입니다. 지금 괜찮아요라고 말씀해 주세요.", 40.seconds)
                    record("askResult=" + when (result) {
                        is QuestionResult.Answer -> redacted(result.result)
                        is QuestionResult.PlaybackFailed -> "PlaybackFailed:${result.reason}"
                    })
                }
                "cancel" -> {
                    val operation = launch { service.ask("취소 테스트입니다. 이 안내는 잠시 후 중단됩니다.", 15.seconds) }
                    delay(500)
                    record("beforeCancel=${service.phase.value}")
                    service.stop()
                    assertTrue(operation.isCancelled)
                    assertEquals(VoicePhase.IDLE, service.phase.value)
                    record("cancelled=${operation.isCancelled} completed=${operation.isCompleted}")
                }
                "overlap" -> {
                    val operation = launch { service.speak("동시 청취 차단을 확인하는 음성 테스트입니다.") }
                    delay(300)
                    assertEquals(VoicePhase.SPEAKING, service.phase.value)
                    val competing = service.listen(2.seconds)
                    assertEquals(com.example.pleosconnect.contract.voice.RecognitionResult.ServiceUnavailable, competing)
                    record("competingListenRejected=$competing phase=${service.phase.value}")
                    operation.join()
                }
            }
        } finally {
            service.close()
            record("callbackCounts=${seen.toSortedMap()}")
            observer.cancelAndJoin()
            record("closed=${service.phase.value}")
        }
    }

    private fun redacted(result: com.example.pleosconnect.contract.voice.RecognitionResult): String =
        if (result is com.example.pleosconnect.contract.voice.RecognitionResult.Text)
            "Text(redacted,length=${result.value.length},matchesTestPhrase=${result.value.replace(" ", "").trimEnd('.', '!', '?') == "괜찮아요"})" else result.toString()
}
