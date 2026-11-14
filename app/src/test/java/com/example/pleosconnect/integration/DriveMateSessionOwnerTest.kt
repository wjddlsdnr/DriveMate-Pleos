package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.*
import com.example.pleosconnect.contract.integration.MediaCommand
import com.example.pleosconnect.domain.SafeParking
import com.example.pleosconnect.domain.rest.RestCheckpoint
import com.example.pleosconnect.mock.voice.MockVoiceBackend
import com.example.pleosconnect.ui.DriveMateViewModel
import com.example.pleosconnect.ui.driverStatusLabel
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveMateSessionOwnerTest {
    @Test fun unconfirmedLatestAnswerDoesNotDisplayOldNormalStateAsConfirmed() {
        assertEquals("미확인", driverStatusLabel(DriverStatus(state = DriverState.NORMAL, report = ConditionReport.UNCONFIRMED)))
        assertEquals("고위험 의심 · 휴식 권고", driverStatusLabel(DriverStatus(state = DriverState.HIGH_RISK, report = ConditionReport.UNCONFIRMED)))
    }
    private class Probe(scenario: SimulationScenario) : VoiceBackend {
        private val backend = MockVoiceBackend(scenario.answers(), playbackMillis = 10)
        var closes = 0
        var stops = 0
        val prompts = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun speak(text: String): OperationResult<Unit> {
            prompts += text; gate?.await(); return backend.speak(text)
        }
        override suspend fun listen() = backend.listen()
        override suspend fun stop() { stops++; backend.stop() }
        override suspend fun close() { closes++; backend.close() }
    }
    private class Fixture(val test: TestScope) {
        val graphs = mutableListOf<DriveMateAppGraph>()
        val probes = mutableListOf<Probe>()
        val factory: (CoroutineScope, DriverPreferences, SimulationScenario) -> DriveMateAppGraph = { scope, profile, scenario ->
            val probe = Probe(scenario).also { probes += it }
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { test.testScheduler.currentTime + 10 }, probe).also { graphs += it }
        }
        val owner = DriveMateSessionOwner(test.backgroundScope, factory)
    }

    @Test fun initialUnknownThenRealSessionFlowsAndMediaCommandsReachUi() = runTest {
        val f = Fixture(this); runCurrent()
        assertNull(f.owner.state.value.driver.state)
        assertEquals(ConditionReport.UNCONFIRMED, f.owner.state.value.driver.report)
        assertEquals(SafeParking.UNKNOWN, f.graphs.last().parking.current())
        f.owner.submit(SimulationAction.START); runCurrent()
        assertNull(f.owner.state.value.route) // background gate
        f.owner.foreground(); f.owner.submit(SimulationAction.START)
        advanceTimeBy(100); runCurrent()
        assertEquals(DriverState.NORMAL, f.owner.state.value.driver.state)
        assertEquals(DrivingMode.QUIET, f.owner.state.value.mode.mode)
        assertTrue(f.owner.state.value.pendingCount > 0)
        f.owner.submit(SimulationAction.MUSIC); advanceTimeBy(100); runCurrent()
        assertEquals(MediaCommand.PlayMusic("발라드"), f.owner.state.value.mediaCommand)
        f.owner.close()
    }

    @Test fun backgroundCancelsSpeechAndForegroundDoesNotRestartDialogue() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.foreground()
        f.probes.last().gate = CompletableDeferred()
        f.owner.submit(SimulationAction.START); runCurrent()
        assertEquals(VoicePhase.SPEAKING, f.owner.state.value.voicePhase)
        f.owner.background(); runCurrent()
        assertFalse(f.owner.state.value.busy)
        assertFalse(f.owner.state.value.foreground)
        assertEquals(VoicePhase.IDLE, f.graphs.last().voice.phase.value)
        val count = f.probes.last().prompts.size
        advanceTimeBy(5_000); runCurrent()
        assertEquals(count, f.probes.last().prompts.size)
        f.owner.foreground(); f.owner.foreground(); runCurrent()
        assertEquals(count, f.probes.last().prompts.size)
        f.owner.close()
    }

    @Test fun switchingDriverClosesOldVoiceAndClearsRestBeforeNewSession() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.foreground()
        val old = f.graphs.last()
        old.store.save(RestCheckpoint(100_000, false))
        f.probes.last().gate = CompletableDeferred()
        f.owner.submit(SimulationAction.START); runCurrent()
        f.owner.reset(switchProfile = true); runCurrent()
        assertEquals(1, f.probes.first().closes)
        assertEquals(VoicePhase.UNAVAILABLE, old.voice.phase.value)
        assertNull(old.store.load())
        assertEquals("가상 운전자 2", f.owner.state.value.profileName)
        assertNull(f.owner.state.value.driver.state)
        assertNull(f.owner.state.value.route)
        assertTrue(f.owner.state.value.foreground)
        f.owner.close(); f.owner.close()
        assertTrue(f.probes.all { it.closes == 1 })
    }

    @Test fun unknownParkingBlocksRestWithoutConsumingASpeechAnswer() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.foreground()
        val before = f.probes.last().prompts.size
        f.owner.submit(SimulationAction.REST); runCurrent()
        assertEquals(before, f.probes.last().prompts.size)
        assertTrue(f.owner.state.value.notice!!.contains("미확인"))
        assertNotEquals(DriverState.RESTING, f.owner.state.value.driver.state)
        f.owner.close()
    }

    @Test fun permissionAndTimeoutRemainUnconfirmedAndExposeFailures() = runTest {
        for ((scenario, failure) in listOf(SimulationScenario.PERMISSION_DENIED to FailureReason.PERMISSION_DENIED,
            SimulationScenario.TIMEOUT to FailureReason.TIMEOUT)) {
            val f = Fixture(this); runCurrent(); f.owner.reset(scenario); f.owner.foreground()
            f.owner.submit(SimulationAction.START); advanceTimeBy(300); runCurrent()
            assertEquals(ConditionReport.UNCONFIRMED, f.owner.state.value.driver.report)
            assertTrue(f.owner.state.value.failures.contains(failure))
            f.owner.close()
        }
    }

    @Test fun fakeDestinationFailureDoesNotStartVoiceOrCreateRoute() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.foreground()
        f.graphs.last().navigation.failure = FailureReason.UNSUPPORTED
        f.owner.submit(SimulationAction.START); runCurrent()
        assertNull(f.owner.state.value.route)
        assertEquals(FailureReason.UNSUPPORTED, f.owner.state.value.failure)
        assertTrue(f.probes.last().prompts.isEmpty())
        f.owner.close()
    }

    @Test fun fatigueScenarioUsesAForAssistAndWaypointWithoutEnablingParking() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.reset(SimulationScenario.FATIGUE); f.owner.foreground()
        f.owner.submit(SimulationAction.START); advanceTimeBy(300); runCurrent()
        assertEquals(DriverState.CAUTION, f.owner.state.value.driver.state)
        assertEquals(1, f.graphs.last().vehicle.appliedCount)
        f.owner.submit(SimulationAction.CHECK); advanceTimeBy(300); runCurrent()
        assertEquals(DriverState.WARNING, f.owner.state.value.driver.state)
        assertEquals(1, f.graphs.last().navigation.waypointCount)
        assertEquals(SafeParking.UNKNOWN, f.graphs.last().parking.current())
        f.owner.close()
    }

    @Test fun fatigueRecheckAtActualPlaybackSpeedReachesWarning() = runTest {
        val graphs = mutableListOf<DriveMateAppGraph>()
        val owner = DriveMateSessionOwner(backgroundScope, { scope, profile, scenario ->
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { testScheduler.currentTime + 10 }).also { graphs += it }
        })
        runCurrent(); owner.reset(SimulationScenario.FATIGUE); owner.foreground()
        owner.submit(SimulationAction.START)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(DriverState.CAUTION, owner.state.value.driver.state)
        assertTrue("Ready after initial dialogue: ${owner.state.value}", owner.state.value.canAct)
        owner.submit(SimulationAction.CHECK)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(DriverState.WARNING, owner.state.value.driver.state)
        assertEquals(1, graphs.last().navigation.waypointCount)
        owner.close()
    }

    @Test fun singleForegroundPollingDeliversDueCheckAndStopsInBackground() = runTest {
        val f = Fixture(this); runCurrent(); f.owner.foreground(); f.owner.foreground()
        f.owner.submit(SimulationAction.START); advanceTimeBy(100); runCurrent()
        val before = f.probes.last().prompts.size
        advanceTimeBy(20 * 60_000L + 1_000); runCurrent()
        assertEquals(before + 1, f.probes.last().prompts.size)
        f.owner.background()
        val paused = f.probes.last().prompts.size
        advanceTimeBy(25 * 60_000L); runCurrent()
        assertEquals(paused, f.probes.last().prompts.size)
        f.owner.close()
    }

    @Test fun viewModelSerializesVisibilityAndClosesOutsideViewModelScope() = runTest {
        val graphs = mutableListOf<DriveMateAppGraph>()
        val probes = mutableListOf<Probe>()
        val vm = DriveMateViewModel(StandardTestDispatcher(testScheduler)) { scope, profile, scenario ->
            val probe = Probe(scenario).also { probes += it }
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { testScheduler.currentTime + 10 }, probe).also { graphs += it }
        }
        vm.setForeground(true); vm.act(SimulationAction.START); advanceTimeBy(100); runCurrent()
        assertEquals(DriverState.NORMAL, vm.state.value.driver.state)
        vm.setForeground(false); vm.setForeground(true); runCurrent()
        assertTrue(vm.state.value.foreground)
        vm.reset(switchProfile = true); runCurrent()
        assertEquals("가상 운전자 2", vm.state.value.profileName)
        vm.closeAndJoin()
        assertTrue(probes.all { it.closes == 1 })
        assertTrue(graphs.all { it.voice.phase.value == VoicePhase.UNAVAILABLE })
    }

    @Test fun immediateViewModelDisposalStillClosesResources() = runTest {
        lateinit var probe: Probe
        val vm = DriveMateViewModel(StandardTestDispatcher(testScheduler)) { scope, profile, scenario ->
            probe = Probe(scenario)
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { 10 }, probe)
        }
        vm.closeAndJoin()
        assertEquals(1, probe.closes)
    }
}
