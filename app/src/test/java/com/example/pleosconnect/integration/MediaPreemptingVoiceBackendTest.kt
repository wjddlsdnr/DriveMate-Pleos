package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaPreemptingVoiceBackendTest {
    private class Fixture {
        val events = mutableListOf<String>()
        var failure: FailureReason? = null
        var enabled = true
        val media = object : MediaController {
            override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
                assertEquals(MediaCommand.Stop, command); events += "media-stop"
                return failure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
            }
        }
        val delegate = object : VoiceBackend {
            override suspend fun speak(text: String): OperationResult<Unit> { events += "speak"; return OperationResult.Success(Unit) }
            override suspend fun listen(): RecognitionResult { events += "listen"; return RecognitionResult.Text("fixture") }
            override suspend fun stop() { events += "voice-stop" }
            override suspend fun close() { events += "close" }
        }
        val subject = MediaPreemptingVoiceBackend(delegate, media) { enabled }
    }
    @Test fun stopPrecedesBothSpeechAndListening() = runTest {
        val f = Fixture(); f.subject.speak("question"); f.subject.listen()
        assertEquals(listOf("media-stop", "speak", "media-stop", "listen"), f.events)
    }
    @Test fun failedMediaStopNeverOpensMicrophoneOrTts() = runTest {
        val f = Fixture(); f.failure = FailureReason.MANUAL_OVERRIDE
        assertEquals(OperationResult.Failure(FailureReason.MANUAL_OVERRIDE), f.subject.speak("question"))
        assertEquals(RecognitionResult.ServiceUnavailable, f.subject.listen())
        assertEquals(listOf("media-stop", "media-stop"), f.events)
    }
    @Test fun disabledModeRejectsLateVoiceButStillReleasesDelegate() = runTest {
        val f = Fixture(); f.enabled = false
        f.subject.speak("question"); f.subject.listen(); f.subject.stop(); f.subject.close()
        assertEquals(listOf("voice-stop", "close"), f.events)
    }
}
