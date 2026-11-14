package com.example.pleosconnect.integration

import com.example.pleosconnect.contract.integration.*
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.RecognitionResult
import com.example.pleosconnect.data.profile.*
import com.example.pleosconnect.mock.integration.*
import com.example.pleosconnect.voice.VoiceBackend
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RealVoiceSessionGateTest {
    @Test fun optedInSessionDoesNotAskOrListenBeforeExplicitStart() = runTest {
        val clock = MonotonicClock { testScheduler.currentTime + 10 }
        val navigation = FakeNavigationController(clock)
        navigation.setDestination(DestinationRequest("fixture-route", "fixture"), DriverConsent(ConsentScope.DESTINATION, "fixture-route", clock.nowMillis()))
        val profile = DriverPreferences("driver", "fixture")
        val repository = LocalDriverProfileRepository(object : ProfileStorage {
            override fun load() = ProfileSnapshot(listOf(profile), profile.profileId)
            override fun save(snapshot: ProfileSnapshot) = Unit
        }, StandardTestDispatcher(testScheduler))
        var speaks = 0
        val backend = object : VoiceBackend {
            override suspend fun speak(text: String): OperationResult<Unit> { speaks++; return OperationResult.Success(Unit) }
            override suspend fun listen() = RecognitionResult.Timeout
            override suspend fun stop() = Unit
            override suspend fun close() = Unit
        }
        val media = FakeMediaController()
        val owner = DriveMateSessionOwner(backgroundScope, { scope, p, scenario ->
            DriveMateAppGraph(scope, p, scenario, clock, backend, repository, navigation,
                FakeRestStopFinder(navigation), realVoice = true, mediaOverride = media)
        }, catalog = repository)
        owner.loadProfiles(); owner.foreground()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, speaks)
        owner.submitRealVoice(true); advanceTimeBy(10); runCurrent()
        assertTrue(speaks > 0)
        owner.close()
    }
}
