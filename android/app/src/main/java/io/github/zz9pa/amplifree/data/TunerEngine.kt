package io.github.zz9pa.amplifree.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class TunerState(
    val isTunerActive: Boolean = false,
    val noteName: String = "—",
    val octave: Int = 0,
    val targetFrequency: Float = 0f,
    val currentFrequency: Float = 0f,
    val centsOffset: Float = 0f,      // -50..+50
    val isInTune: Boolean = false,
    val activeStringIndex: Int = -1,  // -1 = auto-detect
    val signalDetected: Boolean = false,
    val level: Float = 0f             // 0..1 input level, for a meter
)

data class GuitarString(val name: String, val octave: Int, val frequency: Float)

/**
 * Real guitar tuner: microphone capture plus autocorrelation pitch detection.
 *
 * Guitar fundamentals run 82 Hz (low E) to 330 Hz (high E). Autocorrelation is
 * a good fit here — it locks onto the fundamental rather than the strongest
 * harmonic, which matters for a guitar's rich overtones where FFT peak-picking
 * often reports an octave too high.
 */
class TunerEngine {

    companion object {
        private const val TAG = "TunerEngine"
        private const val SAMPLE_RATE = 44100
        private const val WINDOW = 4096          // ~93ms: >2 periods of low E
        private const val MIN_FREQ = 70f         // below low E
        private const val MAX_FREQ = 400f        // above high E
        private const val RMS_GATE = 0.008f      // ignore silence/room noise
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

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var job: Job? = null

    /** -1 selects auto-detect (nearest string). */
    fun selectString(index: Int) {
        _state.value = _state.value.copy(activeStringIndex = index)
    }

    fun toggleTuner(active: Boolean) {
        if (active) start() else stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (job != null) return
        _state.value = _state.value.copy(isTunerActive = true)
        job = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "AudioRecord unavailable (minBufferSize=$minBuf)")
                _state.value = _state.value.copy(isTunerActive = false)
                return@launch
            }
            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, WINDOW * 2 * 2)
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "RECORD_AUDIO permission not granted")
                _state.value = _state.value.copy(isTunerActive = false)
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialise")
                recorder.release()
                _state.value = _state.value.copy(isTunerActive = false)
                return@launch
            }

            val shorts = ShortArray(WINDOW)
            val buf = FloatArray(WINDOW)
            try {
                recorder.startRecording()
                while (isActive) {
                    var read = 0
                    while (read < WINDOW && isActive) {
                        val n = recorder.read(shorts, read, WINDOW - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < WINDOW) continue

                    var sum = 0.0
                    for (i in 0 until WINDOW) {
                        val v = shorts[i] / 32768f
                        buf[i] = v
                        sum += (v * v).toDouble()
                    }
                    val rms = sqrt(sum / WINDOW).toFloat()

                    if (rms < RMS_GATE) {
                        _state.value = _state.value.copy(signalDetected = false, level = rms * 12f)
                        continue
                    }
                    val freq = detectPitch(buf, rms)
                    if (freq == null) {
                        _state.value = _state.value.copy(signalDetected = false, level = rms * 12f)
                        continue
                    }
                    publish(freq, rms)
                }
            } catch (e: Exception) {
                Log.e(TAG, "tuner loop failed", e)
            } finally {
                try { recorder.stop() } catch (_: Exception) {}
                recorder.release()
            }
        }
    }

    private fun stop() {
        val j = job
        job = null
        _state.value = _state.value.copy(isTunerActive = false, signalDetected = false, level = 0f)
        if (j != null) scope.launch { j.cancelAndJoin() }
    }

    /**
     * Autocorrelation with parabolic interpolation around the peak.
     * Returns null when no confident periodic pitch is present.
     */
    private fun detectPitch(x: FloatArray, rms: Float): Float? {
        val minLag = (SAMPLE_RATE / MAX_FREQ).toInt()
        val maxLag = (SAMPLE_RATE / MIN_FREQ).toInt().coerceAtMost(x.size - 1)
        if (minLag >= maxLag) return null

        var bestLag = -1
        var bestCorr = 0f
        var prevCorr = 0f
        var rising = false

        for (lag in minLag..maxLag) {
            var corr = 0f
            var i = 0
            val n = x.size - lag
            while (i < n) { corr += x[i] * x[i + lag]; i++ }
            corr /= n

            // Only consider peaks after the correlation first starts rising:
            // avoids latching onto the trivial lag-0 shoulder.
            if (!rising && corr > prevCorr) rising = true
            if (rising && corr > bestCorr) { bestCorr = corr; bestLag = lag }
            prevCorr = corr
        }
        if (bestLag <= 0) return null

        // Confidence: normalised against signal energy.
        val energy = rms * rms
        if (energy <= 0f || bestCorr / energy < 0.5f) return null

        // Parabolic interpolation for sub-sample lag accuracy (cents matter).
        fun corrAt(lag: Int): Float {
            if (lag < 1 || lag >= x.size) return 0f
            var c = 0f; var i = 0; val n = x.size - lag
            while (i < n) { c += x[i] * x[i + lag]; i++ }
            return c / n
        }
        val y0 = corrAt(bestLag - 1); val y1 = bestCorr; val y2 = corrAt(bestLag + 1)
        val denom = 2f * (2f * y1 - y0 - y2)
        val shift = if (abs(denom) > 1e-9f) (y2 - y0) / denom else 0f
        val lag = bestLag + shift.coerceIn(-1f, 1f)
        val freq = SAMPLE_RATE / lag
        return if (freq in MIN_FREQ..MAX_FREQ) freq else null
    }

    /**
     * Feed a pitch measured elsewhere (the amp's own tuner). Null means "no note".
     * Display logic is shared with the microphone path.
     */
    fun publishExternal(freq: Float?) {
        if (freq == null || freq !in MIN_FREQ..MAX_FREQ) {
            _state.value = _state.value.copy(isTunerActive = true, signalDetected = false, level = 0f)
        } else {
            _state.value = _state.value.copy(isTunerActive = true)
            publish(freq, 0.1f)
        }
    }

    private fun publish(freq: Float, rms: Float) {
        val sel = _state.value.activeStringIndex
        val target = if (sel in standardStrings.indices) standardStrings[sel]
        else standardStrings.minByOrNull { abs(centsBetween(freq, it.frequency)) }!!

        val cents = centsBetween(freq, target.frequency)
        _state.value = _state.value.copy(
            noteName = target.name,
            octave = target.octave,
            targetFrequency = target.frequency,
            currentFrequency = freq,
            centsOffset = cents.coerceIn(-50f, 50f),
            isInTune = abs(cents) < IN_TUNE_CENTS,
            signalDetected = true,
            level = (rms * 12f).coerceIn(0f, 1f)
        )
    }

    private fun centsBetween(f: Float, ref: Float): Float =
        (1200.0 * ln((f / ref).toDouble()) / ln(2.0)).toFloat()
}
