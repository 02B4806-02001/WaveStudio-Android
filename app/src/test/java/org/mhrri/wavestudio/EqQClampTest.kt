package org.mhrri.wavestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqQClampTest {

    @Test
    fun `high shelf Q is capped by gain dependent maximum`() {
        val requestedQ = 6f
        val gainDb = 12f
        val capped = AudioEngineViewModel.clampEqQForBand(
            AudioEngineViewModel.EqBandType.HIGH_SHELF,
            gainDb,
            requestedQ
        )

        val maxQ = AudioEngineViewModel.maxEqQForGainDb(gainDb)
        assertTrue("expected gain-dependent cap to be below the requested Q", maxQ < requestedQ)
        assertEquals(maxQ, capped, 1e-4f)
    }

    @Test
    fun `peak Q still respects global slider bounds`() {
        assertEquals(
            0.2f,
            AudioEngineViewModel.clampEqQForBand(AudioEngineViewModel.EqBandType.PEAK, 18f, 0.05f),
            0f
        )
        assertEquals(
            6f,
            AudioEngineViewModel.clampEqQForBand(AudioEngineViewModel.EqBandType.PEAK, 18f, 9f),
            0f
        )
    }
}


