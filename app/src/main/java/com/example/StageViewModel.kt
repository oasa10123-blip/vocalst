package com.example

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.audio.KeyDetector
import com.example.audio.UsbHostDiagnostic
import com.example.data.StageSettings
import com.example.data.VocalPresetEntity
import com.example.data.WsCommandRuleEntity
import com.example.model.AudioBitDepth
import com.example.model.AudioEndpointInfo
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.BufferFramesOption
import com.example.model.EchoMode
import com.example.model.HarmonyType
import com.example.model.KeyDetectionSpeed
import com.example.model.KeyDispatchStatus
import com.example.model.LatencyProfile
import com.example.model.MinorKeyMapping
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.PedalState
import com.example.model.SampleRateOption
import com.example.model.UsbInputChannel
import com.example.model.WsLogEntry
import com.example.model.WsOperationMode
import com.example.model.WsServerStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StageViewModel(
    private val controller: StageController
) : ViewModel() {

    val pedalState: StateFlow<PedalState> = controller.pedalState
    val telemetry: StateFlow<AudioTelemetry> = controller.dspEngine.telemetry
    val dispatchStatus: StateFlow<KeyDispatchStatus> = controller.dispatchStatus
    val wsStatus: StateFlow<WsServerStatus> = controller.combinedWsStatus
    val wsLogs: StateFlow<List<WsLogEntry>> = controller.wsLogs
    val inputDevices: StateFlow<List<AudioEndpointInfo>> = controller.deviceHelper.inputDevices
    val outputDevices: StateFlow<List<AudioEndpointInfo>> = controller.deviceHelper.outputDevices
    val usbDiagnostic: StateFlow<UsbHostDiagnostic> = controller.deviceHelper.usbDiagnostic

    val wsRules: StateFlow<List<WsCommandRuleEntity>> = controller.repository.wsRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<VocalPresetEntity>> = controller.repository.presets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<StageSettings> = controller.repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageSettings())

    // Audio Engine & Source Controls
    fun startAudioEngine() = controller.startAudioEngine()
    fun stopAudioEngine() = controller.stopAudioEngine()
    fun toggleAudioEngine() = controller.toggleAudioEngine()
    fun resetPeakClips() = controller.dspEngine.resetPeakClips()
    fun refreshAudioDevices() = controller.deviceHelper.refreshDevices()

    fun switchAudioSourceMode(mode: AudioSourceMode) = controller.switchAudioSourceMode(mode)
    fun setTestFilePlaying(playing: Boolean) = controller.setTestFilePlaying(playing)
    fun seekTestFile(progress: Float) = controller.seekTestFile(progress)

    fun importTestAudioFile(uri: Uri, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            val result = controller.importAndLoadTestAudio(uri)
            onResult(result)
        }
    }

    // Key Detector & Dispatcher Controls
    fun resetKeyDetection() = controller.resetKeyDetection()
    fun selectManualKey(key: MusicalKey?) = controller.selectManualKey(key)
    fun forceSendCurrentCommand(): Boolean = controller.forceSendCurrentCommand()
    fun sendCustomWsText(text: String): Boolean = controller.sendCustomWsText(text)

    fun setKeyDetectionSpeed(speed: KeyDetectionSpeed) {
        controller.updatePedalState { it.copy(keyDetectionSpeed = speed) }
        viewModelScope.launch { controller.repository.setKeyDetectionSpeed(speed) }
    }

    fun setMinorKeyMapping(mapping: MinorKeyMapping) {
        controller.updatePedalState { it.copy(minorKeyMapping = mapping) }
        viewModelScope.launch { controller.repository.setMinorKeyMapping(mapping) }
    }

    fun setReferenceA4Hz(hz: Float) {
        val clamped = hz.coerceIn(430f, 450f)
        controller.updatePedalState { it.copy(referenceA4Hz = clamped) }
        viewModelScope.launch { controller.repository.setReferenceA4Hz(clamped) }
    }

    fun setAutoSendWsOnKeyDetect(enabled: Boolean) {
        controller.updatePedalState { it.copy(autoSendWsOnKeyDetect = enabled) }
        viewModelScope.launch { controller.repository.setAutoSendWsOnKeyDetect(enabled) }
    }

    fun setMinConfidenceToSend(confidence: Float) {
        viewModelScope.launch { controller.repository.setMinConfidenceToSend(confidence) }
    }

    fun setWsCommandTemplate(template: String) {
        viewModelScope.launch { controller.repository.setWsCommandTemplate(template) }
    }

    fun resetWsCommandTemplate() {
        viewModelScope.launch {
            controller.repository.setWsCommandTemplate(KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE)
        }
    }

    fun setSendOnWsConnect(enabled: Boolean) {
        viewModelScope.launch { controller.repository.setSendOnWsConnect(enabled) }
    }

    fun setMicGainDb(db: Float) = controller.updatePedalState { it.copy(micGainDb = db.coerceIn(-12f, 18f)) }

    // Legacy compatibility actions
    fun setKey(key: MusicalKey) = controller.selectManualKey(key)
    fun setScale(scale: MusicalScale) = controller.updatePedalState { it.copy(scale = scale) }
    fun setHarmonyType(type: HarmonyType) = controller.updatePedalState { it.copy(harmonyType = type) }
    fun setBalance(balance: Float) = controller.updatePedalState { it.copy(balance = balance.coerceIn(0f, 1f)) }
    fun setEchoMode(mode: EchoMode) = controller.updatePedalState { it.copy(echoMode = mode) }
    fun setEchoMix(mix: Float) = controller.updatePedalState { it.copy(echoMix = mix.coerceIn(0f, 1f)) }
    fun toggleEnhance() = controller.updatePedalState { it.copy(enhanceEnabled = !it.enhanceEnabled) }
    fun toggleVariation() = controller.updatePedalState { it.copy(variationEnabled = !it.variationEnabled) }
    fun toggleAutoHarmonist() = controller.updatePedalState { it.copy(autoHarmonistEnabled = !it.autoHarmonistEnabled) }
    fun toggleHarmonyFootswitch() = controller.updatePedalState { it.copy(harmonyActive = !it.harmonyActive) }
    fun toggleFullBypass() = controller.updatePedalState { it.copy(fullBypass = !it.fullBypass) }
    fun cycleMemoryPreset() = controller.cycleNextMemoryPreset()
    fun loadPreset(preset: VocalPresetEntity) = controller.applyPreset(preset)

    fun saveCurrentAsPreset(name: String, existingId: Long = 0L, slotNumber: Int? = null) {
        val p = pedalState.value
        val nextSlot = slotNumber ?: ((presets.value.maxOfOrNull { it.slotNumber } ?: 0) + 1)
        viewModelScope.launch {
            controller.repository.savePreset(
                VocalPresetEntity(
                    id = existingId,
                    slotNumber = nextSlot,
                    name = name.trim().ifEmpty { "Preset M$nextSlot" },
                    keyName = p.key.name,
                    scaleName = p.scale.name,
                    harmonyTypeName = p.harmonyType.name,
                    balance = p.balance,
                    echoModeName = p.echoMode.name,
                    echoMix = p.echoMix,
                    micGainDb = p.micGainDb,
                    enhanceEnabled = p.enhanceEnabled,
                    variationEnabled = p.variationEnabled,
                    autoHarmonistEnabled = p.autoHarmonistEnabled
                )
            )
            controller.updatePedalState { it.copy(activePresetName = name.trim().ifEmpty { "Preset M$nextSlot" }) }
        }
    }

    fun deletePreset(preset: VocalPresetEntity) {
        viewModelScope.launch { controller.repository.deletePreset(preset) }
    }

    // WebSocket Client & Server Controls + Editable Rules
    fun switchWsMode(mode: WsOperationMode) = controller.switchWsMode(mode)

    fun connectWsClient(url: String, autoReconnect: Boolean) =
        controller.connectWsClient(url, autoReconnect)

    fun disconnectWsClient() = controller.disconnectWsClient()

    fun setWsAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { controller.repository.setWsAutoReconnect(enabled) }
    }

    fun toggleWsServer() {
        val port = settings.value.wsPort
        if (controller.wsServer.status.value.isRunning) {
            controller.wsServer.stop()
        } else {
            controller.wsServer.start(port)
        }
    }

    fun updateWsPort(port: Int) {
        viewModelScope.launch {
            controller.repository.setWsPort(port)
            if (controller.wsServer.status.value.isRunning) {
                controller.wsServer.start(port.coerceIn(1024, 65535))
            }
        }
    }

    fun sendTestWsCommand(commandText: String) {
        controller.simulateIncomingCommand(commandText)
    }

    fun saveWsRule(rule: WsCommandRuleEntity) {
        viewModelScope.launch { controller.repository.saveRule(rule) }
    }

    fun deleteWsRule(rule: WsCommandRuleEntity) {
        viewModelScope.launch { controller.repository.deleteRule(rule) }
    }

    fun resetDefaultWsRules() {
        viewModelScope.launch { controller.repository.resetDefaultRules() }
    }

    // Audio Format, Buffer, Bit Depth & Interface Setup
    fun setPreferredInputDevice(id: Int?) {
        viewModelScope.launch { controller.repository.setPreferredInputDeviceId(id) }
    }

    fun setPreferredOutputDevice(id: Int?) {
        viewModelScope.launch { controller.repository.setPreferredOutputDeviceId(id) }
    }

    fun setLatencyProfile(profile: LatencyProfile) {
        viewModelScope.launch { controller.repository.setLatencyProfile(profile) }
    }

    fun setSampleRateOption(option: SampleRateOption) {
        viewModelScope.launch { controller.repository.setSampleRateOption(option) }
    }

    fun setBitDepth(bitDepth: AudioBitDepth) {
        viewModelScope.launch { controller.repository.setBitDepth(bitDepth) }
    }

    fun setBufferFramesOption(option: BufferFramesOption) {
        viewModelScope.launch { controller.repository.setBufferFramesOption(option) }
    }

    fun setDirectMonitor(enabled: Boolean) {
        controller.updatePedalState { it.copy(directMonitor = enabled) }
        viewModelScope.launch { controller.repository.setDirectMonitor(enabled) }
    }

    fun setNoiseGateDb(db: Float) {
        controller.updatePedalState { it.copy(noiseGateDb = db) }
        viewModelScope.launch { controller.repository.setNoiseGateDb(db) }
    }

    fun setKeepAliveService(enabled: Boolean) {
        viewModelScope.launch { controller.repository.setKeepAliveService(enabled) }
    }

    fun setTdPsolaEnabled(enabled: Boolean) {
        controller.updatePedalState { it.copy(tdPsolaEnabled = enabled) }
        viewModelScope.launch { controller.repository.setTdPsolaEnabled(enabled) }
    }

    fun setForceLowLatencyPath(enabled: Boolean) {
        controller.updatePedalState { it.copy(forceLowLatencyPath = enabled) }
        viewModelScope.launch { controller.repository.setForceLowLatencyPath(enabled) }
    }

    fun applyUltraLowLatencyPreset() {
        val optimalBuffer = if (com.example.audio.NativeOboeBridge.isAvailable()) {
            BufferFramesOption.FRAMES_64
        } else {
            val isUsb = controller.dspEngine.telemetry.value.isUsbInterfaceActive ||
                controller.deviceHelper.usbDiagnostic.value.audioEndpointMounted
            if (isUsb) BufferFramesOption.AUTO_NATIVE else BufferFramesOption.FRAMES_96
        }
        controller.updatePedalState {
            it.copy(
                forceLowLatencyPath = true,
                latencyProfile = LatencyProfile.ULTRA_LOW_1X
            )
        }
        viewModelScope.launch {
            controller.repository.setForceLowLatencyPath(true)
            controller.repository.setLatencyProfile(LatencyProfile.ULTRA_LOW_1X)
            controller.repository.setSampleRateOption(SampleRateOption.AUTO_NATIVE)
            controller.repository.setBitDepth(AudioBitDepth.PCM_16BIT)
            controller.repository.setBufferFramesOption(optimalBuffer)
        }
    }

    fun setUsbInputChannel(channel: UsbInputChannel) {
        controller.updatePedalState { it.copy(usbInputChannel = channel) }
        viewModelScope.launch { controller.repository.setUsbInputChannel(channel) }
    }

    class Factory(private val controller: StageController) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StageViewModel(controller) as T
        }
    }
}
