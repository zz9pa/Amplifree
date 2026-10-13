package io.github.zz9pa.amplifree.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.zz9pa.amplifree.TestSupport
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.ui.screens.SignalChainEditorScreen
import io.github.zz9pa.amplifree.ui.theme.AmplifreeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Editor behaviour on a phone-sized window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class SignalChainEditorScreenTest {
    @get:Rule val rule = createComposeRule()

    private val none = FriendlyStatus("", StatusKind.INFO)

    private fun preset() = PresetCodec.decode(TestSupport.preset(), TestSupport.SYMBOLS)

    @Composable
    private fun Editor(
        preset: PresetCodec.Preset? = preset(),
        link: LinkStatus = LinkStatus.READY,
        status: FriendlyStatus = none,
        dirty: Boolean = false,
        onConnect: () -> Unit = {},
        onSetBool: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onSetFloat: (String, String, Float) -> Unit = { _, _, _ -> }
    ) = AmplifreeTheme {
        SignalChainEditorScreen(
            preset = preset, revision = 0, link = link, status = status,
            onSetFloat = onSetFloat, onSetBool = onSetBool,
            onOpenPresets = {}, onReload = {}, onConnect = onConnect, dirty = dirty
        )
    }

    // ---- header -----------------------------------------------------------

    @Test fun headerShowsToneNameAndModifiedMarkerOnlyWhenEdited() {
        var dirty = false
        rule.setContent { Editor(dirty = dirty) }
        rule.onNodeWithTag("tone_title").assertTextContains("Test Tone")
        rule.onNodeWithTag("modified_chip").assertDoesNotExist()
    }

    @Test fun modifiedMarkerIsShownWhenDirty() {
        rule.setContent { Editor(dirty = true) }
        rule.onNodeWithTag("modified_chip").assertIsDisplayed()
    }

    @Test fun headerStaysVisibleWhileTheCardsScroll() {
        rule.setContent { Editor(dirty = true) }
        rule.onNodeWithTag("signal_chain_editor").performScrollToNode(hasTestTag("save_button"))
        rule.onNodeWithTag("tone_title").assertIsDisplayed()
        rule.onNodeWithTag("modified_chip").assertIsDisplayed()
        rule.onNodeWithTag("jump_chips").assertIsDisplayed()
    }

    // ---- states -----------------------------------------------------------

    @Test fun noToneAndNoAmpShowsConnect() {
        var connects = 0
        rule.setContent { Editor(preset = null, link = LinkStatus.DISCONNECTED, onConnect = { connects++ }) }
        rule.onNodeWithText("Amp not connected").assertIsDisplayed()
        rule.onNodeWithText("Connect to amp").performClick()
        assertEquals(1, connects)
    }

    @Test fun noToneYetWhileConnectedShowsReadingState() {
        rule.setContent { Editor(preset = null, link = LinkStatus.READY) }
        rule.onNodeWithText("Reading your tone…").assertIsDisplayed()
    }

    @Test fun staleToneWhileDisconnectedShowsBannerWithConnect() {
        var connects = 0
        rule.setContent { Editor(link = LinkStatus.DISCONNECTED, onConnect = { connects++ }) }
        rule.onNodeWithTag("editor_banner").assertIsDisplayed()
        rule.onNodeWithText("Connect").performClick()
        assertEquals(1, connects)
        rule.onNodeWithTag("param_Bass").assertIsNotEnabled()
    }

    @Test fun statusLineShowsTheFriendlyText() {
        rule.setContent { Editor(status = FriendlyStatus("Loaded Test Tone", StatusKind.INFO)) }
        rule.onNodeWithTag("editor_status").assertTextContains("Loaded Test Tone")
    }

    // ---- jump chips and collapsible cards ---------------------------------

    @Test fun chipsCoverTheBlocksThatExist() {
        rule.setContent { Editor() }
        rule.onNodeWithTag("jump_Amp").assertIsDisplayed()
        rule.onNodeWithTag("jump_FX1").assertIsDisplayed()
        rule.onNodeWithTag("jump_FX2").assertDoesNotExist()
        rule.onNodeWithTag("jump_StudioCab").assertDoesNotExist()
    }

    @Test fun chipsAreAtLeast48dpTall() {
        rule.setContent { Editor() }
        rule.onNodeWithTag("jump_Amp").assertHeightIsAtLeast(48.dp)
    }

    @Test fun chipScrollsToTheCardAndExpandsIt() {
        rule.setContent { Editor() }
        rule.onNodeWithTag("toggle_FX1").performClick()          // collapse FX 1
        rule.onNodeWithTag("param_Drive").assertExists()          // only the Amp's Drive is left
        rule.onNodeWithTag("jump_FX1").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithTag("param_Drive").assertCountEquals(2) // FX 1 is open again
        rule.onNodeWithTag("card_FX1").assertIsDisplayed()
    }

    @Test fun cardsCollapseAndExpand() {
        rule.setContent { Editor() }
        rule.onNodeWithTag("param_Bass").assertIsDisplayed()
        rule.onNodeWithTag("toggle_Amp").performClick()
        rule.onNodeWithTag("param_Bass").assertDoesNotExist()
        rule.onNodeWithTag("toggle_Amp").performClick()
        rule.onNodeWithTag("param_Bass").assertIsDisplayed()
    }

    @Test fun collapsedCardsStayCollapsedAfterRecreation() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Editor() }
        rule.onNodeWithTag("toggle_Amp").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("param_Bass").assertDoesNotExist()
        rule.onNodeWithTag("card_Amp").assertIsDisplayed()
    }

    // ---- switches ---------------------------------------------------------

    @Test fun switchRowIsAWholeRowToggleAtLeast48dpTall() {
        rule.setContent { Editor() }
        rule.onNodeWithTag("enable_FX1EnableState").assertHeightIsAtLeast(48.dp)
            .assertIsOn()
        rule.onNodeWithTag("enable_CabEnableState").assertIsOff()
    }

    @Test fun togglingASwitchSendsTheChangeAndGivesHapticFeedback() {
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        var ticks = 0
        val haptics = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks++ }
        }
        rule.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                Editor(onSetBool = { b, p, v -> sent += Triple(b, p, v) })
            }
        }
        rule.onNodeWithTag("enable_FX1EnableState").performClick()
        assertEquals(listOf(Triple("GuitarProc", "FX1EnableState", false)), sent)
        assertEquals(1, ticks)
        rule.onNodeWithTag("enable_FX1EnableState").assertIsOff()
    }

    @Test fun disconnectedSwitchesDoNothing() {
        val sent = mutableListOf<String>()
        rule.setContent { Editor(link = LinkStatus.DISCONNECTED, onSetBool = { _, p, _ -> sent += p }) }
        rule.onNodeWithTag("enable_FX1EnableState").assertIsNotEnabled()
        assertEquals(emptyList<String>(), sent)
    }

    // ---- sliders ----------------------------------------------------------

    private fun sliderText(tag: String): String? =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test fun anUncommittedSliderDragSurvivesRecreation() {
        val committed = mutableListOf<Float>()
        val tester = StateRestorationTester(rule)
        tester.setContent { Editor(onSetFloat = { _, _, v -> committed += v }) }
        val before = sliderText("param_Bass")
        rule.onNodeWithTag("param_Bass").performTouchInput {
            down(Offset(width * 0.5f, height - 24f))
            moveTo(Offset(width * 0.9f, height - 24f))
            // finger still down: nothing has been sent yet
        }
        val during = sliderText("param_Bass")
        assertNotEquals(before, during)
        assertEquals(emptyList<Float>(), committed)
        tester.emulateSavedInstanceStateRestore()
        assertEquals(during, sliderText("param_Bass"))
    }

    // ---- save stays off ---------------------------------------------------

    @Test fun saveButtonIsStillDisabled() {
        rule.setContent { Editor(dirty = true) }
        rule.onNodeWithTag("signal_chain_editor").performScrollToNode(hasTestTag("save_button"))
        rule.onNodeWithTag("save_button").assertIsNotEnabled()
        rule.onNodeWithText("SAVE (COMING SOON)").assertExists()
    }
}

/** The same editor in a window wide enough for two columns, and in a narrow one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h420dp")
class SignalChainEditorWideTest {
    @get:Rule val rule = createComposeRule()

    @Test fun wideWindowsUseTwoColumnsWithBothColumnsFilled() {
        rule.setContent {
            AmplifreeTheme {
                SignalChainEditorScreen(
                    preset = PresetCodec.decode(TestSupport.preset(), TestSupport.SYMBOLS),
                    revision = 0, link = LinkStatus.READY,
                    status = FriendlyStatus("", StatusKind.INFO),
                    onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> },
                    onOpenPresets = {}, onReload = {}
                )
            }
        }
        rule.onNodeWithTag("editor_column_first").assertIsDisplayed()
        rule.onNodeWithTag("editor_column_second").assertIsDisplayed()
        // The cards are shared out in reading order: the signal path first, the FX block last.
        rule.onNodeWithTag("editor_column_first").assert(hasAnyDescendant(hasTestTag("card_@path")))
        rule.onNodeWithTag("editor_column_second").assert(hasAnyDescendant(hasTestTag("card_FX1")))
    }

    @Test fun chipsReachCardsInEitherColumn() {
        rule.setContent {
            AmplifreeTheme {
                SignalChainEditorScreen(
                    preset = PresetCodec.decode(TestSupport.preset(), TestSupport.SYMBOLS),
                    revision = 0, link = LinkStatus.READY,
                    status = FriendlyStatus("", StatusKind.INFO),
                    onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> },
                    onOpenPresets = {}, onReload = {}
                )
            }
        }
        rule.onNodeWithTag("jump_FX1").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("card_FX1").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class SignalChainEditorNarrowTest {
    @get:Rule val rule = createComposeRule()

    @Test fun phonesUseASingleColumn() {
        rule.setContent {
            AmplifreeTheme {
                SignalChainEditorScreen(
                    preset = PresetCodec.decode(TestSupport.preset(), TestSupport.SYMBOLS),
                    revision = 0, link = LinkStatus.READY,
                    status = FriendlyStatus("", StatusKind.INFO),
                    onSetFloat = { _, _, _ -> }, onSetBool = { _, _, _ -> },
                    onOpenPresets = {}, onReload = {}
                )
            }
        }
        rule.onNodeWithTag("editor_column_first").assertDoesNotExist()
        rule.onNodeWithTag("signal_chain_editor").assertIsDisplayed()
    }
}
