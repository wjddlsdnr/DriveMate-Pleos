package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.model.OperationResult
import com.example.pleosconnect.contract.voice.RecognitionResult

/** App-owned boundary, not a Pleos API. Completion means actual playback completion.
 * stop must release listening/playback resources; return only after cleanup.
 */
interface VoiceBackend {
    suspend fun speak(text: String): OperationResult<Unit>
    suspend fun listen(): RecognitionResult
    suspend fun stop()
    suspend fun close()
}
