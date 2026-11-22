package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.example.model.AudioBitDepth
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.BufferFramesOption
import com.example.model.KeyCandidate
import com.example.model.LatencyProfile
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.PedalState
import com.example.model.SampleRateOption
import com.example.model.UsbInputChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real-time low-latency full-duplex vocal harmonizer DSP engine.
 * Supports:
 * - Configurable Sample Rate (Auto / 44.1k / 48k / 96k), Bit Depth (16-bit PCM / 32-bit Float),
 *   and Buffer Frames (Auto / 64..1024)
 * - Seamless source switching between Live Mic/USB Audio Interface and Imported Test Audio File
 */
class VocalDspEngine(
    private val deviceHelper: AudioDeviceHelper
) {
    private val _telemetry = MutableStateFlow(AudioTelemetry())
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val running = AtomicBoolean(false)
    private val pedalStateRef = AtomicReference(PedalState())
    private val testClipRef = AtomicReference<DecodedAudioClip?>(null)
    private val testFilePlaying = AtomicBoolean(true)

    @Volatile
    private var testPlayHeadSamples = 0.0

    private var preferredInputDeviceId: Int? = null
    private var preferredOutputDeviceId: Int? = null
    private var sampleRateOption: SampleRateOption = SampleRateOption.AUTO_NATIVE
    private var bitDepth: AudioBitDepth = AudioBitDepth.PCM_16BIT
    private var bufferFramesOption: BufferFramesOption = BufferFramesOption.AUTO_NATIVE

    private var audioThread: Thread? = null
    private var peakClipCounter = 0

    @Volatile
    private var asyncKeyResult = KeyAnalysisResult()

    private val kotlinKeyResetPending = AtomicBoolean(false)
    private val chordHistoryLock = Any()
    private val recentChordsQueue = ArrayDeque<String>()
    private var pendingChordLabel = "--"
    private var pendingChordFrames = 0

    fun updatePedalState(state: PedalState) {
        val prev = pedalStateRef.getAndSet(state)
        NativeOboeBridge.pushParams(state, testFilePlaying.get())
        val needRestart = running.get() && (
            prev.latencyProfile != state.latencyProfile ||
                prev.audioSourceMode != state.audioSourceMode ||
                prev.forceLowLatencyPath != state.forceLowLatencyPath
            )
        if (needRestart) {
            restart()
        } else {
            _telemetry.value = _telemetry.value.copy(audioSourceMode = state.audioSourceMode)
        }
    }

    fun setAudioFormatConfig(
        sampleRateOption: SampleRateOption,
        bitDepth: AudioBitDepth,
        bufferFramesOption: BufferFramesOption
    ) {
        val changed = this.sampleRateOption != sampleRateOption ||
            this.bitDepth != bitDepth ||
            this.bufferFramesOption != bufferFramesOption
        this.sampleRateOption = sampleRateOption
        this.bitDepth = bitDepth
        this.bufferFramesOption = bufferFramesOption
        if (changed) {
            _telemetry.value = _telemetry.value.copy(bitDepthLabel = bitDepth.shortLabel)
            if (running.get()) restart()
        }
    }

    fun setPreferredDevices(inputId: Int?, outputId: Int?) {
        val changed = inputId != preferredInputDeviceId || outputId != preferredOutputDeviceId
        preferredInputDeviceId = inputId
        preferredOutputDeviceId = outputId
        if (changed && running.get()) {
            restart()
        }
    }

    fun loadTestAudioClip(clip: DecodedAudioClip) {
        testClipRef.set(clip)
        testPlayHeadSamples = 0.0
        testFilePlaying.set(true)
        NativeOboeBridge.nativeSetTestClip(clip.monoSamples, clip.sampleRate)
        _telemetry.value = _telemetry.value.copy(
            testFileName = clip.displayName,
            testFileDurationSec = clip.durationSec,
            testFileProgress = 0f,
            testFilePlaying = true
        )
    }

    fun setTestFilePlaying(playing: Boolean) {
        testFilePlaying.set(playing)
        NativeOboeBridge.pushParams(pedalStateRef.get(), playing)
        _telemetry.value = _telemetry.value.copy(testFilePlaying = playing)
    }

    fun seekTestFile(progress: Float) {
        val clip = testClipRef.get() ?: return
        val clamped = progress.coerceIn(0f, 0.999f)
        testPlayHeadSamples = clamped * clip.monoSamples.size.toDouble()
        NativeOboeBridge.nativeSeekTestFile(clamped)
        _telemetry.value = _telemetry.value.copy(testFileProgress = clamped)
    }

    fun resetPeakClips() {
        peakClipCounter = 0
        if (NativeOboeBridge.isAvailable()) {
            NativeOboeBridge.nativeResetPeakClips()
        }
        _telemetry.value = _telemetry.value.copy(peakClipCount = 0, isPeakClipping = false)
    }

    fun resetKeyDetection() {
        synchronized(chordHistoryLock) {
            recentChordsQueue.clear()
            pendingChordLabel = "--"
            pendingChordFrames = 0
        }
        kotlinKeyResetPending.set(true)
        if (NativeOboeBridge.isAvailable()) {
            NativeOboeBridge.nativeResetKeyDetection()
        }
        asyncKeyResult = KeyAnalysisResult()
        _telemetry.value = _telemetry.value.copy(
            hasLockedKey = false,
            keyConfidence = 0f,
            signalActive = false,
            liveChordLabel = "--",
            recentChords = emptyList(),
            chromaBins = List(12) { 0f },
            topKeyCandidates = emptyList()
        )
    }

    private fun recordLiveChord(chordLabel: String, signalActive: Boolean): List<String> {
        synchronized(chordHistoryLock) {
            if (!signalActive || chordLabel == "--") {
                return recentChordsQueue.toList()
            }
            if (chordLabel == pendingChordLabel) {
                pendingChordFrames++
            } else {
                pendingChordLabel = chordLabel
                pendingChordFrames = 1
            }
            if (pendingChordFrames >= 3) {
                val cleanShort = chordLabel.replace(" (Note)", "")
                if (recentChordsQueue.lastOrNull() != cleanShort) {
                    recentChordsQueue.addLast(cleanShort)
                    if (recentChordsQueue.size > 5) {
                        recentChordsQueue.removeFirst()
                    }
                }
            }
            return recentChordsQueue.toList()
        }
    }

    fun isRunning(): Boolean = running.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running.getAndSet(true)) return true

        val thread = Thread({
            if (NativeOboeBridge.isAvailable()) {
                runNativeOboeLoop()
            } else {
                runAudioLoop()
            }
        }, if (NativeOboeBridge.isAvailable()) "VocalStage-Oboe-Telemetry" else "VocalStage-DSP-Thread")
        thread.priority = if (NativeOboeBridge.isAvailable()) Thread.NORM_PRIORITY else Thread.MAX_PRIORITY
        audioThread = thread
        thread.start()
        return true
    }

    fun stop() {
        running.set(false)
        if (NativeOboeBridge.isAvailable()) {
            NativeOboeBridge.nativeStop()
        }
        try {
            audioThread?.join(700)
        } catch (_: InterruptedException) {
        }
        audioThread = null
        val clip = testClipRef.get()
        _telemetry.value = _telemetry.value.copy(
            isRunning = false,
            inputRmsDb = -60f,
            inputPeakDb = -60f,
            harmonyRmsDb = -60f,
            outputRmsDb = -60f,
            outputPeakDb = -60f,
            detectedFreqHz = 0f,
            detectedNoteName = "--",
            harmonyNoteNames = "--",
            signalActive = false,
            liveChordLabel = "--",
            audioSourceMode = pedalStateRef.get().audioSourceMode,
            testFileName = clip?.displayName,
            testFileDurationSec = clip?.durationSec ?: 0f,
            testFilePlaying = testFilePlaying.get(),
            statusMessage = "Standby — Tap START DSP to start"
        )
    }

    fun restart() {
        if (!running.get()) return
        stop()
        start()
    }

    private fun runNativeOboeLoop() {
        deviceHelper.refreshDevices()
        val inputDevice = deviceHelper.findInputDevice(preferredInputDeviceId)
        val outputDevice = deviceHelper.findOutputDevice(preferredOutputDeviceId)

        val currentState = pedalStateRef.get()
        val forceFast = currentState.forceLowLatencyPath
        val sourceMode = currentState.audioSourceMode
        val clip = testClipRef.get()
        val useTestFile = sourceMode == AudioSourceMode.TEST_FILE && clip != null

        val isUsbIn = !useTestFile && inputDevice?.let { AudioDeviceHelper.isUsbDeviceType(it.type) } == true
        val isUsbOut = outputDevice?.let { AudioDeviceHelper.isUsbDeviceType(it.type) } == true
        val isUsbActive = isUsbIn || isUsbOut
        val isBluetoothOut = outputDevice?.let { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true

        if (isUsbOut) {
            deviceHelper.clearUsbMixerOverrides(outputDevice)
        }

        val nativeSr = deviceHelper.getNativeSampleRate()
        val requestedSr = when {
            sampleRateOption.hz != null -> sampleRateOption.hz!!
            isUsbActive -> deviceHelper.negotiateSampleRate(
                requestedHz = null,
                nativeHz = nativeSr,
                inputDevice = inputDevice,
                outputDevice = outputDevice
            )
            else -> 0 // Let Oboe pick exact natural hardware rate (zero OS resampler!)
        }

        val preferFloat = bitDepth == AudioBitDepth.PCM_FLOAT_32BIT
        val requestedFrames = bufferFramesOption.frames ?: 0
        val preferStereoIn = !useTestFile && deviceHelper.prefersStereoInput(inputDevice)

        if (clip != null) {
            NativeOboeBridge.nativeSetTestClip(clip.monoSamples, clip.sampleRate)
        }
        NativeOboeBridge.pushParams(currentState, testFilePlaying.get())

        val started = NativeOboeBridge.nativeStart(
            requestedSampleRate = requestedSr,
            preferFloat = preferFloat,
            requestedFrames = requestedFrames,
            bufferMultiplier = currentState.latencyProfile.burstMultiplier,
            forceLowLatency = forceFast,
            inputDeviceId = inputDevice?.id ?: 0,
            outputDeviceId = outputDevice?.id ?: 0,
            useTestFile = useTestFile,
            isUsbIn = isUsbIn,
            isUsbOut = isUsbOut,
            preferStereoIn = preferStereoIn
        )

        if (!started) {
            // Graceful fallback to Java AudioTrack/AudioRecord if native stream failed to open
            runAudioLoop()
            return
        }

        val inDeviceLabel = if (useTestFile) {
            "File: ${clip.displayName}"
        } else {
            inputDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: if (isUsbIn) "USB Audio In" else "Device Microphone"
        }
        val outDeviceLabel = outputDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: if (isUsbOut) "USB Audio Out" else "Line / Speaker Out"
        val bitLabel = if (preferFloat) "32f-bit" else "16-bit"

        val floatTelemetry = FloatArray(160)
        val intTelemetry = IntArray(32)
        val waveInList = MutableList(64) { 0f }
        val waveOutList = MutableList(64) { 0f }
        val chromaList = MutableList(12) { 0f }

        while (running.get() && NativeOboeBridge.nativeIsRunning()) {
            NativeOboeBridge.nativeReadTelemetry(floatTelemetry, intTelemetry)

            val st = pedalStateRef.get()
            val sr = intTelemetry[1].coerceAtLeast(8000)
            val cbFrames = intTelemetry[2].coerceAtLeast(16)
            val burstFrames = intTelemetry[3].coerceAtLeast(16)
            val outBufFrames = intTelemetry[4]
            val outCapFrames = intTelemetry[5].coerceAtLeast(1)
            val inCapFrames = intTelemetry[6]
            val xruns = intTelemetry[7]
            val clipCount = intTelemetry[8]
            val isClip = intTelemetry[9] == 1
            val domMidi = intTelemetry[10]
            val lockedKey = MusicalKey.fromIndex(intTelemetry[11])
            val lockedScale = if (intTelemetry[12] == 1) MusicalScale.MINOR else MusicalScale.MAJOR
            val inChCount = intTelemetry[13].coerceAtLeast(1)
            val isAAudio = intTelemetry[14] == 1
            val isExclusive = intTelemetry[15] == 1
            val hasLockedKey = intTelemetry[16] == 1
            val liveKey = MusicalKey.fromIndex(intTelemetry[17])
            val liveScale = if (intTelemetry[18] == 1) MusicalScale.MINOR else MusicalScale.MAJOR
            val signalActive = intTelemetry[19] == 1
            val topCandidates = (0 until 3).map { idx ->
                val base = 20 + idx * 3
                KeyCandidate(
                    key = MusicalKey.fromIndex(intTelemetry[base]),
                    scale = if (intTelemetry[base + 1] == 1) MusicalScale.MINOR else MusicalScale.MAJOR,
                    scorePct = intTelemetry[base + 2].coerceIn(0, 100)
                )
            }

            val liveChordLabel = KeyDetector.formatChordLabel(intTelemetry[29], intTelemetry[30])
            val recentChordsList = recordLiveChord(liveChordLabel, signalActive)

            val domFreq = floatTelemetry[5]
            val inMs = floatTelemetry[6]
            val dspMs = floatTelemetry[7]
            val dspMaxMs = floatTelemetry[8]
            val outMs = floatTelemetry[9]
            val totalMs = floatTelemetry[10]
            val testProg = floatTelemetry[11]
            val keyConf = floatTelemetry[12].coerceIn(0f, 1f)

            for (i in 0 until 64) {
                waveInList[i] = floatTelemetry[18 + i]
                waveOutList[i] = floatTelemetry[82 + i]
            }
            for (i in 0 until 12) {
                chromaList[i] = floatTelemetry[146 + i].coerceIn(0f, 1f)
            }

            val domNoteName = if (domFreq > 40f && domMidi > 0) {
                MusicTheory.midiToNoteName(domMidi)
            } else {
                "--"
            }

            val apiTag = if (isAAudio) "AAudio" else "OpenSL"
            val shareTag = if (isExclusive) "EXCLUSIVE" else "SHARED"
            val isLowLatencyPath = isExclusive || outCapFrames <= 1024
            val pathLabel = when {
                useTestFile -> "Oboe $apiTag ($shareTag)"
                isExclusive -> "Oboe $apiTag MMAP EXCLUSIVE"
                isLowLatencyPath -> "Oboe $apiTag Fast ($shareTag)"
                isUsbActive -> "Oboe USB $apiTag ($shareTag ${outCapFrames}fr)"
                else -> "Oboe $apiTag ($shareTag ${outCapFrames}fr)"
            }

            _telemetry.value = AudioTelemetry(
                isRunning = true,
                inputRmsDb = floatTelemetry[0],
                inputPeakDb = floatTelemetry[1],
                isPeakClipping = isClip,
                peakClipCount = clipCount,
                harmonyRmsDb = floatTelemetry[2],
                outputRmsDb = floatTelemetry[3],
                outputPeakDb = floatTelemetry[4],
                detectedFreqHz = domFreq,
                detectedMidiNote = domMidi.toFloat(),
                detectedNoteName = domNoteName,
                hasLockedKey = hasLockedKey,
                detectedKey = lockedKey,
                detectedScale = lockedScale,
                liveCandidateKey = liveKey,
                liveCandidateScale = liveScale,
                keyConfidence = keyConf,
                signalActive = signalActive,
                liveChordLabel = liveChordLabel,
                recentChords = recentChordsList,
                chromaBins = chromaList.toList(),
                topKeyCandidates = topCandidates,
                sampleRate = sr,
                bitDepthLabel = bitLabel,
                framesPerBuffer = cbFrames,
                inputBufferCapacityFrames = inCapFrames,
                outputBufferFrames = outBufFrames,
                outputBufferCapacityFrames = outCapFrames,
                inputLatencyMs = inMs,
                dspProcessMs = dspMs,
                dspMaxProcessMs = dspMaxMs,
                outputLatencyMs = outMs,
                totalLatencyMs = totalMs,
                measuredHardwareLatencyMs = totalMs,
                audioPathLabel = pathLabel,
                audioApiLabel = "Oboe $apiTag · $shareTag",
                nativeEngineActive = true,
                hardwareBurstFrames = burstFrames,
                xrunCount = xruns,
                isLowLatencyPath = isLowLatencyPath,
                isUsbInterfaceActive = isUsbActive,
                isBluetoothOutput = isBluetoothOut,
                inputChannelCount = inChCount,
                inputDeviceName = inDeviceLabel,
                outputDeviceName = outDeviceLabel,
                statusMessage = when {
                    useTestFile -> "File Key Detector (Oboe $apiTag) · ${cbFrames}fr"
                    isUsbActive -> "USB Key Detector (${if (inChCount >= 2) st.usbInputChannel.shortLabel else "Mono"}) · $apiTag $shareTag · ${cbFrames}fr"
                    else -> "Live Key Detector (Oboe $apiTag) · ${cbFrames}fr"
                },
                waveformInput = waveInList.toList(),
                waveformOutput = waveOutList.toList(),
                audioSourceMode = sourceMode,
                testFileName = clip?.displayName,
                testFileDurationSec = clip?.durationSec ?: 0f,
                testFileProgress = testProg,
                testFilePlaying = testFilePlaying.get()
            )

            try {
                Thread.sleep(30)
            } catch (_: InterruptedException) {
                break
            }
        }

        val wasDisconnected = running.getAndSet(false)
        NativeOboeBridge.nativeStop()
        if (wasDisconnected) {
            _telemetry.value = _telemetry.value.copy(
                isRunning = false,
                statusMessage = "Audio stream disconnected (check USB OTG cable & tap START)"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun runAudioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        deviceHelper.refreshDevices()
        val inputDevice = deviceHelper.findInputDevice(preferredInputDeviceId)
        val outputDevice = deviceHelper.findOutputDevice(preferredOutputDeviceId)

        val currentState = pedalStateRef.get()
        val forceFast = currentState.forceLowLatencyPath
        val sourceMode = currentState.audioSourceMode
        val useTestFile = sourceMode == AudioSourceMode.TEST_FILE && testClipRef.get() != null

        val nativeSr = deviceHelper.getNativeSampleRate()
        var targetSr = deviceHelper.negotiateSampleRate(
            requestedHz = sampleRateOption.hz,
            nativeHz = nativeSr,
            inputDevice = inputDevice,
            outputDevice = outputDevice
        )
        val nativeFrames = deviceHelper.getNativeFramesPerBuffer()
        val isUsbIn = !useTestFile && inputDevice?.let { AudioDeviceHelper.isUsbDeviceType(it.type) } == true
        val isUsbOut = outputDevice?.let { AudioDeviceHelper.isUsbDeviceType(it.type) } == true
        val isUsbActive = isUsbIn || isUsbOut
        val autoBaseFrames = when {
            isUsbActive && forceFast && currentState.latencyProfile == LatencyProfile.ULTRA_LOW_1X ->
                nativeFrames.coerceIn(192, 256)
            forceFast && currentState.latencyProfile == LatencyProfile.ULTRA_LOW_1X ->
                min(nativeFrames, 192)
            else -> nativeFrames
        }
        val baseFrames = bufferFramesOption.frames ?: autoBaseFrames
        val framesPerBuffer = if (bufferFramesOption.frames != null) {
            baseFrames.coerceIn(64, 2048)
        } else {
            (baseFrames * currentState.latencyProfile.burstMultiplier).coerceIn(64, 2048)
        }

        val wantFloatEncoding = bitDepth == AudioBitDepth.PCM_FLOAT_32BIT
        var activeEncoding = if (wantFloatEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

        val preferStereoIn = !useTestFile && deviceHelper.prefersStereoInput(inputDevice)
        val isBluetoothOut = outputDevice?.let { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true

        // Clear any stuck BIT_PERFECT mixer locks on Android 14+ USB devices before opening PCM/DSP stream
        if (isUsbOut) {
            deviceHelper.clearUsbMixerOverrides(outputDevice)
        }

        var recorder: AudioRecord? = null
        var activeInputChannels = 1
        var track: AudioTrack? = null

        var pitchThread: Thread? = null

        try {
            if (!useTestFile) {
                val recResult = createAudioRecord(
                    sampleRate = targetSr,
                    encoding = activeEncoding,
                    framesPerBuffer = framesPerBuffer,
                    forceFast = forceFast,
                    isUsbIn = isUsbIn,
                    preferStereo = preferStereoIn,
                    inputDevice = inputDevice
                )
                if (recResult != null) {
                    recorder = recResult.first
                    activeInputChannels = recResult.second
                } else {
                    val fallbackRates = listOf(nativeSr, 48000, 44100).distinct()
                    for (sr in fallbackRates) {
                        val retry = createAudioRecord(
                            sampleRate = sr,
                            encoding = AudioFormat.ENCODING_PCM_16BIT,
                            framesPerBuffer = framesPerBuffer,
                            forceFast = forceFast,
                            isUsbIn = isUsbIn,
                            preferStereo = preferStereoIn,
                            inputDevice = inputDevice
                        )
                        if (retry != null) {
                            targetSr = sr
                            activeEncoding = AudioFormat.ENCODING_PCM_16BIT
                            recorder = retry.first
                            activeInputChannels = retry.second
                            break
                        }
                    }
                }
            }

            track = createAudioTrack(
                sampleRate = targetSr,
                encoding = activeEncoding,
                framesPerBuffer = framesPerBuffer,
                forceFast = forceFast,
                isUsbOut = isUsbOut,
                outputDevice = outputDevice
            )
            if (track == null && (targetSr != nativeSr || activeEncoding != AudioFormat.ENCODING_PCM_16BIT)) {
                targetSr = nativeSr
                activeEncoding = AudioFormat.ENCODING_PCM_16BIT
                if (!useTestFile) {
                    recorder?.release()
                    val retryRec = createAudioRecord(
                        sampleRate = targetSr,
                        encoding = activeEncoding,
                        framesPerBuffer = framesPerBuffer,
                        forceFast = forceFast,
                        isUsbIn = isUsbIn,
                        preferStereo = preferStereoIn,
                        inputDevice = inputDevice
                    )
                    recorder = retryRec?.first
                    activeInputChannels = retryRec?.second ?: 1
                }
                track = createAudioTrack(
                    sampleRate = targetSr,
                    encoding = activeEncoding,
                    framesPerBuffer = framesPerBuffer,
                    forceFast = forceFast,
                    isUsbOut = isUsbOut,
                    outputDevice = outputDevice
                )
            }

            if ((!useTestFile && (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED)) ||
                track == null || track.state != AudioTrack.STATE_INITIALIZED
            ) {
                val usbHint = if (isUsbActive) {
                    "USB Audio init failed (${targetSr}Hz, ${if (preferStereoIn) "2ch" else "1ch"}). Check OTG cable/power"
                } else {
                    "Audio hardware init failed (check Mic permission or USB interface)"
                }
                _telemetry.value = _telemetry.value.copy(
                    isRunning = false,
                    statusMessage = usbHint
                )
                running.set(false)
                return
            }

            val outCapFrames = track.bufferCapacityInFrames.coerceAtLeast(framesPerBuffer)
            var currentOutBufFrames = track.bufferSizeInFrames
            if (forceFast) {
                val multiplier = if (isUsbOut) 3 else 2
                val targetOutFrames = (framesPerBuffer * multiplier).coerceIn(framesPerBuffer, outCapFrames)
                val applied = track.setBufferSizeInFrames(targetOutFrames)
                if (applied > 0) currentOutBufFrames = applied
            }

            val useFloat = activeEncoding == AudioFormat.ENCODING_PCM_FLOAT
            val ioInShortBuf = ShortArray(framesPerBuffer * activeInputChannels)
            val ioInFloatBuf = FloatArray(framesPerBuffer * activeInputChannels)
            val ioStereoShortBuf = ShortArray(framesPerBuffer * 2)
            val ioStereoFloatBuf = FloatArray(framesPerBuffer * 2)
            val blockInputFloat = FloatArray(framesPerBuffer)
            val keyRing = FloatArray(8192)
            var keyWritePos = 0

            val decimFactor = (targetSr / 12000.0).roundToInt().coerceAtLeast(1)
            val decimatedSr = targetSr.toFloat() / decimFactor.toFloat()
            var decimAcc = 0f
            var decimCount = 0
            var decimSamplesSinceAnalyze = 0

            val keyDetector = KeyDetector()
            val fxProcessor = VocalEffectsProcessor(sampleRate = targetSr)

            val waveIn = FloatArray(64)
            val waveOut = FloatArray(64)
            var waveSampleIdx = 0
            val waveStride = max(1, framesPerBuffer / 16)

            // Lock-free asynchronous FFT Chromagram key analysis thread
            val keySnapshotBuf = FloatArray(KeyDetector.FFT_SIZE)
            val keyBusy = AtomicBoolean(false)
            val keyRequestPending = AtomicBoolean(false)
            asyncKeyResult = KeyAnalysisResult()

            pitchThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val localWin = FloatArray(KeyDetector.FFT_SIZE)
                while (running.get()) {
                    if (kotlinKeyResetPending.compareAndSet(true, false)) {
                        keyDetector.reset()
                    }
                    if (keyRequestPending.compareAndSet(true, false)) {
                        keyBusy.set(true)
                        System.arraycopy(keySnapshotBuf, 0, localWin, 0, KeyDetector.FFT_SIZE)
                        keyBusy.set(false)

                        val st = pedalStateRef.get()
                        asyncKeyResult = keyDetector.analyzeDecimatedFrame(
                            decimated4096 = localWin,
                            decimatedSampleRate = decimatedSr,
                            noiseGateDb = st.noiseGateDb,
                            decayFactor = st.keyDetectionSpeed.decay,
                            lockHoldFrames = st.keyDetectionSpeed.holdFrames,
                            referenceA4Hz = st.referenceA4Hz
                        )
                    } else {
                        LockSupport.parkNanos(3_000_000L)
                    }
                }
            }, "VocalStage-Key-Thread").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }

            var uiEmitCounter = 0
            var dspAvgMs = 0f
            var dspMaxMs = 0f
            val outTimestamp = AudioTimestamp()
            val inTimestamp = AudioTimestamp()

            val inCapFrames = if (!useTestFile && recorder != null) {
                recorder.bufferSizeInFrames
            } else {
                framesPerBuffer
            }
            val isLowLatencyPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY && outCapFrames <= 1024
            } else {
                outCapFrames <= 1024
            }
            val audioPathLabel = when {
                useTestFile -> "Test File Stream"
                isLowLatencyPath -> "FastMixer Direct"
                isUsbActive -> "USB Normal HAL (${outCapFrames}fr)"
                else -> "Shared HAL (${outCapFrames}fr)"
            }

            val inDeviceLabel = if (useTestFile) {
                "File: ${testClipRef.get()?.displayName ?: "Test Audio"}"
            } else {
                inputDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
                    ?: if (isUsbIn) "USB Audio In" else "Device Microphone"
            }
            val outDeviceLabel = outputDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
                ?: if (isUsbOut) "USB Audio Out" else "Line / Speaker Out"
            val bitLabel = if (useFloat) "32f-bit" else "16-bit"

            recorder?.startRecording()
            track.play()
            // Warm up output path with silent burst BEFORE flushing input so startup doesn't lock input behind pre-roll
            val warmupSamples = framesPerBuffer shl 1
            val warmWritten = if (useFloat) {
                ioStereoFloatBuf.fill(0f)
                track.write(ioStereoFloatBuf, 0, warmupSamples, AudioTrack.WRITE_BLOCKING)
            } else {
                ioStereoShortBuf.fill(0)
                track.write(ioStereoShortBuf, 0, warmupSamples)
            }
            var totalFramesWritten: Long = if (warmWritten > 0) (warmWritten / 2).toLong() else 0L
            var totalFramesRead: Long = 0L

            // Flush ALL queued pre-roll frames from AudioRecord so capture starts at t=0 live edge
            if (recorder != null) {
                for (flushIter in 0 until 24) {
                    val flushed = if (useFloat) {
                        recorder.read(ioInFloatBuf, 0, framesPerBuffer * activeInputChannels, AudioRecord.READ_NON_BLOCKING)
                    } else {
                        recorder.read(ioInShortBuf, 0, framesPerBuffer * activeInputChannels, AudioRecord.READ_NON_BLOCKING)
                    }
                    if (flushed <= 0) break
                    totalFramesRead += (flushed / activeInputChannels).toLong()
                }
            }

            val inv32768 = 1f / 32768f
            var consecutiveReadStalls = 0
            var lastXruns = track.underrunCount
            var lastAutoTuneMs = 0L
            var smoothedOutHwFrames = currentOutBufFrames.toFloat()
            var smoothedInQueueFrames = framesPerBuffer.toFloat()

            while (running.get()) {
                val loopState = pedalStateRef.get()
                val readFrames: Int
                if (useTestFile) {
                    val clip = testClipRef.get()
                    if (clip == null || clip.monoSamples.isEmpty()) {
                        blockInputFloat.fill(0f)
                    } else if (!testFilePlaying.get()) {
                        blockInputFloat.fill(0f)
                    } else {
                        val samples = clip.monoSamples
                        val totalLen = samples.size
                        val step = clip.sampleRate.toDouble() / targetSr.toDouble()
                        var pos = testPlayHeadSamples
                        for (i in 0 until framesPerBuffer) {
                            val idx0 = floor(pos).toInt() % totalLen
                            val idx1 = (idx0 + 1) % totalLen
                            val frac = (pos - floor(pos)).toFloat()
                            blockInputFloat[i] = samples[idx0] + (samples[idx1] - samples[idx0]) * frac
                            pos += step
                            if (pos >= totalLen) pos -= totalLen
                        }
                        testPlayHeadSamples = pos
                    }
                    readFrames = framesPerBuffer
                } else {
                    val rec = recorder!!
                    val samplesToRead = framesPerBuffer * activeInputChannels
                    val rawReadSamples = if (useFloat) {
                        rec.read(ioInFloatBuf, 0, samplesToRead, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(ioInShortBuf, 0, samplesToRead)
                    }
                    if (rawReadSamples < 0) {
                        _telemetry.value = _telemetry.value.copy(
                            isRunning = false,
                            statusMessage = "Audio input disconnected/error (code $rawReadSamples). Reconnect USB OTG & tap START"
                        )
                        break
                    }
                    if (rawReadSamples == 0) {
                        consecutiveReadStalls++
                        if (consecutiveReadStalls >= 50) {
                            _telemetry.value = _telemetry.value.copy(
                                isRunning = false,
                                statusMessage = "USB input stalled (0 frames). Try 48kHz / 16-bit PCM or check OTG hub power"
                            )
                            break
                        }
                        continue
                    }
                    consecutiveReadStalls = 0
                    readFrames = rawReadSamples / activeInputChannels
                    if (readFrames <= 0) continue
                    totalFramesRead += readFrames.toLong()

                    if (useFloat) {
                        extractMonoFromFloat(
                            interleaved = ioInFloatBuf,
                            frameCount = readFrames,
                            channels = activeInputChannels,
                            channelMode = loopState.usbInputChannel,
                            outMono = blockInputFloat
                        )
                    } else {
                        extractMonoFromShort(
                            interleaved = ioInShortBuf,
                            frameCount = readFrames,
                            channels = activeInputChannels,
                            channelMode = loopState.usbInputChannel,
                            invScale = inv32768,
                            outMono = blockInputFloat
                        )
                    }

                    // Anti-backlog guard: if input timestamp shows AudioRecord fell > 3 bursts behind real time, drop stale frames
                    if (rec.getTimestamp(inTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS &&
                        inTimestamp.framePosition > totalFramesRead
                    ) {
                        val queuedIn = (inTimestamp.framePosition - totalFramesRead).toFloat()
                        smoothedInQueueFrames = smoothedInQueueFrames * 0.85f + queuedIn * 0.15f
                        if (queuedIn > framesPerBuffer * 3f) {
                            val drainBuf = if (useFloat) ioInFloatBuf else null
                            for (d in 0 until 8) {
                                val drained = if (drainBuf != null) {
                                    rec.read(drainBuf, 0, samplesToRead, AudioRecord.READ_NON_BLOCKING)
                                } else {
                                    rec.read(ioInShortBuf, 0, samplesToRead, AudioRecord.READ_NON_BLOCKING)
                                }
                                if (drained <= 0) break
                                totalFramesRead += (drained / activeInputChannels).toLong()
                            }
                        }
                    }
                }

                val dspStartNs = System.nanoTime()
                val state = pedalStateRef.get()
                val micGainLinear = 10.0.pow(state.micGainDb / 20.0).toFloat()
                val monitorOut = useTestFile || state.directMonitor

                var inSumSq = 0f
                var inPeak = 0f
                var outSumSq = 0f
                var outPeak = 0f

                for (i in 0 until readFrames) {
                    val gained = blockInputFloat[i] * micGainLinear
                    val absIn = abs(gained)
                    if (absIn > inPeak) inPeak = absIn
                    inSumSq += gained * gained

                    decimAcc += gained
                    decimCount++
                    if (decimCount >= decimFactor) {
                        keyRing[keyWritePos and 8191] = decimAcc / decimCount.toFloat()
                        keyWritePos++
                        decimAcc = 0f
                        decimCount = 0
                        decimSamplesSinceAnalyze++
                    }

                    val finalOut = if (monitorOut) fxProcessor.softLimit(gained) else 0f
                    val absOut = abs(finalOut)
                    if (absOut > outPeak) outPeak = absOut
                    outSumSq += finalOut * finalOut

                    if (i % waveStride == 0) {
                        waveIn[waveSampleIdx and 63] = gained.coerceIn(-1f, 1f)
                        waveOut[waveSampleIdx and 63] = finalOut.coerceIn(-1f, 1f)
                        waveSampleIdx++
                    }

                    val stereoIdx = i shl 1
                    if (useFloat) {
                        ioStereoFloatBuf[stereoIdx] = finalOut
                        ioStereoFloatBuf[stereoIdx + 1] = finalOut
                    } else {
                        val pcm = (finalOut * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        ioStereoShortBuf[stereoIdx] = pcm
                        ioStereoShortBuf[stereoIdx + 1] = pcm
                    }
                }

                if (decimSamplesSinceAnalyze >= 512 &&
                    keyWritePos >= KeyDetector.FFT_SIZE &&
                    !keyBusy.get()
                ) {
                    decimSamplesSinceAnalyze = 0
                    val startCopy = keyWritePos - KeyDetector.FFT_SIZE
                    for (k in 0 until KeyDetector.FFT_SIZE) {
                        keySnapshotBuf[k] = keyRing[(startCopy + k) and 8191]
                    }
                    keyRequestPending.set(true)
                    LockSupport.unpark(pitchThread)
                }

                val stereoSamples = readFrames shl 1
                val writtenFrames = if (useFloat) {
                    val w = track.write(ioStereoFloatBuf, 0, stereoSamples, AudioTrack.WRITE_BLOCKING)
                    if (w > 0) w / 2 else 0
                } else {
                    val w = track.write(ioStereoShortBuf, 0, stereoSamples)
                    if (w > 0) w / 2 else 0
                }
                totalFramesWritten += writtenFrames.toLong()

                val dspMs = (System.nanoTime() - dspStartNs) / 1_000_000f
                dspAvgMs = if (dspAvgMs == 0f) dspMs else dspAvgMs * 0.9f + dspMs * 0.1f
                if (dspMs > dspMaxMs) dspMaxMs = dspMs

                if (inPeak >= 0.94f) {
                    peakClipCounter++
                }

                val xruns = track.underrunCount
                val nowMs = System.currentTimeMillis()
                if (xruns > lastXruns && currentOutBufFrames < outCapFrames && nowMs - lastAutoTuneMs > 120L) {
                    lastXruns = xruns
                    lastAutoTuneMs = nowMs
                    val bumped = (currentOutBufFrames + framesPerBuffer).coerceAtMost(outCapFrames)
                    val applied = track.setBufferSizeInFrames(bumped)
                    if (applied > 0) currentOutBufFrames = applied
                }

                uiEmitCounter++
                if (uiEmitCounter >= 8) {
                    uiEmitCounter = 0

                    val activeOutFrames = track.bufferSizeInFrames
                    currentOutBufFrames = activeOutFrames
                    val inRmsDb = amplitudeToDb(sqrt(inSumSq / readFrames))
                    val inPeakDb = amplitudeToDb(inPeak)
                    val outRmsDb = amplitudeToDb(sqrt(outSumSq / readFrames))
                    val outPeakDb = amplitudeToDb(outPeak)

                    var hwOutFrames = activeOutFrames.toFloat()
                    if (track.getTimestamp(outTimestamp) && outTimestamp.nanoTime > 0L) {
                        val deltaNs = (System.nanoTime() - outTimestamp.nanoTime).coerceAtLeast(0L)
                        val playedAtNow = outTimestamp.framePosition.toDouble() +
                            (deltaNs.toDouble() * targetSr.toDouble()) / 1_000_000_000.0
                        val queuedOut = (totalFramesWritten.toDouble() - playedAtNow).toFloat()
                        if (queuedOut in framesPerBuffer.toFloat()..(outCapFrames + framesPerBuffer).toFloat()) {
                            smoothedOutHwFrames = smoothedOutHwFrames * 0.80f + queuedOut * 0.20f
                            hwOutFrames = smoothedOutHwFrames
                        }
                    } else if (!isLowLatencyPath && outCapFrames > activeOutFrames) {
                        hwOutFrames = ((activeOutFrames + outCapFrames) * 0.5f)
                    }

                    val inBufMs = if (useTestFile) 0f else (max(readFrames.toFloat(), smoothedInQueueFrames) / targetSr) * 1000f
                    val outBufMs = (activeOutFrames.toFloat() / targetSr) * 1000f
                    val hwOutMs = (hwOutFrames / targetSr) * 1000f
                    val totalLatencyMs = inBufMs + dspAvgMs + max(outBufMs, hwOutMs)

                    val kr = asyncKeyResult
                    val recentChordsList = recordLiveChord(kr.liveChordLabel, kr.signalActive)
                    val clip = testClipRef.get()
                    val fileProg = if (clip != null && clip.monoSamples.isNotEmpty()) {
                        (testPlayHeadSamples / clip.monoSamples.size.toDouble()).toFloat().coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val domNoteName = if (kr.dominantFreqHz > 40f && kr.dominantMidi > 0) {
                        MusicTheory.midiToNoteName(kr.dominantMidi)
                    } else {
                        "--"
                    }

                    _telemetry.value = AudioTelemetry(
                        isRunning = true,
                        inputRmsDb = inRmsDb,
                        inputPeakDb = inPeakDb,
                        isPeakClipping = inPeak >= 0.94f,
                        peakClipCount = peakClipCounter,
                        harmonyRmsDb = -60f,
                        outputRmsDb = outRmsDb,
                        outputPeakDb = outPeakDb,
                        detectedFreqHz = kr.dominantFreqHz,
                        detectedMidiNote = kr.dominantMidi.toFloat(),
                        detectedNoteName = domNoteName,
                        hasLockedKey = kr.hasLockedKey,
                        detectedKey = kr.lockedKey,
                        detectedScale = kr.lockedScale,
                        liveCandidateKey = kr.liveKey,
                        liveCandidateScale = kr.liveScale,
                        keyConfidence = kr.confidence,
                        signalActive = kr.signalActive,
                        liveChordLabel = kr.liveChordLabel,
                        recentChords = recentChordsList,
                        chromaBins = kr.chroma,
                        topKeyCandidates = kr.topCandidates,
                        sampleRate = targetSr,
                        bitDepthLabel = bitLabel,
                        framesPerBuffer = framesPerBuffer,
                        inputBufferCapacityFrames = inCapFrames,
                        outputBufferFrames = activeOutFrames,
                        outputBufferCapacityFrames = outCapFrames,
                        inputLatencyMs = inBufMs,
                        dspProcessMs = dspAvgMs,
                        dspMaxProcessMs = dspMaxMs,
                        outputLatencyMs = max(outBufMs, hwOutMs),
                        totalLatencyMs = totalLatencyMs,
                        measuredHardwareLatencyMs = totalLatencyMs,
                        audioPathLabel = audioPathLabel,
                        xrunCount = xruns,
                        isLowLatencyPath = isLowLatencyPath,
                        isUsbInterfaceActive = isUsbActive,
                        isBluetoothOutput = isBluetoothOut,
                        inputChannelCount = activeInputChannels,
                        inputDeviceName = inDeviceLabel,
                        outputDeviceName = outDeviceLabel,
                        statusMessage = when {
                            useTestFile -> "Test File Key Detector · ${targetSr / 1000}kHz · $activeOutFrames/$outCapFrames fr"
                            isUsbActive -> "USB Key Detector (${if (activeInputChannels >= 2) state.usbInputChannel.shortLabel else "Mono"}) · $audioPathLabel"
                            else -> "Live Key Detector · $audioPathLabel"
                        },
                        waveformInput = waveIn.toList(),
                        waveformOutput = waveOut.toList(),
                        audioSourceMode = sourceMode,
                        testFileName = clip?.displayName,
                        testFileDurationSec = clip?.durationSec ?: 0f,
                        testFileProgress = fileProg,
                        testFilePlaying = testFilePlaying.get()
                    )
                }
            }
        } catch (t: Throwable) {
            _telemetry.value = _telemetry.value.copy(
                isRunning = false,
                statusMessage = "Audio error: ${t.localizedMessage ?: "Unknown"}"
            )
        } finally {
            running.set(false)
            LockSupport.unpark(pitchThread)
            try {
                pitchThread?.join(250)
            } catch (_: Exception) {
            }
            try {
                recorder?.stop()
                recorder?.release()
            } catch (_: Exception) {
            }
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(
        sampleRate: Int,
        encoding: Int,
        framesPerBuffer: Int,
        forceFast: Boolean,
        isUsbIn: Boolean,
        preferStereo: Boolean,
        inputDevice: AudioDeviceInfo?
    ): Pair<AudioRecord, Int>? {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val channelOrder = if (preferStereo) {
            intArrayOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        } else {
            intArrayOf(AudioFormat.CHANNEL_IN_MONO, AudioFormat.CHANNEL_IN_STEREO)
        }

        // USB Audio HAL (e.g., PreSonus AudioBox iTwo) routes most reliably via VOICE_RECOGNITION / MIC
        val sources = when {
            isUsbIn -> intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_PERFORMANCE
            )
            forceFast -> intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_PERFORMANCE,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            else -> intArrayOf(
                MediaRecorder.AudioSource.VOICE_PERFORMANCE,
                MediaRecorder.AudioSource.MIC
            )
        }

        for (channelMask in channelOrder) {
            val chCount = if (channelMask == AudioFormat.CHANNEL_IN_STEREO) 2 else 1
            val minRecBytes = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            if (minRecBytes <= 0) continue

            val bytesPerFrame = bytesPerSample * chCount
            val targetFastBytes = (framesPerBuffer * 2).coerceIn(128, 512) * bytesPerFrame
            val usbSafeBytes = max(framesPerBuffer * 3 * bytesPerFrame, minRecBytes)
            val fallbackBytes = max(framesPerBuffer * 2 * bytesPerFrame, minRecBytes * 2)

            // Only bypass getMinBufferSize on non-USB fast internal path; USB UAC2 needs >= minRecBytes
            val candidateSizes = if (!isUsbIn && forceFast) {
                intArrayOf(targetFastBytes, fallbackBytes)
            } else {
                intArrayOf(usbSafeBytes, fallbackBytes)
            }

            for (bufBytes in candidateSizes.distinct()) {
                for (src in sources) {
                    val rec = buildRecorder(src, sampleRate, encoding, channelMask, bufBytes, inputDevice)
                    if (rec != null) return Pair(rec, chCount)
                }
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun buildRecorder(
        source: Int,
        sampleRate: Int,
        encoding: Int,
        channelMask: Int,
        bufferBytes: Int,
        inputDevice: AudioDeviceInfo?
    ): AudioRecord? {
        return try {
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return null
            }
            if (inputDevice != null) {
                rec.setPreferredDevice(inputDevice)
            }
            rec
        } catch (_: Throwable) {
            null
        }
    }

    private fun createAudioTrack(
        sampleRate: Int,
        encoding: Int,
        framesPerBuffer: Int,
        forceFast: Boolean,
        isUsbOut: Boolean,
        outputDevice: AudioDeviceInfo?
    ): AudioTrack? {
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val stereoChannels = 2
        val targetFastFrames = (framesPerBuffer * 2).coerceIn(128, 512)
        val fastBytes = targetFastFrames * stereoChannels * bytesPerSample
        val minPlayBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding
        )
        if (minPlayBytes <= 0) return null
        val fallbackBytes = max(fastBytes, minPlayBytes)
        val usbSafeBytes = max(framesPerBuffer * 4 * stereoChannels * bytesPerSample, minPlayBytes)

        // Fast-Track Stereo with minimal double-burst buffer (tries USAGE_GAME low-latency path first)
        if (forceFast) {
            buildTrack(
                sampleRate = sampleRate,
                encoding = encoding,
                bufferBytes = fastBytes,
                usage = AudioAttributes.USAGE_GAME,
                preferLowLatency = true,
                outputDevice = outputDevice
            )?.let { return it }
        }

        // USB Audio HAL fallback: USAGE_MEDIA attaches reliably to USB UAC2 endpoints
        if (isUsbOut) {
            buildTrack(
                sampleRate = sampleRate,
                encoding = encoding,
                bufferBytes = usbSafeBytes,
                usage = AudioAttributes.USAGE_MEDIA,
                preferLowLatency = forceFast,
                outputDevice = outputDevice
            )?.let { return it }

            buildTrack(
                sampleRate = sampleRate,
                encoding = encoding,
                bufferBytes = fallbackBytes * 2,
                usage = AudioAttributes.USAGE_MEDIA,
                preferLowLatency = false,
                outputDevice = outputDevice
            )?.let { return it }
        }

        buildTrack(
            sampleRate = sampleRate,
            encoding = encoding,
            bufferBytes = fallbackBytes,
            usage = if (forceFast && !isUsbOut) AudioAttributes.USAGE_GAME else AudioAttributes.USAGE_MEDIA,
            preferLowLatency = true,
            outputDevice = outputDevice
        )?.let { return it }

        return null
    }

    private fun buildTrack(
        sampleRate: Int,
        encoding: Int,
        bufferBytes: Int,
        usage: Int,
        preferLowLatency: Boolean,
        outputDevice: AudioDeviceInfo?
    ): AudioTrack? {
        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (preferLowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return null
            }
            if (outputDevice != null) {
                track.setPreferredDevice(outputDevice)
            }
            track
        } catch (_: Throwable) {
            null
        }
    }

    private fun amplitudeToDb(amp: Float): Float {
        if (amp <= 0.001f) return -60f
        return (20f * log10(amp)).coerceIn(-60f, 0f)
    }

    companion object {
        fun extractMonoFromShort(
            interleaved: ShortArray,
            frameCount: Int,
            channels: Int,
            channelMode: UsbInputChannel,
            invScale: Float = 1f / 32768f,
            outMono: FloatArray
        ) {
            if (channels <= 1) {
                for (i in 0 until frameCount) {
                    outMono[i] = interleaved[i] * invScale
                }
                return
            }
            for (i in 0 until frameCount) {
                val base = i * channels
                val l = interleaved[base] * invScale
                val r = interleaved[base + 1] * invScale
                outMono[i] = when (channelMode) {
                    UsbInputChannel.CH_1_LEFT -> l
                    UsbInputChannel.CH_2_RIGHT -> r
                    UsbInputChannel.MIX_1_2 -> (l + r).coerceIn(-1f, 1f)
                }
            }
        }

        fun extractMonoFromFloat(
            interleaved: FloatArray,
            frameCount: Int,
            channels: Int,
            channelMode: UsbInputChannel,
            outMono: FloatArray
        ) {
            if (channels <= 1) {
                System.arraycopy(interleaved, 0, outMono, 0, frameCount)
                return
            }
            for (i in 0 until frameCount) {
                val base = i * channels
                val l = interleaved[base]
                val r = interleaved[base + 1]
                outMono[i] = when (channelMode) {
                    UsbInputChannel.CH_1_LEFT -> l
                    UsbInputChannel.CH_2_RIGHT -> r
                    UsbInputChannel.MIX_1_2 -> (l + r).coerceIn(-1f, 1f)
                }
            }
        }
    }
}
