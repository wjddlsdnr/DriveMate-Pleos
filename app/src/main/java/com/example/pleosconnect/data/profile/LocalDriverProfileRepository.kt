package com.example.pleosconnect.data.profile

import com.example.pleosconnect.contract.integration.DriverProfileRepository
import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class ProfileCatalogState(
    val ready: Boolean = false,
    val profiles: List<DriverPreferences> = emptyList(),
    val selectedId: String? = null,
    val failure: FailureReason? = null,
)

data class ProfileDraft(
    val displayName: String,
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val playlists: List<String> = emptyList(),
    val contentPreferences: List<String> = emptyList(),
    val voiceGuidancePreference: String? = null,
)

/** Single app-level owner. All disk work is serialized off the UI thread. No health or voice history. */
class LocalDriverProfileRepository(
    private val storage: ProfileStorage,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DriverProfileRepository {
    private val lock = Mutex()
    private val mutableCatalog = MutableStateFlow(ProfileCatalogState())
    val catalog = mutableCatalog.asStateFlow()
    private val mutableSelected = MutableStateFlow<DriverPreferences?>(null)
    override val selectedProfile = mutableSelected.asStateFlow()

    suspend fun load(): OperationResult<Unit> = transaction {
        if (catalog.value.ready) return@transaction OperationResult.Success(Unit)
        val snapshot = storage.load()
        require(valid(snapshot))
        publish(snapshot)
        OperationResult.Success(Unit)
    }
    suspend fun register(draft: ProfileDraft): OperationResult<DriverPreferences> = transaction {
        if (!catalog.value.ready) return@transaction unavailable()
        val value = draft.clean().let { DriverPreferences(newId(), it.displayName, genres = it.genres,
            artists = it.artists, playlists = it.playlists, contentPreferences = it.contentPreferences,
            voiceGuidancePreference = it.voiceGuidancePreference) }
        val next = ProfileSnapshot(catalog.value.profiles + value, value.profileId)
        if (!valid(next)) return@transaction invalid()
        commit(next)
        OperationResult.Success(value)
    }
    suspend fun select(profileId: String): OperationResult<Unit> = transaction {
        if (!catalog.value.ready) return@transaction unavailable()
        if (catalog.value.profiles.none { it.profileId == profileId }) return@transaction stale()
        commit(ProfileSnapshot(catalog.value.profiles, profileId))
        OperationResult.Success(Unit)
    }
    suspend fun update(profileId: String, draft: ProfileDraft): OperationResult<Unit> = transaction {
        if (!catalog.value.ready) return@transaction unavailable()
        val old = catalog.value.profiles.find { it.profileId == profileId } ?: return@transaction stale()
        val clean = draft.clean()
        val changed = old.copy(displayName = clean.displayName, genres = clean.genres, artists = clean.artists,
            playlists = clean.playlists, contentPreferences = clean.contentPreferences, voiceGuidancePreference = clean.voiceGuidancePreference)
        val next = ProfileSnapshot(catalog.value.profiles.map { if (it.profileId == profileId) changed else it }, catalog.value.selectedId)
        if (!valid(next)) return@transaction invalid()
        commit(next)
        OperationResult.Success(Unit)
    }
    suspend fun delete(profileId: String): OperationResult<Unit> = transaction {
        if (!catalog.value.ready) return@transaction unavailable()
        if (catalog.value.profiles.none { it.profileId == profileId }) return@transaction stale()
        commit(ProfileSnapshot(catalog.value.profiles.filterNot { it.profileId == profileId },
            catalog.value.selectedId.takeUnless { it == profileId }))
        OperationResult.Success(Unit)
    }
    override suspend fun savePreferences(preferences: DriverPreferences): OperationResult<Unit> = transaction {
        if (!catalog.value.ready) return@transaction unavailable()
        val current = selectedProfile.value ?: return@transaction stale()
        if (preferences.profileId != current.profileId) return@transaction stale()
        // A may update preferences, but cannot rename B-owned identity using an old snapshot.
        val changed = preferences.copy(displayName = current.displayName).immutable()
        val next = ProfileSnapshot(catalog.value.profiles.map { if (it.profileId == changed.profileId) changed else it }, current.profileId)
        if (!valid(next)) return@transaction invalid()
        commit(next)
        OperationResult.Success(Unit)
    }
    private fun commit(snapshot: ProfileSnapshot) {
        storage.save(snapshot) // Never publish before persistence succeeds.
        publish(snapshot)
    }
    private fun publish(snapshot: ProfileSnapshot) {
        val profiles = snapshot.profiles.map { it.immutable() }
        mutableSelected.value = profiles.find { it.profileId == snapshot.selectedId }
        mutableCatalog.value = ProfileCatalogState(true, profiles, snapshot.selectedId)
    }
    private suspend fun <T> transaction(block: () -> OperationResult<T>): OperationResult<T> = withContext(io) {
        lock.withLock {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableCatalog.update { it.copy(failure = FailureReason.SERVICE_UNAVAILABLE) }
                unavailable()
            }
        }
    }
    private fun valid(snapshot: ProfileSnapshot): Boolean = snapshot.profiles.size <= 20 &&
        snapshot.profiles.map { it.profileId }.distinct().size == snapshot.profiles.size &&
        (snapshot.selectedId == null || snapshot.profiles.any { it.profileId == snapshot.selectedId }) &&
        snapshot.profiles.all { p ->
            p.profileId.isNotBlank() && p.profileId.length <= 100 && p.displayName.isNotBlank() && p.displayName.length <= 60 &&
                listOf(p.genres, p.artists, p.playlists, p.contentPreferences).all { list ->
                    list.size <= 20 && list.all { it.isNotBlank() && it.length <= 100 && it.none(Char::isISOControl) }
                } && p.displayName.none(Char::isISOControl) &&
                (p.voiceGuidancePreference == null || p.voiceGuidancePreference.length <= 100 && p.voiceGuidancePreference.none(Char::isISOControl))
        }
    private fun ProfileDraft.clean() = copy(displayName = displayName.trim(),
        genres = genres.clean(), artists = artists.clean(), playlists = playlists.clean(), contentPreferences = contentPreferences.clean(),
        voiceGuidancePreference = voiceGuidancePreference?.trim()?.takeIf { it.isNotEmpty() })
    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    private fun DriverPreferences.immutable() = copy(genres = genres.toList(), artists = artists.toList(),
        playlists = playlists.toList(), contentPreferences = contentPreferences.toList())
    private fun unavailable() = OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE)
    private fun invalid() = OperationResult.Failure(FailureReason.INVALID_REQUEST)
    private fun stale() = OperationResult.Failure(FailureReason.STALE_REQUEST)
}
