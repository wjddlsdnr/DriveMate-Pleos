package com.example.pleosconnect.integration

import android.content.Context
import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.data.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI setup gate only; never upgrades SafeParking. No assumption from simulator driver replies. */
fun mediaSetupAllowed(state: com.example.pleosconnect.pleos.vehicle.VehicleReadUiState): Boolean {
    val s = state.snapshot
    return state.enabled && s.gear.fresh(state.nowMillis) && s.gear.value == com.example.pleosconnect.pleos.vehicle.ReadGear.PARK &&
        s.speedMetersPerSecond.fresh(state.nowMillis) && s.speedMetersPerSecond.value?.let { it.isFinite() && kotlin.math.abs(it) <= 0.05f } == true
}

data class LiveMediaUiState(val available: Boolean = false, val busy: Boolean = false,
    val apps: List<MediaApp> = emptyList(), val selected: MediaApp? = null, val root: String? = null,
    val entries: List<MediaEntry> = emptyList(), val notice: String = "연결 시 자동 재생하지 않습니다.",
    val failure: FailureReason? = null, val profileId: String? = null, val preferredAppId: String? = null, val preferencesReady: Boolean = false)

/** B5-1 direct user opt-in. Never consumes scripted voice or claims news integration. */
class LiveMediaIntegration(private val gateway: MediaServiceGateway,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val preferences: MediaAppPreferences? = null) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val port = ServiceMediaController(gateway)
    private val mutable = MutableStateFlow(LiveMediaUiState(available = true))
    val state = mutable.asStateFlow()
    private var active = false
    private var work: Job? = null
    private var generation = 0L
    val voiceReady get() = state.value.preferencesReady && state.value.preferredAppId != null && state.value.selected?.id == state.value.preferredAppId && state.value.root != null
    suspend fun bindProfile(profileId: String?) {
        disconnect()
        mutable.value = state.value.copy(profileId = profileId, preferredAppId = null, preferencesReady = false)
        if (profileId != null) {
            try {
                val saved = withContext(Dispatchers.IO) { preferences?.read()?.get(profileId) }
                mutable.value = state.value.copy(preferredAppId = saved, preferencesReady = true)
            } catch (_: Exception) { display(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
        }
    }
    suspend fun retainProfiles(ids: Set<String>) {
        try { withContext(Dispatchers.IO) { preferences?.retainProfiles(ids) } }
        catch (_: Exception) { display(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
    }
    fun saveDefault(id: String?) = launch {
        val profile = state.value.profileId ?: return@launch
        if (id != null && state.value.apps.none { it.id == id }) return@launch
        check(preferences != null)
        withContext(Dispatchers.IO) { preferences.save(profile, id) }
        mutable.value = state.value.copy(preferredAppId = id, notice = "이 프로필의 기본 음악 앱을 저장했습니다. 자동 연결·재생하지 않습니다.")
    }
    suspend fun executeFromVoice(command: MediaCommand): OperationResult<Unit> {
        if (!voiceReady || (!active && command != MediaCommand.Stop)) return OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
        return port.execute(command)
    }
    fun suspendCommands() { active = false; work?.cancel() }
    fun foreground() { active = true }
    fun disconnect() {
        generation++
        work?.cancel(); work = null; port.disconnect()
        mutable.value = LiveMediaUiState(available = true, apps = state.value.apps,
            profileId = state.value.profileId, preferredAppId = state.value.preferredAppId, preferencesReady = state.value.preferencesReady,
            notice = "연결 해제됨. 외부 앱의 재생은 자동 중단하지 않으므로 필요하면 해당 앱에서 확인하세요.")
    }
    fun background() { active = false; disconnect() }
    fun refresh() = launch { disconnectConnection(); mutable.value = state.value.copy(apps = gateway.apps(), notice = "사용할 앱을 선택하세요.") }
    private fun disconnectConnection() { port.disconnect(); mutable.value = state.value.copy(selected = null, root = null, entries = emptyList()) }
    fun select(id: String) = launch {
        val app = state.value.apps.firstOrNull { it.id == id } ?: return@launch
        disconnectConnection()
        val connection = port.connect(app)
        mutable.value = state.value.copy(selected = app, root = connection.root, notice = "연결됨 · 아직 재생을 요청하지 않았습니다.")
        load(connection.root)
    }
    private suspend fun load(parent: String) { mutable.value = state.value.copy(entries = gateway.children(parent), notice = "서비스 목록 · 최대 30개") }
    fun browse(id: String) = launch {
        val root = state.value.root
        if (id != root && state.value.entries.none { it.id == id && it.browsable }) return@launch
        load(id)
    }
    fun play(id: String) = launch {
        val entry = state.value.entries.firstOrNull { it.id == id && it.playable } ?: return@launch
        display(port.play(entry))
    }
    fun command(command: MediaCommand) = launch { display(port.execute(command)) }
    private fun display(result: OperationResult<Unit>) {
        mutable.value = when (result) {
            is OperationResult.Success -> state.value.copy(notice = "서비스의 재생 상태 변경을 확인했습니다. 소리 출력은 직접 확인하세요.", failure = null)
            is OperationResult.Failure -> state.value.copy(failure = result.reason,
                notice = "요청을 확인하지 못했습니다. 재생 여부는 선택한 앱에서 확인하세요.")
        }
    }
    private fun launch(block: suspend () -> Unit) {
        if (!active || work?.isActive == true) return
        mutable.value = state.value.copy(busy = true, failure = null)
        val epoch = generation
        work = scope.launch {
            try { withTimeout(12_000) { block() } }
            catch (_: TimeoutCancellationException) { display(OperationResult.Failure(FailureReason.TIMEOUT)) }
            catch (e: CancellationException) { throw e }
            catch (_: SecurityException) { display(OperationResult.Failure(FailureReason.PERMISSION_DENIED)) }
            catch (_: Exception) { display(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)) }
            finally { if (epoch == generation) mutable.value = state.value.copy(busy = false) }
        }
    }
    fun close() { background(); scope.cancel() }
    companion object { fun create(context: Context) = LiveMediaIntegration(AndroidMediaServiceGateway(context),
        preferences = MediaAppPreferences(java.io.File(context.noBackupFilesDir, "profiles/media-apps.bin"))) }
}
