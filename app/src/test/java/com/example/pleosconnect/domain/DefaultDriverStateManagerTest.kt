package com.example.pleosconnect.domain

import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.*
import com.example.pleosconnect.contract.voice.RecognitionResult
import org.junit.Assert.*
import org.junit.Test

class DefaultDriverStateManagerTest {
    private class Fixture {
        var now = 0L
        var parking = SafeParking.UNKNOWN
        val manager = DefaultDriverStateManager(MonotonicClock { now }, SafeParkingCheck { parking })
        val status get() = manager.status.value
        fun answer(text: String) = input(RecognitionResult.Text(text))
        fun input(result: RecognitionResult) { manager.onConditionAnswer(result, ++now) }
        fun reach(state: DriverState) {
            when (state) {
                DriverState.NORMAL -> answer("괜찮아")
                DriverState.CAUTION -> answer("졸려")
                DriverState.WARNING -> answer("계속 졸려")
                DriverState.HIGH_RISK -> answer("너무 졸려")
                DriverState.RECOVERY_CHECK -> { answer("졸려"); answer("괜찮아") }
                DriverState.RESTING -> { parking = SafeParking.VERIFIED; manager.onRestStarted() }
            }
            assertEquals(state, status.state)
        }
    }

    @Test fun startsUnconfirmedAndNormalIsExplicit() {
        val f = Fixture()
        assertNull(f.status.state)
        assertNull(f.status.lastConfirmedAtMillis)
        assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
        f.answer("안 졸려")
        assertEquals(DriverState.NORMAL, f.status.state)
        assertFalse(f.status.followUpRequired)
    }

    @Test fun fatigueEscalatesAndWeakerReportsDoNotLowerRisk() {
        val f = Fixture()
        f.answer("조금 피곤해")
        assertEquals(DriverState.CAUTION, f.status.state)
        f.answer("좀 졸려")
        assertEquals(DriverState.WARNING, f.status.state)
        f.answer("너무 졸려")
        assertEquals(DriverState.HIGH_RISK, f.status.state)
        f.answer("좀 졸려")
        assertEquals(DriverState.HIGH_RISK, f.status.state)
        assertEquals(ConditionReport.MILD_FATIGUE, f.status.report)
    }

    @Test fun persistentAndSevereCanEnterRiskDirectly() {
        val f = Fixture()
        f.answer("계속 졸려")
        assertEquals(DriverState.WARNING, f.status.state)
        f.answer("운전 못 하겠어")
        assertEquals(DriverState.HIGH_RISK, f.status.state)
    }

    @Test fun eachRiskLevelNeedsTwoDistinctNormalAnswers() {
        listOf(DriverState.CAUTION, DriverState.WARNING, DriverState.HIGH_RISK).forEach {
            val f = Fixture()
            f.reach(it)
            f.answer("이제 괜찮아")
            assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
            assertTrue(f.status.followUpRequired)
            f.answer("안 졸려")
            assertEquals(DriverState.NORMAL, f.status.state)
            assertFalse(f.status.followUpRequired)
            f.answer("조금 피곤해")
            assertEquals(DriverState.CAUTION, f.status.state)
        }
    }

    @Test fun recoveryRelapsePreservesHighRiskFloor() {
        val f = Fixture()
        f.reach(DriverState.HIGH_RISK)
        f.answer("괜찮아")
        f.answer("좀 졸려")
        assertEquals(DriverState.HIGH_RISK, f.status.state)
    }

    @Test fun ambiguousAnswersAndErrorsNeverChangeSafetyState() {
        val uncertain = listOf(
            RecognitionResult.Text("괜찮아 하지만 졸려"), RecognitionResult.Timeout,
            RecognitionResult.PermissionDenied, RecognitionResult.RecognitionFailure,
            RecognitionResult.ServiceUnavailable,
        )
        DriverState.entries.filter { it != DriverState.RESTING }.forEach { state ->
            uncertain.forEach { input ->
                val f = Fixture()
                f.reach(state)
                val confirmed = f.status.lastConfirmedAtMillis
                f.input(input)
                assertEquals(state, f.status.state)
                assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
                assertEquals(confirmed, f.status.lastConfirmedAtMillis)
                assertTrue(f.status.followUpRequired)
                assertEquals(0, f.status.consecutiveNoResponses)
            }
        }
    }

    @Test fun repeatedNoResponseIsUnconfirmedObservationNotSleep() {
        val f = Fixture()
        repeat(3) { f.input(RecognitionResult.NoResponse) }
        assertNull(f.status.state)
        assertEquals(3, f.status.consecutiveNoResponses)
        assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
        assertNull(f.status.lastConfirmedAtMillis)
        f.input(RecognitionResult.Timeout)
        assertEquals(0, f.status.consecutiveNoResponses)
        f.input(RecognitionResult.NoResponse)
        f.answer("잘 모르겠어")
        assertEquals(0, f.status.consecutiveNoResponses)
    }

    @Test fun uncertaintyBreaksConsecutiveRecoveryChecks() {
        listOf(RecognitionResult.NoResponse, RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable,
            RecognitionResult.Text("잘 모르겠어")).forEach {
            val f = Fixture()
            f.reach(DriverState.CAUTION)
            f.answer("괜찮아")
            f.input(it)
            f.answer("괜찮아")
            assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
            f.answer("괜찮아")
            assertEquals(DriverState.NORMAL, f.status.state)
        }
    }

    @Test fun parkingIsCheckedAtEntryAndDefaultsToUnknown() {
        val defaultManager = DefaultDriverStateManager(MonotonicClock { 0 })
        defaultManager.onRestStarted()
        assertNull(defaultManager.status.value.state)
        val f = Fixture()
        f.reach(DriverState.HIGH_RISK)
        listOf(SafeParking.UNKNOWN, SafeParking.NOT_PARKED).forEach {
            f.parking = it
            f.manager.onRestStarted()
            assertEquals(DriverState.HIGH_RISK, f.status.state)
        }
        f.parking = SafeParking.VERIFIED
        f.manager.onRestStarted()
        assertEquals(DriverState.RESTING, f.status.state)
        f.manager.onRestEnded()
        f.parking = SafeParking.NOT_PARKED
        f.manager.onRestStarted()
        assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
    }

    @Test fun parkingFailureDoesNotEnterRest() {
        val manager = DefaultDriverStateManager(MonotonicClock { 0 }, SafeParkingCheck { error("no vehicle status") })
        manager.onRestStarted()
        assertNull(manager.status.value.state)
    }

    @Test fun restIgnoresAnswersAndCannotResumeAutomatically() {
        val f = Fixture()
        f.reach(DriverState.RESTING)
        val resting = f.status
        f.answer("괜찮아")
        f.answer("너무 졸려")
        f.input(RecognitionResult.NoResponse)
        assertEquals(resting, f.status)
        f.manager.onRestEnded()
        assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
        f.answer("괜찮아")
        assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
        f.answer("괜찮아")
        assertEquals(DriverState.NORMAL, f.status.state)
    }

    @Test fun restEndOutsideRestAndRepeatedRestStartAreIdempotent() {
        val f = Fixture()
        f.manager.onRestEnded()
        assertNull(f.status.state)
        f.reach(DriverState.RESTING)
        val resting = f.status
        f.manager.onRestStarted()
        assertEquals(resting, f.status)
    }

    @Test fun duplicateAndStaleAnswersCannotConfirmRecoveryOrLowerRisk() {
        val f = Fixture()
        f.reach(DriverState.HIGH_RISK)
        f.answer("괜찮아")
        val recovering = f.status
        f.manager.onConditionAnswer(RecognitionResult.Text("괜찮아"), f.now)
        f.manager.onConditionAnswer(RecognitionResult.Text("괜찮아"), f.now - 1)
        assertEquals(recovering, f.status)
        f.answer("괜찮아")
        assertEquals(DriverState.NORMAL, f.status.state)
    }

    @Test fun restBoundaryRejectsPendingEarlierAnswers() {
        val f = Fixture()
        f.reach(DriverState.HIGH_RISK)
        f.parking = SafeParking.VERIFIED
        f.now = 100
        f.manager.onRestStarted()
        f.now = 200
        f.manager.onRestEnded()
        f.manager.onConditionAnswer(RecognitionResult.Text("괜찮아"), 150)
        assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
        f.answer("괜찮아")
        assertEquals(DriverState.RECOVERY_CHECK, f.status.state)
    }

    @Test fun invalidTimeFailsBeforeMutation() {
        val f = Fixture()
        val before = f.status
        try {
            f.manager.onConditionAnswer(RecognitionResult.Text("괜찮아"), -1)
            fail("negative time accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, f.status)
    }

    @Test fun noResponseNeverLowersAnyExistingRiskOrMarksItConfirmed() {
        DriverState.entries.filter { it != DriverState.RESTING }.forEach {
            val f = Fixture()
            f.reach(it)
            val confirmed = f.status.lastConfirmedAtMillis
            repeat(3) { f.input(RecognitionResult.NoResponse) }
            assertEquals(it, f.status.state)
            assertEquals(3, f.status.consecutiveNoResponses)
            assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
            assertEquals(confirmed, f.status.lastConfirmedAtMillis)
            assertTrue(f.status.followUpRequired)
        }
    }

    @Test fun recognitionErrorsCannotInitializeDriverAsNormalOrHighRisk() {
        val f = Fixture()
        listOf(RecognitionResult.Timeout, RecognitionResult.PermissionDenied,
            RecognitionResult.RecognitionFailure, RecognitionResult.ServiceUnavailable,
            RecognitionResult.Text("잘 모르겠어")).forEach {
            f.input(it)
            assertNull(f.status.state)
            assertNull(f.status.lastConfirmedAtMillis)
            assertEquals(ConditionReport.UNCONFIRMED, f.status.report)
        }
    }
}
