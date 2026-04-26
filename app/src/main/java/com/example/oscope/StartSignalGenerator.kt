package org.mhrri.wavestudio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.abs

/**
 * Synthetic input waveform used when pressing the main "开始" button but no real mic input is present
 * (e.g., emulator / permissions / device without microphone).
 *
 * Goal: a periodic 440Hz-like test tone that is NOT a perfectly clean sine, so filters can be
 * tested under more realistic harmonic + interference conditions.
 */
internal object StartSignalGenerator {

    data class Params(
        val baseHz: Float = 440f,
        /** 0..1 */
        val harmonicLevel: Float = 0.35f,
        /** 0..1 */
        val noiseLevel: Float = 0.06f,
        /** 0..1 */
        val humLevel: Float = 0.08f,
        /** Deterministic seed so waveform is repeatable */
        val seed: Int = 1,
    )

    fun generate(sampleRate: Int, n: Int, params: Params = Params()): FloatArray {
        val out = FloatArray(n)
        val twoPi = (2.0 * PI)

        // xorshift32 deterministic noise
        var rng = if (params.seed != 0) params.seed else 1
        fun nextFloatSigned(): Float {
            var x = rng
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            rng = x
            // map to [-1,1]
            val u = (x ushr 1).toFloat() / Int.MAX_VALUE.toFloat()
            return (u * 2f - 1f)
        }

        val f0 = params.baseHz.coerceIn(20f, (sampleRate / 2f) - 200f)
        val hum50 = 50f
        val hum100 = 100f

        val h = params.harmonicLevel.coerceIn(0f, 1f)
        val nlv = params.noiseLevel.coerceIn(0f, 1f)
        val hum = params.humLevel.coerceIn(0f, 1f)

        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate.toDouble()
            val w = twoPi * f0.toDouble() * t

            // Base sine + harmonics (2nd/3rd/5th) to make it "complex".
            var y = sin(w)
            y += (0.30 * h) * sin(2.0 * w + 0.3)
            y += (0.22 * h) * sin(3.0 * w - 0.6)
            y += (0.10 * h) * sin(5.0 * w + 0.9)

            // Slight soft clipping to add more odd harmonics
            val yc = y.toFloat()
            val clipped = (yc / (1f + 0.7f * abs(yc))).toDouble()

            // Add hum + small ripple
            val humSig = (hum * 0.35f) * sin(twoPi * hum50.toDouble() * t) +
                (hum * 0.20f) * sin(twoPi * hum100.toDouble() * t + 0.4)

            // White-ish noise
            val noise = (nlv * 0.18f) * nextFloatSigned()

            out[i] = (clipped + humSig + noise).toFloat().coerceIn(-1f, 1f)
        }

        return out
    }
}
