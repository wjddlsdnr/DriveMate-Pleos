package com.example.pleosconnect.integration

import android.content.Context
import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.data.media.LocalAudioPlayer
import com.example.pleosconnect.pleos.voice.PleosVoiceBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** No SDK object, microphone request or playback until an opted-in graph actually performs A work. */
class LiveVoiceIntegration(context: Context, private val player: LocalAudioPlayer, private val external: LiveMediaIntegration? = null) {
    private val context = context.applicationContext
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    fun setEnabled(value: Boolean) { mutableEnabled.value = value }
    private val localMedia = LocalVoiceMediaController(player)
    val media: MediaController = object : MediaController {
        override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
            if (external != null && !external.state.value.preferencesReady) return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
            return if (external?.state?.value?.preferredAppId != null) external.executeFromVoice(command)
            else localMedia.execute(command)
        }
    }
    fun backend() = MediaPreemptingVoiceBackend(PleosVoiceBackend(context), media) { enabled.value }
}

class LocalVoiceMediaController(private val player: LocalAudioPlayer) : MediaController {
    override suspend fun execute(command: MediaCommand): OperationResult<Unit> = when (command) {
        MediaCommand.Stop -> {
            val released = player.stop()
            if (released && !player.state.value.playing && !player.state.value.preparing && player.positionMillis() == null)
                OperationResult.Success(Unit) else OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        }
        MediaCommand.Next, MediaCommand.PlayNewsAudio -> OperationResult.Failure(FailureReason.UNSUPPORTED)
        is MediaCommand.PlayMusic -> {
            val alias = command.query.replace(Regex("\\s+"), "")
            if (alias !in setOf("로컬파일", "선택한파일", "테스트음")) OperationResult.Failure(FailureReason.UNSUPPORTED)
            else if (!player.state.value.selected) OperationResult.Failure(FailureReason.INVALID_REQUEST)
            else {
                player.play()
                val result = withTimeout(9_000) { player.state.first { it.playing || !it.preparing } }
                if (result.playing) OperationResult.Success(Unit)
                else OperationResult.Failure(result.failure ?: FailureReason.SERVICE_UNAVAILABLE)
            }
        }
    }
}
