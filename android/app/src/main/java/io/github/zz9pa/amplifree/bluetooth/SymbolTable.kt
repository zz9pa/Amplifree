package io.github.zz9pa.amplifree.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * The amp's symbol table: the dictionary that maps the numeric ids used on the
 * wire (and inside presets) to parameter / module names.
 *
 * The table is *not* bundled with this app. It is downloaded from the connected
 * amp on first use and cached in app-private storage, which is also what makes
 * the app correct across products: each model (and firmware) has its own table,
 * so a table baked into the app would be wrong for everything but one amp.
 *
 * Binary layout:
 *
 *     u32 count          number of symbols
 *     u32 totalSize      size of the whole table, header included
 *     count x 12 bytes   index entries (hash, length, offset) -- not needed to read
 *     NUL-separated      the names, in id order: a symbol's id is its position
 *
 * The handshake that opens each service sends the CRC-32 of the *entire* table
 * (header and index included) so the amp can tell whether the app's copy matches
 * its own.
 */
class SymbolTable(val raw: ByteArray) {

    val names: List<String> = parse(raw)

    private val ids: Map<String, Int> = names.withIndex().associate { (i, n) -> n to i }

    val size: Int get() = names.size

    fun id(name: String): Int? = ids[name]

    fun name(id: Int): String = names.getOrElse(id) { "sym#$id" }

    /** CRC-32 of the whole table, as sent in the handshake. */
    val crc32: Int = CRC32().also { it.update(raw) }.value.toInt()

    /** The handshake argument: the CRC as 4 little-endian bytes. */
    fun crcBytes(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc32).array()

    companion object {
        private const val HEADER = 8
        private const val ENTRY = 12

        /** True if [raw] is structurally a symbol table (guards a corrupt download/cache). */
        fun looksValid(raw: ByteArray): Boolean {
            if (raw.size < HEADER) return false
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int
            val total = bb.int
            if (count !in 1..100_000 || total != raw.size) return false
            val blobStart = HEADER.toLong() + count.toLong() * ENTRY
            if (blobStart >= raw.size) return false
            return parse(raw).size == count
        }

        private fun parse(raw: ByteArray): List<String> {
            if (raw.size < HEADER) return emptyList()
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count = bb.int
            val blobStart = HEADER + count * ENTRY
            if (count < 0 || blobStart >= raw.size) return emptyList()
            return String(raw, blobStart, raw.size - blobStart, Charsets.US_ASCII)
                .split('\u0000')
                .dropLastWhile { it.isEmpty() }
        }

        /** Build a table from names. Used by tests and tools; the app downloads the real one. */
        fun build(names: List<String>): ByteArray {
            val blob = names.joinToString("") { it + "\u0000" }.toByteArray(Charsets.US_ASCII)
            val total = HEADER + names.size * ENTRY + blob.size
            val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(names.size)
            bb.putInt(total)
            var off = 0
            for (n in names) {
                bb.putInt(n.hashCode())      // index entries are unused when reading
                bb.putInt(n.length + 1)
                bb.putInt(off)
                off += n.length + 1
            }
            bb.put(blob)
            return bb.array()
        }
    }
}
