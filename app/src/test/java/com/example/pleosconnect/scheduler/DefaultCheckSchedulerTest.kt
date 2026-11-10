package com.example.pleosconnect.scheduler

import com.example.pleosconnect.contract.integration.RouteProgress
import com.example.pleosconnect.contract.logic.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

class DefaultCheckSchedulerTest {
    private class Fixture {
        var time = 0L
        val scheduler = DefaultCheckScheduler(MonotonicClock { time })
        fun at(minutes: Int) { time = minutes * 60_000L }
        fun route(eta: Int = 60, revision: Long = 0, id: String = "trip") {
            scheduler.onRouteUpdated(RouteProgress(id, revision, eta.minutes, time))
        }
        val times get() = scheduler.pending.value.filter { it.kind == CheckKind.PERIODIC }.map { it.dueAtMillis / 60_000L }
    }

    @Test fun sixtyMinuteTripHasThreeChecksAndNoArrivalQuestion() {
        val f = Fixture(); f.route()
        assertEquals(listOf(0L, 20L, 40L), f.times)
        listOf(0, 20, 40).forEach { f.at(it); assertEquals(CheckKind.PERIODIC, f.scheduler.takeDue().single().kind); assertTrue(f.scheduler.takeDue().isEmpty()) }
        f.at(60); assertTrue(f.scheduler.takeDue().isEmpty())
    }
    @Test fun twoHourTripHasFourChecks() {
        val f = Fixture(); f.route(120)
        assertEquals(listOf(0L, 30L, 60L, 90L), f.times)
    }
    @Test fun shortTripOnlyChecksDeparture() {
        listOf(1, 10, 20).forEach { val f = Fixture(); f.route(it); assertEquals(listOf(0L), f.times) }
    }
    @Test fun fractionalIntervalsStayBeforeArrival() {
        val f = Fixture(); f.route(61)
        val times = f.scheduler.pending.value.map { it.dueAtMillis }
        assertEquals(listOf(0L, 1_220_000L, 2_440_000L), times)
    }
    @Test fun receivedSnapshotDoesNotExtendEtaFromReceptionTime() {
        val f = Fixture(); f.at(10)
        f.scheduler.onRouteUpdated(RouteProgress("trip", 0, 60.minutes, 0))
        assertEquals(listOf(10L, 20L, 40L), f.times)
        f.scheduler.takeDue()
        f.at(60); assertTrue(f.scheduler.takeDue().isEmpty())
    }
    @Test fun repeatedOrStaleRevisionsDoNotReplaceIdsOrChecks() {
        val f = Fixture(); f.route(revision = 2)
        val before = f.scheduler.pending.value
        f.route(120, 2); f.route(120, 1)
        assertEquals(before, f.scheduler.pending.value)
        f.at(1); f.route(60, 3)
        val updated = f.scheduler.pending.value
        f.scheduler.onRouteUpdated(RouteProgress("trip", 4, 120.minutes, 0))
        assertEquals(updated, f.scheduler.pending.value)
    }
    @Test fun etaRefreshDoesNotRepeatDepartureOrPostponeNextCheckForever() {
        val f = Fixture(); f.route(); val departure = f.scheduler.takeDue().single()
        for (minute in 1..19) { f.at(minute); f.route(60, minute.toLong()); assertEquals(20L, f.times.first()) }
        f.at(20); val due = f.scheduler.takeDue().single()
        assertNotEquals(departure.id, due.id)
        assertTrue(f.scheduler.takeDue().isEmpty())
        assertTrue(f.scheduler.pending.value.filter { it.kind == CheckKind.PERIODIC }.all { it.dueAtMillis >= 30 * 60_000L })
    }
    @Test fun shorterEtaDropsChecksBeyondArrival() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.at(10); f.route(5, 1)
        assertTrue(f.scheduler.pending.value.isEmpty())
        f.at(15); assertTrue(f.scheduler.takeDue().isEmpty())
    }
    @Test fun reroutePreservesRiskAndRecoveryDeadlines() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 5.minutes)
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 8.minutes)
        val followups = f.scheduler.pending.value.filter { it.kind != CheckKind.PERIODIC }
        f.at(1); f.route(80, 1)
        assertEquals(followups, f.scheduler.pending.value.filter { it.kind != CheckKind.PERIODIC })
    }
    @Test fun simultaneousChecksCoalesceToRiskThenDoNotReappear() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 20.minutes)
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 20.minutes)
        f.at(20)
        assertEquals(CheckKind.RISK_FOLLOW_UP, f.scheduler.takeDue().single().kind)
        assertTrue(f.scheduler.takeDue().isEmpty())
        f.at(21); assertTrue(f.scheduler.takeDue().isEmpty())
        assertTrue(f.scheduler.pending.value.none { it.dueAtMillis <= 20 * 60_000L })
    }
    @Test fun recoveryBeatsPeriodicAndSafetyKindsAreIndependent() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 20.minutes)
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 25.minutes)
        f.at(20); assertEquals(CheckKind.RECOVERY, f.scheduler.takeDue().single().kind)
        f.at(25); assertEquals(CheckKind.RISK_FOLLOW_UP, f.scheduler.takeDue().single().kind)
    }
    @Test fun repeatedFollowupKeepsEarliestWithoutDuplicate() {
        val f = Fixture(); f.route()
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 5.minutes)
        val first = f.scheduler.pending.value.single { it.kind == CheckKind.RECOVERY }
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 10.minutes)
        assertEquals(first, f.scheduler.pending.value.single { it.kind == CheckKind.RECOVERY })
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 2.minutes)
        assertEquals(120_000L, f.scheduler.pending.value.single { it.kind == CheckKind.RECOVERY }.dueAtMillis)
    }
    @Test fun delayedPollingCoalescesMissedPeriodicChecks() {
        val f = Fixture(); f.route(); f.at(45)
        assertEquals(1, f.scheduler.takeDue().size)
        assertTrue(f.scheduler.pending.value.isEmpty())
        assertTrue(f.scheduler.takeDue().isEmpty())
    }
    @Test fun allKindsRespectOneMinuteDeliveryGap() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 1.milliseconds)
        f.time = 1; assertTrue(f.scheduler.takeDue().isEmpty())
        f.at(1); assertEquals(CheckKind.RISK_FOLLOW_UP, f.scheduler.takeDue().single().kind)
    }
    @Test fun arrivalCancelsEvenOverdueSafetyQuestions() {
        val f = Fixture(); f.route()
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 5.minutes)
        f.at(60)
        assertTrue(f.scheduler.takeDue().isEmpty())
        assertTrue(f.scheduler.pending.value.isEmpty())
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 1.minutes)
        assertTrue(f.scheduler.pending.value.isEmpty())
    }
    @Test fun zeroRemainingAndNullAndCancelAllClearEveryKind() {
        repeat(3) { mode ->
            val f = Fixture(); f.route()
            f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 1.minutes)
            when (mode) { 0 -> f.route(0, 1); 1 -> f.scheduler.onRouteUpdated(null); else -> f.scheduler.cancelAll() }
            assertTrue(f.scheduler.pending.value.isEmpty())
            f.at(20); assertTrue(f.scheduler.takeDue().isEmpty())
            f.route(60, 2)
            assertTrue(f.scheduler.pending.value.isEmpty())
        }
    }
    @Test fun routeReplacementDropsOldFollowupsAndRejectsLateOldTrip() {
        val f = Fixture(); f.route()
        f.scheduler.scheduleFollowUp(CheckKind.RISK_FOLLOW_UP, 5.minutes)
        f.at(1); f.route(120, id = "new-trip")
        assertTrue(f.scheduler.pending.value.all { it.kind == CheckKind.PERIODIC })
        val before = f.scheduler.pending.value
        f.at(2); f.route(60, 100)
        assertEquals(before, f.scheduler.pending.value)
    }
    @Test fun expiredEtaCanBeExtendedWithoutNewDeparture() {
        val f = Fixture(); f.route(20); f.scheduler.takeDue()
        f.at(20); assertTrue(f.scheduler.takeDue().isEmpty())
        f.route(60, 1)
        assertEquals(listOf(40L, 60L), f.times)
    }
    @Test fun noRouteCannotScheduleOrDeliver() {
        val f = Fixture()
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 1.minutes)
        assertTrue(f.scheduler.pending.value.isEmpty())
        assertTrue(f.scheduler.takeDue().isEmpty())
    }
    @Test fun invalidRoutesLeaveExistingScheduleIntact() {
        val f = Fixture(); f.route()
        val before = f.scheduler.pending.value
        val invalid = listOf(
            RouteProgress("", 1, 60.minutes, 0), RouteProgress("trip", -1, 60.minutes, 0),
            RouteProgress("trip", 1, (-1).minutes, 0), RouteProgress("trip", 1, Duration.INFINITE, 0),
            RouteProgress("trip", 1, 1.nanoseconds, 0), RouteProgress("trip", 1, 60.minutes, 1),
            RouteProgress("trip", 1, Long.MAX_VALUE.milliseconds, 0),
        )
        invalid.forEach { input -> expectInvalid { f.scheduler.onRouteUpdated(input) }; assertEquals(before, f.scheduler.pending.value) }
    }
    @Test fun invalidFollowupsAndBackwardClockAreRejected() {
        val f = Fixture(); f.route()
        val before = f.scheduler.pending.value
        listOf(Duration.ZERO, (-1).minutes, Duration.INFINITE).forEach { delay ->
            expectInvalid { f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, delay) }
        }
        expectInvalid { f.scheduler.scheduleFollowUp(CheckKind.PERIODIC, 1.minutes) }
        assertEquals(before, f.scheduler.pending.value)
        f.time = -1; expectInvalid { f.scheduler.takeDue() }
        f.time = 60_000; f.scheduler.takeDue()
        f.time = 59_999; expectInvalid { f.scheduler.takeDue() }
    }
    @Test fun subMillisecondFollowupRoundsUpAndDoesNotFireEarly() {
        val f = Fixture(); f.route()
        f.scheduler.scheduleFollowUp(CheckKind.RECOVERY, 1.nanoseconds)
        assertEquals(1L, f.scheduler.pending.value.single { it.kind == CheckKind.RECOVERY }.dueAtMillis)
    }
    @Test fun concurrentPollersCannotDeliverSameQuestionTwice() {
        val f = Fixture(); f.route()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val answers = executor.invokeAll(List(12) { Callable { f.scheduler.takeDue() } }).flatMap { it.get() }
            assertEquals(1, answers.size)
        } finally { executor.shutdownNow() }
    }
    @Test fun newTripDepartureRespectsGapAfterCancellation() {
        val f = Fixture(); f.route(); f.scheduler.takeDue(); f.scheduler.cancelAll()
        f.time = 1; f.route(id = "next")
        assertTrue(f.scheduler.takeDue().isEmpty())
        f.at(1); assertEquals(CheckKind.PERIODIC, f.scheduler.takeDue().single().kind)
    }
    @Test fun nearRecalculatedPeriodicChecksAreNotDeliveredOneMinuteApart() {
        val f = Fixture(); f.route(); f.scheduler.takeDue()
        f.at(1); f.route(60, 1) // Original 20-minute checkpoint plus a new 21-minute checkpoint.
        f.at(20); assertEquals(1, f.scheduler.takeDue().size)
        f.at(21); assertTrue(f.scheduler.takeDue().isEmpty())
        assertTrue(f.scheduler.pending.value.all { it.dueAtMillis >= 30 * 60_000L })
    }
    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("invalid input accepted") } catch (_: IllegalArgumentException) { }
    }
}
