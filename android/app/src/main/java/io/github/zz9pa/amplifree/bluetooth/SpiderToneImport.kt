package io.github.zz9pa.amplifree.bluetooth

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads a Spider V style tone file (JSON with `"schema":"L6Preset"`) and turns it into a [ToneFile], so it
 * can be applied to the live tone like any other shared tone.
 *
 * Those tones name their models and parameters in plain text, using the same names this amp does, so
 * most of a tone carries straight over. What differs is the layout: the file has one entry per effect
 * with `@enabled`, `@mix`, `@post` and `@mixtype` beside the model's own settings, where the amp keeps
 * those on its routing block; and the file has one cabinet with a microphone number, where the amp has a
 * studio cabinet model with the microphone in its name, plus a live cabinet.
 *
 * Tones for other families (Helix, POD Go) use models this amp does not have and are refused.
 */
object SpiderToneImport {

    /** One entry of the file and where it goes on the amp's chain. */
    private class Slot(
        val key: String,
        val slot: String,
        val enable: String,
        val post: String? = null,
        val mix: String? = null,
        val mixType: String? = null
    )

    private val SLOTS = listOf(
        Slot("gate", "Gate", "GateEnable"),
        Slot("volume", "VolumePedal", "VolumePedalEnable", post = "VolumePost"),
        Slot("wah", "Wah", "WahEnable"),
        Slot("fx1", "FX1", "FX1Enable", "FX1Post", "FX1Mix", "FX1MixType"),
        Slot("fx2", "FX2", "FX2Enable", "FX2Post", "FX2Mix", "FX2MixType"),
        Slot("fx3", "FX3", "FX3Enable", "FX3Post", "FX3Mix", "FX3MixType"),
        Slot("reverb", "FX4", "FX4Enable", "FX4Post", "FX4Mix"),
        Slot("compressor", "PostComp", "PostCompEnable"),
        Slot("eq", "PostEQ", "PostEQEnable")
    )

    /**
     * The microphone number (0..3) in the file, as the suffix the amp puts on a studio cabinet's name.
     * Inferred from the order the suffixes are listed in, so it is the one mapping here that is not
     * confirmed by sound.
     */
    private val MIC_SUFFIX = listOf("57OnAxis", "57OffAxis", "421", "U67")

    private const val STUDIO = "Studio"
    private const val LIVE_SUFFIX = "Live2x12"

    private const val NOT_COMPATIBLE =
        "That is a Helix or POD Go tone. Its models do not exist on an AMPLIFi, so it can't be imported."
    private const val UNKNOWN = "That tone file isn't one Amplifree can read."

    /** Null when [text] is not this kind of file at all, so the caller can say something else. */
    fun parse(text: String): ToneFile.Parsed? {
        if (text.length > ToneFile.MAX_BYTES) return null
        val root = try { JSONObject(text) } catch (e: JSONException) { return null }
        if (root.optString("schema") != "L6Preset") return null
        val data = root.optJSONObject("data") ?: return ToneFile.Parsed.Refused(UNKNOWN)
        val tone = data.optJSONObject("tone") ?: return ToneFile.Parsed.Refused(UNKNOWN)
        if (tone.has("dsp0") || tone.has("dsp1")) return ToneFile.Parsed.Refused(NOT_COMPATIBLE)
        val amp = tone.optJSONObject("amp") ?: return ToneFile.Parsed.Refused(UNKNOWN)

        val routing = LinkedHashMap<String, Any>()
        val blocks = ArrayList<ToneFile.Block>()

        for (s in SLOTS) {
            val e = tone.optJSONObject(s.key) ?: continue
            if (e.has("@enabled")) routing[s.enable] = truth(e.opt("@enabled"))
            s.post?.let { p -> if (e.has("@post")) routing[p] = truth(e.opt("@post")) }
            s.mix?.let { m -> number(e.opt("@mix"))?.let { routing[m] = it } }
            s.mixType?.let { t -> number(e.opt("@mixtype"))?.let { routing[t] = it.toInt() } }
            val model = e.optString("@model").takeIf { it.isNotEmpty() }
            blocks.add(ToneFile.Block(s.slot, model, settings(e)))
        }

        // The amp: its volume is the channel volume on the routing block, and its gain knob is the amp's Drive.
        number(amp.opt("@volume"))?.let { routing["ChannelVolume"] = it }
        val ampSettings = LinkedHashMap<String, Any>()
        for ((k, v) in settings(amp)) ampSettings[if (k == "Gain") "Drive" else k] = v
        blocks.add(ToneFile.Block("Amp", amp.optString("@model").takeIf { it.isNotEmpty() }, ampSettings))

        // The cabinet: one model and a mic number becomes a studio cab (mic in the name) and a live cab.
        tone.optJSONObject("cab")?.let { cab ->
            val base = cab.optString("@model")
            if (base.endsWith(STUDIO)) {
                val mic = MIC_SUFFIX.getOrNull(number(cab.opt("@mic"))?.toInt() ?: -1)
                routing["CabEnable"] = true
                blocks.add(ToneFile.Block("StudioCab", if (mic != null) base + mic else null, emptyMap()))
                blocks.add(ToneFile.Block("LiveCab", base.removeSuffix(STUDIO) + LIVE_SUFFIX, emptyMap()))
            }
        }

        if (blocks.none { it.model != null }) return ToneFile.Parsed.Refused(UNKNOWN)
        blocks.add(0, ToneFile.Block(AmplifiProtocol.ROUTING_BLOCK, null, routing))

        val name = data.optJSONObject("meta")?.optString("name").orEmpty()
            .filter { it in ' '..'~' }.trim().take(64)
        return ToneFile.Parsed.Ok(ToneFile(name.ifEmpty { "Imported tone" }, blocks))
    }

    /** A model's own settings: its numbers and switches, not the `@` bookkeeping keys. */
    private fun settings(e: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (k in e.keys()) {
            if (k.startsWith("@")) continue
            when (val v = e.get(k)) {
                is Boolean -> out[k] = v
                is Double, is Float -> if (v.toDouble().isFinite()) out[k] = v.toDouble()
                is Number -> out[k] = v.toLong().toInt()
            }
        }
        return out
    }

    private fun number(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    private fun truth(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> false
    }
}

/** Reads a tone file in any format Amplifree understands: its own, or a Spider V style one. */
fun parseToneText(text: String): ToneFile.Parsed {
    val ours = ToneFile.parse(text)
    if (ours is ToneFile.Parsed.Ok) return ours
    return SpiderToneImport.parse(text) ?: ours
}
