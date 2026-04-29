package org.mhrri.wavestudio

import kotlin.collections.iterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class NewTriggerEngine(
    private val nominalWindowSize: Int = 512,
) {
    companion object {
        private const val DEFAULT_TRIGGER_THRESHOLD = 0.02f
        private const val TRIGGER_CROSSING_HYSTERESIS_FLOOR = 0.002f
        private const val TRIGGER_CROSSING_HYSTERESIS_RATIO = 0.18f
        private const val TRIGGER_WEAK_SIGNAL_RMS_FLOOR = 0.006f
        private const val TRIGGER_MAX_ACCEPTED_PERIOD_JUMP_RATIO = 0.12f
        private const val TRIGGER_PHASE_ERROR_EMA_ALPHA = 0.12f
        private const val PERIOD_EMA_NEW_WEIGHT = 0.18f
    }

    enum class Mode {
        OFF,
        RISING,
        FALLING,
    }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val strongLowPassHz: Float = 160f,
        val fMinHz: Float = 5f,
        val fMaxHz: Float = 2000f,
        val useAutocorrelation: Boolean = false,
        val autocorrRefreshFrames: Int = 8,
        val autocorrMaxSamples: Int = 512,
        val preTriggerRatio: Float = 0.22f,
        val hysteresisRatio: Float = 0.16f,
        val holdoffRatio: Float = 0.60f,
        val maxStepPerFrame: Int = 10,
        val lockEnterConfidence: Float = 0.24f,
        val lockExitConfidence: Float = 0.10f,
        val unlockAfterBadFrames: Int = 6,
        val periodSmoothAlpha: Float = 0.18f,
        val triggerThreshold: Float = DEFAULT_TRIGGER_THRESHOLD,
        val triggerCrossingHysteresisFloor: Float = TRIGGER_CROSSING_HYSTERESIS_FLOOR,
        val triggerCrossingHysteresisRatio: Float = TRIGGER_CROSSING_HYSTERESIS_RATIO,
        val triggerWeakSignalRmsFloor: Float = TRIGGER_WEAK_SIGNAL_RMS_FLOOR,
        val triggerMaxAcceptedPeriodJumpRatio: Float = TRIGGER_MAX_ACCEPTED_PERIOD_JUMP_RATIO,
        val triggerHoldoffMs: Float = 1.0f,
        val triggerEdgeConsistencyRadius: Int = 10,
        val triggerPhaseErrorEmaAlpha: Float = TRIGGER_PHASE_ERROR_EMA_ALPHA,
        val triggerPreferRisingPrimaryReference: Boolean = true,
    )

    data class Result(
        val startIndex: Int,
        val anchorIndex: Int,
        val periodSamples: Int,
        val confidence: Float,
        val locked: Boolean,
        val mode: Mode,
        val freqHz: Float,
    )

    private data class TriggerLockState(
        var emaPeriodSamples: Float = 0f,
        var triggerFingerprint: FloatArray? = null,
        var lastAbsoluteAnchorIndex: Long = -1L,
        var phaseErrorSamples: Float = 0f,
        var confidenceEma: Float = 0f,
        var badFrames: Int = 0,
        var locked: Boolean = false,
    )

    private data class ScoredCrossing(
        val index: Int,
        val score: Float,
        val confidence: Float,
        val phaseError: Int,
    )

    private var lp = FloatArray(0)
    private var ac = FloatArray(0)

    private var estimatedPeriodSamples: Int = 0
    private var lastTriggerAnchor: Int = -1
    private var processFrameIndex: Int = 0
    private val lockState = TriggerLockState()

    fun process(x: FloatArray, config: Config): Result {
        val n = x.size
        val maxStart = max(0, n - nominalWindowSize)
        val preferredAnchor = preferredAnchorSamples()
        processFrameIndex++
        if (config.mode == Mode.OFF || n < 64) {
            lockState.locked = false
            lockState.badFrames = 0
            val start = defaultStart(n)
            val anchor = (start + (nominalWindowSize * config.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
            return Result(start, anchor, 0, 0f, false, config.mode, 0f)
        }

        ensureCapacity(n)
        // Output-waveform mode: trigger directly on the same array that will be displayed.
        val triggerSignal = x
        val signalRms = rms(triggerSignal, n)
        val adaptiveThreshold = config.triggerThreshold
        val edgeMode = primaryMode(config)
        val allCrossings = collectHysteresisCrossings(
            x = triggerSignal,
            n = n,
            mode = edgeMode,
            threshold = adaptiveThreshold,
            rms = signalRms,
            config = config,
        )

        if (signalRms >= config.triggerWeakSignalRmsFloor) {
            val crossingCandidates = staggeredFundamentalPeriodCandidates(
                crossings = allCrossings,
                sampleRateHz = config.sampleRateHz,
                fMinHz = config.fMinHz,
                fMaxHz = config.fMaxHz,
            )
            var observedPeriodSamples = chooseFundamentalPeriodCandidate(
                candidates = crossingCandidates,
                seed = estimatedPeriodSamples,
            )
            if (config.useAutocorrelation && n >= 96) {
                val acPeriod = estimatePeriodFromAutocorrelation(triggerSignal, n, config, observedPeriodSamples)
                if (acPeriod > 0) observedPeriodSamples = acPeriod
            }
            if (observedPeriodSamples > 0) {
                estimatedPeriodSamples = updatePeriodState(estimatedPeriodSamples, observedPeriodSamples, config)
                lockState.emaPeriodSamples = smoothFloat(lockState.emaPeriodSamples, estimatedPeriodSamples.toFloat(), PERIOD_EMA_NEW_WEIGHT)
            }
        }

        val holdoffSamples = holdoffSamples(config.sampleRateHz, config.triggerHoldoffMs)
        val validCrossings = ArrayList<Int>(allCrossings.size)
        var lastKept = Int.MIN_VALUE / 4
        for (crossing in allCrossings) {
            if (crossing - lastKept < holdoffSamples) continue
            if (isLegalAnchor(crossing, n, preferredAnchor)) {
                validCrossings += crossing
                lastKept = crossing
            }
        }
        if (validCrossings.isEmpty()) {
            return fallbackResult(n, config)
        }

        val predictedAnchor = predictedAnchor(estimatedPeriodSamples, lastTriggerAnchor, n)
        val bestCrossing = chooseBestTriggerCrossing(
            signal = triggerSignal,
            crossings = validCrossings,
            mode = edgeMode,
            predictedAnchor = predictedAnchor,
            periodSamples = estimatedPeriodSamples,
            searchRadius = phaseSearchRadius(estimatedPeriodSamples),
            priorFingerprint = lockState.triggerFingerprint,
            edgeRadius = config.triggerEdgeConsistencyRadius,
        )
        if (bestCrossing == null) {
            return fallbackResult(n, config)
        }

        val anchorIndex = bestCrossing.index
        val phaseErr = if (predictedAnchor >= 0) anchorIndex - predictedAnchor else 0
        lockState.phaseErrorSamples = smoothFloat(
            lockState.phaseErrorSamples,
            phaseErr.toFloat(),
            config.triggerPhaseErrorEmaAlpha.coerceIn(0f, 1f),
        )
        lastTriggerAnchor = anchorIndex

        val startIndex = (anchorIndex - preferredAnchor).coerceIn(0, maxStart)

        val periodSamples = estimatedPeriodSamples

        val freqHz = if (periodSamples > 1) config.sampleRateHz / periodSamples.toFloat() else 0f
        val confidenceAdjusted = if (signalRms < config.triggerWeakSignalRmsFloor && periodSamples > 0) {
            (bestCrossing.confidence * 0.84f).coerceAtLeast(0.18f)
        } else {
            bestCrossing.confidence
        }

        val lockedNow = updateLockState(
            confidence = confidenceAdjusted,
            phaseError = phaseErr.toFloat(),
            periodSamples = periodSamples,
            anchorIndex = anchorIndex,
            frameSize = n,
            signal = triggerSignal,
            config = config,
        )

        return Result(
            startIndex = startIndex,
            anchorIndex = anchorIndex,
            periodSamples = periodSamples,
            confidence = confidenceAdjusted,
            locked = lockedNow,
            mode = config.mode,
            freqHz = freqHz,
        )
    }

    private fun updateLockState(
        confidence: Float,
        phaseError: Float,
        periodSamples: Int,
        anchorIndex: Int,
        frameSize: Int,
        signal: FloatArray,
        config: Config,
    ): Boolean {
        lockState.confidenceEma = smoothFloat(lockState.confidenceEma, confidence, 0.22f)
        lockState.phaseErrorSamples = phaseError

        when {
            confidence >= config.lockEnterConfidence -> {
                lockState.badFrames = 0
                lockState.locked = true
            }

            lockState.locked && confidence < config.lockExitConfidence -> {
                lockState.badFrames += 1
                if (lockState.badFrames >= config.unlockAfterBadFrames.coerceAtLeast(1)) {
                    lockState.locked = false
                }
            }

            else -> {
                lockState.badFrames = max(0, lockState.badFrames - 1)
            }
        }

        val absoluteAnchor = processFrameIndex.toLong() * frameSize.toLong() + anchorIndex.toLong()
        lockState.lastAbsoluteAnchorIndex = absoluteAnchor

        if (periodSamples > 0) {
            lockState.emaPeriodSamples = smoothFloat(lockState.emaPeriodSamples, periodSamples.toFloat(), config.periodSmoothAlpha)
        }
        if (confidence >= config.lockExitConfidence) {
            lockState.triggerFingerprint = triggerFingerprint(signal, anchorIndex, periodSamples)
        }
        return lockState.locked
    }

    private fun chooseBestTriggerCrossing(
        signal: FloatArray,
        crossings: List<Int>,
        mode: Mode,
        predictedAnchor: Int,
        periodSamples: Int,
        searchRadius: Int,
        priorFingerprint: FloatArray?,
        edgeRadius: Int,
    ): ScoredCrossing? {
        if (crossings.isEmpty()) return null

        var best: ScoredCrossing? = null
        for (anchor in crossings) {
            if (anchor !in 1 until signal.size) continue
            val edge = edgeConsistencyScore(signal, anchor, mode, edgeRadius)
            val phase = phaseAlignmentScore(anchor, predictedAnchor, searchRadius)
            val period = periodDeviationScore(anchor, periodSamples, searchRadius)
            val symmetry = halfWaveSymmetryScore(signal, anchor, periodSamples)
            val fp = fingerprintScore(signal, anchor, periodSamples, priorFingerprint)
            val recency = (anchor.toFloat() / signal.lastIndex.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            val total = (
                    0.38f * edge +
                            0.20f * symmetry +
                            0.20f * fp +
                            0.14f * max(phase, period) +
                            0.08f * recency
                    ).coerceIn(0f, 1f)
            val confidence = total
            val phaseErr = if (predictedAnchor >= 0) anchor - predictedAnchor else 0
            if (best == null || total > best.score) {
                best = ScoredCrossing(anchor, total, confidence, phaseErr)
            }
        }
        return best
    }

    private fun edgeConsistencyScore(signal: FloatArray, anchorIndex: Int, mode: Mode, radius: Int): Float {
        val r = radius.coerceAtLeast(1)
        val start = (anchorIndex - r).coerceAtLeast(1)
        val end = (anchorIndex + r).coerceAtMost(signal.lastIndex)
        var directed = 0f
        var mag = 0f
        for (i in start..end) {
            val d = signal[i] - signal[i - 1]
            directed += if (mode == Mode.FALLING) -d else d
            mag += abs(d)
        }
        if (mag <= 1e-6f) return 0f
        return ((directed / mag) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    private fun phaseAlignmentScore(
        anchorIndex: Int,
        predictedAnchor: Int,
        searchRadius: Int,
    ): Float {
        return if (predictedAnchor >= 0 && searchRadius > 0) {
            val dist = abs(anchorIndex - predictedAnchor).toFloat()
            (1f - (dist / searchRadius.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else 0.5f
    }

    private fun periodDeviationScore(
        anchorIndex: Int,
        periodSamples: Int,
        searchRadius: Int,
    ): Float {
        if (periodSamples <= 0 || lastTriggerAnchor < 0) return 0.55f
        val expected = lastTriggerAnchor + periodSamples
        val radius = max(searchRadius, (periodSamples * 0.45f).roundToInt().coerceAtLeast(1))
        val dist = abs(anchorIndex - expected).toFloat()
        return (1f - (dist / radius.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    private fun fingerprintScore(
        signal: FloatArray,
        anchorIndex: Int,
        periodSamples: Int,
        priorFingerprint: FloatArray?,
    ): Float {
        val prev = priorFingerprint ?: return 0.5f
        val now = triggerFingerprint(signal, anchorIndex, periodSamples) ?: return 0.5f
        return cosineSimilarity(now, prev)
    }

    private fun halfWaveSymmetryScore(signal: FloatArray, anchorIndex: Int, periodSamples: Int): Float {
        if (periodSamples <= 8) return 0.5f
        val half = (periodSamples / 2).coerceAtLeast(2)
        val limit = min(half, 48)
        var err = 0f
        var mag = 0f
        for (k in 0 until limit) {
            val a = anchorIndex - k
            val b = anchorIndex + half - k
            if (a !in signal.indices || b !in signal.indices) continue
            val va = signal[a]
            val vb = signal[b]
            err += abs(va + vb)
            mag += abs(va) + abs(vb)
        }
        if (mag <= 1e-6f) return 0.5f
        return (1f - (err / mag).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    private fun triggerFingerprint(signal: FloatArray, anchorIndex: Int, periodSamples: Int): FloatArray? {
        if (periodSamples <= 0) return null
        val taps = 8
        val fp = FloatArray(taps)
        val span = (periodSamples * 0.9f).coerceAtLeast(6f)
        for (i in 0 until taps) {
            val t = i.toFloat() / (taps - 1).toFloat()
            val rel = ((t - 0.30f) * span).roundToInt()
            val idx = (anchorIndex + rel).coerceIn(0, signal.lastIndex)
            fp[i] = signal[idx]
        }
        var rmsSq = 0f
        for (v in fp) rmsSq += v * v
        val inv = 1f / sqrt((rmsSq / taps).coerceAtLeast(1e-6f))
        for (i in fp.indices) fp[i] *= inv
        return fp
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0.5f
        var ab = 0f
        var aa = 0f
        var bb = 0f
        for (i in 0 until n) {
            ab += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        val den = sqrt((aa * bb).coerceAtLeast(1e-6f))
        if (den <= 1e-6f) return 0.5f
        return ((ab / den) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    private fun predictedAnchor(periodSamples: Int, lastAnchor: Int, n: Int): Int {
        if (periodSamples <= 0 || lastAnchor < 0) return -1
        return (lastAnchor + periodSamples).coerceIn(0, n - 1)
    }

    private fun phaseSearchRadius(periodSamples: Int): Int {
        val base = if (periodSamples > 0) (periodSamples * 0.35f).roundToInt() else (nominalWindowSize * 0.20f).roundToInt()
        return max(8, base)
    }

    private fun holdoffSamples(sampleRateHz: Float, holdoffMs: Float): Int {
        return ((sampleRateHz.coerceAtLeast(1f) * holdoffMs.coerceAtLeast(0f)) / 1000f)
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun estimatePeriodFromCrossingsMedian(crossings: IntArray): Int {
        if (crossings.size < 2) return 0
        val deltas = ArrayList<Int>(crossings.size - 1)
        for (i in 1 until crossings.size) {
            val d = crossings[i] - crossings[i - 1]
            if (d > 0) deltas += d
        }
        if (deltas.isEmpty()) return 0
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    private fun updatePeriodState(previous: Int, observed: Int, config: Config): Int {
        if (observed <= 0) return previous
        if (previous <= 0) return observed
        val jumpRatio = abs(observed - previous).toFloat() / previous.toFloat().coerceAtLeast(1f)
        if (jumpRatio > config.triggerMaxAcceptedPeriodJumpRatio.coerceAtLeast(0f)) {
            return previous
        }
        return (previous.toFloat() * (1f - PERIOD_EMA_NEW_WEIGHT) + observed.toFloat() * PERIOD_EMA_NEW_WEIGHT)
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun staggeredFundamentalPeriodCandidates(
        crossings: IntArray,
        sampleRateHz: Float,
        fMinHz: Float,
        fMaxHz: Float,
    ): IntArray {
        if (crossings.size < 2) return IntArray(0)
        val minPeriod = (sampleRateHz / fMaxHz.coerceAtLeast(1f)).roundToInt().coerceAtLeast(2)
        val maxPeriod = (sampleRateHz / fMinHz.coerceAtLeast(1f)).roundToInt().coerceAtLeast(minPeriod + 1)
        val out = ArrayList<Int>(crossings.size * 2)

        for (stride in 1..3) {
            for (i in stride until crossings.size) {
                val delta = crossings[i] - crossings[i - stride]
                if (delta <= 0) continue
                val period = (delta.toFloat() / stride.toFloat()).roundToInt()
                if (period in minPeriod..maxPeriod) out += period
            }
        }
        return IntArray(out.size) { out[it] }
    }

    private fun chooseFundamentalPeriodCandidate(candidates: IntArray, seed: Int): Int {
        if (candidates.isEmpty()) return 0
        val hist = HashMap<Int, Int>()
        for (c in candidates) {
            val k = c.coerceAtLeast(1)
            hist[k] = (hist[k] ?: 0) + 1
        }

        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for ((candidate, count) in hist) {
            val support = count.toFloat()
            val proximity = if (seed > 0) {
                val dist = abs(candidate - seed).toFloat()
                (1f - (dist / seed.toFloat()).coerceIn(0f, 1f))
            } else 0.5f
            val score = support + 1.6f * proximity
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        if (best > 0) return best

        val sorted = candidates.sorted()
        return sorted[sorted.size / 2]
    }

    private fun estimatePeriodFromAutocorrelation(
        signal: FloatArray,
        n: Int,
        config: Config,
        seedLag: Int,
    ): Int {
        if (n <= 16) return 0
        val windowLen = min(n, config.autocorrMaxSamples.coerceIn(64, n))
        ensureAutocorrCapacity(windowLen)
        val acLen = min(windowLen, ac.size)
        if (acLen <= 16) return 0
        val start = n - acLen

        Autocorrelation.computeNormalized(
            x = signal,
            start = start,
            len = acLen,
            maxLag = acLen,
            out = ac,
        )

        val dt = 1f / config.sampleRateHz.coerceAtLeast(1f)
        return Autocorrelation.estimatePeriodFromAutocorrSeeded(
            ac = ac,
            acLen = acLen,
            dt = dt,
            fMinHz = config.fMinHz,
            fMaxHz = config.fMaxHz,
            seedLag = seedLag.coerceAtLeast(0),
        )
    }

    private fun smoothPeriodSamples(previous: Int, candidate: Int, alpha: Float): Int {
        if (candidate <= 0) return previous
        if (previous <= 0) return candidate
        val t = alpha.coerceIn(0f, 1f)
        return ((1f - t) * previous + t * candidate).roundToInt().coerceAtLeast(1)
    }

    private fun smoothFloat(previous: Float, candidate: Float, alpha: Float): Float {
        if (candidate <= 0f) return previous
        if (previous <= 0f) return candidate
        val t = alpha.coerceIn(0f, 1f)
        return (1f - t) * previous + t * candidate
    }

    private fun fallbackResult(n: Int, config: Config): Result {
        val confidence = 0.04f
        updateLockState(
            confidence = confidence,
            phaseError = 0f,
            periodSamples = 0,
            anchorIndex = -1,
            frameSize = n.coerceAtLeast(1),
            signal = FloatArray(0),
            config = config,
        )
        val fallbackStart = defaultStart(n)
        val fallbackAnchor = (fallbackStart + (nominalWindowSize * config.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
        return Result(
            startIndex = fallbackStart,
            anchorIndex = fallbackAnchor,
            periodSamples = 0,
            confidence = confidence,
            locked = lockState.locked,
            mode = config.mode,
            freqHz = 0f,
        )
    }

    private fun startFromAnchor(anchor: Int, n: Int, preTriggerRatio: Float): Int {
        val pre = (nominalWindowSize * preTriggerRatio.coerceIn(0.05f, 0.45f)).roundToInt()
        return (anchor - pre).coerceIn(0, max(0, n - nominalWindowSize))
    }

    private fun isLegalAnchor(anchor: Int, n: Int, preferredAnchorSamples: Int): Boolean {
        if (n < nominalWindowSize) return false
        val rawStart = anchor - preferredAnchorSamples.coerceIn(1, nominalWindowSize - 1)
        val rawEnd = rawStart + nominalWindowSize
        return rawStart >= 0 && rawEnd <= n
    }

    private fun preferredAnchorSamples(): Int = (nominalWindowSize / 5).coerceAtLeast(1)

    fun extractTriggeredWindow(source: FloatArray, result: Result?): FloatArray {
        if (source.isEmpty()) return source
        if (source.size <= nominalWindowSize || result == null || result.mode == Mode.OFF) return source.copyOf()
        val maxStart = max(0, source.size - nominalWindowSize)
        val start = result.startIndex.coerceIn(0, maxStart)
        return extractLinearWindow(source, start, nominalWindowSize)
    }

    private fun collectHysteresisCrossings(
        x: FloatArray,
        n: Int,
        mode: Mode,
        threshold: Float,
        rms: Float,
        config: Config,
    ): IntArray {
        val list = ArrayList<Int>(64)
        val hysteresis = max(
            config.triggerCrossingHysteresisFloor,
            max(
                abs(threshold) * config.triggerCrossingHysteresisRatio,
                rms * 0.06f,
            ),
        )
        val lowThreshold = threshold - hysteresis
        val highThreshold = threshold + hysteresis
        var armed = true
        for (i in 1 until n) {
            when (mode) {
                Mode.FALLING -> {
                    if (!armed && x[i] >= highThreshold) armed = true
                    if (armed && x[i - 1] > lowThreshold && x[i] <= lowThreshold) {
                        list += i
                        armed = false
                    }
                }
                else -> {
                    if (!armed && x[i] <= lowThreshold) armed = true
                    if (armed && x[i - 1] < highThreshold && x[i] >= highThreshold) {
                        list += i
                        armed = false
                    }
                }
            }
        }
        return IntArray(list.size) { idx -> list[idx] }
    }

    private fun rms(x: FloatArray, n: Int): Float {
        if (n <= 0) return 0f
        var sumSq = 0f
        for (i in 0 until n) {
            val v = x[i]
            sumSq += v * v
        }
        return sqrt(sumSq / n.toFloat())
    }

    private fun primaryMode(config: Config): Mode {
        if (config.mode == Mode.OFF) return Mode.OFF
        return when (config.mode) {
            Mode.FALLING -> Mode.FALLING
            else -> if (config.triggerPreferRisingPrimaryReference) Mode.RISING else Mode.FALLING
        }
    }

    private fun extractLinearWindow(source: FloatArray, start: Int, size: Int): FloatArray {
        if (source.isEmpty()) return source
        val safeStart = start.coerceIn(0, max(0, source.size - 1))
        val safeEnd = (safeStart + size).coerceIn(safeStart + 1, source.size)
        return source.copyOfRange(safeStart, safeEnd)
    }

    private fun triggerLowPass(x: FloatArray, n: Int, sampleRateHz: Float, cutoffHz: Float): FloatArray {
        val dt = 1f / sampleRateHz.coerceAtLeast(1f)
        val rc = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(5f))
        val alpha = dt / (rc + dt)
        var y = 0f
        for (i in 0 until n) {
            y += alpha * (x[i] - y)
            lp[i] = y
        }
        return lp
    }

    private fun defaultStart(n: Int): Int = max(0, n - nominalWindowSize)

    private fun ensureCapacity(n: Int) {
        if (lp.size < n) lp = FloatArray(n)
    }

    private fun ensureAutocorrCapacity(n: Int) {
        if (ac.size < n) ac = FloatArray(n)
    }
}
