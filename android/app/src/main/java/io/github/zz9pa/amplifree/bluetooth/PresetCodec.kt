package io.github.zz9pa.amplifree.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decode / re-encode a preset: the ~1.2 kB body exchanged by GetPresetFile and
 * SetPresetFile. A preset IS the entire signal chain: every module in the path
 * with all of its parameter values.
 *
 * Wire format:
 *
 *     record := key:u32  type:u16  payload
 *         type 0     -> none
 *         type 1     -> u32 bool
 *         type 2     -> u32 int
 *         type 3     -> f32 float
 *         type 7     -> u32 enum/symbol id
 *         type 4     -> u32 length + length bytes (NUL-terminated)
 *
 *     0x80000000 section id   1 = DSP (signal chain), 2 = PS (preset info)
 *     0x80000001 magic        "PSRP" / "TRTG"
 *     0x80000002 section end
 *     0x80000003 block symbol e.g. "GuitarProc", "Amp", "FX1"
 *     0x80000005 block flow   the model, e.g. "BritGainJ800", "AnalogDelay"
 *     0x80000006 param count
 *
 * Every other key is a parameter whose key indexes the amp's symbol table
 * ([SymbolTable]), which is downloaded from the amp at runtime.
 *
 * Round-tripping is byte-exact, which is what makes editing safe: decode, change
 * a value, re-encode, push with SetPresetFile(index = LIVE_PRESET).
 */
object PresetCodec {

    const val KEY_SECTION = 0x80000000.toInt()
    const val KEY_MAGIC = 0x80000001.toInt()
    const val KEY_SECTION_END = 0x80000002.toInt()
    const val KEY_BLOCK = 0x80000003.toInt()
    const val KEY_FLOW = 0x80000005.toInt()
    const val KEY_PARAM_COUNT = 0x80000006.toInt()

    const val SECTION_DSP = 1
    const val SECTION_PS = 2

    const val TYPE_NONE = 0
    const val TYPE_BOOL = 1
    const val TYPE_INT = 2
    const val TYPE_FLOAT = 3
    const val TYPE_STRING = 4
    const val TYPE_ENUM = 7

    class Record(val key: Int, val type: Int, var raw: ByteArray?) {
        val asInt: Int
            get() = raw?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int } ?: 0
        val asFloat: Float
            get() = raw?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float } ?: 0f
        val asString: String
            get() = raw?.toString(Charsets.US_ASCII)?.trimEnd('\u0000') ?: ""

        val asBool: Boolean get() = asInt != 0

        fun setInt(v: Int) {
            raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        }

        fun setFloat(v: Float) {
            raw = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
        }

        fun setBool(v: Boolean) = setInt(if (v) 1 else 0)

        fun setString(v: String) {
            raw = (v + '\u0000').toByteArray(Charsets.US_ASCII)
        }

        fun encodedSize(): Int = when (type) {
            TYPE_NONE -> 6
            TYPE_STRING -> 6 + 4 + (raw?.size ?: 0)
            else -> 6 + 4
        }

        fun writeTo(bb: ByteBuffer) {
            bb.putInt(key)
            bb.putShort(type.toShort())
            when (type) {
                TYPE_NONE -> {}
                TYPE_STRING -> {
                    val r = raw ?: ByteArray(0)
                    bb.putInt(r.size)
                    bb.put(r)
                }
                else -> bb.put(raw ?: ByteArray(4))
            }
        }
    }

    /** One module in the signal chain. */
    class Block(val symbol: String, var flow: String?, val params: MutableList<Param> = mutableListOf())

    /** A named parameter bound to its underlying record. */
    class Param(val name: String, val record: Record) {
        val isBool get() = record.type == TYPE_BOOL
        val isFloat get() = record.type == TYPE_FLOAT
        val isInt get() = record.type == TYPE_INT || record.type == TYPE_ENUM
    }

    class Preset(val records: MutableList<Record>, val tail: ByteArray, private val symbols: List<String>) {

        fun sym(i: Int): String = symbols.getOrElse(i) { "sym#$i" }

        var name: String
            get() = records.firstOrNull { it.type == TYPE_STRING }?.asString ?: ""
            set(v) { records.firstOrNull { it.type == TYPE_STRING }?.setString(v) }

        /** Group the flat records into signal-chain blocks. */
        fun blocks(): List<Block> {
            val out = mutableListOf<Block>()
            var cur: Block? = null
            var section = 0
            for (r in records) {
                when {
                    r.key == KEY_SECTION -> section = r.asInt
                    r.key == KEY_BLOCK && section == SECTION_DSP -> {
                        cur?.let { out.add(it) }
                        cur = Block(sym(r.asInt), null)
                    }
                    r.key == KEY_FLOW && cur != null -> cur!!.flow = sym(r.asInt)
                    r.key == KEY_PARAM_COUNT -> {}
                    r.key >= 0 && cur != null && section == SECTION_DSP ->
                        cur!!.params.add(Param(sym(r.key), r))
                }
            }
            cur?.let { out.add(it) }
            return out
        }

        fun param(blockSymbol: String, paramName: String): Param? =
            blocks().firstOrNull { it.symbol == blockSymbol }
                ?.params?.firstOrNull { it.name == paramName }

        fun encode(): ByteArray {
            val size = records.sumOf { it.encodedSize() } + tail.size
            val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
            records.forEach { it.writeTo(bb) }
            bb.put(tail)
            return bb.array()
        }
    }

    fun decode(body: ByteArray, symbols: List<String>): Preset {
        val recs = mutableListOf<Record>()
        var i = 0
        val n = body.size
        while (i + 6 <= n) {
            val bb = ByteBuffer.wrap(body, i, n - i).order(ByteOrder.LITTLE_ENDIAN)
            val key = bb.int
            val type = bb.short.toInt() and 0xFFFF
            i += 6
            val raw: ByteArray? = when (type) {
                TYPE_NONE -> null
                TYPE_BOOL, TYPE_INT, TYPE_FLOAT, TYPE_ENUM -> {
                    if (i + 4 > n) break
                    body.copyOfRange(i, i + 4).also { i += 4 }
                }
                TYPE_STRING -> {
                    if (i + 4 > n) break
                    val len = ByteBuffer.wrap(body, i, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    i += 4
                    if (len < 0 || i + len > n) break
                    body.copyOfRange(i, i + len).also { i += len }
                }
                else -> break
            }
            recs.add(Record(key, type, raw))
        }
        return Preset(recs, body.copyOfRange(i, n), symbols)
    }
}
