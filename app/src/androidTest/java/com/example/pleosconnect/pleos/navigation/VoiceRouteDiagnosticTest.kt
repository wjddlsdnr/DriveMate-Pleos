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
            val backend = PleosNaviBackend(InstrumentationRegistry.getInstrumentation().targetContext, scope) { routing, mode, terms ->
                Log.i("DriveMateRouteRead", "sdkRouting=$routing,sdkMode=$mode,sdkTerms=$terms")
            }
            try {
                val result = backend.connect { event ->
                    val detail = when (event) {
                        is NavigationEvent.RouteState -> "active=${event.active},terms=${event.termsAccepted}"
                        is NavigationEvent.Destination -> "valid=${event.place.valid()},nameLength=${event.place.name.length},etaPositive=${event.remainingSeconds > 0}"
                        is NavigationEvent.Failed -> "failure=${event.reason}"
                        else -> event.javaClass.simpleName
                    }
                    Log.i("DriveMateRouteRead", detail)
                }
                Log.i("DriveMateRouteRead", "connection=$result")
                repeat(3) { backend.refresh(); delay(3_000) }
            } finally { backend.disconnect(); scope.cancel() }
        }
    }
}
