package org.mhrri.wavestudio

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

internal class NewTriggerEngine(
    private val nominalWindowSize: Int = 512,
) {
    // ── Constants ──
    companion object {
        private const val HYSTERESIS_FLOOR = 0.002f
        private const val CONDITIONING_LP_ORDER = 4
        private const val ASSIST_LP_ORDER = 3
        private const val CROSSING_CLIP_MAX = 192
        private const val FINGERPRINT_TAPS = 24
        private const val SEARCH_MAX_SAMPLES = 6144
        private const val PERIOD_ESTIMATE_MAX_SAMPLES = 2048

        fun alignedTriggerWindow(
            outputSignal: FloatArray,
            anchorIndex: Int,
            windowSamples: Int,
            preTriggerRatio: Float,
        ): FloatArray {
            val pre = (windowSamples * preTriggerRatio).roundToInt()
            val start = (anchorIndex - pre).coerceIn(0, outputSignal.size - windowSamples)
            return outputSignal.sliceArray(start until start + windowSamples)
        }
    }

    enum class Mode { OFF, RISING, FALLING }
    enum class TriggerSourceMode { OUTPUT, CONDITIONED }

    data class Config(
        val mode: Mode,
        val sampleRateHz: Float,
        val sourceMode: TriggerSourceMode = TriggerSourceMode.CONDITIONED,
        val fMinHz: Float = 1f,
        val fMaxHz: Float = 240f,
        val preTriggerRatio: Float = 0.22f,
        val lockEnterConfidence: Float = 0.24f,
        val lockExitConfidence: Float = 0.10f,
        val unlockAfterBadFrames: Int = 6,
        val triggerThreshold: Float = 0.02f,
        val triggerHoldoffMs: Float = 1.0f,
        val triggerEdgeConsistencyRadius: Int = 3,
        val triggerPhaseErrorEmaAlpha: Float = 0.12f,
        val triggerPreferRisingPrimaryReference: Boolean = true,
        val triggerHalfCycleAliasPenalty: Float = 0.22f,
        val triggerMinimumConfidenceForAcceptance: Float = 0.46f,
        val triggerPhaseStickinessMargin: Float = 0.075f,
        val triggerAmbiguousScoreMargin: Float = 0.055f,
        val triggerRmsHysteresisRatio: Float = 0.10f,
        val triggerMaxCrossingsToScore: Int = CROSSING_CLIP_MAX,
        val triggerFingerprintTaps: Int = FINGERPRINT_TAPS,
        val triggerPhaseLockWindowRatio: Float = 0.22f,
        val triggerPredictionNeighborhoodRatio: Float = 0.14f,
        val triggerMaxPredictionErrorRatio: Float = 0.22f,
        val triggerSearchMaxSamples: Int = SEARCH_MAX_SAMPLES,
        val triggerPeriodEstimateMaxSamples: Int = PERIOD_ESTIMATE_MAX_SAMPLES,
        val triggerPeriodSmoothingPreviousWeight: Float = 0.82f,
        val triggerPeriodSmoothingMeasuredWeight: Float = 0.18f,
        val triggerMaxAcceptedPeriodJumpRatio: Float = 0.30f,
        val triggerRenderRefineMaxOffsetSamples: Int = 18,
        val triggerRenderRefineDeadbandSamples: Float = 0.75f,
        val triggerConditioningHighShelfHz: Float = 156f,
        val triggerConditioningHighShelfGainDb: Float = -40f,
        val triggerConditioningLowPassHz: Float = 800f,
        val triggerAssistLowPassCutoffHz: Float = 360f,
        val triggerAssistLowPassOrder: Int = ASSIST_LP_ORDER,
        val triggerConditioningLowPassOrder: Int = CONDITIONING_LP_ORDER,
        val triggerWeakSignalRmsFloor: Float = 0.006f,
        val triggerHysteresisFloor: Float = HYSTERESIS_FLOOR,
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

    // ── Lock state ──
    private data class LockState(
        var lastTriggerSampleIndex: Long = -1L,
        var smoothedPreviousSample: Float = 0f,
        var estimatedPeriodSamples: Double = 0.0,
        var lastTriggerFingerprint: FloatArray = floatArrayOf(),
        var phaseErrorRatioEMA: Double = 0.0,
        var conditionedToRenderOffsetEMA: Double = 0.0,
    ) {
        val triggerFingerprint: FloatArray? get() = lastTriggerFingerprint.takeIf { it.isNotEmpty() }
        var confidenceEma: Float = 0f
        var badFrames: Int = 0
        var locked: Boolean = false
        var displayAnchorIndex: Int = -1
        var lastRawPeriodCandidate: Int = 0
        var lastLowPassPeriodCandidate: Int = 0
    }

    private data class ScoredCrossing(
        val index: Int,
        val confidence: Float,
        val predictionError: Int,
        val predictionErrorRatio: Float = 1f,
    )

    // ── Buffers ──
    private var ac = FloatArray(0)
    private var condBuf = FloatArray(0)

    // Biquad state
    private var hsX1 = 0f; private var hsX2 = 0f
    private var hsY1 = 0f; private var hsY2 = 0f
    private val condLpStates = FloatArray(CONDITIONING_LP_ORDER)
    private val assistLpStates = FloatArray(ASSIST_LP_ORDER)

    // ── State ──
    private var periodEstimationPhase = false
    private var estimatedPeriodSamples: Int = 0
    private var lastTriggerAnchor: Int = -1
    private var periodEstimationCounter: Int = 0
    private var globalWriteIndex: Long = 0L

    private var rawLock = LockState()
    private var filteredLock = LockState()

    // Display cache
    private var displayCache = FloatArray(0)
    private var lastDisplayConfig = Config(mode = Mode.OFF, sampleRateHz = 48000f)

    private var config: Config = Config(mode = Mode.OFF, sampleRateHz = 48000f)

    fun reset() {
        hsX1 = 0f; hsX2 = 0f; hsY1 = 0f; hsY2 = 0f
        condLpStates.fill(0f); assistLpStates.fill(0f)
        periodEstimationPhase = false
        estimatedPeriodSamples = 0
        lastTriggerAnchor = -1
        periodEstimationCounter = 0
        globalWriteIndex = 0L
        config = Config(mode = Mode.OFF, sampleRateHz = 48000f)
        rawLock = LockState(); filteredLock = LockState()
        displayCache = FloatArray(0)
        lastDisplayConfig = Config(mode = Mode.OFF, sampleRateHz = 48000f)
    }

    fun setGlobalWriteIndex(index: Long) { globalWriteIndex = index }

    // ═══════════════════════════════════════════════════════════════
    //  process() — main entry point
    // ═══════════════════════════════════════════════════════════════
    fun process(x: FloatArray, cfg: Config): Result {
        val n = x.size
        this.config = cfg
        val preferredAnchor = (nominalWindowSize * cfg.preTriggerRatio).roundToInt().coerceAtLeast(1)

        if (cfg.mode == Mode.OFF || n < 64) {
            rawLock.locked = false; rawLock.badFrames = 0
            filteredLock.locked = false; filteredLock.badFrames = 0
            val start = max(0, n - nominalWindowSize)
            return Result(start, (start + preferredAnchor).coerceIn(0, max(0, n - 1)),
                0, 0f, false, cfg.mode, 0f)
        }

        ensureCapacity(n)
        val lock = if (cfg.sourceMode == TriggerSourceMode.CONDITIONED) filteredLock else rawLock

        // 1. Signal conditioning
        val trigSig: FloatArray
        if (cfg.sourceMode == TriggerSourceMode.CONDITIONED) {
            System.arraycopy(x, 0, condBuf, 0, n)
            applyHighShelf(condBuf, condBuf, n)
            applyLowPassCascade(condBuf, condBuf, n, condLpStates, cfg.triggerConditioningLowPassHz, CONDITIONING_LP_ORDER)
            trigSig = condBuf
        } else { trigSig = x }

        val rms = rms(trigSig, n)
        val rawRms = rms(x, n)
        val edgeMode = if (cfg.mode == Mode.FALLING) Mode.FALLING else Mode.RISING

        // 2. Crossing detection — hysteresis with fallback
        val thr = max(abs(cfg.triggerThreshold), rms * cfg.triggerRmsHysteresisRatio)
        val hyst = max(cfg.triggerHysteresisFloor, max(thr * 0.18f, rms * 0.06f))
        var crossings = collectHysteresis(trigSig, n, edgeMode, thr, hyst, lock)
        if (crossings.isEmpty())
            crossings = collectSimple(trigSig, n, edgeMode, thr)

        // 3. Period estimation — staggered dual-path
        if (rms >= cfg.triggerWeakSignalRmsFloor && rawRms >= cfg.triggerWeakSignalRmsFloor)
            maybeRefreshPeriod(x, n, cfg)

        // 4. Crossing reduction
        val holdoffSamples = ((cfg.sampleRateHz.coerceAtLeast(1f) * cfg.triggerHoldoffMs.coerceAtLeast(0f)) / 1000f).roundToInt().coerceAtLeast(1)
        val reduced = reduceCrossings(crossings, estimatedPeriodSamples, lock, n, holdoffSamples)
        if (reduced.isEmpty()) return fallback(n, cfg)

        // 5. Score & choose
        val best = chooseBest(trigSig, reduced, edgeMode, estimatedPeriodSamples, lock, preferredAnchor, n)
            ?: return fallback(n, cfg)

        val anchor = best.index
        val conf = best.confidence

        // 6. Offset refinement (conditioned mode)
        val displayIdx = if (cfg.sourceMode == TriggerSourceMode.CONDITIONED)
            refineOffset(x, n, anchor, estimatedPeriodSamples)
        else anchor

        // 7. Update lock state
        lock.phaseErrorRatioEMA = ema(lock.phaseErrorRatioEMA, best.predictionError.toDouble(), cfg.triggerPhaseErrorEmaAlpha.toDouble())
        lastTriggerAnchor = displayIdx
        lock.displayAnchorIndex = displayIdx

        val period = estimatedPeriodSamples
        val freq = if (period > 1) cfg.sampleRateHz / period else 0f

        val adjConf = if (rawRms < cfg.triggerWeakSignalRmsFloor && period > 0)
            conf * 0.84f.coerceAtLeast(0.18f) else conf

        val locked = updateLock(lock, adjConf, best.predictionError.toFloat(), period, displayIdx, n, trigSig, cfg)

        // 8. Cache
        displayCache = x.copyOf()
        lastDisplayConfig = cfg

        val maxStart = max(0, n - nominalWindowSize)
        return Result(
            startIndex = (displayIdx - preferredAnchor).coerceIn(0, maxStart),
            anchorIndex = displayIdx,
            periodSamples = period,
            confidence = adjConf,
            locked = locked,
            mode = cfg.mode,
            freqHz = freq,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Crossing detection
    // ═══════════════════════════════════════════════════════════════
    private fun collectHysteresis(x: FloatArray, n: Int, mode: Mode, thr: Float, hyst: Float, lock: LockState): IntArray {
        val res = mutableListOf<Int>()
        val hi = thr + hyst; val lo = thr - hyst
        var armed = when (mode) {
            Mode.FALLING -> lock.smoothedPreviousSample >= hi
            else           -> lock.smoothedPreviousSample <= lo
        }
        for (i in 1 until n) {
            val p = x[i - 1]; val c = x[i]
            when (mode) {
                Mode.FALLING -> {
                    if (c >= hi) armed = true
                    if (armed && p >= hi && c <= lo) { res.add(i); armed = false }
                }
                else -> {
                    if (c <= lo) armed = true
                    if (armed && p <= lo && c >= hi) { res.add(i); armed = false }
                }
            }
        }
        lock.smoothedPreviousSample = if (n > 0) x[n - 1] else 0f
        return res.toIntArray()
    }

    private fun collectSimple(x: FloatArray, n: Int, mode: Mode, thr: Float): IntArray {
        val res = mutableListOf<Int>()
        for (i in 1 until n) {
            val p = x[i - 1]; val c = x[i]
            if (when (mode) {
                Mode.FALLING -> p >= thr && c < thr
                else           -> p <= thr && c > thr
            }) res.add(i)
        }
        return res.toIntArray()
    }

    // ═══════════════════════════════════════════════════════════════
    //  Crossing reduction
    // ═══════════════════════════════════════════════════════════════
    private fun reduceCrossings(crossings: IntArray, period: Int, lock: LockState, frameSize: Int, holdoff: Int): List<Int> {
        val list = crossings.toList()
        if (list.isEmpty()) return list

        // Holdoff via global index
        val base = globalWriteIndex - frameSize
        val afterHoldoff = if (lock.lastTriggerSampleIndex >= 0)
            list.filter { (base + it) - lock.lastTriggerSampleIndex >= holdoff }
        else list
        if (afterHoldoff.isEmpty()) return afterHoldoff

        // Phase-lock window (period × 0.78 ~ 1.22)
        if (period > 0 && lock.lastTriggerSampleIndex >= 0 && lastTriggerAnchor >= 0) {
            val frameStart = globalWriteIndex - frameSize
            val predGlobal = lock.lastTriggerSampleIndex + period
            val pred = (predGlobal - frameStart).toInt().coerceIn(0, frameSize - 1)
            val halfWin = (period * config.triggerPhaseLockWindowRatio).roundToInt().coerceAtLeast(4)
            val lo = max(0, pred - halfWin)
            val hi = min(frameSize - 1, pred + halfWin)
            val locked = afterHoldoff.filter { it in lo..hi }
            if (locked.size >= 4) return locked
        }

        // Thin to 192 max
        if (afterHoldoff.size <= CROSSING_CLIP_MAX) return afterHoldoff
        val step = afterHoldoff.size.toFloat() / CROSSING_CLIP_MAX
        return (0 until CROSSING_CLIP_MAX).map { afterHoldoff[(it * step).roundToInt().coerceIn(0, afterHoldoff.size - 1)] }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Scoring
    // ═══════════════════════════════════════════════════════════════
    private fun chooseBest(signal: FloatArray, crossings: List<Int>, mode: Mode, period: Int, lock: LockState, preferred: Int, n: Int): ScoredCrossing? {
        if (crossings.isEmpty()) return null

        val fingerprint = lock.triggerFingerprint
        val r = config.triggerEdgeConsistencyRadius

        // Predict using global sample index, accounting for frame advance
        val predicted = if (period > 0 && lock.lastTriggerSampleIndex >= 0) {
            val frameStart = globalWriteIndex - n
            val predGlobal = lock.lastTriggerSampleIndex + period
            (predGlobal - frameStart).toInt().coerceIn(0, n - 1)
        } else preferred

        // Prediction neighborhood filter
        var candidates = if (period > 0 && lastTriggerAnchor >= 0) {
            val nr = (period * config.triggerPredictionNeighborhoodRatio).roundToInt().coerceAtLeast(4)
            crossings.filter { abs(it - predicted) <= nr }.ifEmpty { crossings }
        } else crossings

        // Phase-lock filter — use global sample distances, not frame-relative
        if (period > 0 && lock.lastTriggerSampleIndex >= 0) {
            val lo = (period * (1f - config.triggerMaxPredictionErrorRatio)).roundToInt()
            val hi = (period * (1f + config.triggerMaxPredictionErrorRatio)).roundToInt()
            val frameBase = globalWriteIndex - n
            val flt = candidates.filter {
                val dist = (frameBase + it) - lock.lastTriggerSampleIndex
                dist in lo..hi
            }
            if (flt.isNotEmpty()) candidates = flt
        }
        if (candidates.isEmpty()) candidates = crossings

        // Score each candidate
        data class Entry(val c: ScoredCrossing, val predErrRatio: Float)
        val entries = mutableListOf<Entry>()
        for (idx in candidates) {
            if (idx !in 1 until signal.size - 1) continue
            val (conf, predErr) = score(signal, idx, mode, period, fingerprint, predicted, r, n)
            val per = if (period > 0) predErr.toFloat() / period else 1f
            entries.add(Entry(ScoredCrossing(idx, conf, predErr, per), per))
        }
        if (entries.isEmpty()) return null

        entries.sortByDescending { it.c.confidence }
        val best = entries[0]

        // Phase stickiness
        val predCand = entries.firstOrNull { it.predErrRatio < 0.08f }

        return when {
            best.c.confidence >= config.triggerMinimumConfidenceForAcceptance -> best.c
            predCand != null -> predCand.c
            lock.locked -> entries.minByOrNull { it.predErrRatio }!!.c
            else -> best.c
        }
    }

    private fun score(signal: FloatArray, idx: Int, mode: Mode, period: Int, fingerprint: FloatArray?, predicted: Int, r: Int, n: Int): Pair<Float, Int> {
        val edgeR = edgeScore(signal, idx, Mode.RISING, r)   // ref — always rising
        val edgeD = edgeScore(signal, idx, mode, r)           // user direction
        val sl     = slope(signal, idx)
        val hist   = if (fingerprint != null) histScore(signal, idx, period, fingerprint) else 0.5f
        val sym    = if (period > 8) symmetry(signal, idx, period) else 0.5f

        val predErr = abs(idx - predicted)
        val sigma = if (period > 0) period * 0.35f else n * 0.1f
        val pred = if (sigma > 0) exp(-(predErr * predErr) / (2f * sigma * sigma)) else 1f

        val aliasPen = if (period > 0 && lastTriggerAnchor >= 0) {
            val hp = period / 2
            if (abs(abs(idx - lastTriggerAnchor) - hp) < hp * 0.15f) config.triggerHalfCycleAliasPenalty else 0f
        } else 0f

        val raw = 0.72f * pred + 0.20f * edgeR + 0.18f * sl + 0.14f * hist + 0.10f * sym + 0.08f * edgeD - aliasPen
        return (raw / 1.42f).coerceIn(0f, 1f) to predErr
    }

    // Scoring helpers
    private fun edgeScore(signal: FloatArray, idx: Int, mode: Mode, radius: Int): Float {
        val lo = max(1, idx - radius); val hi = min(signal.lastIndex, idx + radius)
        var directed = 0f; var mag = 0f
        for (i in lo..hi) {
            val d = signal[i] - signal[i - 1]
            directed += if (mode == Mode.FALLING) -d else d; mag += abs(d)
        }
        if (mag <= 1e-6f) return 0f
        return ((directed / mag) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    private fun slope(signal: FloatArray, idx: Int): Float {
        if (idx !in 1 until signal.size - 1) return 0.5f
        return (abs(signal[idx + 1] - signal[idx - 1]) / 0.5f).coerceIn(0f, 1f)
    }

    private fun histScore(signal: FloatArray, idx: Int, period: Int, prior: FloatArray): Float {
        val now = extractFingerprint(signal, idx, period) ?: return 0.5f
        return cosineSim(now, prior)
    }

    private fun symmetry(signal: FloatArray, idx: Int, period: Int): Float {
        if (period <= 8) return 0.5f
        val half = period / 2; val lim = min(half, 48)
        var err = 0f; var mag = 0f
        for (k in 0 until lim) {
            val a = idx - k; val b = idx + half - k
            if (a !in signal.indices || b !in signal.indices) continue
            err += abs(signal[a] + signal[b]); mag += abs(signal[a]) + abs(signal[b])
        }
        if (mag <= 1e-6f) return 0.5f
        return (1f - (err / mag).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }

    private fun extractFingerprint(signal: FloatArray, idx: Int, period: Int): FloatArray? {
        val taps = FINGERPRINT_TAPS; val fp = FloatArray(taps)
        val half = ((taps - 1) / 2).toFloat()
        val span = if (period > 0) (period * 0.9f).coerceAtLeast(6f) else (taps * 4f)
        for (i in 0 until taps) {
            val t = (i - half) / half
            fp[i] = signal[(idx + (t * span * 0.5f).roundToInt()).coerceIn(0, signal.lastIndex)]
        }
        var sq = 0f; for (v in fp) sq += v * v
        val inv = 1f / sqrt((sq / taps).coerceAtLeast(1e-6f))
        for (i in fp.indices) fp[i] *= inv
        return fp
    }

    private fun cosineSim(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size); if (n == 0) return 0.5f
        var ab = 0f; var aa = 0f; var bb = 0f
        for (i in 0 until n) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        val d = sqrt((aa * bb).coerceAtLeast(1e-6f))
        if (d <= 1e-6f) return 0.5f
        return ((ab / d) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Period estimation — staggered dual-path
    // ═══════════════════════════════════════════════════════════════
    private fun maybeRefreshPeriod(signal: FloatArray, n: Int, cfg: Config) {
        periodEstimationCounter++
        val freq = if (estimatedPeriodSamples > 0) cfg.sampleRateHz / estimatedPeriodSamples else 0f
        val divisor = when {
            estimatedPeriodSamples <= 0 -> 1
            freq <= 30f -> 24; freq <= 60f -> 18; freq <= 120f -> 12
            else -> 8
        }
        if (periodEstimationCounter % divisor != 0) return

        val obs = estimatePeriodDual(signal, n, cfg)
        if (obs > 0) estimatedPeriodSamples = smoothPeriod(estimatedPeriodSamples, obs, cfg)
    }

    private fun estimatePeriodDual(signal: FloatArray, n: Int, cfg: Config): Int {
        val dc = FloatArray(n) { signal[it] }
        var m = 0f; for (v in dc) m += v; m /= n; for (i in dc.indices) dc[i] -= m

        val lock = if (cfg.sourceMode == TriggerSourceMode.CONDITIONED) filteredLock else rawLock
        val prev = estimatedPeriodSamples

        val useRaw = periodEstimationPhase
        periodEstimationPhase = !periodEstimationPhase

        if (useRaw) {
            val rp = estimatePeriodCore(dc, n, cfg, prev)
            if (rp > 0) lock.lastRawPeriodCandidate = rp
        } else {
            val ab = FloatArray(n)
            val hz = if (prev > 0) cfg.sampleRateHz / prev else 0f
            val fc = when {
                hz <= 0f -> cfg.triggerAssistLowPassCutoffHz
                hz <= 60f -> min(180f, max(90f, hz * 4f))
                else -> max(90f, min(cfg.triggerAssistLowPassCutoffHz, hz * 4f))
            }
            applyLowPassCascade(dc, ab, n, assistLpStates, fc, ASSIST_LP_ORDER)
            val ap = estimatePeriodCore(ab, n, cfg, prev)
            if (ap > 0) lock.lastLowPassPeriodCandidate = ap
        }

        val raw = lock.lastRawPeriodCandidate
        val lp = lock.lastLowPassPeriodCandidate
        if (raw <= 0 && lp <= 0) return 0
        if (raw <= 0) return lp
        if (lp <= 0) return raw
        if (prev <= 0) return if (lp > 0) lp else raw
        val rDrift = abs(raw - prev).toFloat() / prev; val lDrift = abs(lp - prev).toFloat() / prev
        return if (lDrift <= rDrift + 0.03f) lp else raw
    }

    private fun estimatePeriodCore(signal: FloatArray, n: Int, cfg: Config, prev: Int): Int {
        val maxLag = min(n, cfg.triggerPeriodEstimateMaxSamples.coerceAtLeast(64))
        if (maxLag < 16) return 0
        if (ac.size < maxLag) ac = FloatArray(maxLag)
        Autocorrelation.computeNormalized(signal, 0, min(n, maxLag), maxLag, ac)
        val fs = cfg.sampleRateHz.coerceAtLeast(1f)
        val lo = (fs / cfg.fMaxHz.coerceAtLeast(1f)).toInt().coerceIn(2, maxLag - 2)
        val hi = (fs / cfg.fMinHz.coerceAtLeast(1f)).toInt().coerceIn(lo + 1, maxLag - 2)

        val (sMin, sMax) = if (prev > 0)
            max(lo, (prev * 0.45f).roundToInt()) to min(hi, (prev * 2f).roundToInt())
        else lo to hi

        data class P(val lag: Int, val v: Float)
        val peaks = mutableListOf<P>()
        for (lag in max(lo, sMin)..min(hi, sMax)) {
            if (lag < 2 || lag >= maxLag) continue
            val v = ac[lag]; if (v <= 0f || v < ac[lag - 1] || v < ac[lag + 1]) continue
            peaks.add(P(lag, v * (0.75f + 0.25f * (lag - lo).toFloat() / max(1, hi - lo))))
        }
        if (peaks.isEmpty()) return 0
        peaks.sortByDescending { p ->
            var pen = 0f
            if (sMin == lo && sMax == hi)
                for (d in 2..3) {
                    val sl = p.lag / d
                    if (sl in lo..hi && ac[sl] > 0f && ac[sl] >= p.v * 0.80f) pen += 0.18f
                }
            p.v - pen
        }
        return if (peaks[0].v >= 0.12f) peaks[0].lag else 0
    }

    private fun smoothPeriod(prev: Int, obs: Int, cfg: Config): Int {
        if (obs <= 0) return prev; if (prev <= 0) return obs
        if (abs(obs - prev).toFloat() / prev > cfg.triggerMaxAcceptedPeriodJumpRatio.coerceAtLeast(0.01f)) return prev
        val b = cfg.triggerPeriodSmoothingMeasuredWeight.coerceIn(0.05f, 0.50f)
        return max(
            (prev * (1f - b) + obs * b).roundToInt().coerceAtLeast(1),
            (prev * cfg.triggerPeriodSmoothingPreviousWeight).roundToInt()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  Lock state update
    // ═══════════════════════════════════════════════════════════════
    private fun updateLock(lock: LockState, conf: Float, phaseErr: Float, period: Int, anchor: Int, n: Int, sig: FloatArray, cfg: Config): Boolean {
        lock.confidenceEma = emaFloat(lock.confidenceEma, conf, 0.22f)
        when {
            conf >= cfg.lockEnterConfidence -> { lock.badFrames = 0; lock.locked = true }
            lock.locked && conf < cfg.lockExitConfidence -> {
                if (++lock.badFrames >= cfg.unlockAfterBadFrames.coerceAtLeast(1)) lock.locked = false
            }
            else -> lock.badFrames = max(0, lock.badFrames - 1)
        }
        lock.lastTriggerSampleIndex = globalWriteIndex - n + anchor
        if (period > 0) lock.estimatedPeriodSamples = ema(lock.estimatedPeriodSamples, period.toDouble(), 0.18)
        if (conf >= cfg.lockExitConfidence)
            lock.lastTriggerFingerprint = extractFingerprint(sig, anchor, period)?.copyOf() ?: floatArrayOf()
        return lock.locked
    }

    // ═══════════════════════════════════════════════════════════════
    //  Conditioned offset refinement
    // ═══════════════════════════════════════════════════════════════
    private fun refineOffset(x: FloatArray, n: Int, condIdx: Int, period: Int): Int {
        val lock = filteredLock
        if (lock.phaseErrorRatioEMA > 0.30) { lock.conditionedToRenderOffsetEMA *= 0.90; return condIdx }

        val maxOff = if (period > 0) max((period * 0.1f).roundToInt(), config.triggerRenderRefineMaxOffsetSamples)
                     else config.triggerRenderRefineMaxOffsetSamples
        val lo = max(1, condIdx + lock.conditionedToRenderOffsetEMA.roundToInt() - maxOff)
        val hi = min(n - 1, condIdx + lock.conditionedToRenderOffsetEMA.roundToInt() + maxOff)
        val mode = if (config.mode == Mode.FALLING) Mode.FALLING else Mode.RISING
        val thr = abs(config.triggerThreshold)

        var bestIdx = condIdx; var bestScore = -1f
        val predCtr = condIdx + lock.conditionedToRenderOffsetEMA.roundToInt()

        for (i in lo..hi) {
            val p = x[i - 1]; val c = x[i]
            if (when (mode) { Mode.FALLING -> p >= thr && c < thr; else -> p <= thr && c > thr }) {
                val dp = 1f - abs(i - predCtr).toFloat() / maxOff
                val dd = 1f - abs(i - condIdx).toFloat() / maxOff
                val s = 0.4f * dp + 0.3f * dd + 0.3f * slope(x, i)
                if (s > bestScore) { bestScore = s; bestIdx = i }
            }
        }
        if (bestScore < 0f) {
            for (i in lo..hi) {
                val d = x[i] - x[i - 1]
                if (when (mode) { Mode.FALLING -> d >= 0f; else -> d <= 0f }) continue
                val dp = 1f - abs(i - predCtr).toFloat() / maxOff
                val s = 0.5f * dp + 0.5f * slope(x, i)
                if (s > bestScore) { bestScore = s; bestIdx = i }
            }
        }

        if (abs(bestIdx - predCtr) < config.triggerRenderRefineDeadbandSamples)
            return predCtr.coerceIn(0, n - 1)

        lock.conditionedToRenderOffsetEMA = ema(lock.conditionedToRenderOffsetEMA, (bestIdx - condIdx).toDouble(), 0.12)
        return bestIdx
    }

    // ═══════════════════════════════════════════════════════════════
    //  Window extraction
    // ═══════════════════════════════════════════════════════════════
    fun extractTriggeredWindow(source: FloatArray, result: Result?, targetSize: Int = nominalWindowSize): FloatArray {
        if (source.isEmpty()) return source
        val tgt = targetSize.coerceAtLeast(64)
        if (source.size <= tgt || result == null || result.mode == Mode.OFF) return source.copyOf()
        val pre = (tgt * config.preTriggerRatio).roundToInt()
        val start = (result.anchorIndex - pre).coerceIn(0, source.size - tgt)
        return source.copyOfRange(start, start + tgt)
    }

    fun snapshotWindowForDisplay(windowSamples: Int = 512): FloatArray? {
        if (displayCache.isEmpty()) return null
        if (lastTriggerAnchor < 0) return displayCache.copyOf(windowSamples.coerceAtMost(displayCache.size))
        return alignedTriggerWindow(displayCache, lastTriggerAnchor, windowSamples, config.preTriggerRatio)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Math helpers
    // ═══════════════════════════════════════════════════════════════
    private fun rms(x: FloatArray, n: Int): Float {
        var sq = 0f; for (i in 0 until n) sq += x[i] * x[i]
        return sqrt(sq / n)
    }

    private fun ema(prev: Double, candidate: Double, alpha: Double): Double =
        if (candidate <= 0.0 || prev <= 0.0) if (candidate > 0.0) candidate else prev
        else (1.0 - alpha) * prev + alpha * candidate

    private fun emaFloat(prev: Float, candidate: Float, alpha: Float): Float =
        if (candidate <= 0f || prev <= 0f) if (candidate > 0f) candidate else prev
        else (1f - alpha) * prev + alpha * candidate

    private fun fallback(n: Int, cfg: Config): Result {
        val start = max(0, n - nominalWindowSize)
        return Result(start, (start + (nominalWindowSize * cfg.preTriggerRatio).roundToInt()).coerceIn(0, max(0, n - 1)),
            0, 0.04f, false, cfg.mode, 0f)
    }

    private fun ensureCapacity(n: Int) { if (condBuf.size < n) condBuf = FloatArray(n) }

    // ═══════════════════════════════════════════════════════════════
    //  Signal conditioning filters
    // ═══════════════════════════════════════════════════════════════
    private fun applyHighShelf(x: FloatArray, out: FloatArray, n: Int) {
        val fs = config.sampleRateHz.coerceAtLeast(1f)
        val f0 = config.triggerConditioningHighShelfHz.coerceAtLeast(1f)
        val db = config.triggerConditioningHighShelfGainDb
        val w0 = 2f * PI.toFloat() * f0 / fs
        val a = 10f.pow(db / 40f)
        val alpha = sin(w0) / (2f * 2f).coerceAtLeast(1e-6f)
        val cw = cos(w0); val sa = sqrt(a)
        val b0 = a * ((a + 1f) + (a - 1f) * cw + 2f * sa * alpha)
        val b1 = -2f * a * ((a - 1f) + (a + 1f) * cw)
        val b2 = a * ((a + 1f) + (a - 1f) * cw - 2f * sa * alpha)
        val ai = 1f / ((a + 1f) - (a - 1f) * cw + 2f * sa * alpha)
        val a1n = 2f * ((a - 1f) - (a + 1f) * cw) * ai
        val a2n = ((a + 1f) - (a - 1f) * cw - 2f * sa * alpha) * ai
        val b0n = b0 * ai; val b1n = b1 * ai; val b2n = b2 * ai
        for (i in 0 until n) {
            val y = b0n * x[i] + hsX1
            hsX1 = b1n * x[i] - a1n * y + hsX2
            hsX2 = b2n * x[i] - a2n * y
            out[i] = y
        }
    }

    private fun applyLowPassCascade(x: FloatArray, out: FloatArray, n: Int, states: FloatArray, cutoffHz: Float, order: Int) {
        val dt = 1f / config.sampleRateHz.coerceAtLeast(1f)
        val rc = 1f / (2f * PI.toFloat() * cutoffHz.coerceAtLeast(1f))
        val a = dt / (rc + dt)
        for (i in 0 until n) {
            var v = x[i]
            for (j in 0 until order.coerceAtMost(states.size)) { v = states[j] + a * (v - states[j]); states[j] = v }
            out[i] = v
        }
    }
}
