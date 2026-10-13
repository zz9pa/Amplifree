package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.bluetooth.ParamStatsBuilder
import io.github.zz9pa.amplifree.bluetooth.PresetCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamStatsTest {
    @Test fun knobsInsideZeroToOneGetAUnitSlider() {
        val b = ParamStatsBuilder()
        b.addFloat("Amp.Drive", 0.2f); b.addFloat("Amp.Drive", 0.9f)
        val info = b.build().getValue("Amp.Drive")
        assertTrue(info.isUnit)
        assertEquals(0f..1f, info.sliderRange(0.5f))
    }

    @Test fun otherRangesAreLearnedFromTheValuesSeenWithAMargin() {
        val b = ParamStatsBuilder()
        b.addFloat("Gate.Thresh", -63f); b.addFloat("Gate.Thresh", -40f)
        val r = b.build().getValue("Gate.Thresh").sliderRange(-50f)
        assertTrue(r.start < -63f && r.endInclusive > -40f)
        assertTrue(r.start > -70f && r.endInclusive < -35f)
    }

    @Test fun aValueOutsideTheLearnedRangeStillFits() {
        val b = ParamStatsBuilder(); b.addFloat("X.Y", 1f); b.addFloat("X.Y", 2f)
        val r = b.build().getValue("X.Y").sliderRange(5f)
        assertTrue(5f in r)
    }

    @Test fun choicesAreCollectedDistinctAndSorted() {
        val b = ParamStatsBuilder()
        b.addChoice("Delay.Sync", PresetCodec.TYPE_ENUM, 30); b.addChoice("Delay.Sync", PresetCodec.TYPE_ENUM, 12)
        b.addChoice("Delay.Sync", PresetCodec.TYPE_ENUM, 30)
        assertEquals(listOf(12, 30), b.build().getValue("Delay.Sync").values)
    }
}
