package com.example.pleosconnect.mock.integration

import com.example.pleosconnect.contract.integration.DriverProfileRepository
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Demo identities/preferences live only in memory. */
class FakeDriverProfileRepository(profile: DriverPreferences) : DriverProfileRepository {
    private val mutableProfile = MutableStateFlow<DriverPreferences?>(profile)
    override val selectedProfile = mutableProfile.asStateFlow()
    var failure: FailureReason? = null
    override suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit> {
        if (preferences.profileId != selectedProfile.value?.profileId) return OperationResult.Failure(FailureReason.STALE_REQUEST)
        failure?.let { return OperationResult.Failure(it) }
        mutableProfile.value = preferences
        return OperationResult.Success(Unit)
    }
}
