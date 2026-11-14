package com.example.pleosconnect.data.media

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native discovery/connect/browse only. Never sends a playback command. */
@RunWith(AndroidJUnit4::class)
class MediaDiscoveryTest {
    @Test fun inspectInstalledServicesWithoutPlayback() = runBlocking {
        withContext(Dispatchers.Main) {
            val gateway = AndroidMediaServiceGateway(InstrumentationRegistry.getInstrumentation().targetContext)
            val apps = gateway.apps()
            assertEquals(apps.size, apps.distinctBy { it.id }.size)
            Log.i("DriveMateB5", "discovered=${apps.size},playbackRequests=0")
            for (app in apps) {
                var phase = "connect"
                try {
                    val connection = gateway.connect(app)
                    phase = "browse"
                    Log.i("DriveMateB5", "service=${app.id},connected=true,playbackRequests=0")
                    val entries = gateway.children(connection.root)
                    assertTrue(entries.size <= 30)
                    Log.i("DriveMateB5", "service=${app.id},connected=true,entries=${entries.size},playbackRequests=0")
                } catch (e: Exception) {
                    Log.i("DriveMateB5", "service=${app.id},phase=$phase,completed=false,error=${e.javaClass.simpleName},playbackRequests=0")
                } finally { gateway.disconnect() }
            }
        }
    }
}
