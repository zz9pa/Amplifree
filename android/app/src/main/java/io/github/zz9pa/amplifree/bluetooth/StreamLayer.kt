package io.github.zz9pa.amplifree.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MsgPort_Stream: the reliable byte stream that carries each service's
 * commands, sitting between BSP framing (below) and the command layer (above).
 *
 * The rules below were worked out by observing the protocol and verified against
 * recorded sessions with a real amp (every segment of a full session agrees with
 * the window model). The class is deliberately pure -- no Android, no I/O -- so it
 * can be unit-tested with synthetic conversations.
 *
 * A BSP frame carrying stream data is:
 *
 *     [ 8 bytes  BSP header   type | flags | dst | src | aux ]
 *     [ 8 bytes  envelope     word0 = flags<<24 | ctr<<8
 *                             word1 = window                  ]
 *     [ N bytes  stream payload                               ]
 *
 *  - `dst` is a LENGTH: payload + 8 (type 1) or payload + 12 (type 2), so a
 *    whole frame is always `dst + 8` bytes. One RSMC payload may hold several
 *    frames back to back; [splitFrames] separates them.
 *  - `ctr` increments for EVERY segment a side emits, including empty acks.
 *  - `window` is a TCP-style flow-control edge in absolute stream offsets:
 *    `receiveBufferSize + totalBytesConsumed`. The app's buffer is 4096 bytes,
 *    the amp's only 512. Advertising a constant instead makes the amp believe
 *    our buffer is full after 4 KB and go silent -- while the RSMC link keeps
 *    acking, which looks exactly like a dead amp.
 *  - The connect handshake negotiates an MTU of 228, so a segment carries at
 *    most 228 - 16 = 212 payload bytes. Larger frames are discarded.
 */
object Bsp {
    /**
     * A frame is `dst + 8` bytes (header included), and the next frame starts on the next
     * 4-byte boundary: the sender pads an odd-sized frame. Ignoring the padding misreads
     * whatever follows, which silently loses small frames such as bare acknowledgements.
     */
    fun splitFrames(payload: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var off = 0
        while (off + 8 <= payload.size) {
            val h = AmplifiProtocol.unpackBspHeader(payload.copyOfRange(off, off + 8)) ?: break
            var total = h.dst + 8
            if (total < 8 || off + total > payload.size) total = payload.size - off // malformed tail
            out.add(payload.copyOfRange(off, off + total))     // without the padding
            off += (total + 3) and 3.inv()
        }
        return out
    }
}

/** A parsed stream segment (type 1 data/ack, or type 2 connect). */
class Segment(
    val type: Int,
    val aux: Int,
    val src: Int,
    val ctr: Int,
    val flags: Int,
    val window: Int,
    /** Peer's maximum frame size; only present on type 2 (connect) segments. */
    val mtu: Int,
    val payload: ByteArray
) {
    companion object {
        fun parse(frame: ByteArray): Segment? {
            val h = AmplifiProtocol.unpackBspHeader(frame) ?: return null
            val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
            return when (h.type) {
                1 -> {
                    if (frame.size < 16) return null
                    val w0 = bb.getInt(8)
                    Segment(1, h.aux, h.src, (w0 ushr 8) and 0xFF, (w0 ushr 24) and 0xFF,
                        bb.getInt(12), 0, frame.copyOfRange(16, frame.size))
                }
                2 -> {
                    // connect: {mtu << 16, flags<<24|ctr<<8, window}
                    if (frame.size < 20) return null
                    val w0 = bb.getInt(8)
                    val w1 = bb.getInt(12)
                    Segment(2, h.aux, h.src, (w1 ushr 8) and 0xFF, (w1 ushr 24) and 0xFF,
                        bb.getInt(16), (w0 ushr 16) and 0xFFFF, frame.copyOfRange(20, frame.size))
                }
                else -> null
            }
        }
    }
}

/**
 * One service's stream state (the Editor and the Librarian each have their own,
 * with their own counters and their own `src`).
 */
class ServiceStream(val aux: Int) {

    companion object {
        /** Our receive buffer. The amp's is 512 -- see [peerEdge]. */
        const val RX_CAPACITY = 4096
        const val DEFAULT_MTU = 228
        const val FRAME_OVERHEAD = 16
        const val AMP_INITIAL_EDGE = 512

        const val FLAG_SYN = 0x01
        const val FLAG_DATA = 0x04
        const val FLAG_ACK = 0x08
        /** Set on the amp's empty once-a-second keep-alive segments. */
        const val FLAG_HEARTBEAT = 0x10
        /** Seen on a segment the amp sends just before it stops answering on that stream: it closed it. */
        const val FLAG_CLOSED = 0x02
    }

    val src: Int = AmplifiProtocol.srcForAux(aux)

    // ---- transmit side -------------------------------------------------
    /** Counter of the last segment we emitted; the first data segment is 2. */
    var txCtr: Int = 1
        private set
    /** Total stream bytes we have sent. */
    var txSent: Long = 0
        private set
    /** Right edge of what the amp will accept (512 + bytes it has consumed). */
    var peerEdge: Long = AMP_INITIAL_EDGE.toLong()
        private set
    var peerMtu: Int = DEFAULT_MTU
        private set

    // ---- receive side --------------------------------------------------
    /** Total in-order stream bytes received and consumed. */
    var rxConsumed: Long = 0
        private set
    private var lastRxCtr: Int = -1
    private var lastAdvertised: Int = -1

    /** Window we advertise: capacity + everything we have consumed. */
    fun window(): Int = (RX_CAPACITY + rxConsumed).toInt()

    /** Largest payload one segment may carry. */
    fun maxSegment(): Int = peerMtu - FRAME_OVERHEAD

    /** Bytes we may still send before overrunning the amp's buffer. */
    fun credit(): Int = (peerEdge - txSent).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Result of feeding an inbound frame in. */
    class Inbound(val newBytes: ByteArray, val isConnect: Boolean)

    /** type=2 CONNECT control segment (mtu 228, flags 0x21, ctr 1, window 4096). */
    fun connectSegment(): ByteArray {
        txCtr = 1
        txSent = 0
        rxConsumed = 0
        lastRxCtr = -1
        lastAdvertised = RX_CAPACITY
        val header = AmplifiProtocol.packBspHeader(type = 2, dst = 12, aux = aux, src = src)
        val body = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(DEFAULT_MTU shl 16)
        body.putInt(((FLAG_SYN or 0x20) shl 24) or (1 shl 8))
        body.putInt(RX_CAPACITY)
        return header + body.array()
    }

    /**
     * Process one inbound frame for this service. Returns only the NEW in-order
     * payload: RSMC retransmits, so the same segment can arrive twice, and it
     * must be counted once (de-duplicated by counter, not by content, because
     * the counter is a single byte and wraps).
     */
    fun onFrame(frame: ByteArray): Inbound {
        val seg = Segment.parse(frame) ?: return Inbound(ByteArray(0), false)
        if (seg.type == 2) {
            peerMtu = if (seg.mtu in 64..1024) seg.mtu else DEFAULT_MTU
            peerEdge = maxOf(peerEdge, seg.window.toLong())
            lastRxCtr = seg.ctr
            return Inbound(ByteArray(0), true)
        }
        // The window only ever grows; never let a stale segment shrink it.
        if (seg.window.toLong() > peerEdge) peerEdge = seg.window.toLong()

        val inOrder = lastRxCtr < 0 || seg.ctr == ((lastRxCtr + 1) and 0xFF)
        if (!inOrder) return Inbound(ByteArray(0), false)
        lastRxCtr = seg.ctr
        rxConsumed += seg.payload.size
        return Inbound(seg.payload, false)
    }

    /** True when our advertised window is stale and the amp should be told. */
    fun needsAck(): Boolean = window() != lastAdvertised

    private fun emit(payload: ByteArray, off: Int, len: Int, flagsIn: Int): ByteArray {
        txCtr = (txCtr + 1) and 0xFF
        val win = window()
        // The ACK bit marks a segment whose window word is fresh -- in captures
        // it is set exactly when the advertised window changed.
        val flags = flagsIn or (if (win != lastAdvertised) FLAG_ACK else 0)
        lastAdvertised = win
        val header = AmplifiProtocol.packBspHeader(type = 1, dst = len + 8, aux = aux, src = src)
        val env = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        env.putInt((flags shl 24) or (txCtr shl 8))
        env.putInt(win)
        return header + env.array() + payload.copyOfRange(off, off + len)
    }

    /** One data segment. Caller guarantees len <= min(credit(), maxSegment()). */
    fun dataSegment(payload: ByteArray, off: Int, len: Int): ByteArray {
        require(len in 1..maxSegment()) { "segment of $len bytes exceeds the ${maxSegment()}-byte MTU" }
        val f = emit(payload, off, len, FLAG_DATA)
        txSent += len
        return f
    }

    /**
     * Empty keep-alive segment, like the amp's own (flag 0x10). The amp stops answering a
     * stream it hears nothing from for a while, so an idle app has to keep talking.
     */
    fun heartbeatSegment(): ByteArray = emit(ByteArray(0), 0, 0, FLAG_HEARTBEAT)

    /** Empty segment whose only job is to advertise our updated window. */
    fun ackSegment(): ByteArray {
        val f = emit(ByteArray(0), 0, 0, FLAG_ACK)
        return f
    }
}

/** A decoded record from a service's inbound byte stream: `{type, len, data}`. */
class Record(val type: Int, val data: ByteArray) {
    /** For command responses, data[0..3] is the result code. */
    val result: Int
        get() = if (data.size >= 4) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int else 0
    val body: ByteArray
        get() = if (data.size > 4) data.copyOfRange(4, data.size) else ByteArray(0)

    companion object {
        const val TYPE_RESPONSE = 1
        const val TYPE_PATCH_EVENT = 3
        /** {slotId, paramId, valueType, value}: a parameter the app subscribed to has changed. */
        const val TYPE_PARAM_EVENT = 2
        /** Same layout as [TYPE_PARAM_EVENT]: a parameter was changed (e.g. a knob turned on the amp). */
        const val TYPE_PARAM_CHANGED = 4
    }
}

/** Reassembles inbound stream bytes into [Record]s. */
class RecordParser {
    private var buf = ByteArray(0)

    /** Number of leading bytes still to discard (the Librarian's 4-byte hello). */
    @Volatile var skip: Int = 0

    fun feed(bytes: ByteArray): List<Record> {
        var data = bytes
        if (skip > 0) {
            val n = minOf(skip, data.size)
            data = data.copyOfRange(n, data.size)
            skip -= n
        }
        if (data.isNotEmpty()) buf += data
        val out = mutableListOf<Record>()
        while (buf.size >= 8) {
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            val type = bb.int
            val len = bb.int
            if (len < 0 || len > 65536) { buf = ByteArray(0); break } // desync: drop, resync on next reply
            if (buf.size < 8 + len) break
            out.add(Record(type, buf.copyOfRange(8, 8 + len)))
            buf = buf.copyOfRange(8 + len, buf.size)
        }
        return out
    }

    fun reset() { buf = ByteArray(0); skip = 0 }
}
