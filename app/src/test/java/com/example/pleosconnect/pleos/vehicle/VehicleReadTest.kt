package com.example.pleosconnect.pleos.vehicle

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.domain.SafeParking
import com.example.pleosconnect.integration.LiveVehicleReadIntegration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleReadTest {
    @Test fun areaValueAgeIsNotResetWhenAnotherAreaFinishesLater() {
        val area = VehicleAreaValue("ROW_1_LEFT", "1", "0", "100", readWrite = true, observedAtMillis = 100)
        val section = VehicleReading(listOf(area), 6_000)
        assertTrue(section.fresh(6_001))
        assertFalse(area.fresh(6_001))
        assertFalse(area.copy(observedAtMillis = 6_002).fresh(6_001))
        assertTrue(area.copy(observedAtMillis = 6_000).fresh(6_001))
    }
    @Test fun zeroSpeedAndParkAndBrakeCannotProveSafeLocation() {
        val snapshot = VehicleReadSnapshot(gear = VehicleReading(ReadGear.PARK, 100), speedMetersPerSecond = VehicleReading(0f, 100),
            parkingBrake = VehicleReading(true, 100), brakePedal = VehicleReading(true, 100))
        assertEquals(SafeParking.UNKNOWN, VehicleSafeParkingCheck({ snapshot }, MonotonicClock { 101 }).current())
    }
    @Test fun reverseSpeedAndNonParkGearDisproveParking() {
        for (speed in listOf(1f, -1f)) {
            val snapshot = VehicleReadSnapshot(speedMetersPerSecond = VehicleReading(speed, 100))
            assertEquals(SafeParking.NOT_PARKED, VehicleSafeParkingCheck({ snapshot }, MonotonicClock { 101 }).current())
        }
        for (gear in listOf(ReadGear.DRIVE, ReadGear.REVERSE, ReadGear.NEUTRAL)) {
            val snapshot = VehicleReadSnapshot(gear = VehicleReading(gear, 100))
            assertEquals(SafeParking.NOT_PARKED, VehicleSafeParkingCheck({ snapshot }, MonotonicClock { 101 }).current())
        }
    }
    @Test fun staleFutureInvalidAndDeniedSignalsRemainUnknown() {
        val values = listOf(VehicleReading(1f, 0), VehicleReading(1f, 10_001), VehicleReading(Float.NaN, 9_999),
            VehicleReading(1f, 9_999, VehicleReadProblem.PERMISSION_DENIED))
        for (reading in values) assertEquals(SafeParking.UNKNOWN, VehicleSafeParkingCheck(
            { VehicleReadSnapshot(speedMetersPerSecond = reading) }, MonotonicClock { 10_000 }).current())
    }
    private class FakeBackend : VehicleReadBackend {
        var reads = 0; var closes = 0
        var readBlock: suspend () -> VehicleReadSnapshot = { VehicleReadSnapshot() }
        override suspend fun read(): VehicleReadSnapshot { reads++; return readBlock() }
        override suspend fun close() { closes++ }
    }
    @Test fun noReadsBeforeOptInOrWhileBackgroundedAndReconnectsOnReturn() = runTest {
        val backend = FakeBackend()
        val integration = LiveVehicleReadIntegration(backend, MonotonicClock { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        integration.foreground(); runCurrent(); assertEquals(0, backend.reads)
        integration.setEnabled(true); runCurrent(); assertEquals(1, backend.reads)
        integration.background(); val count = backend.reads; advanceTimeBy(20_000); runCurrent(); assertEquals(count, backend.reads)
        assertEquals(VehicleReadSnapshot(), integration.state.value.snapshot)
        integration.foreground(); runCurrent(); assertEquals(count + 1, backend.reads)
        integration.close()
    }
    @Test fun backgroundWaitsForReadCancellationAndDiscardsItsValues() = runTest {
        val backend = FakeBackend(); val started = CompletableDeferred<Unit>(); var cancelled = false
        backend.readBlock = { started.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        val integration = LiveVehicleReadIntegration(backend, MonotonicClock { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        integration.foreground(); integration.setEnabled(true); runCurrent(); started.await()
        integration.background(); assertTrue(cancelled)
        assertFalse(integration.state.value.busy); assertEquals(VehicleReadSnapshot(), integration.state.value.snapshot)
        integration.close()
    }
    @Test fun partialFailureIsPreservedAndOldSignalsAgeOut() = runTest {
        val backend = FakeBackend()
        backend.readBlock = { VehicleReadSnapshot(gear = VehicleReading(ReadGear.DRIVE, 0),
            speedMetersPerSecond = VehicleReading(problem = VehicleReadProblem.PERMISSION_DENIED)) }
        val integration = LiveVehicleReadIntegration(backend, MonotonicClock { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        integration.foreground(); integration.setEnabled(true); runCurrent()
        assertEquals(SafeParking.NOT_PARKED, integration.parking.current())
        assertEquals(VehicleReadProblem.PERMISSION_DENIED, integration.state.value.snapshot.speedMetersPerSecond.problem)
        advanceTimeBy(5_001); runCurrent(); assertEquals(SafeParking.UNKNOWN, integration.parking.current())
        integration.setEnabled(false); assertEquals(SafeParking.UNKNOWN, integration.parking.current())
        integration.close()
    }
    @Test fun serviceFailureDoesNotBecomeZeroSpeedOrVerifiedParking() = runTest {
        val backend = FakeBackend(); backend.readBlock = { throw IllegalStateException() }
        val integration = LiveVehicleReadIntegration(backend, MonotonicClock { 0 }, StandardTestDispatcher(testScheduler))
        integration.foreground(); integration.setEnabled(true); runCurrent()
        assertNull(integration.state.value.snapshot.speedMetersPerSecond.value)
        assertEquals(VehicleReadProblem.SERVICE_UNAVAILABLE, integration.state.value.snapshot.gear.problem)
        assertEquals(SafeParking.UNKNOWN, integration.parking.current())
        integration.close(); val closes = backend.closes; integration.close(); assertEquals(closes, backend.closes)
    }
    @Test fun hungReadTimesOutAndLeavesUnknownInsteadOfSuccess() = runTest {
        val backend = FakeBackend(); backend.readBlock = { awaitCancellation() }
        val integration = LiveVehicleReadIntegration(backend, MonotonicClock { testScheduler.currentTime }, StandardTestDispatcher(testScheduler))
        integration.foreground(); integration.setEnabled(true); runCurrent()
        advanceTimeBy(9_001); runCurrent()
        assertEquals(VehicleReadProblem.TIMEOUT, integration.state.value.snapshot.gear.problem)
        assertEquals(SafeParking.UNKNOWN, integration.parking.current())
        integration.close()
    }
}
