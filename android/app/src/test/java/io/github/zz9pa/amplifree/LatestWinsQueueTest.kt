package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.LatestWinsQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsQueueTest {
    @Test fun newestValueReplacesOlderOneForTheSameControl() {
        val q = LatestWinsQueue<Int>()
        q.put("Amp.Drive", 1); q.put("Amp.Drive", 2); q.put("Amp.Drive", 3)
        assertEquals("Amp.Drive" to 3, q.take())
        assertNull(q.take())
    }

    @Test fun controlsAreSentInOrderOfLastChange() {
        val q = LatestWinsQueue<Int>()
        q.put("a", 1); q.put("b", 1); q.put("a", 2)       // a touched again after b
        assertEquals("b" to 1, q.take())
        assertEquals("a" to 2, q.take())
    }

    @Test fun emptyAndClear() {
        val q = LatestWinsQueue<Int>()
        assertTrue(q.isEmpty())
        q.put("x", 1); q.clear()
        assertTrue(q.isEmpty())
        assertNull(q.take())
    }
}
