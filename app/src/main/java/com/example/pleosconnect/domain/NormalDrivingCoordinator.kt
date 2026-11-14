package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class NormalModePhase { IDLE, ASKING, READY, CLARIFY, BLOCKED, PROFILE_CHANGED, FAILED, CANCELLED }
data class NormalModeStatus(
    val phase: NormalModePhase = NormalModePhase.IDLE,
    val mode: DrivingMode? = null,
    val failure: FailureReason? = null,
    val preferencesSaved: Boolean = false,
)

/** A-owned intent orchestration. Media/profile implementations and UI belong to B. */
class NormalDrivingCoordinator(
    private val safety: StateFlow<SafetyResponse>,
    private val profiles: DriverProfileRepository,
    private val media: MediaController,
    private val voice: VoiceService,
    private val clock: MonotonicClock,
    private val parser: NormalCommandParser = NormalCommandParser(),
) {
    private val switching = Mutex()
    private var active: Deferred<Unit>? = null
    private var closed = false
    private var clarificationUsed = false
    private var tasteAsked = false
    private var lastReceived = -1L
    private var lastClock = -1L
    private val offeredProfiles = mutableSetOf<String?>()
    private val mutableStatus = MutableStateFlow(NormalModeStatus())
    val status = mutableStatus.asStateFlow()

    suspend fun onCommand(text: String, receivedAtMillis: Long) = action(receivedAtMillis, false, false) { id ->
        execute(parser.parse(text), id, true)
    }

    suspend fun offerMode(retry: Boolean = false) = action(null, true, retry) { id ->
        val profile = profiles.selectedProfile.value
        val previous = profile?.previousMode
        val label = when (previous) {
            DrivingMode.MUSIC -> profile?.genres?.firstOrNull { it.isNotBlank() }?.let { "$it 음악" } ?: "음악"
            DrivingMode.QUIET -> "조용한 운전"
            DrivingMode.NEWS_AUDIO -> "뉴스"
            DrivingMode.COMPANION -> "말동무"
            null -> null
        }
        val prompt = label?.let { "오늘도 $it 모드를 선택하시겠어요?" }
            ?: "조용한 운전, 음악, 뉴스, 말동무 중 무엇을 원하시나요?"
        val reply = ask(prompt, id) ?: return@action
        if (previous != null && reply is RecognitionResult.Text && parser.affirmative(reply.value)) {
            execute(NormalCommand.Select(previous, if (previous == DrivingMode.MUSIC) preferredQuery(profile) else null), id, true)
        } else if (reply is RecognitionResult.Text) execute(parser.parse(reply.value), id, true)
        else speechFailure(reply)
    }

    private suspend fun action(stamp: Long?, offer: Boolean, retry: Boolean, block: suspend (String?) -> Unit): Unit = coroutineScope {
        val invalidated = AtomicBoolean(false)
        val task = switching.withLock {
            if (closed) return@coroutineScope
            if (stamp != null) {
                require(stamp in 0..now())
                if (stamp <= lastReceived) return@coroutineScope
            }
            val id = profiles.selectedProfile.value?.profileId
            if (offer && !retry && id in offeredProfiles) return@coroutineScope
            active?.cancelAndJoin()
            if (stamp != null) lastReceived = stamp
            if (!normal()) { stopBlocked(); return@coroutineScope }
            if (offer) offeredProfiles += id
            async(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext().job
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    combine(safety, profiles.selectedProfile) { _, _ -> fresh(id) }.first { !it }
                    invalidated.set(true); owner.cancel()
                }
                try {
                    checkFresh(id)
                    mutableStatus.value = status.value.copy(failure = null, preferencesSaved = false)
                    clarificationUsed = false
                    tasteAsked = false
                    block(id)
                } catch (cancelled: CancellationException) {
                    val stopped = withContext(NonCancellable) { call { media.execute(MediaCommand.Stop) } }
                    mutableStatus.value = NormalModeStatus(
                        if (invalidated.get()) { if (!normal()) NormalModePhase.BLOCKED else NormalModePhase.PROFILE_CHANGED }
                        else NormalModePhase.CANCELLED,
                        failure = (stopped as? OperationResult.Failure)?.reason,
                    )
                    throw cancelled
                } catch (_: StaleChoice) {
                    invalidated.set(true)
                    val stopped = withContext(NonCancellable) { call { media.execute(MediaCommand.Stop) } }
                    mutableStatus.value = NormalModeStatus(if (!normal()) NormalModePhase.BLOCKED else NormalModePhase.PROFILE_CHANGED,
                        failure = (stopped as? OperationResult.Failure)?.reason)
                } finally {
                    watcher.cancel()
                    withContext(NonCancellable) { call { voice.stop(); OperationResult.Success(Unit) } }
                }
            }.also { active = it; it.start() }
        }
        try { task.await() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (!invalidated.get()) throw cancelled }
    }

    private suspend fun execute(command: NormalCommand, id: String?, allowTaste: Boolean) {
        checkFresh(id)
        when (command) {
            NormalCommand.Unknown -> clarify(id)
            NormalCommand.Stop -> select(DrivingMode.QUIET, null, null, id)
            NormalCommand.Next -> {
                if (status.value.mode != DrivingMode.MUSIC) clarify(id)
                else {
                    val next = call { media.execute(MediaCommand.Next) }
                    checkFresh(id)
                    if (next is OperationResult.Failure) failed(next.reason)
                    else mutableStatus.value = status.value.copy(phase = NormalModePhase.READY)
                }
            }
            is NormalCommand.Select -> {
                var query = command.query
                if (command.mode == DrivingMode.MUSIC && query == null) query = preferredQuery(profiles.selectedProfile.value)
                if (command.mode == DrivingMode.MUSIC && query == null) {
                    if (!allowTaste || tasteAsked) { clarify(id); return }
                    tasteAsked = true
                    val reply = ask("어떤 음악을 좋아하시나요? 좋아하는 장르나 가수를 말씀해 주세요.", id) ?: return
                    if (reply !is RecognitionResult.Text) { speechFailure(reply); return }
                    val preference = parser.preference(reply.value)
                    if (preference != null) select(DrivingMode.MUSIC, preference.query, preference, id)
                    else execute(parser.parse(reply.value), id, false)
                } else select(command.mode, query, null, id)
            }
        }
    }

    private suspend fun select(mode: DrivingMode, query: String?, preference: MusicPreference?, id: String?) {
        checkFresh(id)
        val stopped = call { media.execute(MediaCommand.Stop) }
        checkFresh(id)
        if (stopped is OperationResult.Failure) { mutableStatus.value = status.value.copy(mode = null); failed(stopped.reason); return }
        mutableStatus.value = status.value.copy(mode = DrivingMode.QUIET)
        val command = when (mode) {
            DrivingMode.MUSIC -> MediaCommand.PlayMusic(requireNotNull(query))
            DrivingMode.NEWS_AUDIO -> MediaCommand.PlayNewsAudio
            else -> null
        }
        if (command != null) {
            val played = call { media.execute(command) }
            checkFresh(id)
            if (played is OperationResult.Failure) {
                // A timeout may have happened after playback started. Never retry Play automatically.
                val cleanup = call { media.execute(MediaCommand.Stop) }
                checkFresh(id)
                mutableStatus.value = status.value.copy(mode = if (cleanup is OperationResult.Success) DrivingMode.QUIET else null)
                failed(if (cleanup is OperationResult.Failure) cleanup.reason else played.reason)
                return
            }
        }
        mutableStatus.value = NormalModeStatus(NormalModePhase.READY, mode)
        val latest = profiles.selectedProfile.value
        if (id != null && latest?.profileId == id) {
            val updated = latest.copy(
                previousMode = mode,
                genres = preference?.genre?.let { (listOf(it) + latest.genres).distinct() } ?: latest.genres,
                artists = preference?.artist?.let { (listOf(it) + latest.artists).distinct() } ?: latest.artists,
                playlists = preference?.playlist?.let { (listOf(it) + latest.playlists).distinct() } ?: latest.playlists,
                contentPreferences = if (mode == DrivingMode.NEWS_AUDIO) (latest.contentPreferences + "뉴스").distinct() else latest.contentPreferences,
            )
            checkFresh(id)
            val saved = call { profiles.savePreferences(updated) }
            checkFresh(id)
            if (saved is OperationResult.Success) mutableStatus.value = status.value.copy(preferencesSaved = true)
            else if (saved is OperationResult.Failure) failed(saved.reason)
        }
    }

    private suspend fun ask(prompt: String, id: String?): RecognitionResult? {
        checkFresh(id)
        mutableStatus.value = status.value.copy(phase = NormalModePhase.ASKING)
        val spoken = call(30.seconds) { voice.speak(prompt) }
        checkFresh(id)
        if (spoken is OperationResult.Failure) { failed(spoken.reason); return null }
        val answer = try { withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { RecognitionResult.PermissionDenied }
        catch (_: Exception) { RecognitionResult.ServiceUnavailable }
        checkFresh(id)
        return answer
    }
    private suspend fun clarify(id: String?) {
        checkFresh(id)
        if (!clarificationUsed) {
            clarificationUsed = true
            val reply = ask("원하시는 모드를 다시 말씀해 주세요. 조용한 운전, 음악, 뉴스, 말동무 중 선택할 수 있습니다.", id) ?: return
            if (reply is RecognitionResult.Text) execute(parser.parse(reply.value), id, true)
            else speechFailure(reply)
            return
        }
        val result = call(30.seconds) { voice.speak("선택을 확인하지 못했습니다. 모드 선택을 종료합니다.") }
        checkFresh(id)
        if (result is OperationResult.Failure) failed(result.reason)
        else mutableStatus.value = status.value.copy(phase = NormalModePhase.CLARIFY)
    }

    /** Caller owns this collection. Never auto-resumes content when NORMAL returns. */
    suspend fun observeChanges() {
        var initialized = false
        var previousId: String? = null
        combine(safety, profiles.selectedProfile) { _, profile -> normal() to profile?.profileId }
            .distinctUntilChanged().collect { (safe, id) ->
                if (!safe || (initialized && id != previousId)) switching.withLock {
                    if (!closed) {
                        active?.cancelAndJoin()
                        stopBlocked(if (safe) NormalModePhase.PROFILE_CHANGED else NormalModePhase.BLOCKED)
                    }
                }
                initialized = true; previousId = id
            }
    }
    suspend fun cancel() = switching.withLock {
        active?.cancelAndJoin(); active = null; stopBlocked(NormalModePhase.CANCELLED)
    }
    suspend fun close() = switching.withLock {
        if (!closed) { closed = true; active?.cancelAndJoin(); active = null; stopBlocked() }
    }
    private suspend fun stopBlocked(phase: NormalModePhase = NormalModePhase.BLOCKED) {
        val stopped = withContext(NonCancellable) { call { media.execute(MediaCommand.Stop) } }
        mutableStatus.value = NormalModeStatus(phase, failure = (stopped as? OperationResult.Failure)?.reason)
    }
    private fun preferredQuery(profile: DriverPreferences?): String? = profile?.genres?.firstOrNull { it.isNotBlank() }
        ?: profile?.artists?.firstOrNull { it.isNotBlank() }?.let { "$it 노래" }
        ?: profile?.playlists?.firstOrNull { it.isNotBlank() }?.let { "$it 플레이리스트" }
    private fun normal(): Boolean {
        val current = safety.value
        return current.driver.state == DriverState.NORMAL && current.driver.report == ConditionReport.NORMAL &&
            !current.driver.followUpRequired && !current.restStopNeeded
    }
    private fun fresh(id: String?) = normal() && profiles.selectedProfile.value?.profileId == id
    private fun checkFresh(id: String?) { if (!fresh(id)) throw StaleChoice() }
    private fun speechFailure(result: RecognitionResult) {
        failed(when (result) {
            RecognitionResult.PermissionDenied -> FailureReason.PERMISSION_DENIED
            RecognitionResult.Timeout -> FailureReason.TIMEOUT
            RecognitionResult.NoResponse -> FailureReason.CONSENT_REQUIRED
            else -> FailureReason.SERVICE_UNAVAILABLE
        })
    }
    private fun failed(reason: FailureReason) { mutableStatus.value = status.value.copy(phase = NormalModePhase.FAILED, failure = reason) }
    private suspend fun <T> call(timeout: Duration = 15.seconds, block: suspend () -> OperationResult<T>): OperationResult<T> = try {
        withTimeoutOrNull(timeout) { block() } ?: OperationResult.Failure(FailureReason.TIMEOUT)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
    catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
    @Synchronized private fun now(): Long {
        val value = clock.nowMillis(); require(value >= 0 && value >= lastClock); lastClock = value; return value
    }
    private class StaleChoice : RuntimeException()
}
