package com.example.pleosconnect.mock.voice

import com.example.pleosconnect.contract.model.OperationResult
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.delay

/** MOCK ONLY: no microphone, speaker, Pleos service or health inference.
 * Script is immutable; use one instance per SerializedVoiceService.
 */
class MockVoiceBackend(
    answers: List<RecognitionResult> = emptyList(),
    private val playbackMillis: Long = 100,
) : VoiceBackend {
    init { require(playbackMillis >= 0) }
    private val script = ArrayDeque(answers)
    override suspend fun speak(text: String): OperationResult<Unit> {
        delay(playbackMillis)
        return OperationResult.Success(Unit)
    }
    override suspend fun listen(): RecognitionResult =
        script.removeFirstOrNull() ?: RecognitionResult.NoResponse
    override suspend fun stop() = Unit
    override suspend fun close() { script.clear() }
}
