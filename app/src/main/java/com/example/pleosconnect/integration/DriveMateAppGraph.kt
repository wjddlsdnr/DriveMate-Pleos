package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.integration.DriverProfileRepository
import com.example.pleosconnect.contract.integration.NavigationController
import com.example.pleosconnect.contract.integration.RestStopFinder
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.domain.session.DriveMateSession
import com.example.pleosconnect.mock.integration.*
import com.example.pleosconnect.mock.voice.MockVoiceBackend
import com.example.pleosconnect.voice.SerializedVoiceService
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Every scenario uses preset responses, including consent. No real driver answer is inferred. */
enum class SimulationScenario(val label: String, val description: String) {
    NORMAL("정상 응답", "괜찮아 → 조용히. 이후 상태 확인에도 정상 응답을 사용합니다."),
    FATIGUE("피로 지속", "조금 피곤해 → 가상 환기 동의 → 계속 졸려 → 가상 경유지 동의"),
    SEVERE("심한 졸음", "너무 졸려 → 가상 경유지 동의"),
    TIMEOUT("음성 시간 초과", "시간 초과를 주입합니다. 졸음으로 확정하지 않습니다."),
    PERMISSION_DENIED("마이크 권한 거부", "가상 권한 거부를 주입합니다. 실제 권한은 요청하지 않습니다."),
    NO_RESPONSE("무응답", "응답 없음만 주입합니다. 실제 졸음 판정을 뜻하지 않습니다.");

    fun answers(): List<RecognitionResult> = when (this) {
        NORMAL -> listOf("괜찮아", "조용히").map { RecognitionResult.Text(it) } + List(20) { RecognitionResult.Text("괜찮아") }
        FATIGUE -> listOf("조금 피곤해", "네", "계속 졸려", "네").map { RecognitionResult.Text(it) }
        SEVERE -> listOf("너무 졸려", "네").map { RecognitionResult.Text(it) }
        TIMEOUT -> List(10) { RecognitionResult.Timeout }
        PERMISSION_DENIED -> List(10) { RecognitionResult.PermissionDenied }
        NO_RESPONSE -> emptyList()
    }
}

/** B1 composition root. All B ports and speech are explicitly simulated. */
class DriveMateAppGraph(
    parentScope: CoroutineScope,
    val profile: DriverPreferences,
    val scenario: SimulationScenario,
    val clock: MonotonicClock,
    backend: VoiceBackend = MockVoiceBackend(scenario.answers(), playbackMillis = 600),
    val profiles: DriverProfileRepository = FakeDriverProfileRepository(profile),
    navigationOverride: NavigationController? = null,
    finderOverride: RestStopFinder? = null,
    parkingOverride: com.example.pleosconnect.domain.SafeParkingCheck? = null,
    val realVoice: Boolean = false,
    mediaOverride: com.example.pleosconnect.contract.integration.MediaController? = null,
) {
    init {
        require(!realVoice || navigationOverride != null && mediaOverride != null)
        require(!realVoice || backend !is MockVoiceBackend) { "Real voice cannot use the default scripted backend" }
        require(realVoice || mediaOverride == null) { "Scripted speech must never control real media" }
        require((navigationOverride == null) == (finderOverride == null)) {
            "Navigation and rest-stop finder must be supplied together; live navigation cannot use a simulated finder"
        }
    }
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val mutablePrompt = MutableStateFlow("아직 시작하지 않았습니다.")
    val prompt = mutablePrompt.asStateFlow()
    private val mutableVoiceFailure = MutableStateFlow<FailureReason?>(null)
    val voiceFailure = mutableVoiceFailure.asStateFlow()
    val navigation = FakeNavigationController(clock)
    val finder = FakeRestStopFinder(navigation)
    val navigationPort = navigationOverride ?: navigation
    val finderPort = finderOverride ?: finder
    val simulatedDriving = navigationOverride == null
    val vehicle = FakeVehicleController(clock)
    val media = FakeMediaController()
    val mediaPort = mediaOverride ?: media
    private val vehiclePort = if (!realVoice) vehicle else object : com.example.pleosconnect.contract.integration.VehicleController {
        override val manualOverrides = kotlinx.coroutines.flow.emptyFlow<com.example.pleosconnect.contract.integration.VehicleAdjustment>()
        override suspend fun propose(assist: com.example.pleosconnect.contract.integration.VehicleAssist) = OperationResult.Failure(FailureReason.UNSUPPORTED)
        override suspend fun apply(proposal: com.example.pleosconnect.contract.integration.VehicleAssistProposal, consent: DriverConsent) = OperationResult.Failure(FailureReason.UNSUPPORTED)
        override suspend fun restore(adjustment: com.example.pleosconnect.contract.integration.VehicleAdjustment, consent: DriverConsent) = OperationResult.Failure(FailureReason.UNSUPPORTED)
    }
    val parking = FakeSafeParkingCheck()
    val parkingPort = parkingOverride ?: parking
    val store = InMemoryRestSessionStore()
    // Observation only. A's backend and orchestration stay unchanged.
    private val sessionBackend = if (simulatedDriving || realVoice) backend else object : VoiceBackend {
        override suspend fun speak(text: String): OperationResult<Unit> = OperationResult.Failure(FailureReason.UNSUPPORTED)
        override suspend fun listen(): RecognitionResult = RecognitionResult.ServiceUnavailable
        override suspend fun stop() = Unit
        override suspend fun close() = Unit
    }
    val voice = SerializedVoiceService(object : VoiceBackend by sessionBackend {
        override suspend fun speak(text: String): OperationResult<Unit> {
            mutablePrompt.value = text
            return sessionBackend.speak(text).also { result ->
                if (result is OperationResult.Failure) mutableVoiceFailure.value = result.reason
            }
        }
        override suspend fun listen(): RecognitionResult = sessionBackend.listen().also { result ->
            // Display service errors only; no driver-state interpretation here.
            mutableVoiceFailure.value = when (result) {
                RecognitionResult.PermissionDenied -> FailureReason.PERMISSION_DENIED
                RecognitionResult.Timeout -> FailureReason.TIMEOUT
                RecognitionResult.ServiceUnavailable, RecognitionResult.RecognitionFailure -> FailureReason.SERVICE_UNAVAILABLE
                else -> null
            }
        }
    })
    val session = DriveMateSession(scope, voice, navigationPort, finderPort, vehiclePort, mediaPort, profiles, parkingPort, clock, store)
    private var closed = false
    suspend fun close() {
        if (closed) return
        closed = true
        withContext(NonCancellable) {
            try { session.close() }
            finally {
                try { voice.close() }
                finally { store.save(null); job.cancelAndJoin() }
            }
        }
    }
}
