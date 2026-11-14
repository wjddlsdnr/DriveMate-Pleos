package com.example.pleosconnect.pleos.navigation

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test

/** Read-only SDK callbacks; never logs place names/coordinates or requests a route. */
class VoiceRouteDiagnosticTest {
    @Test fun readGuidanceCallbacks(): Unit = runBlocking {
        withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val observed = ObservedVoiceRoute(com.example.pleosconnect.contract.logic.MonotonicClock {
                android.os.SystemClock.elapsedRealtime()
            })
            val backend = PleosNaviBackend(InstrumentationRegistry.getInstrumentation().targetContext, scope) { routing, mode, terms ->
                Log.i("DriveMateRouteRead", "sdkRouting=$routing,sdkMode=$mode,sdkTerms=$terms")
            }
            val controller = PleosNavigationController(backend, com.example.pleosconnect.contract.logic.MonotonicClock {
                android.os.SystemClock.elapsedRealtime()
            }, NavigationConsentGate(com.example.pleosconnect.contract.logic.MonotonicClock { android.os.SystemClock.elapsedRealtime() }))
            try {
                controller.connect()
                var previous: RestSearchContext? = null
                repeat(5) {
                    delay(5_000)
                    val current = controller.observedVoiceRoute.searchContext()
                    Log.i("DriveMateRouteRead", "controllerContext=${current != null},controllerIdChanged=${previous != null && current != null && previous!!.routeId != current.routeId},controllerRevisionChanged=${previous != null && current != null && previous!!.routeRevision != current.routeRevision}")
                    previous = current
                    controller.refresh()
                }
            } finally { controller.disconnect() }
            try {
                val result = backend.connect { event ->
                    val before = observed.route.value
                    observed.accept(event)
                    val after = observed.route.value
                    Log.i("DriveMateRouteRead", "routeIdentityChanged=${before != null && after != null && before.routeId != after.routeId},routeRevisionChanged=${before != null && after != null && before.revision != after.revision},routeLost=${before != null && after == null}")
                    val detail = when (event) {
                        is NavigationEvent.Location -> "locationCallback=true,valid=${event.value.valid()}"
                        is NavigationEvent.RouteState -> "active=${event.active},terms=${event.termsAccepted}"
                        is NavigationEvent.Destination -> "valid=${event.place.valid()},nameLength=${event.place.name.length},etaPositive=${event.remainingSeconds > 0}"
                        is NavigationEvent.Failed -> "failure=${event.reason}"
                        else -> event.javaClass.simpleName
                    }
                    Log.i("DriveMateRouteRead", detail)
                }
                Log.i("DriveMateRouteRead", "connection=$result")
                repeat(6) { backend.refresh(); delay(3_000) }
                Log.i("DriveMateRouteRead", "searchContextAvailable=${observed.searchContext() != null},routeCorrespondenceVerified=false,headingUnitVerified=false")
            } finally { backend.disconnect(); scope.cancel() }
        }
    }
}
