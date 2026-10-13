package io.github.zz9pa.amplifree

import io.github.zz9pa.amplifree.ui.ModelNames
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelNamesTest {
    @Test fun knownModelsGetTheirDisplayName() {
        assertEquals("1968 Plexi Lead 100", ModelNames.name("BritPlexiLead100"))
        assertEquals("Analog Delay w/Modulation", ModelNames.name("AnalogDelay_Mod"))
    }

    @Test fun cabsCombineBaseAndMic() {
        assertEquals("4x12 Brit V30 (studio, 57 on-axis)", ModelNames.name("4x12BritV30Studio57OnAxis"))
        assertEquals("4x12 Brit V30 (studio, U67)", ModelNames.name("4x12BritV30StudioU67"))
        assertEquals("2x12 Fawn (studio, 421)", ModelNames.name("2x12ClassAFawnStudio421"))
        assertEquals("1x12 Blackface (live)", ModelNames.name("1x12BlackfaceLive2x12"))
    }

    @Test fun searchMatchesEveryWordInAnyOrder() {
        assertEquals(true, ModelNames.matches("BlackfaceLux", "black"))
        assertEquals(true, ModelNames.matches("2x12BlackfaceStudio421", "blackface 2x12"))
        assertEquals(false, ModelNames.matches("BlackfaceLux", "plexi"))
        assertEquals(true, ModelNames.matches("BlackfaceLux", ""))
    }

    @Test fun choicesAddEverythingTheAmpKnowsOfTheRightKind() {
        val table = listOf("TweedBMan", "TinyTweed", "ZenMaster", "Phaser", "4x12BritV30Studio421", "1x12BlackfaceLive2x12", "Gain")
        val amps = ModelNames.choices("Amp", table, listOf("TweedBMan"))
        assertEquals(true, "TinyTweed" in amps && "ZenMaster" in amps)
        assertEquals(1, amps.count { it == "TweedBMan" })            // no duplicates
        assertEquals(false, "Phaser" in amps)
        assertEquals(listOf("4x12BritV30Studio421"), ModelNames.choices("StudioCab", table, emptyList()))
        assertEquals(listOf("1x12BlackfaceLive2x12"), ModelNames.choices("LiveCab", table, emptyList()))
        assertEquals(listOf("Phaser"), ModelNames.choices("FX2", table, emptyList()))
    }

    @Test fun unknownModelsFallBackToTheTidiedInternalName() {
        assertEquals("Slap Plate 1", ModelNames.name("SlapPlate1"))
        assertEquals("Some New Model 2", ModelNames.name("SomeNewModel2"))
    }
}
