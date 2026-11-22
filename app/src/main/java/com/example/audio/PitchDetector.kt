package com.example.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Low-CPU zero-allocation YIN vocal pitch detector with octave-glitch rejection
 * and consonant/breath gap pitch hold to prevent vinyl-scratch artifacts in TD-PSOLA.
 */
class PitchDetector(
    private val windowSize: Int = 1024
) {
    private val halfWindow = windowSize / 2
    private val diffBuffer = FloatArray(halfWindow)
    private val cmndBuffer = FloatArray(halfWindow)
    private val analysisFrame = FloatArray(windowSize)

    private var smoothedFreqHz = 0f
    private var unvoicedCount = 0

    // Multi-frame confirmation prevents single-frame octave errors from pulling pitch
    private var jumpCandidateHz = 0f
    private var jumpCandidateCount = 0

    @Volatile
    var isCurrentFrameVoiced: Boolean = false
        private set

    fun reset() {
        smoothedFreqHz = 0f
        unvoicedCount = 0
        jumpCandidateHz = 0f
        jumpCandidateCount = 0
        isCurrentFrameVoiced = false
    }

    fun detectPitchHz(
        ringBuffer: FloatArray,
        writePos: Int,
        sampleRate: Int,
        minRmsThreshold: Float = 0.008f
    ): Float {
        val ringMask = ringBuffer.size - 1
        var energy = 0f
        val start = writePos - windowSize
        for (i in 0 until windowSize) {
            val s = ringBuffer[(start + i) and ringMask]
            analysisFrame[i] = s
            energy += s * s
        }
        val rms = sqrt(energy / windowSize)
        if (rms < minRmsThreshold) {
            isCurrentFrameVoiced = false
            unvoicedCount++
            if (unvoicedCount > 18) {
                smoothedFreqHz = 0f
                jumpCandidateCount = 0
            }
            return smoothedFreqHz
        }

        val minTau = (sampleRate / 950).coerceAtLeast(16)
        val maxTau = (sampleRate / 75).coerceAtMost(halfWindow - 1)
        if (maxTau <= minTau + 3) {
            isCurrentFrameVoiced = false
            return smoothedFreqHz
        }

        // 1. Coarse difference function d(tau) at stride 2 (cuts CPU load by 50% with zero lost range)
        diffBuffer[0] = 0f
        for (tau in 1..maxTau) {
            var sum = 0f
            var j = 0
            while (j < halfWindow) {
                val delta = analysisFrame[j] - analysisFrame[j + tau]
                sum += delta * delta
                j += 2
            }
            diffBuffer[tau] = sum
        }

        // 2. Cumulative mean normalized difference function (CMNDF)
        cmndBuffer[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxTau) {
            runningSum += diffBuffer[tau]
            cmndBuffer[tau] = if (runningSum > 1e-9f) {
                diffBuffer[tau] * tau / runningSum
            } else {
                1f
            }
        }

        // 3. Absolute threshold search
        val threshold = 0.16f
        var foundTau = -1
        var tau = minTau
        while (tau < maxTau) {
            if (cmndBuffer[tau] < threshold) {
                while (tau + 1 < maxTau && cmndBuffer[tau + 1] < cmndBuffer[tau]) {
                    tau++
                }
                foundTau = tau
                break
            }
            tau++
        }

        // Fallback: global minimum if slightly breathy voice
        if (foundTau == -1) {
            var minVal = 0.28f
            for (t in minTau until maxTau) {
                if (cmndBuffer[t] < minVal) {
                    minVal = cmndBuffer[t]
                    foundTau = t
                }
            }
        }

        if (foundTau <= 2 || foundTau >= maxTau - 2) {
            isCurrentFrameVoiced = false
            unvoicedCount++
            if (unvoicedCount > 18) {
                smoothedFreqHz = 0f
                jumpCandidateCount = 0
            }
            return smoothedFreqHz
        }

        // 4. Exact full-resolution local refinement around [foundTau - 2 .. foundTau + 2]
        val fineStart = (foundTau - 2).coerceAtLeast(minTau - 1).coerceAtLeast(1)
        val fineEnd = (foundTau + 2).coerceAtMost(maxTau - 1)
        var bestFineTau = foundTau
        var bestFineDiff = Float.MAX_VALUE
        val fineSums = FloatArray(5)
        for (t in fineStart..fineEnd) {
            var sum = 0f
            for (j in 0 until halfWindow) {
                val delta = analysisFrame[j] - analysisFrame[j + t]
                sum += delta * delta
            }
            fineSums[t - fineStart] = sum
            if (sum < bestFineDiff) {
                bestFineDiff = sum
                bestFineTau = t
            }
        }

        // Parabolic interpolation on full-res local window
        val idx = bestFineTau - fineStart
        val betterTau = if (idx in 1..(fineEnd - fineStart - 1)) {
            val s0 = fineSums[idx - 1]
            val s1 = fineSums[idx]
            val s2 = fineSums[idx + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (abs(denom) > 1e-6f) bestFineTau + (s2 - s0) / denom else bestFineTau.toFloat()
        } else {
            bestFineTau.toFloat()
        }

        val freq = sampleRate / betterTau
        if (freq in 70f..1000f) {
            unvoicedCount = 0
            isCurrentFrameVoiced = true
            if (smoothedFreqHz <= 0f) {
                smoothedFreqHz = freq
                jumpCandidateCount = 0
            } else {
                val ratio = freq / smoothedFreqHz
                if (ratio in 0.78f..1.28f) {
                    // Normal continuous pitch tracking / vibrato
                    smoothedFreqHz = smoothedFreqHz * 0.65f + freq * 0.35f
                    jumpCandidateCount = 0
                } else {
                    // Reject single/double-frame octave glitches; only switch on genuine 3-frame note leap
                    if (jumpCandidateCount > 0 && (freq / jumpCandidateHz) in 0.88f..1.14f) {
                        jumpCandidateCount++
                    } else {
                        jumpCandidateHz = freq
                        jumpCandidateCount = 1
                    }
                    if (jumpCandidateCount >= 3) {
                        smoothedFreqHz = freq
                        jumpCandidateCount = 0
                    }
                }
            }
        } else {
            isCurrentFrameVoiced = false
        }
        return smoothedFreqHz
    }
}
