package com.example.audio

import com.example.model.EchoMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Zero-allocation vocal channel strip & spatial effects processor:
 * - 80 Hz High-Pass Filter (removes stage mic handling rumble)
 * - Smooth Noise Gate
 * - Enhancer (2.8 kHz vocal presence shelf + soft-knee compressor)
 * - Echo Engine (4-comb + 2-allpass Schroeder Reverb + Feedback Delay)
 * - Soft Output Limiter
 */
class VocalEffectsProcessor(
    sampleRate: Int = 48000
) {
    private var sr = sampleRate.toFloat()

    // 1-pole HPF state
    private var hpfPrevIn = 0f
    private var hpfPrevOut = 0f
    private var hpfCoeff = 0.989f

    // Presence shelf filter state (biquad-like 1-pole split)
    private var presenceLpState = 0f
    private var presenceLpCoeff = 0.72f

    // Gate & Compressor envelopes
    private var gateEnv = 0f
    private var gateGain = 1f
    private var compEnv = 0f

    // Feedback Delay Line (max 600 ms at 48kHz)
    private val delayBuffer = FloatArray(32768)
    private var delayWriteIdx = 0
    private var delayLpState = 0f

    // Schroeder 4-Comb + 2-Allpass Reverb Tank
    private val combDelays = intArrayOf(1557, 1617, 1491, 1422)
    private val combBuffers = Array(4) { FloatArray(2400) }
    private val combIndices = IntArray(4)
    private val combLpStates = FloatArray(4)

    private val allpassDelays = intArrayOf(225, 556)
    private val allpassBuffers = Array(2) { FloatArray(1024) }
    private val allpassIndices = IntArray(2)

    init {
        configureSampleRate(sampleRate)
    }

    fun configureSampleRate(sampleRate: Int) {
        sr = sampleRate.toFloat().coerceAtLeast(8000f)
        // 80 Hz HPF
        val rc = 1f / (2f * Math.PI.toFloat() * 80f)
        val dt = 1f / sr
        hpfCoeff = rc / (rc + dt)
        // ~2.4 kHz split for vocal presence boost
        presenceLpCoeff = exp((-2f * Math.PI.toFloat() * 2400f) / sr)
    }

    fun reset() {
        hpfPrevIn = 0f
        hpfPrevOut = 0f
        presenceLpState = 0f
        gateEnv = 0f
        gateGain = 1f
        compEnv = 0f
        delayBuffer.fill(0f)
        delayWriteIdx = 0
        delayLpState = 0f
        for (i in combBuffers.indices) {
            combBuffers[i].fill(0f)
            combIndices[i] = 0
            combLpStates[i] = 0f
        }
        for (i in allpassBuffers.indices) {
            allpassBuffers[i].fill(0f)
            allpassIndices[i] = 0
        }
    }

    fun processInputChannel(
        sample: Float,
        micGainLinear: Float,
        noiseGateDb: Float,
        enhanceEnabled: Boolean
    ): Float {
        // 1. Input gain
        var x = sample * micGainLinear

        // 2. 80 Hz HPF
        val hpfOut = hpfCoeff * (hpfPrevOut + x - hpfPrevIn)
        hpfPrevIn = x
        hpfPrevOut = hpfOut
        x = hpfOut

        // 3. Noise Gate
        val absX = abs(x)
        gateEnv = if (absX > gateEnv) {
            gateEnv + 0.02f * (absX - gateEnv)
        } else {
            gateEnv + 0.0008f * (absX - gateEnv)
        }
        val gateThreshLinear = 10.0.pow(noiseGateDb / 20.0).toFloat()
        val targetGateGain = if (gateEnv > gateThreshLinear) 1f else 0.04f
        gateGain += (targetGateGain - gateGain) * (if (targetGateGain > gateGain) 0.015f else 0.0015f)
        x *= gateGain

        // 4. Enhancer (Vocal Presence + Soft-knee Compression)
        if (enhanceEnabled) {
            presenceLpState = presenceLpCoeff * presenceLpState + (1f - presenceLpCoeff) * x
            val highs = x - presenceLpState
            x += highs * 0.42f // +3 dB presence clarity

            val envAbs = abs(x)
            compEnv = if (envAbs > compEnv) {
                compEnv + 0.01f * (envAbs - compEnv)
            } else {
                compEnv + 0.0005f * (envAbs - compEnv)
            }
            val compThreshold = 0.18f
            if (compEnv > compThreshold) {
                val excess = compEnv - compThreshold
                val gain = (compThreshold + excess * 0.35f) / compEnv
                x *= gain * 1.15f
            } else {
                x *= 1.15f
            }
        }
        return x
    }

    fun processEcho(
        dryPlusHarmony: Float,
        echoMode: EchoMode,
        echoMix: Float
    ): Float {
        if (echoMode == EchoMode.OFF || echoMix <= 0.001f) {
            // Still advance delay buffers silently to avoid stale tails when re-enabled
            delayBuffer[delayWriteIdx] = 0f
            delayWriteIdx = (delayWriteIdx + 1) and (delayBuffer.size - 1)
            return dryPlusHarmony
        }

        val scaleFactor = (sr / 44100f).coerceIn(0.5f, 2.5f)
        var reverbOut = 0f
        var delayOut = 0f

        if (echoMode == EchoMode.REVERB || echoMode == EchoMode.REV_DLY) {
            var combSum = 0f
            val feedback = 0.78f
            for (i in 0 until 4) {
                val len = (combDelays[i] * scaleFactor).toInt().coerceIn(64, combBuffers[i].size - 1)
                val idx = combIndices[i]
                val buf = combBuffers[i]
                val delayed = buf[idx]
                // Damping low-pass in feedback path for warm stage reverb
                combLpStates[i] = delayed * 0.72f + combLpStates[i] * 0.28f
                buf[idx] = dryPlusHarmony * 0.25f + combLpStates[i] * feedback
                combIndices[i] = if (idx + 1 >= len) 0 else idx + 1
                combSum += delayed
            }
            var ap = combSum
            for (i in 0 until 2) {
                val len = (allpassDelays[i] * scaleFactor).toInt().coerceIn(32, allpassBuffers[i].size - 1)
                val idx = allpassIndices[i]
                val buf = allpassBuffers[i]
                val delayed = buf[idx]
                val out = -ap + delayed
                buf[idx] = ap + delayed * 0.5f
                allpassIndices[i] = if (idx + 1 >= len) 0 else idx + 1
                ap = out
            }
            reverbOut = ap * 0.45f
        }

        if (echoMode == EchoMode.DELAY || echoMode == EchoMode.REV_DLY) {
            val delaySamples = (0.295f * sr).toInt().coerceIn(256, delayBuffer.size - 1)
            val readIdx = (delayWriteIdx - delaySamples + delayBuffer.size) and (delayBuffer.size - 1)
            val tap = delayBuffer[readIdx]
            delayLpState = tap * 0.65f + delayLpState * 0.35f
            delayBuffer[delayWriteIdx] = dryPlusHarmony + delayLpState * 0.34f
            delayWriteIdx = (delayWriteIdx + 1) and (delayBuffer.size - 1)
            delayOut = tap * 0.55f
        } else {
            delayBuffer[delayWriteIdx] = 0f
            delayWriteIdx = (delayWriteIdx + 1) and (delayBuffer.size - 1)
        }

        val wet = when (echoMode) {
            EchoMode.OFF -> 0f
            EchoMode.REVERB -> reverbOut
            EchoMode.DELAY -> delayOut
            EchoMode.REV_DLY -> reverbOut * 0.72f + delayOut * 0.62f
        }

        return dryPlusHarmony + wet * (echoMix * 0.95f)
    }

    fun softLimit(sample: Float): Float {
        return if (abs(sample) > 0.75f) {
            tanh(sample.toDouble()).toFloat() * 0.96f
        } else {
            sample
        }
    }
}
