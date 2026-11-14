package com.example.pleosconnect.pleos.navigation

/** Development relay is unavailable in release. */
class DebugNearbyTransport : NearbyDiscoveryTransport {
    override suspend fun discover(request: NearbySearchConsentGate.Request, token: String) = NearbyDiscoveryResult.Failed
}
