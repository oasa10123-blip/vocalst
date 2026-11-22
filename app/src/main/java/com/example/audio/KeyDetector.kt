package com.example.audio

import com.example.model.KeyCandidate
import com.example.model.MinorKeyMapping
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class KeyAnalysisResult(
    val hasLockedKey: Boolean = false,
    val lockedKey: MusicalKey = MusicalKey.C,
    val lockedScale: MusicalScale = MusicalScale.MAJOR,
    val liveKey: MusicalKey = MusicalKey.C,
    val liveScale: MusicalScale = MusicalScale.MAJOR,
    val confidence: Float = 0f,
    val signalActive: Boolean = false,
    val dominantFreqHz: Float = 0f,
    val dominantMidi: Int = 0,
    val liveChordLabel: String = "--",
    val chroma: List<Float> = List(12) { 0f },
    val topCandidates: List<KeyCandidate> = emptyList()
)

/**
 * Guitar Chord-Progression & Melody Key Detector (HPCP + Dual-Timescale Memory).
 * Mirrors native KeyDetectorCpp 1:1 for Kotlin fallback, full-clip song scanning, and unit testing.
 */
class KeyDetector {

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE) { i ->
        var rev = 0
        var v = i
        repeat(12) {
            rev = (rev shl 1) or (v and 1)
            v = v ushr 1
        }
        rev
    }
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val binMags = FloatArray(FFT_SIZE / 2)

    private val majorCentered = FloatArray(12)
    private val minorCentered = FloatArray(12)
    private var majorNorm = 1f
    private var minorNorm = 1f

    private val fastChroma = FloatArray(12)
    private val progChroma = FloatArray(12)
    private val bassAccum = FloatArray(12)
    private val lastNormalizedChroma = FloatArray(12)
    private var voicedFrames = 0
    private var modulationFrames = 0
    private var hasLockedKey = false
    private var lockedKey = MusicalKey.C
    private var lockedScale = MusicalScale.MAJOR
    private var liveKey = MusicalKey.C
    private var liveScale = MusicalScale.MAJOR
    private var pendingKey = MusicalKey.C
    private var pendingScale = MusicalScale.MAJOR
    private var pendingHoldCount = 0
    private var liveChordRoot = 0
    private var liveChordQuality = -1 // -1=None, 0=Maj, 1=Min, 2=Note
    private var lastConfidence = 0f
    private var lastTop: List<KeyCandidate> = emptyList()

    init {
        val majMean = MAJOR_PROFILE.average().toFloat()
        val minMean = MINOR_PROFILE.average().toFloat()
        var majSq = 0f
        var minSq = 0f
        for (i in 0 until 12) {
            majorCentered[i] = MAJOR_PROFILE[i] - majMean
            minorCentered[i] = MINOR_PROFILE[i] - minMean
            majSq += majorCentered[i] * majorCentered[i]
            minSq += minorCentered[i] * minorCentered[i]
        }
        majorNorm = sqrt(majSq).coerceAtLeast(1e-6f)
        minorNorm = sqrt(minSq).coerceAtLeast(1e-6f)
    }

    fun reset() {
        fastChroma.fill(0f)
        progChroma.fill(0f)
        bassAccum.fill(0f)
        lastNormalizedChroma.fill(0f)
        voicedFrames = 0
        modulationFrames = 0
        hasLockedKey = false
        lockedKey = MusicalKey.C
        lockedScale = MusicalScale.MAJOR
        liveKey = MusicalKey.C
        liveScale = MusicalScale.MAJOR
        pendingKey = MusicalKey.C
        pendingScale = MusicalScale.MAJOR
        pendingHoldCount = 0
        liveChordRoot = 0
        liveChordQuality = -1
        lastConfidence = 0f
        lastTop = emptyList()
    }

    fun analyzeDecimatedFrame(
        decimated4096: FloatArray,
        decimatedSampleRate: Float,
        noiseGateDb: Float = -56f,
        decayFactor: Float = 0.986f,
        lockHoldFrames: Int = 22,
        referenceA4Hz: Float = 440f
    ): KeyAnalysisResult {
        if (decimated4096.size < FFT_SIZE || decimatedSampleRate < 4000f) {
            return KeyAnalysisResult()
        }

        var energySum = 0.0
        for (i in 0 until FFT_SIZE) {
            val s = decimated4096[i]
            energySum += s * s
            fftRe[i] = s * hannWindow[i]
            fftIm[i] = 0f
        }
        val rms = sqrt(energySum / FFT_SIZE).toFloat()
        val rmsDb = if (rms > 1e-6f) 20f * log10(rms) else -120f
        val effectiveGate = noiseGateDb.coerceIn(-72f, -18f)

        if (rmsDb < effectiveGate) {
            return KeyAnalysisResult(
                hasLockedKey = hasLockedKey,
                lockedKey = lockedKey,
                lockedScale = lockedScale,
                liveKey = liveKey,
                liveScale = liveScale,
                confidence = lastConfidence,
                signalActive = false,
                liveChordLabel = formatChordLabel(liveChordRoot, liveChordQuality),
                chroma = lastNormalizedChroma.toList(),
                topCandidates = lastTop
            )
        }

        computeFft()

        val binHz = decimatedSampleRate / FFT_SIZE.toFloat()
        val minBin = (floor(65f / binHz).toInt()).coerceAtLeast(2)
        val maxBin = (kotlin.math.ceil(1850f / binHz).toInt()).coerceAtMost(FFT_SIZE / 2 - 2)
        val safeA4 = referenceA4Hz.coerceIn(425f, 455f)
        val invLog2 = 1.442695f

        var peakBinMag = 0f
        var magSum = 0f
        var bestBin = 0
        var binCount = 0

        for (b in (minBin - 1)..(maxBin + 1)) {
            val re = fftRe[b]
            val im = fftIm[b]
            val mag = sqrt(re * re + im * im) * (2f / FFT_SIZE.toFloat())
            binMags[b] = mag
            if (b in minBin..maxBin) {
                magSum += mag
                binCount++
                if (mag > peakBinMag) {
                    peakBinMag = mag
                    bestBin = b
                }
            }
        }

        val meanMag = if (binCount > 0) magSum / binCount.toFloat() else 0f
        val crestRatio = peakBinMag / meanMag.coerceAtLeast(1e-7f)
        if (peakBinMag < 2e-5f || crestRatio < 2.8f) {
            return KeyAnalysisResult(
                hasLockedKey = hasLockedKey,
                lockedKey = lockedKey,
                lockedScale = lockedScale,
                liveKey = liveKey,
                liveScale = liveScale,
                confidence = lastConfidence,
                signalActive = true,
                liveChordLabel = formatChordLabel(liveChordRoot, liveChordQuality),
                chroma = lastNormalizedChroma.toList(),
                topCandidates = lastTop
            )
        }

        val frameChroma = FloatArray(12)
        val frameBass = FloatArray(12)
        val minPeakMag = peakBinMag * 0.055f

        for (b in minBin..maxBin) {
            val m = binMags[b]
            if (m < minPeakMag) continue
            val mL = binMags[b - 1]
            val mR = binMags[b + 1]
            if (m < mL || m < mR) continue

            val denom = mL - 2f * m + mR
            val delta = if (abs(denom) > 1e-9f) {
                (0.5f * (mL - mR) / denom).coerceIn(-0.5f, 0.5f)
            } else {
                0f
            }
            val freq = (b.toFloat() + delta) * binHz
            if (freq < 65f || freq > 1850f) continue

            val bandWeight = when {
                freq < 290f -> 1.55f
                freq < 920f -> 1.10f
                else -> 0.38f
            }
            val specEnergy = ln(1f + 120f * m) * bandWeight
            val midi = 69f + 12f * (ln(freq / safeA4) * invLog2)
            var pc = midi % 12f
            if (pc < 0f) pc += 12f

            val pc0 = pc.toInt() % 12
            val pc1 = (pc0 + 1) % 12
            val frac = pc - floor(pc)

            val w0 = cos(frac * (PI.toFloat() * 0.5f))
            val w1 = sin(frac * (PI.toFloat() * 0.5f))
            frameChroma[pc0] += specEnergy * (w0 * w0)
            frameChroma[pc1] += specEnergy * (w1 * w1)

            if (freq < 265f) {
                frameBass[pc0] += specEnergy * (w0 * w0)
                frameBass[pc1] += specEnergy * (w1 * w1)
            }
        }

        val domFreq = if (bestBin > 0) bestBin * binHz else 0f
        val domMidi = if (domFreq > 0f) (69f + 12f * (ln(domFreq / safeA4) * invLog2)).roundToInt() else 0

        var frameNormSq = 0f
        for (v in frameChroma) frameNormSq += v * v
        if (frameNormSq > 1e-9f) {
            val invNorm = 1f / sqrt(frameNormSq)
            for (i in 0 until 12) frameChroma[i] *= invNorm
        }
        var bassNormSq = 0f
        for (v in frameBass) bassNormSq += v * v
        if (bassNormSq > 1e-9f) {
            val invBass = 1f / sqrt(bassNormSq)
            for (i in 0 until 12) frameBass[i] *= invBass
        }

        voicedFrames++
        val fastDecay = if (voicedFrames <= 3) 1f - 1f / voicedFrames.toFloat() else 0.84f
        val fastAlpha = 1f - fastDecay
        for (i in 0 until 12) {
            fastChroma[i] = fastChroma[i] * fastDecay + frameChroma[i] * fastAlpha
        }
        estimateLiveChord()

        if (hasLockedKey) {
            var totalFast = 0f
            var outsideFast = 0f
            val lockedIsMin = lockedScale == MusicalScale.MINOR
            val lockedIdx = lockedKey.semitoneOffset
            for (i in 0 until 12) {
                totalFast += fastChroma[i]
                val rel = ((i - lockedIdx) + 12) % 12
                if (!isDiatonicStep(rel, lockedIsMin)) {
                    outsideFast += fastChroma[i]
                }
            }
            val nonDiatonicRatio = if (totalFast > 1e-6f) outsideFast / totalFast else 0f
            if (nonDiatonicRatio > 0.34f) {
                modulationFrames = (modulationFrames + 1).coerceAtMost(30)
            } else {
                modulationFrames = (modulationFrames - 1).coerceAtLeast(0)
            }
        } else {
            modulationFrames = 0
        }

        val baseDecay = decayFactor.coerceIn(0.88f, 0.996f)
        var progDecay = baseDecay
        var progAlpha = 1f - progDecay
        if (modulationFrames >= 5) {
            progAlpha = maxOf(0.052f, progAlpha * 3.0f).coerceAtMost(0.068f)
            progDecay = 1f - progAlpha
        }

        var maxChroma = 1e-6f
        for (i in 0 until 12) {
            progChroma[i] = progChroma[i] * progDecay + frameChroma[i] * progAlpha
            bassAccum[i] = bassAccum[i] * progDecay + frameBass[i] * progAlpha
            if (progChroma[i] > maxChroma) maxChroma = progChroma[i]
        }
        for (i in 0 until 12) {
            lastNormalizedChroma[i] = (progChroma[i] / maxChroma).coerceIn(0f, 1f)
        }

        var sumChroma = 0f
        var totalBass = 1e-6f
        for (i in 0 until 12) {
            sumChroma += progChroma[i]
            totalBass += bassAccum[i]
        }
        val meanChroma = sumChroma / 12f
        var varChroma = 0f
        for (v in progChroma) {
            val d = v - meanChroma
            varChroma += d * d
        }
        val stdChroma = sqrt(varChroma)

        data class Scored(val key: MusicalKey, val scale: MusicalScale, val corr: Float)
        val allScores = ArrayList<Scored>(24)
        var lockedKeyCorr = -1f
        if (stdChroma > 1e-6f) {
            for (k in 0 until 12) {
                var dotMaj = 0f
                var dotMin = 0f
                for (i in 0 until 12) {
                    val idx = (k + i) % 12
                    val cCentered = progChroma[idx] - meanChroma
                    dotMaj += cCentered * majorCentered[i]
                    dotMin += cCentered * minorCentered[i]
                }
                val mk = MusicalKey.fromIndex(k)
                val tonicBassBias = 0.045f * (bassAccum[k] / totalBass)
                val majScore = (dotMaj / (stdChroma * majorNorm)) + tonicBassBias
                val minScore = (dotMin / (stdChroma * minorNorm)) + tonicBassBias * 0.9f
                allScores.add(Scored(mk, MusicalScale.MAJOR, majScore))
                allScores.add(Scored(mk, MusicalScale.MINOR, minScore))
                if (hasLockedKey && mk == lockedKey) {
                    lockedKeyCorr = if (lockedScale == MusicalScale.MINOR) minScore else majScore
                }
            }
        } else {
            MusicalKey.entries.forEach { mk ->
                allScores.add(Scored(mk, MusicalScale.MAJOR, 0f))
                allScores.add(Scored(mk, MusicalScale.MINOR, 0f))
            }
            lockedKeyCorr = 0f
        }
        allScores.sortByDescending { it.corr }

        liveKey = allScores[0].key
        liveScale = allScores[0].scale

        val r1 = allScores[0].corr
        val r2 = allScores[1].corr
        val rawConf = ((r1 - 0.14f) / 0.72f).coerceIn(0f, 1f)
        val marginBoost = ((r1 - r2) * 2.4f).coerceIn(0f, 0.24f)
        lastConfidence = (rawConf * 0.82f + marginBoost * 0.75f).coerceIn(0f, 1f)
        lastTop = allScores.take(3).map {
            KeyCandidate(it.key, it.scale, (it.corr.coerceIn(0f, 1f) * 100f).roundToInt())
        }

        var activePitchClasses = 0
        val activeThresh = maxChroma * 0.14f
        for (i in 0 until 12) {
            if (progChroma[i] >= activeThresh) {
                activePitchClasses++
            }
        }

        val reqHold = lockHoldFrames.coerceIn(4, 60)
        if (!hasLockedKey) {
            val isMelodyMode = liveChordQuality == 2
            val minActivePcs = if (isMelodyMode && voicedFrames < 65) 4 else 3
            val minMargin = if (isMelodyMode && voicedFrames < 65) 0.095f else 0f
            if (voicedFrames >= 5 &&
                activePitchClasses >= minActivePcs &&
                r1 >= 0.28f &&
                (r1 - r2) >= minMargin
            ) {
                hasLockedKey = true
                lockedKey = liveKey
                lockedScale = liveScale
                pendingHoldCount = 0
            }
        } else if (liveKey != lockedKey || liveScale != lockedScale) {
            val corrGain = r1 - lockedKeyCorr
            val requiredGain = if (modulationFrames >= 5) 0.03f else 0.08f
            if (corrGain >= requiredGain) {
                if (liveKey == pendingKey && liveScale == pendingScale) {
                    pendingHoldCount++
                } else {
                    pendingKey = liveKey
                    pendingScale = liveScale
                    pendingHoldCount = 1
                }
                val effectiveHold = if (modulationFrames >= 5) (reqHold / 2).coerceAtLeast(5) else reqHold
                if (pendingHoldCount >= effectiveHold && lastConfidence >= 0.34f) {
                    lockedKey = pendingKey
                    lockedScale = pendingScale
                    pendingHoldCount = 0
                }
            } else {
                pendingHoldCount = (pendingHoldCount - 1).coerceAtLeast(0)
            }
        } else {
            pendingHoldCount = 0
        }

        return KeyAnalysisResult(
            hasLockedKey = hasLockedKey,
            lockedKey = lockedKey,
            lockedScale = lockedScale,
            liveKey = liveKey,
            liveScale = liveScale,
            confidence = lastConfidence,
            signalActive = true,
            dominantFreqHz = domFreq,
            dominantMidi = domMidi,
            liveChordLabel = formatChordLabel(liveChordRoot, liveChordQuality),
            chroma = lastNormalizedChroma.toList(),
            topCandidates = lastTop
        )
    }

    private fun isDiatonicStep(rel: Int, isMinor: Boolean): Boolean {
        return if (!isMinor) {
            rel == 0 || rel == 2 || rel == 4 || rel == 5 || rel == 7 || rel == 9 || rel == 11
        } else {
            rel == 0 || rel == 2 || rel == 3 || rel == 5 || rel == 7 || rel == 8 || rel == 10 || rel == 11
        }
    }

    private fun estimateLiveChord() {
        var sum = 0f
        var top1 = 0f
        var top2 = 0f
        var top1Idx = 0
        for (i in 0 until 12) {
            val v = fastChroma[i]
            sum += v
            if (v > top1) {
                top2 = top1
                top1 = v
                top1Idx = i
            } else if (v > top2) {
                top2 = v
            }
        }
        if (sum < 1e-4f) {
            liveChordQuality = -1
            return
        }
        if (top1 > top2 * 3.8f && top2 < sum * 0.13f) {
            liveChordRoot = top1Idx
            liveChordQuality = 2
            return
        }

        var bestScore = -1e9f
        var bestRoot = 0
        var bestQual = 0
        for (r in 0 until 12) {
            val root = fastChroma[r]
            val m3 = fastChroma[(r + 3) % 12]
            val maj3 = fastChroma[(r + 4) % 12]
            val p5 = fastChroma[(r + 7) % 12]
            val nonChordMaj = sum - (root + maj3 + p5)
            val nonChordMin = sum - (root + m3 + p5)

            val scoreMaj = root * 1.15f + maj3 * 0.95f + p5 * 0.80f - nonChordMaj * 0.35f
            val scoreMin = root * 1.15f + m3 * 0.95f + p5 * 0.80f - nonChordMin * 0.35f

            if (scoreMaj > bestScore) {
                bestScore = scoreMaj
                bestRoot = r
                bestQual = 0
            }
            if (scoreMin > bestScore) {
                bestScore = scoreMin
                bestRoot = r
                bestQual = 1
            }
        }
        liveChordRoot = bestRoot
        liveChordQuality = bestQual
    }

    private fun computeFft() {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = fftRe[i]
                val ti = fftIm[i]
                fftRe[i] = fftRe[j]
                fftIm[i] = fftIm[j]
                fftRe[j] = tr
                fftIm[j] = ti
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val angle = -2.0 * PI / len
            val wLenCos = cos(angle).toFloat()
            val wLenSin = sin(angle).toFloat()
            val half = len ushr 1
            var start = 0
            while (start < FFT_SIZE) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half
                    val oddRe = fftRe[odd]
                    val oddIm = fftIm[odd]
                    val tRe = wRe * oddRe - wIm * oddIm
                    val tIm = wRe * oddIm + wIm * oddRe
                    fftRe[odd] = fftRe[even] - tRe
                    fftIm[odd] = fftIm[even] - tIm
                    fftRe[even] += tRe
                    fftIm[even] += tIm
                    val nextRe = wRe * wLenCos - wIm * wLenSin
                    wIm = wRe * wLenSin + wIm * wLenCos
                    wRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val FFT_SIZE = 4096
        const val DEFAULT_WS_HARMONY_TEMPLATE =
            "ch 1 harmony {key} 0 0 0 100 2 100 3 80 1000 100 0 0 500 1 1"

        private val MAJOR_PROFILE = floatArrayOf(
            6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
            2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
        )

        private val MINOR_PROFILE = floatArrayOf(
            6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
            2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
        )

        fun formatChordLabel(rootIndex: Int, qualityCode: Int): String {
            if (qualityCode < 0) return "--"
            val root = MusicalKey.fromIndex(rootIndex).displayName
            return when (qualityCode) {
                0 -> root
                1 -> "${root}m"
                else -> "$root (Note)"
            }
        }

        fun mapToOutputKey(
            detectedKey: MusicalKey,
            detectedScale: MusicalScale,
            mapping: MinorKeyMapping
        ): MusicalKey {
            if (detectedScale == MusicalScale.MAJOR) return detectedKey
            return when (mapping) {
                MinorKeyMapping.RELATIVE_MAJOR -> MusicalKey.fromIndex(detectedKey.semitoneOffset + 3)
                MinorKeyMapping.DIRECT_ROOT -> detectedKey
            }
        }

        fun formatWsHarmonyCommand(
            template: String,
            outputKey: MusicalKey,
            detectedScale: MusicalScale
        ): String {
            val base = template.ifBlank { DEFAULT_WS_HARMONY_TEMPLATE }
            val indexStr = outputKey.semitoneOffset.toString()
            return if (base.contains("{key}", ignoreCase = true) ||
                base.contains("{keyName}", ignoreCase = true) ||
                base.contains("{scale}", ignoreCase = true)
            ) {
                base.replace("{key}", indexStr, ignoreCase = true)
                    .replace("{keyName}", outputKey.displayName, ignoreCase = true)
                    .replace("{scale}", detectedScale.displayName, ignoreCase = true)
            } else {
                "$base $indexStr"
            }
        }

        /**
         * Scans an imported clip (even multi-minute) quickly by sampling windows
         * across the song to determine the overall tonic key immediately upon import.
         */
        fun analyzeFullClip(
            monoSamples: FloatArray,
            sampleRate: Int,
            referenceA4Hz: Float = 440f
        ): KeyAnalysisResult {
            if (monoSamples.isEmpty() || sampleRate < 8000) return KeyAnalysisResult()
            val decim = (sampleRate / 12000f).roundToInt().coerceIn(1, 8)
            val decimSr = sampleRate.toFloat() / decim.toFloat()
            val decimated = FloatArray(monoSamples.size / decim)
            for (i in decimated.indices) {
                decimated[i] = monoSamples[i * decim]
            }
            if (decimated.size < FFT_SIZE) {
                val padded = FloatArray(FFT_SIZE)
                System.arraycopy(decimated, 0, padded, 0, decimated.size)
                return KeyDetector().analyzeDecimatedFrame(padded, decimSr, noiseGateDb = -70f, referenceA4Hz = referenceA4Hz)
            }

            val detector = KeyDetector()
            val window = FloatArray(FFT_SIZE)
            val maxWindows = 40
            val totalSpan = decimated.size - FFT_SIZE
            val step = (totalSpan / maxWindows).coerceAtLeast(FFT_SIZE / 4)

            var pos = 0
            var lastRes = KeyAnalysisResult()
            while (pos <= totalSpan) {
                System.arraycopy(decimated, pos, window, 0, FFT_SIZE)
                lastRes = detector.analyzeDecimatedFrame(
                    decimated4096 = window,
                    decimatedSampleRate = decimSr,
                    noiseGateDb = -66f,
                    decayFactor = 0.94f,
                    lockHoldFrames = 6,
                    referenceA4Hz = referenceA4Hz
                )
                pos += step
            }
            return lastRes
        }
    }
}
