package com.example.pleosconnect.data.profile

import com.example.pleosconnect.contract.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDriverProfileRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun file() = File(folder.root, "profiles.bin")
    private fun TestScope.repository(storage: ProfileStorage = FileProfileStorage(file())) =
        LocalDriverProfileRepository(storage, StandardTestDispatcher(testScheduler))
    private fun <T> OperationResult<T>.value(): T = (this as OperationResult.Success<T>).value

    @Test fun allPreferencesAndSelectedIdentitySurviveNewRepositoryInstance() = runTest {
        val first = repository(); first.load().value()
        val created = first.register(ProfileDraft("테스트 운전자", listOf("재즈", "발라드"), listOf("테스트 가수"),
            listOf("출근길"), listOf("과학", "뉴스"), "간단한 안내")).value()
        first.savePreferences(created.copy(previousMode = DrivingMode.MUSIC)).value()
        val second = repository(); second.load().value()
        assertEquals(created.copy(previousMode = DrivingMode.MUSIC), second.selectedProfile.value)
        assertEquals(created.profileId, second.catalog.value.selectedId)
    }
    @Test fun duplicateNamesUseIndependentStableIdsAndDeletionNeverAutoSelectsOtherDriver() = runTest {
        val repo = repository(); repo.load()
        val first = repo.register(ProfileDraft("같은 이름")).value()
        val second = repo.register(ProfileDraft("같은 이름")).value()
        assertNotEquals(first.profileId, second.profileId)
        repo.delete(second.profileId).value()
        assertNull(repo.selectedProfile.value)
        assertEquals(listOf(first), repo.catalog.value.profiles)
        val loaded = repository(); loaded.load()
        assertNull(loaded.selectedProfile.value)
        loaded.select(first.profileId).value()
        assertEquals(first, loaded.selectedProfile.value)
    }
    @Test fun staleDriverCannotSaveAfterSwitchAndAUpdateCannotRenameIdentity() = runTest {
        val repo = repository(); repo.load()
        val first = repo.register(ProfileDraft("첫 사용자")).value()
        val second = repo.register(ProfileDraft("두번째 사용자")).value()
        assertEquals(OperationResult.Failure(FailureReason.STALE_REQUEST), repo.savePreferences(first.copy(genres = listOf("다른 취향"))))
        repo.update(second.profileId, ProfileDraft("새 별명")).value()
        repo.savePreferences(second.copy(previousMode = DrivingMode.QUIET)).value()
        assertEquals("새 별명", repo.selectedProfile.value!!.displayName)
        assertEquals(DrivingMode.QUIET, repo.selectedProfile.value!!.previousMode)
        assertTrue(repo.catalog.value.profiles.first().genres.isEmpty())
    }
    @Test fun writeFailureDoesNotPublishOrErasePreviouslyStoredProfile() = runTest {
        val disk = FileProfileStorage(file())
        var fail = false
        val storage = object : ProfileStorage by disk {
            override fun save(snapshot: ProfileSnapshot) { if (fail) throw IOException("test failure"); disk.save(snapshot) }
        }
        val repo = repository(storage); repo.load()
        val original = repo.register(ProfileDraft("보존 대상")).value()
        val bytes = file().readBytes()
        fail = true
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), repo.update(original.profileId, ProfileDraft("실패한 수정")))
        assertEquals(original, repo.selectedProfile.value)
        assertArrayEquals(bytes, file().readBytes())
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), repo.delete(original.profileId))
        assertEquals(original, repo.selectedProfile.value)
        fail = false
        repo.update(original.profileId, ProfileDraft("성공한 수정")).value()
        assertNull(repo.catalog.value.failure)
    }
    @Test fun corruptFileBlocksWritesAndPreservesOriginalBytes() = runTest {
        val broken = byteArrayOf(1, 2, 3, 4, 5)
        file().writeBytes(broken)
        val repo = repository()
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), repo.load())
        assertFalse(repo.catalog.value.ready)
        assertEquals(OperationResult.Failure(FailureReason.SERVICE_UNAVAILABLE), repo.register(ProfileDraft("덮어쓰지 않음")))
        assertArrayEquals(broken, file().readBytes())
    }
    @Test fun invalidNamesAndOversizeListsDoNotCreateStorage() = runTest {
        val repo = repository(); repo.load()
        for (draft in listOf(ProfileDraft(" "), ProfileDraft("x".repeat(61)), ProfileDraft("이름\n줄바꿈"),
            ProfileDraft("이름", genres = List(21) { "장르$it" }), ProfileDraft("이름", artists = listOf("x".repeat(101))))) {
            assertEquals(OperationResult.Failure(FailureReason.INVALID_REQUEST), repo.register(draft))
        }
        assertFalse(file().exists())
        assertTrue(repo.catalog.value.profiles.isEmpty())
    }
    @Test fun profileLimitAndInputCleanupAreEnforced() = runTest {
        val repo = repository(); repo.load()
        val first = repo.register(ProfileDraft("  별명  ", listOf(" 재즈 ", "재즈", ""))).value()
        assertEquals("별명", first.displayName)
        assertEquals(listOf("재즈"), first.genres)
        repeat(19) { repo.register(ProfileDraft("프로필$it")).value() }
        assertEquals(OperationResult.Failure(FailureReason.INVALID_REQUEST), repo.register(ProfileDraft("초과")))
        assertEquals(20, repo.catalog.value.profiles.size)
    }
    @Test fun concurrentRegistrationAndRepeatedLoadDoNotLoseCommittedProfiles() = runTest {
        val repo = repository(); repo.load()
        coroutineScope { repeat(10) { index -> launch { repo.register(ProfileDraft("운전자$index")).value() } } }
        repo.load().value()
        assertEquals(10, repo.catalog.value.profiles.size)
        val loaded = repository(); loaded.load().value()
        assertEquals(repo.catalog.value, loaded.catalog.value)
    }
    @Test fun failedAtomicReplacementKeepsExistingSnapshot() = runTest {
        val disk = FileProfileStorage(file())
        val original = ProfileSnapshot(listOf(DriverPreferences("A", "이름")), "A")
        disk.save(original)
        // The temporary path is deliberately a directory, so opening it cannot replace the old file.
        val obstacle = File(folder.root, "profiles.bin.new")
        assertTrue(obstacle.mkdir())
        try { disk.save(ProfileSnapshot()); fail("Expected storage failure") } catch (_: IOException) { }
        assertEquals(original, disk.load())
    }
    @Test fun uncommittedTemporaryFileIsNotTreatedAsSavedProfile() = runTest {
        File(folder.root, "profiles.bin.new").writeText("incomplete write")
        val repo = repository(); repo.load().value()
        assertTrue(repo.catalog.value.profiles.isEmpty())
        assertNull(repo.selectedProfile.value)
    }
}
