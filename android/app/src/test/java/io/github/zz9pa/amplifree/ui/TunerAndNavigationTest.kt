package io.github.zz9pa.amplifree.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.zz9pa.amplifree.BottomNavigation
import io.github.zz9pa.amplifree.SideNavigation
import io.github.zz9pa.amplifree.data.ConnectionState
import io.github.zz9pa.amplifree.data.HardwareDevice
import io.github.zz9pa.amplifree.data.HardwareState
import io.github.zz9pa.amplifree.data.TunerEngine
import io.github.zz9pa.amplifree.data.TunerState
import io.github.zz9pa.amplifree.ui.components.TopHardwareHeader
import io.github.zz9pa.amplifree.ui.screens.TunerScreen
import io.github.zz9pa.amplifree.ui.theme.AmplifreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class TunerScreenTest {
    @get:Rule val rule = createComposeRule()

    private val none = FriendlyStatus("", StatusKind.INFO)
    private val reading = TunerState(
        isTunerActive = true, noteName = "A", octave = 2, currentFrequency = 109.2f,
        centsOffset = -12f, signalDetected = true
    )

    @Test fun disconnectedShowsConnectInsteadOfTheGauge() {
        var connects = 0
        rule.setContent {
            AmplifreeTheme {
                TunerScreen(TunerState(), TunerEngine(), LinkStatus.DISCONNECTED, none, { connects++ })
            }
        }
        rule.onNodeWithTag("tuner_gauge").assertDoesNotExist()
        rule.onNodeWithText("Connect to amp").performClick()
        assertEquals(1, connects)
    }

    @Test fun gaugeDescribesTheCurrentReading() {
        rule.setContent {
            AmplifreeTheme { TunerScreen(reading, TunerEngine(), LinkStatus.READY, none, {}) }
        }
        val description = rule.onNodeWithTag("tuner_gauge").fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")
        assertTrue(description, "A2" in description && "109.2" in description && "-12" in description)
    }

    @Test fun tappingAStringPinsTheTunerToIt() {
        val engine = TunerEngine()
        rule.setContent {
            AmplifreeTheme { TunerScreen(reading, engine, LinkStatus.READY, none, {}) }
        }
        rule.onNodeWithTag("string_2").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, engine.state.value.activeStringIndex)
    }
}

/** At 200% font size the six string buttons wrap to two rows instead of being squeezed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp", fontScale = 2f)
class TunerScreenLargeFontTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stringsWrapToTwoRowsAndStayTappable() {
        rule.setContent {
            AmplifreeTheme {
                TunerScreen(TunerState(), TunerEngine(), LinkStatus.READY, FriendlyStatus("", StatusKind.INFO), {})
            }
        }
        val first = rule.onNodeWithTag("string_0").fetchSemanticsNode().boundsInRoot
        val fourth = rule.onNodeWithTag("string_3").fetchSemanticsNode().boundsInRoot
        assertTrue("fourth string should be on a lower row", fourth.top > first.bottom - 1f)
        rule.onNodeWithTag("string_3").assertHeightIsAtLeast(48.dp)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class NavigationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun bottomBarSelectsTabsWithBigTargets() {
        var picked = -1
        rule.setContent { AmplifreeTheme { BottomNavigation(selectedTab = 0) { picked = it } } }
        rule.onNodeWithTag("nav_tab_1").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, picked)
        rule.onNodeWithTag("nav_tab_2").performClick()
        assertEquals(2, picked)
        rule.onNodeWithTag("nav_tab_0").assertIsSelected()
    }

    @Test fun sideRailOffersTheSameTabs() {
        var picked = -1
        rule.setContent { AmplifreeTheme { SideNavigation(selectedTab = 2) { picked = it } } }
        rule.onNodeWithTag("nav_tab_2").assertIsSelected()
        rule.onNodeWithTag("nav_tab_0").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(0, picked)
    }

    @Test fun tabsAreReadOnceByTheirLabel() {
        rule.setContent { AmplifreeTheme { BottomNavigation(selectedTab = 0) {} } }
        // The icon is decorative, so the tab's spoken text is just its label.
        val texts = rule.onNodeWithTag("nav_tab_1").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.map { it.text }
        assertEquals(listOf("Tones"), texts)
        assertEquals(null, rule.onNodeWithTag("nav_tab_1").fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ContentDescription))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class HeaderTest {
    @get:Rule val rule = createComposeRule()

    private val amp = HardwareDevice("Test Amp", "test", "00:00:00:00:00:00")

    @Test fun connectionChipIsOneDescribedButtonAtLeast48dpTall() {
        var opened = 0
        rule.setContent {
            AmplifreeTheme {
                TopHardwareHeader(
                    HardwareState(ConnectionState.CONNECTED, amp), ready = true,
                    toneName = "Crunch Rhythm", onOpenConnectionDialog = { opened++ }
                )
            }
        }
        val node = rule.onNodeWithTag("connection_chip")
        node.assertHasClickAction().assertHeightIsAtLeast(48.dp)
        val description = node.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertEquals("Amp connection: Test Amp", description)
        node.performClick()
        assertEquals(1, opened)
    }

    @Test fun disconnectedChipInvitesATap() {
        rule.setContent {
            AmplifreeTheme {
                TopHardwareHeader(HardwareState(), ready = false, toneName = null, onOpenConnectionDialog = {})
            }
        }
        rule.onNodeWithText("Tap to connect").assertIsDisplayed()
    }
}
