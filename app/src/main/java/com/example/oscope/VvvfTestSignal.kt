package com.example.oscope

import kotlin.math.PI
import kotlin.math.sin

/**
 * VVVF / SPWM 测试波形（用于界面“测试”按钮，不依赖音频采集）
 *
 * 这里使用“同步 SPWM”思路：载波比可配置（fc = carrierMultiple * f0）。
 *
 * 约定：
 * - 基频 f0 = 50Hz
 * - 正弦参考（调制波）幅值 mA 可配置（默认 0.5）
 * - 三角载波幅值 = 1.0，频率 fc = carrierMultiple * f0
 * - 相电压开关函数（相臂 PWM）： ref(t) > carrier(t) => 1，否则 0
 * - 三相参考相位：U=0，V=-2π/3，W=+2π/3（严格 120° 对称）
 * - 线电压 = U - V（输出范围 {-1,0,1}）
 */
object VvvfTestSignal {
    fun generateLineUv(
        sampleRate: Int,
        windowMs: Float,
        baseHz: Float = 50f,
        modulationAmp: Float = 0.5f,
        carrierMultiple: Float = 18f,
        carrierAmp: Float = 1f,
        // New: add interference/noise to make Trigger debugging realistic
        interference: Boolean = true,
        // 0..1 overall strength of the added interference
        interferenceLevel: Float = 0.35f,
        // Deterministic seed so the waveform is repeatable
        seed: Int = 1,
    ): FloatArray {
        val n = (sampleRate * (windowMs / 1000f)).toInt().coerceAtLeast(64)
        val out = FloatArray(n)

        val carrierHz = baseHz * carrierMultiple

        // 严格 120° 相移：2π/3
        val twoPi = 2.0 * PI
        val phi120 = twoPi / 3.0
        val phiU = 0.0
        val phiV = -phi120

        // Deterministic RNG (xorshift32)
        var rng = if (seed != 0) seed else 1
        fun nextFloatSigned(): Float {
            // xorshift32
            var x = rng
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            rng = x
            // map to [-1,1]
            val u = (x ushr 1).toFloat() / Int.MAX_VALUE.toFloat()
            return (u * 2f - 1f)
        }

        // Slow "dirty" AM to simulate supply ripple / speed ripple (still deterministic)
        val amHz1 = baseHz * 0.12f
        val amHz2 = baseHz * 0.07f

        for (i in 0 until n) {
            val t = i.toDouble() / sampleRate.toDouble()

            // 载波（三角波）
            val c = carrierAmp * tri(carrierHz.toDouble(), t)

            val ang = twoPi * baseHz.toDouble() * t

            // Interference: amplitude wobble + small harmonic distortion + ripple
            val am = if (interference) {
                val a1 = 1.0 + (0.12 * interferenceLevel) * sin(twoPi * amHz1.toDouble() * t)
                val a2 = 1.0 + (0.08 * interferenceLevel) * sin(twoPi * amHz2.toDouble() * t + 0.7)
                (a1 * a2).toFloat()
            } else 1f

            // Harmonics (3rd/5th) to create a more complex reference waveform
            val harm = if (interference) {
                val h3 = (0.20f * interferenceLevel) * sin(3.0 * ang + 0.2)
                val h5 = (0.12f * interferenceLevel) * sin(5.0 * ang - 0.4)
                (h3 + h5).toFloat()
            } else 0f

            // High-frequency ripple around carrier (simulates switching ripple leaking into measurement)
            val ripple = if (interference) {
                val r = (0.10f * interferenceLevel) * sin(twoPi * (carrierHz * 1.7).toDouble() * t + 1.1)
                r.toFloat()
            } else 0f

            // Occasional impulse noise (deterministic) to test robustness
            val impulse = if (interference) {
                // About 1 impulse per ~2000 samples on average (depends on rng)
                val p = kotlin.math.abs(nextFloatSigned())
                if (p > 0.9992f) (0.8f * interferenceLevel) * nextFloatSigned() else 0f
            } else 0f

            // U 相
            val mU = (modulationAmp * am) * sin(ang + phiU).toFloat() + harm + ripple
            val u = if (mU > c) 1f else 0f

            // V 相：-120°
            val mV = (modulationAmp * am) * sin(ang + phiV).toFloat() - harm + ripple
            val v = if (mV > c) 1f else 0f

            // Base SPWM line voltage
            var y = u - v

            // Add a tiny measurement noise floor (keeps periodicity but makes edges less perfect)
            if (interference) {
                y += (0.04f * interferenceLevel) * nextFloatSigned()
                y += impulse
            }

            out[i] = y.coerceIn(-1.2f, 1.2f)
        }

        return out
    }

    /**
     * 幅值 [-1,1] 的对称三角波。
     */
    private fun tri(freqHz: Double, tSec: Double): Double {
        // wrap: avoid negative modulo issues
        val x = ((tSec * freqHz) % 1.0 + 1.0) % 1.0 // [0,1)
        return 4.0 * kotlin.math.abs(x - 0.5) - 1.0
    }
}
