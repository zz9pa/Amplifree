package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun delaysGrowAndThenStop() {
        val delays = (0 until ReconnectPolicy.MAX_ATTEMPTS).map { ReconnectPolicy.delayMs(it) }
        assertTrue(delays.all { it != null })
        assertEquals(delays.sortedBy { it }, delays)          // never shrinks
        assertNull(ReconnectPolicy.delayMs(ReconnectPolicy.MAX_ATTEMPTS))
    }

    @Test fun firstRetryIsQuick() {
        assertTrue(ReconnectPolicy.delayMs(0)!! <= 1_500)
    }
}
