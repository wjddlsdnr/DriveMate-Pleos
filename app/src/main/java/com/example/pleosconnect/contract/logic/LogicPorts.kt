package com.example.pleosconnect.contract.logic

import com.example.pleosconnect.contract.integration.RouteProgress
import com.example.pleosconnect.contract.model.DriverStatus
import com.example.pleosconnect.contract.voice.RecognitionResult
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/** All *AtMillis values use one injected monotonic clock within a process/boot session.
 * Never persist these deadlines across reboot or restart without revalidation.
 */
fun interface MonotonicClock {
    fun nowMillis(): Long
}

interface DriverStateManager {
    val status: StateFlow<DriverStatus>
    /** Analyze only driver-condition answers. Errors never imply confirmed sleep. */
    fun onConditionAnswer(answer: RecognitionResult, receivedAtMillis: Long)
    /** Called only after safe parking is verified; unknown vehicle state must block entry. */
    fun onRestStarted()
    /** Must enter recovery confirmation, never automatically NORMAL. */
    fun onRestEnded()
}

enum class CheckKind { PERIODIC, RISK_FOLLOW_UP, RECOVERY }
data class ScheduledCheck(val id: String, val kind: CheckKind, val dueAtMillis: Long)

interface CheckScheduler {
    val pending: StateFlow<List<ScheduledCheck>>
    /** Replace only periodic checks on revision change, deduplicate already delivered checks.
     * Null route cancels trip checks. Concrete scheduling belongs to Phase 4.
     */
    fun onRouteUpdated(route: RouteProgress?)
    /** delay must be finite/positive; risk and recovery checks are separate from periodic checks. */
    fun scheduleFollowUp(kind: CheckKind, delay: Duration)
    /** Atomically remove and return due checks so repeated polling cannot duplicate questions. */
    fun takeDue(): List<ScheduledCheck>
    fun cancelAll()
}

enum class ConversationPhase { IDLE, ASKING, LISTENING, PAUSED, CLOSED }

/** A owns orchestration; a future ViewModel observes state. No Activity/SDK dependency here. */
interface ConversationController {
    val phase: StateFlow<ConversationPhase>
    suspend fun start()
    /** Safety prompts preempt companion/media flow and stop in-progress voice operations. */
    suspend fun requestConditionCheck(kind: CheckKind)
    /** Stop listening/playback, preserve only non-sensitive context; no implicit restart. */
    suspend fun pause()
    suspend fun close()
}
