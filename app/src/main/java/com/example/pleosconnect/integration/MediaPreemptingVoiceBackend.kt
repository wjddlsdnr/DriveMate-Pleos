package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.integration.MediaController
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.voice.VoiceBackend

/** B composition decorator; A's serialization and dialogue remain the sole voice owner. */
class MediaPreemptingVoiceBackend(private val delegate: VoiceBackend, private val media: MediaController,
    private val enabled: () -> Boolean = { true }) : VoiceBackend {
    override suspend fun speak(text: String): OperationResult<Unit> {
        if (!enabled()) return OperationResult.Failure(FailureReason.UNSUPPORTED)
        val stopped = media.execute(MediaCommand.Stop)
        if (stopped is OperationResult.Failure) return stopped
        return if (enabled()) delegate.speak(text) else OperationResult.Failure(FailureReason.STALE_REQUEST)
    }
    override suspend fun listen(): RecognitionResult {
        if (!enabled() || media.execute(MediaCommand.Stop) !is OperationResult.Success) return RecognitionResult.ServiceUnavailable
        return if (enabled()) delegate.listen() else RecognitionResult.ServiceUnavailable
    }
    override suspend fun stop() = delegate.stop()
    override suspend fun close() = delegate.close()
}
