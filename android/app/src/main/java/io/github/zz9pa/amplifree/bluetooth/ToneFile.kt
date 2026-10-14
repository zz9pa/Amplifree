package io.github.zz9pa.amplifree.bluetooth

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A tone as a small, open, human-readable JSON file that people can share.
 *
 * It is Amplifree's own format, not a dump of the amp's stored tone: it names each block, the model
 * in it and the settings, using the names the amp itself reports, e.g.
 *
 * ```
 * {"format":"amplifree-tone","version":1,"name":"My Lead",
 *  "blocks":[{"slot":"Amp","model":"ModelName","params":{"Drive":0.63,"Bass":0.5}}]}
 * ```
 *
 * Loading a file applies it to the live tone through the same editor commands the sliders use
 * (swap a model, set a parameter), so no stored tone is rewritten and the amp checks what it is given.
 */
class ToneFile(val name: String, val blocks: List<Block>) {

    /** One module: its slot ("Amp", "FX1", ...), the model in it (if any) and its settings. */
    class Block(val slot: String, val model: String?, val params: Map<String, Any>)

    fun toJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("name", name)
        val arr = JSONArray()
        for (b in blocks) {
            val o = JSONObject()
            o.put("slot", b.slot)
            if (b.model != null) o.put("model", b.model)
            val p = JSONObject()
            for ((k, v) in b.params) p.put(k, v)
            o.put("params", p)
            arr.put(o)
        }
        root.put("blocks", arr)
        return root.toString(2)
    }

    /** Result of reading a file: the tone, or a short reason it was refused. */
    sealed class Parsed {
        class Ok(val tone: ToneFile) : Parsed()
        class Refused(val reason: String) : Parsed()
    }

    companion object {
        const val FORMAT = "amplifree-tone"
        const val VERSION = 1
        const val MAX_BYTES = 256 * 1024
        private const val MAX_BLOCKS = 64
        private const val MAX_PARAMS = 256
        private const val MAX_NAME = 64

        /** Read at most [MAX_BYTES] from [input]; anything longer comes back as text [parse] will refuse. */
        fun readLimited(input: java.io.InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) return "x".repeat(MAX_BYTES + 1)
                out.write(chunk, 0, n)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Names we accept for slots, models and parameters: printable ASCII, short. */
        private fun goodName(s: String) = s.isNotEmpty() && s.length <= MAX_NAME && s.all { it in ' '..'~' }

        /** Capture the tone in [preset]. Choice-type values are written by name so they survive another amp. */
        fun fromPreset(preset: PresetCodec.Preset, symbolName: (Int) -> String): ToneFile {
            val out = ArrayList<Block>()
            for (b in preset.blocks()) {
                val params = LinkedHashMap<String, Any>()
                for (p in b.params) {
                    val key = AmplifiProtocol.editorParamName(b.symbol, p.name)
                    when (p.record.type) {
                        PresetCodec.TYPE_FLOAT -> p.record.asFloat.takeIf { it.isFinite() }?.let { params[key] = it.toString().toDouble() }
                        PresetCodec.TYPE_BOOL -> params[key] = p.record.asBool
                        PresetCodec.TYPE_INT -> params[key] = p.record.asInt
                        PresetCodec.TYPE_ENUM -> params[key] = symbolName(p.record.asInt)
                    }
                }
                out.add(Block(b.symbol, b.flow, params))
            }
            return ToneFile(preset.name.trim().take(MAX_NAME), out)
        }

        /** Read and check a file's text. Anything unexpected is refused with a plain reason. */
        fun parse(text: String): Parsed {
            if (text.length > MAX_BYTES) return Parsed.Refused("That file is too large to be a tone.")
            val root = try { JSONObject(text) } catch (e: JSONException) {
                return Parsed.Refused("That isn't an Amplifree tone file.")
            }
            if (root.optString("format") != FORMAT) return Parsed.Refused("That isn't an Amplifree tone file.")
            if (root.optInt("version", 0) !in 1..VERSION) return Parsed.Refused("That tone file is from a newer Amplifree.")
            val arr = root.optJSONArray("blocks") ?: return Parsed.Refused("That tone file has no settings in it.")
            if (arr.length() == 0 || arr.length() > MAX_BLOCKS) return Parsed.Refused("That tone file isn't valid.")

            val blocks = ArrayList<Block>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return Parsed.Refused("That tone file isn't valid.")
                val slot = o.optString("slot")
                if (!goodName(slot)) return Parsed.Refused("That tone file isn't valid.")
                val model = if (o.has("model")) o.optString("model").also {
                    if (!goodName(it)) return Parsed.Refused("That tone file isn't valid.")
                } else null
                val po = o.optJSONObject("params") ?: JSONObject()
                if (po.length() > MAX_PARAMS) return Parsed.Refused("That tone file isn't valid.")
                val params = LinkedHashMap<String, Any>()
                for (k in po.keys()) {
                    if (!goodName(k)) return Parsed.Refused("That tone file isn't valid.")
                    when (val v = po.get(k)) {
                        is Boolean -> params[k] = v
                        is Number -> {
                            val d = v.toDouble()
                            if (!d.isFinite()) return Parsed.Refused("That tone file isn't valid.")
                            params[k] = if (v is Double || v is Float) d else v.toLong().toInt()
                        }
                        is String -> if (goodName(v)) params[k] = v else return Parsed.Refused("That tone file isn't valid.")
                        else -> return Parsed.Refused("That tone file isn't valid.")
                    }
                }
                blocks.add(Block(slot, model, params))
            }
            val name = root.optString("name").filter { it in ' '..'~' }.trim().take(MAX_NAME)
            return Parsed.Ok(ToneFile(name.ifEmpty { "Imported tone" }, blocks))
        }
    }
}

/**
 * Works out what applying a [ToneFile] to the live tone means, kept apart from the Bluetooth code so
 * it can be tested. Nothing here can make the amp do anything it could not do from the editor: models
 * are swapped only when the amp knows them and values are held to a sensible range.
 */
object ToneImport {

    class Swap(val slot: String, val model: String)

    /** Which parameter to set to what; [kind] says how to send it. */
    class Setting(val slot: String, val presetName: String, val kind: Kind, val value: Any) {
        enum class Kind { FLOAT, BOOL, INT }
    }

    class Plan(val swaps: List<Swap>)

    /** [unmatched] names the blocks left alone because the model they were written for is not the one loaded. */
    class ParamPlan(val sets: List<Setting>, val skipped: Int, val unmatched: List<String> = emptyList())

    /** Blocks whose model differs from what is live, for models the amp knows. The routing block is never swapped. */
    fun planSwaps(
        file: ToneFile,
        live: List<PresetCodec.Block>,
        idOf: (String) -> Int?,
        routing: String
    ): Plan {
        val swaps = ArrayList<Swap>()
        for (fb in file.blocks) {
            val model = fb.model ?: continue
            if (fb.slot == routing) continue
            val lb = live.firstOrNull { it.symbol == fb.slot } ?: continue
            if (lb.flow == model) continue
            if (idOf(fb.slot) == null || idOf(model) == null) continue
            swaps.add(Swap(fb.slot, model))
        }
        return Plan(swaps)
    }

    /**
     * The settings to send once the models are in place. A value is kept to the range this amp's
     * tones use (the amp itself will store whatever it is given), and anything that does not fit
     * the parameter's type or is unknown to the amp is skipped and counted.
     */
    fun planParams(
        file: ToneFile,
        live: List<PresetCodec.Block>,
        info: Map<String, ParamInfo>,
        idOf: (String) -> Int?,
        routing: String = AmplifiProtocol.ROUTING_BLOCK
    ): ParamPlan {
        val sets = ArrayList<Setting>()
        val unmatched = ArrayList<String>()
        var skipped = 0
        for (fb in file.blocks) {
            val lb = live.firstOrNull { it.symbol == fb.slot }
            if (lb == null) { skipped += fb.params.size; continue }
            // Settings written for one model must not be applied to another that happens to share names.
            if (fb.model != null && fb.slot != routing && lb.flow != fb.model) {
                skipped += fb.params.size
                unmatched += "${fb.slot}: ${fb.model}"
                continue
            }
            for ((key, v) in fb.params) {
                val p = lb.params.firstOrNull { AmplifiProtocol.editorParamName(lb.symbol, it.name) == key }
                if (p == null) { skipped++; continue }
                val planned = when (p.record.type) {
                    PresetCodec.TYPE_FLOAT -> {
                        val d = (v as? Number)?.toFloat()
                        if (d == null || !d.isFinite()) null else {
                            val cur = p.record.asFloat
                            val r = info[(lb.flow ?: lb.symbol) + "." + p.name]?.sliderRange(cur)
                                ?: if (cur in 0f..1f) 0f..1f else null
                            if (r == null) null else Setting(fb.slot, p.name, Setting.Kind.FLOAT, d.coerceIn(r.start, r.endInclusive))
                        }
                    }
                    PresetCodec.TYPE_BOOL -> when (v) {
                        is Boolean -> Setting(fb.slot, p.name, Setting.Kind.BOOL, v)
                        is Number -> Setting(fb.slot, p.name, Setting.Kind.BOOL, v.toInt() != 0)
                        else -> null
                    }
                    PresetCodec.TYPE_INT -> (v as? Number)?.toInt()?.takeIf { it in 0..1023 }
                        ?.let { Setting(fb.slot, p.name, Setting.Kind.INT, it) }
                    PresetCodec.TYPE_ENUM -> (v as? String)?.let(idOf)
                        ?.let { Setting(fb.slot, p.name, Setting.Kind.INT, it) }
                    else -> null
                }
                if (planned == null) skipped++ else sets.add(planned)
            }
        }
        return ParamPlan(sets, skipped, unmatched)
    }
}
