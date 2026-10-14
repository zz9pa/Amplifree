package io.github.zz9pa.amplifree.bluetooth

/**
 * The form a tone takes when the amp stores it, made from the live tone.
 *
 * Compared with the live tone, a stored tone leaves out a few values that only make sense while playing
 * (the headphone and studio-mode switches, the volume pedal's position and one item of tone info), keeps
 * each block's parameter count in step with what it holds, and lists the tone-info items in a fixed
 * order, as do the blocks and the parameters inside each block. Everything else is the same, which is
 * what lets a tone be saved from the live one: saved back unedited, a tone comes out byte for byte as it
 * was stored (checked on an AMPLIFi 150).
 *
 * When the tone's source slot is known, what to leave out is worked out from it by comparison, so the
 * rule follows what this amp really stores. Without one, the values listed here are used.
 */
object StoredForm {

    /** Live-only settings, by block and parameter name, as seen on an AMPLIFi 150. */
    private val LIVE_ONLY = setOf(
        AmplifiProtocol.ROUTING_BLOCK to "headphoneState",
        AmplifiProtocol.ROUTING_BLOCK to "StudioModeState",
        "VolumePedal" to "VolumeState"
    )
    private val LIVE_ONLY_INFO = setOf(6)
    private val INFO_ORDER = listOf(0, 7, 8, 9, 4, 5, 1, 2, 3)

    /** [live] as it would be stored. [source] is the stored copy of the tone it came from, if known. */
    fun of(live: PresetCodec.Preset, source: PresetCodec.Preset?): PresetCodec.Preset {
        val dropParams = LinkedHashSet(LIVE_ONLY)
        val dropInfo = LinkedHashSet(LIVE_ONLY_INFO)
        var order = INFO_ORDER
        val paramOrder = HashMap<String, List<String>>()      // block -> its parameters in the stored order
        val blockOrder = source?.blocks()?.map { it.symbol }.orEmpty()

        if (source != null) {
            val stored = source.blocks()
            for (b in live.blocks()) {
                val s = stored.firstOrNull { it.symbol == b.symbol && it.flow == b.flow } ?: continue
                val have = s.params.map { it.name }.toSet()
                paramOrder[b.symbol] = s.params.map { it.name }
                b.params.filter { it.name !in have }.forEach { dropParams += b.symbol to it.name }
            }
            val liveInfo = infoKeys(live); val storedInfo = infoKeys(source)
            if (storedInfo.isNotEmpty()) {
                dropInfo += liveInfo - storedInfo.toSet()
                order = storedInfo
            }
        }

        // Copies, so the live tone shown in the editor is left as it is.
        val recs = live.records.map { PresetCodec.Record(it.key, it.type, it.raw?.copyOf()) }.toMutableList()
        val keep = BooleanArray(recs.size) { true }
        val countAt = HashMap<String, Int>()
        val kept = HashMap<String, Int>()
        var section = 0
        var block = ""
        for ((i, r) in recs.withIndex()) {
            when {
                r.key == PresetCodec.KEY_SECTION -> section = r.asInt
                r.key == PresetCodec.KEY_BLOCK && section == PresetCodec.SECTION_DSP -> block = live.sym(r.asInt)
                r.key == PresetCodec.KEY_PARAM_COUNT && section == PresetCodec.SECTION_DSP -> countAt[block] = i
                r.key >= 0 && section == PresetCodec.SECTION_DSP ->
                    if ((block to live.sym(r.key)) in dropParams) keep[i] = false
                    else kept[block] = (kept[block] ?: 0) + 1
                r.key >= 0 && section == PresetCodec.SECTION_PS && r.key in dropInfo -> keep[i] = false
            }
        }
        for ((b, i) in countAt) recs[i].setInt(kept[b] ?: 0)

        // Put the tone-info items in the stored order, in the places they already occupy.
        section = 0
        val infoAt = ArrayList<Int>()
        for ((i, r) in recs.withIndex()) {
            if (r.key == PresetCodec.KEY_SECTION) section = r.asInt
            else if (r.key >= 0 && section == PresetCodec.SECTION_PS && r.key != 0 && keep[i]) infoAt += i
        }
        val sorted = infoAt.map { recs[it] }.sortedBy { r -> order.indexOf(r.key).let { if (it < 0) Int.MAX_VALUE else it } }
        for ((n, i) in infoAt.withIndex()) recs[i] = sorted[n]

        // Put each block's parameters in the order the source stores them (those it does not list go last).
        if (paramOrder.isNotEmpty()) {
            section = 0; block = ""
            val byBlock = LinkedHashMap<String, MutableList<Int>>()
            for ((i, r) in recs.withIndex()) {
                when {
                    r.key == PresetCodec.KEY_SECTION -> section = r.asInt
                    r.key == PresetCodec.KEY_BLOCK && section == PresetCodec.SECTION_DSP -> block = live.sym(r.asInt)
                    r.key >= 0 && section == PresetCodec.SECTION_DSP && keep[i] && block in paramOrder ->
                        byBlock.getOrPut(block) { ArrayList() }.add(i)
                }
            }
            for ((b, at) in byBlock) {
                val want = paramOrder.getValue(b)
                val sortedRecs = at.map { recs[it] }.sortedBy { r -> want.indexOf(live.sym(r.key)).let { if (it < 0) Int.MAX_VALUE else it } }
                for ((n, i) in at.withIndex()) recs[i] = sortedRecs[n]
            }
        }

        val finalRecs = recs.filterIndexed { i, _ -> keep[i] }.toMutableList()
        return live.withRecords(inBlockOrder(live, finalRecs, blockOrder))
    }

    /** [recs] with the signal-chain blocks in [order] (blocks it does not list go last, in their own order). */
    private fun inBlockOrder(live: PresetCodec.Preset, recs: MutableList<PresetCodec.Record>, order: List<String>): MutableList<PresetCodec.Record> {
        if (order.isEmpty()) return recs
        var section = 0
        val starts = ArrayList<Int>()
        var end = recs.size
        for ((i, r) in recs.withIndex()) {
            when {
                r.key == PresetCodec.KEY_SECTION -> section = r.asInt
                r.key == PresetCodec.KEY_BLOCK && section == PresetCodec.SECTION_DSP -> starts += i
                r.key == PresetCodec.KEY_SECTION_END && section == PresetCodec.SECTION_DSP -> end = i
            }
        }
        if (starts.size < 2) return recs
        val ranges = starts.mapIndexed { n, s -> recs.subList(s, if (n + 1 < starts.size) starts[n + 1] else end).toList() }
        val sorted = ranges.sortedBy { rr -> order.indexOf(live.sym(rr.first().asInt)).let { if (it < 0) Int.MAX_VALUE else it } }
        val out = ArrayList<PresetCodec.Record>(recs.subList(0, starts.first()))
        sorted.forEach { out.addAll(it) }
        out.addAll(recs.subList(end, recs.size))
        return out
    }

    /** The item numbers in a tone's info section, in the order they are stored. */
    private fun infoKeys(p: PresetCodec.Preset): List<Int> {
        var section = 0
        val out = ArrayList<Int>()
        for (r in p.records) {
            if (r.key == PresetCodec.KEY_SECTION) section = r.asInt
            else if (r.key >= 0 && section == PresetCodec.SECTION_PS) out += r.key
        }
        return out
    }
}
