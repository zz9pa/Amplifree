package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.data.TunerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunerEngineTest {
    // The amp reports semitones above A (modulo 12): E = 7, A = 0 (or just under 12 when flat), D = 5, G = 10.

    @Test fun flatLowEIsMatchedToTheLowEString() {
        val t = TunerEngine(); t.publishNote(6.81f)
        val s = t.state.value
        assertTrue(s.signalDetected)
        assertEquals("E", s.noteName)
        assertEquals(-19f, s.centsOffset, 0.5f)
        assertFalse(s.isInTune)
    }

    @Test fun justUnderTwelveIsAFlatA() {
        val t = TunerEngine(); t.publishNote(11.93f)
        assertEquals("A", t.state.value.noteName)
        assertEquals(-7f, t.state.value.centsOffset, 0.5f)
    }

    @Test fun inTuneWithinThreeCents() {
        val t = TunerEngine(); t.publishNote(5.01f)
        assertEquals("D", t.state.value.noteName)
        assertTrue(t.state.value.isInTune)
    }

    @Test fun noNoteClearsTheSignal() {
        val t = TunerEngine(); t.publishNote(7f); t.publishNote(null)
        assertFalse(t.state.value.signalDetected)
    }

    @Test fun aSelectedStringIsAlwaysTheTarget() {
        val t = TunerEngine(); t.selectString(1)
        t.publishNote(7f)
        assertEquals("A", t.state.value.noteName)
        assertEquals(50f, Math.abs(t.state.value.centsOffset), 0.5f)
    }
}
