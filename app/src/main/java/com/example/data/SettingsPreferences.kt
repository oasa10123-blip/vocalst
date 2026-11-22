package com.example.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.audio.KeyDetector
import com.example.model.AudioBitDepth
import com.example.model.BufferFramesOption
import com.example.model.KeyDetectionSpeed
import com.example.model.LatencyProfile
import com.example.model.MinorKeyMapping
import com.example.model.SampleRateOption
import com.example.model.UsbInputChannel
import com.example.model.WsOperationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

import com.example.ws.StageWebSocketClient

private val Context.stageDataStore by preferencesDataStore(name = "vocal_stage_prefs")

data class StageSettings(
    val wsMode: WsOperationMode = WsOperationMode.CLIENT,
    val wsClientUrl: String = StageWebSocketClient.DEFAULT_WS_CLIENT_URL,
    val wsAutoReconnect: Boolean = true,
    val wsPort: Int = 4444,
    val autoStartWs: Boolean = true,
    val preferredInputDeviceId: Int? = null,
    val preferredOutputDeviceId: Int? = null,
    val latencyProfile: LatencyProfile = LatencyProfile.ULTRA_LOW_1X,
    val sampleRateOption: SampleRateOption = SampleRateOption.AUTO_NATIVE,
    val bitDepth: AudioBitDepth = AudioBitDepth.PCM_16BIT,
    val bufferFramesOption: BufferFramesOption = BufferFramesOption.AUTO_NATIVE,
    val directMonitor: Boolean = false,
    val noiseGateDb: Float = -56f,
    val keepAliveService: Boolean = true,
    val tdPsolaEnabled: Boolean = true,
    val psolaPitchSmoothing: Float = 0.60f,
    val psolaGrainOverlap: Float = 1.05f,
    val psolaPeakSearchRatio: Float = 0.30f,
    val psolaUnvoicedDamping: Float = 0.35f,
    val psolaNoteHysteresis: Float = 0.65f,
    val forceLowLatencyPath: Boolean = true,
    val usbInputChannel: UsbInputChannel = UsbInputChannel.MIX_1_2,
    // Auto Key Detector & WS Dispatcher settings
    val keyDetectionSpeed: KeyDetectionSpeed = KeyDetectionSpeed.BALANCED,
    val minorKeyMapping: MinorKeyMapping = MinorKeyMapping.RELATIVE_MAJOR,
    val referenceA4Hz: Float = 440f,
    val autoSendWsOnKeyDetect: Boolean = true,
    val minConfidenceToSend: Float = 0.42f,
    val wsCommandTemplate: String = KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE,
    val sendOnWsConnect: Boolean = true
)

class SettingsPreferences(private val context: Context) {
    private object Keys {
        val WS_MODE = stringPreferencesKey("ws_mode")
        val WS_CLIENT_URL = stringPreferencesKey("ws_client_url")
        val WS_AUTO_RECONNECT = booleanPreferencesKey("ws_auto_reconnect")
        val WS_PORT = intPreferencesKey("ws_port")
        val AUTO_START_WS = booleanPreferencesKey("auto_start_ws")
        val PREFERRED_IN_ID = intPreferencesKey("preferred_in_id")
        val PREFERRED_OUT_ID = intPreferencesKey("preferred_out_id")
        val LATENCY_PROFILE = stringPreferencesKey("latency_profile")
        val SAMPLE_RATE_OPT = stringPreferencesKey("sample_rate_opt")
        val BIT_DEPTH = stringPreferencesKey("bit_depth")
        val BUFFER_FRAMES_OPT = stringPreferencesKey("buffer_frames_opt")
        val DIRECT_MONITOR = booleanPreferencesKey("direct_monitor")
        val NOISE_GATE_DB = floatPreferencesKey("noise_gate_db")
        val KEEP_ALIVE_SERVICE = booleanPreferencesKey("keep_alive_service")
        val TD_PSOLA_ENABLED = booleanPreferencesKey("td_psola_enabled")
        val PSOLA_PITCH_SMOOTHING = floatPreferencesKey("psola_pitch_smoothing")
        val PSOLA_GRAIN_OVERLAP = floatPreferencesKey("psola_grain_overlap")
        val PSOLA_PEAK_SEARCH = floatPreferencesKey("psola_peak_search")
        val PSOLA_UNVOICED_DAMPING = floatPreferencesKey("psola_unvoiced_damping")
        val PSOLA_NOTE_HYSTERESIS = floatPreferencesKey("psola_note_hysteresis")
        val FORCE_LOW_LATENCY_PATH = booleanPreferencesKey("force_low_latency_path")
        val USB_INPUT_CHANNEL = stringPreferencesKey("usb_input_channel")
        val KEY_DETECTION_SPEED = stringPreferencesKey("key_detection_speed")
        val MINOR_KEY_MAPPING = stringPreferencesKey("minor_key_mapping")
        val REFERENCE_A4_HZ = floatPreferencesKey("reference_a4_hz")
        val AUTO_SEND_WS = booleanPreferencesKey("auto_send_ws_on_key")
        val MIN_CONF_TO_SEND = floatPreferencesKey("min_conf_to_send")
        val WS_COMMAND_TEMPLATE = stringPreferencesKey("ws_command_template")
        val SEND_ON_WS_CONNECT = booleanPreferencesKey("send_on_ws_connect")
    }

    val settingsFlow: Flow<StageSettings> = context.stageDataStore.data.map { prefs ->
        val profileName = prefs[Keys.LATENCY_PROFILE] ?: LatencyProfile.ULTRA_LOW_1X.name
        val profile = LatencyProfile.entries.firstOrNull { it.name == profileName }
            ?: LatencyProfile.ULTRA_LOW_1X
        val wsModeName = prefs[Keys.WS_MODE] ?: WsOperationMode.CLIENT.name
        val wsMode = WsOperationMode.entries.firstOrNull { it.name == wsModeName }
            ?: WsOperationMode.CLIENT
        val srName = prefs[Keys.SAMPLE_RATE_OPT] ?: SampleRateOption.AUTO_NATIVE.name
        val srOpt = SampleRateOption.entries.firstOrNull { it.name == srName }
            ?: SampleRateOption.AUTO_NATIVE
        val bdName = prefs[Keys.BIT_DEPTH] ?: AudioBitDepth.PCM_16BIT.name
        val bd = AudioBitDepth.entries.firstOrNull { it.name == bdName }
            ?: AudioBitDepth.PCM_16BIT
        val bfName = prefs[Keys.BUFFER_FRAMES_OPT] ?: BufferFramesOption.AUTO_NATIVE.name
        val bfOpt = BufferFramesOption.entries.firstOrNull { it.name == bfName }
            ?: BufferFramesOption.AUTO_NATIVE
        val usbChName = prefs[Keys.USB_INPUT_CHANNEL] ?: UsbInputChannel.MIX_1_2.name
        val usbCh = UsbInputChannel.entries.firstOrNull { it.name == usbChName }
            ?: UsbInputChannel.MIX_1_2
        val speedName = prefs[Keys.KEY_DETECTION_SPEED] ?: KeyDetectionSpeed.BALANCED.name
        val keySpeed = KeyDetectionSpeed.entries.firstOrNull { it.name == speedName }
            ?: KeyDetectionSpeed.BALANCED
        val minorMapName = prefs[Keys.MINOR_KEY_MAPPING] ?: MinorKeyMapping.RELATIVE_MAJOR.name
        val minorMap = MinorKeyMapping.entries.firstOrNull { it.name == minorMapName }
            ?: MinorKeyMapping.RELATIVE_MAJOR

        val inId = prefs[Keys.PREFERRED_IN_ID]?.takeIf { it >= 0 }
        val outId = prefs[Keys.PREFERRED_OUT_ID]?.takeIf { it >= 0 }
        StageSettings(
            wsMode = wsMode,
            wsClientUrl = prefs[Keys.WS_CLIENT_URL] ?: StageWebSocketClient.DEFAULT_WS_CLIENT_URL,
            wsAutoReconnect = prefs[Keys.WS_AUTO_RECONNECT] ?: true,
            wsPort = prefs[Keys.WS_PORT] ?: 4444,
            autoStartWs = prefs[Keys.AUTO_START_WS] ?: true,
            preferredInputDeviceId = inId,
            preferredOutputDeviceId = outId,
            latencyProfile = profile,
            sampleRateOption = srOpt,
            bitDepth = bd,
            bufferFramesOption = bfOpt,
            directMonitor = prefs[Keys.DIRECT_MONITOR] ?: false,
            noiseGateDb = prefs[Keys.NOISE_GATE_DB] ?: -56f,
            keepAliveService = prefs[Keys.KEEP_ALIVE_SERVICE] ?: true,
            tdPsolaEnabled = prefs[Keys.TD_PSOLA_ENABLED] ?: true,
            psolaPitchSmoothing = (prefs[Keys.PSOLA_PITCH_SMOOTHING] ?: 0.60f).coerceIn(0f, 1f),
            psolaGrainOverlap = (prefs[Keys.PSOLA_GRAIN_OVERLAP] ?: 1.05f).coerceIn(0.80f, 1.50f),
            psolaPeakSearchRatio = (prefs[Keys.PSOLA_PEAK_SEARCH] ?: 0.30f).coerceIn(0.05f, 0.45f),
            psolaUnvoicedDamping = (prefs[Keys.PSOLA_UNVOICED_DAMPING] ?: 0.35f).coerceIn(0.10f, 1.00f),
            psolaNoteHysteresis = (prefs[Keys.PSOLA_NOTE_HYSTERESIS] ?: 0.65f).coerceIn(0.10f, 0.90f),
            forceLowLatencyPath = prefs[Keys.FORCE_LOW_LATENCY_PATH] ?: true,
            usbInputChannel = usbCh,
            keyDetectionSpeed = keySpeed,
            minorKeyMapping = minorMap,
            referenceA4Hz = (prefs[Keys.REFERENCE_A4_HZ] ?: 440f).coerceIn(430f, 450f),
            autoSendWsOnKeyDetect = prefs[Keys.AUTO_SEND_WS] ?: true,
            minConfidenceToSend = (prefs[Keys.MIN_CONF_TO_SEND] ?: 0.42f).coerceIn(0.20f, 0.85f),
            wsCommandTemplate = prefs[Keys.WS_COMMAND_TEMPLATE]
                ?.takeIf { it.isNotBlank() }
                ?: KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE,
            sendOnWsConnect = prefs[Keys.SEND_ON_WS_CONNECT] ?: true
        )
    }

    suspend fun setWsMode(mode: WsOperationMode) {
        context.stageDataStore.edit { it[Keys.WS_MODE] = mode.name }
    }

    suspend fun setWsClientUrl(url: String) {
        context.stageDataStore.edit {
            it[Keys.WS_CLIENT_URL] = StageWebSocketClient.normalizeWsUrl(url)
        }
    }

    suspend fun setWsAutoReconnect(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.WS_AUTO_RECONNECT] = enabled }
    }

    suspend fun setWsPort(port: Int) {
        context.stageDataStore.edit { it[Keys.WS_PORT] = port.coerceIn(1024, 65535) }
    }

    suspend fun setPreferredInputDeviceId(id: Int?) {
        context.stageDataStore.edit { it[Keys.PREFERRED_IN_ID] = id ?: -1 }
    }

    suspend fun setPreferredOutputDeviceId(id: Int?) {
        context.stageDataStore.edit { it[Keys.PREFERRED_OUT_ID] = id ?: -1 }
    }

    suspend fun setLatencyProfile(profile: LatencyProfile) {
        context.stageDataStore.edit { it[Keys.LATENCY_PROFILE] = profile.name }
    }

    suspend fun setSampleRateOption(option: SampleRateOption) {
        context.stageDataStore.edit { it[Keys.SAMPLE_RATE_OPT] = option.name }
    }

    suspend fun setBitDepth(bitDepth: AudioBitDepth) {
        context.stageDataStore.edit { it[Keys.BIT_DEPTH] = bitDepth.name }
    }

    suspend fun setBufferFramesOption(option: BufferFramesOption) {
        context.stageDataStore.edit { it[Keys.BUFFER_FRAMES_OPT] = option.name }
    }

    suspend fun setDirectMonitor(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.DIRECT_MONITOR] = enabled }
    }

    suspend fun setNoiseGateDb(db: Float) {
        context.stageDataStore.edit { it[Keys.NOISE_GATE_DB] = db.coerceIn(-70f, -20f) }
    }

    suspend fun setKeepAliveService(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.KEEP_ALIVE_SERVICE] = enabled }
    }

    suspend fun setTdPsolaEnabled(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.TD_PSOLA_ENABLED] = enabled }
    }

    suspend fun setPsolaPitchSmoothing(value: Float) {
        context.stageDataStore.edit { it[Keys.PSOLA_PITCH_SMOOTHING] = value.coerceIn(0f, 1f) }
    }

    suspend fun setPsolaGrainOverlap(value: Float) {
        context.stageDataStore.edit { it[Keys.PSOLA_GRAIN_OVERLAP] = value.coerceIn(0.80f, 1.50f) }
    }

    suspend fun setPsolaPeakSearchRatio(value: Float) {
        context.stageDataStore.edit { it[Keys.PSOLA_PEAK_SEARCH] = value.coerceIn(0.05f, 0.45f) }
    }

    suspend fun setPsolaUnvoicedDamping(value: Float) {
        context.stageDataStore.edit { it[Keys.PSOLA_UNVOICED_DAMPING] = value.coerceIn(0.10f, 1.00f) }
    }

    suspend fun setPsolaNoteHysteresis(value: Float) {
        context.stageDataStore.edit { it[Keys.PSOLA_NOTE_HYSTERESIS] = value.coerceIn(0.10f, 0.90f) }
    }

    suspend fun applyTdPsolaParams(
        pitchSmoothing: Float,
        grainOverlap: Float,
        peakSearchRatio: Float,
        unvoicedDamping: Float,
        noteHysteresis: Float
    ) {
        context.stageDataStore.edit {
            it[Keys.PSOLA_PITCH_SMOOTHING] = pitchSmoothing.coerceIn(0f, 1f)
            it[Keys.PSOLA_GRAIN_OVERLAP] = grainOverlap.coerceIn(0.80f, 1.50f)
            it[Keys.PSOLA_PEAK_SEARCH] = peakSearchRatio.coerceIn(0.05f, 0.45f)
            it[Keys.PSOLA_UNVOICED_DAMPING] = unvoicedDamping.coerceIn(0.10f, 1.00f)
            it[Keys.PSOLA_NOTE_HYSTERESIS] = noteHysteresis.coerceIn(0.10f, 0.90f)
        }
    }

    suspend fun setForceLowLatencyPath(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.FORCE_LOW_LATENCY_PATH] = enabled }
    }

    suspend fun setUsbInputChannel(channel: UsbInputChannel) {
        context.stageDataStore.edit { it[Keys.USB_INPUT_CHANNEL] = channel.name }
    }

    suspend fun setKeyDetectionSpeed(speed: KeyDetectionSpeed) {
        context.stageDataStore.edit { it[Keys.KEY_DETECTION_SPEED] = speed.name }
    }

    suspend fun setMinorKeyMapping(mapping: MinorKeyMapping) {
        context.stageDataStore.edit { it[Keys.MINOR_KEY_MAPPING] = mapping.name }
    }

    suspend fun setReferenceA4Hz(hz: Float) {
        context.stageDataStore.edit { it[Keys.REFERENCE_A4_HZ] = hz.coerceIn(430f, 450f) }
    }

    suspend fun setAutoSendWsOnKeyDetect(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.AUTO_SEND_WS] = enabled }
    }

    suspend fun setMinConfidenceToSend(confidence: Float) {
        context.stageDataStore.edit { it[Keys.MIN_CONF_TO_SEND] = confidence.coerceIn(0.20f, 0.85f) }
    }

    suspend fun setWsCommandTemplate(template: String) {
        context.stageDataStore.edit {
            it[Keys.WS_COMMAND_TEMPLATE] = template.ifBlank { KeyDetector.DEFAULT_WS_HARMONY_TEMPLATE }
        }
    }

    suspend fun setSendOnWsConnect(enabled: Boolean) {
        context.stageDataStore.edit { it[Keys.SEND_ON_WS_CONNECT] = enabled }
    }
}
