package io.github.zz9pa.amplifree.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zz9pa.amplifree.data.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusTextTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun setupStepsBecomeOneFriendlyBusyMessage() {
        listOf(
            "Opening session…", "Handshaking…", "Waiting for the amp to be ready…",
            "Downloading the amp's symbol table…"
        ).forEach {
            val s = friendlyStatus(it, res)
            assertEquals("Getting your amp ready…", s.text)
            assertTrue(it, s.isBusy)
        }
    }

    @Test fun modelLearningShowsProgressInWords() {
        val s = friendlyStatus("Learning your amp's models… 3/12", res)
        assertEquals("Learning your amp's models (3 of 12)…", s.text)
        assertTrue(s.isBusy)
    }

    @Test fun connectionLossAndStatesAreRewritten() {
        assertEquals(
            "Lost the connection to your amp. Reconnecting…",
            friendlyStatus("Connection lost. Reconnecting…", res).text
        )
        assertEquals("Not connected to your amp.", friendlyStatus("Not connected", res).text)
        assertEquals("Connected and ready.", friendlyStatus("Connected", res).text)
    }

    @Test fun problemsAreFlaggedAsErrorsAndKeepTheirText() {
        listOf(
            "The amp didn't load Foo", "Loaded, but couldn't read the tone back",
            "Bluetooth is off. Turn it on and tap the amp again."
        ).forEach {
            val s = friendlyStatus(it, res)
            assertTrue(it, s.isError)
            assertEquals(it, s.text)
        }
    }

    @Test fun progressMessagesPassThroughAsBusy() {
        assertTrue(friendlyStatus("Connecting to Amp… (try 2)", res).isBusy)
        assertTrue(friendlyStatus("Loading Foo…", res).isBusy)
    }

    @Test fun unknownTextIsLeftAlone() {
        val s = friendlyStatus("Something new", res)
        assertEquals("Something new", s.text)
        assertFalse(s.isBusy || s.isError)
        assertEquals("", friendlyStatus("  ", res).text)
    }

    @Test fun linkStatusFoldsTheFlags() {
        assertEquals(LinkStatus.READY, LinkStatus.of(ConnectionState.CONNECTED, ready = true, needsLink = false))
        assertEquals(LinkStatus.CONNECTING, LinkStatus.of(ConnectionState.CONNECTED, ready = false, needsLink = false))
        assertEquals(LinkStatus.CONNECTING, LinkStatus.of(ConnectionState.SCANNING, ready = false, needsLink = false))
        assertEquals(LinkStatus.NEEDS_LINK, LinkStatus.of(ConnectionState.DISCONNECTED, ready = false, needsLink = true))
        assertEquals(LinkStatus.DISCONNECTED, LinkStatus.of(ConnectionState.DISCONNECTED, ready = false, needsLink = false))
        assertTrue(LinkStatus.READY.isReady)
        assertFalse(LinkStatus.CONNECTING.isReady)
    }
}
