package com.example.pleosconnect.pleos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitialize
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitialize
import ai.pleos.playground.tts.listener.EventListener
import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.contract.model.OperationResult
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.voice.VoiceBackend
import com.example.pleosconnect.voice.testPhraseMatch
import kotlinx.coroutines.*
import java.util.Locale

/** REAL SDK adapter (3.3.1), runtime compatibility not yet verified on Pleos Emulator.
 * ON_DEVICE only: no credentials or remote speech registration. Use through
 * SerializedVoiceService, never share this backend between services.
 * A new SDK object per operation isolates late callbacks after cancellation.
 */
class PleosVoiceBackend(
    context: Context,
    private val diagnostic: (VoiceDiagnostic) -> Unit = {},
) : VoiceBackend {
    private fun trace(event: String, length: Int? = null) {
        // Diagnostics cannot interrupt SDK cleanup or voice processing.
        runCatching { diagnostic(VoiceDiagnostic(event, length)) }
    }
    private val appContext = context.applicationContext
    private val debugDiagnostics = appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private var closed = false
    private var poisoned = false
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    override suspend fun speak(text: String): OperationResult<Unit> = withContext(Dispatchers.Main.immediate) {
        if (closed || poisoned) return@withContext OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        val done = CompletableDeferred<OperationResult<Unit>>()
        val initialized = CompletableDeferred<TtsError?>()
        val focus = focusRequest {
            initialized.complete(TtsError.AUDIO_ERROR)
            done.complete(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE))
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            return@withContext OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        var sdk: TextToSpeech? = null
        var registered = false
        val listener = object : EventListener {
            override fun onReady() = Unit
            override fun onStart() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onDone() { trace("TTS_DONE"); done.complete(OperationResult.Success(Unit)) }
            override fun onStop() { done.complete(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
            override fun onError(errorType: TtsError) { done.complete(OperationResult.Failure(mapTtsError(errorType))) }
        }
        try {
            val tts = TextToSpeech(appContext, TtsMode.ON_DEVICE)
            sdk = tts
            tts.initialize(object : TtsInitialize {
                override fun onSuccess() { initialized.complete(null) }
                override fun onFailure(errorType: TtsError) { initialized.complete(errorType) }
            })
            val error = initialized.await()
            if (error != null) return@withContext OperationResult.Failure(mapTtsError(error))
            tts.addEventListener(listener)
            registered = true
            tts.setLocale(Locale.KOREA)
            tts.setAudioAttributes(attributes)
            tts.speak(text, true)
            done.await()
        } finally {
            cleanup(
                { sdk?.stop() },
                { if (registered) sdk?.removeEventListener(listener) },
                { sdk?.release() },
                { audio.abandonAudioFocusRequest(focus) },
            )
        }
    }

    override suspend fun listen(): RecognitionResult = withContext(Dispatchers.Main.immediate) {
        if (closed || poisoned) return@withContext RecognitionResult.ServiceUnavailable
        val done = CompletableDeferred<RecognitionResult>()
        val initialized = CompletableDeferred<SttError?>()
        val focus = focusRequest {
            initialized.complete(SttError.AUDIO_ERROR)
            done.complete(RecognitionResult.ServiceUnavailable)
        }
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            return@withContext RecognitionResult.ServiceUnavailable
        var sdk: SpeechToText? = null
        var registered = false
        val result = PleosRecognitionResult()
        val listener = object : ResultListener {
            override fun onReady() { trace("STT_READY") }
            override fun onStartedRecognition() { trace("STT_STARTED") }
            override fun onUpdatedEpdData(on: Long, off: Long) = Unit
            override fun onUpdatedRms(rms: Double) { if (rms.isFinite() && rms > 0) trace("STT_RMS_ACTIVE") }
            override fun onUpdated(stt: String, completed: Boolean) {
                trace(if (completed) "STT_FINAL" else "STT_INTERIM", stt.length)
                if (completed && debugDiagnostics) {
                    trace("STT_FINAL_TEST_${testPhraseMatch(stt).name}")
                }
                result.update(stt, completed)
                if (completed) done.complete(result.ended())
            }
            // Speech-segment end can precede final text. Do not release the SDK here.
            // If no final callback arrives, SerializedVoiceService applies its deadline.
            override fun onEndedRecognition() { trace("STT_SEGMENT_ENDED") }
            override fun onError(errorType: SttError) { trace("STT_ERROR_${errorType.name}"); done.complete(mapSttError(errorType)) }
        }
        try {
            val stt = SpeechToText(appContext, SttMode.ON_DEVICE)
            sdk = stt
            stt.initialize(object : SttInitialize {
                override fun onSuccess() { initialized.complete(null) }
                override fun onFailure(errorType: SttError) { initialized.complete(errorType) }
            })
            val error = initialized.await()
            if (error != null) return@withContext mapSttError(error)
            stt.addListener(listener)
            registered = true
            stt.setLocale(Locale.KOREA)
            stt.request()
            trace("STT_REQUESTED")
            done.await()
        } finally {
            cleanup(
                { sdk?.stop() },
                { if (registered) sdk?.removeListener(listener) },
                { sdk?.release() },
                { audio.abandonAudioFocusRequest(focus) },
            )
            trace("STT_RELEASED")
        }
    }

    private fun focusRequest(onLost: () -> Unit) =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change -> if (change < 0) onLost() }
            .build()

    private fun cleanup(vararg actions: () -> Unit) {
        var failure: Exception? = null
        actions.forEach { action ->
            try { action() } catch (error: Exception) { failure = failure ?: error }
        }
        failure?.let { poisoned = true; throw it }
    }

    // Operation finally blocks perform stop/listener removal/release even on cancellation.
    override suspend fun stop() = Unit
    override suspend fun close() { closed = true }
}

internal fun mapSttError(error: SttError): RecognitionResult = when (error) {
    SttError.PERMISSION_DENIED -> RecognitionResult.PermissionDenied
    SttError.TIMEOUT -> RecognitionResult.Timeout
    SttError.AUDIO_ERROR, SttError.MIC_ERROR, SttError.INVALID_REQUEST_ERROR -> RecognitionResult.RecognitionFailure
    else -> RecognitionResult.ServiceUnavailable
}

internal fun mapTtsError(error: TtsError): FailureReason = when (error) {
    TtsError.PERMISSION_DENIED -> FailureReason.PERMISSION_DENIED
    TtsError.TIMEOUT -> FailureReason.TIMEOUT
    TtsError.NOT_SUPPORTED_API, TtsError.UNAVAILABLE_LANGUAGE -> FailureReason.UNSUPPORTED
    TtsError.INVALID_REQUEST_ERROR -> FailureReason.INVALID_REQUEST
    else -> FailureReason.SERVICE_UNAVAILABLE
}

/** No documented silence-specific event in 3.3.1. An empty end is uncertain,
 * not NoResponse. Intermediate recognition must never become a driver answer.
 */
internal class PleosRecognitionResult {
    private var finalText: String? = null
    @Synchronized fun update(text: String, completed: Boolean) {
        if (completed) finalText = text.takeIf { it.isNotBlank() }
    }
    @Synchronized fun ended(): RecognitionResult =
        finalText?.let { RecognitionResult.Text(it) } ?: RecognitionResult.RecognitionFailure
}
