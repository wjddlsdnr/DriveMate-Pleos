package com.example.pleosconnect.pleos.navigation

import com.example.pleosconnect.contract.integration.RestStopSearch

/** B-only provider evidence. A name match, heading or distance alone cannot issue this proof. */
data class RestRouteEvidence(
    val search: RestStopSearch,
    val place: NavigationPlace,
    val exSourceId: String,
    val tmapSourceId: String,
    val verificationSource: String,
    val coordinateSystemVerified: Boolean,
    val identityVerified: Boolean,
    val checkedAtMillis: Long,
    val validUntilMillis: Long,
) {
    internal fun validFor(request: RestStopSearch, target: NavigationPlace, nowMillis: Long): Boolean =
        search == request && place == target && target.valid() && coordinateSystemVerified && identityVerified &&
            listOf(exSourceId, tmapSourceId, verificationSource).all { value ->
                value.isNotBlank() && value.length <= 300 && value.none(Char::isISOControl)
            } && checkedAtMillis >= 0 && nowMillis >= checkedAtMillis &&
            validUntilMillis > checkedAtMillis && validUntilMillis - checkedAtMillis in 1..60_000 &&
            nowMillis < validUntilMillis
}
