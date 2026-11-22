package com.example

import com.example.audio.AudioDeviceHelper
import com.example.audio.KeyDetector
import com.example.audio.MusicTheory
import com.example.audio.PitchDetector
import com.example.audio.PitchShifter
import com.example.audio.VocalDspEngine
import com.example.audio.VocalEffectsProcessor
import com.example.model.EchoMode
import com.example.model.HarmonyType
import com.example.model.KeyDetectionSpeed
import com.example.model.MinorKeyMapping
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.UsbInputChannel
import com.example.ws.StageWebSocketClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VocalDspAndTheoryTest {

    @Test
    fun diatonicHarmony_cMajorThirdAbove_resolvesMajorAndMinorThirdsCorrectly() {
        val c4Res = MusicTheory.solveHarmony(
            detectedFreqHz = 261.63f,
            key = MusicalKey.C,
            scale = MusicalScale.MAJOR,
            harmonyType = HarmonyType.HIGH_3RD,
            variation = false,
            autoHarmonist = true
        )
        assertEquals("C4", c4Res.detectedNoteName)
        assertEquals(4, c4Res.voice1Semitones)
        assertEquals("E4", c4Res.harmonyNoteNames)

        val d4Res = MusicTheory.solveHarmony(
            detectedFreqHz = 293.66f,
            key = MusicalKey.C,
            scale = MusicalScale.MAJOR,
            harmonyType = HarmonyType.HIGH_3RD,
            variation = false,
            autoHarmonist = true
        )
        assertEquals("D4", d4Res.detectedNoteName)
        assertEquals(3, d4Res.voice1Semitones)
        assertEquals("F4", d4Res.harmonyNoteNames)
    }

    @Test
    fun pitchDetector_tracks440HzSineWaveAccurately() {
        val sampleRate = 48000
        val ring = FloatArray(2048)
        for (i in ring.indices) {
            ring[i] = 0.45f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
        }
        val detector = PitchDetector(windowSize = 1024)
        val detected = detector.detectPitchHz(ringBuffer = ring, writePos = 2048, sampleRate = sampleRate)
        assertTrue("Expected ~440 Hz, got $detected", abs(detected - 440f) < 4f)
    }

    @Test
    fun tdPsolaPitchShifter_shiftsPitchSmoothlyWithoutWarble() {
        val sampleRate = 48000
        val shifter = PitchShifter()
        val ratio = MusicTheory.semitonesToRatio(4f) // Major 3rd up from 220 Hz -> 277.18 Hz
        val totalSamples = 8192
        val output = FloatArray(totalSamples)

        for (i in 0 until totalSamples) {
            // Harmonic vocal-like signal at 220 Hz (A3)
            val phase = 2.0 * PI * 220.0 * i / sampleRate
            val input = (0.35 * sin(phase) + 0.18 * sin(2 * phase) + 0.09 * sin(3 * phase)).toFloat()
            val out = shifter.processSample(
                input = input,
                targetRatio = ratio,
                detectedFreqHz = 220f,
                sampleRate = sampleRate
            )
            assertTrue("Output must be finite", out.isFinite())
            output[i] = out
        }

        // Check steady-state output pitch accuracy (last 2048 samples)
        val ring = FloatArray(2048)
        System.arraycopy(output, totalSamples - 2048, ring, 0, 2048)
        val detector = PitchDetector(windowSize = 1024)
        val outFreq = detector.detectPitchHz(ringBuffer = ring, writePos = 2048, sampleRate = sampleRate)
        assertTrue("Expected shifted pitch ~277.2 Hz, got $outFreq", abs(outFreq - 277.18f) < 8f)

        // Check RMS amplitude stability across 512-sample chunks in steady state (no warble/tremolo)
        val chunkRms = mutableListOf<Float>()
        var idx = 3072
        while (idx + 512 <= totalSamples) {
            var sumSq = 0f
            for (j in idx until idx + 512) {
                sumSq += output[j] * output[j]
            }
            chunkRms.add(sqrt(sumSq / 512f))
            idx += 512
        }
        val meanRms = chunkRms.average().toFloat()
        val maxDev = chunkRms.maxOf { abs(it - meanRms) }
        assertTrue("Expected audible steady RMS > 0.15, got $meanRms", meanRms > 0.15f)
        assertTrue("Expected low amplitude ripple (maxDev/mean < 0.12), got ${maxDev / meanRms}", (maxDev / meanRms) < 0.12f)
    }

    @Test
    fun effectsProcessor_enhancerAndReverbStayBounded() {
        val fx = VocalEffectsProcessor(48000)
        var maxOut = 0f
        for (i in 0 until 4096) {
            val raw = 0.6f * sin(2.0 * PI * 330.0 * i / 48000.0).toFloat()
            val clean = fx.processInputChannel(raw, micGainLinear = 1.5f, noiseGateDb = -60f, enhanceEnabled = true)
            val echoed = fx.processEcho(clean, echoMode = EchoMode.REV_DLY, echoMix = 0.45f)
            val limited = fx.softLimit(echoed)
            assertTrue(limited.isFinite())
            if (abs(limited) > maxOut) maxOut = abs(limited)
        }
        assertTrue("Limiter keeps output <= 1.0f", maxOut <= 1.0f)
    }

    @Test
    fun classicDualTapModeAndLiveToggle_stayFiniteAndShiftPitch() {
        val sampleRate = 48000
        val shifter = PitchShifter()
        val ratio = MusicTheory.semitonesToRatio(4f)
        val totalSamples = 8192
        val output = FloatArray(totalSamples)

        for (i in 0 until totalSamples) {
            val phase = 2.0 * PI * 220.0 * i / sampleRate
            val input = (0.35 * sin(phase) + 0.15 * sin(2 * phase)).toFloat()
            val out = shifter.processSample(
                input = input,
                targetRatio = ratio,
                detectedFreqHz = 220f,
                sampleRate = sampleRate,
                tdPsolaEnabled = false,
                pitchSmoothing = 0.25f
            )
            assertTrue("Classic Dual-Tap output must be finite", out.isFinite())
            assertTrue("Classic Dual-Tap output must remain bounded", abs(out) <= 1.0f)
            output[i] = out
        }

        val ring = FloatArray(2048)
        System.arraycopy(output, totalSamples - 2048, ring, 0, 2048)
        val detector = PitchDetector(windowSize = 1024)
        val outFreq = detector.detectPitchHz(ringBuffer = ring, writePos = 2048, sampleRate = sampleRate)
        assertTrue("Expected classic shifter pitch ~277.2 Hz, got $outFreq", abs(outFreq - 277.18f) < 12f)

        // Toggle back to TD-PSOLA with custom parameters mid-stream
        val resumed = shifter.processSample(
            input = 0.25f,
            targetRatio = ratio,
            detectedFreqHz = 220f,
            sampleRate = sampleRate,
            tdPsolaEnabled = true,
            pitchSmoothing = 0.78f,
            grainOverlap = 1.32f,
            peakSearchRatio = 0.35f,
            unvoicedDamping = 0.22f
        )
        assertTrue("Mode-switch sample must be finite", resumed.isFinite())
    }

    @Test
    fun noteHysteresisThreshold_controlsVibratoNoteLocking() {
        // Slightly above E4 (+0.55 semitone toward F4 in C Major) from previousMidi = 64 (E4)
        val sharpE4Hz = 329.63f * MusicTheory.semitonesToRatio(0.55f)

        val locked = MusicTheory.solveHarmony(
            detectedFreqHz = sharpE4Hz,
            key = MusicalKey.C,
            scale = MusicalScale.MAJOR,
            harmonyType = HarmonyType.HIGH_3RD,
            variation = false,
            autoHarmonist = true,
            previousMidi = 64,
            hysteresisThreshold = 0.70f
        )
        assertEquals("Should remain locked to E4 when hysteresis is 0.70 st", 64, locked.detectedMidi)

        val unlocked = MusicTheory.solveHarmony(
            detectedFreqHz = sharpE4Hz,
            key = MusicalKey.C,
            scale = MusicalScale.MAJOR,
            harmonyType = HarmonyType.HIGH_3RD,
            variation = false,
            autoHarmonist = true,
            previousMidi = 64,
            hysteresisThreshold = 0.30f
        )
        assertEquals("Should advance to F4 when hysteresis is 0.30 st", 65, unlocked.detectedMidi)
    }

    @Test
    fun pitchDetector_rejectsSingleFrameOctaveGlitch() {
        val sampleRate = 48000
        val detector = PitchDetector(windowSize = 1024)
        val ring220 = FloatArray(2048) { i ->
            0.40f * sin(2.0 * PI * 220.0 * i / sampleRate).toFloat()
        }
        val ring440 = FloatArray(2048) { i ->
            0.40f * sin(2.0 * PI * 440.0 * i / sampleRate).toFloat()
        }

        // Track steady 220 Hz first
        val basePitch = detector.detectPitchHz(ring220, writePos = 2048, sampleRate = sampleRate)
        assertTrue("Expected ~220 Hz, got $basePitch", abs(basePitch - 220f) < 4f)

        // Single octave-glitch frame at 440 Hz must NOT drag smoothed pitch up
        val afterSingleGlitch = detector.detectPitchHz(ring440, writePos = 2048, sampleRate = sampleRate)
        assertTrue(
            "Single-frame octave glitch must be rejected (stay near 220 Hz), got $afterSingleGlitch",
            abs(afterSingleGlitch - 220f) < 5f
        )
    }

    @Test
    fun tdPsola_handlesBriefConsonantGapWithoutPeriodWarp() {
        val sampleRate = 48000
        val shifter = PitchShifter()
        val ratio = MusicTheory.semitonesToRatio(4f) // +4 st -> ~277.18 Hz
        val output = FloatArray(12288)
        var maxPeak = 0f

        for (i in output.indices) {
            val inGap = i in 5120 until 7168
            val phase = 2.0 * PI * 220.0 * i / sampleRate
            val input = if (inGap) {
                0.02f * sin(2.0 * PI * 3200.0 * i / sampleRate).toFloat() // Quiet sibilant burst
            } else {
                (0.35 * sin(phase) + 0.18 * sin(2 * phase)).toFloat()
            }
            val out = shifter.processSample(
                input = input,
                targetRatio = ratio,
                detectedFreqHz = if (inGap) 0f else 220f,
                sampleRate = sampleRate,
                tdPsolaEnabled = true,
                isVoicedFrame = !inGap
            )
            assertTrue("Output must stay finite across unvoiced gap", out.isFinite())
            if (abs(out) > maxPeak) maxPeak = abs(out)
            output[i] = out
        }

        assertTrue("Output peak across gap/resume should stay bounded (< 0.85f), got $maxPeak", maxPeak < 0.85f)

        val ring = FloatArray(2048)
        System.arraycopy(output, output.size - 2048, ring, 0, 2048)
        val detector = PitchDetector(windowSize = 1024)
        val resumedFreq = detector.detectPitchHz(ring, writePos = 2048, sampleRate = sampleRate)
        assertTrue("Expected clean resume at ~277.2 Hz, got $resumedFreq", abs(resumedFreq - 277.18f) < 8f)
    }

    @Test
    fun usbSampleRateNegotiation_picksCommonSupportedRate() {
        // PreSonus AudioBox iTwo typical rates
        val uac2Rates = listOf(44100, 48000, 88200, 96000)
        assertEquals(
            48000,
            AudioDeviceHelper.chooseBestSampleRate(
                requestedHz = null,
                nativeHz = 48000,
                inputSupportedRates = uac2Rates,
                outputSupportedRates = uac2Rates
            )
        )
        // Interface that only accepts 44.1 kHz
        assertEquals(
            44100,
            AudioDeviceHelper.chooseBestSampleRate(
                requestedHz = null,
                nativeHz = 48000,
                inputSupportedRates = listOf(44100),
                outputSupportedRates = listOf(44100, 48000)
            )
        )
        // Explicit user 96k request honored when both endpoints support it
        assertEquals(
            96000,
            AudioDeviceHelper.chooseBestSampleRate(
                requestedHz = 96000,
                nativeHz = 48000,
                inputSupportedRates = uac2Rates,
                outputSupportedRates = uac2Rates
            )
        )
    }

    @Test
    fun usbStereoInputExtraction_routesChannelsOneTwoAndMixCleanly() {
        // Interleaved stereo: frame 0 -> (L=+0.5, R=-0.25), frame 1 -> (L=0.0, R=+0.75)
        val stereoFloat = floatArrayOf(0.5f, -0.25f, 0.0f, 0.75f)
        val outMono = FloatArray(2)

        VocalDspEngine.extractMonoFromFloat(stereoFloat, 2, 2, UsbInputChannel.CH_1_LEFT, outMono)
        assertEquals(0.5f, outMono[0], 1e-4f)
        assertEquals(0.0f, outMono[1], 1e-4f)

        VocalDspEngine.extractMonoFromFloat(stereoFloat, 2, 2, UsbInputChannel.CH_2_RIGHT, outMono)
        assertEquals(-0.25f, outMono[0], 1e-4f)
        assertEquals(0.75f, outMono[1], 1e-4f)

        VocalDspEngine.extractMonoFromFloat(stereoFloat, 2, 2, UsbInputChannel.MIX_1_2, outMono)
        assertEquals(0.25f, outMono[0], 1e-4f)
        assertEquals(0.75f, outMono[1], 1e-4f)

        // Short PCM path preserves single active channel without -6dB attenuation
        val stereoShort = shortArrayOf(16384, 0, 0, 16384)
        VocalDspEngine.extractMonoFromShort(stereoShort, 2, 2, UsbInputChannel.MIX_1_2, outMono = outMono)
        assertEquals(0.5f, outMono[0], 1e-3f)
        assertEquals(0.5f, outMono[1], 1e-3f)
    }

    @Test
    fun keyDetector_identifiesMajorAndMinorChordsAndFullClipsAccurately() {
        val sampleRate = 48000

        // Synthesize 2 seconds of C Major triad (C4=261.63, E4=329.63, G4=392.00 + C3 bass=130.81)
        val cMajSamples = FloatArray(sampleRate * 2) { i ->
            val t = i.toDouble() / sampleRate
            (0.28 * sin(2.0 * PI * 130.81 * t) +
                0.30 * sin(2.0 * PI * 261.63 * t) +
                0.24 * sin(2.0 * PI * 329.63 * t) +
                0.22 * sin(2.0 * PI * 392.00 * t)).toFloat()
        }
        val cRes = KeyDetector.analyzeFullClip(cMajSamples, sampleRate, referenceA4Hz = 440f)
        assertEquals(MusicalKey.C, cRes.liveKey)
        assertEquals(MusicalScale.MAJOR, cRes.liveScale)
        assertTrue("Expected positive key confidence, got ${cRes.confidence}", cRes.confidence > 0.35f)

        // Full-clip offline scan on F# Major triad (F#3=185.00, A#3=233.08, C#4=277.18, F#4=369.99)
        val fSharpClip = FloatArray(sampleRate * 3) { i ->
            val t = i.toDouble() / sampleRate
            (0.28 * sin(2.0 * PI * 185.00 * t) +
                0.24 * sin(2.0 * PI * 233.08 * t) +
                0.24 * sin(2.0 * PI * 277.18 * t) +
                0.22 * sin(2.0 * PI * 369.99 * t)).toFloat()
        }
        val fSharpScan = KeyDetector.analyzeFullClip(fSharpClip, sampleRate, referenceA4Hz = 440f)
        assertEquals(MusicalKey.F_SHARP, fSharpScan.liveKey)
        assertEquals(MusicalScale.MAJOR, fSharpScan.liveScale)

        // Fresh detector on A Minor triad (A2=110.00, A3=220.00, C4=261.63, E4=329.63)
        val aMinClip = FloatArray(sampleRate * 3) { i ->
            val t = i.toDouble() / sampleRate
            (0.30 * sin(2.0 * PI * 110.00 * t) +
                0.28 * sin(2.0 * PI * 220.00 * t) +
                0.26 * sin(2.0 * PI * 261.63 * t) +
                0.24 * sin(2.0 * PI * 329.63 * t)).toFloat()
        }
        val aMinScan = KeyDetector.analyzeFullClip(aMinClip, sampleRate, referenceA4Hz = 440f)
        assertEquals(MusicalKey.A, aMinScan.liveKey)
        assertEquals(MusicalScale.MINOR, aMinScan.liveScale)
    }

    @Test
    fun wsHarmonyCommand_formatsExactKeyIndicesZeroToElevenAndMinorMapping() {
        // C -> 0
        val cmdC = KeyDetector.formatWsHarmonyCommand(
            template = KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE,
            outputKey = MusicalKey.C,
            detectedScale = MusicalScale.MAJOR
        )
        assertEquals("ch 1 harmony 0 0 0 0 100 2 100 3 80 1000 100 0 0 500 1 1", cmdC)

        // C# -> 1, B -> 11
        val cmdCs = KeyDetector.formatWsHarmonyCommand(
            template = KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE,
            outputKey = MusicalKey.C_SHARP,
            detectedScale = MusicalScale.MAJOR
        )
        assertEquals("ch 1 harmony 1 0 0 0 100 2 100 3 80 1000 100 0 0 500 1 1", cmdCs)

        val cmdB = KeyDetector.formatWsHarmonyCommand(
            template = KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE,
            outputKey = MusicalKey.B,
            detectedScale = MusicalScale.MAJOR
        )
        assertEquals("ch 1 harmony 11 0 0 0 100 2 100 3 80 1000 100 0 0 500 1 1", cmdB)

        // Minor mapping modes: Am -> Relative Major (C=0) vs Direct Root (A=9)
        assertEquals(
            MusicalKey.C,
            KeyDetector.mapToOutputKey(MusicalKey.A, MusicalScale.MINOR, MinorKeyMapping.RELATIVE_MAJOR)
        )
        assertEquals(
            MusicalKey.A,
            KeyDetector.mapToOutputKey(MusicalKey.A, MusicalScale.MINOR, MinorKeyMapping.DIRECT_ROOT)
        )
    }

    @Test
    fun wsClientUrlNormalization_preservesPathAndBuildsValidOkHttpRequest() {
        val expected = "ws://192.168.1.230:8080/ws"
        assertEquals(expected, StageWebSocketClient.DEFAULT_WS_CLIENT_URL)
        assertEquals(expected, StageWebSocketClient.normalizeWsUrl("ws://192.168.1.230:8080/ws"))
        assertEquals(expected, StageWebSocketClient.normalizeWsUrl("192.168.1.230:8080/ws"))
        assertEquals(expected, StageWebSocketClient.normalizeWsUrl("http://192.168.1.230:8080/ws"))
        assertEquals("wss://192.168.1.230:8443/ws", StageWebSocketClient.normalizeWsUrl("https://192.168.1.230:8443/ws"))

        val req = Request.Builder().url(StageWebSocketClient.normalizeWsUrl(expected)).build()
        assertEquals("192.168.1.230", req.url.host)
        assertEquals(8080, req.url.port)
        assertEquals("/ws", req.url.encodedPath)
    }

    @Test
    fun guitarChordProgression_staysLockedAcrossChordsAndSwitchesOnModulation() {
        val decimSr = 12000f
        val detector = KeyDetector()
        val window = FloatArray(KeyDetector.FFT_SIZE)

        fun fillChordWindow(freqs: DoubleArray) {
            for (i in 0 until KeyDetector.FFT_SIZE) {
                val t = i.toDouble() / decimSr
                var sum = 0.0
                for ((idx, f) in freqs.withIndex()) {
                    val w = if (idx == 0) 0.36 else 0.24
                    sum += w * sin(2.0 * PI * f * t) + 0.09 * sin(2.0 * PI * (f * 2.0) * t)
                }
                window[i] = sum.toFloat()
            }
        }

        // Simulate 4-bar guitar progression in G Major: G -> D -> Em -> C (approx 1.2s per chord = 28 hops)
        val gMajChords = listOf(
            doubleArrayOf(98.00, 196.00, 246.94, 293.66),  // G
            doubleArrayOf(146.83, 293.66, 369.99, 440.00), // D
            doubleArrayOf(82.41, 164.81, 196.00, 246.94),  // Em
            doubleArrayOf(130.81, 261.63, 329.63, 392.00)  // C
        )
        var lastResult = detector.analyzeDecimatedFrame(window, decimSr)
        for (chord in gMajChords) {
            fillChordWindow(chord)
            repeat(28) {
                lastResult = detector.analyzeDecimatedFrame(
                    decimated4096 = window,
                    decimatedSampleRate = decimSr,
                    decayFactor = KeyDetectionSpeed.BALANCED.decay,
                    lockHoldFrames = KeyDetectionSpeed.BALANCED.holdFrames
                )
            }
        }
        assertTrue("Expected locked key for G-D-Em-C progression", lastResult.hasLockedKey)
        assertEquals(MusicalKey.G, lastResult.lockedKey)
        assertEquals(MusicalScale.MAJOR, lastResult.lockedScale)

        // Verify live chord tracker recognized final C chord cleanly
        assertEquals("C", lastResult.liveChordLabel)

        // Now modulate to a completely new song in D Major: D -> A -> Bm -> G
        val dMajChords = listOf(
            doubleArrayOf(146.83, 293.66, 369.99, 440.00), // D
            doubleArrayOf(110.00, 220.00, 277.18, 329.63), // A
            doubleArrayOf(123.47, 246.94, 293.66, 369.99), // Bm
            doubleArrayOf(98.00, 196.00, 246.94, 293.66)   // G
        )
        for (chord in dMajChords) {
            fillChordWindow(chord)
            repeat(28) {
                lastResult = detector.analyzeDecimatedFrame(
                    decimated4096 = window,
                    decimatedSampleRate = decimSr,
                    decayFactor = KeyDetectionSpeed.FAST.decay,
                    lockHoldFrames = KeyDetectionSpeed.FAST.holdFrames
                )
            }
        }
        assertEquals(MusicalKey.D, lastResult.lockedKey)
        assertEquals(MusicalScale.MAJOR, lastResult.lockedScale)
    }

    @Test
    fun guitarLeadMelody_resolvesKeyFromSingleNotePhrase() {
        val decimSr = 12000f
        val detector = KeyDetector()
        val window = FloatArray(KeyDetector.FFT_SIZE)

        // Play a melodic phrase strictly in C Major (E4 -> G4 -> A4 -> G4 -> F4 -> D4 -> C4)
        val melodyHz = doubleArrayOf(329.63, 392.00, 440.00, 392.00, 349.23, 293.66, 261.63)
        var lastResult = detector.analyzeDecimatedFrame(window, decimSr)
        for (freq in melodyHz) {
            for (i in 0 until KeyDetector.FFT_SIZE) {
                val t = i.toDouble() / decimSr
                window[i] = (0.42 * sin(2.0 * PI * freq * t) + 0.14 * sin(2.0 * PI * (freq * 2.0) * t)).toFloat()
            }
            repeat(10) {
                lastResult = detector.analyzeDecimatedFrame(
                    decimated4096 = window,
                    decimatedSampleRate = decimSr,
                    decayFactor = KeyDetectionSpeed.BALANCED.decay,
                    lockHoldFrames = KeyDetectionSpeed.BALANCED.holdFrames
                )
            }
        }
        assertTrue("Expected key lock from melody phrase", lastResult.hasLockedKey)
        assertEquals(MusicalKey.C, lastResult.lockedKey)
        assertEquals(MusicalScale.MAJOR, lastResult.lockedScale)
    }
}
