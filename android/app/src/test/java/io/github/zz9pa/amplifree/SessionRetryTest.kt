package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.NameList
import io.github.zz9pa.amplifree.bluetooth.SessionRetry
import io.github.zz9pa.amplifree.bluetooth.SessionRetry.Action
import io.github.zz9pa.amplifree.bluetooth.SessionRetry.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRetryTest {
    @Test fun aRefusedSessionIsRetriedAFewTimesAndThenReported() {
        val max = 3
        assertEquals(Action.WAIT_AND_RETRY, SessionRetry.decide(Outcome.NOT_ACCEPTED, 0, max))
        assertEquals(Action.WAIT_AND_RETRY, SessionRetry.decide(Outcome.NOT_ACCEPTED, 2, max))
        assertEquals(Action.GIVE_UP, SessionRetry.decide(Outcome.NOT_ACCEPTED, 3, max))
    }

    @Test fun theLoopTerminatesForARefusingAmp() {
        var tries = 0
        var steps = 0
        while (true) {
            steps++
            when (SessionRetry.decide(Outcome.NOT_ACCEPTED, tries, 3)) {
                Action.WAIT_AND_RETRY -> tries++
                else -> break
            }
            assertEquals(true, steps < 10)
        }
        assertEquals(3, tries)
    }

    @Test fun successAndStoppedFinishAndARestoredLinkRetriesAtOnce() {
        assertEquals(Action.DONE, SessionRetry.decide(Outcome.ESTABLISHED, 0, 3))
        assertEquals(Action.DONE, SessionRetry.decide(Outcome.STOPPED, 5, 3))
        assertEquals(Action.RETRY_NOW, SessionRetry.decide(Outcome.LINK_RESTORED, 0, 3))
    }

    @Test fun aPartialScanNeverMakesNamesDisappear() {
        val known = mapOf(0 to "A", 1 to "B", 2 to "C")
        val merged = NameList.merge(known, mapOf(1 to "B2", 3 to "D"))
        assertEquals(listOf(0 to "A", 1 to "B2", 2 to "C", 3 to "D"), merged)
        assertEquals(known.entries.map { it.key to it.value }, NameList.merge(known, emptyMap()))
    }
}
