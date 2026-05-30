package org.mhrri.wavestudio

import kotlin.math.abs
import kotlin.math.max

/**
 * Lightweight time-domain autocorrelation for small buffers.
 * Designed for realtime UI usage with ~512-768 samples.
 */
internal object Autocorrelation {

    /**
     * Compute a short-lag autocorrelation sequence.
     *
     * @param x input samples
     * @param start start index in x
     * @param len number of samples used from x
     * @param maxLag maximum lag (output length)
     * @param out output buffer of size >= maxLag
     *
     * Output is normalized to roughly [-1, 1] (by energy at lag 0).
     */
    fun computeNormalized(
        x: FloatArray,
        start: Int,
        len: Int,
        maxLag: Int,
        out: FloatArray,
    ) {
        val n = len.coerceAtLeast(0)
        if (n <= 8 || maxLag <= 1) {
            for (i in 0 until maxLag) out[i] = 0f
            return
        }

        // Remove mean
        var sum = 0f
        for (i in 0 until n) sum += x[start + i]
        val mean = sum / n

        // Energy
        var e0 = 0f
        for (i in 0 until n) {
            val v = x[start + i] - mean
            e0 += v * v
        }
        e0 = max(e0, 1e-6f)

        val lagMax = maxLag.coerceAtMost(n - 1)
        for (lag in 0 until lagMax) {
            var acc = 0f
            var i = 0
            val end = n - lag
            while (i < end) {
                val a = x[start + i] - mean
                val b = x[start + i + lag] - mean
                acc += a * b
                i++
            }
            // Normalize by lag-0 energy approximation
            out[lag] = (acc / e0).coerceIn(-1f, 1f)
        }
        for (lag in lagMax until maxLag) out[lag] = 0f

        // Optional: emphasize periodic peaks, reduce DC by zeroing lag 0
        // out[0] = 0f
    }

    /** Simple peakiness metric (used for future debug / heuristics). */
    fun peakiness(x: FloatArray, n: Int): Float {
        if (n <= 8) return 0f
        var mx = 0f
        var s = 0f
        for (i in 0 until n) {
            val v = abs(x[i])
            if (v > mx) mx = v
            s += v
        }
        val avg = s / n
        return if (avg > 1e-6f) (mx / avg) else 0f
    }

    /**
     * Estimate period (lag in samples) from a normalized autocorrelation sequence.
     *
     * We pick the strongest positive peak in [lagMin, lagMax] with a simple local-maximum check.
     * Returns 0 if no reliable peak is found.
     */
    fun estimatePeriodFromAutocorr(
        ac: FloatArray,
        acLen: Int,
        dt: Float,
        fMinHz: Float = 20f,
        fMaxHz: Float = 3000f,
    ): Int {
        return estimatePeriodFromAutocorrSeeded(
            ac = ac,
            acLen = acLen,
            dt = dt,
            fMinHz = fMinHz,
            fMaxHz = fMaxHz,
            seedLag = 0,
        )
    }

    fun estimatePeriodFromAutocorrSeeded(
        ac: FloatArray,
        acLen: Int,
        dt: Float,
        fMinHz: Float = 20f,
        fMaxHz: Float = 3000f,
        seedLag: Int = 0,
    ): Int {
        val n = acLen.coerceAtMost(ac.size)
        if (n <= 16) return 0
        val fs = (1f / dt).takeIf { it.isFinite() && it > 1f } ?: return 0

        val lagMin = (fs / fMaxHz.coerceAtLeast(1f)).toInt().coerceIn(2, n - 2)
        val lagMax = (fs / fMinHz.coerceAtLeast(1f)).toInt().coerceIn(lagMin + 1, n - 2)

        var bestLag = 0
        var bestScore = Float.NEGATIVE_INFINITY
        val seeded = seedLag in lagMin..lagMax

        // ignore lag 0; scan for peaks
        for (lag in lagMin..lagMax) {
            val v = ac[lag]
            if (v <= 0f) continue
            // local maximum
            val isPeak = v >= ac[lag - 1] && v >= ac[lag + 1]
            if (!isPeak) continue

            val proximityBonus = if (seeded) {
                val dist = abs(lag - seedLag).toFloat()
                0.20f * (1f - (dist / seedLag.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
            } else 0f
            val harmonicPenalty = if (seeded && lag < seedLag * 0.75f) 0.12f else 0f
            val score = v + proximityBonus - harmonicPenalty
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }

        // Require some peak strength; otherwise it's probably noise.
        return if (bestLag > 0 && ac[bestLag] >= 0.15f) bestLag else 0
    }
}
