package com.example.pleosconnect.pleos.navigation

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.contract.model.OperationResult
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manual opt-in only. Caller must obtain user consent for this single SDK position -> TMAP request.
 * Does not exercise or bypass the app UI consent gate; isolates actual SDK/HTTP integration.
 */
class NearbyLiveRequestTest {
    @Test fun oneExplicitlyAuthorizedLocationRequest(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowLocationToTmap") == "one-request")
        val token = args.getString("relaySessionToken").orEmpty()
        require(token.matches(Regex("[a-f0-9]{64}"))) { "Session credential missing" }
        val location = withContext(Dispatchers.Main) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val backend = PleosNaviBackend(InstrumentationRegistry.getInstrumentation().targetContext, scope)
            val received = CompletableDeferred<NavigationLocation>()
            try {
                val connected = backend.connect { event ->
                    if (event is NavigationEvent.Location && event.value.valid()) received.complete(event.value)
                }
                assertTrue(connected is OperationResult.Success)
                backend.refresh()
                withTimeout(10_000) { received.await() }
            } finally { backend.disconnect(); scope.cancel() }
        }
        val result = DebugNearbyTransport().discover(NearbySearchConsentGate.Request(location.latitude, location.longitude), token)
        when (result) {
            is NearbyDiscoveryResult.Found -> Log.i("NearbyRelayProbe", "liveDiscoveryCount=${result.count},safeCandidates=0")
            NearbyDiscoveryResult.Failed -> Log.i("NearbyRelayProbe", "liveDiscoveryFailed=true")
        }
        assertTrue("Actual SDK/HTTP request did not return discoveries", result is NearbyDiscoveryResult.Found)
    }
}
