package com.example.pleosconnect.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.data.media.LocalAudioPlayer
import com.example.pleosconnect.pleos.voice.PleosVoiceBackend
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.domain.NormalCommand
import com.example.pleosconnect.domain.NormalCommandParser
import com.example.pleosconnect.domain.LocalMediaDiagnostic
import com.example.pleosconnect.domain.ConditionClassifier
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** No SDK object, microphone request or playback until an opted-in graph actually performs A work. */
class LiveVoiceIntegration(context: Context, private val player: LocalAudioPlayer, private val external: LiveMediaIntegration? = null) {
    private val context = context.applicationContext
    private val diagnostics = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private fun trace(value: String) { if (diagnostics) Log.i("DriveMateVoiceProbe", value) }
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    fun setEnabled(value: Boolean) { mutableEnabled.value = value }
    private val localMedia = LocalVoiceMediaController(player)
    val media: MediaController = object : MediaController {
        override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
            if (external != null && !external.state.value.preferencesReady) return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
            if (diagnostics) {
                val category = (command as? MediaCommand.PlayMusic)?.let { LocalMediaDiagnostic.classify(it.query) }
                trace("request=${if (command is MediaCommand.PlayMusic) "MUSIC" else if (command == MediaCommand.Stop) "STOP" else "OTHER"},alias=${category ?: "NONE"},port=${if (external?.state?.value?.preferredAppId != null) "EXTERNAL" else "LOCAL"},wasPlaying=${player.state.value.playing},wasPreparing=${player.state.value.preparing}")
            }
            val result = if (external?.state?.value?.preferredAppId != null) external.executeFromVoice(command)
            else localMedia.execute(command)
            val kind = when (command) {
                is MediaCommand.PlayMusic -> if (command.query.replace(Regex("\\s+"), "") in setOf("로컬파일", "선택한파일", "테스트음")) "LOCAL_ALIAS" else "OTHER_MUSIC"
                MediaCommand.Stop -> "STOP"
                MediaCommand.Next -> "NEXT"
                MediaCommand.PlayNewsAudio -> "NEWS"
            }
            trace("media=$kind,result=${(result as? OperationResult.Failure)?.reason ?: "SUCCESS"}")
            return result
        }
    }
    fun backend(): VoiceBackend {
        val delegate = MediaPreemptingVoiceBackend(PleosVoiceBackend(context) { event ->
            if (event.event != "STT_RMS_ACTIVE") trace("sdk=${event.event},length=${event.textLength ?: -1}")
        }, media) { enabled.value }
        return object : VoiceBackend by delegate {
            override suspend fun listen(): RecognitionResult {
                val result = delegate.listen()
                if (diagnostics) {
                    val parsed = (result as? RecognitionResult.Text)?.let { NormalCommandParser().parse(it.value) }
                    val intent = when (parsed) {
                        is NormalCommand.Select -> "SELECT_${parsed.mode},hasQuery=${parsed.query != null}"
                        NormalCommand.Stop -> "STOP"
                        NormalCommand.Next -> "NEXT"
                        else -> "UNKNOWN"
                    }
                    trace("result=${result.javaClass.simpleName},intent=$intent")
                    if (result is RecognitionResult.Text) {
                        val report = ConditionClassifier().classify(result.value)
                        val stripped = result.value.trim().trimEnd('?', '？', ',', '，', '.', '!', '。', '！')
                        trace("condition=$report,afterTerminalPunctuation=${ConditionClassifier().classify(stripped)},hasQuestionMark=${'?' in result.value || '？' in result.value}")
                    }
                }
                return result
            }
        }
    }
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
