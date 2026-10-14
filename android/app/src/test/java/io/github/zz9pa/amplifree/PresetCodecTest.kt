package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.AmplifiProtocol
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.bluetooth.SymbolTable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Preset and symbol-table handling, using only synthetic data (see [TestSupport]). */
class PresetCodecTest {

    private val table = TestSupport.symbolTable()

    // ---- symbol table ---------------------------------------------------------

    @Test
    fun symbolTableRoundTripsAndResolvesNames() {
        assertTrue(SymbolTable.looksValid(table.raw))
        assertEquals(TestSupport.SYMBOLS, table.names)
        assertEquals(TestSupport.SYMBOLS.indexOf("Amp"), table.id("Amp"))
        assertEquals("Drive", table.name(table.id("Drive")!!))
        assertEquals("unknown ids degrade gracefully", "sym#9999", table.name(9999))
    }

    @Test
    fun handshakeArgumentIsTheCrc32OfTheWholeTableLittleEndian() {
        val expected = CRC32().apply { update(table.raw) }.value.toInt()
        assertEquals(expected, table.crc32)
        val wire = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(expected).array()
        assertArrayEquals(wire, table.crcBytes())
    }

    @Test
    fun corruptTablesAreRejected() {
        assertFalse("empty", SymbolTable.looksValid(ByteArray(0)))
        assertFalse("truncated", SymbolTable.looksValid(table.raw.copyOf(table.raw.size - 5)))
        val wrongTotal = table.raw.copyOf().also { it[4] = (it[4] + 1).toByte() }
        assertFalse("size field disagrees with length", SymbolTable.looksValid(wrongTotal))
        assertFalse("garbage", SymbolTable.looksValid(ByteArray(64) { 0x7f }))
    }

    // ---- preset codec -----------------------------------------------------------

    @Test
    fun presetRoundTripsByteExact() {
        val body = TestSupport.preset()
        val p = PresetCodec.decode(body, table.names)
        assertArrayEquals(body, p.encode())
    }

    @Test
    fun signalChainIsDecodedIntoNamedBlocks() {
        val p = PresetCodec.decode(TestSupport.preset("My Tone"), table.names)
        assertEquals("My Tone", p.name)

        val blocks = p.blocks()
        assertEquals(listOf("GuitarProc", "Amp", "FX1"), blocks.map { it.symbol })
        assertEquals("ModelA", blocks.first { it.symbol == "Amp" }.flow)

        val drive = p.param("Amp", "DriveState")
        assertNotNull(drive)
        assertTrue(drive!!.isFloat)
        assertEquals(0.5f, drive.record.asFloat, 1e-6f)

        val sw = p.param("GuitarProc", "FX1EnableState")
        assertNotNull(sw)
        assertTrue(sw!!.isBool)
        assertTrue(sw.record.asBool)
        assertFalse(p.param("GuitarProc", "CabEnableState")!!.record.asBool)
    }

    @Test
    fun editingChangesOnlyTheEditedValue() {
        val body = TestSupport.preset(drive = 0.5f)
        val p = PresetCodec.decode(body, table.names)
        p.param("Amp", "DriveState")!!.record.setFloat(0.3f)
        val after = p.encode()

        assertEquals("a float edit must not change the length", body.size, after.size)
        assertEquals("exactly the 4 value bytes differ (0.5f and 0.3f differ in every byte)", 4, body.indices.count { body[it] != after[it] })

        val again = PresetCodec.decode(after, table.names)
        assertEquals(0.3f, again.param("Amp", "DriveState")!!.record.asFloat, 1e-6f)
        // FX1 has a parameter with the same name; it must be untouched
        assertEquals(0.75f, again.param("FX1", "DriveState")!!.record.asFloat, 1e-6f)
    }

    @Test
    fun switchesCanBeToggled() {
        val p = PresetCodec.decode(TestSupport.preset(), table.names)
        p.param("GuitarProc", "CabEnableState")!!.record.setBool(true)
        val again = PresetCodec.decode(p.encode(), table.names)
        assertTrue(again.param("GuitarProc", "CabEnableState")!!.record.asBool)
    }

    @Test
    fun renamingAChangesLengthButStaysDecodable() {
        val p = PresetCodec.decode(TestSupport.preset("Short"), table.names)
        p.name = "A much longer tone name"
        val again = PresetCodec.decode(p.encode(), table.names)
        assertEquals("A much longer tone name", again.name)
        assertEquals(3, again.blocks().size)
    }

    // ---- preset <-> editor naming -----------------------------------------------------

    /**
     * A preset stores `DriveState` while the Editor addresses the same knob as `Drive`.
     * They are different symbols with different ids, so sending the preset name is a
     * well-formed command that addresses the wrong parameter.
     */
    @Test
    fun editorParameterNamesDropTheStateSuffix() {
        assertEquals("Drive", AmplifiProtocol.editorParamName("Amp", "DriveState"))
        assertEquals("Bass", AmplifiProtocol.editorParamName("Amp", "BassState"))
        assertEquals(
            "the routing switches follow the same rule",
            "FX1Enable",
            AmplifiProtocol.editorParamName(AmplifiProtocol.ROUTING_BLOCK, "FX1EnableState")
        )
        // different symbols, different ids
        assertTrue(table.id("DriveState") != table.id("Drive"))
    }

    @Test
    fun everyModuleParameterInThePresetHasAnEditorCounterpart() {
        val p = PresetCodec.decode(TestSupport.preset(), table.names)
        for (b in p.blocks()) for (param in b.params) {
            val editorName = AmplifiProtocol.editorParamName(b.symbol, param.name)
            assertNotNull("${b.symbol}.${param.name} -> $editorName", table.id(editorName))
        }
    }

    @Test
    fun anUnknownRecordTypeKeepsTheBodyByteExact() {
        val table = TestSupport.symbolTable()
        val body = TestSupport.preset() + byteArrayOf(9, 0, 0, 0, 0x63, 0x00, 1, 2, 3, 4)   // type 0x63 is unknown
        val p = PresetCodec.decode(body, table.names)
        assertFalse(p.isComplete)
        assertArrayEquals(body, p.encode())
    }

    @Test
    fun aTruncatedRecordKeepsTheBodyByteExact() {
        val table = TestSupport.symbolTable()
        val body = TestSupport.preset() + byteArrayOf(9, 0, 0, 0, PresetCodec.TYPE_FLOAT.toByte(), 0, 1, 2)
        val p = PresetCodec.decode(body, table.names)
        assertFalse(p.isComplete)
        assertArrayEquals(body, p.encode())
    }

    @Test
    fun aMalformedShortBlockRecordDoesNotMakeBlocksThrow() {
        val short = PresetCodec.Record(PresetCodec.KEY_BLOCK, PresetCodec.TYPE_STRING, byteArrayOf(1, 2))
        val sec = PresetCodec.Record(PresetCodec.KEY_SECTION, PresetCodec.TYPE_INT, byteArrayOf(1, 0, 0, 0))
        val p = PresetCodec.Preset(mutableListOf(sec, short), ByteArray(0), listOf("A"))
        assertEquals(1, p.blocks().size)       // does not throw
    }
}
