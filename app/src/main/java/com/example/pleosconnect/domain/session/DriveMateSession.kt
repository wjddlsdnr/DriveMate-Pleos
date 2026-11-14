package com.example.pleosconnect.domain.session

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.*
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.domain.*
import com.example.pleosconnect.domain.rest.*
import com.example.pleosconnect.scheduler.DefaultCheckScheduler
import com.example.pleosconnect.voice.LimitedCompanionController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

/** One driver/foreground session. B supplies ports; only this entry point may run its children. */
class DriveMateSession(
    parentScope: CoroutineScope,
    private val voice: VoiceService,
    private val navigation: NavigationController,
    finder: RestStopFinder,
    vehicle: VehicleController,
    private val media: MediaController,
    private val profiles: DriverProfileRepository,
    parking: SafeParkingCheck,
    private val clock: MonotonicClock,
    store: RestSessionStore,
) {
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val switching = Mutex()
    private val foreground = MutableStateFlow(true)
    @Volatile private var invalidated = false
    @Volatile private var closed = false
    private var active: Deferred<Unit>? = null
    private var activeSafety = false
    @Volatile private var restSchedulingPaused = false
    private val initializedTrips = mutableSetOf<String>()
    private val mutableFailure = MutableStateFlow<FailureReason?>(null)
    val failure = mutableFailure.asStateFlow()
    private val profileId = profiles.selectedProfile.value?.profileId
    private val driver = DefaultDriverStateManager(clock, parking)
    private val scheduler = DefaultCheckScheduler(clock)
    private val snapshot = MutableStateFlow(SafetyResponse(driver.status.value))
    private lateinit var companion: LimitedCompanionController
    private val companionPort = object : ConversationController {
        override val phase = MutableStateFlow(ConversationPhase.IDLE)
        override suspend fun start() = Unit
        override suspend fun requestConditionCheck(kind: CheckKind) = Unit
        override suspend fun pause() { companion.pause() }
        override suspend fun close() { companion.close() }
    }
    private val safety = SafetyResponseCoordinator(driver, scheduler, voice, vehicle, media, companionPort, clock)
    private val normal = NormalDrivingCoordinator(snapshot, profiles, media, voice, clock)
    private val guidance = RestStopGuidanceCoordinator(snapshot, finder, navigation, voice, clock)
    private val rest = RestSessionCoordinator(clock, parking, driver, navigation, voice, foreground,
        RestVoiceOwner { it() }, store)
    val driverStatus = driver.status
    val safetyStatus = snapshot.asStateFlow()
    val modeStatus = normal.status
    val restStatus = rest.status
    val guidanceStatus = guidance.status
    val pendingChecks = scheduler.pending

    init {
        sessionJob.invokeOnCompletion {
            invalidated = true; foreground.value = false; active?.cancel(); scheduler.cancelAll()
        }
        companion = LimitedCompanionController(snapshot, normal.status, profiles, foreground, voice) { checkCondition(it) }
        scope.launch { navigation.route.collect { route ->
            if (restSchedulingPaused || driver.status.value.state == DriverState.RESTING) scheduler.pauseForRest()
            scheduler.onRouteUpdated(route)
            if (route == null) { active?.cancel(); scheduler.cancelAll() }
        } }
        scope.launch { combine(driver.status, safety.response) { _, _ -> Unit }.collect { refresh() } }
        scope.launch { profiles.selectedProfile.collect { profile ->
            if (profile?.profileId != profileId) { invalidated = true; suspendSession() }
        } }
        scope.launch { normal.observeChanges() }
        scope.launch { safety.observeManualOverrides() }
    }
    private fun refresh() {
        val current = driver.status.value
        snapshot.value = safety.response.value.copy(driver = current,
            restStopNeeded = current.state != DriverState.NORMAL && current.state != DriverState.RESTING && safety.response.value.restStopNeeded)
    }
    private fun usable() = !closed && !invalidated && foreground.value && profiles.selectedProfile.value?.profileId == profileId

    /** First condition check also consumes the scheduler's departure check. */
    suspend fun initialCheck() = execute(priority = true) {
        val route = navigation.route.value ?: return@execute
        if (!initializedTrips.add(route.routeId)) return@execute
        scheduler.onRouteUpdated(route)
        scheduler.takeDue()
        checkCondition(CheckKind.PERIODIC)
        if (driver.status.value.state == DriverState.NORMAL && driver.status.value.report == ConditionReport.NORMAL) normal.offerMode()
    }
    suspend fun conditionCheck(kind: CheckKind = CheckKind.RISK_FOLLOW_UP) = execute(priority = true) {
        // This explicit check also satisfies questions already due (including departure).
        // Keep future periodic/risk checks; otherwise the next tick repeats this question.
        scheduler.takeDue()
        checkCondition(kind)
    }
    private suspend fun checkCondition(kind: CheckKind) {
        if (driver.status.value.state == DriverState.RESTING || navigation.route.value == null) return
        var answer = ask("현재 졸리거나 피곤하신가요?")
        if (ConditionQuestion.FATIGUE.needsNormalConfirmation(answer)) {
            answer = ask("상태를 한 번 더 확인하겠습니다. 현재 졸리거나 피곤하신가요?")
        }
        delay(1)
        safety.onConditionAnswer(com.example.pleosconnect.domain.ConditionQuestion.FATIGUE.interpret(answer), clock.nowMillis())
        refresh()
        if (snapshot.value.restStopNeeded) guidance.request()
    }
    suspend fun modeCommand(text: String) = execute(priority = false, preempt = true) {
        delay(1); normal.onCommand(text, clock.nowMillis())
    }
    suspend fun companionChat() = execute(priority = false, companionWork = true) {
        companion.start()
        if (companion.status.value.needsConditionCheck) companion.requestConditionCheck(CheckKind.RISK_FOLLOW_UP)
    }
    suspend fun restCommand() = execute(priority = false, preempt = true) {
        rest.onForeground(); rest.command(); refresh()
        if (driver.status.value.state == DriverState.RESTING) {
            restSchedulingPaused = true
            scheduler.pauseForRest()
        }
    }
    suspend fun restoreRest() = execute(priority = false) {
        rest.onForeground()
        if (rest.restore()) { restSchedulingPaused = true; scheduler.pauseForRest() }
        refresh()
    }
    suspend fun recoveryCheck() = execute(priority = false) { rest.onForeground(); rest.checkRecovery(); refresh() }
    suspend fun resumeAfterRest() = execute(priority = false) {
        rest.onForeground(); rest.resume(); refresh()
        if (rest.status.value.phase == RestPhase.RESUMED) {
            scheduler.resumeAfterRest(navigation.route.value)
            restSchedulingPaused = false
        }
    }
    suspend fun tick() = execute(priority = scheduler.pending.value.any { it.dueAtMillis <= clock.nowMillis() }) {
        rest.onForeground(); rest.tick(); refresh()
        if (driver.status.value.state == DriverState.RESTING || rest.status.value.phase !in setOf(RestPhase.IDLE, RestPhase.RESUMED)) return@execute
        val checks = scheduler.takeDue()
        if (checks.isNotEmpty()) checkCondition(checks.first().kind)
    }
    /** B calls before leaving foreground. No automatic dialogue resumes on foreground return. */
    suspend fun onBackground() { foreground.value = false; suspendSession() }
    fun onForeground() { if (!closed && !invalidated) foreground.value = true }
    private suspend fun suspendSession() {
        foreground.value = false
        switching.withLock { active?.cancelAndJoin(); active = null }
        companion.onBackground(); rest.cancelVoice(); normal.cancel(); safety.cancel(); guidance.cancel()
        withContext(NonCancellable) { cleanup() }
    }
    suspend fun cancel() { suspendSession() }
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            suspendSession(); sessionJob.cancelAndJoin(); companion.close(); normal.close(); safety.close(); guidance.close()
        }
        // VoiceService is shared and must be closed once by B's app-level owner.
    }
    private suspend fun execute(priority: Boolean, companionWork: Boolean = false, preempt: Boolean = false, block: suspend () -> Unit): Unit = coroutineScope {
        val task = switching.withLock {
            if (!usable()) return@coroutineScope
            if (active?.isCompleted == false && (activeSafety || (!priority && !preempt))) return@coroutineScope
            active?.cancelAndJoin()
            activeSafety = priority
            async(start = CoroutineStart.LAZY) {
                try {
                    if (!usable()) return@async
                    companion.onForeground()
                    if (companionWork) block() else companion.withSafetyPriority { if (usable()) block() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: SecurityException) { mutableFailure.value = FailureReason.PERMISSION_DENIED; invalidated = true }
                catch (_: Exception) { mutableFailure.value = FailureReason.SERVICE_UNAVAILABLE; invalidated = true }
                finally { withContext(NonCancellable) { cleanup() } }
            }.also { active = it; it.start() }
        }
        try { task.await() } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive() }
    }
    private suspend fun cleanup() {
        try { voice.stop(); if (voice.phase.value == VoicePhase.UNAVAILABLE) { invalidated = true; mutableFailure.value = FailureReason.SERVICE_UNAVAILABLE } }
        catch (_: Exception) { invalidated = true; mutableFailure.value = FailureReason.SERVICE_UNAVAILABLE }
    }
    private suspend fun ask(text: String): RecognitionResult {
        val result = try { withTimeoutOrNull(30.seconds) { voice.speak(text) } }
        catch (_: SecurityException) { return RecognitionResult.PermissionDenied }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return RecognitionResult.ServiceUnavailable }
        if (result !is OperationResult.Success) return when ((result as? OperationResult.Failure)?.reason) {
            FailureReason.PERMISSION_DENIED -> RecognitionResult.PermissionDenied
            FailureReason.TIMEOUT, null -> RecognitionResult.Timeout
            else -> RecognitionResult.ServiceUnavailable
        }
        return try { withTimeoutOrNull(10.seconds) { voice.listen(10.seconds) } ?: RecognitionResult.Timeout }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { RecognitionResult.PermissionDenied }
        catch (_: Exception) { RecognitionResult.ServiceUnavailable }
    }
}
