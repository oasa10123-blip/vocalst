package com.example.data

import com.example.model.AudioBitDepth
import com.example.model.BufferFramesOption
import com.example.model.LatencyProfile
import com.example.model.SampleRateOption
import com.example.model.UsbInputChannel
import com.example.model.WsOperationMode
import kotlinx.coroutines.flow.Flow

class HarmonistRepository(
    private val wsRuleDao: WsCommandRuleDao,
    private val presetDao: VocalPresetDao,
    private val preferences: SettingsPreferences
) {
    val wsRules: Flow<List<WsCommandRuleEntity>> = wsRuleDao.observeAll()
    val presets: Flow<List<VocalPresetEntity>> = presetDao.observeAll()
    val settings: Flow<StageSettings> = preferences.settingsFlow

    suspend fun ensureSeeded() {
        if (wsRuleDao.count() == 0) {
            wsRuleDao.insertAll(DefaultSeeds.defaultWsRules())
        }
        if (presetDao.count() == 0) {
            presetDao.insertAll(DefaultSeeds.defaultPresets())
        }
    }

    suspend fun saveRule(rule: WsCommandRuleEntity) {
        if (rule.id == 0L) {
            wsRuleDao.insert(rule)
        } else {
            wsRuleDao.update(rule)
        }
    }

    suspend fun deleteRule(rule: WsCommandRuleEntity) {
        wsRuleDao.delete(rule)
    }

    suspend fun resetDefaultRules() {
        wsRuleDao.deleteAll()
        wsRuleDao.insertAll(DefaultSeeds.defaultWsRules())
    }

    suspend fun savePreset(preset: VocalPresetEntity): Long {
        return if (preset.id == 0L) {
            presetDao.insert(preset)
        } else {
            presetDao.update(preset)
            preset.id
        }
    }

    suspend fun deletePreset(preset: VocalPresetEntity) {
        presetDao.delete(preset)
    }

    suspend fun setWsMode(mode: WsOperationMode) = preferences.setWsMode(mode)
    suspend fun setWsClientUrl(url: String) = preferences.setWsClientUrl(url)
    suspend fun setWsAutoReconnect(enabled: Boolean) = preferences.setWsAutoReconnect(enabled)
    suspend fun setWsPort(port: Int) = preferences.setWsPort(port)
    suspend fun setPreferredInputDeviceId(id: Int?) = preferences.setPreferredInputDeviceId(id)
    suspend fun setPreferredOutputDeviceId(id: Int?) = preferences.setPreferredOutputDeviceId(id)
    suspend fun setLatencyProfile(profile: LatencyProfile) = preferences.setLatencyProfile(profile)
    suspend fun setSampleRateOption(option: SampleRateOption) = preferences.setSampleRateOption(option)
    suspend fun setBitDepth(bitDepth: AudioBitDepth) = preferences.setBitDepth(bitDepth)
    suspend fun setBufferFramesOption(option: BufferFramesOption) = preferences.setBufferFramesOption(option)
    suspend fun setDirectMonitor(enabled: Boolean) = preferences.setDirectMonitor(enabled)
    suspend fun setNoiseGateDb(db: Float) = preferences.setNoiseGateDb(db)
    suspend fun setKeepAliveService(enabled: Boolean) = preferences.setKeepAliveService(enabled)
    suspend fun setTdPsolaEnabled(enabled: Boolean) = preferences.setTdPsolaEnabled(enabled)
    suspend fun setPsolaPitchSmoothing(value: Float) = preferences.setPsolaPitchSmoothing(value)
    suspend fun setPsolaGrainOverlap(value: Float) = preferences.setPsolaGrainOverlap(value)
    suspend fun setPsolaPeakSearchRatio(value: Float) = preferences.setPsolaPeakSearchRatio(value)
    suspend fun setPsolaUnvoicedDamping(value: Float) = preferences.setPsolaUnvoicedDamping(value)
    suspend fun setPsolaNoteHysteresis(value: Float) = preferences.setPsolaNoteHysteresis(value)
    suspend fun applyTdPsolaParams(
        pitchSmoothing: Float,
        grainOverlap: Float,
        peakSearchRatio: Float,
        unvoicedDamping: Float,
        noteHysteresis: Float
    ) = preferences.applyTdPsolaParams(
        pitchSmoothing = pitchSmoothing,
        grainOverlap = grainOverlap,
        peakSearchRatio = peakSearchRatio,
        unvoicedDamping = unvoicedDamping,
        noteHysteresis = noteHysteresis
    )
    suspend fun setForceLowLatencyPath(enabled: Boolean) = preferences.setForceLowLatencyPath(enabled)
    suspend fun setUsbInputChannel(channel: UsbInputChannel) = preferences.setUsbInputChannel(channel)
    suspend fun setKeyDetectionSpeed(speed: com.example.model.KeyDetectionSpeed) = preferences.setKeyDetectionSpeed(speed)
    suspend fun setMinorKeyMapping(mapping: com.example.model.MinorKeyMapping) = preferences.setMinorKeyMapping(mapping)
    suspend fun setReferenceA4Hz(hz: Float) = preferences.setReferenceA4Hz(hz)
    suspend fun setAutoSendWsOnKeyDetect(enabled: Boolean) = preferences.setAutoSendWsOnKeyDetect(enabled)
    suspend fun setMinConfidenceToSend(confidence: Float) = preferences.setMinConfidenceToSend(confidence)
    suspend fun setWsCommandTemplate(template: String) = preferences.setWsCommandTemplate(template)
    suspend fun setSendOnWsConnect(enabled: Boolean) = preferences.setSendOnWsConnect(enabled)
}
