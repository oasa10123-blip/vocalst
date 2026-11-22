package com.example.audio

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.example.model.AudioEndpointInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UsbHostDiagnostic(
    val usbAudioPresent: Boolean = false,
    val audioEndpointMounted: Boolean = false,
    val summary: String = "No USB Audio Interface connected on OTG bus"
)

class AudioDeviceHelper(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val _inputDevices = MutableStateFlow<List<AudioEndpointInfo>>(emptyList())
    val inputDevices: StateFlow<List<AudioEndpointInfo>> = _inputDevices.asStateFlow()

    private val _outputDevices = MutableStateFlow<List<AudioEndpointInfo>>(emptyList())
    val outputDevices: StateFlow<List<AudioEndpointInfo>> = _outputDevices.asStateFlow()

    private val _usbDiagnostic = MutableStateFlow(UsbHostDiagnostic())
    val usbDiagnostic: StateFlow<UsbHostDiagnostic> = _usbDiagnostic.asStateFlow()

    private val _rawInputs = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    private val _rawOutputs = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())

    var onDevicesChangedCallback: (() -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            onDevicesChangedCallback?.invoke()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevices()
            onDevicesChangedCallback?.invoke()
        }
    }

    init {
        refreshDevices()
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    fun release() {
        try {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
        } catch (_: Exception) {
        }
    }

    fun refreshDevices() {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        _rawInputs.value = inputs
        _rawOutputs.value = outputs

        _inputDevices.value = inputs
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { it.toEndpointInfo(isInput = true) }
            .sortedByDescending { it.isUsb }

        _outputDevices.value = outputs
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .map { it.toEndpointInfo(isInput = false) }
            .sortedByDescending { it.isUsb }

        _usbDiagnostic.value = buildUsbDiagnostic(inputs, outputs)
    }

    private fun buildUsbDiagnostic(
        inputs: List<AudioDeviceInfo>,
        outputs: List<AudioDeviceInfo>
    ): UsbHostDiagnostic {
        val usbIn = inputs.firstOrNull { isUsbDeviceType(it.type) }
        val usbOut = outputs.firstOrNull { isUsbDeviceType(it.type) }
        val usbHostAudio = inspectUsbHostAudioDevice()

        return when {
            usbIn != null || usbOut != null -> {
                val activeDev = usbIn ?: usbOut!!
                val name = activeDev.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB Audio Class (UAC)"
                val maxCh = usbIn?.channelCounts?.maxOrNull() ?: 2
                val rates = (usbIn?.sampleRates?.toList().orEmpty() + usbOut?.sampleRates?.toList().orEmpty())
                    .distinct()
                    .sorted()
                    .joinToString("/") { "${it / 1000}k" }
                    .ifBlank { "44.1k/48k" }
                UsbHostDiagnostic(
                    usbAudioPresent = true,
                    audioEndpointMounted = true,
                    summary = "UAC Connected: $name · ${maxCh}ch In · $rates"
                )
            }
            usbHostAudio != null -> {
                val label = listOfNotNull(
                    usbHostAudio.manufacturerName?.takeIf { it.isNotBlank() },
                    usbHostAudio.productName?.takeIf { it.isNotBlank() }
                ).joinToString(" ").ifBlank {
                    "USB Audio VID:${"%04X".format(usbHostAudio.vendorId)}"
                }
                UsbHostDiagnostic(
                    usbAudioPresent = true,
                    audioEndpointMounted = false,
                    summary = "USB Host เห็น $label แต่ Android Audio HAL ยังไม่ Mount — เช็กสาย OTG / ไฟเลี้ยง Hub (หากเปิด +48V)"
                )
            }
            else -> {
                UsbHostDiagnostic(
                    usbAudioPresent = false,
                    audioEndpointMounted = false,
                    summary = "ยังไม่พบ USB Audio Interface (Android ใช้มาตรฐาน UAC1/UAC2 แทน ASIO ของ Windows)"
                )
            }
        }
    }

    private fun inspectUsbHostAudioDevice(): UsbDevice? {
        return try {
            val devices = usbManager?.deviceList?.values ?: return null
            devices.firstOrNull { dev ->
                dev.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                    (0 until dev.interfaceCount).any { idx ->
                        dev.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_AUDIO
                    }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun findInputDevice(preferredId: Int?): AudioDeviceInfo? {
        val list = _rawInputs.value
        if (preferredId != null) {
            list.firstOrNull { it.id == preferredId }?.let { return it }
        }
        // Automatically prefer USB Audio Interface if connected
        return list.firstOrNull { isUsbDeviceType(it.type) }
            ?: list.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            ?: list.firstOrNull()
    }

    fun findOutputDevice(preferredId: Int?): AudioDeviceInfo? {
        val list = _rawOutputs.value
        if (preferredId != null) {
            list.firstOrNull { it.id == preferredId }?.let { return it }
        }
        // Automatically prefer USB Audio Interface or wired line-out over phone speaker to avoid stage feedback
        return list.firstOrNull { isUsbDeviceType(it.type) }
            ?: list.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                    it.type == AudioDeviceInfo.TYPE_LINE_DIGITAL
            }
            ?: list.firstOrNull()
    }

    fun getNativeSampleRate(): Int {
        val raw = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        return if (raw != null && raw in 16000..192000) raw else 48000
    }

    fun getNativeFramesPerBuffer(): Int {
        val raw = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        return if (raw != null && raw in 32..2048) raw else 256
    }

    fun negotiateSampleRate(
        requestedHz: Int?,
        nativeHz: Int,
        inputDevice: AudioDeviceInfo?,
        outputDevice: AudioDeviceInfo?
    ): Int {
        val inRates = inputDevice?.sampleRates?.toList().orEmpty()
        val outRates = outputDevice?.sampleRates?.toList().orEmpty()
        return chooseBestSampleRate(
            requestedHz = requestedHz,
            nativeHz = nativeHz,
            inputSupportedRates = inRates,
            outputSupportedRates = outRates
        )
    }

    fun prefersStereoInput(inputDevice: AudioDeviceInfo?): Boolean {
        if (inputDevice == null) return false
        val counts = inputDevice.channelCounts
        if (isUsbDeviceType(inputDevice.type)) {
            return counts.isEmpty() || counts.any { it >= 2 }
        }
        return false
    }

    /**
     * Clear any previously pinned BIT_PERFECT mixer overrides on Android 14+ USB outputs.
     * Real-time DSP mixing requires standard PCM/Float mixer compatibility rather than exclusive bit-perfect locks
     * which can reject 16-bit/USAGE_GAME streams or mismatch format encodings.
     */
    fun clearUsbMixerOverrides(outputDevice: AudioDeviceInfo?) {
        if (outputDevice == null || !isUsbDeviceType(outputDevice.type)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val mediaAttr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            audioManager.clearPreferredMixerAttributes(mediaAttr, outputDevice)
        } catch (_: Throwable) {
        }
    }

    companion object {
        fun isUsbDeviceType(type: Int): Boolean {
            return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

        /**
         * Selects the best sample rate compatible with both input and output hardware endpoints.
         * Empty lists mean Android reported "unrestricted / default HAL rate".
         */
        fun chooseBestSampleRate(
            requestedHz: Int?,
            nativeHz: Int,
            inputSupportedRates: List<Int>,
            outputSupportedRates: List<Int>
        ): Int {
            fun supports(list: List<Int>, hz: Int): Boolean = list.isEmpty() || hz in list

            if (requestedHz != null &&
                supports(inputSupportedRates, requestedHz) &&
                supports(outputSupportedRates, requestedHz)
            ) {
                return requestedHz
            }

            val candidates = buildList {
                if (requestedHz != null) add(requestedHz)
                add(nativeHz)
                add(48000)
                add(44100)
                add(96000)
                add(88200)
            }.distinct()

            candidates.firstOrNull { hz ->
                supports(inputSupportedRates, hz) && supports(outputSupportedRates, hz)
            }?.let { return it }

            if (outputSupportedRates.isNotEmpty() && 48000 in outputSupportedRates) return 48000
            if (outputSupportedRates.isNotEmpty() && 44100 in outputSupportedRates) return 44100
            return outputSupportedRates.firstOrNull()
                ?: inputSupportedRates.firstOrNull()
                ?: nativeHz
        }

        private fun deviceTypeLabel(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Interface"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio Headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Accessory"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Line / Headphones"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Out"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "Audio Device"
        }

        private fun AudioDeviceInfo.toEndpointInfo(isInput: Boolean): AudioEndpointInfo {
            val isUsb = isUsbDeviceType(type)
            val label = productName?.toString()?.takeIf { it.isNotBlank() } ?: deviceTypeLabel(type)
            return AudioEndpointInfo(
                id = id,
                name = label,
                typeLabel = deviceTypeLabel(type),
                isUsb = isUsb,
                isInput = isInput,
                maxChannels = channelCounts.maxOrNull() ?: 1,
                supportedSampleRates = sampleRates.toList()
            )
        }
    }
}
