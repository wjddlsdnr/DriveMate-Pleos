package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.model.FailureReason
import com.example.pleosconnect.contract.model.OperationResult
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.contract.voice.VoicePhase
import com.example.pleosconnect.contract.voice.VoiceService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface QuestionResult {
    data class Answer(val result: RecognitionResult) : QuestionResult
    data class PlaybackFailed(val reason: FailureReason) : QuestionResult
}

/** One owner per backend. Busy requests fail instead of building a stale question queue.
 * stop/pause cancels the active transaction and waits for resource release.
 */
class SerializedVoiceService(
    private val backend: VoiceBackend,
    private val playbackTimeout: Duration = 30.seconds,
) : VoiceService {
    init { require(playbackTimeout.isFinite() && playbackTimeout > Duration.ZERO) }
    private val guard = Any()
    private val controls = Mutex()
    private var active: Job? = null
    private var stopping = false
    private var closed = false
    private var broken = false
    private val mutablePhase = MutableStateFlow(VoicePhase.IDLE)
    override val phase = mutablePhase.asStateFlow()

    private suspend fun <T> transaction(rejected: () -> T, block: suspend () -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val accepted = synchronized(guard) {
            if (closed || stopping || broken || active != null) false
            else { active = job; true }
        }
        if (!accepted) return@coroutineScope rejected()
        try { block() } finally {
            withContext(NonCancellable) {
                try { backend.stop() }
                catch (_: Exception) { synchronized(guard) { broken = true } }
                synchronized(guard) {
                    active = null
                    mutablePhase.value = if (closed || broken) VoicePhase.UNAVAILABLE else VoicePhase.IDLE
                }
            }
        }
    }

    private suspend fun play(text: String): OperationResult<Unit> {
        if (text.isBlank()) return OperationResult.Failure(FailureReason.INVALID_REQUEST)
        mutablePhase.value = VoicePhase.SPEAKING
        return try {
            withTimeoutOrNull(playbackTimeout) { backend.speak(text) }
                ?: OperationResult.Failure(FailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
    }

    private suspend fun recognize(timeout: Duration): RecognitionResult {
        mutablePhase.value = VoicePhase.LISTENING
        return try {
            val result = withTimeoutOrNull(timeout) { backend.listen() } ?: RecognitionResult.Timeout
            if (result is RecognitionResult.Text && result.value.isBlank()) RecognitionResult.RecognitionFailure
            else result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { RecognitionResult.PermissionDenied }
        catch (_: Exception) { RecognitionResult.ServiceUnavailable }
    }

    override suspend fun speak(text: String): OperationResult<Unit> = transaction(
        { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) },
    ) { play(text) }

    override suspend fun listen(timeout: Duration): RecognitionResult {
        validate(timeout)
        return transaction({ RecognitionResult.ServiceUnavailable }) { recognize(timeout) }
    }

    /** Atomic question/answer transaction: no competing speak/listen can enter between them. */
    suspend fun ask(prompt: String, timeout: Duration): QuestionResult {
        validate(timeout)
        return transaction({ QuestionResult.PlaybackFailed(FailureReason.SERVICE_UNAVAILABLE) }) {
            when (val playback = play(prompt)) {
                is OperationResult.Failure -> QuestionResult.PlaybackFailed(playback.reason)
                is OperationResult.Success -> QuestionResult.Answer(recognize(timeout))
            }
        }
    }

    private fun validate(timeout: Duration) {
        require(timeout.isFinite() && timeout > Duration.ZERO)
    }

    override suspend fun stop() = shutdown(false)
    override suspend fun close() = shutdown(true)

    private suspend fun shutdown(permanent: Boolean) = withContext(NonCancellable) {
        controls.withLock {
            val job = synchronized(guard) {
                if (closed) return@withLock
                stopping = true
                if (permanent) closed = true
                active
            }
            job?.cancelAndJoin()
            try {
                if (permanent) backend.close() else backend.stop()
            } catch (_: Exception) {
                synchronized(guard) { broken = true }
            } finally {
                synchronized(guard) {
                    stopping = false
                    mutablePhase.value = if (closed || broken) VoicePhase.UNAVAILABLE else VoicePhase.IDLE
                }
            }
        }
    }
}
