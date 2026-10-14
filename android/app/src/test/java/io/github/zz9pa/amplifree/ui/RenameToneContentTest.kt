package io.github.zz9pa.amplifree.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.github.zz9pa.amplifree.ui.dialogs.RenameToneContent
import io.github.zz9pa.amplifree.ui.theme.AmplifreeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h820dp")
class RenameToneContentTest {
    @get:Rule val rule = createComposeRule()

    // A focused text field blinks its cursor for ever, so the clock is driven by hand.

    @Test fun confirmNeedsANewNameAndReportsIt() {
        var renamed: String? = null
        rule.setContent { AmplifreeTheme { RenameToneContent(2, "Lead Boost", { renamed = it }, {}) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("rename_confirm").assertIsNotEnabled()           // still the old name
        rule.onNodeWithTag("rename_field").performTextReplacement("Lead Plus")
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("rename_confirm").assertIsEnabled().performClick()
        assertEquals("Lead Plus", renamed)
    }

    @Test fun cancelReportsDismissAndRenamesNothing() {
        var renamed = 0; var dismissed = 0
        rule.setContent { AmplifreeTheme { RenameToneContent(0, "Clean", { renamed++ }, { dismissed++ }) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed); assertEquals(0, renamed)
    }

    @Test fun nonPrintableCharactersAreDroppedAndLengthIsLimited() {
        var renamed: String? = null
        rule.setContent { AmplifreeTheme { RenameToneContent(1, "x", { renamed = it }, {}) } }
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("rename_field").performTextReplacement("Caf\u00e9 " + "a".repeat(60))
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("rename_confirm").performClick()
        val n = renamed!!
        assertEquals(true, n.length <= 32 && n.all { it.code in 32..126 })
    }
}
