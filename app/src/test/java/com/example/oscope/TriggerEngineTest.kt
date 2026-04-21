package com.example.oscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

class TriggerEngineTest {

    @Test
    fun `rising edge trigger locks on stable sine without drifting violently`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 25_600f
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING,
            sampleRateHz = sampleRateHz,
            lockEnterConfidence = 0.24f,
            lockExitConfidence = 0.10f,
            unlockAfterBadFrames = 10,
            maxStepPerFrame = 10,
        )

        val n = 768
        val f = 200f
        val dt = 1f / sampleRateHz

        val buf = FloatArray(n)

        var lockedEver = false

        for (frame in 0 until 60) {
            val phase = frame * 0.25f
            for (i in 0 until n) {
                val t = i * dt
                buf[i] = sin(2.0 * PI * f * t + phase).toFloat()
            }

            val r = engine.process(buf, cfg)

            if (r.locked) lockedEver = true
            if (frame > 10 && r.locked) {
                val expectedPeriod = (sampleRateHz / f).toInt()
                assertTrue(
                    "bad period: ${r.periodSamples} expected around $expectedPeriod",
                    r.periodSamples in (expectedPeriod - 5)..(expectedPeriod + 5)
                )
            }
        }

        assertTrue("never locked", lockedEver)
    }

    @Test
    fun `trigger selects latest valid rising crossing`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 10_000f
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING,
            sampleRateHz = sampleRateHz,
            preTriggerRatio = 0.16f,
        )

        val n = 1536
        val x = FloatArray(n)
        // earlier valid crossing
        for (i in 930..980) x[i] = 0.08f
        // latest valid crossing
        for (i in 1100..1160) x[i] = 0.09f
        // invalid late crossing that would push the display window out of bounds after pre-trigger placement
        for (i in 1320..1400) x[i] = 0.10f

        val result = engine.process(x, cfg)

        assertTrue(result.locked)
        assertTrue("anchor not in latest valid plateau: ${result.anchorIndex}", result.anchorIndex in 1100..1250)
        assertTrue("anchor spilled into invalid late plateau: ${result.anchorIndex}", result.anchorIndex < 1320)
        val expectedStart = (result.anchorIndex - (512 / 5)).coerceAtLeast(0)
        assertTrue("start mismatch: ${result.startIndex} vs $expectedStart", abs(result.startIndex - expectedStart) <= 2)
    }

    @Test
    fun `off mode stays unlocked and uses latest window`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.OFF,
            sampleRateHz = 44_100f,
        )
        val x = FloatArray(768) { i -> if (i % 2 == 0) 0.8f else -0.8f }

        val result = engine.process(x, cfg)

        assertTrue(!result.locked)
        assertEquals(0, result.periodSamples)
        assertEquals(768 - 512, result.startIndex)
    }

    @Test
    fun `extract triggered window honors computed start index`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val source = FloatArray(704) { it.toFloat() }
        val result = NewTriggerEngine.Result(
            startIndex = 96,
            anchorIndex = 220,
            periodSamples = 64,
            confidence = 0.9f,
            locked = true,
            mode = NewTriggerEngine.Mode.RISING,
            freqHz = 200f,
        )

        val window = engine.extractTriggeredWindow(source, result)

        assertEquals(512, window.size)
        assertEquals(96f, window.first(), 0f)
        assertEquals(607f, window.last(), 0f)
    }

    @Test
    fun `autocorr period estimator finds sine period`() {
        val n = 512
        val windowSec = 0.030f
        val dt = windowSec / n
        val fs = 1f / dt
        val f = 250f
        val expectedLag = (fs / f).toInt()

        val x = FloatArray(n) { i -> sin(2.0 * PI * f * (i * dt)).toFloat() }
        val ac = FloatArray(512)
        Autocorrelation.computeNormalized(x = x, start = 0, len = n, maxLag = 512, out = ac)
        val lag = Autocorrelation.estimatePeriodFromAutocorr(ac = ac, acLen = 512, dt = dt, fMinHz = 20f, fMaxHz = 3000f)

        assertTrue("bad lag: $lag (expected ~$expectedLag)", lag in (expectedLag - 2)..(expectedLag + 2))
    }

    @Test
    fun `autocorrelation assisted trigger tracks low frequency fundamental`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 25_600f
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING,
            sampleRateHz = sampleRateHz,
            strongLowPassHz = 240f,
            fMinHz = 5f,
            fMaxHz = 260f,
            useAutocorrelation = true,
            preTriggerRatio = 0.16f,
        )

        val n = 768
        val f = 120f
        val dt = 1f / sampleRateHz
        val buf = FloatArray(n)

        var lockedEver = false
        for (frame in 0 until 50) {
            val phase = frame * 0.18f
            for (i in 0 until n) {
                val t = i * dt
                val base = sin(2.0 * PI * f * t + phase).toFloat()
                val harmonic = 0.28f * sin(2.0 * PI * (2f * f) * t + phase * 1.5f).toFloat()
                val highFreqNoise = 0.08f * sin(2.0 * PI * 900f * t).toFloat()
                buf[i] = base + harmonic + highFreqNoise
            }

            val result = engine.process(buf, cfg)
            if (result.locked) lockedEver = true

            if (frame > 10 && result.locked) {
                val expectedPeriod = (sampleRateHz / f).toInt()
                assertTrue(
                    "bad autocorr period: ${result.periodSamples} expected around $expectedPeriod",
                    result.periodSamples in (expectedPeriod - 8)..(expectedPeriod + 8)
                )
                assertTrue("unexpected freq: ${result.freqHz}", abs(result.freqHz - f) <= 8f)
            }
        }

        assertTrue("autocorr trigger never locked", lockedEver)
    }

    @Test
    fun `corrscope style trigger prefers historical phase over later stronger spike`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 25_600f
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING,
            sampleRateHz = sampleRateHz,
            strongLowPassHz = 240f,
            fMinHz = 5f,
            fMaxHz = 260f,
            useAutocorrelation = true,
            preTriggerRatio = 0.16f,
            autocorrRefreshFrames = 4,
        )

        val n = 768
        val dt = 1f / sampleRateHz
        val buf = FloatArray(n)
        val f = 120f

        fun fillBase(phase: Float) {
            for (i in 0 until n) {
                val t = i * dt
                val base = sin(2.0 * PI * f * t + phase).toFloat()
                val harmonic = 0.15f * sin(2.0 * PI * (2f * f) * t + phase * 1.2f).toFloat()
                buf[i] = base + harmonic
            }
        }

        var stableAnchor = -1
        for (frame in 0 until 6) {
            fillBase(frame * 0.10f)
            val r = engine.process(buf, cfg)
            if (r.locked) stableAnchor = r.anchorIndex
        }

        assertTrue("setup did not lock", stableAnchor >= 0)

        fillBase(0.62f)
        val spikePos = (stableAnchor + 28).coerceIn(0, n - 1)
        for (i in spikePos until min(n, spikePos + 5)) {
            buf[i] += 0.45f
        }

        val result = engine.process(buf, cfg)

        assertTrue(result.locked)
        assertTrue(
            "trigger jumped to later spike: anchor=${result.anchorIndex} stable=$stableAnchor spike=$spikePos",
            abs(result.anchorIndex - stableAnchor) <= 18
        )
        assertTrue(
            "anchor landed on spike region: anchor=${result.anchorIndex} spike=$spikePos",
            abs(result.anchorIndex - spikePos) > 10
        )
    }
}

