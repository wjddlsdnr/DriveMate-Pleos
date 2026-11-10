package com.example.pleosconnect.contract.model

/** App-owned contracts, not Pleos SDK types. No state transition logic in Phase 1. */
enum class DriverState { NORMAL, CAUTION, WARNING, HIGH_RISK, RESTING, RECOVERY_CHECK }

enum class DrivingMode { QUIET, MUSIC, COMPANION, NEWS_AUDIO }

/** Unconfirmed observation is independent of the six-state safety workflow. */
enum class ConditionReport { NORMAL, MILD_FATIGUE, PERSISTENT_FATIGUE, SEVERE, UNCONFIRMED }

data class DriverStatus(
    val state: DriverState? = null,
    val report: ConditionReport = ConditionReport.UNCONFIRMED,
    val lastConfirmedAtMillis: Long? = null,
    val consecutiveNoResponses: Int = 0,
    val followUpRequired: Boolean = true,
    val mode: DrivingMode? = null,
)

/** Minimal A-facing profile view. B owns identity verification and persistent storage. */
data class DriverPreferences(
    val profileId: String,
    val displayName: String,
    val previousMode: DrivingMode? = null,
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<String> = emptyList(),
    val contentPreferences: List<String> = emptyList(),
    val voiceGuidancePreference: String? = null,
)

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>
    data class Failure(val reason: FailureReason) : OperationResult<Nothing>
}

enum class FailureReason {
    UNSUPPORTED, PERMISSION_DENIED, SERVICE_UNAVAILABLE, TIMEOUT,
    CONSENT_REQUIRED, MANUAL_OVERRIDE, STALE_REQUEST, INVALID_REQUEST,
}

enum class ConsentScope { DESTINATION, REST_WAYPOINT, RESUME_ROUTE, VEHICLE_ASSIST, RESTORE_VEHICLE }

/** Evidence of an explicit answer, bound to one proposed action. Not an authorization token.
 * Implementations must verify scope, target, freshness, current restrictions and revocation.
 */
data class DriverConsent(
    val scope: ConsentScope,
    val targetId: String,
    val confirmedAtMillis: Long,
)
