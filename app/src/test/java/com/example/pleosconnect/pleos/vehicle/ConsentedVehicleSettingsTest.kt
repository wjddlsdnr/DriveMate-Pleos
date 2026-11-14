package com.example.pleosconnect.pleos.vehicle

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.integration.LiveVehicleControlIntegration
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentedVehicleSettingsTest {
    private class Backend(val now: () -> Long) : VehicleSettingsBackend {
        val key = VehicleSettingKey(VehicleSettingKind.CLIMATE_POWER, "ROW_1_LEFT")
        var value = 1.0; var writes = 0; var closes = 0; var reads = 0
        var gear = ReadGear.PARK; var speed: Float? = 0f; var parking = true
        var callback: ((VehicleSettingChange) -> Unit)? = null
        var failMonitoring = false; var changeValue = true; var writeFailure: FailureReason? = null
        var throwMonitoring = false
        var waitForWrite: CompletableDeferred<Unit>? = null
        override suspend fun inspect(): VehicleControlInspection {
            reads++
            return VehicleControlInspection(VehicleReadSnapshot(gear = VehicleReading(gear, now()),
                speedMetersPerSecond = VehicleReading(speed, now(), if (speed == null) VehicleReadProblem.PERMISSION_DENIED else null),
                parkingBrake = VehicleReading(parking, now())), listOf(VehicleSetting(key, value, 0.0, 1.0, now())))
        }
        override suspend fun monitor(settings: List<VehicleSetting>, listener: (VehicleSettingChange) -> Unit) {
            callback = listener
            if (throwMonitoring) throw IllegalStateException()
            if (failMonitoring) listener(VehicleSettingChange(key, null))
        }
        override suspend fun write(key: VehicleSettingKey, value: Double): OperationResult<Unit> {
            writes++; waitForWrite?.await()
            writeFailure?.let { return OperationResult.Failure(it) }
            if (changeValue) { this.value = value; callback?.invoke(VehicleSettingChange(key, value)) }
            return OperationResult.Success(Unit)
        }
        override suspend fun close() { closes++ }
        fun external(value: Double?) { if (value != null) this.value = value; callback?.invoke(VehicleSettingChange(key, value)) }
    }
    private class Fixture(test: TestScope) {
        val clock = MonotonicClock { test.testScheduler.currentTime + 100 }
        val backend = Backend { clock.nowMillis() }
        val control = ConsentedVehicleSettings(backend, clock)
        suspend fun prepare(): String { control.open("profile-A"); control.prepare(backend.key, 0.0); return control.state.value.review!!.id }
        suspend fun apply(): String { val id = prepare(); control.confirmFromUi(id); return id }
    }
    @Test fun preparingAndCancellingNeverChangesSettings() = runTest {
        val f = Fixture(this); f.prepare(); assertEquals(0, f.backend.writes)
        f.control.cancelReview(); f.control.confirmFromUi("invented")
        assertEquals(0, f.backend.writes); assertEquals(FailureReason.CONSENT_REQUIRED, f.control.state.value.failure)
    }
    @Test fun onlyExactFreshReviewCanChangeAndReadbackOwnsOriginal() = runTest {
        val f = Fixture(this); val id = f.prepare(); f.control.confirmFromUi(id)
        assertEquals(1, f.backend.writes)
        val change = f.control.state.value.changes.single()
        assertEquals(1.0, change.original, 0.0); assertEquals(0.0, change.applied, 0.0); assertTrue(change.restorable)
        f.control.confirmFromUi(id); assertEquals(1, f.backend.writes)
    }
    @Test fun wrongAndExpiredConsentNeverWrites() = runTest {
        val f = Fixture(this); f.prepare(); f.control.confirmFromUi("wrong")
        assertEquals(0, f.backend.writes)
        val id = f.prepare(); advanceTimeBy(30_001); f.control.confirmFromUi(id)
        assertEquals(0, f.backend.writes)
    }
    @Test fun changedBeforeValueInvalidatesConsentWithoutWriting() = runTest {
        val f = Fixture(this); val id = f.prepare(); f.backend.value = 0.0
        f.control.confirmFromUi(id); assertEquals(0, f.backend.writes)
        assertEquals(FailureReason.STALE_REQUEST, f.control.state.value.failure)
    }
    @Test fun forwardReverseNeutralMissingSpeedAndReleasedBrakeBlockCommands() = runTest {
        for (gear in listOf(ReadGear.DRIVE, ReadGear.REVERSE, ReadGear.NEUTRAL)) {
            val f = Fixture(this); f.control.open("A"); f.backend.gear = gear
            f.control.prepare(f.backend.key, 0.0); assertNull(f.control.state.value.review); assertEquals(0, f.backend.writes)
        }
        for (speed in listOf<Float?>(null, -1f, 1f, Float.NaN)) {
            val f = Fixture(this); f.control.open("A"); f.backend.speed = speed
            f.control.prepare(f.backend.key, 0.0); assertNull(f.control.state.value.review)
        }
        val f = Fixture(this); f.control.open("A"); f.backend.parking = false
        f.control.prepare(f.backend.key, 0.0); assertNull(f.control.state.value.review)
    }
    @Test fun restorationRequiresNewReviewNonceAndNewConfirmation() = runTest {
        val f = Fixture(this); val id = f.apply()
        f.control.prepareRestore(id); val restore = f.control.state.value.review!!
        assertNotEquals(id, restore.id); assertTrue(restore.restoring)
        assertEquals(1, f.backend.writes)
        f.control.confirmFromUi(id); assertEquals(1, f.backend.writes)
        f.control.prepareRestore(id); f.control.confirmFromUi(f.control.state.value.review!!.id)
        assertEquals(2, f.backend.writes); assertEquals(1.0, f.backend.value, 0.0)
        assertTrue(f.control.state.value.changes.isEmpty())
    }
    @Test fun differingOrUnknownEventsBlockRestoration() = runTest {
        for (value in listOf<Double?>(1.0, null)) {
            val f = Fixture(this); val id = f.apply(); f.backend.external(value)
            assertFalse(f.control.state.value.changes.single().restorable)
            f.control.prepareRestore(id); assertNull(f.control.state.value.review); assertEquals(1, f.backend.writes)
        }
    }
    @Test fun duplicateAppliedValueEventsDoNotDiscardVerifiedOwnership() = runTest {
        val f = Fixture(this); val id = f.apply()
        f.backend.external(0.0); f.backend.external(0.0)
        assertTrue(f.control.state.value.changes.single().restorable)
        f.control.prepareRestore(id); assertNotNull(f.control.state.value.review)
        f.control.confirmFromUi(f.control.state.value.review!!.id)
        assertEquals(2, f.backend.writes); assertTrue(f.control.state.value.changes.isEmpty())
    }
    @Test fun callbackAcknowledgementWithoutMatchingReadbackIsUncertainNotSuccess() = runTest {
        val f = Fixture(this); val id = f.prepare(); f.backend.changeValue = false
        f.control.confirmFromUi(id)
        assertFalse(f.control.state.value.changes.single().restorable)
        assertNotNull(f.control.state.value.failure)
        f.control.prepare(f.backend.key, 0.0); assertNull(f.control.state.value.review); assertEquals(1, f.backend.writes)
    }
    @Test fun errorAfterCommandNeverTriggersAutomaticRollbackOrRetry() = runTest {
        val f = Fixture(this); val id = f.prepare(); f.backend.writeFailure = FailureReason.TIMEOUT
        f.control.confirmFromUi(id); assertEquals(1, f.backend.writes)
        assertFalse(f.control.state.value.changes.single().restorable)
        f.control.invalidate(); assertEquals(1, f.backend.writes)
    }
    @Test fun cancellationAndLateCallbackCannotCreateOwnership() = runTest {
        val f = Fixture(this); val id = f.prepare(); f.backend.waitForWrite = CompletableDeferred()
        val work = backgroundScope.launch { f.control.confirmFromUi(id) }; runCurrent()
        val old = f.backend.callback!!
        f.control.invalidate(); work.cancelAndJoin(); old(VehicleSettingChange(f.backend.key, 0.0))
        assertTrue(f.control.state.value.changes.isEmpty()); assertFalse(f.control.state.value.enabled)
        assertEquals(1, f.backend.writes)
    }
    @Test fun profileSwitchInvalidatesReviewAndAllOldCallbacks() = runTest {
        val f = Fixture(this); val id = f.prepare(); val old = f.backend.callback!!
        f.control.open("profile-B"); old(VehicleSettingChange(f.backend.key, null)); f.control.confirmFromUi(id)
        assertEquals(0, f.backend.writes); assertTrue(f.control.state.value.changes.isEmpty())
        f.control.prepare(f.backend.key, 0.0); assertNotNull(f.control.state.value.review)
    }
    @Test fun failedMonitoringAndMissingProfileNeverPermitChanges() = runTest {
        val f = Fixture(this); f.control.open(null); assertFalse(f.control.state.value.enabled)
        f.backend.failMonitoring = true; f.control.open("A"); f.control.prepare(f.backend.key, 0.0)
        assertNull(f.control.state.value.review)
    }
    @Test fun partiallyRegisteredMonitoringFailureClosesAndDisablesAuthority() = runTest {
        val f = Fixture(this); f.backend.throwMonitoring = true
        try { f.control.open("A"); fail("Monitoring must fail") } catch (_: IllegalStateException) { }
        assertFalse(f.control.state.value.enabled); assertEquals(1, f.backend.closes)
        f.control.prepare(f.backend.key, 0.0); assertNull(f.control.state.value.review)
        assertEquals(0, f.backend.writes)
    }
    @Test fun windowTargetsMustBeWholeValuesInsideCapabilityBounds() {
        val window = VehicleSetting(VehicleSettingKey(VehicleSettingKind.WINDOW_POSITION, "ROW_1_LEFT"), 10.0, 0.0, 100.0, 0)
        assertTrue(window.accepts(25.0))
        for (target in listOf(-1.0, 101.0, 1.5, 1.0000001, Double.NaN, Double.POSITIVE_INFINITY)) assertFalse(window.accepts(target))
        assertFalse(window.copy(minimum = 100.0, maximum = 0.0).accepts(25.0))
    }
    @Test fun lifecycleCancelsQueuedWorkAndDoesNotAutoRestore() = runTest {
        val clock = MonotonicClock { testScheduler.currentTime + 100 }; val backend = Backend { clock.nowMillis() }
        val integration = LiveVehicleControlIntegration(backend, clock, StandardTestDispatcher(testScheduler))
        integration.prepareConnection("A"); runCurrent(); assertEquals(0, backend.reads)
        integration.foreground(); integration.prepareConnection("A"); runCurrent()
        integration.prepare(backend.key, 0.0); runCurrent(); val id = integration.state.value.review!!.id
        integration.confirm(id); runCurrent(); assertEquals(1, backend.writes)
        integration.suspendCommands(); integration.background(); assertTrue(integration.state.value.changes.isEmpty())
        integration.confirm(id); runCurrent(); assertEquals(1, backend.writes)
        integration.foreground(); integration.prepareConnection("B"); runCurrent(); assertTrue(integration.state.value.enabled)
        integration.close(); assertEquals(1, backend.writes)
    }
}
