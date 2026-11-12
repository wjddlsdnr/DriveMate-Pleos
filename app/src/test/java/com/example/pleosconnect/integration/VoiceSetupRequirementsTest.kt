package com.example.pleosconnect.integration

import com.example.pleosconnect.data.media.LocalAudioState
import com.example.pleosconnect.pleos.vehicle.*
import org.junit.Assert.*
import org.junit.Test

class VoiceSetupRequirementsTest {
    private val vehicle = VehicleReadUiState(enabled = true, nowMillis = 100,
        snapshot = VehicleReadSnapshot(gear = VehicleReading(ReadGear.PARK, 100), speedMetersPerSecond = VehicleReading(0f, 100)))
    private val media = LiveMediaUiState(profileId = "a", preferencesReady = true)
    private val local = LocalAudioState(selected = true, playing = true)
    @Test fun playingToneDoesNotReplaceRealNavigationOptIn() {
        val reasons = voiceSetupRequirements(true, false, "a", media, local, vehicle)
        assertEquals(1, reasons.size); assertTrue(reasons.single().contains("실제 내비 연결하기"))
        assertTrue(voiceSetupRequirements(true, true, "a", media, local, vehicle).isEmpty())
    }
    @Test fun savedAppMustConnectEvenWhenLocalToneIsPlaying() {
        val reasons = voiceSetupRequirements(true, true, "a", media.copy(preferredAppId = "music/Service"), local, vehicle)
        assertEquals(1, reasons.size); assertTrue(reasons.single().contains("기본 음악 앱"))
    }
    @Test fun staleSignalsAndDifferentProfilesAreVisibleBlockers() {
        val reasons = voiceSetupRequirements(true, true, "b", media, local, vehicle.copy(nowMillis = 6_000))
        assertEquals(2, reasons.size)
    }
}
