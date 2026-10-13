package io.github.zz9pa.amplifree.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

data class TunerState(
    val isTunerActive: Boolean = false,
    val noteName: String = "—",
    val octave: Int = 0,
    val targetFrequency: Float = 0f,
    val currentFrequency: Float = 0f,
    val centsOffset: Float = 0f,      // -50..+50
    val isInTune: Boolean = false,
    val activeStringIndex: Int = -1,  // -1 = auto-detect
    val signalDetected: Boolean = false
)

data class GuitarString(val name: String, val octave: Int, val frequency: Float)

/**
 * What the tuner screen shows. The pitch comes from the amp's own tuner, which listens to the
 * guitar signal itself; nothing here uses the phone's microphone.
 *
 * The amp reports the note as semitones above A (modulo 12), so it carries no octave. Each
 * reading is therefore matched to a string by pitch class, and the cents error is the distance
 * to that string's note.
 */
class TunerEngine {

    companion object {
        private const val IN_TUNE_CENTS = 3f
    }

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state.asStateFlow()

    val standardStrings = listOf(
        GuitarString("E", 2, 82.41f),
        GuitarString("A", 2, 110.00f),
        GuitarString("D", 3, 146.83f),
        GuitarString("G", 3, 196.00f),
        GuitarString("B", 3, 246.94f),
        GuitarString("E", 4, 329.63f)
    )

    /** -1 selects auto-detect (nearest string). */
    fun selectString(index: Int) {
        _state.value = _state.value.copy(activeStringIndex = index)
    }

    fun setActive(active: Boolean) {
        _state.value = _state.value.copy(
            isTunerActive = active,
            signalDetected = false,
            noteName = "—",
            centsOffset = 0f,
            isInTune = false,
            currentFrequency = 0f
        )
    }

    /** A reading from the amp: semitones above A, or null when no note is being played. */
    fun publishNote(semitonesFromA: Float?) {
        if (semitonesFromA == null || semitonesFromA.isNaN() || semitonesFromA < 0f || semitonesFromA >= 12f) {
            _state.value = _state.value.copy(isTunerActive = true, signalDetected = false, isInTune = false, centsOffset = 0f)
            return
        }
        val sel = _state.value.activeStringIndex
        val candidates = if (sel in standardStrings.indices) listOf(standardStrings[sel]) else standardStrings
        var best = candidates.first()
        var bestDiff = Float.MAX_VALUE
        for (s in candidates) {
            val d = semitoneDistance(semitonesFromA, pitchClass(s.frequency))
            if (abs(d) < abs(bestDiff)) { best = s; bestDiff = d }
        }
        val cents = bestDiff * 100f
        _state.value = _state.value.copy(
            isTunerActive = true,
            noteName = best.name,
            octave = best.octave,
            targetFrequency = best.frequency,
            currentFrequency = (best.frequency * 2.0.pow(bestDiff / 12.0)).toFloat(),
            centsOffset = cents.coerceIn(-50f, 50f),
            isInTune = abs(cents) < IN_TUNE_CENTS,
            signalDetected = true
        )
    }

    /** Semitones above A, modulo 12, of [freq]. */
    private fun pitchClass(freq: Float): Float {
        val s = 12.0 * ln(freq / 440.0) / ln(2.0)
        return (((s % 12.0) + 12.0) % 12.0).toFloat()
    }

    /** Signed shortest distance from [to] to [from] around the 12-semitone circle, in -6..6. */
    private fun semitoneDistance(from: Float, to: Float): Float =
        (((from - to + 6f) % 12f + 12f) % 12f) - 6f
}
