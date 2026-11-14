package com.example.pleosconnect.data.media

import android.content.Context
import android.media.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.example.pleosconnect.contract.model.FailureReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalAudioState(val available: Boolean = false, val selected: Boolean = false,
    val preparing: Boolean = false, val playing: Boolean = false, val failure: FailureReason? = null,
    val notice: String = "파일을 선택해도 자동으로 재생하지 않습니다.")

/** Foreground-only local file player. URI stays in memory; no broad storage/network permission. Main thread. */
class LocalAudioPlayer(context: Context) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(LocalAudioState(available = true))
    val state = mutable.asStateFlow()
    private var uri: Uri? = null
    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var job: Job? = null
    private var epoch = 0L
    private var active = false
    private var closed = false
    fun foreground() { if (!closed) active = true }
    fun pickerUnavailable() { stop(FailureReason.UNSUPPORTED, "이 기기에 오디오 파일 선택기가 없습니다.") }
    fun select(value: Uri) {
        if (closed) return
        if (!stop()) return
        if (value.scheme != "content" || !runCatching { context.contentResolver.getType(value)?.startsWith("audio/") == true }.getOrDefault(false)) {
            uri = null
            mutable.value = LocalAudioState(available = true, failure = FailureReason.INVALID_REQUEST, notice = "오디오 파일을 선택해 주세요.")
            return
        }
        uri = value
        mutable.value = LocalAudioState(available = true, selected = true, notice = "오디오 선택됨 · 재생 버튼을 눌러 시작하세요.")
    }
    fun play() {
        if (!active || closed || uri == null || state.value.preparing) return
        if (!stop()) return
        val selected = uri ?: return
        val token = epoch
        mutable.value = state.value.copy(preparing = true, failure = null, notice = "파일 준비 중…")
        job = scope.launch {
            try {
                val prepared = CompletableDeferred<Unit>()
                val instance = MediaPlayer()
                player = instance
                val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                instance.setAudioAttributes(attributes)
                instance.setOnPreparedListener { if (token == epoch) prepared.complete(Unit) }
                instance.setOnErrorListener { _, _, _ ->
                    if (token == epoch) {
                        prepared.completeExceptionally(IllegalStateException("Local audio decoding failed"))
                        if (state.value.playing) stop(FailureReason.SERVICE_UNAVAILABLE, "오디오 재생 오류로 중단했습니다.")
                    }
                    true
                }
                instance.setOnCompletionListener { if (token == epoch) stop(notice = "재생을 마쳤습니다.") }
                // Open through the user-granted content URI. Never retain a file descriptor or copy the audio.
                var descriptor: android.content.res.AssetFileDescriptor? = null
                try {
                    withContext(Dispatchers.IO) { descriptor = context.contentResolver.openAssetFileDescriptor(selected, "r") }
                    requireNotNull(descriptor)
                    if (!active || token != epoch) return@launch
                    val opened = requireNotNull(descriptor)
                    if (opened.length >= 0) instance.setDataSource(opened.fileDescriptor, opened.startOffset, opened.length)
                    else instance.setDataSource(opened.fileDescriptor)
                } finally { descriptor?.close() }
                instance.prepareAsync()
                withTimeout(8_000) { prepared.await() }
                if (!active || token != epoch) return@launch
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener({ change ->
                        if (token == epoch && change != AudioManager.AUDIOFOCUS_GAIN)
                            stop(notice = "다른 오디오의 포커스 요청으로 중단했습니다. 자동 재개하지 않습니다.")
                    }, handler).build()
                focus = request
                if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stop(FailureReason.SERVICE_UNAVAILABLE, "오디오 포커스를 얻지 못해 재생하지 않았습니다.")
                    return@launch
                }
                if (!active || token != epoch) return@launch
                session = MediaSession(context, "DriveMateLocalAudio").also { media ->
                    media.setCallback(object : MediaSession.Callback() {
                        override fun onPause() { stop(notice = "미디어 제어로 중단했습니다.") }
                        override fun onStop() { stop(notice = "미디어 제어로 중단했습니다.") }
                    }, handler)
                    media.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "선택한 로컬 오디오").build())
                    media.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
                    media.isActive = true
                }
                instance.start()
                check(instance.isPlaying)
                mutable.value = state.value.copy(preparing = false, playing = true, notice = "선택한 로컬 오디오 재생 중")
            } catch (_: TimeoutCancellationException) {
                if (token == epoch) stop(FailureReason.TIMEOUT, "파일 준비 시간이 초과됐습니다.")
            } catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { if (token == epoch) stop(FailureReason.PERMISSION_DENIED, "파일 접근 권한을 확인해 주세요.") }
            catch (_: Exception) { if (token == epoch) stop(FailureReason.SERVICE_UNAVAILABLE, "이 파일을 재생할 수 없습니다.") }
        }
    }
    fun stop(failure: FailureReason? = null, notice: String = "로컬 재생을 중단했습니다."): Boolean {
        epoch++
        job?.cancel(); job = null
        val previous = player; player = null
        val released = runCatching { previous?.release() }.isSuccess
        if (!released) player = previous
        session?.release(); session = null
        focus?.let { audio.abandonAudioFocusRequest(it) }; focus = null
        mutable.value = LocalAudioState(available = true, selected = uri != null,
            failure = failure ?: if (!released) FailureReason.SERVICE_UNAVAILABLE else null, notice = notice)
        return released
    }
    fun background() { active = false; stop(notice = "앱이 백그라운드로 이동해 중단했습니다. 자동 재개하지 않습니다.") }
    fun clear() { uri = null; stop(notice = "파일 선택을 해제했습니다.") }
    fun close() { active = false; closed = true; clear(); scope.cancel() }
    /** Native verification observes actual progression without exposing the selected URI. */
    internal fun positionMillis(): Int? = runCatching { player?.currentPosition }.getOrNull()
}
