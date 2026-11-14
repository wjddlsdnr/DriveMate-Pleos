package com.example.pleosconnect.pleos.navigation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Connectivity only. No credentials, coordinates, provider calls or vehicle setters. */
@RunWith(AndroidJUnit4::class)
class NearbyRelayDiagnosticTest {
    @Test fun unauthenticatedHealthReachesRelay() {
        val connection = URL("http://10.0.2.2:18765/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            Log.i("NearbyRelayProbe", "healthHttpStatus=$status")
            assertEquals(401, status)
        } finally { connection.disconnect() }
    }
}
