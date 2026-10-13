package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.AmplifiProtocol
import io.github.zz9pa.amplifree.bluetooth.Bsp
import io.github.zz9pa.amplifree.bluetooth.Record
import io.github.zz9pa.amplifree.bluetooth.RecordParser
import io.github.zz9pa.amplifree.bluetooth.Segment
import io.github.zz9pa.amplifree.bluetooth.ServiceStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The stream layer sits between BSP framing and the command layer. The amp
 * acknowledges bytes at the link level whether or not the stream layer is right,
 * so a mistake here looks exactly like a dead amp. These tests pin the rules down
 * with synthetic traffic and a simulated amp that enforces its real limits.
 */
class StreamLayerTest {

    private val lib = AmplifiProtocol.AUX_LIBRARIAN

    // ---- frame builders (the amp's side of the conversation) ------------------

    private fun ampFrame(aux: Int, ctr: Int, flags: Int, window: Int, payload: ByteArray): ByteArray {
        // amp -> app: header `aux` carries the app's src, header `src` the amp service
        val h = AmplifiProtocol.packBspHeader(1, payload.size + 8, AmplifiProtocol.srcForAux(aux), aux)
        val env = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        env.putInt((flags shl 24) or (ctr shl 8)); env.putInt(window)
        return h + env.array() + payload
    }

    private fun ampConnectReply(aux: Int, window: Int = 512, mtu: Int = 228): ByteArray {
        val h = AmplifiProtocol.packBspHeader(2, 12, AmplifiProtocol.srcForAux(aux), aux)
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(mtu shl 16); b.putInt((0x01 shl 24) or (1 shl 8)); b.putInt(window)
        return h + b.array()
    }

    // ---- BSP framing ---------------------------------------------------------------

    /** One RSMC payload may hold several BSP frames; each is `dst + 8` bytes long. */
    @Test
    fun splitFramesSeparatesConcatenatedFrames() {
        val a = ampFrame(lib, 2, 0x08, 600, ByteArray(0))
        val b = ampFrame(lib, 3, 0x04, 600, ByteArray(12) { it.toByte() })
        val c = ampFrame(lib, 4, 0x0c, 700, ByteArray(212))
        val parts = Bsp.splitFrames(a + b + c)
        assertEquals(listOf(a.size, b.size, c.size), parts.map { it.size })
        assertArrayEquals(b, parts[1])
        assertEquals(1, Bsp.splitFrames(a).size)
    }

    /** An odd-sized frame is padded to 4 bytes, so the next frame starts after the padding. */
    @Test
    fun splitFramesSkipsPaddingAfterOddSizedFrames() {
        val odd = ampFrame(lib, 5, 0x0c, 540, ByteArray(9) { 7 })       // 25 bytes on its own
        val ack = ampFrame(lib, 6, 0x10, 540, ByteArray(0))             // 16 bytes
        val wire = odd + ByteArray(3) + ack                             // 3 padding bytes between
        val parts = Bsp.splitFrames(wire)
        assertEquals(2, parts.size)
        assertEquals(9, Segment.parse(parts[0])!!.payload.size)          // padding is not payload
        assertEquals(6, Segment.parse(parts[1])!!.ctr)
        // padding after the last frame must not turn into a bogus extra frame
        assertEquals(1, Bsp.splitFrames(odd + ByteArray(3)).size)
    }

    @Test
    fun aMalformedTailIsKeptRatherThanDropped() {
        val a = ampFrame(lib, 2, 0x04, 600, ByteArray(4))
        val truncated = a.copyOf(a.size - 2)
        assertEquals(1, Bsp.splitFrames(truncated).size)
    }

    @Test
    fun aClosedFlagFromTheAmpIsVisibleOnTheParsedSegment() {
        val seg = Segment.parse(ampFrame(lib, 49, ServiceStream.FLAG_CLOSED, 976, ByteArray(0)))!!
        assertEquals(ServiceStream.FLAG_CLOSED, seg.flags and ServiceStream.FLAG_CLOSED)
        assertEquals(0, Segment.parse(ampFrame(lib, 50, ServiceStream.FLAG_HEARTBEAT, 976, ByteArray(0)))!!.flags and ServiceStream.FLAG_CLOSED)
    }

    @Test
    fun heartbeatIsEmptyFlaggedAndAdvancesTheCounterOnly() {
        val st = ServiceStream(lib)
        st.connectSegment()
        val before = st.txSent
        val seg = Segment.parse(st.heartbeatSegment())!!
        assertEquals(0, seg.payload.size)
        assertEquals(ServiceStream.FLAG_HEARTBEAT, seg.flags and ServiceStream.FLAG_HEARTBEAT)
        assertEquals(2, seg.ctr)
        assertEquals(3, Segment.parse(st.heartbeatSegment())!!.ctr)
        assertEquals(before, st.txSent)
    }

    // ---- connect ---------------------------------------------------------------------

    @Test
    fun connectSegmentAdvertisesOurMtuWindowAndFirstCounter() {
        val st = ServiceStream(lib)
        val seg = Segment.parse(st.connectSegment())!!
        assertEquals(2, seg.type)
        assertEquals(lib, seg.aux)
        assertEquals(AmplifiProtocol.SRC_LIBRARIAN, seg.src)
        assertEquals(228, seg.mtu)
        assertEquals(1, seg.ctr)
        assertEquals(4096, seg.window)
        // the BSP `dst` of a connect frame is its length + 12 with no payload
        assertEquals(12, AmplifiProtocol.unpackBspHeader(st.connectSegment())!!.dst)
    }

    @Test
    fun theConnectReplySetsTheMtuAndTheAmpsBuffer() {
        val st = ServiceStream(lib)
        st.connectSegment()
        assertTrue(st.onFrame(ampConnectReply(lib, window = 512, mtu = 228)).isConnect)
        assertEquals(212, st.maxSegment())
        assertEquals(512, st.credit())
        // a different negotiated MTU is honoured
        val other = ServiceStream(lib)
        other.onFrame(ampConnectReply(lib, mtu = 100))
        assertEquals(84, other.maxSegment())
    }

    // ---- flow control: our receive side ------------------------------------------------

    /**
     * We advertise `4096 + bytes consumed`. A constant instead makes the amp think our
     * buffer is full after 4 KB, so it stops sending while the link stays alive.
     */
    @Test
    fun windowIsBufferSizePlusEverythingConsumed() {
        val st = ServiceStream(lib)
        st.connectSegment(); st.onFrame(ampConnectReply(lib))
        assertEquals(4096, st.window())
        st.onFrame(ampFrame(lib, 2, 0x04, 512, ByteArray(212)))
        assertEquals(4096 + 212, st.window())
        st.onFrame(ampFrame(lib, 3, 0x04, 512, ByteArray(100)))
        assertEquals(4096 + 312, st.window())
        // and it is what we put on the wire
        val seg = Segment.parse(st.dataSegment(ByteArray(4), 0, 4))!!
        assertEquals(4096 + 312, seg.window)
    }

    @Test
    fun anAckSegmentCarriesTheFreshWindowAndNothingElse() {
        val st = ServiceStream(lib)
        st.connectSegment(); st.onFrame(ampConnectReply(lib))
        st.onFrame(ampFrame(lib, 2, 0x04, 512, ByteArray(212)))
        assertTrue(st.needsAck())
        val ack = Segment.parse(st.ackSegment())!!
        assertEquals(4096 + 212, ack.window)
        assertEquals(0, ack.payload.size)
        assertTrue(ack.flags and ServiceStream.FLAG_ACK != 0)
        assertFalse("nothing new to report", st.needsAck())
    }

    @Test
    fun theAckBitMarksSegmentsWhoseWindowChanged() {
        val st = ServiceStream(lib)
        st.connectSegment(); st.onFrame(ampConnectReply(lib, window = 1 shl 20))
        val first = Segment.parse(st.dataSegment(ByteArray(8), 0, 8))!!
        assertEquals("window unchanged since connect", 0, first.flags and ServiceStream.FLAG_ACK)
        st.onFrame(ampFrame(lib, 2, 0x04, 600, ByteArray(30)))
        val second = Segment.parse(st.dataSegment(ByteArray(8), 0, 8))!!
        assertTrue("window moved, so the ACK bit is set", second.flags and ServiceStream.FLAG_ACK != 0)
    }

    @Test
    fun retransmittedSegmentsAreCountedOnce() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib))
        val seg = ampFrame(lib, 2, 0x04, 512, ByteArray(4))
        assertEquals(4, st.onFrame(seg).newBytes.size)
        assertEquals("duplicate adds nothing", 0, st.onFrame(seg).newBytes.size)
        assertEquals(4L, st.rxConsumed)
    }

    /** The counter is one byte, so de-duplication must be by sequence, not by value. */
    @Test
    fun countersMayWrapPast255() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib))
        var ctr = 1
        var delivered = 0
        repeat(600) {
            ctr = (ctr + 1) and 0xFF
            delivered += st.onFrame(ampFrame(lib, ctr, 0x04, 512, ByteArray(3))).newBytes.size
        }
        assertEquals("every segment counted despite the wrap", 1800, delivered)
    }

    // ---- flow control: our transmit side ------------------------------------------------

    @Test
    fun segmentsNeverExceedTheMtu() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib, window = 1 shl 20))
        assertEquals(212, st.maxSegment())
        val frame = st.dataSegment(ByteArray(212), 0, 212)
        assertEquals(228, frame.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anOversizeSegmentIsRefused() {
        val st = ServiceStream(lib)
        st.dataSegment(ByteArray(512), 0, 512)
    }

    @Test
    fun creditShrinksAsWeSendAndGrowsWhenTheAmpAcks() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib, window = 512))
        assertEquals(512, st.credit())
        st.dataSegment(ByteArray(212), 0, 212)
        assertEquals(300, st.credit())
        st.dataSegment(ByteArray(212), 0, 212)
        assertEquals(88, st.credit())
        st.onFrame(ampFrame(lib, 2, 0x10, 512 + 424, ByteArray(0)))   // amp consumed 424 bytes
        assertEquals(512, st.credit())
    }

    @Test
    fun aStaleWindowNeverShrinksTheCredit() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib, window = 512))
        st.onFrame(ampFrame(lib, 2, 0x10, 900, ByteArray(0)))
        st.onFrame(ampFrame(lib, 3, 0x10, 700, ByteArray(0)))     // reordered / stale
        assertEquals(900L, st.peerEdge)
    }

    @Test
    fun everySegmentGetsItsOwnCounter() {
        val st = ServiceStream(lib)
        st.onFrame(ampConnectReply(lib, window = 1 shl 20))
        val ctrs = listOf(
            Segment.parse(st.dataSegment(ByteArray(4), 0, 4))!!.ctr,
            Segment.parse(st.ackSegment())!!.ctr,
            Segment.parse(st.dataSegment(ByteArray(4), 0, 4))!!.ctr
        )
        assertEquals("the first data segment after connect is 2, and acks count too", listOf(2, 3, 4), ctrs)
    }

    // ---- a simulated amp ---------------------------------------------------------------------

    /**
     * An amp that enforces the real limits: frames over the MTU are dropped, and a
     * 512-byte receive buffer that only drains when it is ticked. Anything that
     * violates a limit is recorded rather than silently accepted.
     */
    private class FakeAmp(val aux: Int) {
        var violations = mutableListOf<String>()
        val received = java.io.ByteArrayOutputStream()
        private var buffered = 0
        private var consumed = 0L
        private var lastCtr = 1
        private var ctr = 1

        fun onAppFrame(frame: ByteArray) {
            if (frame.size > 228) { violations += "frame of ${frame.size} bytes exceeds the MTU"; return }
            val seg = Segment.parse(frame) ?: return
            if (seg.payload.isEmpty()) return
            if (seg.ctr != ((lastCtr + 1) and 0xFF)) { violations += "counter ${seg.ctr} after $lastCtr"; return }
            lastCtr = seg.ctr
            if (buffered + seg.payload.size > 512) { violations += "buffer overrun: ${buffered + seg.payload.size} > 512"; return }
            buffered += seg.payload.size
            received.write(seg.payload)
        }

        /** The amp's firmware gets round to reading some of its buffer; it then advertises the new edge. */
        fun tick(bytes: Int): ByteArray {
            val n = minOf(bytes, buffered)
            buffered -= n; consumed += n
            ctr = (ctr + 1) and 0xFF
            val h = AmplifiProtocol.packBspHeader(1, 8, AmplifiProtocol.srcForAux(aux), aux)
            val env = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            env.putInt((0x10 shl 24) or (ctr shl 8)); env.putInt((512 + consumed).toInt())
            return h + env.array()
        }
    }

    @Test
    fun aLargeCommandReachesTheAmpIntactWithoutBreakingAnyLimit() {
        val st = ServiceStream(lib)
        val amp = FakeAmp(lib)
        st.onFrame(ampConnectReply(lib, window = 512))

        // a 1.3 kB command -- the size of a preset write
        val command = AmplifiProtocol.command(
            AmplifiProtocol.CMD_LIB_SET_PRESET_FILE,
            AmplifiProtocol.setPresetFileArgs(ByteArray(1258) { (it * 7).toByte() })
        )
        var off = 0
        var guard = 0
        while (off < command.size) {
            val n = minOf(command.size - off, st.credit(), st.maxSegment())
            if (n > 0) {
                amp.onAppFrame(st.dataSegment(command, off, n))
                off += n
            } else {
                st.onFrame(amp.tick(100))       // wait for the amp to drain, as the real sender does
            }
            check(++guard < 10_000) { "sender made no progress" }
        }
        assertEquals("nothing violated a protocol limit", emptyList<String>(), amp.violations)
        assertArrayEquals("the amp received exactly what we sent", command, amp.received.toByteArray())
    }

    /** The mistake that made every hand-built command fail: blasting without respecting credit. */
    @Test
    fun ignoringFlowControlWouldOverrunTheAmp() {
        val st = ServiceStream(lib)
        val amp = FakeAmp(lib)
        st.onFrame(ampConnectReply(lib, window = 1 shl 20))      // pretend credit is unlimited
        val data = ByteArray(1274)
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, st.maxSegment())
            amp.onAppFrame(st.dataSegment(data, off, n)); off += n
        }
        assertTrue("a 512-byte buffer cannot absorb 1.3 kB at once", amp.violations.isNotEmpty())
    }

    /** And the receive-side mistake: if we never advertise what we consumed, the amp stops at 4 KB. */
    @Test
    fun withoutAcksTheAmpCanOnlySendOneBufferFull() {
        fun transfer(sendAcks: Boolean): Int {
            val st = ServiceStream(lib)
            st.connectSegment(); st.onFrame(ampConnectReply(lib))
            var edge = 4096L          // what the amp believes it may send up to
            var sent = 0L
            var ctr = 1
            var got = 0
            repeat(200) {
                val n = minOf(212L, edge - sent).toInt()
                if (n > 0) {
                    ctr = (ctr + 1) and 0xFF
                    got += st.onFrame(ampFrame(lib, ctr, 0x04, 512, ByteArray(n))).newBytes.size
                    sent += n
                }
                if (sendAcks && st.needsAck()) edge = Segment.parse(st.ackSegment())!!.window.toLong()
            }
            return got
        }
        assertEquals("stalls after exactly one buffer", 4096, transfer(sendAcks = false))
        assertTrue("with acks the whole stream flows", transfer(sendAcks = true) > 20_000)
    }

    // ---- records ---------------------------------------------------------------------------------

    @Test
    fun recordsAreReassembledAcrossSegmentsAndSkipTheHello() {
        val p = RecordParser()
        p.skip = AmplifiProtocol.LIBRARIAN_HELLO.size
        val stream = AmplifiProtocol.LIBRARIAN_HELLO +
            TestSupport.hex("01000000" + "04000000" + "64000000") +      // {type 1, len 4, 100}
            TestSupport.hex("01000000" + "06000000" + "000000001234")    // {type 1, len 6, result 0, 2 bytes}
        val recs = mutableListOf<Record>()
        for (b in stream) recs.addAll(p.feed(byteArrayOf(b)))             // one byte at a time
        assertEquals(2, recs.size)
        assertEquals(100, recs[0].result)
        assertEquals(0, recs[1].result)
        assertArrayEquals(TestSupport.hex("1234"), recs[1].body)
    }

    @Test
    fun eventsAreDistinguishedFromResponses() {
        val p = RecordParser()
        // {type 2, len 16, slot, param, float type, 1.0f}
        val ev = TestSupport.hex("02000000" + "10000000" + "4b020000" + "52020000" + "04000000" + "0000803f")
        val rec = p.feed(ev).single()
        assertEquals(Record.TYPE_PARAM_EVENT, rec.type)
        assertEquals(1.0f, ByteBuffer.wrap(rec.data).order(ByteOrder.LITTLE_ENDIAN).let { it.int; it.int; it.int; it.float }, 0f)
    }

    @Test
    fun anImplausibleLengthResyncsInsteadOfBuffering64KbOfGarbage() {
        val p = RecordParser()
        assertEquals(0, p.feed(TestSupport.hex("01000000" + "ffffff7f")).size)
        val good = p.feed(TestSupport.hex("01000000" + "04000000" + "07000000"))
        assertEquals(1, good.size)
        assertEquals(7, good[0].result)
    }
}
