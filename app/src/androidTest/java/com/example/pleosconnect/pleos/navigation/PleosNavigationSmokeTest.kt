package com.example.pleosconnect.pleos.navigation

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.contract.logic.MonotonicClock
import com.example.pleosconnect.contract.model.OperationResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Read-only real SDK probe. NEVER requests, cancels, resumes or edits an actual route. */
@RunWith(AndroidJUnit4::class)
class PleosNavigationSmokeTest {
    @Test fun connectReadStateAndReleaseWithoutChangingAnyRoute() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val backend = PleosNaviBackend(InstrumentationRegistry.getInstrumentation().targetContext, scope)
        val states = AtomicInteger(); val destinations = AtomicInteger(); val waypoints = AtomicInteger()
        val observed = object : NavigationBackend by backend {
            override suspend fun connect(listener: (NavigationEvent) -> Unit) = backend.connect { event ->
                when (event) {
                    is NavigationEvent.RouteState -> states.incrementAndGet()
                    is NavigationEvent.Destination -> destinations.incrementAndGet()
                    is NavigationEvent.Waypoints -> waypoints.incrementAndGet()
                    else -> Unit
                }
                listener(event)
            }
        }
        val clock = MonotonicClock { SystemClock.elapsedRealtime() }
        val controller = PleosNavigationController(observed, clock, NavigationConsentGate(clock))
        try {
            controller.bindProfile("read-only-smoke")
            controller.connect()
            if (controller.status.value.connected) withTimeoutOrNull(6_000) {
                while (states.get() == 0) delay(100)
            }
            val connected = controller.status.value.connected
            val favorites = if (controller.status.value.readReady && controller.status.value.termsAccepted == true)
                controller.loadSavedDestinations() else null
            Log.i("DriveMateNaviProbe", "connected=$connected,failure=${controller.status.value.failure},stateCallbacks=${states.get()},destinationCallbacks=${destinations.get()},waypointCallbacks=${waypoints.get()},favoritesLoaded=${favorites is OperationResult.Success},favoritesCount=${controller.savedDestinations.value?.size}")
            // Existing native routes are never silently adopted by a newly connected app session.
            assertNull(controller.route.value)
        } finally {
            controller.disconnect(); scope.cancel()
        }
        assertFalse(controller.status.value.connected)
    }
}
