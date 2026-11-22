package com.example.audio

import com.example.model.PedalState

object NativeOboeBridge {
    private val libraryLoaded: Boolean = try {
        System.loadLibrary("vocalstage_native")
        nativeIsAvailable()
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: Throwable) {
        false
    }

    fun isAvailable(): Boolean = libraryLoaded

    fun pushParams(state: PedalState, testFilePlaying: Boolean) {
        if (!libraryLoaded) return
        nativeUpdateDetectorParams(
            micGainDb = state.micGainDb,
            noiseGateDb = state.noiseGateDb,
            directMonitor = state.directMonitor,
            keyDecayFactor = state.keyDetectionSpeed.decay,
            keyLockHoldFrames = state.keyDetectionSpeed.holdFrames,
            referenceA4Hz = state.referenceA4Hz,
            usbChannelMode = state.usbInputChannel.ordinal,
            testFilePlaying = testFilePlaying
        )
    }

    @JvmStatic
    external fun nativeIsAvailable(): Boolean

    @JvmStatic
    external fun nativeStart(
        requestedSampleRate: Int,
        preferFloat: Boolean,
        requestedFrames: Int,
        bufferMultiplier: Int,
        forceLowLatency: Boolean,
        inputDeviceId: Int,
        outputDeviceId: Int,
        useTestFile: Boolean,
        isUsbIn: Boolean,
        isUsbOut: Boolean,
        preferStereoIn: Boolean
    ): Boolean

    @JvmStatic
    external fun nativeStop()

    @JvmStatic
    external fun nativeIsRunning(): Boolean

    @JvmStatic
    external fun nativeUpdateDetectorParams(
        micGainDb: Float,
        noiseGateDb: Float,
        directMonitor: Boolean,
        keyDecayFactor: Float,
        keyLockHoldFrames: Int,
        referenceA4Hz: Float,
        usbChannelMode: Int,
        testFilePlaying: Boolean
    )

    @JvmStatic
    external fun nativeResetKeyDetection()

    @JvmStatic
    external fun nativeSetTestClip(monoSamples: FloatArray?, sampleRate: Int)

    @JvmStatic
    external fun nativeSeekTestFile(progress: Float)

    @JvmStatic
    external fun nativeResetPeakClips()

    @JvmStatic
    external fun nativeReadTelemetry(floatOut: FloatArray, intOut: IntArray)
}
