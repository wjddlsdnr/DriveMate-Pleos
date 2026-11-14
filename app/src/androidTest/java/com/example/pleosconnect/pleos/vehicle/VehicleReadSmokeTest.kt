package com.example.pleosconnect.pleos.vehicle

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual SDK read-only smoke. No setters, VHAL injection, seat motion or manual-state changes. */
@RunWith(AndroidJUnit4::class)
class VehicleReadSmokeTest {
    @Test fun actualVehicleAndAaosValuesCanBeReadAndReleased() = runBlocking {
        val backend = PleosVehicleReadBackend(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val result = withTimeout(15_000) { backend.read() }
            Log.i("DriveMateVehicleProbe", "gearReceived=${result.gear.value != null},gearProblem=${result.gear.problem}," +
                "speedReceived=${result.speedMetersPerSecond.value != null},speedProblem=${result.speedMetersPerSecond.problem}," +
                "parkingBrakeReceived=${result.parkingBrake.value != null},climateAreas=${result.climate.value?.size}," +
                "brakePedalReceived=${result.brakePedal.value != null},climateValues=${result.climate.value?.count { it.value != null && it.problem == null }}," +
                "climateIssues=${result.climate.value?.map { it.problem }},powerValues=${result.climatePower.value?.count { it.value != null && it.problem == null }}," +
                "climateProblem=${result.climate.problem},windowAreas=${result.windows.value?.size}," +
                "windowValues=${result.windows.value?.count { it.value != null && it.problem == null }},windowProblem=${result.windows.problem}")
            assertTrue("At least one actual SDK/property result must arrive", result.gear.value != null || result.parkingBrake.value != null ||
                result.climate.value != null || result.windows.value != null)
        } finally { backend.close() }
    }
}
