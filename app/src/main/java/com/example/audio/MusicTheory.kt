package com.example.audio

import com.example.model.HarmonyType
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

data class HarmonyResolution(
    val detectedMidi: Int,
    val detectedNoteName: String,
    val voice1Semitones: Int,
    val voice2Semitones: Int?,
    val voice1Gain: Float,
    val voice2Gain: Float,
    val voice1DetuneCents: Float = 0f,
    val voice2DetuneCents: Float = 0f,
    val harmonyNoteNames: String
)

object MusicTheory {
    private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun freqToMidiFloat(freqHz: Float): Float {
        if (freqHz <= 20f) return 0f
        return 69f + 12f * (ln(freqHz / 440f) / ln(2f))
    }

    fun midiToNoteName(midi: Int): String {
        if (midi <= 0) return "--"
        val note = NOTE_NAMES[((midi % 12) + 12) % 12]
        val octave = (midi / 12) - 1
        return "$note$octave"
    }

    fun semitonesToRatio(semitones: Float): Float {
        return 2.0.pow(semitones / 12.0).toFloat()
    }

    /**
     * Given a sung frequency and target key/scale, finds the nearest scale degree index (0..6)
     * with optional previousMidi hysteresis to prevent vibrato from chattering between semitones.
     */
    fun solveHarmony(
        detectedFreqHz: Float,
        key: MusicalKey,
        scale: MusicalScale,
        harmonyType: HarmonyType,
        variation: Boolean,
        autoHarmonist: Boolean,
        previousMidi: Int = 0,
        hysteresisThreshold: Float = 0.65f
    ): HarmonyResolution {
        val lockWindow = hysteresisThreshold.coerceIn(0.05f, 0.95f)
        val rawMidi = if (detectedFreqHz > 40f) {
            val midiFloat = freqToMidiFloat(detectedFreqHz)
            if (previousMidi > 0 && abs(midiFloat - previousMidi) < lockWindow) {
                previousMidi
            } else {
                midiFloat.roundToInt()
            }
        } else {
            if (previousMidi > 0) previousMidi else 60
        }

        val rootOffset = key.semitoneOffset
        val intervals = scale.intervals

        val pitchClass = ((rawMidi - rootOffset) % 12 + 12) % 12
        var bestDegreeIdx = 0
        var minDist = 12
        for (i in intervals.indices) {
            val d = abs(intervals[i] - pitchClass)
            val wrapped = minOf(d, 12 - d)
            if (wrapped < minDist) {
                minDist = wrapped
                bestDegreeIdx = i
            }
        }

        val quantizedMidi = if (autoHarmonist && detectedFreqHz > 40f) {
            val octaveBase = rawMidi - pitchClass
            octaveBase + intervals[bestDegreeIdx]
        } else {
            rawMidi
        }

        fun diatonicInterval(steps: Int): Int {
            val total = bestDegreeIdx + steps
            val octaveShift = Math.floorDiv(total, 7)
            val deg = Math.floorMod(total, 7)
            return (octaveShift * 12 + intervals[deg]) - intervals[bestDegreeIdx]
        }

        val v1Semi: Int
        var v2Semi: Int? = null
        var v1Gain = 0.85f
        var v2Gain = 0f
        var v1Detune = 0f
        var v2Detune = 0f

        when (harmonyType) {
            HarmonyType.OCT_DOWN -> {
                v1Semi = -12
                if (variation) {
                    v2Semi = diatonicInterval(-2)
                    v2Gain = 0.65f
                }
            }
            HarmonyType.LOWER_5TH -> {
                v1Semi = diatonicInterval(-3)
                if (variation) {
                    v2Semi = diatonicInterval(-2)
                    v2Gain = 0.65f
                }
            }
            HarmonyType.LOW_3RD -> {
                v1Semi = diatonicInterval(-2)
                if (variation) {
                    v2Semi = diatonicInterval(-4)
                    v2Gain = 0.6f
                }
            }
            HarmonyType.UNISON -> {
                v1Semi = 0
                v1Detune = if (variation) 8f else 4f
                v1Gain = 0.72f
                v2Semi = 0
                v2Detune = if (variation) -8f else -4f
                v2Gain = 0.72f
            }
            HarmonyType.HIGH_3RD -> {
                v1Semi = diatonicInterval(+2)
                if (variation) {
                    v2Semi = diatonicInterval(+4)
                    v2Gain = 0.62f
                }
            }
            HarmonyType.HIGHER_5TH -> {
                v1Semi = diatonicInterval(+4)
                if (variation) {
                    v2Semi = diatonicInterval(+2)
                    v2Gain = 0.65f
                }
            }
            HarmonyType.HIGH_AND_LOW -> {
                v1Semi = diatonicInterval(+2)
                v2Semi = diatonicInterval(-2)
                v1Gain = 0.75f
                v2Gain = 0.75f
                if (variation) {
                    v1Detune = 4f
                    v2Detune = -4f
                }
            }
            HarmonyType.THIRD_AND_FIFTH -> {
                v1Semi = diatonicInterval(+2)
                v2Semi = diatonicInterval(+4)
                v1Gain = 0.78f
                v2Gain = 0.72f
                if (variation) {
                    v1Detune = 4f
                    v2Detune = -4f
                }
            }
            HarmonyType.OCT_UP -> {
                v1Semi = 12
                if (variation) {
                    v2Semi = diatonicInterval(+2)
                    v2Gain = 0.65f
                }
            }
        }

        val leadName = if (detectedFreqHz > 40f) midiToNoteName(quantizedMidi) else "--"
        val harmNames = if (detectedFreqHz > 40f) {
            buildString {
                append(midiToNoteName(quantizedMidi + v1Semi))
                if (v2Semi != null && v2Gain > 0.05f) {
                    append(" + ")
                    append(midiToNoteName(quantizedMidi + v2Semi))
                }
            }
        } else {
            "--"
        }

        return HarmonyResolution(
            detectedMidi = if (detectedFreqHz > 40f) quantizedMidi else 0,
            detectedNoteName = leadName,
            voice1Semitones = v1Semi,
            voice2Semitones = v2Semi,
            voice1Gain = v1Gain,
            voice2Gain = v2Gain,
            voice1DetuneCents = v1Detune,
            voice2DetuneCents = v2Detune,
            harmonyNoteNames = harmNames
        )
    }
}
