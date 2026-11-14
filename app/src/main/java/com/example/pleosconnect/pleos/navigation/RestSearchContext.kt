package com.example.pleosconnect.pleos.navigation

/** Local route binding by receipt time only. Not evidence of matching provider/native road geometry. */
data class RestSearchContext(val routeId: String, val routeRevision: Long,
    val location: NavigationLocation, val observedAtMillis: Long) {
    val routeCorrespondenceVerified: Boolean get() = false
    val headingUnitVerified: Boolean get() = false
    override fun toString() = "RestSearchContext(redacted,routeCorrespondenceVerified=false)"
}
