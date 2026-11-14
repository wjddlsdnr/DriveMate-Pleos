package com.example.pleosconnect.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.integration.*
import com.example.pleosconnect.data.profile.*
import com.example.pleosconnect.mock.integration.FakeDriverProfileRepository
import com.example.pleosconnect.pleos.navigation.NavigationPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** The cleanup scope outlives viewModelScope cancellation, then terminates after awaited close. */
class DriveMateViewModel(
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val catalog: LocalDriverProfileRepository? = null,
    private val liveNavigation: LiveNavigationIntegration? = null,
    private val liveVehicle: LiveVehicleReadIntegration? = null,
    private val vehicleControl: LiveVehicleControlIntegration? = null,
    private val liveMedia: LiveMediaIntegration? = null,
    private val localAudio: com.example.pleosconnect.data.media.LocalAudioPlayer? = null,
    private val liveVoice: LiveVoiceIntegration? = null,
    nearbyTransport: com.example.pleosconnect.pleos.navigation.NearbyDiscoveryTransport? = null,
    factory: (CoroutineScope, com.example.pleosconnect.contract.model.DriverPreferences, SimulationScenario) -> DriveMateAppGraph =
        { scope, profile, scenario ->
            liveNavigation?.bindProfile(profile.profileId.takeUnless { it == "unselected" })
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { SystemClock.elapsedRealtime() },
                backend = liveVoice?.takeIf { it.enabled.value && liveNavigation?.enabled == true }?.backend()
                    ?: com.example.pleosconnect.mock.voice.MockVoiceBackend(scenario.answers(), playbackMillis = 600),
                profiles = catalog ?: FakeDriverProfileRepository(profile),
                navigationOverride = liveNavigation?.takeIf { it.enabled }?.let { if (liveVoice?.enabled?.value == true) it.voiceNavigation else it.controller },
                finderOverride = liveNavigation?.takeIf { it.enabled }?.let { if (liveVoice?.enabled?.value == true) it.voiceFinder else it.finder },
                parkingOverride = liveVehicle?.takeIf { liveNavigation?.enabled == true }?.parking,
                realVoice = liveVoice?.enabled?.value == true && liveNavigation?.enabled == true,
                mediaOverride = liveVoice?.takeIf { it.enabled.value && liveNavigation?.enabled == true }?.media)
        },
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val owner = DriveMateSessionOwner(scope, factory, catalog = catalog)
    val state = owner.state
    private val nearby = nearbyTransport?.let { transport ->
        com.example.pleosconnect.pleos.navigation.NearbyDiscoveryController(scope,
            MonotonicClock { SystemClock.elapsedRealtime() },
            { liveNavigation?.takeIf { it.enabled }?.controller?.observedVoiceRoute?.searchContext() },
            { state.value.foreground && state.value.selectedProfileId != null && liveNavigation?.enabled == true &&
                liveVoice?.enabled?.value != true && mediaSetupAllowed(vehicleState.value) }, transport)
    }
    val nearbyAvailable = nearby != null
    val nearbyState = nearby?.state ?: MutableStateFlow(com.example.pleosconnect.pleos.navigation.NearbyDiscoveryState())
    fun prepareNearby(token: String) { nearby?.prepare(token) }
    fun confirmNearby() { nearby?.confirmFromUi() }
    fun cancelNearby() { nearby?.cancel() }
    val liveVoiceAvailable = liveVoice != null
    val liveVoiceEnabled = liveVoice?.enabled ?: MutableStateFlow(false)
    val voiceConnectionNotice = MutableStateFlow<String?>(null)
    val mediaState = liveMedia?.state ?: MutableStateFlow(LiveMediaUiState())
    val localAudioState = localAudio?.state ?: MutableStateFlow(com.example.pleosconnect.data.media.LocalAudioState())
    fun canSelectLocalAudio() = !liveVoiceEnabled.value && state.value.foreground && mediaSetupAllowed(vehicleState.value)
    fun selectLocalAudio(uri: android.net.Uri) { if (!liveVoiceEnabled.value) localAudio?.select(uri) }
    fun playLocalAudio() { if (canSelectLocalAudio() && !liveVoiceEnabled.value) { liveMedia?.disconnect(); localAudio?.play() } }
    fun stopLocalAudio() { localAudio?.stop() }
    fun localAudioPickerUnavailable() { localAudio?.pickerUnavailable() }
    val mediaActions = com.example.pleosconnect.ui.media.MediaActions(
        refresh = { mediaSetup { liveMedia?.refresh() } }, select = { id -> mediaSetup { liveMedia?.select(id) } },
        browse = { id -> mediaSetup { liveMedia?.browse(id) } }, play = { id -> mediaSetup { liveMedia?.play(id) } },
        command = { command ->
            if (command == com.example.pleosconnect.contract.integration.MediaCommand.Stop && state.value.foreground) liveMedia?.command(command)
            else mediaSetup { liveMedia?.command(command) }
        }, disconnect = { if (!liveVoiceEnabled.value) liveMedia?.disconnect() },
        saveDefault = { id -> mediaSetup { liveMedia?.saveDefault(id) } })
    private fun mediaSetup(block: () -> Unit) {
        if (!liveVoiceEnabled.value && state.value.foreground && mediaSetupAllowed(vehicleState.value)) { localAudio?.stop(); block() }
    }
    val navigationState = liveNavigation?.state ?: MutableStateFlow(LiveNavigationUiState())
    val vehicleState = liveVehicle?.state ?: MutableStateFlow(com.example.pleosconnect.pleos.vehicle.VehicleReadUiState())
    val vehicleControlState = vehicleControl?.state ?: MutableStateFlow(com.example.pleosconnect.pleos.vehicle.VehicleControlUiState())
    private sealed interface Event {
        data class Visibility(val foreground: Boolean) : Event
        data class Action(val value: SimulationAction) : Event
        data class Reset(val scenario: SimulationScenario, val switchProfile: Boolean) : Event
        data class RegisterProfile(val draft: ProfileDraft) : Event
        data class SelectProfile(val id: String) : Event
        data class SaveProfile(val id: String, val draft: ProfileDraft) : Event
        data class DeleteProfile(val id: String) : Event
        data object ReloadProfiles : Event
        data class NavigationMode(val enabled: Boolean) : Event
        data class LiveDestination(val place: NavigationPlace) : Event
        data object ResumeNavigation : Event
        data object LoadNavigationDestinations : Event
        data class VehicleReadMode(val enabled: Boolean) : Event
        data object PrepareVehicleControl : Event
        data class PrepareSetting(val key: com.example.pleosconnect.pleos.vehicle.VehicleSettingKey, val target: Double) : Event
        data class RestoreSetting(val id: String) : Event
        data class ConfirmSetting(val id: String) : Event
        data object StopVehicleControl : Event
        data class VoiceMode(val enabled: Boolean) : Event
        data class VoiceCheck(val initial: Boolean) : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            owner.loadProfiles()
            liveMedia?.bindProfile(state.value.selectedProfileId)
            for (event in events) when (event) {
                is Event.Visibility -> if (event.foreground) {
                    owner.foreground(); liveNavigation?.foreground(); liveVehicle?.foreground(); vehicleControl?.foreground(); liveMedia?.foreground(); localAudio?.foreground()
                } else {
                    val wasRealVoice = state.value.realVoice
                    vehicleControl?.background(); owner.background()
                    if (wasRealVoice) owner.reset()
                    liveMedia?.background(); liveNavigation?.background(); liveVehicle?.background()
                }
                is Event.Action -> owner.submit(event.value)
                is Event.Reset -> { liveVoice?.setEnabled(false); liveMedia?.disconnect(); localAudio?.clear(); owner.reset(event.scenario, event.switchProfile) }
                is Event.RegisterProfile -> changeProfile { owner.registerProfile(event.draft) }
                is Event.SelectProfile -> changeProfile { owner.selectProfile(event.id) }
                is Event.SaveProfile -> changeProfile { owner.saveProfile(event.id, event.draft) }
                is Event.DeleteProfile -> {
                    changeProfile { owner.deleteProfile(event.id) }
                    if (state.value.profilesReady && state.value.profileFailure == null) liveMedia?.retainProfiles(state.value.profiles.map { it.profileId }.toSet())
                }
                Event.ReloadProfiles -> changeProfile { owner.loadProfiles() }
                is Event.NavigationMode -> owner.reset(beforeCreate = { liveVoice?.setEnabled(false); liveNavigation?.setEnabled(event.enabled) })
                is Event.LiveDestination -> liveNavigation?.destinationConfirmedFromUi(event.place)
                Event.ResumeNavigation -> liveNavigation?.resumeConfirmedFromUi()
                Event.LoadNavigationDestinations -> liveNavigation?.loadSavedDestinations()
                is Event.VehicleReadMode -> { if (!event.enabled) vehicleControl?.stop(); liveVehicle?.setEnabled(event.enabled) }
                Event.PrepareVehicleControl -> if (liveVehicle?.state?.value?.enabled == true) vehicleControl?.prepareConnection(state.value.selectedProfileId)
                is Event.PrepareSetting -> vehicleControl?.prepare(event.key, event.target)
                is Event.RestoreSetting -> vehicleControl?.restore(event.id)
                is Event.ConfirmSetting -> vehicleControl?.confirm(event.id)
                Event.StopVehicleControl -> vehicleControl?.stop()
                is Event.VoiceMode -> {
                    if (!event.enabled || canEnableVoice()) {
                        voiceConnectionNotice.value = "음성 연결을 전환하고 있습니다…"
                        try {
                            localAudio?.stop()
                            owner.reset(beforeCreate = { liveVoice?.setEnabled(event.enabled) })
                            voiceConnectionNotice.value = if (event.enabled) "연결 준비 완료. 이제 ‘음성 출발 확인’을 눌러야 질문이 시작됩니다." else "실제 음성 연결을 껐습니다."
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { liveVoice?.setEnabled(false); voiceConnectionNotice.value = "음성 연결 준비에 실패했습니다. 연결을 다시 켜 주세요." }
                    } else if (!liveVoiceEnabled.value) voiceConnectionNotice.value = "연결 조건이 바뀌어 켜지 못했습니다. 아래 ‘연결 준비’ 항목을 확인해 주세요."
                }
                is Event.VoiceCheck -> if (liveVoiceEnabled.value) owner.submitRealVoice(event.initial)
            }
        } finally {
            withContext(NonCancellable) { try { owner.close() } finally {
                try { liveNavigation?.close() } finally { try { liveVehicle?.close() } finally { try { vehicleControl?.close() } finally { try { liveMedia?.close() } finally { localAudio?.close() } } } }
            } }
            scope.cancel()
        }
    }
    fun setForeground(value: Boolean) {
        if (!value) {
            nearby?.cancel()
            if (liveVoiceEnabled.value) voiceConnectionNotice.value = "앱이 화면을 벗어나 실제 음성 연결을 껐습니다. 돌아온 뒤 다시 켜 주세요."
            liveVoice?.setEnabled(false); vehicleControl?.suspendCommands(); liveMedia?.suspendCommands(); localAudio?.background()
        }
        events.trySend(Event.Visibility(value))
    }
    private fun canEnableVoice() = !liveVoiceEnabled.value && voiceSetupRequirements(state.value.foreground,
        liveNavigation?.enabled == true, state.value.selectedProfileId, mediaState.value, localAudioState.value, vehicleState.value).isEmpty()
    fun setLiveVoice(value: Boolean) { nearby?.cancel(); if (!value) liveVoice?.setEnabled(false); events.trySend(Event.VoiceMode(value)) }
    fun voiceCheck(initial: Boolean) { events.trySend(Event.VoiceCheck(initial)) }
    fun act(value: SimulationAction) { events.trySend(Event.Action(value)) }
    private suspend fun changeProfile(block: suspend () -> Unit) {
        nearby?.cancel()
        vehicleControl?.stop()
        liveVoice?.setEnabled(false)
        if (liveMedia?.voiceReady == true) liveMedia.executeFromVoice(com.example.pleosconnect.contract.integration.MediaCommand.Stop)
        liveMedia?.disconnect()
        localAudio?.clear()
        liveNavigation?.beforeProfileChange()
        block()
        liveMedia?.bindProfile(state.value.selectedProfileId)
        liveNavigation?.afterProfileChange()
    }
    fun setLiveNavigation(enabled: Boolean) { nearby?.cancel(); events.trySend(Event.NavigationMode(enabled)) }
    fun setVehicleRead(enabled: Boolean) { nearby?.cancel(); events.trySend(Event.VehicleReadMode(enabled)) }
    fun prepareVehicleControl() { events.trySend(Event.PrepareVehicleControl) }
    fun prepareVehicleSetting(key: com.example.pleosconnect.pleos.vehicle.VehicleSettingKey, target: Double) { events.trySend(Event.PrepareSetting(key, target)) }
    fun restoreVehicleSetting(id: String) { events.trySend(Event.RestoreSetting(id)) }
    fun confirmVehicleSetting(id: String) { events.trySend(Event.ConfirmSetting(id)) }
    fun cancelVehicleSetting() { vehicleControl?.cancelReview() }
    fun stopVehicleControl() { vehicleControl?.cancelCommands(); events.trySend(Event.StopVehicleControl) }
    fun setLiveDestination(place: NavigationPlace) { events.trySend(Event.LiveDestination(place)) }
    fun resumeLiveNavigation() { events.trySend(Event.ResumeNavigation) }
    fun loadNavigationDestinations() { events.trySend(Event.LoadNavigationDestinations) }
    fun registerProfile(draft: ProfileDraft) { events.trySend(Event.RegisterProfile(draft)) }
    fun selectProfile(id: String) { events.trySend(Event.SelectProfile(id)) }
    fun saveProfile(id: String, draft: ProfileDraft) { events.trySend(Event.SaveProfile(id, draft)) }
    fun deleteProfile(id: String) { events.trySend(Event.DeleteProfile(id)) }
    fun reloadProfiles() { events.trySend(Event.ReloadProfiles) }
    fun reset(scenario: SimulationScenario = state.value.scenario, switchProfile: Boolean = false) {
        nearby?.cancel()
        events.trySend(Event.Reset(scenario, switchProfile))
    }
    /** Closing discards pending commands; cleanup is never launched into a cancelled viewModelScope. */
    fun shutdown() { nearby?.cancel(); liveVoice?.setEnabled(false); vehicleControl?.suspendCommands(); liveMedia?.background(); localAudio?.background(); events.cancel(); worker.cancel() }
    suspend fun closeAndJoin() { shutdown(); worker.join() }
    override fun onCleared() { shutdown() }
}
