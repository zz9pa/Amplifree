package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.AmplifiProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PresetNameTest {
    /** Header of 16 arbitrary bytes, then length and NUL-terminated text, as the amp sends it. */
    private fun reply(text: String, header: ByteArray = ByteArray(16) { (it * 7 + 3).toByte() }): ByteArray {
        val t = text.toByteArray(Charsets.ISO_8859_1) + 0
        return ByteBuffer.allocate(16 + 4 + t.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(header).putInt(t.size).put(t).array()
    }

    @Test fun readsNameAfterHeader() {
        assertEquals("Test Tone", AmplifiProtocol.parsePresetName(reply("Test Tone")))
    }

    @Test fun trailingSpaceIsTrimmed() {
        assertEquals("nothing.", AmplifiProtocol.parsePresetName(reply("nothing. ")))
    }

    @Test fun emptyOrTruncatedRepliesGiveEmptyName() {
        assertEquals("", AmplifiProtocol.parsePresetName(ByteArray(0)))
        assertEquals("", AmplifiProtocol.parsePresetName(ByteArray(12)))
        val lying = reply("abc").also { it[16] = 120 }          // length longer than the data
        assertEquals("", AmplifiProtocol.parsePresetName(lying))
    }
}
