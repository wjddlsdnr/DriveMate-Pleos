package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class NormalDrivingCoordinatorTest {
    private class Fixture(val scope: TestScope) {
        var offset = 1L
        val clock = MonotonicClock { scope.testScheduler.currentTime + offset }
        val safety = MutableStateFlow(SafetyResponse(DriverStatus(state = DriverState.NORMAL,
            report = ConditionReport.NORMAL, followUpRequired = false)))
        val profile = MutableStateFlow<DriverPreferences?>(DriverPreferences("driver-A", "테스트"))
        val saved = mutableListOf<DriverPreferences>()
        var saveResult: OperationResult<Unit> = OperationResult.Success(Unit)
        var onSave: () -> Unit = {}
        val repository = object : DriverProfileRepository {
            override val selectedProfile = profile
            override suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit> {
                onSave()
                if (profile.value?.profileId != preferences.profileId) return OperationResult.Failure(FailureReason.STALE_REQUEST)
                saved += preferences
                if (saveResult is OperationResult.Success) profile.value = preferences
                return saveResult
            }
        }
        val commands = mutableListOf<MediaCommand>()
        var stopFailure: FailureReason? = null
        var playFailure: FailureReason? = null
        var playGate: CompletableDeferred<Unit>? = null
        var onMedia: (MediaCommand) -> Unit = {}
        val media = object : MediaController {
            override suspend fun execute(command: MediaCommand): OperationResult<Unit> {
                commands += command; onMedia(command)
                if (command == MediaCommand.Stop) return stopFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
                playGate?.await()
                return playFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
            }
        }
        val prompts = mutableListOf<String>()
        var answer: RecognitionResult = RecognitionResult.Text("조용히")
        var listenGate: CompletableDeferred<RecognitionResult>? = null
        var onListen: () -> Unit = {}
        var speechFailure: FailureReason? = null
        var listens = 0
        var voiceStops = 0
        val voice = object : VoiceService {
            override val phase = MutableStateFlow(VoicePhase.IDLE)
            override suspend fun speak(text: String): OperationResult<Unit> {
                prompts += text
                return speechFailure?.let { OperationResult.Failure(it) } ?: OperationResult.Success(Unit)
            }
            override suspend fun listen(timeout: Duration): RecognitionResult {
                listens++; onListen(); return listenGate?.await() ?: answer
            }
            override suspend fun stop() { voiceStops++ }
            override suspend fun close() = Unit
        }
        val coordinator = NormalDrivingCoordinator(safety, repository, media, voice, clock)
        val status get() = coordinator.status.value
        suspend fun command(text: String) { offset++; coordinator.onCommand(text, clock.nowMillis()) }
        fun danger() { safety.value = SafetyResponse(DriverStatus(state = DriverState.HIGH_RISK, report = ConditionReport.SEVERE), restStopNeeded = true) }
    }

    @Test fun explicitMusicQueryOverridesStoredPreference() = runTest {
        val f = Fixture(this); f.profile.value = f.profile.value!!.copy(genres = listOf("재즈"))
        f.command("발라드 틀어줘")
        assertEquals(listOf(MediaCommand.Stop, MediaCommand.PlayMusic("발라드")), f.commands)
        assertEquals(listOf("재즈"), f.saved.single().genres)
        assertEquals(DrivingMode.MUSIC, f.status.mode); assertTrue(f.status.preferencesSaved)
    }
    @Test fun previousModeIsProposedBeforePlaybackAndRequiresResponse() = runTest {
        val f = Fixture(this); f.profile.value = f.profile.value!!.copy(previousMode = DrivingMode.MUSIC, genres = listOf("발라드"))
        f.listenGate = CompletableDeferred()
        val task = launch { f.coordinator.offerMode() }; runCurrent()
        assertTrue(f.prompts.single().contains("발라드")); assertTrue(f.commands.isEmpty())
        f.listenGate!!.complete(RecognitionResult.Text("네")); task.join()
        assertEquals(MediaCommand.PlayMusic("발라드"), f.commands.last())
    }
    @Test fun explicitQuietReplyOverridesPreviousMusicProposal() = runTest {
        val f = Fixture(this); f.profile.value = f.profile.value!!.copy(previousMode = DrivingMode.MUSIC, genres = listOf("발라드"))
        f.answer = RecognitionResult.Text("아니, 오늘은 조용하게 운전할래"); f.coordinator.offerMode()
        assertEquals(listOf(MediaCommand.Stop), f.commands)
        assertEquals(DrivingMode.QUIET, f.saved.single().previousMode)
        assertEquals(listOf("발라드"), f.saved.single().genres)
    }
    @Test fun musicToNewsStopsOldMediaAndSavesExplicitSelection() = runTest {
        val f = Fixture(this); f.command("발라드 틀어줘"); f.command("음악 말고 뉴스")
        assertEquals(listOf(MediaCommand.Stop, MediaCommand.PlayNewsAudio), f.commands.takeLast(2))
        assertEquals(DrivingMode.NEWS_AUDIO, f.status.mode)
        assertTrue("뉴스" in f.saved.last().contentPreferences)
    }
    @Test fun stopMeansQuietAndNextOnlyWorksInMusicMode() = runTest {
        val f = Fixture(this); f.command("다음 곡")
        assertTrue(f.commands.isEmpty()); assertEquals(NormalModePhase.CLARIFY, f.status.phase)
        f.command("발라드 틀어줘"); val saves = f.saved.size; f.command("다음 곡")
        assertEquals(MediaCommand.Next, f.commands.last()); assertEquals(saves, f.saved.size)
        f.command("노래 꺼줘"); assertEquals(DrivingMode.QUIET, f.status.mode)
        assertEquals(MediaCommand.Stop, f.commands.last())
    }
    @Test fun companionSelectionDoesNotStartActualConversationOrLlm() = runTest {
        val f = Fixture(this); f.command("말동무 해줘")
        assertEquals(DrivingMode.COMPANION, f.status.mode)
        assertEquals(listOf(MediaCommand.Stop), f.commands)
        assertTrue(f.prompts.isEmpty()); assertEquals(0, f.listens)
    }
    @Test fun firstMusicRequestAsksTasteAndSavesGenreOnlyFromExplicitAnswer() = runTest {
        val f = Fixture(this); f.answer = RecognitionResult.Text("발라드 좋아해"); f.command("음악 틀어줘")
        assertEquals(1, f.listens); assertTrue(f.prompts.first().contains("어떤 음악"))
        assertEquals(MediaCommand.PlayMusic("발라드"), f.commands.last())
        assertEquals(listOf("발라드"), f.saved.single().genres)
    }
    @Test fun artistAndPlaylistPreferenceHaveExplicitStructure() = runTest {
        listOf("가수 아이유 좋아해" to "아이유 노래", "플레이리스트 출근 좋아해" to "출근 플레이리스트").forEach { (answer, query) ->
            val f = Fixture(this); f.answer = RecognitionResult.Text(answer); f.command("음악 틀어줘")
            assertEquals(MediaCommand.PlayMusic(query), f.commands.last())
            assertTrue(f.saved.single().artists.isNotEmpty() || f.saved.single().playlists.isNotEmpty())
        }
    }
    @Test fun explicitModeSwitchDuringTasteQuestionOverridesMusic() = runTest {
        val f = Fixture(this); f.answer = RecognitionResult.Text("오늘은 음악 말고 뉴스 들을래"); f.command("음악 틀어줘")
        assertEquals(MediaCommand.PlayNewsAudio, f.commands.last())
        assertTrue(f.saved.single().genres.isEmpty())
    }
    @Test fun ambiguousOrNegatedPreferenceIsNotSavedAndDoesNotLoop() = runTest {
        listOf("발라드 말고 재즈", "가수 아이유 안 좋아해", "음악 틀어줘", "모르겠어").forEach {
            val f = Fixture(this); f.answer = RecognitionResult.Text(it); f.command("음악 틀어줘")
            assertEquals(1, f.listens); assertEquals(NormalModePhase.CLARIFY, f.status.phase)
            assertTrue(f.commands.isEmpty()); assertTrue(f.saved.isEmpty())
        }
    }
    @Test fun savedArtistsAndPlaylistCanSupplyGenericMusicQuery() = runTest {
        val f = Fixture(this); f.profile.value = f.profile.value!!.copy(artists = listOf("아이유")); f.command("음악 틀어줘")
        assertEquals(MediaCommand.PlayMusic("아이유 노래"), f.commands.last()); assertEquals(0, f.listens)
        val p = Fixture(this); p.profile.value = p.profile.value!!.copy(playlists = listOf("출근")); p.command("음악 틀어줘")
        assertEquals(MediaCommand.PlayMusic("출근 플레이리스트"), p.commands.last())
    }
    @Test fun unrelatedProfileFieldsArePreservedOnModeSave() = runTest {
        val f = Fixture(this)
        val original = f.profile.value!!.copy(genres = listOf("재즈"), artists = listOf("가수"), playlists = listOf("출근"),
            contentPreferences = listOf("기존 콘텐츠"), voiceGuidancePreference = "짧게")
        f.profile.value = original; f.command("오늘은 조용히")
        assertEquals(original.copy(previousMode = DrivingMode.QUIET), f.saved.single())
    }
    @Test fun guestCanChooseTemporaryModeWithoutPersistence() = runTest {
        val f = Fixture(this); f.profile.value = null; f.command("발라드 틀어줘")
        assertEquals(DrivingMode.MUSIC, f.status.mode); assertFalse(f.status.preferencesSaved); assertTrue(f.saved.isEmpty())
    }
    @Test fun allNonNormalAndUnconfirmedStatesBlockContentRequests() = runTest {
        DriverState.entries.filter { it != DriverState.NORMAL }.forEach {
            val f = Fixture(this); f.safety.value = SafetyResponse(DriverStatus(state = it)); f.command("발라드 틀어줘")
            assertEquals(listOf(MediaCommand.Stop), f.commands); assertTrue(f.saved.isEmpty()); assertTrue(f.prompts.isEmpty())
        }
        val f = Fixture(this); f.safety.value = f.safety.value.copy(driver = f.safety.value.driver.copy(report = ConditionReport.UNCONFIRMED))
        f.command("뉴스 틀어줘"); assertEquals(NormalModePhase.BLOCKED, f.status.phase)
        assertEquals(listOf(MediaCommand.Stop), f.commands)
    }
    @Test fun riskObserverStopsAlreadyPlayingMusicAndNeverAutoResumes() = runTest {
        val f = Fixture(this); backgroundScope.launch { f.coordinator.observeChanges() }; runCurrent()
        f.command("발라드 틀어줘"); val normal = f.safety.value; val saves = f.saved.size
        f.danger(); runCurrent(); assertEquals(MediaCommand.Stop, f.commands.last())
        assertEquals(NormalModePhase.BLOCKED, f.status.phase); assertNull(f.status.mode)
        val count = f.commands.size; f.safety.value = normal; runCurrent()
        assertEquals(count, f.commands.size); assertEquals(saves, f.saved.size)
    }
    @Test fun riskDuringTasteListeningCancelsAndDiscardsLateAnswer() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val task = launch { f.command("음악 틀어줘") }; runCurrent(); f.danger(); runCurrent(); task.join()
        f.listenGate!!.complete(RecognitionResult.Text("발라드 좋아해")); runCurrent()
        assertEquals(NormalModePhase.BLOCKED, f.status.phase)
        assertTrue(f.saved.isEmpty()); assertTrue(f.commands.none { it is MediaCommand.PlayMusic })
    }
    @Test fun profileSwitchInvalidatesOldPreferenceAndStopsOldContent() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val task = launch { f.command("음악 틀어줘") }; runCurrent()
        f.profile.value = DriverPreferences("driver-B", "다른 테스트"); runCurrent(); task.join()
        f.listenGate!!.complete(RecognitionResult.Text("발라드 좋아해")); runCurrent()
        assertEquals(NormalModePhase.PROFILE_CHANGED, f.status.phase); assertTrue(f.saved.isEmpty())
        assertTrue(f.commands.none { it is MediaCommand.PlayMusic })
    }
    @Test fun safetyChangeImmediatelyAfterStopPreventsNewPlayback() = runTest {
        val f = Fixture(this); f.onMedia = { if (it == MediaCommand.Stop) f.danger() }; f.command("뉴스 틀어줘")
        assertTrue(f.commands.all { it == MediaCommand.Stop }); assertTrue(f.saved.isEmpty())
        assertEquals(NormalModePhase.BLOCKED, f.status.phase)
    }
    @Test fun failedStopPreventsOverlappingPlaybackAndSave() = runTest {
        val f = Fixture(this); f.stopFailure = FailureReason.SERVICE_UNAVAILABLE; f.command("발라드 틀어줘")
        assertEquals(listOf(MediaCommand.Stop), f.commands); assertTrue(f.saved.isEmpty())
        assertEquals(NormalModePhase.FAILED, f.status.phase); assertNull(f.status.mode)
    }
    @Test fun failedPlaybackDoesNotSaveSelectedMode() = runTest {
        val f = Fixture(this); f.playFailure = FailureReason.UNSUPPORTED; f.command("뉴스 틀어줘")
        assertTrue(f.saved.isEmpty()); assertEquals(DrivingMode.QUIET, f.status.mode)
        assertEquals(FailureReason.UNSUPPORTED, f.status.failure)
    }
    @Test fun profileSaveFailureDoesNotPretendPlaybackFailedOrRetryIt() = runTest {
        val f = Fixture(this); f.saveResult = OperationResult.Failure(FailureReason.PERMISSION_DENIED); f.command("발라드 틀어줘")
        assertEquals(DrivingMode.MUSIC, f.status.mode); assertFalse(f.status.preferencesSaved)
        assertEquals(FailureReason.PERMISSION_DENIED, f.status.failure)
        assertEquals(1, f.commands.count { it is MediaCommand.PlayMusic })
    }
    @Test fun speechFailuresAndSilenceDoNotBecomePreferenceOrConditionAnswers() = runTest {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable).forEach {
            val f = Fixture(this); val original = f.safety.value; f.answer = it; f.command("음악 틀어줘")
            assertTrue(f.saved.isEmpty()); assertTrue(f.commands.isEmpty()); assertEquals(original, f.safety.value)
        }
    }
    @Test fun failedQuestionPlaybackDoesNotStartListening() = runTest {
        val f = Fixture(this); f.speechFailure = FailureReason.PERMISSION_DENIED; f.command("음악 틀어줘")
        assertEquals(0, f.listens); assertTrue(f.commands.isEmpty()); assertTrue(f.saved.isEmpty())
    }
    @Test fun duplicateAndStaleCommandTimestampsDoNotRepeatPlayback() = runTest {
        val f = Fixture(this); f.command("발라드 틀어줘"); val count = f.commands.size
        f.coordinator.onCommand("뉴스 틀어줘", f.clock.nowMillis())
        f.coordinator.onCommand("뉴스 틀어줘", f.clock.nowMillis() - 1)
        assertEquals(count, f.commands.size)
    }
    @Test fun repeatedOfferDoesNotAskAgainUnlessExplicitlyRetried() = runTest {
        val f = Fixture(this); f.coordinator.offerMode(); f.coordinator.offerMode()
        assertEquals(1, f.listens)
        f.coordinator.offerMode(retry = true); assertEquals(2, f.listens)
    }
    @Test fun explicitNewCommandPreemptsPendingProposal() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val old = launch { f.coordinator.offerMode() }; runCurrent(); f.command("오늘은 조용히")
        assertTrue(old.isCancelled); assertEquals(DrivingMode.QUIET, f.status.mode)
        assertTrue(f.commands.none { it is MediaCommand.PlayMusic })
    }
    @Test fun cancellationAndCloseReleasePendingVoiceAndRejectFurtherCommands() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred()
        val task = launch { f.command("음악 틀어줘") }; runCurrent(); f.coordinator.cancel()
        assertTrue(task.isCancelled); assertTrue(f.voiceStops > 0); assertTrue(f.saved.isEmpty())
        f.coordinator.close(); f.coordinator.close(); val count = f.commands.size
        f.command("뉴스 틀어줘"); assertEquals(count, f.commands.size)
    }
    @Test fun listeningAndMediaCallsHaveVirtualDeadlines() = runTest {
        val f = Fixture(this); f.listenGate = CompletableDeferred(); f.command("음악 틀어줘")
        assertEquals(10_000L, testScheduler.currentTime); assertEquals(FailureReason.TIMEOUT, f.status.failure)
        val p = Fixture(this); p.playGate = CompletableDeferred(); p.command("발라드 틀어줘")
        assertEquals(25_000L, testScheduler.currentTime); assertEquals(FailureReason.TIMEOUT, p.status.failure)
        assertTrue(p.saved.isEmpty())
        assertEquals(MediaCommand.Stop, p.commands.last())
    }
    @Test fun profileObserverStopsCompletedPlaybackOnUserSwitch() = runTest {
        val f = Fixture(this); backgroundScope.launch { f.coordinator.observeChanges() }; runCurrent()
        f.command("발라드 틀어줘"); val saves = f.saved.size
        f.profile.value = DriverPreferences("driver-B", "다른 테스트"); runCurrent()
        assertEquals(MediaCommand.Stop, f.commands.last()); assertEquals(NormalModePhase.PROFILE_CHANGED, f.status.phase)
        assertEquals(saves, f.saved.size)
    }
    @Test fun profileSwitchAtSaveCannotWriteAnotherUsersPreferences() = runTest {
        val f = Fixture(this); f.onSave = { f.profile.value = DriverPreferences("driver-B", "다른 테스트") }
        f.command("발라드 틀어줘")
        assertTrue(f.saved.isEmpty()); assertEquals(NormalModePhase.PROFILE_CHANGED, f.status.phase)
        assertEquals(MediaCommand.Stop, f.commands.last())
    }
    @Test fun sameProfilePreferenceRefreshDoesNotChangeWhatWasProposed() = runTest {
        val f = Fixture(this); f.profile.value = f.profile.value!!.copy(previousMode = DrivingMode.MUSIC, genres = listOf("발라드"))
        f.answer = RecognitionResult.Text("네")
        f.onListen = { f.profile.value = f.profile.value!!.copy(genres = listOf("재즈")) }
        f.coordinator.offerMode()
        assertEquals(MediaCommand.PlayMusic("발라드"), f.commands.last())
        assertEquals(listOf("재즈"), f.saved.single().genres)
    }
    @Test fun genericYesWithoutPreviousProposalCannotSelectMode() = runTest {
        val f = Fixture(this); f.answer = RecognitionResult.Text("네"); f.coordinator.offerMode()
        assertEquals(NormalModePhase.CLARIFY, f.status.phase)
        assertTrue(f.commands.isEmpty()); assertTrue(f.saved.isEmpty())
    }
    @Test fun dangerousStateDuringPendingPlaybackCancelsAndStopsIt() = runTest {
        val f = Fixture(this); f.playGate = CompletableDeferred()
        val task = launch { f.command("발라드 틀어줘") }; runCurrent(); f.danger(); runCurrent(); task.join()
        f.playGate!!.complete(Unit); runCurrent()
        assertEquals(MediaCommand.Stop, f.commands.last())
        assertEquals(NormalModePhase.BLOCKED, f.status.phase); assertTrue(f.saved.isEmpty())
    }
    @Test fun unknownCommandDoesNotExecuteOrPersist() = runTest {
        val f = Fixture(this); f.command("음악 틀지 말고 뉴스 틀어줘")
        assertEquals(NormalModePhase.CLARIFY, f.status.phase)
        assertEquals(1, f.prompts.size); assertTrue(f.commands.isEmpty()); assertTrue(f.saved.isEmpty())
    }
    @Test fun invalidCommandTimeIsRejectedBeforeSideEffects() = runTest {
        val f = Fixture(this)
        listOf(-1L, 100L).forEach {
            try { f.coordinator.onCommand("뉴스 틀어줘", it); fail("invalid timestamp accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(f.commands.isEmpty()); assertTrue(f.saved.isEmpty())
    }
    @Test fun blockedStartupOfferDoesNotConsumeLaterNormalProposal() = runTest {
        val f = Fixture(this); val normal = f.safety.value; f.danger(); f.coordinator.offerMode()
        assertEquals(0, f.listens)
        f.safety.value = normal; f.coordinator.offerMode()
        assertEquals(1, f.listens); assertEquals(DrivingMode.QUIET, f.status.mode)
    }
}
