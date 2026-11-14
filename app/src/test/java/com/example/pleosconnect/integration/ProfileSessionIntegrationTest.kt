package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.VoicePhase
import com.example.pleosconnect.data.profile.*
import com.example.pleosconnect.domain.rest.RestCheckpoint
import com.example.pleosconnect.mock.voice.MockVoiceBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileSessionIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun localProfilesGateStartPersistASavesAndSwitchAfterClosingOldSession() = runTest {
        val storage = FileProfileStorage(File(folder.root, "profiles.bin"))
        val repo = LocalDriverProfileRepository(storage, StandardTestDispatcher(testScheduler))
        val graphs = mutableListOf<DriveMateAppGraph>()
        val owner = DriveMateSessionOwner(backgroundScope, { scope, profile, scenario ->
            DriveMateAppGraph(scope, profile, scenario, MonotonicClock { testScheduler.currentTime + 10 },
                backend = MockVoiceBackend(scenario.answers(), 10), profiles = repo).also { graphs += it }
        }, catalog = repo)
        owner.loadProfiles(); runCurrent(); owner.foreground()
        owner.submit(SimulationAction.START); runCurrent()
        assertNull(owner.state.value.route)
        owner.registerProfile(ProfileDraft("운전자 A")); runCurrent()
        assertTrue(owner.state.value.canAct)
        val firstId = repo.selectedProfile.value!!.profileId
        owner.submit(SimulationAction.START); advanceTimeBy(100); runCurrent()
        assertEquals(DriverState.NORMAL, owner.state.value.driver.state)
        assertEquals(DrivingMode.QUIET, repo.selectedProfile.value!!.previousMode)
        val oldGraph = graphs.last()
        oldGraph.store.save(RestCheckpoint(1000, false))
        owner.submit(SimulationAction.CHECK); runCurrent()
        assertEquals(VoicePhase.SPEAKING, oldGraph.voice.phase.value)
        owner.registerProfile(ProfileDraft("운전자 B")); runCurrent()
        assertEquals(VoicePhase.UNAVAILABLE, oldGraph.voice.phase.value)
        assertNull(oldGraph.store.load())
        assertNull(owner.state.value.driver.state)
        assertNull(owner.state.value.route)
        assertNull(repo.selectedProfile.value!!.previousMode)
        owner.selectProfile(firstId); runCurrent()
        assertEquals(DrivingMode.QUIET, owner.state.value.preferences!!.previousMode)
        owner.deleteProfile(firstId); runCurrent()
        assertFalse(owner.state.value.canAct)
        assertNull(owner.state.value.selectedProfileId)
        assertEquals(1, owner.state.value.profiles.size)
        owner.close()
        val reloaded = LocalDriverProfileRepository(storage, StandardTestDispatcher(testScheduler))
        reloaded.load()
        assertNull(reloaded.selectedProfile.value)
        assertEquals("운전자 B", reloaded.catalog.value.profiles.single().displayName)
    }
}
