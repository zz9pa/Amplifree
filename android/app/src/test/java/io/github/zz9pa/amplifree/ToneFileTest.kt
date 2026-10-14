package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.ParamInfo
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.bluetooth.ToneFile
import io.github.zz9pa.amplifree.bluetooth.ToneImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToneFileTest {
    private val names = TestSupport.SYMBOLS
    private fun idOf(n: String): Int? = names.indexOf(n).takeIf { it >= 0 }
    private fun live(drive: Float = 0.5f) =
        PresetCodec.decode(TestSupport.preset(drive = drive), names).blocks()
    private fun parse(t: String) = ToneFile.parse(t)
    private fun ok(t: String) = (parse(t) as ToneFile.Parsed.Ok).tone

    // ---- the file itself ----------------------------------------------------

    @Test fun aToneSurvivesAWriteAndRead() {
        val preset = PresetCodec.decode(TestSupport.preset("Lead", drive = 0.63f), names)
        val tone = ToneFile.fromPreset(preset) { names[it] }
        val back = ok(tone.toJson())
        assertEquals("Lead", back.name)
        val amp = back.blocks.first { it.slot == "Amp" }
        assertEquals("ModelA", amp.model)
        assertEquals(0.63, (amp.params["Drive"] as Number).toDouble(), 1e-6)
        // every name is written as the editor knows it (no "State" suffix), the routing block included
        assertEquals(true, back.blocks.first { it.slot == "GuitarProc" }.params["FX1Enable"])
    }

    @Test fun anythingThatIsNotOurFormatIsRefused() {
        assertTrue(parse("not json") is ToneFile.Parsed.Refused)
        assertTrue(parse("{}") is ToneFile.Parsed.Refused)
        assertTrue(parse("""{"format":"something-else","version":1,"blocks":[{"slot":"Amp"}]}""") is ToneFile.Parsed.Refused)
    }

    @Test fun aNewerVersionIsRefusedPolitely() {
        val r = parse("""{"format":"amplifree-tone","version":99,"blocks":[{"slot":"Amp"}]}""")
        assertTrue((r as ToneFile.Parsed.Refused).reason.contains("newer"))
    }

    @Test fun oversizedOrOddContentIsRefused() {
        assertTrue(parse("x".repeat(ToneFile.MAX_BYTES + 1)) is ToneFile.Parsed.Refused)
        val odd = """{"format":"amplifree-tone","version":1,"blocks":[{"slot":"Amp","params":{"Drive":[1,2]}}]}"""
        assertTrue(parse(odd) is ToneFile.Parsed.Refused)
        val ctl = "{\"format\":\"amplifree-tone\",\"version\":1,\"blocks\":[{\"slot\":\"A\\u0007mp\"}]}"
        assertTrue(parse(ctl) is ToneFile.Parsed.Refused)
    }

    @Test fun theNameIsTidiedAndDefaulted() {
        val t = ok("""{"format":"amplifree-tone","version":1,"name":"  ","blocks":[{"slot":"Amp"}]}""")
        assertEquals("Imported tone", t.name)
    }

    // ---- applying it ----------------------------------------------------------

    private fun file(vararg blocks: ToneFile.Block) = ToneFile("T", blocks.toList())

    @Test fun aDifferentKnownModelIsSwappedAndAnUnknownOneIsNot() {
        val f = file(
            ToneFile.Block("Amp", "ModelC", emptyMap()),
            ToneFile.Block("FX1", "NoSuchModel", emptyMap()),
            ToneFile.Block("GuitarProc", "ModelC", emptyMap())
        )
        val plan = ToneImport.planSwaps(f, live(), ::idOf, routing = "GuitarProc")
        assertEquals(listOf("Amp" to "ModelC"), plan.swaps.map { it.slot to it.model })
    }

    @Test fun theSameModelIsLeftAlone() {
        val f = file(ToneFile.Block("Amp", "ModelA", emptyMap()))
        assertTrue(ToneImport.planSwaps(f, live(), ::idOf, "GuitarProc").swaps.isEmpty())
    }

    @Test fun valuesAreKeptToTheRangeAndUnknownNamesAreCounted() {
        val f = file(
            ToneFile.Block("Amp", null, mapOf("Drive" to 7.0, "Bass" to 0.1, "Nope" to 0.5)),
            ToneFile.Block("GuitarProc", null, mapOf("FX1Enable" to false, "CabEnable" to 1))
        )
        val plan = ToneImport.planParams(f, live(), emptyMap(), ::idOf)
        val byName = plan.sets.associateBy { it.slot + "." + it.presetName }
        assertEquals(1.0f, byName.getValue("Amp.DriveState").value as Float, 0f)
        assertEquals(0.1f, byName.getValue("Amp.BassState").value as Float, 1e-6f)
        assertEquals(false, byName.getValue("GuitarProc.FX1EnableState").value)
        assertEquals(true, byName.getValue("GuitarProc.CabEnableState").value)
        assertEquals(1, plan.skipped)
    }

    @Test fun nonFiniteAndWrongTypedValuesAreSkipped() {
        val f = file(ToneFile.Block("Amp", null, mapOf("Drive" to Double.NaN, "Bass" to "loud")))
        val plan = ToneImport.planParams(f, live(), emptyMap(), ::idOf)
        assertTrue(plan.sets.isEmpty())
        assertEquals(2, plan.skipped)
    }

    @Test fun aLearnedRangeWidensWhatIsAccepted() {
        // a level in dB: the tones used -60..-20, and the amp would store anything, so the slider
        // range (not the file) decides what is sent
        val info = mapOf("ModelA.DriveState" to ParamInfo(PresetCodec.TYPE_FLOAT, -60f, -20f, emptyList()))
        val f = file(ToneFile.Block("Amp", null, mapOf("Drive" to -500.0)))
        val plan = ToneImport.planParams(f, live(drive = -30f), info, ::idOf)
        val v = plan.sets.single().value as Float
        assertTrue(v >= -96f && v < -60f)
    }
}
