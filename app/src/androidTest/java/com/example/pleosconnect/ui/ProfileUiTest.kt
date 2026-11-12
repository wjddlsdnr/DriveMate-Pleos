package com.example.pleosconnect.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.pleosconnect.MainActivity
import androidx.activity.compose.setContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.pleosconnect.data.profile.*
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses a fresh cache fixture, never the production no-backup profile file. */
@RunWith(AndroidJUnit4::class)
class ProfileUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: DriveMateViewModel
    private lateinit var directory: File
    private lateinit var file: File
    @Volatile private var failSave = false
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "profile-ui-${UUID.randomUUID()}")
        check(directory.mkdir())
        file = File(directory, "profiles.bin")
        val disk = FileProfileStorage(file)
        val storage = object : ProfileStorage by disk {
            override fun save(snapshot: ProfileSnapshot) { if (failSave) throw IOException("injected test failure"); disk.save(snapshot) }
        }
        model = DriveMateViewModel(catalog = LocalDriverProfileRepository(storage))
        compose.activityRule.scenario.onActivity { host -> host.setContent {
            val state by model.state.collectAsState()
            PleosConnectTheme {
                DriveMateScreen(state, model::act, { model.reset(it) }, {}, model::registerProfile, model::selectProfile,
                    model::saveProfile, model::deleteProfile, model::reloadProfiles)
            }
        } }
        model.setForeground(true)
        compose.waitUntil(20_000) {
            model.state.value.profilesReady && model.state.value.foreground &&
                runCatching { compose.onAllNodesWithTag("new-profile").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }
    @After fun cleanup() {
        runBlocking { model.closeAndJoin() }
        File(directory, "profiles.bin.new").delete(); file.delete(); directory.delete()
    }
    private fun register(name: String) {
        compose.onNodeWithTag("new-profile").performScrollTo().performClick()
        compose.onNodeWithTag("profile-name").performTextInput(name)
        compose.onNodeWithTag("profile-genres").performScrollTo().performTextInput("재즈, 발라드")
        compose.onNodeWithTag("save-profile").performClick()
        compose.waitUntil(10_000) { model.state.value.preferences?.displayName == name && !model.state.value.profileWorking }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("save-profile").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun registerAndEditPersistAcrossNewRepository() {
        compose.onNodeWithTag("start-simulation").assertIsNotEnabled()
        register("테스트 프로필")
        compose.onNodeWithTag("start-simulation").assertIsEnabled()
        compose.onNodeWithText("프로필·취향 수정").performScrollTo().performClick()
        compose.onNodeWithTag("profile-name").performTextReplacement("수정된 프로필")
        compose.onNodeWithTag("save-profile").performClick()
        compose.waitUntil(10_000) { model.state.value.preferences?.displayName == "수정된 프로필" }
        val reloaded = LocalDriverProfileRepository(FileProfileStorage(file))
        runBlocking { reloaded.load() }
        assertEquals("수정된 프로필", reloaded.selectedProfile.value!!.displayName)
        assertEquals(listOf("재즈", "발라드"), reloaded.selectedProfile.value!!.genres)
    }
    @Test fun selectAndDeleteDoNotLeakOrAutoSelectOtherProfile() {
        register("테스트 첫 사용자")
        val firstId = model.state.value.selectedProfileId!!
        register("테스트 두번째 사용자")
        compose.onNodeWithTag("select-profile").performScrollTo().performClick()
        compose.onNodeWithTag("profile-choice-$firstId").performClick()
        compose.waitUntil(10_000) { model.state.value.selectedProfileId == firstId && !model.state.value.profileWorking }
        compose.onNodeWithText("선택한 프로필 삭제").performScrollTo().performClick()
        compose.onNodeWithText("삭제", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { model.state.value.selectedProfileId == null && !model.state.value.profileWorking }
        compose.onNodeWithTag("start-simulation").assertIsNotEnabled()
        assertEquals(1, model.state.value.profiles.size)
    }
    @Test fun saveFailureKeepsEditorAndDoesNotClaimSuccess() {
        failSave = true
        compose.onNodeWithTag("new-profile").performScrollTo().performClick()
        compose.onNodeWithTag("profile-name").performTextInput("실패 테스트")
        compose.onNodeWithTag("save-profile").performClick()
        compose.waitUntil(10_000) { model.state.value.profileFailure != null && !model.state.value.profileWorking }
        compose.onNodeWithTag("profile-name").assertTextContains("실패 테스트")
        assertNull(model.state.value.selectedProfileId)
        assertFalse(file.exists())
        failSave = false
        compose.onNodeWithTag("save-profile").performClick()
        compose.waitUntil(10_000) { model.state.value.preferences?.displayName == "실패 테스트" }
    }
}
