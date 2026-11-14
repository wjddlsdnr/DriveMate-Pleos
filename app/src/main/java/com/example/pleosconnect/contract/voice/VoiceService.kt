package com.example.pleosconnect.contract.voice

import com.example.pleosconnect.contract.model.OperationResult
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

enum class VoicePhase { IDLE, SPEAKING, LISTENING, UNAVAILABLE }

sealed interface RecognitionResult {
    data class Text(val value: String) : RecognitionResult
    data object NoResponse : RecognitionResult
    data object Timeout : RecognitionResult
    data object PermissionDenied : RecognitionResult
    data object RecognitionFailure : RecognitionResult
    data object ServiceUnavailable : RecognitionResult
}

/** A-owned port. Real adapter and Mock must be clearly distinguished.
 * Serialize operations: speak returns success only after playback completes;
 * listen starts only after playback has ended. Timeout is not proof of silence.
 * Cancellation must stop playback/listening and release the microphone before returning.
 */
interface VoiceService {
    val phase: StateFlow<VoicePhase>
    suspend fun speak(text: String): OperationResult<Unit>
    /** timeout must be finite and positive. */
    suspend fun listen(timeout: Duration): RecognitionResult
    suspend fun stop()
    /** Detach listeners and release SDK resources. Idempotent. */
    suspend fun close()
}
