package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.logic.MonotonicClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NearbyDiscoveryControllerTest {
    private val fixture = RestSearchContext("fixture", 1,
        NavigationLocation(37.0, 127.0, 0.0, "fixture", null), 0)
    private val token = "a".repeat(64)
    @Test fun prepareNeverSendsAndConfirmationIsSingleUse() = runTest {
        var calls = 0
        val controller = NearbyDiscoveryController(this, MonotonicClock { testScheduler.currentTime },
            { fixture }, { true }, NearbyDiscoveryTransport { _, _ -> calls++; NearbyDiscoveryResult.Found(2) })
        controller.prepare(token); runCurrent(); assertEquals(0, calls)
        controller.confirmFromUi(); controller.confirmFromUi(); runCurrent()
        assertEquals(1, calls); assertEquals(2, controller.state.value.count)
    }
    @Test fun invalidTokenDisabledAndExpiredConsentNeverSend() = runTest {
        var calls = 0; var allowed = false
        val controller = NearbyDiscoveryController(this, MonotonicClock { testScheduler.currentTime },
            { fixture }, { allowed }, NearbyDiscoveryTransport { _, _ -> calls++; NearbyDiscoveryResult.Failed })
        controller.prepare(token); controller.confirmFromUi()
        allowed = true; controller.prepare("invalid"); controller.confirmFromUi()
        controller.prepare(token); advanceTimeBy(5_000); controller.confirmFromUi(); runCurrent()
        assertEquals(0, calls); assertFalse(controller.state.value.review)
    }
    @Test fun changedLocationBeforeConsentAndCancelledReviewNeverSend() = runTest {
        var current = fixture; var calls = 0
        val controller = NearbyDiscoveryController(this, MonotonicClock { 0 }, { current }, { true },
            NearbyDiscoveryTransport { _, _ -> calls++; NearbyDiscoveryResult.Failed })
        controller.prepare(token); current = fixture.copy(routeRevision = 2); controller.confirmFromUi()
        controller.prepare(token); controller.cancel(); controller.confirmFromUi(); runCurrent()
        assertEquals(0, calls)
    }
    @Test fun reviewCountdownExpiresWithoutSendingAndCanPrepareAgain() = runTest {
        var calls = 0
        val controller = NearbyDiscoveryController(this, MonotonicClock { testScheduler.currentTime },
            { fixture.copy(observedAtMillis = testScheduler.currentTime) }, { true },
            NearbyDiscoveryTransport { _, _ -> calls++; NearbyDiscoveryResult.Found(0) })
        controller.prepare(token); advanceTimeBy(10_000); runCurrent()
        assertEquals(20, controller.state.value.remainingSeconds)
        advanceTimeBy(20_000); runCurrent()
        assertFalse(controller.state.value.review); assertEquals(0, calls)
        controller.confirmFromUi(); runCurrent(); assertEquals(0, calls)
        controller.prepare(token); controller.confirmFromUi(); runCurrent()
        assertEquals(1, calls); assertEquals(0, controller.state.value.count)
    }
    @Test fun routeChangeDuringRequestDiscardsResult() = runTest {
        var current = fixture
        val result = CompletableDeferred<NearbyDiscoveryResult>()
        val controller = NearbyDiscoveryController(this, MonotonicClock { 0 }, { current }, { true },
            NearbyDiscoveryTransport { _, _ -> result.await() })
        controller.prepare(token); controller.confirmFromUi(); runCurrent()
        current = fixture.copy(routeRevision = 2); result.complete(NearbyDiscoveryResult.Found(3)); runCurrent()
        assertNull(controller.state.value.count); assertFalse(controller.state.value.busy)
    }
    @Test fun cancellationAndTimeoutDoNotPublishResultsOrRetry() = runTest {
        var calls = 0
        val controller = NearbyDiscoveryController(this, MonotonicClock { 0 }, { fixture }, { true },
            NearbyDiscoveryTransport { _, _ -> calls++; CompletableDeferred<NearbyDiscoveryResult>().await() })
        controller.prepare(token); controller.confirmFromUi(); runCurrent(); controller.cancel(); runCurrent()
        assertFalse(controller.state.value.busy)
        controller.prepare(token); controller.confirmFromUi(); runCurrent(); advanceTimeBy(20_000); runCurrent()
        assertFalse(controller.state.value.busy); assertNull(controller.state.value.count); assertEquals(2, calls)
    }
    @Test fun serviceFailureAndLostPermissionRemainFailures() = runTest {
        var allowed = true
        val controller = NearbyDiscoveryController(this, MonotonicClock { 0 }, { fixture }, { allowed },
            NearbyDiscoveryTransport { _, _ -> error("fixture failure") })
        controller.prepare(token); controller.confirmFromUi(); runCurrent()
        assertNull(controller.state.value.count); assertFalse(controller.state.value.busy)
        controller.prepare(token); allowed = false; controller.confirmFromUi(); runCurrent()
        assertFalse(controller.state.value.review)
    }
}
