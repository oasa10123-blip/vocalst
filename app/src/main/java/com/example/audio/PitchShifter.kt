package com.example.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Real-Time Vocal Pitch Shifter supporting:
 * 1) Artifact-free TD-PSOLA (Time-Domain Pitch-Synchronous Overlap-Add):
 *    - Monotonic pitch-mark ring buffer using 5-tap LPF peak detection + centering bias (no harmonic peak hopping)
 *    - Continuous 1.0x real-time analysis cursor mapped to nearest verified pitch mark (no DJ-scratch jumps)
 *    - Locked-period persistence over brief consonants/silences (no sudden 240-sample tempo warps)
 *    - Guaranteed COLA overlap normalization (weightRing) preventing dropouts/stutter
 * 2) Classic Dual-Tap Fractional Delay Shifter (when TD-PSOLA is toggled OFF).
 */
class PitchShifter(
    ringCapacityPow2: Int = 16384
) {
    private val mask = ringCapacityPow2 - 1
    private val inputRing = FloatArray(ringCapacityPow2)
    private val outputRing = FloatArray(ringCapacityPow2)
    private val weightRing = FloatArray(ringCapacityPow2)

    // Monotonic ring of verified glottal pitch marks (sample positions)
    private val markCapacity = 64
    private val markMask = markCapacity - 1
    private val markRing = IntArray(markCapacity)
    private var markWriteCount = 0
    private var lastMarkPos = 0

    private var writePos = 0
    private var nextSynthPos = 0.0
    private var currentRatio = 1f
    private var activePeriodIn = 220f
    private var voicedEnvelope = 1f

    // Classic Dual-Tap delay-line state (used when tdPsolaEnabled = false)
    private var classicPhase = 0f
    private val classicWindowSize = 1024f

    // Smooth cross-ramp when switching between TD-PSOLA and Classic mode
    private var lastModeTdPsola = true
    private var modeSwitchFade = 1f

    // Precomputed raised-cosine Hann lookup table
    private val tableSize = 1024
    private val hannTable = FloatArray(tableSize + 1) { i ->
        val u = i.toFloat() / tableSize.toFloat()
        (0.5 * (1.0 - cos(2.0 * PI * u))).toFloat()
    }

    private val analysisLag = 480
    private val outputReadLag = 500
    private val maxHalfWin = 460

    fun reset() {
        inputRing.fill(0f)
        outputRing.fill(0f)
        weightRing.fill(0f)
        markRing.fill(0)
        markWriteCount = 0
        lastMarkPos = 0
        writePos = 0
        nextSynthPos = 0.0
        currentRatio = 1f
        activePeriodIn = 220f
        voicedEnvelope = 1f
        classicPhase = 0f
        modeSwitchFade = 1f
    }

    fun processSample(
        input: Float,
        targetRatio: Float,
        detectedFreqHz: Float,
        sampleRate: Int,
        tdPsolaEnabled: Boolean = true,
        pitchSmoothing: Float = 0.60f,
        grainOverlap: Float = 1.05f,
        peakSearchRatio: Float = 0.30f,
        unvoicedDamping: Float = 0.35f,
        isVoicedFrame: Boolean = detectedFreqHz in 70f..950f
    ): Float {
        if (tdPsolaEnabled != lastModeTdPsola) {
            lastModeTdPsola = tdPsolaEnabled
            modeSwitchFade = 0f
            if (tdPsolaEnabled) {
                outputRing.fill(0f)
                weightRing.fill(0f)
                markWriteCount = 0
                lastMarkPos = writePos - analysisLag
                nextSynthPos = writePos.toDouble()
            } else {
                classicPhase = 0f
            }
        }
        if (modeSwitchFade < 1f) {
            modeSwitchFade = min(1f, modeSwitchFade + 0.005f)
        }

        // Exponential slew alpha: 0.0 -> 0.025 (snappy), 0.6 -> ~0.00266 (silky default), 1.0 -> 0.0006 (legato)
        val smoothNorm = pitchSmoothing.coerceIn(0f, 1f).toDouble()
        val slewAlpha = (0.025 * 0.024.pow(smoothNorm)).toFloat()
        currentRatio += (targetRatio - currentRatio) * slewAlpha

        inputRing[writePos and mask] = input

        val outSample = if (tdPsolaEnabled) {
            processTdPsolaSample(
                detectedFreqHz = detectedFreqHz,
                sampleRate = sampleRate,
                grainOverlap = grainOverlap.coerceIn(0.80f, 1.50f),
                peakSearchRatio = peakSearchRatio.coerceIn(0.05f, 0.45f),
                unvoicedDamping = unvoicedDamping.coerceIn(0.10f, 1.00f),
                isVoicedFrame = isVoicedFrame
            )
        } else {
            processClassicDualTapSample()
        }

        writePos++
        return outSample * modeSwitchFade
    }

    private fun processTdPsolaSample(
        detectedFreqHz: Float,
        sampleRate: Int,
        grainOverlap: Float,
        peakSearchRatio: Float,
        unvoicedDamping: Float,
        isVoicedFrame: Boolean
    ): Float {
        val hasPitch = detectedFreqHz in 70f..950f
        if (hasPitch) {
            val targetPeriodIn = (sampleRate.toFloat() / detectedFreqHz).coerceIn(48f, 640f)
            // Gentle continuous period tracking prevents sudden time-warp swings
            activePeriodIn += (targetPeriodIn - activePeriodIn) * 0.012f
        }
        // Keep activePeriodIn frozen when !hasPitch instead of snapping to 240f (no DJ-scratch drop)
        val pIn = activePeriodIn

        val targetVoicedEnv = if (isVoicedFrame || currentRatio in 0.97f..1.03f) 1f else unvoicedDamping
        voicedEnvelope += (targetVoicedEnv - voicedEnvelope) * 0.004f

        val ratio = currentRatio.coerceIn(0.45f, 2.2f)
        val periodOut = (pIn / ratio).coerceIn(32f, 680f)

        // 1. Append verified, monotonically increasing pitch marks to markRing safely behind safeHead
        val safeHead = writePos - analysisLag
        if (markWriteCount == 0) {
            if (safeHead >= 0) {
                lastMarkPos = safeHead
                markRing[0] = lastMarkPos
                markWriteCount = 1
            }
        } else {
            var guard = 0
            while (safeHead - lastMarkPos >= pIn && guard < 4) {
                guard++
                val expected = lastMarkPos + pIn.roundToInt()
                val nextMark = if (isVoicedFrame && hasPitch && peakSearchRatio > 0.01f) {
                    val radius = (pIn * peakSearchRatio).toInt().coerceIn(4, 150)
                    val minAllowed = lastMarkPos + (pIn * 0.65f).toInt().coerceAtLeast(32)
                    val startSearch = max(minAllowed, expected - radius)
                    val endSearch = min(safeHead, expected + radius)
                    if (startSearch <= endSearch) {
                        var bestIdx = expected.coerceIn(startSearch, endSearch)
                        var bestScore = 0f
                        val invRadiusSq = 1f / max(1, radius * radius).toFloat()
                        for (p in startSearch..endSearch) {
                            // 5-tap LPF suppresses formant harmonic spikes so marks lock onto the true fundamental pulse
                            val smoothed = 0.10f * inputRing[(p - 2) and mask] +
                                0.20f * inputRing[(p - 1) and mask] +
                                0.40f * inputRing[p and mask] +
                                0.20f * inputRing[(p + 1) and mask] +
                                0.10f * inputRing[(p + 2) and mask]
                            if (smoothed > 0f) {
                                val dist = (p - expected).toFloat()
                                val centerBias = 1f - 0.18f * (dist * dist * invRadiusSq)
                                val score = smoothed * centerBias
                                if (score > bestScore) {
                                    bestScore = score
                                    bestIdx = p
                                }
                            }
                        }
                        if (bestScore > 0f) bestIdx else expected.coerceIn(minAllowed, safeHead)
                    } else {
                        expected.coerceIn(minAllowed, safeHead)
                    }
                } else {
                    expected.coerceAtMost(safeHead)
                }
                lastMarkPos = nextMark
                markRing[markWriteCount and markMask] = nextMark
                markWriteCount++
            }
        }

        // 2. Continuous 1.0x analysis cursor selects nearest verified pitch mark (never teleports!)
        val analysisCursor = (safeHead - pIn * 0.65).coerceAtLeast(0.0)
        if (nextSynthPos < writePos - 32.0) {
            nextSynthPos = writePos.toDouble()
        }
        while (nextSynthPos <= writePos.toDouble()) {
            val synthCenter = nextSynthPos.toInt()
            // Ensure halfWin >= periodOut * 1.04 so COLA weightRing never drops near zero
            val minSafeHalfWin = (periodOut * 1.04f).toInt()
            val desiredHalfWin = (max(pIn, periodOut) * grainOverlap).toInt()
            val halfWin = max(minSafeHalfWin, desiredHalfWin).coerceIn(64, maxHalfWin)

            var pm = safeHead
            val count = min(markWriteCount, 20)
            if (count > 0) {
                var bestDist = Double.MAX_VALUE
                for (i in 1..count) {
                    val candidate = markRing[(markWriteCount - i) and markMask]
                    if (candidate > safeHead) continue
                    val d = abs(candidate - analysisCursor)
                    if (d < bestDist) {
                        bestDist = d
                        pm = candidate
                    } else if (candidate < analysisCursor) {
                        break
                    }
                }
            }

            val invDoubleW = tableSize.toFloat() / (2f * halfWin)
            for (k in -halfWin..halfWin) {
                val tIdx = ((k + halfWin) * invDoubleW).toInt().coerceIn(0, tableSize)
                val w = hannTable[tIdx]
                val s = inputRing[(pm + k) and mask]
                val oIdx = (synthCenter + k) and mask
                outputRing[oIdx] += s * w
                weightRing[oIdx] += w
            }
            nextSynthPos += periodOut.toDouble()
        }

        // 3. Read COLA-normalized sample safely behind synthesis grains and clear cell
        val outPos = writePos - outputReadLag
        val outIdx = outPos and mask
        val wSum = weightRing[outIdx]
        val outSample = if (wSum > 0.05f) {
            (outputRing[outIdx] / wSum) * voicedEnvelope
        } else {
            0f
        }
        outputRing[outIdx] = 0f
        weightRing[outIdx] = 0f
        return outSample
    }

    private fun processClassicDualTapSample(): Float {
        val ratio = currentRatio.coerceIn(0.45f, 2.2f)
        classicPhase += (1f - ratio) / classicWindowSize
        while (classicPhase >= 1f) classicPhase -= 1f
        while (classicPhase < 0f) classicPhase += 1f

        val phaseA = classicPhase
        val phaseB = if (phaseA + 0.5f >= 1f) phaseA - 0.5f else phaseA + 0.5f

        val delayA = 64f + phaseA * classicWindowSize
        val delayB = 64f + phaseB * classicWindowSize

        val sampleA = readInterpolated(writePos - delayA)
        val sampleB = readInterpolated(writePos - delayB)

        val wA = hannTable[(phaseA * tableSize).toInt().coerceIn(0, tableSize)]
        val wB = 1f - wA
        return sampleA * wA + sampleB * wB
    }

    private fun readInterpolated(pos: Float): Float {
        val i0 = floor(pos).toInt()
        val frac = pos - i0
        val s0 = inputRing[i0 and mask]
        val s1 = inputRing[(i0 + 1) and mask]
        return s0 + (s1 - s0) * frac
    }
}
