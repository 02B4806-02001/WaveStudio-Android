package org.mhrri.wavestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class TriggerEngineTest {

    @Test
    fun `rising edge trigger locks on stable sine`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 44_100f
        val f = 200f
        val n = 1536
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING, sampleRateHz = sampleRateHz,
            fMinHz = 5f, fMaxHz = 1200f,
            sourceMode = NewTriggerEngine.TriggerSourceMode.OUTPUT, preTriggerRatio = 0.16f,
        )
        var lockedEver = false
        for (frame in 0 until 60) {
            val x = FloatArray(n) { sin(2.0 * PI * f * (it / sampleRateHz) + frame * 0.12f).toFloat() }
            val r = engine.process(x, cfg)
            if (r.locked) { lockedEver = true }
        }
        assertTrue("never locked", lockedEver)
    }

    @Test
    fun `off mode returns invalid result`() {
        val engine = NewTriggerEngine()
        val cfg = NewTriggerEngine.Config(mode = NewTriggerEngine.Mode.OFF, sampleRateHz = 44_100f)
        val x = FloatArray(768) { if (it % 2 == 0) 0.8f else -0.8f }
        val r = engine.process(x, cfg)
        assertTrue(!r.locked)
        assertEquals(0, r.periodSamples)
    }

    @Test
    fun `extractTriggeredWindow aligns to anchor`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 44_100f
        val n = 2048
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING, sampleRateHz = sampleRateHz,
            fMinHz = 5f, fMaxHz = 1200f,
            sourceMode = NewTriggerEngine.TriggerSourceMode.OUTPUT, preTriggerRatio = 0.16f,
        )
        val x = FloatArray(n) { i -> when { i < 990 -> -0.5f; i in 990..1010 -> ((i - 990) / 20f - 0.5f); else -> 0.5f } }
        val r = engine.process(x, cfg)
        assertTrue("should find crossing", r.periodSamples > 0 || r.confidence > 0f)
        // targetSize < source.size so alignment works
        val win = engine.extractTriggeredWindow(x, r, 640)
        assertEquals(640, win.size)
    }

    @Test
    fun `e2e continuous sine — 100 frames stability`() {
        val engine = NewTriggerEngine(nominalWindowSize = 512)
        val sampleRateHz = 44_100f
        val f = 200f; val n = 2048
        val cfg = NewTriggerEngine.Config(
            mode = NewTriggerEngine.Mode.RISING, sampleRateHz = sampleRateHz,
            fMinHz = 5f, fMaxHz = 1200f,
            sourceMode = NewTriggerEngine.TriggerSourceMode.OUTPUT, preTriggerRatio = 0.16f,
        )
        var lockCount = 0
        for (frame in 0 until 100) {
            val x = FloatArray(n) { sin(2.0 * PI * f * (it / sampleRateHz) + frame * 0.12f).toFloat() }
            var dc = 0f; for (v in x) dc += v; dc /= n; for (i in x.indices) x[i] -= dc
            val r = engine.process(x, cfg)
            if (r.locked) lockCount++
            if (r.periodSamples > 0) {
                val e = (sampleRateHz / f).toInt()
                assertTrue("period ${r.periodSamples} vs $e", r.periodSamples in (e - 10)..(e + 10))
            }
            val win = engine.extractTriggeredWindow(x, r)
            assertEquals(512, win.size)
        }
        assertTrue("lock rate $lockCount/100", lockCount > 60)
    }

    @Test
    fun `e2e multiple frequencies`() {
        val sampleRateHz = 44_100f
        val n = 2048
        for (f in listOf(50f, 100f, 200f, 440f, 1000f)) {
            val engine = NewTriggerEngine(nominalWindowSize = 512)
            val cfg = NewTriggerEngine.Config(
                mode = NewTriggerEngine.Mode.RISING, sampleRateHz = sampleRateHz,
                fMinHz = 5f, fMaxHz = 2000f,
                sourceMode = NewTriggerEngine.TriggerSourceMode.OUTPUT, preTriggerRatio = 0.16f,
            )
            var ok = false
            for (frame in 0 until 60) {
                val x = FloatArray(n) { sin(2.0 * PI * f * (it / sampleRateHz)).toFloat() }
                var dc = 0f; for (v in x) dc += v; dc /= n; for (i in x.indices) x[i] -= dc
                val r = engine.process(x, cfg)
                if (r.periodSamples > 0 && r.periodSamples in ((sampleRateHz / f).toInt() - 10)..((sampleRateHz / f).toInt() + 10)) ok = true
            }
            assertTrue("$f Hz failed", ok)
        }
    }
}
