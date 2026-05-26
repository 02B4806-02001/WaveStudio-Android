package org.mhrri.wavestudio

import kotlin.collections.iterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow
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
        private const val TRIGGER_MAX_ACCEPTED_PERIOD_JUMP_RATIO = 0.18f
        private const val TRIGGER_PHASE_ERROR_EMA_ALPHA = 0.12f
        private const val PERIOD_EMA_NEW_WEIGHT = 0.12f
        private const val TRIGGER_MIN_FUNDAMENTAL_HZ = 1.0f
        private const val TRIGGER_MAX_FUNDAMENTAL_HZ = 240.0f
        private const val TRIGGER_CONDITIONING_HIGH_SHELF_HZ = 156.0f
        private const val TRIGGER_CONDITIONING_HIGH_SHELF_GAIN_DB = -40.0f
        private const val TRIGGER_CONDITIONING_LOW_PASS_HZ = 800.0f
        private const val TRIGGER_ASSIST_LOW_PASS_CUTOFF_HZ = 360.0f
        private const val TRIGGER_ASSIST_LOW_PASS_ORDER = 3
        private const val TRIGGER_HALF_CYCLE_ALIAS_PENALTY = 0.22f
        private const val TRIGGER_PREDICTION_WEIGHT = 0.82f
        private const val TRIGGER_MINIMUM_CONFIDENCE_FOR_ACCEPTANCE = 0.52f
        private const val TRIGGER_PHASE_STICKINESS_MARGIN = 0.12f
        private const val TRIGGER_RENDER_REFINE_MAX_OFFSET_SAMPLES = 18
        private const val TRIGGER_SEARCH_MAX_SAMPLES = 6144
        private const val TRIGGER_PERIOD_ESTIMATE_MAX_SAMPLES = 2048
        private const val FINGERPRINT_TAPS = 24
        private const val CONDITIONING_LP_ORDER = 4
        private const val CROSSING_CLIP_MAX = 192
    }

    enum class Mode {
        OFF,
        RISING,
        FALLING,
    }

        enum class TriggerSourceMode { OUTPUT, CONDITIONED }
      data class Config(
          val mode: Mode,
          val sampleRateHz: Float,
          val sourceMode: TriggerSourceMode = TriggerSourceMode.CONDITIONED,
          val fMinHz: Float = TRIGGER_MIN_FUNDAMENTAL_HZ,
          val fMaxHz: Float = TRIGGER_MAX_FUNDAMENTAL_HZ,
          val autocorrMaxSamples: Int = TRIGGER_PERIOD_ESTIMATE_MAX_SAMPLES,
          val preTriggerRatio: Float = 0.22f,
          val lockEnterConfidence: Float = 0.24f,
          val lockExitConfidence: Float = 0.10f,
          val unlockAfterBadFrames: Int = 6,
          val triggerThreshold: Float = DEFAULT_TRIGGER_THRESHOLD,
          val triggerCrossingHysteresisFloor: Float = TRIGGER_CROSSING_HYSTERESIS_FLOOR,
          val triggerCrossingHysteresisRatio: Float = TRIGGER_CROSSING_HYSTERESIS_RATIO,
          val triggerWeakSignalRmsFloor: Float = TRIGGER_WEAK_SIGNAL_RMS_FLOOR,
          val triggerMaxAcceptedPeriodJumpRatio: Float = TRIGGER_MAX_ACCEPTED_PERIOD_JUMP_RATIO,
          val triggerHoldoffMs: Float = 1.0f,
          val triggerEdgeConsistencyRadius: Int = 10,
          val triggerPhaseErrorEmaAlpha: Float = TRIGGER_PHASE_ERROR_EMA_ALPHA,
          val triggerPreferRisingPrimaryReference: Boolean = true,
          val triggerHalfCycleAliasPenalty: Float = TRIGGER_HALF_CYCLE_ALIAS_PENALTY,
          val triggerPredictionWeight: Float = TRIGGER_PREDICTION_WEIGHT,
          val triggerMinimumConfidenceForAcceptance: Float = TRIGGER_MINIMUM_CONFIDENCE_FOR_ACCEPTANCE,
          val triggerPhaseStickinessMargin: Float = TRIGGER_PHASE_STICKINESS_MARGIN,
          val triggerSearchMaxSamples: Int = TRIGGER_SEARCH_MAX_SAMPLES,
          val triggerPeriodEstimateMaxSamples: Int = TRIGGER_PERIOD_ESTIMATE_MAX_SAMPLES,
          val triggerRenderRefineMaxOffsetSamples: Int = TRIGGER_RENDER_REFINE_MAX_OFFSET_SAMPLES,
          val triggerConditioningHighShelfHz: Float = TRIGGER_CONDITIONING_HIGH_SHELF_HZ,
          val triggerConditioningHighShelfGainDb: Float = TRIGGER_CONDITIONING_HIGH_SHELF_GAIN_DB,
          val triggerConditioningLowPassHz: Float = TRIGGER_CONDITIONING_LOW_PASS_HZ,
          val triggerAssistLowPassCutoffHz: Float = TRIGGER_ASSIST_LOW_PASS_CUTOFF_HZ,
          val triggerAssistLowPassOrder: Int = TRIGGER_ASSIST_LOW_PASS_ORDER,
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
            var lastTriggerSampleIndex: Long = -1L,
            var smoothedPreviousSample: Float = 0f,
            var estimatedPeriodSamples: Double = 0.0,
            var lastTriggerFingerprint: FloatArray = floatArrayOf(),
            var phaseErrorRatioEMA: Double = 0.0,
            var conditionedToRenderOffsetEMA: Double = 0.0,
        ) {
            var emaPeriodSamples: Float
                get() = estimatedPeriodSamples.toFloat()
                set(v) { estimatedPeriodSamples = v.toDouble() }
            var triggerFingerprint: FloatArray?
                get() = if (lastTriggerFingerprint.isEmpty()) null else lastTriggerFingerprint
                set(v) { lastTriggerFingerprint = v?.copyOf() ?: floatArrayOf() }
            var phaseErrorSamples: Float
                get() = phaseErrorRatioEMA.toFloat()
                set(v) { phaseErrorRatioEMA = v.toDouble() }
            var confidenceEma: Float = 0f
            var badFrames: Int = 0
            var locked: Boolean = false
            var lastAbsoluteAnchorIndex: Long = -1L
            }

    private data class ScoredCrossing(
        val index: Int,
        val score: Float,
        val confidence: Float,
        val phaseError: Int,
    )

    private var lp = FloatArray(0)
    private var ac = FloatArray(0)
    private var condBuf = FloatArray(0)

    // high-shelf biquad state
    private var hsX1 = 0f
    private var hsX2 = 0f
    private var hsY1 = 0f
    private var hsY2 = 0f

    // conditioning low-pass cascade states
    private val condLpStates = FloatArray(CONDITIONING_LP_ORDER)

    // assist LP cascade states
    private val assistLpStates = FloatArray(TRIGGER_ASSIST_LOW_PASS_ORDER)

    private var periodEstimationPhase = false
    private var estimatedPeriodSamples: Int = 0
    private var lastTriggerAnchor: Int = -1
    private var processFrameIndex: Int = 0
    private var lastOutputSignalCache = FloatArray(0)
    private val rawLock = TriggerLockState()
    private val filteredLock = TriggerLockState()

    private var acCountSinceLastRefresh = 0

    fun process(x: FloatArray, config: Config): Result {
        val n = x.size
        val maxStart = max(0, n - nominalWindowSize)
        val preferredAnchor = preferredAnchorSamples()
        processFrameIndex++
        if (config.mode == Mode.OFF || n < 64) {
              rawLock.locked = false
              rawLock.badFrames = 0
              filteredLock.locked = false
              filteredLock.badFrames = 0
            val start = defaultStart(n)
            val anchor = (start + (nominalWindowSize * config.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
            return Result(start, anchor, 0, 0f, false, config.mode, 0f)
        }

        ensureCapacity(n)
        // Apply signal conditioning for stable trigger detection.
        val triggerSignal: FloatArray
        if (config.sourceMode == TriggerSourceMode.CONDITIONED) {
            System.arraycopy(x, 0, condBuf, 0, n)
            applyHighShelf(condBuf, condBuf, n, config)
            applyLowPassCascade(condBuf, condBuf, n, config, condLpStates, config.triggerConditioningLowPassHz, CONDITIONING_LP_ORDER)
            triggerSignal = condBuf
        } else {
            triggerSignal = x
        }
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
                val observedPeriod = estimatePeriodDual(triggerSignal, n, config)
                if (observedPeriod > 0) {
                    estimatedPeriodSamples = stabilizedTriggerPeriod(estimatedPeriodSamples, observedPeriod, config)
                    rawLock.emaPeriodSamples = smoothFloat(rawLock.emaPeriodSamples, estimatedPeriodSamples.toFloat(), PERIOD_EMA_NEW_WEIGHT)
            }
                periodEstimationPhase = !periodEstimationPhase
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
                priorFingerprint = rawLock.triggerFingerprint,
            edgeRadius = config.triggerEdgeConsistencyRadius,
                    config = config,
        )
        if (bestCrossing == null) {
            return fallbackResult(n, config)
        }

        val anchorIndex = bestCrossing.index
        val phaseErr = if (predictedAnchor >= 0) anchorIndex - predictedAnchor else 0
        rawLock.phaseErrorSamples = smoothFloat(
            rawLock.phaseErrorSamples,
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
                lockState = rawLock,
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
            lockState: TriggerLockState,
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
                confidence >= config.lockEnterConfidence -> { lockState.badFrames = 0; lockState.locked = true }
                lockState.locked && confidence < config.lockExitConfidence -> { lockState.badFrames += 1; if (lockState.badFrames >= config.unlockAfterBadFrames.coerceAtLeast(1)) lockState.locked = false }
                else -> { lockState.badFrames = max(0, lockState.badFrames - 1) }
            }
            lockState.lastAbsoluteAnchorIndex = processFrameIndex.toLong() * frameSize.toLong() + anchorIndex.toLong()
            if (periodSamples > 0) lockState.emaPeriodSamples = smoothFloat(lockState.emaPeriodSamples, periodSamples.toFloat(), PERIOD_EMA_NEW_WEIGHT)
            if (confidence >= config.lockExitConfidence) lockState.triggerFingerprint = triggerFingerprint(signal, anchorIndex, periodSamples)
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
            config: Config,
    ): ScoredCrossing? {
        if (crossings.isEmpty()) return null

        var best: ScoredCrossing? = null
        for (anchor in crossings) {
                if (anchor !in 1 until signal.size) continue
                val score = computeCrossingScore(
                    signal = signal, anchor = anchor, mode = mode,
                    predictedAnchor = predictedAnchor, periodSamples = periodSamples,
                    searchRadius = searchRadius, priorFingerprint = priorFingerprint,
                    edgeRadius = edgeRadius, n = signal.size,
                        config = config,
                    preferredAnchor = preferredAnchorSamples(),
                    phaseErrorRatioEMA = rawLock.phaseErrorRatioEMA,
                    outputSignal = null,
                )
            val phaseErr = if (predictedAnchor >= 0) anchor - predictedAnchor else 0
            if (best == null || score > best.score) {
                best = ScoredCrossing(anchor, score, score, phaseErr)
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
            val fallbackStart = defaultStart(n)
            val fallbackAnchor = (fallbackStart + (nominalWindowSize * config.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1))
            return Result(
                startIndex = fallbackStart,
                anchorIndex = fallbackAnchor,
                periodSamples = 0,
                confidence = confidence,
                locked = false,
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

    fun extractTriggeredWindow(source: FloatArray, result: Result?, targetSize: Int = nominalWindowSize): FloatArray {
        if (source.isEmpty()) return source
        val tgt = targetSize.coerceAtLeast(64)
        if (source.size <= tgt || result == null || result.mode == Mode.OFF) return source.copyOf()
        val maxStart = max(0, source.size - tgt)
        val start = result.startIndex.coerceIn(0, maxStart)
        return extractLinearWindow(source, start, tgt)
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
    // ── New scoring & period estimation methods ──

    private fun computeCrossingScore(
        signal: FloatArray, anchor: Int, mode: Mode,
        predictedAnchor: Int, periodSamples: Int, searchRadius: Int,
        priorFingerprint: FloatArray?, edgeRadius: Int, n: Int,
        config: Config, preferredAnchor: Int,
        phaseErrorRatioEMA: Double, outputSignal: FloatArray?,
    ): Float {
        val predScore = if (predictedAnchor >= 0 && searchRadius > 0) {
            val sigma = searchRadius.toFloat() * 0.45f
            val dist = abs(anchor - predictedAnchor).toFloat()
            exp(-0.5f * dist * dist / (sigma * sigma + 1e-6f))
        } else 0f
        val slope = slopeScore(signal, anchor)
        val halfWave = halfWaveSymmetryScore(signal, anchor, periodSamples)
        val fp = fingerprintScore(signal, anchor, periodSamples, priorFingerprint)
        val edgeConsistency = edgeConsistencyScore(signal, anchor, mode, edgeRadius)
        val refEdge = outputSignal?.let { edgeConsistencyScore(it, anchor, mode, edgeRadius) } ?: 0f
        val anchorDist = abs(anchor - preferredAnchor).toFloat()
        val anchorScoreMax = (n * 0.4f).coerceAtLeast(1f)
        val anchorScore = (1f - (anchorDist / anchorScoreMax).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val phaseContinuity: Float = if (phaseErrorRatioEMA > 0.0) {
                  val sigma = 0.15f
            val dist = (anchor - predictedAnchor).toFloat() / periodSamples.coerceAtLeast(1).toFloat()
            exp(-0.5f * dist * dist / (sigma * sigma + 1e-6f))
        } else 0.5f
        // half-cycle alias penalty
        val halfAliasPenalty = if (periodSamples > 0 && lastTriggerAnchor >= 0) {
            val distToLast = abs(anchor - lastTriggerAnchor)
            val halfPeriod = periodSamples / 2
            val distToHalf = abs(distToLast - halfPeriod).toFloat()
            if (distToHalf < periodSamples * 0.25f) config.triggerHalfCycleAliasPenalty else 0f
        } else 0f
        val rawTotal =
            0.82f * predScore + 0.12f * slope + 0.12f * halfWave + 0.12f * fp +
            0.10f * edgeConsistency + 0.24f * refEdge + 0.10f * anchorScore + 0.08f * phaseContinuity - halfAliasPenalty
        return rawTotal.coerceIn(0f, 1f)
    }

    private fun crossingScoreAt(
        signal: FloatArray, anchor: Int, mode: Mode,
        predictedAnchor: Int, periodSamples: Int, searchRadius: Int,
        priorFingerprint: FloatArray?, edgeRadius: Int, n: Int,
        config: Config, outputSignal: FloatArray?,
    ): Float {
        if (anchor !in 1 until n) return 0f
        val phaseEma = if (config.sourceMode == TriggerSourceMode.CONDITIONED) filteredLock.phaseErrorRatioEMA else rawLock.phaseErrorRatioEMA
        return computeCrossingScore(
            signal = signal, anchor = anchor, mode = mode,
            predictedAnchor = predictedAnchor, periodSamples = periodSamples,
            searchRadius = searchRadius, priorFingerprint = priorFingerprint,
            edgeRadius = edgeRadius, n = n, config = config,
            preferredAnchor = preferredAnchorSamples(), phaseErrorRatioEMA = phaseEma,
            outputSignal = outputSignal,
        )
    }

    private fun slopeScore(signal: FloatArray, anchor: Int): Float {
        if (anchor !in 1 until signal.size - 1) return 0.5f
        val slope = abs(signal[anchor + 1] - signal[anchor - 1])
        return (slope / 0.5f).coerceIn(0f, 1f)
    }

    private fun applyHighShelf(x: FloatArray, out: FloatArray, n: Int, config: Config) {
        val fs = config.sampleRateHz.coerceAtLeast(1f)
        val f0 = config.triggerConditioningHighShelfHz.coerceAtLeast(1f)
        val gainDb = config.triggerConditioningHighShelfGainDb
        val q = 2.0f
        val w0 = 2f * PI.toFloat() * f0 / fs
        val a = 10f.pow(gainDb / 40f)
        val alpha = (sin(w0) / (2f * q)).coerceAtLeast(1e-6f)
        val cosW0 = cos(w0)
        val sqrtA = sqrt(a)
        val b0 = a * ((a + 1f) + (a - 1f) * cosW0 + 2f * sqrtA * alpha)
        val b1 = -2f * a * ((a - 1f) + (a + 1f) * cosW0)
        val b2 = a * ((a + 1f) + (a - 1f) * cosW0 - 2f * sqrtA * alpha)
        val a0inv = 1f / ((a + 1f) - (a - 1f) * cosW0 + 2f * sqrtA * alpha)
        val a1norm = 2f * ((a - 1f) - (a + 1f) * cosW0) * a0inv
        val a2norm = ((a + 1f) - (a - 1f) * cosW0 - 2f * sqrtA * alpha) * a0inv
        val b0norm = b0 * a0inv; val b1norm = b1 * a0inv; val b2norm = b2 * a0inv
        // Direct form II transposed
        for (i in 0 until n) {
            val input = x[i]; val y = b0norm * input + hsX1
            hsX1 = b1norm * input - a1norm * y + hsX2
            hsX2 = b2norm * input - a2norm * y
            out[i] = y
        }
    }

    private fun applyLowPassCascade(x: FloatArray, out: FloatArray, n: Int, config: Config, states: FloatArray, cutoffHz: Float, order: Int) {
        val fs = config.sampleRateHz.coerceAtLeast(1f)
        val fc = cutoffHz.coerceAtLeast(1f)
        val rc = 1f / (2f * PI.toFloat() * fc)
        val dt = 1f / fs; val alpha = dt / (rc + dt)
        for (i in 0 until n) {
            var v = x[i]
            for (j in 0 until order.coerceAtMost(states.size)) { v += alpha * (v - states[j]); states[j] = v }
            out[i] = v
        }
    }

    private fun estimatePeriodDual(signal: FloatArray, n: Int, config: Config): Int {
        // DC removal
        val dcSignal = FloatArray(n) { if (it < n) signal[it] else 0f }
        var dcMean = 0f; for (i in 0 until n) dcMean += dcSignal[i]
        dcMean /= n.toFloat(); for (i in 0 until n) dcSignal[i] -= dcMean

        val rawPeriod = estimatePeriodAcCore(dcSignal, n, config)
        // LP assist
        val assistBuf = FloatArray(n)
        applyLowPassCascade(dcSignal, assistBuf, n, config, assistLpStates, config.triggerAssistLowPassCutoffHz, config.triggerAssistLowPassOrder)
        val assistPeriod = estimatePeriodAcCore(assistBuf, n, config)

        return if (rawPeriod > 0 && assistPeriod > 0) {
            if (abs(rawPeriod - assistPeriod) < 0.30f * max(rawPeriod, assistPeriod)) rawPeriod
            else max(rawPeriod, assistPeriod)
        } else max(rawPeriod, assistPeriod)
    }

    private fun estimatePeriodAcCore(signal: FloatArray, n: Int, config: Config): Int {
        val maxLag = min(n, config.triggerPeriodEstimateMaxSamples.coerceAtLeast(64))
        if (maxLag < 16) return 0
        ensureAutocorrCapacity(maxLag)
        Autocorrelation.computeNormalized(x = signal, start = 0, len = min(n, maxLag), maxLag = maxLag, out = ac)
        val fs = config.sampleRateHz.coerceAtLeast(1f)
        val lagMin = (fs / config.fMaxHz.coerceAtLeast(1f)).toInt().coerceIn(2, maxLag - 2)
        val lagMax = (fs / config.fMinHz.coerceAtLeast(1f)).toInt().coerceIn(lagMin + 1, maxLag - 2)
        var bestLag = 0; var bestScore = -1f
        for (lag in lagMin..lagMax) {
            val v = ac[lag]; if (v <= 0f) continue
            if (v < ac[lag - 1] || v < ac[lag + 1]) continue
            val bias = sqrt(lag.toFloat() / lagMax.toFloat()) * 0.12f
            val score = v + bias
            if (score > bestScore) { bestScore = score; bestLag = lag }
        }
        return if (bestLag > 0 && ac[bestLag] >= 0.12f) bestLag else 0
    }

    private fun stabilizedTriggerPeriod(previous: Int, observed: Int, config: Config): Int {
        if (observed <= 0) return previous
        if (previous <= 0) return observed
        val jumpRatio = abs(observed - previous).toFloat() / previous.toFloat().coerceAtLeast(1f)
        if (jumpRatio > config.triggerMaxAcceptedPeriodJumpRatio.coerceAtLeast(0.01f)) return previous
        val alpha = PERIOD_EMA_NEW_WEIGHT
        return (previous.toFloat() * (1f - alpha) + observed.toFloat() * alpha).roundToInt().coerceAtLeast(1)
    }

    private fun refineRenderCrossingFromConditionedTrigger(
        outputSignal: FloatArray, n: Int,
        conditionedIndex: Int, predictedAnchor: Int,
        priorFingerprint: FloatArray?, config: Config,
    ): Int {
        val maxOffset = config.triggerRenderRefineMaxOffsetSamples.coerceAtLeast(4)
        val searchStart = max(1, conditionedIndex - maxOffset)
        val searchEnd = min(n - 1, conditionedIndex + maxOffset)
        var bestIdx = conditionedIndex; var bestScore = -1f
        for (i in searchStart..searchEnd) {
            val dPred = if (predictedAnchor >= 0) (1f - abs(i - predictedAnchor).toFloat() / maxOffset.toFloat()).coerceIn(0f, 1f) else 0f
            val dCond = (1f - abs(i - conditionedIndex).toFloat() / maxOffset.toFloat()).coerceIn(0f, 1f)
            val sl = slopeScore(outputSignal, i)
            val score = 1.0f * dPred + 0.3f * dCond + 0.15f * sl
            if (score > bestScore) { bestScore = score; bestIdx = i }
        }
        val offset = bestIdx - conditionedIndex
        val prevOffset = filteredLock.conditionedToRenderOffsetEMA
        filteredLock.conditionedToRenderOffsetEMA = prevOffset * (1.0 - 0.12) + offset.toDouble() * 0.12
        if (filteredLock.phaseErrorRatioEMA > 0.30) filteredLock.conditionedToRenderOffsetEMA *= 0.90
        if (abs(filteredLock.conditionedToRenderOffsetEMA) <= 0.75 && predictedAnchor >= 0) return predictedAnchor
        return bestIdx
    }

    // ── End new methods ──

    private fun ensureCapacity(n: Int) {
            if (lp.size < n) {
                lp = FloatArray(n)
                condBuf = FloatArray(n)
            }
    }

    private fun ensureAutocorrCapacity(n: Int) {
        if (ac.size < n) ac = FloatArray(n)
    }
}
