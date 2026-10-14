package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.AmplifiProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RsmcDecoderTest {
    private fun frame(seq: Int, ack: Int, payload: ByteArray = ByteArray(0)) =
        AmplifiProtocol.packRsmcFrame(seq, ack, (ack + 8) and 0xFF, payload)

    @Test fun crc16MatchesTheStandardCheckValue() {
        assertEquals(0x29B1, AmplifiProtocol.crc16("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test fun aFrameSurvivesAPackAndDecodeRoundTrip() {
        for (seq in intArrayOf(0, 1, 254, 255)) for (ack in intArrayOf(0, 7, 255)) for (len in intArrayOf(0, 1, 200, 65_535)) {
            val payload = ByteArray(len) { (it * 31).toByte() }
            val f = AmplifiProtocol.RsmcDecoder().push(frame(seq, ack, payload), frame(seq, ack, payload).size).single()
            assertEquals(seq, f.seq); assertEquals(ack, f.ack)
            assertArrayEquals(payload, f.payload)
        }
    }

    @Test fun framesSplitAtEveryByteBoundaryStillDecode() {
        val bytes = frame(5, 9, ByteArray(30) { it.toByte() }) + frame(6, 9)
        for (cut in 1 until bytes.size) {
            val d = AmplifiProtocol.RsmcDecoder()
            val out = d.push(bytes, cut) + d.push(bytes.copyOfRange(cut, bytes.size), bytes.size - cut)
            assertEquals("cut at $cut", 2, out.size)
        }
    }

    @Test fun aStrayLeadingSyncByteDoesNotHideTheNextFrame() {
        val bytes = byteArrayOf(0x55) + frame(3, 3)
        assertEquals(1, AmplifiProtocol.RsmcDecoder().push(bytes, bytes.size).size)
    }

    @Test fun aCorruptedPayloadIsRejectedAndTheNextFrameStillDecodes() {
        val bad = frame(4, 4, ByteArray(10) { 7 }).also { it[14] = (it[14] + 1).toByte() }
        val good = frame(5, 4, ByteArray(10) { 9 })
        val d = AmplifiProtocol.RsmcDecoder()
        val out = d.push(bad + good, bad.size + good.size)
        assertEquals(1, out.size); assertEquals(5, out[0].seq)
        assertTrue(d.badFrames >= 1)
    }

    @Test fun aNewDecoderOrAResetForgetsAPartialFrame() {
        val partial = frame(7, 7, ByteArray(100) { 1 }).copyOf(40)
        val heartbeat = frame(8, 7)
        val old = AmplifiProtocol.RsmcDecoder()
        old.push(partial, partial.size)
        old.reset()
        assertEquals(1, old.push(heartbeat, heartbeat.size).size)
        assertEquals(1, AmplifiProtocol.RsmcDecoder().push(heartbeat, heartbeat.size).size)
    }
}
