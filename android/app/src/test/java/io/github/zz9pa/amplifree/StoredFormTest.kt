package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.bluetooth.StoredForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic data only: the symbols and values are made up. */
class StoredFormTest {
    private val names = TestSupport.SYMBOLS
    private fun live() = PresetCodec.decode(TestSupport.preset(liveOnly = true), names)
    private fun paramNames(p: PresetCodec.Preset, block: String) =
        p.blocks().first { it.symbol == block }.params.map { it.name }
    private fun infoKeys(p: PresetCodec.Preset): List<Int> {
        var section = 0
        return p.records.mapNotNull { r ->
            if (r.key == PresetCodec.KEY_SECTION) { section = r.asInt; null }
            else if (r.key >= 0 && section == PresetCodec.SECTION_PS) r.key else null
        }
    }
    private fun counts(p: PresetCodec.Preset) = p.records.filter { it.key == PresetCodec.KEY_PARAM_COUNT }.map { it.asInt }

    @Test fun liveOnlyValuesAreLeftOutAndTheCountsFollow() {
        val stored = StoredForm.of(live(), null)
        assertEquals(listOf("FX1EnableState", "CabEnableState"), paramNames(stored, "GuitarProc"))
        assertTrue(stored.blocks().first { it.symbol == "VolumePedal" }.params.isEmpty())
        assertEquals(listOf(0, 7, 1), infoKeys(stored))                        // 6 is gone, the rest in stored order
        assertEquals(listOf(2, 2, 1, 0), counts(stored))                       // routing, amp, fx1, volume pedal
    }

    @Test fun theNameAndEveryOtherValueSurvive() {
        val stored = StoredForm.of(live(), null)
        assertEquals("Test Tone", stored.name)
        val amp = stored.blocks().first { it.symbol == "Amp" }
        assertEquals(0.5f, amp.params.first { it.name == "DriveState" }.record.asFloat, 0f)
        assertEquals("ModelA", amp.flow)
    }

    @Test fun theLiveToneItselfIsNotChanged() {
        val l = live()
        val before = l.encode()
        StoredForm.of(l, null)
        assertTrue(before.contentEquals(l.encode()))
    }

    @Test fun theResultIsAWholeTone() {
        val stored = StoredForm.of(live(), null)
        val again = PresetCodec.decode(stored.encode(), names)
        assertTrue(again.isComplete)
        assertTrue(stored.encode().contentEquals(again.encode()))
    }

    @Test fun parametersFollowTheOrderTheSourceStoresThem() {
        val source = StoredForm.of(live(), null)
        // the source keeps the amp's two knobs the other way round
        val idx = source.records.indices.filter { source.records[it].key == names.indexOf("DriveState") ||
                                                  source.records[it].key == names.indexOf("BassState") }
        val a = source.records[idx[0]]; source.records[idx[0]] = source.records[idx[1]]; source.records[idx[1]] = a
        assertEquals(listOf("BassState", "DriveState"), paramNames(source, "Amp"))
        val stored = StoredForm.of(live(), source)
        assertEquals(listOf("BassState", "DriveState"), paramNames(stored, "Amp"))
    }

    @Test fun whatTheSourceDoesNotStoreIsLeftOutToo() {
        // a source that stores no tone-info item 1 and no CabEnableState: the saved tone follows it
        val source = StoredForm.of(live(), null)
        val trimmed = source.withRecords(source.records.filter { it.key != 1 && it.key != names.indexOf("CabEnableState") }.toMutableList())
        val stored = StoredForm.of(live(), trimmed)
        assertEquals(listOf("FX1EnableState"), paramNames(stored, "GuitarProc"))
        assertEquals(listOf(0, 7), infoKeys(stored))
    }

    @Test fun blocksFollowTheOrderTheSourceStoresThem() {
        fun order(p: PresetCodec.Preset) = p.blocks().map { it.symbol }
        val source = StoredForm.of(live(), null)
        // the source keeps two blocks the other way round (as a stored tone may, against the live one)
        val ranges = source.records.indices.filter { source.records[it].key == PresetCodec.KEY_BLOCK }
        val amp = ranges[1]; val fx = ranges[2]; val end = ranges[3]            // Amp, FX1, then VolumePedal
        val head = source.records.subList(0, amp).toList()
        val a = source.records.subList(amp, fx).toList(); val f = source.records.subList(fx, end).toList()
        val tail = source.records.subList(end, source.records.size).toList()
        val swapped = source.withRecords((head + f + a + tail).toMutableList())
        assertEquals(listOf("GuitarProc", "FX1", "Amp", "VolumePedal"), order(swapped))
        assertEquals(listOf("GuitarProc", "FX1", "Amp", "VolumePedal"), order(StoredForm.of(live(), swapped)))
    }
}
