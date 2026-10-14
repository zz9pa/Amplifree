package io.github.zz9pa.amplifree.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol spoken by Line 6 AMPLIFi amplifiers over Bluetooth SPP.
 *
 * This is an independent, unofficial implementation written from observing the
 * protocol; it is not affiliated with or endorsed by Line 6 / Yamaha. The layers:
 *
 *   RFCOMM/SPP -> RSMC   (12-byte reliable framing)       [this file]
 *              -> BSP    (8-byte transport header)        [this file]
 *              -> Stream (segmented byte stream)          [StreamLayer.kt]
 *              -> commands/records                        [AmplifiBluetoothManager.kt]
 *
 * Details that are easy to get wrong and fail silently (the amp acknowledges the
 * bytes at the link layer and then ignores them, which looks exactly like a dead
 * device):
 *
 *  - RSMC `seq` advances only on payload frames; acks/heartbeats repeat the last
 *    `seq`. The receiver delivers a payload only when seq == expected + 1.
 *  - `nak` is always `ack + 8` (an 8-frame window).
 *  - A zero-length RSMC frame is a valid heartbeat and its payload-CRC field is
 *    not meaningful (the amp sends 0x0000 where crc16(empty) is 0xFFFF).
 *  - Each service (Editor, Librarian) is a separate stream with its own counters
 *    and its own `src` address.
 */
object AmplifiProtocol {

    const val SPP_UUID_STRING = "00001101-0000-1000-8000-00805f9b34fb"

    val IAP_OK: ByteArray = "IAP x OK".toByteArray(Charsets.US_ASCII)
    val IAP_START: ByteArray = "IAP x START".toByteArray(Charsets.US_ASCII)
    val IAP_STOP: ByteArray = "IAP x STOP".toByteArray(Charsets.US_ASCII)

    /** Service endpoints. `src` differs per service; using one for both misroutes the other. */
    const val AUX_EDITOR = 0x03ED
    const val AUX_LIBRARIAN = 0x03EF
    const val SRC_EDITOR = 0x2002
    const val SRC_LIBRARIAN = 0x2003

    /** RSMC receive window: every frame carries nak = ack + 8. */
    const val WINDOW = 8

    fun srcForAux(aux: Int): Int = if (aux == AUX_EDITOR) SRC_EDITOR else SRC_LIBRARIAN

    // ---- command ids -------------------------------------------------------
    // Both services share ids 1..4. A command is the byte-stream record
    // {cmdId:u32, argLen:u32, args[argLen]}; the amp answers {type:u32, len:u32, data[len]}.

    const val CMD_GET_VERSION = 1
    const val CMD_GET_SYMBOL_TABLE_SIZE = 2
    const val CMD_GET_SYMBOL_TABLE = 3
    const val CMD_HANDSHAKE = 4

    // Librarian (preset storage)
    const val CMD_LIB_GET_NUM_PRESETS = 5
    const val CMD_LIB_GET_PRESET_FILE_SIZE = 0x0A
    const val CMD_LIB_GET_PRESET_FILE = 0x0B
    const val CMD_LIB_SET_PRESET_FILE = 0x0C
    const val CMD_LIB_LOAD_HARDWARE_PRESET = 0x0D
    const val CMD_LIB_GET_PRESET_NAME = 0x0E

    // Editor (the live DSP)
    const val CMD_ED_QUERY_DSP_MODEL = 7
    const val CMD_ED_LOAD_DSP_MODEL = 8
    const val CMD_ED_GET_DSP_MODEL_PARAM = 9
    const val CMD_ED_SET_DSP_MODEL_PARAM = 10
    const val CMD_ED_REGISTER_DSP_EVENT = 0x0B
    const val CMD_ED_UNREGISTER_DSP_EVENT = 0x0C
    const val CMD_ED_GET_DIRTY_STATE = 0x0F
    const val CMD_ED_GET_FBV_STATUS = 0x10

    /** Preset index meaning "the tone currently live on the amp". */
    const val LIVE_PRESET = -1

    /** The Librarian stream opens with a 4-byte version word in each direction. */
    val LIBRARIAN_HELLO: ByteArray = byteArrayOf(1, 0, 0, 0)

    // ---- RSMC framing -----------------------------------------------------

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, MSB-first). */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF
                else (crc shl 1) and 0xFFFF
            }
        }
        return crc and 0xFFFF
    }

    /**
     * RSMC frame: sync(2) zero(1) seq(1) ack(1) nak(1) len(2) payloadCrc(2) hdrCrc(2).
     * A zero-length frame is a valid header-only ack.
     */
    fun packRsmcFrame(seq: Int, ack: Int, nak: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteArray(12 + payload.size)
        buf[0] = 0x55; buf[1] = 0x55; buf[2] = 0
        buf[3] = (seq and 0xFF).toByte()
        buf[4] = (ack and 0xFF).toByte()
        buf[5] = (nak and 0xFF).toByte()
        buf[6] = (payload.size and 0xFF).toByte()
        buf[7] = ((payload.size shr 8) and 0xFF).toByte()
        val pCrc = crc16(payload)
        buf[8] = (pCrc and 0xFF).toByte()
        buf[9] = ((pCrc shr 8) and 0xFF).toByte()
        val hCrc = crc16(buf, 0, 12)        // computed with bytes 10..11 still zero
        buf[10] = (hCrc and 0xFF).toByte()
        buf[11] = ((hCrc shr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, 12, payload.size)
        return buf
    }

    // ---- BSP transport header ---------------------------------------------

    /** word0 = type<<28 | flags<<26 | dst ; word1 = aux<<16 | src */
    fun packBspHeader(type: Int, dst: Int, aux: Int, src: Int, flags: Int = 2): ByteArray {
        val b = ByteArray(8)
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(((type and 0xF) shl 28) or ((flags and 0x3) shl 26) or (dst and 0xFFFF))
        bb.putInt(((aux and 0xFFFF) shl 16) or (src and 0xFFFF))
        return b
    }

    data class BspHeader(val type: Int, val flags: Int, val dst: Int, val src: Int, val aux: Int)

    fun unpackBspHeader(p: ByteArray): BspHeader? {
        if (p.size < 8) return null
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val w0 = bb.int
        val w1 = bb.int
        return BspHeader(
            type = (w0 ushr 28) and 0xF,
            flags = (w0 ushr 26) and 0x3,
            dst = w0 and 0xFFFF,
            src = w1 and 0xFFFF,
            aux = (w1 ushr 16) and 0xFFFF
        )
    }

    // ---- command encoding -------------------------------------------------

    /** The byte-stream form of a command: `{cmdId, argLen}` then the arguments. */
    fun command(cmdId: Int, args: ByteArray = ByteArray(0)): ByteArray {
        val bb = ByteBuffer.allocate(8 + args.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmdId)
        bb.putInt(args.size)
        bb.put(args)
        return bb.array()
    }

    fun u32(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    /** SetPresetFile arguments: `{index, size, body}`; use [LIVE_PRESET] for the live tone. */
    fun setPresetFileArgs(presetBody: ByteArray, index: Int = LIVE_PRESET): ByteArray {
        val bb = ByteBuffer.allocate(8 + presetBody.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(index)
        bb.putInt(presetBody.size)
        bb.put(presetBody)
        return bb.array()
    }

    // ---- Editor: live parameters ------------------------------------------
    //
    // SetDspModelParam args = { slotId, paramId, valueType, value }, all u32.
    // slotId / paramId are symbol-table ids. valueType: int = 2, bool = 3, float = 4.

    const val VALUE_TYPE_INT = 2
    const val VALUE_TYPE_BOOL = 3
    const val VALUE_TYPE_FLOAT = 4

    private fun dspParamArgs(slotId: Int, paramId: Int, type: Int, rawValue: Int): ByteArray {
        val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(slotId); bb.putInt(paramId); bb.putInt(type); bb.putInt(rawValue)
        return bb.array()
    }

    fun setDspParamFloat(slotId: Int, paramId: Int, value: Float): ByteArray =
        dspParamArgs(slotId, paramId, VALUE_TYPE_FLOAT, java.lang.Float.floatToRawIntBits(value))

    fun setDspParamBool(slotId: Int, paramId: Int, value: Boolean): ByteArray =
        dspParamArgs(slotId, paramId, VALUE_TYPE_BOOL, if (value) 1 else 0)

    fun setDspParamInt(slotId: Int, paramId: Int, value: Int): ByteArray =
        dspParamArgs(slotId, paramId, VALUE_TYPE_INT, value)

    /** GetDspModelParam / RegisterDspEvent arguments: `{slotId, paramId}`. */
    fun dspEventArgs(slotId: Int, paramId: Int): ByteArray {
        val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(slotId); bb.putInt(paramId)
        return bb.array()
    }

    /**
     * Name out of a GetPresetName reply (the bytes after the result code): a 16-byte header,
     * then `{length:u32, text[length]}` with the text NUL-terminated.
     */
    fun parsePresetName(body: ByteArray): String {
        if (body.size >= 20) {
            val len = ByteBuffer.wrap(body, 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len in 0..(body.size - 20)) {
                return body.copyOfRange(20, 20 + len)
                    .takeWhile { it != 0.toByte() }.toByteArray()
                    .toString(Charsets.ISO_8859_1).trim()
            }
        }
        return ""
    }

    /** The symbol that holds the routing/enable switches rather than a single module. */
    const val ROUTING_BLOCK = "GuitarProc"

    /**
     * Translate a PRESET parameter name into the EDITOR parameter name.
     *
     * A preset stores `DriveState` while the Editor addresses the same knob as
     * `Drive`; they are different symbols with different ids, so sending the preset
     * name is a well-formed command that quietly addresses the wrong parameter.
     * Every parameter follows `<Name>State` -> `<Name>`, the routing block's switches and mix
     * levels (`FX1EnableState` -> `FX1Enable`) included. Verified on hardware: the amp answers the
     * `...State` name on the routing block with an error, so those controls did nothing until this.
     */
    fun editorParamName(block: String, presetParam: String): String = presetParam.removeSuffix("State")

    // ---- the amp's own tuner ------------------------------------------------
    //
    // Engage with SetDspModelParam(Tuner, Enable, 1), then RegisterDspEvent(Tuner,
    // Note). Readings arrive as parameter events {slot, param, float}: semitones above A (modulo
    // 12, so no octave), with -1 meaning no note.

    const val SYM_TUNER = "Tuner"
    const val SYM_TUNER_ENABLE = "Enable"
    const val SYM_TUNER_NOTE = "Note"


    // ---- RSMC decoding ----------------------------------------------------

    data class RsmcFrame(val seq: Int, val ack: Int, val nak: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as RsmcFrame
            return seq == other.seq && ack == other.ack &&
                    nak == other.nak && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int =
            (((seq * 31) + ack) * 31 + nak) * 31 + payload.contentHashCode()
    }

    /** Incremental RSMC decoder: hunts for sync, validates the header CRC, then the payload CRC. */
    class RsmcDecoder {
        private val buf = ArrayDeque<Byte>()
        var badFrames = 0
            private set

        /** Forget any partial frame (a new connection must not inherit the old one's leftovers). */
        fun reset() { buf.clear() }

        fun push(data: ByteArray, length: Int): List<RsmcFrame> {
            for (i in 0 until length) buf.addLast(data[i])
            val out = mutableListOf<RsmcFrame>()
            while (true) {
                while (buf.size >= 2 &&
                    !(buf[0] == 0x55.toByte() && buf[1] == 0x55.toByte())
                ) buf.removeFirst()
                if (buf.size < 12) return out

                val h = ByteArray(12) { buf[it] }
                val given = (h[10].toInt() and 0xFF) or ((h[11].toInt() and 0xFF) shl 8)
                val check = h.copyOf().also { it[10] = 0; it[11] = 0 }
                if (crc16(check, 0, 12) != given) {
                    badFrames++
                    buf.removeFirst()
                    continue
                }
                val len = (h[6].toInt() and 0xFF) or ((h[7].toInt() and 0xFF) shl 8)
                if (buf.size < 12 + len) return out

                repeat(12) { buf.removeFirst() }
                val payload = ByteArray(len) { buf.removeFirst() }
                // Zero-length frames are heartbeats; their payload-CRC field is not
                // validated (see the class comment). Validating it would reject every
                // heartbeat and make a healthy amp look dead.
                if (len > 0) {
                    val wantCrc = (h[8].toInt() and 0xFF) or ((h[9].toInt() and 0xFF) shl 8)
                    if (crc16(payload) != wantCrc) {
                        badFrames++
                        continue
                    }
                }
                out.add(RsmcFrame(h[3].toInt() and 0xFF, h[4].toInt() and 0xFF, h[5].toInt() and 0xFF, payload))
            }
        }
    }
}
