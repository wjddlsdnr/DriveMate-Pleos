package com.example.pleosconnect.pleos.navigation

import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Emulator-only loopback relay; never logs body, coordinates, credential or exceptions. */
class DebugNearbyTransport : NearbyDiscoveryTransport {
    override suspend fun discover(request: NearbySearchConsentGate.Request, token: String): NearbyDiscoveryResult =
        withContext(Dispatchers.IO) {
            val connection = URL("http://10.0.2.2:18765/v1/nearby").openConnection() as HttpURLConnection
            try {
                ensureActive()
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5_000; connection.readTimeout = 15_000
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("X-DriveMate-Location-Consent", "current-location-to-tmap")
                val body = JSONObject().put("latitude", request.latitude).put("longitude", request.longitude)
                    .put("radiusKm", request.radiusKm).toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { ensureActive(); it.write(body) }
                val responseCode = connection.responseCode
                android.util.Log.i("NearbyRelayProbe", "nearbyHttpStatus=$responseCode")
                if (responseCode != 200) return@withContext NearbyDiscoveryResult.Failed
                val bytes = connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() < 65_537) {
                        ensureActive()
                        val size = input.read(buffer, 0, minOf(buffer.size, 65_537 - output.size()))
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                    output.toByteArray()
                }
                ensureActive()
                if (bytes.size > 65_536) return@withContext NearbyDiscoveryResult.Failed
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val found = json.getJSONArray("discoveries")
                if (json.getInt("version") != 1 || json.getString("status") != "DiscoveryOnly" ||
                    json.getJSONArray("safeCandidates").length() != 0 || found.length() > 20)
                    return@withContext NearbyDiscoveryResult.Failed
                for (index in 0 until found.length()) {
                    val item = found.getJSONObject(index)
                    if (item.getBoolean("directionVerified") || item.getBoolean("accessVerified") || item.getBoolean("parkingVerified"))
                        return@withContext NearbyDiscoveryResult.Failed
                }
                NearbyDiscoveryResult.Found(found.length())
            } catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                val category = when (failure) {
                    is java.net.SocketTimeoutException -> "TIMEOUT"
                    is java.net.ConnectException -> "CONNECT"
                    is java.net.UnknownHostException -> "HOST"
                    is org.json.JSONException -> "RESPONSE_SCHEMA"
                    is java.io.IOException -> "IO"
                    is SecurityException -> "PERMISSION"
                    else -> "SERVICE"
                }
                android.util.Log.i("NearbyRelayProbe", "failure=$category")
                NearbyDiscoveryResult.Failed
            }
            finally { connection.disconnect() }
        }
}
