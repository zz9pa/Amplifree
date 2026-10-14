package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.bluetooth.SymbolTable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Synthetic data for the tests. Nothing here is copied from a real amp or from the
 * manufacturer's software: the symbol names are made up, and the preset is built from
 * the documented record format.
 */
object TestSupport {

    /** A fake symbol table. Ids are positions, so the tests refer to names, never numbers. */
    val SYMBOLS: List<String> = listOf(
        "Alpha", "Beta", "Gamma",
        "DriveState", "Drive", "BassState", "Bass",
        "FX1EnableState", "CabEnableState",
        "Amp", "FX1", "GuitarProc",
        "ModelA", "ModelB", "ModelC",
        "Tuner", "Enable", "Note",
        "FX1Enable", "CabEnable",
        "headphoneState", "VolumePedal", "VolumeState"
    )

    fun symbolTable() = SymbolTable(SymbolTable.build(SYMBOLS))

    private fun id(name: String) = SYMBOLS.indexOf(name).also { require(it >= 0) { "no symbol $name" } }

    private class W {
        val bb: ByteBuffer = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun rec(key: Int, type: Int, value: Int) { bb.putInt(key); bb.putShort(type.toShort()); bb.putInt(value) }
        fun f(key: Int, v: Float) { bb.putInt(key); bb.putShort(PresetCodec.TYPE_FLOAT.toShort()); bb.putFloat(v) }
        fun end(key: Int) { bb.putInt(key); bb.putShort(0) }
        fun str(key: Int, s: String) {
            val b = (s + "\u0000").toByteArray(Charsets.US_ASCII)
            bb.putInt(key); bb.putShort(PresetCodec.TYPE_STRING.toShort()); bb.putInt(b.size); bb.put(b)
        }
        fun bytes(): ByteArray = bb.array().copyOf(bb.position())
    }

    /** A small but structurally faithful preset: info section, then three DSP blocks. */
    fun preset(name: String = "Test Tone", drive: Float = 0.5f, bass: Float = 0.25f, liveOnly: Boolean = false): ByteArray {
        val w = W()
        // preset-info section
        w.rec(PresetCodec.KEY_SECTION, PresetCodec.TYPE_INT, PresetCodec.SECTION_PS)
        w.rec(PresetCodec.KEY_MAGIC, PresetCodec.TYPE_INT, 0x50525350)
        w.str(0, name)
        if (liveOnly) {                                        // tone-info items: 6 is live-only
            w.rec(1, PresetCodec.TYPE_INT, 11)
            w.f(6, 0.8f)
            w.rec(7, PresetCodec.TYPE_INT, 77)
        } else {
            w.rec(7, PresetCodec.TYPE_INT, 77)
            w.rec(1, PresetCodec.TYPE_INT, 11)
        }
        w.end(PresetCodec.KEY_SECTION_END)
        // signal chain
        w.rec(PresetCodec.KEY_SECTION, PresetCodec.TYPE_INT, PresetCodec.SECTION_DSP)
        w.rec(PresetCodec.KEY_MAGIC, PresetCodec.TYPE_INT, 0x47545254)

        w.rec(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_ENUM, id("GuitarProc"))
        w.rec(PresetCodec.KEY_PARAM_COUNT, PresetCodec.TYPE_INT, if (liveOnly) 3 else 2)
        w.rec(id("FX1EnableState"), PresetCodec.TYPE_BOOL, 1)
        if (liveOnly) w.rec(id("headphoneState"), PresetCodec.TYPE_BOOL, 0)
        w.rec(id("CabEnableState"), PresetCodec.TYPE_BOOL, 0)

        w.rec(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_ENUM, id("Amp"))
        w.rec(PresetCodec.KEY_FLOW, PresetCodec.TYPE_ENUM, id("ModelA"))
        w.rec(PresetCodec.KEY_PARAM_COUNT, PresetCodec.TYPE_INT, 2)
        w.f(id("DriveState"), drive)
        w.f(id("BassState"), bass)

        w.rec(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_ENUM, id("FX1"))
        w.rec(PresetCodec.KEY_FLOW, PresetCodec.TYPE_ENUM, id("ModelB"))
        w.rec(PresetCodec.KEY_PARAM_COUNT, PresetCodec.TYPE_INT, 1)
        w.f(id("DriveState"), 0.75f)

        if (liveOnly) {
            w.rec(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_ENUM, id("VolumePedal"))
            w.rec(PresetCodec.KEY_PARAM_COUNT, PresetCodec.TYPE_INT, 1)
            w.f(id("VolumeState"), 1f)
        }

        w.end(PresetCodec.KEY_SECTION_END)
        return w.bytes()
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
