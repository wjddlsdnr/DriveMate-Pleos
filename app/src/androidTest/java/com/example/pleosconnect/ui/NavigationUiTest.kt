package com.example.pleosconnect.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.pleosconnect.MainActivity
import com.example.pleosconnect.contract.integration.RouteProgress
import com.example.pleosconnect.integration.LiveNavigationUiState
import com.example.pleosconnect.pleos.navigation.NavigationPlace
import com.example.pleosconnect.ui.theme.PleosConnectTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

/** Callback-only fixture: no actual NavigationBackend is connected by these tests. */
@RunWith(AndroidJUnit4::class)
class NavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val destinations = mutableListOf<NavigationPlace>()
    private val resumes = AtomicInteger()
    @Before fun setup() {
        compose.activityRule.scenario.onActivity { host -> host.setContent { PleosConnectTheme {
            DriveMateScreen(DriveMateUiState(foreground = true, localProfiles = true, selectedProfileId = "fixture", liveNavigation = true),
                {}, {}, {}, navigationState = LiveNavigationUiState(available = true, enabled = true, connected = true,
                    readReady = true, termsAccepted = true, route = RouteProgress("fixture-route", 1, 60.minutes, 0),
                    savedDestinations = listOf(NavigationPlace("테스트 즐겨찾기", 37.4, 127.1, poiId = "fixture-id", poiSubId = "2"))),
                onLiveDestination = { destinations += it }, onLiveResume = { resumes.incrementAndGet() })
        } } }
        compose.waitUntil(20_000) {
            runCatching { compose.onAllNodesWithTag("live-destination").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }
    @Test fun realModeDisablesMockDriverControls() {
        compose.onNodeWithTag("simulation-banner").assertTextEquals("실제 내비 연동 점검")
        compose.onNodeWithTag("start-simulation").assertIsNotEnabled()
        compose.onNodeWithTag("scenario-menu").assertIsNotEnabled()
        assertTrue(destinations.isEmpty())
    }
    @Test fun coordinatesMustBeValidAndOnlyExplicitConfirmationRequestsDestination() {
        compose.onNodeWithTag("live-destination").performScrollTo().performClick()
        compose.onNodeWithTag("live-destination-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("live-place-name").performTextInput("테스트 목적지")
        compose.onNodeWithTag("live-latitude").performScrollTo().performTextInput("999")
        compose.onNodeWithTag("live-longitude").performScrollTo().performTextInput("127.1")
        compose.onNodeWithTag("live-destination-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("live-latitude").performScrollTo().performTextReplacement("37.4")
        assertTrue(destinations.isEmpty())
        compose.onNodeWithTag("live-destination-confirm").performClick()
        compose.runOnIdle {
            assertEquals(1, destinations.size)
            assertEquals("테스트 목적지", destinations.single().name)
            assertEquals(37.4, destinations.single().latitude, 0.00001)
        }
    }
    @Test fun cancelDoesNotRequestRouteAndResumeNeedsSeparateConfirmation() {
        compose.onNodeWithTag("live-destination").performScrollTo().performClick()
        compose.onNodeWithText("취소").performClick()
        assertTrue(destinations.isEmpty())
        compose.onNodeWithTag("live-resume").performScrollTo().performClick()
        assertEquals(0, resumes.get())
        compose.onNodeWithText("취소").performClick()
        assertEquals(0, resumes.get())
        compose.onNodeWithTag("live-resume").performScrollTo().performClick()
        compose.onNodeWithText("경로 재요청에 동의").performClick()
        compose.runOnIdle { assertEquals(1, resumes.get()) }
    }
    @Test fun choosingFavoriteDoesNotMutateUntilUserConfirmsAndKeepsPoiMetadata() {
        compose.onNodeWithTag("choose-navigation-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-favorite-0").performClick()
        assertTrue(destinations.isEmpty())
        compose.onNodeWithText("취소").performClick()
        assertTrue(destinations.isEmpty())
        compose.onNodeWithTag("choose-navigation-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("navigation-favorite-0").performClick()
        compose.onNodeWithTag("confirm-navigation-favorite").performClick()
        compose.runOnIdle {
            assertEquals(1, destinations.size)
            assertEquals("fixture-id", destinations.single().poiId)
            assertEquals("2", destinations.single().poiSubId)
        }
    }
}
