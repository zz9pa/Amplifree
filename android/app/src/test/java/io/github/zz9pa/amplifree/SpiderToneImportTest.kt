package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.ToneFile
import io.github.zz9pa.amplifree.bluetooth.ToneImport
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import io.github.zz9pa.amplifree.bluetooth.SpiderToneImport
import io.github.zz9pa.amplifree.bluetooth.parseToneText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All the data here is made up, in the layout of the files, with invented model names. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpiderToneImportTest {

    private val sample = """
    {"schema":"L6Preset","version":5,"data":{"device":1,"meta":{"name":"My Test Tone","author":"someone"},
     "tone":{
      "gate":{"@model":"GateM","@enabled":1,"Thresh":-30.5,"Decay":0.25},
      "volume":{"@model":"VolM","@enabled":1,"@post":1,"Volume":1},
      "wah":{"@model":"WahM","@enabled":0,"Pedal":0.5},
      "fx1":{"@model":"DriveM","@enabled":1,"@mix":1,"@mixtype":0,"@post":0,"Gain":0.8,"Tone":0.4,"Drive":0.7},
      "fx2":{"@model":"DelayM","@enabled":1,"@mix":0.4,"@mixtype":1,"@post":1,"@temposync":1,"Time":0.2,"SyncSelect":4},
      "fx3":{"@model":"ChorusM","@enabled":0,"@mix":0.7,"@mixtype":1,"@post":0,"Depth":0.8},
      "reverb":{"@model":"HallM","@enabled":1,"@mix":0.6,"@post":1,"Decay":0.46},
      "amp":{"@model":"AmpM","@volume":0.23,"Gain":0.5,"Bass":1,"Mid":0.6,"Treble":0.75,"Presence":1},
      "cab":{"@model":"CabX4x12Studio","@mic":2,"Level":0},
      "compressor":{"@model":"CompM","@enabled":1,"Gain":0.77},
      "eq":{"@model":"EqM","@enabled":1,"Low":0.2,"gain":0.3},
      "global":{"@tempo":47.2}
     }}}
    """.trimIndent()

    private fun tone() = (SpiderToneImport.parse(sample) as ToneFile.Parsed.Ok).tone
    private fun block(slot: String) = tone().blocks.first { it.slot == slot }

    @Test fun slotsLandWhereTheAmpKeepsThem() {
        val t = tone()
        assertEquals("My Test Tone", t.name)
        assertEquals("DriveM", block("FX1").model)
        assertEquals("DelayM", block("FX2").model)
        assertEquals("HallM", block("FX4").model)               // the file's reverb is the amp's FX 4
        assertEquals("CompM", block("PostComp").model)
        assertEquals("EqM", block("PostEQ").model)
        assertEquals("VolM", block("VolumePedal").model)
    }

    @Test fun theAmpsGainKnobIsItsDriveAndItsVolumeIsTheChannelVolume() {
        val amp = block("Amp")
        assertEquals("AmpM", amp.model)
        assertEquals(0.5, (amp.params["Drive"] as Number).toDouble(), 1e-9)
        assertTrue("Gain" !in amp.params)
        assertEquals(0.23, (block("GuitarProc").params["ChannelVolume"] as Number).toDouble(), 1e-9)
    }

    @Test fun enableMixPostAndMixTypeMoveToTheRoutingBlock() {
        val r = block("GuitarProc").params
        assertEquals(true, r["FX1Enable"]); assertEquals(false, r["FX3Enable"]); assertEquals(false, r["WahEnable"])
        assertEquals(true, r["FX4Enable"]); assertEquals(true, r["FX4Post"]); assertEquals(false, r["FX1Post"])
        assertEquals(0.4, (r["FX2Mix"] as Number).toDouble(), 1e-9)
        assertEquals(1, r["FX2MixType"]); assertEquals(true, r["VolumePost"])
        assertEquals(true, r["GateEnable"])
        // the bookkeeping keys are not left behind as settings
        assertTrue(block("FX2").params.keys.none { it.startsWith("@") })
        assertEquals(setOf("Time", "SyncSelect"), block("FX2").params.keys)
    }

    @Test fun oneCabinetAndAMicBecomeAStudioCabWithTheMicInItsNameAndALiveCab() {
        assertEquals("CabX4x12Studio421", block("StudioCab").model)    // mic 2
        assertEquals("CabX4x12Live2x12", block("LiveCab").model)
        assertEquals(true, block("GuitarProc").params["CabEnable"])
    }

    @Test fun everyMicNumberHasASuffixAndAnOutOfRangeOneIsLeftOut() {
        fun studio(mic: Int) = (SpiderToneImport.parse(sample.replace("\"@mic\":2", "\"@mic\":$mic")) as ToneFile.Parsed.Ok)
            .tone.blocks.first { it.slot == "StudioCab" }.model
        assertEquals("CabX4x12Studio57OnAxis", studio(0))
        assertEquals("CabX4x12Studio57OffAxis", studio(1))
        assertEquals("CabX4x12StudioU67", studio(3))
        assertNull(studio(9))
    }

    @Test fun helixAndPodGoTonesAreRefusedWithAReason() {
        val helix = """{"schema":"L6Preset","version":6,"data":{"device":1,"tone":{"dsp0":{"block0":{"@model":"X"}}}}}"""
        val r = SpiderToneImport.parse(helix)
        assertTrue(r is ToneFile.Parsed.Refused)
        assertTrue((r as ToneFile.Parsed.Refused).reason.contains("Helix"))
    }

    @Test fun otherFilesAreNotClaimed() {
        assertNull(SpiderToneImport.parse("not json"))
        assertNull(SpiderToneImport.parse("""{"schema":"other"}"""))
    }

    @Test fun theCommonReaderTakesBothFormats() {
        assertTrue(parseToneText(sample) is ToneFile.Parsed.Ok)
        val own = ToneFile("Own", listOf(ToneFile.Block("Amp", "AmpM", mapOf("Drive" to 0.5)))).toJson()
        assertEquals("Own", (parseToneText(own) as ToneFile.Parsed.Ok).tone.name)
        assertTrue(parseToneText("hello") is ToneFile.Parsed.Refused)
    }

    // ---- applying a converted tone ------------------------------------------------------------

    @Test fun settingsForAModelTheAmpDidNotLoadAreNotAppliedToAnotherOne() {
        val names = TestSupport.SYMBOLS
        val live = PresetCodec.decode(TestSupport.preset(), names).blocks()     // Amp holds ModelA
        val f = ToneFile("T", listOf(
            ToneFile.Block("Amp", "SomeOtherModel", mapOf("Drive" to 0.9)),
            ToneFile.Block("FX1", "ModelB", mapOf("Drive" to 0.2))
        ))
        val plan = ToneImport.planParams(f, live, emptyMap(), idOf = { names.indexOf(it).takeIf { i -> i >= 0 } })
        assertEquals(listOf("Amp: SomeOtherModel"), plan.unmatched)
        assertEquals(listOf("FX1"), plan.sets.map { it.slot })            // the model that did match is applied
        assertEquals(1, plan.skipped)
        assertNotNull(plan)
    }
}
