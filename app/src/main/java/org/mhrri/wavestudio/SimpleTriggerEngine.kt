package org.mhrri.wavestudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Simple trigger engine: detects the steepest rising zero-crossing
 * and aligns the waveform display to it.
 *
 * Principle: find a stable crossing point and lock the display window around it.
 * No complex scoring, no autocorrelation, no fingerprinting.
 */
internal class SimpleTriggerEngine(
    private val windowSize: Int = 512,
) {
    enum class Mode { OFF, RISING, FALLING }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val preTriggerRatio: Float = 0.30f,
        val lowPassHz: Float = 200f,
    )

    data class Result(
        val anchorIndex: Int,
        val periodSamples: Int,
        val confidence: Float,
        val locked: Boolean,
        val mode: Mode,
        val freqHz: Float,
    )

    private var lp = FloatArray(0)
    private var lastAnchor = -1
    private var lastPeriod = 0
    private var lockCounter = 0
    private val lockThreshold = 3

    /**
     * Process a signal buffer and find the best trigger anchor point.
     *
     * Strategy:
     * 1. Apply a gentle low-pass to remove high-frequency noise
     * 2. Find all rising zero-crossings
     * 3. Pick the crossing with the steepest local slope (most reliable edge)
     * 4. Lock to that anchor if multiple consecutive frames agree on period
     */
    fun process(signal: FloatArray, config: Config): Result {
        val n = signal.size
        if (config.mode == Mode.OFF || n < 32) {
            lastAnchor = -1
            lastPeriod = 0
            lockCounter = 0
            return Result(0, 0, 0f, false, config.mode, 0f)
        }
        ensureCapacity(n)

        // Step 1: low-pass filter
        val filtered = applyLowPass(signal, n, config.sampleRateHz, config.lowPassHz)

        // Step 2: find rising zero-crossings
        val crossings = findRisingCrossings(filtered, n)

        if (crossings.isEmpty()) {
            lockCounter = max(0, lockCounter - 1)
            val anchor = lastAnchor.coerceIn(0, n - 1)
            return Result(anchor, lastPeriod.coerceAtLeast(0), 0f, lockCounter >= lockThreshold, config.mode, 0f)
        }

        // Step 3: score each crossing by slope steepness + period consistency
        var bestIdx = crossings[0]
        var bestScore = -1f
        var bestPeriod = 0

        for (crossing in crossings) {
            if (crossing !in 1 until n - 1) continue

            // Slope score: how steep is the edge at this crossing?
            val slope = abs(filtered[crossing + 1] - filtered[crossing - 1])

            // Period score: how consistent is this crossing with the last known period?
            val periodScore = if (lastPeriod > 0 && lastAnchor >= 0) {
                val expected = lastAnchor + lastPeriod
                val dist = abs(crossing - expected).toFloat()
                val tolerance = (lastPeriod * 0.40f).coerceAtLeast(8f)
                1f - (dist / tolerance).coerceIn(0f, 1f)
            } else 0f

            val score = slope * 0.7f + periodScore * 0.3f
            if (score > bestScore) {
                bestScore = score
                bestIdx = crossing
                bestPeriod = if (lastPeriod > 0) lastPeriod else periodFromCrossings(crossings)
            }
        }

        // Step 4: update lock state
        val currentPeriod = if (bestPeriod > 0) bestPeriod else periodFromCrossings(crossings)

        val periodConsistent = lastPeriod > 0 && currentPeriod > 0 &&
            abs(currentPeriod - lastPeriod).toFloat() / lastPeriod.toFloat() < 0.35f

        if (periodConsistent) {
            lockCounter = min(lockCounter + 1, lockThreshold * 2)
        } else {
            lockCounter = max(0, lockCounter - 2)
        }

        val locked = lockCounter >= lockThreshold

        if (locked || currentPeriod > 0) {
            lastPeriod = smooth(lastPeriod, currentPeriod, 0.3f)
        }
        lastAnchor = bestIdx

        val freqHz = if (lastPeriod > 1) config.sampleRateHz / lastPeriod.toFloat() else 0f
        val confidence = if (locked) 0.9f else if (periodConsistent) 0.5f else 0.2f

        return Result(
            anchorIndex = bestIdx,
            periodSamples = lastPeriod,
            confidence = confidence,
            locked = locked,
            mode = config.mode,
            freqHz = freqHz,
        )
    }

    /**
     * Extract a triggered window from source data.
     * Positions the anchor at preTriggerRatio * targetSize from the left.
     */
    fun extractWindow(source: FloatArray, result: Result, targetSize: Int, preTriggerRatio: Float): FloatArray {
        if (source.isEmpty() || result.mode == Mode.OFF) {
            return source.copyOf()
        }
        val tgt = targetSize.coerceAtLeast(64)
        val preSamples = (tgt * preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt().coerceAtLeast(1)
        val start = (result.anchorIndex - preSamples).coerceIn(0, max(0, source.size - tgt))
        val end = (start + tgt).coerceAtMost(source.size)
        val win = source.copyOfRange(start, end)
        return if (win.size == tgt) win else win + FloatArray(tgt - win.size) { 0f }
    }

    private fun findRisingCrossings(signal: FloatArray, n: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (i in 1 until n) {
            if (signal[i - 1] < 0f && signal[i] >= 0f) {
                result += i
            }
        }
        return result
    }

    private fun periodFromCrossings(crossings: List<Int>): Int {
        if (crossings.size < 2) return 0
        val last = crossings.last()
        val prev = crossings[crossings.size - 2]
        return (last - prev).coerceAtLeast(2)
    }

    private fun smooth(prev: Int, current: Int, alpha: Float): Int {
        if (prev <= 0) return current
        if (current <= 0) return prev
        return ((1f - alpha) * prev + alpha * current).roundToInt().coerceAtLeast(1)
    }

    private fun applyLowPass(x: FloatArray, n: Int, sampleRateHz: Float, cutoffHz: Float): FloatArray {
        val dt = 1f / sampleRateHz.coerceAtLeast(1f)
        val rc = 1f / (2f * 3.1415927f * cutoffHz.coerceAtLeast(5f))
        val alpha = dt / (rc + dt)
        var y = 0f
        for (i in 0 until n) {
            y += alpha * (x[i] - y)
            lp[i] = y
        }
        return lp
    }

    private fun ensureCapacity(n: Int) {
        if (lp.size < n) lp = FloatArray(n)
    }
}
