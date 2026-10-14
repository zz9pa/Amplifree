package io.github.zz9pa.amplifree.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.zz9pa.amplifree.ui.screens.ToneBrowserScreen
import io.github.zz9pa.amplifree.ui.theme.AmplifreeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class ToneBrowserScreenTest {
    @get:Rule val rule = createComposeRule()

    private val tones = listOf(0 to "Clean Sparkle", 1 to "Crunch Rhythm", 2 to "Lead Boost")
    private val none = FriendlyStatus("", StatusKind.INFO)

    @Composable
    private fun Screen(
        presets: List<Pair<Int, String>> = tones,
        link: LinkStatus = LinkStatus.READY,
        scan: Pair<Int, Int>? = null,
        status: FriendlyStatus = none,
        onConnect: () -> Unit = {},
        onRescan: () -> Unit = {},
        onLoad: (Int, String) -> Unit = { _, _ -> },
        onRename: (Int, String) -> Unit = { _, _ -> }
    ) = AmplifreeTheme {
        ToneBrowserScreen(presets, "Crunch Rhythm", link, scan, status, onRescan, onLoad, onConnect, onRename)
    }

    @Test fun disconnectedWithNoTonesOffersConnect() {
        var connects = 0
        rule.setContent { Screen(presets = emptyList(), link = LinkStatus.DISCONNECTED, onConnect = { connects++ }) }
        rule.onNodeWithText("Amp not connected").assertIsDisplayed()
        rule.onNodeWithText("Connect to amp").assertHasClickAction().performClick()
        assertEquals(1, connects)
    }

    @Test fun connectingShowsProgressAndTheFriendlyStatus() {
        rule.setContent {
            Screen(
                presets = emptyList(), link = LinkStatus.CONNECTING,
                status = FriendlyStatus("Getting your amp ready…", StatusKind.BUSY)
            )
        }
        rule.onNodeWithText("Connecting to your amp…").assertIsDisplayed()
        rule.onNodeWithText("Getting your amp ready…").assertIsDisplayed()
    }

    @Test fun scanningShowsAProgressBarAndCount() {
        rule.setContent { Screen(presets = emptyList(), scan = 3 to 20) }
        rule.onNodeWithTag("tone_scan_progress").assertIsDisplayed()
        rule.onNodeWithText("Reading tones from your amp… 3 of 20").assertIsDisplayed()
        rule.onNodeWithText("Waiting for your amp's tone list…").assertIsDisplayed()
        rule.onNodeWithTag("tone_refresh").assertIsNotEnabled()
    }

    @Test fun emptyListHasAnEmptyStateWithRefresh() {
        var rescans = 0
        rule.setContent { Screen(presets = emptyList(), onRescan = { rescans++ }) }
        rule.onNodeWithText("No tones to show yet").assertIsDisplayed()
        rule.onAllNodesWithText("Refresh").filter(hasClickAction()).onFirst().performClick()
        assertEquals(1, rescans)
    }

    @Test fun staleListWhileDisconnectedShowsBannerAndRowsCannotBeLoaded() {
        var loads = 0
        rule.setContent { Screen(link = LinkStatus.DISCONNECTED, onLoad = { _, _ -> loads++ }) }
        rule.onNodeWithText("Not connected. Connect to load a tone.").assertIsDisplayed()
        rule.onNodeWithText("Lead Boost").assertIsNotEnabled()
        assertEquals(0, loads)
    }

    @Test fun tappingARowLoadsThatTone() {
        var loaded: Pair<Int, String>? = null
        rule.setContent { Screen(onLoad = { i, n -> loaded = i to n }) }
        rule.onNodeWithText("Lead Boost").assertHasClickAction().performClick()
        assertEquals(2 to "Lead Boost", loaded)
    }

    @Test fun searchFiltersAndNoMatchCanBeCleared() {
        rule.setContent { Screen() }
        rule.onNodeWithTag("tone_search").performTextInput("crunch")
        rule.onNodeWithText("Clean Sparkle").assertDoesNotExist()
        rule.onNodeWithText("Crunch Rhythm").assertIsDisplayed()
        rule.onNodeWithTag("tone_search").performTextClearance()
        rule.onNodeWithTag("tone_search").performTextInput("zzz")
        rule.onNodeWithText("No matching tones").assertIsDisplayed()
        rule.onAllNodesWithText("Clear search").filter(hasClickAction()).onFirst().performClick()
        rule.onNodeWithText("Clean Sparkle").assertIsDisplayed()
    }

    @Test fun searchTextSurvivesRecreation() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Screen() }
        rule.onNodeWithTag("tone_search").performTextInput("lead")
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("tone_search").assertTextContains("lead")
        rule.onNodeWithText("Clean Sparkle").assertDoesNotExist()
        rule.onNodeWithText("Lead Boost").assertIsDisplayed()
    }

    @Test fun rowsAndButtonsAreAtLeast48dpHigh() {
        rule.setContent { Screen() }
        rule.onNodeWithText("Lead Boost").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("tone_refresh").assertHeightIsAtLeast(48.dp)
    }

    // ---- renaming ---------------------------------------------------------------

    @Test fun renameIsUnavailableWhileTheAmpIsAway() {
        rule.setContent { Screen(link = LinkStatus.DISCONNECTED) }
        rule.onNodeWithTag("rename_1").assertIsNotEnabled()
    }
}
