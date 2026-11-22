package com.example

import android.content.Context
import android.net.Uri
import com.example.audio.AudioDeviceHelper
import com.example.audio.AudioFileDecoder
import com.example.audio.KeyDetector
import com.example.audio.StageAudioService
import com.example.audio.VocalDspEngine
import com.example.data.HarmonistRepository
import com.example.data.StageSettings
import com.example.data.VocalPresetEntity
import com.example.data.WsCommandRuleEntity
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.BypassAction
import com.example.model.EchoMode
import com.example.model.HarmonyType
import com.example.model.KeyDispatchStatus
import com.example.model.MatchMode
import com.example.model.MinorKeyMapping
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.PedalState
import com.example.model.WsClientConnectionState
import com.example.model.WsLogEntry
import com.example.model.WsOperationMode
import com.example.model.WsServerStatus
import com.example.ws.StageWebSocketClient
import com.example.ws.StageWebSocketServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class StageController(
    private val appContext: Context,
    val repository: HarmonistRepository,
    val deviceHelper: AudioDeviceHelper,
    val dspEngine: VocalDspEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pedalState = MutableStateFlow(PedalState())
    val pedalState: StateFlow<PedalState> = _pedalState.asStateFlow()

    private val _wsLogs = MutableStateFlow<List<WsLogEntry>>(emptyList())
    val wsLogs: StateFlow<List<WsLogEntry>> = _wsLogs.asStateFlow()

    private val _dispatchStatus = MutableStateFlow(KeyDispatchStatus())
    val dispatchStatus: StateFlow<KeyDispatchStatus> = _dispatchStatus.asStateFlow()

    private val _cachedRules = MutableStateFlow<List<WsCommandRuleEntity>>(emptyList())
    private val _cachedPresets = MutableStateFlow<List<VocalPresetEntity>>(emptyList())
    private val _cachedSettings = MutableStateFlow(StageSettings())

    private val logIdGen = AtomicLong(1L)

    @Volatile
    private var lastAutoSentKeyIndex: Int? = null

    @Volatile
    private var lastAutoSentTimeMs: Long = 0L

    val wsServer = StageWebSocketServer { clientAddr, msg ->
        handleIncomingWsMessage(clientAddr, msg)
    }

    val wsClient = StageWebSocketClient { serverUrl, msg ->
        handleIncomingWsMessage(serverUrl, msg)
    }

    val combinedWsStatus: StateFlow<WsServerStatus> = combine(
        wsServer.status,
        wsClient.status,
        repository.settings
    ) { srv, cli, settings ->
        WsServerStatus(
            mode = settings.wsMode,
            isRunning = srv.isRunning,
            port = srv.port,
            localUrls = srv.localUrls,
            connectedClients = srv.connectedClients,
            clientServerUrl = cli.serverUrl.ifBlank { settings.wsClientUrl },
            clientState = cli.state,
            lastError = if (settings.wsMode == WsOperationMode.CLIENT) cli.lastError else srv.lastError
        )
    }.stateIn(scope, SharingStarted.Eagerly, WsServerStatus())

    init {
        deviceHelper.onDevicesChangedCallback = {
            if (dspEngine.isRunning() && _pedalState.value.audioSourceMode == AudioSourceMode.LIVE_MIC) {
                dspEngine.restart()
            }
        }

        scope.launch {
            repository.ensureSeeded()
            val initialSettings = repository.settings.first()
            _cachedSettings.value = initialSettings
            dspEngine.setAudioFormatConfig(
                sampleRateOption = initialSettings.sampleRateOption,
                bitDepth = initialSettings.bitDepth,
                bufferFramesOption = initialSettings.bufferFramesOption
            )
            if (initialSettings.autoStartWs) {
                when (initialSettings.wsMode) {
                    WsOperationMode.CLIENT -> wsClient.connect(
                        initialSettings.wsClientUrl,
                        initialSettings.wsAutoReconnect
                    )
                    WsOperationMode.SERVER -> wsServer.start(initialSettings.wsPort)
                }
            }
        }

        scope.launch {
            repository.wsRules.collect { _cachedRules.value = it }
        }
        scope.launch {
            repository.presets.collect { _cachedPresets.value = it }
        }
        scope.launch {
            repository.settings.collect { settings ->
                val prev = _cachedSettings.value
                _cachedSettings.value = settings
                dspEngine.setPreferredDevices(
                    settings.preferredInputDeviceId,
                    settings.preferredOutputDeviceId
                )
                dspEngine.setAudioFormatConfig(
                    sampleRateOption = settings.sampleRateOption,
                    bitDepth = settings.bitDepth,
                    bufferFramesOption = settings.bufferFramesOption
                )
                updatePedalState {
                    it.copy(
                        latencyProfile = settings.latencyProfile,
                        directMonitor = settings.directMonitor,
                        noiseGateDb = settings.noiseGateDb,
                        tdPsolaEnabled = settings.tdPsolaEnabled,
                        psolaPitchSmoothing = settings.psolaPitchSmoothing,
                        psolaGrainOverlap = settings.psolaGrainOverlap,
                        psolaPeakSearchRatio = settings.psolaPeakSearchRatio,
                        psolaUnvoicedDamping = settings.psolaUnvoicedDamping,
                        psolaNoteHysteresis = settings.psolaNoteHysteresis,
                        forceLowLatencyPath = settings.forceLowLatencyPath,
                        usbInputChannel = settings.usbInputChannel,
                        keyDetectionSpeed = settings.keyDetectionSpeed,
                        minorKeyMapping = settings.minorKeyMapping,
                        referenceA4Hz = settings.referenceA4Hz,
                        autoSendWsOnKeyDetect = settings.autoSendWsOnKeyDetect
                    )
                }
                if (prev.wsPort != settings.wsPort &&
                    settings.wsMode == WsOperationMode.SERVER &&
                    wsServer.status.value.isRunning
                ) {
                    wsServer.start(settings.wsPort)
                }
            }
        }

        // Real-time key detector telemetry -> formatted WS harmony command auto-dispatcher
        scope.launch {
            combine(
                dspEngine.telemetry,
                _pedalState,
                repository.settings
            ) { telem, state, settings ->
                Triple(telem, state, settings)
            }.collect { (telem, state, settings) ->
                evaluateKeyAndAutoSend(telem, state, settings)
            }
        }
    }

    private fun evaluateKeyAndAutoSend(
        telem: AudioTelemetry,
        state: PedalState,
        settings: StageSettings
    ) {
        val manual = state.manualOverrideKey
        val mappedAutoKey = KeyDetector.mapToOutputKey(
            detectedKey = telem.detectedKey,
            detectedScale = telem.detectedScale,
            mapping = settings.minorKeyMapping
        )
        val effectiveKey = manual ?: mappedAutoKey
        val activeScale = if (manual != null) MusicalScale.MAJOR else telem.detectedScale
        val formatted = KeyDetector.formatWsHarmonyCommand(
            template = settings.wsCommandTemplate,
            outputKey = effectiveKey,
            detectedScale = activeScale
        )

        val sourceLabel = when {
            manual != null ->
                "Manual Override → ${manual.displayName} (เลข ${manual.semitoneOffset})"
            telem.isRunning && telem.hasLockedKey ->
                if (telem.detectedScale == MusicalScale.MINOR &&
                    settings.minorKeyMapping == MinorKeyMapping.RELATIVE_MAJOR
                ) {
                    "Detected ${telem.detectedKey.displayName} Minor → Rel. ${effectiveKey.displayName} (เลข ${effectiveKey.semitoneOffset})"
                } else {
                    "Detected ${telem.detectedKey.displayName} ${telem.detectedScale.displayName} (เลข ${effectiveKey.semitoneOffset})"
                }
            telem.isRunning ->
                "กำลังฟัง... คาดการณ์ ${telem.liveCandidateKey.displayName} ${telem.liveCandidateScale.shortLabel} (${(telem.keyConfidence * 100f).roundToInt()}%)"
            else ->
                "กด START เพื่อเริ่มฟังและจับคีย์อัตโนมัติ"
        }

        val waitingReason = when {
            !settings.autoSendWsOnKeyDetect -> "Auto-Send ปิดอยู่ (ส่งแบบกดมือได้)"
            !isWsReadyToSend(settings) -> "ยังไม่ได้เชื่อมต่อ WebSocket Server"
            manual == null && !telem.isRunning -> "เครื่องตรวจจับคีย์ยังไม่ได้เปิด (Standby)"
            manual == null && !telem.hasLockedKey -> "กำลังเก็บช่วงเสียงเพื่อล็อกคีย์เพลง..."
            manual == null && telem.keyConfidence < settings.minConfidenceToSend ->
                "ความมั่นใจ ${(telem.keyConfidence * 100f).roundToInt()}% ต่ำกว่าเกณฑ์ ${(settings.minConfidenceToSend * 100f).roundToInt()}%"
            else -> null
        }

        _dispatchStatus.value = _dispatchStatus.value.copy(
            effectiveKey = effectiveKey,
            effectiveKeyIndex = effectiveKey.semitoneOffset,
            detectedSourceLabel = sourceLabel,
            formattedCommand = formatted,
            waitingReason = waitingReason
        )

        val readyToAutoDispatch = settings.autoSendWsOnKeyDetect &&
            manual == null &&
            telem.isRunning &&
            telem.hasLockedKey &&
            telem.keyConfidence >= settings.minConfidenceToSend

        if (readyToAutoDispatch) {
            val nowMs = System.currentTimeMillis()
            val idx = effectiveKey.semitoneOffset
            if (idx != lastAutoSentKeyIndex && (nowMs - lastAutoSentTimeMs) >= 650L) {
                val confPct = (telem.keyConfidence * 100f).roundToInt()
                val summary = "Auto Key ${telem.detectedKey.displayName} ${telem.detectedScale.shortLabel} ($confPct%) → index $idx (${effectiveKey.displayName})"
                dispatchWsCommandInternal(
                    command = formatted,
                    keyIndex = idx,
                    summaryLabel = summary,
                    ruleTag = "Auto Key TX"
                )
            }
        }
    }

    private fun isWsReadyToSend(settings: StageSettings): Boolean {
        return when (settings.wsMode) {
            WsOperationMode.CLIENT -> wsClient.status.value.state == WsClientConnectionState.CONNECTED
            WsOperationMode.SERVER -> wsServer.status.value.connectedClients > 0
        }
    }

    private fun dispatchWsCommandInternal(
        command: String,
        keyIndex: Int,
        summaryLabel: String,
        ruleTag: String
    ): Boolean {
        val settings = _cachedSettings.value
        val sentViaClient = wsClient.sendText(command)
        val sentViaServer = if (wsServer.status.value.connectedClients > 0) {
            wsServer.broadcast(command)
            true
        } else {
            false
        }
        val sentOk = sentViaClient || sentViaServer
        val targetAddr = when {
            sentViaClient -> settings.wsClientUrl
            sentViaServer -> "WS Clients (${wsServer.status.value.connectedClients})"
            else -> "Not Connected"
        }

        val nowMs = System.currentTimeMillis()
        if (sentOk) {
            lastAutoSentKeyIndex = keyIndex
            lastAutoSentTimeMs = nowMs
            _dispatchStatus.value = _dispatchStatus.value.copy(
                lastSentCommand = command,
                lastSentKeyIndex = keyIndex,
                lastSentTimeMs = nowMs,
                txCount = _dispatchStatus.value.txCount + 1
            )
        }

        appendLog(
            clientAddress = targetAddr,
            rawMessage = command,
            matchedRuleName = if (sentOk) ruleTag else "$ruleTag (Offline)",
            appliedSummary = if (sentOk) summaryLabel else "$summaryLabel · ยังไม่เชื่อมต่อ WS",
            matched = sentOk,
            isOutgoing = true
        )
        return sentOk
    }

    fun forceSendCurrentCommand(): Boolean {
        val d = _dispatchStatus.value
        val keyIdx = d.effectiveKeyIndex
        return dispatchWsCommandInternal(
            command = d.formattedCommand,
            keyIndex = keyIdx,
            summaryLabel = "Force Send Key #${keyIdx} (${d.effectiveKey.displayName})",
            ruleTag = "Manual TX"
        )
    }

    fun sendCustomWsText(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val settings = _cachedSettings.value
        val sentClient = wsClient.sendText(clean)
        val sentServer = if (wsServer.status.value.connectedClients > 0) {
            wsServer.broadcast(clean)
            true
        } else {
            false
        }
        val sentOk = sentClient || sentServer
        appendLog(
            clientAddress = if (sentClient) settings.wsClientUrl else if (sentServer) "WS Clients" else "Offline",
            rawMessage = clean,
            matchedRuleName = if (sentOk) "Custom TX" else "Custom TX (Offline)",
            appliedSummary = if (sentOk) "ส่งคำสั่งทดสอบไปยัง Server แล้ว" else "ยังไม่ได้เชื่อมต่อ WebSocket Server",
            matched = sentOk,
            isOutgoing = true
        )
        return sentOk
    }

    fun selectManualKey(key: MusicalKey?) {
        updatePedalState { it.copy(manualOverrideKey = key) }
        if (key != null) {
            val settings = _cachedSettings.value
            val cmd = KeyDetector.formatWsHarmonyCommand(settings.wsCommandTemplate, key, MusicalScale.MAJOR)
            dispatchWsCommandInternal(
                command = cmd,
                keyIndex = key.semitoneOffset,
                summaryLabel = "Manual Key Tap #${key.semitoneOffset} (${key.displayName})",
                ruleTag = "Manual Key TX"
            )
        }
    }

    fun resetKeyDetection() {
        lastAutoSentKeyIndex = null
        lastAutoSentTimeMs = 0L
        updatePedalState { it.copy(manualOverrideKey = null) }
        _dispatchStatus.value = _dispatchStatus.value.copy(fullFileKey = null, fullFileConfidence = 0f)
        dspEngine.resetKeyDetection()
    }

    fun startAudioEngine() {
        val started = dspEngine.start()
        if (started && _cachedSettings.value.keepAliveService) {
            StageAudioService.start(appContext)
        }
    }

    fun stopAudioEngine() {
        dspEngine.stop()
        StageAudioService.stop(appContext)
    }

    fun toggleAudioEngine() {
        if (dspEngine.isRunning()) stopAudioEngine() else startAudioEngine()
    }

    fun switchAudioSourceMode(mode: AudioSourceMode) {
        if (_pedalState.value.audioSourceMode == mode) return
        lastAutoSentKeyIndex = null
        dspEngine.resetKeyDetection()
        updatePedalState { it.copy(audioSourceMode = mode, manualOverrideKey = null) }
    }

    suspend fun importAndLoadTestAudio(uri: Uri): Result<String> {
        return try {
            val clip = AudioFileDecoder.decodeUri(appContext, uri)
            val fullScan = withContext(Dispatchers.Default) {
                KeyDetector.analyzeFullClip(
                    monoSamples = clip.monoSamples,
                    sampleRate = clip.sampleRate,
                    referenceA4Hz = _cachedSettings.value.referenceA4Hz
                )
            }
            lastAutoSentKeyIndex = null
            dspEngine.resetKeyDetection()
            dspEngine.loadTestAudioClip(clip)
            _dispatchStatus.value = _dispatchStatus.value.copy(
                fullFileKey = if (fullScan.hasLockedKey) fullScan.lockedKey else fullScan.liveKey,
                fullFileScale = if (fullScan.hasLockedKey) fullScan.lockedScale else fullScan.liveScale,
                fullFileConfidence = fullScan.confidence
            )
            updatePedalState { it.copy(audioSourceMode = AudioSourceMode.TEST_FILE, manualOverrideKey = null) }
            if (!dspEngine.isRunning()) {
                startAudioEngine()
            }
            Result.success(clip.displayName)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    fun setTestFilePlaying(playing: Boolean) = dspEngine.setTestFilePlaying(playing)
    fun seekTestFile(progress: Float) = dspEngine.seekTestFile(progress)

    fun switchWsMode(mode: WsOperationMode) {
        scope.launch {
            repository.setWsMode(mode)
            val s = _cachedSettings.value
            when (mode) {
                WsOperationMode.CLIENT -> {
                    wsServer.stop()
                    wsClient.connect(s.wsClientUrl, s.wsAutoReconnect)
                }
                WsOperationMode.SERVER -> {
                    wsClient.disconnect()
                    wsServer.start(s.wsPort)
                }
            }
        }
    }

    fun connectWsClient(url: String, autoReconnect: Boolean) {
        scope.launch {
            repository.setWsClientUrl(url)
            repository.setWsAutoReconnect(autoReconnect)
            repository.setWsMode(WsOperationMode.CLIENT)
            wsServer.stop()
            wsClient.connect(url, autoReconnect)
        }
    }

    fun disconnectWsClient() {
        wsClient.disconnect()
    }

    fun updatePedalState(transform: (PedalState) -> PedalState) {
        val updated = transform(_pedalState.value)
        _pedalState.value = updated
        dspEngine.updatePedalState(updated)
    }

    fun applyPreset(preset: VocalPresetEntity) {
        updatePedalState { current ->
            current.copy(
                key = MusicalKey.fromString(preset.keyName) ?: current.key,
                scale = MusicalScale.fromString(preset.scaleName) ?: current.scale,
                harmonyType = HarmonyType.fromString(preset.harmonyTypeName) ?: current.harmonyType,
                balance = preset.balance.coerceIn(0f, 1f),
                echoMode = EchoMode.fromString(preset.echoModeName) ?: current.echoMode,
                echoMix = preset.echoMix.coerceIn(0f, 1f),
                micGainDb = preset.micGainDb.coerceIn(-12f, 18f),
                enhanceEnabled = preset.enhanceEnabled,
                variationEnabled = preset.variationEnabled,
                autoHarmonistEnabled = preset.autoHarmonistEnabled,
                harmonyActive = true,
                fullBypass = false,
                activePresetName = preset.name
            )
        }
    }

    fun cycleNextMemoryPreset() {
        val list = _cachedPresets.value
        if (list.isEmpty()) return
        val currentName = _pedalState.value.activePresetName
        val idx = list.indexOfFirst { it.name == currentName }
        val next = list[(idx + 1) % list.size]
        applyPreset(next)
    }

    fun simulateIncomingCommand(rawMessage: String): WsLogEntry {
        handleIncomingWsMessage("local-test", rawMessage)
        return _wsLogs.value.first()
    }

    private fun handleIncomingWsMessage(sourceAddress: String, rawMessage: String): String {
        if (rawMessage == "__CONNECTED__") {
            val s = _cachedSettings.value
            val d = _dispatchStatus.value
            val t = dspEngine.telemetry.value
            val state = _pedalState.value
            val shouldSendInitial = s.sendOnWsConnect &&
                s.autoSendWsOnKeyDetect &&
                (state.manualOverrideKey != null || (t.isRunning && t.hasLockedKey))

            appendLog(
                clientAddress = sourceAddress,
                rawMessage = "Connected to WebSocket Server",
                matchedRuleName = "WS Link Ready",
                appliedSummary = if (shouldSendInitial) {
                    "เชื่อมต่อสำเร็จ · ส่งคีย์ปัจจุบัน: ${d.formattedCommand}"
                } else {
                    "เชื่อมต่อสำเร็จ · พร้อมส่งคำสั่งคีย์เพลงอัตโนมัติ"
                },
                matched = true,
                isOutgoing = false
            )
            return if (shouldSendInitial) {
                lastAutoSentKeyIndex = d.effectiveKeyIndex
                lastAutoSentTimeMs = System.currentTimeMillis()
                _dispatchStatus.value = _dispatchStatus.value.copy(
                    lastSentCommand = d.formattedCommand,
                    lastSentKeyIndex = d.effectiveKeyIndex,
                    lastSentTimeMs = lastAutoSentTimeMs,
                    txCount = _dispatchStatus.value.txCount + 1
                )
                d.formattedCommand
            } else {
                ""
            }
        }

        val trimmed = rawMessage.trim()
        if (trimmed.equals("reset", ignoreCase = true) || trimmed.equals("key:reset", ignoreCase = true)) {
            resetKeyDetection()
            appendLog(
                clientAddress = sourceAddress,
                rawMessage = rawMessage,
                matchedRuleName = "Remote Reset",
                appliedSummary = "Server สั่งรีเซ็ตการจับคีย์ใหม่",
                matched = true,
                isOutgoing = false
            )
            return ""
        }

        if (trimmed.equals("status", ignoreCase = true) || trimmed.contains("\"cmd\":\"status\"")) {
            appendLog(
                clientAddress = sourceAddress,
                rawMessage = rawMessage,
                matchedRuleName = "Status Poll",
                appliedSummary = "ตอบกลับคำสั่งปัจจุบันให้ Server",
                matched = true,
                isOutgoing = false
            )
            return _dispatchStatus.value.formattedCommand
        }

        val matchedRule = _cachedRules.value
            .filter { it.enabled && it.triggerText.isNotBlank() }
            .firstOrNull { rule -> matchesRule(trimmed, rule) }

        if (matchedRule != null) {
            val summary = applyRule(matchedRule)
            appendLog(
                clientAddress = sourceAddress,
                rawMessage = rawMessage,
                matchedRuleName = matchedRule.name,
                appliedSummary = summary,
                matched = true,
                isOutgoing = false
            )
            return ""
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            val jsonSummary = tryApplyJsonCommand(trimmed)
            if (jsonSummary != null) {
                appendLog(
                    clientAddress = sourceAddress,
                    rawMessage = rawMessage,
                    matchedRuleName = "JSON Command",
                    appliedSummary = jsonSummary,
                    matched = true,
                    isOutgoing = false
                )
                return ""
            }
        }

        appendLog(
            clientAddress = sourceAddress,
            rawMessage = rawMessage,
            matchedRuleName = "Server Message",
            appliedSummary = "ได้รับข้อความตอบกลับจาก Server",
            matched = true,
            isOutgoing = false
        )
        return ""
    }

    private fun matchesRule(input: String, rule: WsCommandRuleEntity): Boolean {
        val trigger = rule.triggerText.trim()
        val mode = MatchMode.entries.firstOrNull { it.name == rule.matchMode } ?: MatchMode.EXACT_IGNORE_CASE
        return when (mode) {
            MatchMode.EXACT_IGNORE_CASE -> input.equals(trigger, ignoreCase = true)
            MatchMode.EXACT_CASE -> input == trigger
            MatchMode.CONTAINS -> input.contains(trigger, ignoreCase = true)
        }
    }

    private fun applyRule(rule: WsCommandRuleEntity): String {
        val changes = mutableListOf<String>()
        rule.targetKey?.let { keyStr ->
            MusicalKey.fromString(keyStr)?.let { k ->
                selectManualKey(k)
                changes.add("Key ${k.displayName}")
            }
        }
        if (rule.targetBypassAction == BypassAction.FULL_BYPASS_ON.name) {
            scope.launch { repository.setAutoSendWsOnKeyDetect(false) }
            changes.add("Auto-Send PAUSED")
        } else if (rule.targetBypassAction == BypassAction.FULL_BYPASS_OFF.name) {
            scope.launch { repository.setAutoSendWsOnKeyDetect(true) }
            changes.add("Auto-Send RESUMED")
        }
        return if (changes.isEmpty()) "Applied" else changes.joinToString(" · ")
    }

    private fun tryApplyJsonCommand(rawJson: String): String? {
        return try {
            val obj = JSONObject(rawJson)
            val changes = mutableListOf<String>()
            if (obj.has("key")) {
                MusicalKey.fromString(obj.getString("key"))?.let {
                    selectManualKey(it)
                    changes.add("Key ${it.displayName}")
                }
            }
            if (obj.has("reset") && obj.optBoolean("reset", false)) {
                resetKeyDetection()
                changes.add("Key Reset")
            }
            if (changes.isEmpty()) null else changes.joinToString(" · ")
        } catch (_: Exception) {
            null
        }
    }

    private fun appendLog(
        clientAddress: String,
        rawMessage: String,
        matchedRuleName: String?,
        appliedSummary: String,
        matched: Boolean,
        isOutgoing: Boolean
    ) {
        val entry = WsLogEntry(
            id = logIdGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            clientAddress = clientAddress,
            rawMessage = rawMessage,
            matchedRuleName = matchedRuleName,
            appliedSummary = appliedSummary,
            matched = matched,
            isOutgoing = isOutgoing
        )
        _wsLogs.value = (listOf(entry) + _wsLogs.value).take(50)
    }
}
