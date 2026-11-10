package com.example.pleosconnect.voice

import com.example.pleosconnect.contract.integration.DriverProfileRepository
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

enum class CompanionStage { IDLE, SPEAKING, LISTENING, ENDED, BLOCKED, CANCELLED, FAILED, CLOSED }
data class CompanionStatus(
    val stage: CompanionStage = CompanionStage.IDLE,
    val completedTurns: Int = 0,
    val failure: FailureReason? = null,
    val needsConditionCheck: Boolean = false,
)

/** Fixed two-turn local dialogue. No Gleo/LLM, network, content playback or stored transcript. */
class LimitedCompanionController(
    private val safety: StateFlow<SafetyResponse>,
    private val mode: StateFlow<NormalModeStatus>,
    private val profiles: DriverProfileRepository,
    private val foreground: StateFlow<Boolean>,
    private val voice: VoiceService,
    private val conditionCheck: suspend (CheckKind) -> Unit,
) : ConversationController {
    private val control = Mutex()
    private val priorityControl = Mutex()
    private var active: Deferred<Unit>? = null
    @Volatile private var closed = false
    @Volatile private var priority = false
    @Volatile private var cleanupBroken = false
    @Volatile private var conditionPending = false
    private var pendingConfirmationAt: Long? = null
    private var voiceTouched = false
    private val backgroundBlocked = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(CompanionStatus())
    val status = mutableStatus.asStateFlow()
    private val mutablePhase = MutableStateFlow(ConversationPhase.IDLE)
    override val phase = mutablePhase.asStateFlow()
    private val classifier = ConditionClassifier()

    override suspend fun start(): Unit = coroutineScope {
        if (!control.tryLock()) return@coroutineScope
        val invalidated = AtomicBoolean(false)
        val task = try {
            if (closed || active?.isCompleted == false || priority) return@coroutineScope
            if (voice.phase.value != VoicePhase.IDLE) {
                if (voice.phase.value == VoicePhase.UNAVAILABLE) cleanupBroken = true
                update(CompanionStatus(CompanionStage.BLOCKED, failure = FailureReason.SERVICE_UNAVAILABLE,
                    needsConditionCheck = conditionPending))
                return@coroutineScope
            }
            if (!allowed()) {
                update(CompanionStatus(CompanionStage.BLOCKED,
                    failure = if (cleanupBroken) FailureReason.SERVICE_UNAVAILABLE else null,
                    needsConditionCheck = conditionPending))
                return@coroutineScope
            }
            voiceTouched = false
            val profileId = profiles.selectedProfile.value?.profileId
            async(start = CoroutineStart.LAZY) { runSession(profileId, invalidated) }
                .also { active = it; it.start() }
        } finally { control.unlock() }
        try { task.await() }
        catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); if (!invalidated.get()) throw cancelled }
    }

    private suspend fun runSession(profileId: String?, invalidated: AtomicBoolean) = coroutineScope {
        val owner = currentCoroutineContext().job
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            combine(safety, mode, profiles.selectedProfile, foreground) { _, _, profile, _ ->
                allowed() && profile?.profileId == profileId
            }.first { !it }
            invalidated.set(true); owner.cancel()
        }
        update(CompanionStatus())
        try {
            val done = withTimeoutOrNull(60.seconds) {
                checkAllowed(profileId)
                val first = ask("짧게 이야기할게요. 음악과 풍경 중 어떤 이야기를 원하세요?", profileId) ?: return@withTimeoutOrNull true
                if (terminal(first)) return@withTimeoutOrNull true
                val music = first in setOf("음악", "음악이야기")
                val scenery = first in setOf("풍경", "풍경이야기")
                if (!music && !scenery) { unsupported(profileId); return@withTimeoutOrNull true }
                val second = ask(if (music) "음악 취향은 다양하죠. 발라드와 재즈 중 어떤 쪽을 좋아하세요?"
                    else "운전 중에는 도로에 집중해 주세요. 산과 바다 중 어디를 좋아하세요?", profileId) ?: return@withTimeoutOrNull true
                if (terminal(second)) return@withTimeoutOrNull true
                val reply = when {
                    music && second in setOf("발라드", "발라드좋아해", "발라드좋아해요") -> "잔잔한 발라드를 좋아하시는군요. 짧은 이야기는 여기서 마칠게요."
                    music && second in setOf("재즈", "재즈좋아해", "재즈좋아해요") -> "재즈의 리듬을 좋아하시는군요. 짧은 이야기는 여기서 마칠게요."
                    scenery && second in setOf("산", "산좋아해", "산좋아해요") -> "산의 풍경을 좋아하시는군요. 짧은 이야기는 여기서 마칠게요."
                    scenery && second in setOf("바다", "바다좋아해", "바다좋아해요") -> "바다의 풍경을 좋아하시는군요. 짧은 이야기는 여기서 마칠게요."
                    else -> null
                }
                if (reply == null) unsupported(profileId)
                else if (speak(reply, profileId)) update(status.value.copy(stage = CompanionStage.ENDED))
                true
            }
            if (done == null) update(status.value.copy(stage = CompanionStage.FAILED, failure = FailureReason.TIMEOUT))
        } catch (cancelled: CancellationException) {
            update(status.value.copy(stage = if (invalidated.get()) CompanionStage.BLOCKED else CompanionStage.CANCELLED))
            throw cancelled
        } catch (_: InvalidContext) {
            invalidated.set(true); update(status.value.copy(stage = CompanionStage.BLOCKED))
        } finally {
            watcher.cancel()
            withContext(NonCancellable) {
                if (voiceTouched) {
                    try { voice.stop(); if (voice.phase.value == VoicePhase.UNAVAILABLE) cleanupBroken = true }
                    catch (_: Exception) { cleanupBroken = true }
                    if (cleanupBroken) update(status.value.copy(stage = CompanionStage.FAILED, failure = FailureReason.SERVICE_UNAVAILABLE))
                }
            }
        }
    }

    private suspend fun ask(prompt: String, profileId: String?): String? {
        if (!speak(prompt, profileId)) return null
        checkAllowed(profileId)
        update(status.value.copy(stage = CompanionStage.LISTENING))
        val answer = try { withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { RecognitionResult.PermissionDenied }
        catch (_: Exception) { RecognitionResult.ServiceUnavailable }
        checkAllowed(profileId)
        if (answer is RecognitionResult.Text && answer.value.isNotBlank()) {
            update(status.value.copy(completedTurns = status.value.completedTurns + 1))
            return Normalizer.normalize(answer.value, Normalizer.Form.NFC).replace(Regex("\\s+"), "").trimEnd('.', '!')
        }
        if (answer == RecognitionResult.NoResponse) update(status.value.copy(stage = CompanionStage.ENDED))
        else update(status.value.copy(stage = CompanionStage.FAILED, failure = when (answer) {
            RecognitionResult.PermissionDenied -> FailureReason.PERMISSION_DENIED
            RecognitionResult.Timeout -> FailureReason.TIMEOUT
            else -> FailureReason.SERVICE_UNAVAILABLE
        }))
        return null
    }
    private suspend fun speak(text: String, profileId: String?): Boolean {
        checkAllowed(profileId)
        update(status.value.copy(stage = CompanionStage.SPEAKING))
        voiceTouched = true
        val result = try { withTimeoutOrNull(30.seconds) { voice.speak(text) } ?: OperationResult.Failure(FailureReason.TIMEOUT) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { OperationResult.Failure(FailureReason.PERMISSION_DENIED) }
        catch (_: Exception) { OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE) }
        checkAllowed(profileId)
        if (result is OperationResult.Failure) { update(status.value.copy(stage = CompanionStage.FAILED, failure = result.reason)); return false }
        return true
    }
    private fun terminal(text: String): Boolean {
        if (text in setOf("그만", "대화그만", "말동무그만", "조용히", "중단")) {
            update(status.value.copy(stage = CompanionStage.ENDED)); return true
        }
        if (classifier.classify(text) in setOf(ConditionReport.MILD_FATIGUE, ConditionReport.PERSISTENT_FATIGUE, ConditionReport.SEVERE)) {
            pendingConfirmationAt = safety.value.driver.lastConfirmedAtMillis
            conditionPending = true
            update(status.value.copy(stage = CompanionStage.ENDED, needsConditionCheck = true)); return true
        }
        return false
    }
    private suspend fun unsupported(profileId: String?) {
        if (speak("정해진 짧은 주제만 이야기할 수 있어요. 여기서 대화를 마칠게요.", profileId))
            update(status.value.copy(stage = CompanionStage.ENDED))
    }

    suspend fun <T> withSafetyPriority(block: suspend () -> T): T = priorityControl.withLock {
        try {
            control.withLock { priority = true; active?.cancelAndJoin() }
            check(!cleanupBroken && voice.phase.value != VoicePhase.UNAVAILABLE) { "Companion voice cleanup could not be verified" }
            block()
        } finally { withContext(NonCancellable) { control.withLock { priority = false } } }
    }
    override suspend fun requestConditionCheck(kind: CheckKind) {
        if (closed) return
        withSafetyPriority {
            conditionCheck(kind)
            val confirmedAt = safety.value.driver.lastConfirmedAtMillis
            if (conditionPending && confirmedNormal() && confirmedAt != null &&
                (pendingConfirmationAt == null || confirmedAt > pendingConfirmationAt!!)) {
                conditionPending = false
                update(status.value.copy(needsConditionCheck = false))
            }
        }
    }
    override suspend fun pause() { control.withLock { active?.cancelAndJoin(); active = null } }
    suspend fun onBackground() { backgroundBlocked.set(true); pause() }
    suspend fun onForeground() { backgroundBlocked.set(false) }
    override suspend fun close() {
        withContext(NonCancellable) {
            control.withLock {
                if (!closed) { closed = true; active?.cancelAndJoin(); active = null; update(CompanionStatus(CompanionStage.CLOSED)) }
            }
        }
    }
    private fun allowed(): Boolean {
        return !closed && !priority && !cleanupBroken && !conditionPending && !backgroundBlocked.get() && foreground.value &&
            confirmedNormal() &&
            mode.value.mode == DrivingMode.COMPANION && mode.value.phase == NormalModePhase.READY
    }
    private fun confirmedNormal(): Boolean {
        val state = safety.value
        return state.driver.state == DriverState.NORMAL && state.driver.report == ConditionReport.NORMAL &&
            !state.driver.followUpRequired && !state.restStopNeeded
    }
    private fun checkAllowed(profileId: String?) {
        if (!allowed() || profiles.selectedProfile.value?.profileId != profileId) throw InvalidContext()
    }
    private fun update(value: CompanionStatus) {
        mutableStatus.value = value
        mutablePhase.value = when (value.stage) {
            CompanionStage.SPEAKING -> ConversationPhase.ASKING
            CompanionStage.LISTENING -> ConversationPhase.LISTENING
            CompanionStage.IDLE, CompanionStage.ENDED -> ConversationPhase.IDLE
            CompanionStage.CLOSED -> ConversationPhase.CLOSED
            else -> ConversationPhase.PAUSED
        }
    }
    private class InvalidContext : RuntimeException()
}
