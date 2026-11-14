package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.integration.DestinationRequest
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.ui.DriveMateUiState
import com.example.pleosconnect.data.profile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SimulationAction { START, CHECK, QUIET, MUSIC, NEWS, COMPANION, REST, RECOVERY, RESUME }

/** B owns lifetime/polling only. All driver decisions remain inside DriveMateSession. */
class DriveMateSessionOwner(
    private val scope: CoroutineScope,
    private val factory: (CoroutineScope, DriverPreferences, SimulationScenario) -> DriveMateAppGraph,
    private val tickMillis: Long = 1_000,
    private val catalog: LocalDriverProfileRepository? = null,
) {
    init { require(tickMillis > 0) }
    private val controls = Mutex()
    private val mutableState = MutableStateFlow(DriveMateUiState(localProfiles = catalog != null, profilesReady = catalog == null))
    val state = mutableState.asStateFlow()
    private var profileNumber = 1
    private var graph = create(SimulationScenario.NORMAL)
    private var observation: Job? = null
    private var catalogObservation: Job? = null
    private var ticker: Job? = null
    private var action: Job? = null
    private var closed = false
    private var tripSequence = 0
    private var voiceStarted = false

    init {
        mutableState.value = freshState(SimulationScenario.NORMAL)
        observe()
        catalogObservation = catalog?.let { repository -> scope.launch {
            repository.catalog.collect { value -> mutableState.update { it.copy(profilesReady = value.ready,
                profiles = value.profiles, selectedProfileId = value.selectedId, profileFailure = value.failure) } }
        } }
    }
    private fun create(scenario: SimulationScenario) = factory(scope, catalog?.selectedProfile?.value ?:
        if (catalog == null) DriverPreferences("simulation-driver-$profileNumber", "가상 운전자 $profileNumber")
        else DriverPreferences("unselected", "프로필을 등록하거나 선택해 주세요"), scenario)

    private fun freshState(scenario: SimulationScenario): DriveMateUiState {
        val saved = catalog?.catalog?.value
        return DriveMateUiState(profileName = graph.profile.displayName, scenario = scenario,
            localProfiles = catalog != null, profilesReady = saved?.ready ?: true,
            profiles = saved?.profiles ?: emptyList(), selectedProfileId = saved?.selectedId,
            preferences = catalog?.selectedProfile?.value, profileFailure = saved?.failure,
            profileOperation = state.value.profileOperation, liveNavigation = !graph.simulatedDriving, realVoice = graph.realVoice)
    }

    private fun observe() {
        val current = graph
        observation = scope.launch {
            launch { current.session.driverStatus.collect { value -> mutableState.update { it.copy(driver = value) } } }
            launch { current.session.safetyStatus.collect { value -> mutableState.update { it.copy(safety = value) } } }
            launch { current.session.modeStatus.collect { value -> mutableState.update { it.copy(mode = value) } } }
            launch { current.session.restStatus.collect { value -> mutableState.update { it.copy(rest = value) } } }
            launch { current.session.guidanceStatus.collect { value -> mutableState.update { it.copy(guidance = value) } } }
            launch { current.session.pendingChecks.collect { value -> mutableState.update { it.copy(pendingCount = value.size) } } }
            launch { current.session.failure.collect { value -> mutableState.update { it.copy(failure = value) } } }
            launch { current.navigationPort.route.collect { value -> mutableState.update { it.copy(route = value) } } }
            launch { current.voice.phase.collect { value -> mutableState.update { it.copy(voicePhase = value) } } }
            launch { current.prompt.collect { value -> mutableState.update { it.copy(prompt = value) } } }
            launch { current.voiceFailure.collect { value -> mutableState.update { it.copy(voiceFailure = value) } } }
            launch { current.media.lastCommand.collect { value -> mutableState.update { it.copy(mediaCommand = value) } } }
            launch { current.profiles.selectedProfile.collect { value -> mutableState.update {
                it.copy(preferences = value, profileName = value?.displayName ?: "프로필을 등록하거나 선택해 주세요")
            } } }
        }
    }
    suspend fun foreground() = controls.withLock {
        if (closed || state.value.foreground) return@withLock
        graph.session.onForeground()
        mutableState.update { it.copy(foreground = true) }
        startTicker()
    }
    private fun startTicker() {
        check(ticker == null)
        val current = graph
        if (!current.simulatedDriving && !current.realVoice) return // No mock driver answers on a real route.
        ticker = scope.launch {
            while (isActive) {
                delay(tickMillis)
                if (current.realVoice && !voiceStarted) continue
                current.session.tick()
            }
        }
    }
    private suspend fun pause() {
        mutableState.update { it.copy(foreground = false) }
        ticker?.cancelAndJoin(); ticker = null
        graph.session.onBackground()
        action?.cancelAndJoin(); action = null
    }
    suspend fun background() = controls.withLock { if (!closed) pause() }

    /** Close the old owner before creating a new identity or resetting the immutable voice script. */
    suspend fun reset(scenario: SimulationScenario = state.value.scenario, switchProfile: Boolean = false,
        beforeCreate: (suspend () -> Unit)? = null) = controls.withLock {
        if (closed) return@withLock
        val wasForeground = state.value.foreground
        pause()
        observation?.cancelAndJoin()
        graph.close()
        voiceStarted = false
        beforeCreate?.invoke()
        if (switchProfile) profileNumber = if (profileNumber == 1) 2 else 1
        graph = create(scenario)
        mutableState.value = freshState(scenario)
        // New sessions initially allow work. Close that gate until the explicit foreground transition.
        graph.session.onBackground()
        observe()
        if (wasForeground) {
            graph.session.onForeground()
            mutableState.update { it.copy(foreground = true) }
            startTicker()
        }
    }

    suspend fun loadProfiles() { catalog?.let { changeProfiles(announce = false) { it.load() } } }
    suspend fun registerProfile(draft: ProfileDraft) { catalog?.let { changeProfiles { it.register(draft) } } }
    suspend fun selectProfile(id: String) { catalog?.let { changeProfiles { it.select(id) } } }
    suspend fun saveProfile(id: String, draft: ProfileDraft) { catalog?.let { changeProfiles { it.update(id, draft) } } }
    suspend fun deleteProfile(id: String) { catalog?.let { changeProfiles { it.delete(id) } } }

    /** Identity changes happen only after old A work is fully closed, including pending saves. */
    private suspend fun changeProfiles(announce: Boolean = true, change: suspend () -> OperationResult<*>) = controls.withLock {
        if (closed) return@withLock
        val wasForeground = state.value.foreground
        val scenario = state.value.scenario
        val operation = state.value.profileOperation + 1
        mutableState.update { it.copy(profileWorking = true) }
        pause()
        observation?.cancelAndJoin()
        graph.close()
        val result = change()
        graph = create(scenario)
        mutableState.value = freshState(scenario).copy(profileOperation = operation, profileFailure = (result as? OperationResult.Failure)?.reason,
            notice = if (announce && result is OperationResult.Success) "프로필 처리를 완료했습니다. 주행 체험은 새로 시작해 주세요." else null)
        graph.session.onBackground()
        observe()
        if (wasForeground) {
            graph.session.onForeground()
            mutableState.update { it.copy(foreground = true) }
            startTicker()
        }
    }

    suspend fun submit(command: SimulationAction) = controls.withLock {
        if (closed || !state.value.canAct || action?.isActive == true) return@withLock
        val current = graph
        mutableState.update { it.copy(busy = true, notice = null) }
        action = scope.launch {
            try {
                when (command) {
                    SimulationAction.START -> {
                        if (current.navigationPort.route.value == null) {
                            val request = DestinationRequest("simulation-trip-${++tripSequence}", "가상 목적지")
                            val result = current.navigationPort.setDestination(request,
                                DriverConsent(ConsentScope.DESTINATION, request.requestId, current.clock.nowMillis()))
                            if (result is OperationResult.Failure) {
                                mutableState.update { it.copy(failure = result.reason) }
                                return@launch
                            }
                        }
                        current.session.initialCheck()
                    }
                    SimulationAction.CHECK -> current.session.conditionCheck()
                    SimulationAction.QUIET -> current.session.modeCommand("조용히")
                    SimulationAction.MUSIC -> current.session.modeCommand("발라드 틀어줘")
                    SimulationAction.NEWS -> current.session.modeCommand("뉴스 틀어줘")
                    SimulationAction.COMPANION -> current.session.companionChat()
                    SimulationAction.REST -> {
                        // B1 has no trustworthy vehicle signal, so no UI path can claim safe parking.
                        if (current.parking.current() != com.example.pleosconnect.domain.SafeParking.VERIFIED)
                            mutableState.update { it.copy(notice = "안전 주차 미확인: 휴식 타이머를 시작할 수 없습니다.") }
                        else current.session.restCommand()
                    }
                    SimulationAction.RECOVERY -> current.session.recoveryCheck()
                    SimulationAction.RESUME -> current.session.resumeAfterRest()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failure = FailureReason.SERVICE_UNAVAILABLE) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    /** Actual dialogue only through A's public session. No destination creation or scripted command. */
    suspend fun submitRealVoice(initial: Boolean) = controls.withLock {
        if (closed || !graph.realVoice || !state.value.foreground || state.value.selectedProfileId == null ||
            graph.navigationPort.route.value == null || action?.isActive == true) return@withLock
        val current = graph
        voiceStarted = true
        mutableState.update { it.copy(busy = true, notice = null) }
        action = scope.launch {
            try { if (initial) current.session.initialCheck() else current.session.conditionCheck() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(failure = FailureReason.SERVICE_UNAVAILABLE) } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    suspend fun close() = controls.withLock {
        if (closed) return@withLock
        closed = true
        withContext(NonCancellable) {
            pause()
            observation?.cancelAndJoin()
            catalogObservation?.cancelAndJoin()
            graph.close()
        }
    }
}
