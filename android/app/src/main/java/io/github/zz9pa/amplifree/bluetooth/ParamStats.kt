package io.github.zz9pa.amplifree.bluetooth

/**
 * What the amp's own stored tones tell us about a parameter: the range its numbers have been
 * seen in, and for choice-type parameters the values that have been used.
 *
 * Nothing about parameter ranges ships with this app. They are learned at runtime from the tones on
 * the connected amp, so they are a good guide rather than a guarantee: a value the amp itself
 * refuses is simply refused.
 */
class ParamInfo(val type: Int, val min: Float, val max: Float, val values: List<Int>) {

    /** True if every value seen sits in 0..1 (the usual knob). */
    val isUnit: Boolean get() = min >= 0f && max <= 1f

    /**
     * Slider bounds for a float whose present value is [current].
     *
     * The amp does not publish limits (it stores whatever it is given, even -500), so they are
     * guessed from the values seen. Values that are all at or below zero are decibel levels, which
     * cannot sensibly go above 0 dB, and 16-bit audio has no level below about -96 dB; those two
     * facts bound such a slider instead of leaving it to the few values the tones happen to use.
     */
    fun sliderRange(current: Float): ClosedFloatingPointRange<Float> {
        val lo = minOf(min, current)
        val hi = maxOf(max, current)
        if (lo >= 0f && hi <= 1f) return 0f..1f
        val pad = (hi - lo).coerceAtLeast(1e-3f) * 0.1f
        if (hi <= 0f && lo < 0f) return maxOf(DB_FLOOR, lo - pad)..0f
        return (lo - pad)..(hi + pad)
    }

    private companion object { const val DB_FLOOR = -96f }
}

/** Accumulates [ParamInfo] while reading tones. */
class ParamStatsBuilder {
    private class Acc(val type: Int) {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        val values = sortedSetOf<Int>()
    }

    private val map = LinkedHashMap<String, Acc>()

    fun addFloat(key: String, v: Float) {
        if (v.isNaN() || v.isInfinite()) return
        val a = map.getOrPut(key) { Acc(PresetCodec.TYPE_FLOAT) }
        a.min = minOf(a.min, v); a.max = maxOf(a.max, v)
    }

    fun addChoice(key: String, type: Int, v: Int) {
        val a = map.getOrPut(key) { Acc(type) }
        if (a.values.size < 64) a.values.add(v)
    }

    fun build(): Map<String, ParamInfo> = map.mapValues { (_, a) ->
        ParamInfo(
            a.type,
            if (a.min.isInfinite()) 0f else a.min,
            if (a.max.isInfinite()) 0f else a.max,
            a.values.toList()
        )
    }
}
