package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.StageViewModel
import com.example.data.StageSettings
import com.example.data.VocalPresetEntity
import com.example.data.WsCommandRuleEntity
import com.example.model.BypassAction
import com.example.model.EchoMode
import com.example.model.HarmonyType
import com.example.model.KeyDispatchStatus
import com.example.model.MatchMode
import com.example.model.MinorKeyMapping
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.RuleCategory
import com.example.model.WsClientConnectionState
import com.example.model.WsLogEntry
import com.example.model.WsOperationMode
import com.example.model.WsServerStatus
import com.example.ui.components.HardwareLed
import com.example.ui.theme.LedAmber
import com.example.ui.theme.LedBlue
import com.example.ui.theme.LedEmerald
import com.example.ui.theme.LedPeakRed
import com.example.ui.theme.PedalCrimson
import com.example.ui.theme.StagePanelBorder
import com.example.ui.theme.StagePanelElevated
import com.example.ui.theme.StageRackSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSocketControlScreen(
    wsStatus: WsServerStatus,
    dispatchStatus: KeyDispatchStatus,
    settings: StageSettings,
    wsLogs: List<WsLogEntry>,
    wsRules: List<WsCommandRuleEntity>,
    presets: List<VocalPresetEntity>,
    viewModel: StageViewModel,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var clientUrlInput by remember { mutableStateOf(settings.wsClientUrl) }
    var userEditedUrl by remember { mutableStateOf(false) }
    LaunchedEffect(settings.wsClientUrl) {
        if (!userEditedUrl || clientUrlInput.isBlank()) {
            clientUrlInput = settings.wsClientUrl
        }
    }

    var templateInput by remember { mutableStateOf(settings.wsCommandTemplate) }
    LaunchedEffect(settings.wsCommandTemplate) {
        if (templateInput != settings.wsCommandTemplate) {
            templateInput = settings.wsCommandTemplate
        }
    }

    var testCommandInput by remember { mutableStateOf(dispatchStatus.formattedCommand) }
    var selectedCategoryFilter by remember { mutableStateOf<RuleCategory?>(null) }
    var editingRule by remember { mutableStateOf<WsCommandRuleEntity?>(null) }
    var showRuleDialog by remember { mutableStateOf(false) }

    val filteredRules = remember(wsRules, selectedCategoryFilter) {
        if (selectedCategoryFilter == null) wsRules
        else wsRules.filter { it.category == selectedCategoryFilter!!.name }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // WebSocket Client / Server Mode Card
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Mode Switcher
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WsOperationMode.entries.forEach { mode ->
                            FilterChip(
                                selected = wsStatus.mode == mode,
                                onClick = { viewModel.switchWsMode(mode) },
                                label = { Text(mode.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedBlue,
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("ws_mode_${mode.name.lowercase()}")
                            )
                        }
                    }

                    if (wsStatus.mode == WsOperationMode.CLIENT) {
                        val isConnected = wsStatus.clientState == WsClientConnectionState.CONNECTED
                        val isBusy = wsStatus.clientState == WsClientConnectionState.CONNECTING ||
                            wsStatus.clientState == WsClientConnectionState.RECONNECTING

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                HardwareLed(
                                    isOn = isConnected || isBusy,
                                    activeColor = when {
                                        isConnected -> LedEmerald
                                        wsStatus.clientState == WsClientConnectionState.ERROR -> LedPeakRed
                                        else -> LedAmber
                                    },
                                    size = 14.dp,
                                    contentDescription = "WS Client status LED"
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "WS CLIENT · ${wsStatus.clientState.label.uppercase()}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = wsStatus.lastError ?: "เชื่อมต่อกับ Server เพื่อส่งคำสั่งคีย์เพลงอัตโนมัติ (ch 1 harmony ...)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (wsStatus.lastError != null) LedPeakRed else TextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = {
                                    if (isConnected || isBusy) {
                                        viewModel.disconnectWsClient()
                                    } else {
                                        viewModel.connectWsClient(clientUrlInput, settings.wsAutoReconnect)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isConnected || isBusy) LedEmerald else LedBlue,
                                    contentColor = if (isConnected || isBusy) Color(0xFF04140A) else Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_ws_client_connect")
                            ) {
                                Icon(
                                    imageVector = if (isConnected || isBusy) Icons.Default.LinkOff else Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected || isBusy) "DISCONNECT" else "CONNECT",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        OutlinedTextField(
                            value = clientUrlInput,
                            onValueChange = {
                                userEditedUrl = true
                                clientUrlInput = it
                            },
                            label = { Text("Remote WebSocket Server URL (เช่น ws://192.168.1.230:8080/ws)") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_ws_client_url")
                        )

                        if (clientUrlInput.trim() != "ws://192.168.1.230:8080/ws") {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    val presetUrl = "ws://192.168.1.230:8080/ws"
                                    userEditedUrl = false
                                    clientUrlInput = presetUrl
                                    viewModel.connectWsClient(presetUrl, settings.wsAutoReconnect)
                                },
                                label = {
                                    Text(
                                        text = "ใช้ค่าเริ่มต้น & เชื่อมต่อ: ws://192.168.1.230:8080/ws",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = StagePanelElevated
                                ),
                                modifier = Modifier.testTag("chip_preset_ws_230_8080")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Auto-Reconnect ทุก 3 วินาทีเมื่อหลุดการเชื่อมต่อ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Switch(
                                checked = settings.wsAutoReconnect,
                                onCheckedChange = { enabled ->
                                    viewModel.setWsAutoReconnect(enabled)
                                }
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HardwareLed(
                                    isOn = wsStatus.isRunning,
                                    activeColor = LedBlue,
                                    size = 14.dp,
                                    contentDescription = "WebSocket server LED"
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "WEBSOCKET SERVER :${wsStatus.port}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (wsStatus.isRunning) {
                                            "Listening · ${wsStatus.connectedClients} connected client(s)"
                                        } else {
                                            wsStatus.lastError ?: "Server stopped"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (wsStatus.lastError != null) LedPeakRed else TextSecondary
                                    )
                                }
                            }

                            Button(
                                onClick = { viewModel.toggleWsServer() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (wsStatus.isRunning) LedBlue else PedalCrimson,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_ws_server_toggle")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (wsStatus.isRunning) "STOP WS" else "START WS",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }

                        wsStatus.localUrls.forEach { url ->
                            Surface(
                                color = Color(0xFF0B0D12),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, StagePanelBorder)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = LedEmerald
                                    )
                                    IconButton(
                                        onClick = { clipboard.setText(AnnotatedString(url)) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy WebSocket URL",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Outgoing TX Custom Command Console + Recent Traffic
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = testCommandInput,
                            onValueChange = { testCommandInput = it },
                            label = { Text("Send Custom WS Text to Server", style = MaterialTheme.typography.labelSmall) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_test_ws_cmd")
                        )
                        Button(
                            onClick = {
                                if (testCommandInput.isNotBlank()) {
                                    viewModel.sendCustomWsText(testCommandInput)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = LedBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_send_test_ws_cmd")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send WS Command", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("TX", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    // Recent WS TX & RX Traffic
                    if (wsLogs.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "RECENT WS TRAFFIC (TX SENT / RX RECEIVED)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            wsLogs.take(6).forEach { log ->
                                val timeStr = remember(log.timestampMs) {
                                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestampMs))
                                }
                                val directionBadge = if (log.isOutgoing) "TX ↗" else "RX ↙"
                                val badgeColor = when {
                                    log.isOutgoing && log.matched -> LedEmerald
                                    log.isOutgoing -> LedAmber
                                    log.matched -> LedBlue
                                    else -> LedAmber
                                }
                                Surface(
                                    color = StagePanelElevated,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (log.isOutgoing && log.matched) LedEmerald.copy(alpha = 0.25f) else StagePanelBorder
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "$timeStr · [$directionBadge] \"${log.rawMessage}\"",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = badgeColor
                                            )
                                            Text(
                                                text = "${log.appliedSummary} · ${log.clientAddress}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Key-to-WS Harmony Command Dispatcher Settings Card
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LedEmerald.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "KEY → WS COMMAND FORMAT",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "รูปแบบคำสั่งที่ส่งอัตโนมัติเมื่อตรวจจับคีย์เพลงได้ (0=C, 1=C#, ..., 11=B)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.resetWsCommandTemplate() },
                            border = BorderStroke(1.dp, StagePanelBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_reset_ws_template")
                        ) {
                            Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Live formatted preview + Force Send
                    Surface(
                        color = Color(0xFF0A0D12),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, StagePanelBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "LIVE PREVIEW · ${dispatchStatus.effectiveKey.displayName} = ${dispatchStatus.effectiveKeyIndex}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = dispatchStatus.formattedCommand,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LedEmerald
                                )
                            }
                            Button(
                                onClick = { viewModel.forceSendCurrentCommand() },
                                colors = ButtonDefaults.buttonColors(containerColor = LedBlue),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_ws_force_send")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Send Now", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = templateInput,
                        onValueChange = {
                            templateInput = it
                            viewModel.setWsCommandTemplate(it)
                        },
                        label = { Text("Command Template ({key}=0..11, {keyName}=C..B, {scale}=Major/Minor)") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_ws_command_template")
                    )

                    Text("Minor Key Mapping", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        MinorKeyMapping.entries.forEach { mapping ->
                            FilterChip(
                                selected = settings.minorKeyMapping == mapping,
                                onClick = { viewModel.setMinorKeyMapping(mapping) },
                                label = { Text(mapping.label, style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedEmerald.copy(alpha = 0.22f),
                                    selectedLabelColor = LedEmerald
                                )
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Min Key Confidence to Auto-Send", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(
                                text = "${(settings.minConfidenceToSend * 100f).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = LedAmber
                            )
                        }
                        Slider(
                            value = settings.minConfidenceToSend,
                            onValueChange = { viewModel.setMinConfidenceToSend(it) },
                            valueRange = 0.25f..0.80f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LedAmber
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("Auto-Send เมื่อคีย์เพลงเปลี่ยน", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text("ส่งคำสั่งใหม่ทันทีเมื่อจับคีย์และล็อกผลสำเร็จ", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.autoSendWsOnKeyDetect,
                            onCheckedChange = { viewModel.setAutoSendWsOnKeyDetect(it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text("ส่งคีย์ปัจจุบันทันทีเมื่อ Connect WS", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text("ถ้ามีคีย์ที่ล็อกอยู่แล้ว ให้ส่งคำสั่งทันทีที่เชื่อมต่อ Server", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        }
                        Switch(
                            checked = settings.sendOnWsConnect,
                            onCheckedChange = { viewModel.setSendOnWsConnect(it) }
                        )
                    }
                }
            }

            // Editable Command Mappings Section
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "CUSTOM WS COMMAND RULES",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "แก้ไขข้อความคำสั่งที่รับจาก Server เพื่อสั่งเปลี่ยนคีย์และโหมดประสานเสียงได้เอง",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IconButton(
                                onClick = { viewModel.resetDefaultWsRules() },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_reset_ws_rules")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RestartAlt,
                                    contentDescription = "Reset default rules",
                                    tint = TextSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    editingRule = WsCommandRuleEntity(
                                        name = "Custom Command",
                                        triggerText = "song_chorus",
                                        category = RuleCategory.KEY.name,
                                        targetKey = MusicalKey.D.name,
                                        targetScale = MusicalScale.MAJOR.name,
                                        targetHarmonyType = HarmonyType.HIGH_3RD.name
                                    )
                                    showRuleDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PedalCrimson),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_add_ws_rule")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategoryFilter == null,
                            onClick = { selectedCategoryFilter = null },
                            label = { Text("All (${wsRules.size})", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = PedalCrimson,
                                selectedLabelColor = Color.White
                            )
                        )
                        RuleCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = selectedCategoryFilter == cat,
                                onClick = { selectedCategoryFilter = cat },
                                label = { Text(cat.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PedalCrimson,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        filteredRules.forEach { rule ->
                            WsRuleCard(
                                rule = rule,
                                onEdit = {
                                    editingRule = rule
                                    showRuleDialog = true
                                },
                                onToggleEnabled = { enabled ->
                                    viewModel.saveWsRule(rule.copy(enabled = enabled))
                                },
                                onDelete = { viewModel.deleteWsRule(rule) },
                                onTestRun = { viewModel.sendTestWsCommand(rule.triggerText) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRuleDialog && editingRule != null) {
        EditWsRuleDialog(
            initial = editingRule!!,
            presets = presets,
            onDismiss = { showRuleDialog = false },
            onSave = { updated ->
                viewModel.saveWsRule(updated)
                showRuleDialog = false
            }
        )
    }
}

@Composable
private fun WsRuleCard(
    rule: WsCommandRuleEntity,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTestRun: () -> Unit
) {
    val actionSummary = buildList {
        rule.targetKey?.let { add("Key $it") }
        rule.targetScale?.let { add(it) }
        rule.targetHarmonyType?.let { add(it) }
        rule.targetEchoMode?.let { add("Echo $it") }
        rule.targetPresetSlot?.let { add("Preset M$it") }
        if (rule.targetBypassAction != BypassAction.NONE.name) add(rule.targetBypassAction)
    }.joinToString(" · ").ifEmpty { "No action assigned" }

    Surface(
        color = StagePanelElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (rule.enabled) StagePanelBorder else StagePanelBorder.copy(alpha = 0.4f)),
        modifier = Modifier.testTag("ws_rule_card_${rule.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (rule.enabled) TextPrimary else TextSecondary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Surface(
                    color = Color(0xFF0A0C10),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = rule.triggerText,
                        style = MaterialTheme.typography.labelLarge,
                        color = LedAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = actionSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = LedEmerald
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onTestRun,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Test rule",
                        tint = LedBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("btn_edit_rule_${rule.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit rule",
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete rule",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = onToggleEnabled
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditWsRuleDialog(
    initial: WsCommandRuleEntity,
    presets: List<VocalPresetEntity>,
    onDismiss: () -> Unit,
    onSave: (WsCommandRuleEntity) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var triggerText by remember { mutableStateOf(initial.triggerText) }
    var matchMode by remember {
        mutableStateOf(MatchMode.entries.firstOrNull { it.name == initial.matchMode } ?: MatchMode.EXACT_IGNORE_CASE)
    }
    var category by remember {
        mutableStateOf(RuleCategory.entries.firstOrNull { it.name == initial.category } ?: RuleCategory.KEY)
    }
    var targetKey by remember { mutableStateOf(initial.targetKey?.let { MusicalKey.fromString(it) }) }
    var targetScale by remember { mutableStateOf(initial.targetScale?.let { MusicalScale.fromString(it) }) }
    var targetHarmony by remember { mutableStateOf(initial.targetHarmonyType?.let { HarmonyType.fromString(it) }) }
    var targetEcho by remember { mutableStateOf(initial.targetEchoMode?.let { EchoMode.fromString(it) }) }
    var targetBypass by remember {
        mutableStateOf(BypassAction.entries.firstOrNull { it.name == initial.targetBypassAction } ?: BypassAction.NONE)
    }
    var targetPresetSlot by remember { mutableStateOf(initial.targetPresetSlot) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initial.id == 0L) "New WebSocket Command" else "Edit WebSocket Command",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Label") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = triggerText,
                    onValueChange = { triggerText = it },
                    label = { Text("Incoming WS Message Text (แก้ไขคำสั่ง)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("dialog_input_trigger_text")
                )

                Text("Match Mode", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchMode.entries.forEach { m ->
                        FilterChip(
                            selected = matchMode == m,
                            onClick = { matchMode = m },
                            label = { Text(m.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Target Musical Key (Optional)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = targetKey == null,
                        onClick = { targetKey = null },
                        label = { Text("No Change", style = MaterialTheme.typography.labelSmall) }
                    )
                    MusicalKey.entries.forEach { k ->
                        FilterChip(
                            selected = targetKey == k,
                            onClick = { targetKey = k },
                            label = { Text(k.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Target Scale (Optional)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = targetScale == null,
                        onClick = { targetScale = null },
                        label = { Text("No Change", style = MaterialTheme.typography.labelSmall) }
                    )
                    MusicalScale.entries.forEach { s ->
                        FilterChip(
                            selected = targetScale == s,
                            onClick = { targetScale = s },
                            label = { Text(s.displayName, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Target Harmony Type (Optional)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = targetHarmony == null,
                        onClick = { targetHarmony = null },
                        label = { Text("No Change", style = MaterialTheme.typography.labelSmall) }
                    )
                    HarmonyType.entries.forEach { h ->
                        FilterChip(
                            selected = targetHarmony == h,
                            onClick = { targetHarmony = h },
                            label = { Text(h.shortLabel, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Target Echo Mode (Optional)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(
                        selected = targetEcho == null,
                        onClick = { targetEcho = null },
                        label = { Text("No Change", style = MaterialTheme.typography.labelSmall) }
                    )
                    EchoMode.entries.forEach { e ->
                        FilterChip(
                            selected = targetEcho == e,
                            onClick = { targetEcho = e },
                            label = { Text(e.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Text("Bypass / Harmony Switch", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BypassAction.entries.forEach { b ->
                        FilterChip(
                            selected = targetBypass == b,
                            onClick = { targetBypass = b },
                            label = { Text(b.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                if (presets.isNotEmpty()) {
                    Text("Or Load Memory Slot", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = targetPresetSlot == null,
                            onClick = { targetPresetSlot = null },
                            label = { Text("None", style = MaterialTheme.typography.labelSmall) }
                        )
                        presets.forEach { p ->
                            FilterChip(
                                selected = targetPresetSlot == p.slotNumber,
                                onClick = { targetPresetSlot = p.slotNumber },
                                label = { Text("M${p.slotNumber}", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val autoCategory = when {
                        targetPresetSlot != null -> RuleCategory.PRESET.name
                        targetBypass != BypassAction.NONE || targetEcho != null -> RuleCategory.FX.name
                        targetHarmony != null && targetKey == null -> RuleCategory.MODE.name
                        else -> category.name
                    }
                    onSave(
                        initial.copy(
                            name = name.trim().ifEmpty { triggerText.trim() },
                            triggerText = triggerText.trim(),
                            matchMode = matchMode.name,
                            category = autoCategory,
                            targetKey = targetKey?.name,
                            targetScale = targetScale?.name,
                            targetHarmonyType = targetHarmony?.name,
                            targetEchoMode = targetEcho?.name,
                            targetBypassAction = targetBypass.name,
                            targetPresetSlot = targetPresetSlot
                        )
                    )
                },
                enabled = triggerText.isNotBlank(),
                modifier = Modifier.testTag("dialog_btn_save_rule")
            ) {
                Text("Save Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
