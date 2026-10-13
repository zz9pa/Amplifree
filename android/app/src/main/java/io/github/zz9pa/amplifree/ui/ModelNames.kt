package io.github.zz9pa.amplifree.ui

/**
 * Readable names for the amp / cab / effect models the amp reports by internal name.
 *
 * The display names below were copied (names only, no descriptions) from a publicly available
 * source: Line 6's knowledge-base page "Full list of AMPLIFi / FIREHAWK series amps, effects and
 * cabs", https://kb.line6.com/full-list-of-amplifi-firehawk-series-amps-effects-and-cabs
 * (retrieved 2026-10-13). They were matched to the amp's own internal names by hand; nothing here
 * comes from the manufacturer's app or from reverse engineering. Any model not listed falls back
 * to its internal name, tidied up. The names include third-party trademarks; Amplifree is not
 * affiliated with Line 6 or those owners (see NOTICE.md).
 */
object ModelNames {

    private val NAMES: Map<String, String> = mapOf(
        // ---- amps ----
        "AngelPBall" to "2002 Angel P-Ball",
        "BlackfaceLux" to "1964 Blackface 'Lux",
        "BlackfaceVibro" to "1963 Blackface Vibro",
        "BomberUber" to "2002 Bomber Uber",
        "BomberXTC" to "2002 Bomber X-TC",
        "BritGain18" to "Brit Gain 18",
        "BritGainJ2000" to "2003 Brit Gain J-2000",
        "BritGainJ2000_2" to "Brit J-2000 #2",
        "BritGainJ800" to "1990 Brit J-800",
        "BritGainJ900Clean" to "1992 Brit Gain J-900 Clean",
        "BritGainJ900Dist" to "1992 Brit Gain J-900 Dist",
        "BritGainSilverJ" to "1987 Brit Gain Silver J",
        "BritJMPre" to "1996 Brit JM Pre",
        "BritPlexi45" to "1965 Plexi 45",
        "BritPlexiBass100" to "1968 Brit Plexi Bass 100",
        "BritPlexiJumpLead" to "1968 Plexi Jump Lead",
        "BritPlexiLead100" to "1968 Plexi Lead 100",
        "BritPlexiLead200" to "1969 Brit Plexi Lead 200",
        "BritPlexiVariac" to "1968 Plexi Variac'd",
        "Bypass" to "No Amp",
        "CaliCrunch" to "1985 Cali Crunch",
        "CaliDiamondPlate" to "2001 Cali Diamond Plate",
        "CitrusD30" to "Citrus D-30",
        "ClassA15" to "1960 Class A-15",
        "ClassA30Fawn" to "Class A-30 Fawn",
        "ClassA30TopBoost" to "1967 Class A-30 Top Boost",
        "Connor50" to "2003 Connor 50",
        "DeityCrunch" to "2003 Deity Crunch",
        "DeityLead" to "2003 Deity Lead",
        "DeitySon" to "2003 Deity's Son",
        "DoubleShow" to "1967 Double Show",
        "DoubleVerb" to "1965 Double Verb",
        "GibtoneExpo" to "1960 Gibtone Expo",
        "Hiway100" to "1973 Hiway 100",
        "JazzClean" to "1987 Jazz Clean",
        "L6TubePreamp" to "Tube Instrument Preamp",
        "Line6AcousticAmp2" to "Line 6 Piezacoustic 2",
        "Line6Argo" to "Line 6 Agro",
        "Line6Bayou" to "Line 6 Bayou",
        "Line6BigBottom" to "Line 6 Big Bottom",
        "Line6Boutique1" to "Line 6 Boutique #1",
        "Line6ChemicalX" to "Line 6 Chemical X",
        "Line6ChnkChnk" to "Line 6 Chunk Chunk",
        "Line6Crunch" to "Line 6 Crunch",
        "Line6Fuzz" to "Line 6 Fuzz",
        "Line6Insane" to "Line 6 Insane",
        "Line6JTS_45" to "Line 6 JTS-45",
        "Line6Lunatic" to "Line 6 Lunatic",
        "Line6ModernHiGain" to "Line 6 Modern Hi Gain",
        "Line6Mood" to "Line 6 Mood",
        "Line6Octone" to "Line 6 Octone",
        "Line6Purge" to "Line 6 Purge",
        "Line6Smash" to "Line 6 Smash",
        "Line6Sparkle" to "Line 6 Sparkle",
        "Line6SparkleClean" to "Line 6 Sparkle Clean",
        "Line6SpinalPuppet" to "Line 6 Spinal Puppet",
        "Line6SuperClean" to "Line 6 Super Clean",
        "Line6SuperSparkle" to "Line 6 Super Sparkle",
        "Line6Throttle" to "Line 6 Throttle",
        "Line6Twang" to "Line 6 Twang",
        "Line6VariaxAcoustic" to "Line 6 Variax Acoustic",
        "MatchChief" to "1996 Match Chief",
        "MatchD30" to "1993 Match D-30",
        "MiniDouble" to "1996 Mini Double",
        "MississippiCriminal" to "2002 Mississippi Criminal",
        "SilverfaceBass" to "1972 Silverface Bass",
        "SmallTweed" to "1953 Small Tweed",
        "Solo100Head" to "1993 Solo 100 Head",
        "SuperO" to "1960s Super O",
        "SuperOThunder" to "1962 Super O Thunder",
        "TinyTweed" to "1960 Tiny Tweed",
        "TreadplateDual" to "2001 Treadplate Dual",
        "TweedBMan" to "1958 Tweed B-Man",
        "TwoTone" to "1960 Two-Tone",
        "WishbookSilver12" to "1967 Wishbook Silver 12",
        "ZenMaster" to "2001 Zen Master",

        // ---- cabs (the cabinet part only; mic / live-or-studio is added by [name]) ----
        "1x10Gibtone" to "1x10 Gibtone",
        "1x12Blackface" to "1x12 Blackface",
        "1x12Cali" to "1x12 Cali",
        "1x12ClassA" to "1x12 Class A",
        "1x12Line6" to "1x12 Line 6",
        "1x12Tweed" to "1x12 Tweed",
        "1x15Thunder" to "1x15 Thunder",
        "1x6SuperO" to "1x6 Super O",
        "1x8TinyTweed" to "1x8 Tiny Tweed",
        "2x12Blackface" to "2x12 Blackface",
        "2x12ClassA" to "2x12 Class A",
        "2x12ClassAFawn" to "2x12 Fawn",
        "2x12Jazz" to "2x12 Jazz",
        "2x12Line6" to "2x12 Line 6",
        "2x12Match" to "2x12 Match",
        "2x12Wishbook" to "2x12 Wishbook",
        "2x12ZenMaster" to "2x12 Zen Master",
        "2x2MiniT" to "2x2 Mini T",
        "4x10Line6" to "4x10 Line 6",
        "4x10Tweed" to "4x10 Tweed",
        "4x10TweedBMan" to "4x10 Tweed",
        "4x12BritT75" to "4x12 Brit T75",
        "4x12BritV30" to "4x12 Brit V30",
        "4x12Green20s" to "4x12 Green 20's",
        "4x12Green25s" to "4x12 Green 25's",
        "4x12Hiway100" to "4x12 Hiway 100",
        "4x12Line6" to "4x12 Line 6",
        "4x12Solo100" to "4x12 Solo 100",
        "4x12Treadplate" to "4x12 Treadplate",
        "NoCab4x12" to "No Cabinet",

        // ---- effects ----
        "AnalogChorus" to "Analog Chorus",
        "AnalogDelay" to "Analog Delay",
        "AnalogDelay_Mod" to "Analog Delay w/Modulation",
        "AnalogSquareChorus" to "Analog Square Chorus",
        "AutoPanner" to "Auto Pan",
        "AutoSwell" to "Auto Swell",
        "AutoWah" to "Auto Wah",
        "BassOverdrive" to "Bass Overdrive",
        "BiasTremolo" to "Bias Tremolo",
        "BlueComp" to "Blue Comp",
        "BlueCompTreb" to "Blue Comp Treb",
        "BuzzWave" to "Buzz Wave",
        "ClassicDistortion" to "Classic Distortion",
        "CleanSweep" to "Clean Sweep",
        "DingoTron" to "Dingo Tron",
        "DoubleBass" to "Double Bass",
        "EchoPlatter" to "Echo Platter",
        "FacialFuzz" to "Facial Fuzz",
        "FuzzPi" to "Fuzz Pi",
        "HiTalk" to "Hi Talk",
        "JetFlanger" to "Jet Flanger",
        "KillerZ" to "Killer Z",
        "L6BoostPlusEQ" to "Boost + EQ",
        "L6BubbleEcho" to "Bubble Echo",
        "L6DigitalDelay" to "Digital Delay",
        "L6ExpoFlang" to "Expo Flange",
        "L6Flanger" to "Line 6 Flanger",
        "L6LowRezDelay" to "Low Rez",
        "L6LumpyPhase" to "Lumpy Phase",
        "L6PhazeEko" to "Phaze Eko",
        "L6PingPongDelay" to "Ping Pong Delay",
        "L6ReverseDelay" to "Reverse Delay",
        "L6SineChorus" to "Sine Chorus",
        "Line6BronzeMaster" to "Bronze Master",
        "MultiHeadDelay" to "Multi-Head Delay",
        "OctaveFuzz" to "Octave Fuzz",
        "OptoTremolo" to "Opto Tremolo",
        "PODPurpleX" to "POD Purple X",
        "Phaser" to "Phaser",
        "RandomChorus" to "Random Chorus",
        "RedComp" to "Red Comp",
        "RezSynth" to "Rez Synth",
        "RotaryDrum" to "Rotary Drum",
        "RotaryDrum_Horn" to "Rotary Drum + Horn",
        "SampleAndHold" to "Random S & H",
        "Saturn5RingMod" to "Saturn 5 Ring Mod",
        "Screamer" to "Screamer",
        "SeismicSynth" to "Seismik Synth",
        "StereoDelay" to "Stereo Delay",
        "StereoExponentialChorus" to "Stereo Expo Chorus",
        "StereoSquareChorus" to "Stereo Square Chorus",
        "StereoSquareFlange" to "Stereo Square Flange",
        "SweepEcho" to "Sweep Echo",
        "Sweeper" to "Sweeper",
        "SynthAnalog" to "Synth Analog",
        "SynthFX" to "Synth FX",
        "SynthHarmony" to "Synth Harmony",
        "SynthLead" to "Synth Lead",
        "SynthString" to "Synth String",
        "TapeEater" to "Tape Eater",
        "TapeEcho" to "Tape Echo",
        "TubeDrive" to "Tube Drive",
        "TubeEcho" to "Tube Echo",
        "UVibe" to "U-Vibe",
        "VettaComp" to "Vetta Comp",
        "VettaJuice" to "Vetta Juice",
        "Warble_Matic" to "Warble-Matic",

        // ---- wahs ----
        "Chrome" to "Chrome",
        "ChromeCustom" to "Chrome Custom",
        "Colorful" to "Colorful",
        "Conductor" to "Conductor",
        "Fassel" to "Fassel",
        "Line6Vetta" to "Line 6",
        "Throaty" to "Throaty",
        "Weeper" to "Weeper",

        // ---- reverbs ----
        "BriteRoom1" to "Brite Room",
        "DarkHall1" to "Dark Hall",
        "KingSpring" to "King Spring",
        "LuxSpring" to "Lux Spring",
        "MediumHall" to "Medium Hall",
        "RichChamber2" to "Rich Chamber",
        "SmallRoom1" to "Small Room",
        "StandardSpring" to "Standard Spring",
        "TiledRoom1" to "Tiled Room"
    )

    /** Models by kind, used to offer every model the connected amp knows (not only those in its tones). */
    val AMPS: List<String> = listOf(
        "AngelPBall",
        "BlackfaceLux",
        "BlackfaceVibro",
        "BomberUber",
        "BomberXTC",
        "BritGain18",
        "BritGainJ2000",
        "BritGainJ2000_2",
        "BritGainJ800",
        "BritGainJ900Clean",
        "BritGainJ900Dist",
        "BritGainSilverJ",
        "BritJMPre",
        "BritPlexi45",
        "BritPlexiBass100",
        "BritPlexiJumpLead",
        "BritPlexiLead100",
        "BritPlexiLead200",
        "BritPlexiVariac",
        "Bypass",
        "CaliCrunch",
        "CaliDiamondPlate",
        "CitrusD30",
        "ClassA15",
        "ClassA30Fawn",
        "ClassA30TopBoost",
        "Connor50",
        "DeityCrunch",
        "DeityLead",
        "DeitySon",
        "DoubleShow",
        "DoubleVerb",
        "GibtoneExpo",
        "Hiway100",
        "JazzClean",
        "L6TubePreamp",
        "Line6AcousticAmp2",
        "Line6Argo",
        "Line6Bayou",
        "Line6BigBottom",
        "Line6Boutique1",
        "Line6ChemicalX",
        "Line6ChnkChnk",
        "Line6Crunch",
        "Line6Fuzz",
        "Line6Insane",
        "Line6JTS_45",
        "Line6Lunatic",
        "Line6ModernHiGain",
        "Line6Mood",
        "Line6Octone",
        "Line6Purge",
        "Line6Smash",
        "Line6Sparkle",
        "Line6SparkleClean",
        "Line6SpinalPuppet",
        "Line6SuperClean",
        "Line6SuperSparkle",
        "Line6Throttle",
        "Line6Twang",
        "Line6VariaxAcoustic",
        "MatchChief",
        "MatchD30",
        "MiniDouble",
        "MississippiCriminal",
        "SilverfaceBass",
        "SmallTweed",
        "Solo100Head",
        "SuperO",
        "SuperOThunder",
        "TinyTweed",
        "TreadplateDual",
        "TweedBMan",
        "TwoTone",
        "WishbookSilver12",
        "ZenMaster"
    )

    /** Effects for the FX 1-3 slots. */
    val EFFECTS: List<String> = listOf(
        "AnalogChorus",
        "AnalogDelay",
        "AnalogDelay_Mod",
        "AnalogSquareChorus",
        "AutoPanner",
        "AutoSwell",
        "AutoWah",
        "BassOverdrive",
        "BiasTremolo",
        "BlueComp",
        "BlueCompTreb",
        "BuzzWave",
        "ClassicDistortion",
        "CleanSweep",
        "DingoTron",
        "DoubleBass",
        "EchoPlatter",
        "FacialFuzz",
        "FuzzPi",
        "HiTalk",
        "JetFlanger",
        "KillerZ",
        "L6BoostPlusEQ",
        "L6BubbleEcho",
        "L6DigitalDelay",
        "L6ExpoFlang",
        "L6Flanger",
        "L6LowRezDelay",
        "L6LumpyPhase",
        "L6PhazeEko",
        "L6PingPongDelay",
        "L6ReverseDelay",
        "L6SineChorus",
        "Line6BronzeMaster",
        "MultiHeadDelay",
        "OctaveFuzz",
        "OptoTremolo",
        "PODPurpleX",
        "Phaser",
        "RandomChorus",
        "RedComp",
        "RezSynth",
        "RotaryDrum",
        "RotaryDrum_Horn",
        "SampleAndHold",
        "Saturn5RingMod",
        "Screamer",
        "SeismicSynth",
        "StereoDelay",
        "StereoExponentialChorus",
        "StereoSquareChorus",
        "StereoSquareFlange",
        "SweepEcho",
        "Sweeper",
        "SynthAnalog",
        "SynthFX",
        "SynthHarmony",
        "SynthLead",
        "SynthString",
        "TapeEater",
        "TapeEcho",
        "TubeDrive",
        "TubeEcho",
        "UVibe",
        "VettaComp",
        "VettaJuice",
        "Warble_Matic"
    )

    val WAHS: List<String> = listOf(
        "Chrome",
        "ChromeCustom",
        "Colorful",
        "Conductor",
        "Fassel",
        "Line6Vetta",
        "Throaty",
        "Weeper"
    )

    val REVERBS: List<String> = listOf(
        "BriteRoom1",
        "DarkHall1",
        "KingSpring",
        "LuxSpring",
        "MediumHall",
        "RichChamber2",
        "SmallRoom1",
        "StandardSpring",
        "TiledRoom1"
    )

    private val MICS = mapOf(
        "57OnAxis" to "57 on-axis",
        "57OffAxis" to "57 off-axis",
        "421" to "421",
        "U67" to "U67"
    )

    private val CAB = Regex("^(.+?)(?:Live2x12|Studio(57OnAxis|57OffAxis|421|U67))$")

    private val STUDIO_CAB = Regex("^\\d+x\\d+.*Studio(57OnAxis|57OffAxis|421|U67)$")
    private val LIVE_CAB = Regex("^\\d+x\\d+.*Live2x12$")

    /**
     * Everything the picker should offer for [block]: the models this amp's own tones have used
     * ([learned]) plus every model of the right kind that the amp's symbol [table] knows about.
     * The amp has the final say: it refuses a model that does not fit.
     */
    fun choices(block: String, table: List<String>, learned: List<String>): List<String> {
        val have = table.toHashSet()
        val extra = when (block) {
            "Amp" -> AMPS
            "StudioCab" -> table.filter { STUDIO_CAB.matches(it) }
            "LiveCab" -> table.filter { LIVE_CAB.matches(it) || it.startsWith("NoCab") }
            "FX1", "FX2", "FX3" -> EFFECTS
            "FX4" -> REVERBS
            "Wah" -> WAHS
            else -> emptyList()
        }
        return (learned + extra.filter { it in have }).distinct().sortedBy { name(it).lowercase() }
    }

    /** True if every word typed in [query] appears in the model's display or internal name. */
    fun matches(symbol: String, query: String): Boolean {
        val hay = (name(symbol) + " " + symbol).lowercase()
        return query.lowercase().split(' ').filter { it.isNotBlank() }.all { it in hay }
    }

    /** "BritPlexiLead100" -> "Brit Plexi Lead 100" (the fallback for names we have no entry for). */
    fun tidy(symbol: String): String =
        symbol.replace(Regex("(?<=[a-z])(?=[A-Z])|(?<![0-9]x)(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[A-Z])"), " ")
            .replace('_', ' ')

    /** Display name for a model's internal [symbol]. */
    fun name(symbol: String): String {
        NAMES[symbol]?.let { return it }
        val m = CAB.find(symbol) ?: return tidy(symbol)
        val base = NAMES[m.groupValues[1]] ?: tidy(m.groupValues[1])
        val mic = m.groupValues[2]
        return if (mic.isEmpty()) "$base (live)" else "$base (studio, ${MICS[mic] ?: mic})"
    }
}
