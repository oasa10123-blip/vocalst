package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.StageViewModel
import com.example.data.StageSettings
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.KeyDispatchStatus
import com.example.model.MusicalKey
import com.example.model.MusicalScale
import com.example.model.PedalState
import com.example.model.WsClientConnectionState
import com.example.model.WsOperationMode
import com.example.model.WsServerStatus
import com.example.ui.components.ChromagramVisualizer
import com.example.ui.components.HardwareLed
import com.example.ui.components.SignalVuMeter
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
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyDetectorScreen(
    pedalState: PedalState,
    telemetry: AudioTelemetry,
    dispatchStatus: KeyDispatchStatus,
    wsStatus: WsServerStatus,
    settings: StageSettings,
    hasMicPermission: Boolean,
    onRequestMicAndStart: () -> Unit,
    onPickTestAudioFile: () -> Unit,
    onSwitchSourceMode: (AudioSourceMode) -> Unit,
    onNavigateToWs: () -> Unit,
    viewModel: StageViewModel,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val isTestSource = pedalState.audioSourceMode == AudioSourceMode.TEST_FILE

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
                .widthIn(max = 820.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Header, Source Switcher & Engine Control Strip
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "AUTO KEY DETECTOR",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = when {
                                        isTestSource -> LedAmber.copy(alpha = 0.16f)
                                        telemetry.isUsbInterfaceActive -> LedEmerald.copy(alpha = 0.16f)
                                        else -> StagePanelElevated
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        when {
                                            isTestSource -> LedAmber
                                            telemetry.isUsbInterfaceActive -> LedEmerald
                                            else -> StagePanelBorder
                                        }
                                    )
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                isTestSource -> Icons.Default.AudioFile
                                                telemetry.isUsbInterfaceActive -> Icons.Default.Usb
                                                else -> Icons.Default.Mic
                                            },
                                            contentDescription = "Audio Source",
                                            tint = when {
                                                isTestSource -> LedAmber
                                                telemetry.isUsbInterfaceActive -> LedEmerald
                                                else -> TextSecondary
                                            },
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = when {
                                                isTestSource -> "AUDIO FILE"
                                                telemetry.isUsbInterfaceActive -> "USB AUDIO"
                                                else -> "LIVE MIC"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                isTestSource -> LedAmber
                                                telemetry.isUsbInterfaceActive -> LedEmerald
                                                else -> TextSecondary
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = telemetry.inputDeviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.resetKeyDetection() },
                                border = BorderStroke(1.dp, StagePanelBorder),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_reset_key_detection")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "รีเซ็ตคีย์",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                            }

                            Button(
                                onClick = {
                                    if (telemetry.isRunning) {
                                        viewModel.stopAudioEngine()
                                    } else if (isTestSource && telemetry.testFileName != null) {
                                        viewModel.startAudioEngine()
                                    } else if (hasMicPermission) {
                                        viewModel.startAudioEngine()
                                    } else {
                                        onRequestMicAndStart()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (telemetry.isRunning) LedEmerald else PedalCrimson,
                                    contentColor = if (telemetry.isRunning) Color(0xFF04140A) else Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("btn_engine_toggle")
                            ) {
                                Icon(
                                    imageVector = if (telemetry.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (telemetry.isRunning) "STOP" else "START",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    // Audio Source Switcher + Monitor Toggle
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = !isTestSource,
                            onClick = { onSwitchSourceMode(AudioSourceMode.LIVE_MIC) },
                            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("เสียงสด (Mic/USB)", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LedEmerald.copy(alpha = 0.22f),
                                selectedLabelColor = LedEmerald
                            ),
                            modifier = Modifier.testTag("chip_source_live")
                        )
                        FilterChip(
                            selected = isTestSource,
                            onClick = { onSwitchSourceMode(AudioSourceMode.TEST_FILE) },
                            leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("ไฟล์เพลง (Test File)", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LedAmber.copy(alpha = 0.22f),
                                selectedLabelColor = LedAmber
                            ),
                            modifier = Modifier.testTag("chip_source_file")
                        )
                        OutlinedButton(
                            onClick = onPickTestAudioFile,
                            border = BorderStroke(1.dp, StagePanelBorder),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_import_audio")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp), tint = LedAmber)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("นำเข้าไฟล์เพลง", style = MaterialTheme.typography.labelMedium, color = TextPrimary)
                        }
                        FilterChip(
                            selected = pedalState.directMonitor,
                            onClick = { viewModel.setDirectMonitor(!pedalState.directMonitor) },
                            leadingIcon = { Icon(Icons.Default.Headphones, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = {
                                Text(
                                    text = if (pedalState.directMonitor) "Monitor ON" else "Monitor OFF",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LedBlue.copy(alpha = 0.24f),
                                selectedLabelColor = TextPrimary
                            ),
                            modifier = Modifier.testTag("chip_direct_monitor")
                        )
                    }

                    // Test Audio Transport + Instant Full-File Key Scan Summary
                    if (isTestSource) {
                        Surface(
                            color = StagePanelElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StagePanelBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = telemetry.testFileName ?: "ยังไม่ได้เลือกไฟล์เสียง",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { viewModel.setTestFilePlaying(!telemetry.testFilePlaying) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = if (telemetry.testFilePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Play/Pause File",
                                            tint = LedAmber
                                        )
                                    }
                                }

                                if (dispatchStatus.fullFileKey != null) {
                                    val fullKey = dispatchStatus.fullFileKey
                                    val mappedFull = com.example.audio.KeyDetector.mapToOutputKey(
                                        fullKey,
                                        dispatchStatus.fullFileScale,
                                        settings.minorKeyMapping
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Full-Song Scan: ${fullKey.displayName} ${dispatchStatus.fullFileScale.displayName} (${(dispatchStatus.fullFileConfidence * 100f).roundToInt()}%) → Index ${mappedFull.semitoneOffset} (${mappedFull.displayName})",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = LedEmerald,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { viewModel.selectManualKey(mappedFull) },
                                            border = BorderStroke(1.dp, LedEmerald.copy(alpha = 0.6f)),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("ส่งค่าคีย์นี้", style = MaterialTheme.typography.labelSmall, color = LedEmerald)
                                        }
                                    }
                                }

                                Slider(
                                    value = telemetry.testFileProgress,
                                    onValueChange = { viewModel.seekTestFile(it) },
                                    colors = SliderDefaults.colors(
                                        thumbColor = LedAmber,
                                        activeTrackColor = LedAmber
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // 2. HERO DETECTED KEY & OUTPUT INDEX DISPLAY
            val activeDisplayKey = pedalState.manualOverrideKey
                ?: if (telemetry.hasLockedKey) telemetry.detectedKey else telemetry.liveCandidateKey
            val activeDisplayScale = if (pedalState.manualOverrideKey != null) {
                MusicalScale.MAJOR
            } else if (telemetry.hasLockedKey) {
                telemetry.detectedScale
            } else {
                telemetry.liveCandidateScale
            }
            val isKeyReady = pedalState.manualOverrideKey != null || (telemetry.isRunning && telemetry.hasLockedKey)
            val confPct = if (pedalState.manualOverrideKey != null) 100 else (telemetry.keyConfidence * 100f).roundToInt()

            Surface(
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.5.dp,
                    when {
                        pedalState.manualOverrideKey != null -> LedAmber
                        isKeyReady -> LedEmerald
                        telemetry.isRunning -> LedBlue
                        else -> StagePanelBorder
                    }
                ),
                color = StageRackSurface
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    if (isKeyReady) LedEmerald.copy(alpha = 0.14f) else LedBlue.copy(alpha = 0.10f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when {
                                        pedalState.manualOverrideKey != null -> "MANUAL OVERRIDE KEY"
                                        isKeyReady -> "LOCKED SONG KEY"
                                        telemetry.isRunning -> "ANALYZING KEY..."
                                        else -> "KEY DETECTOR STANDBY"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        pedalState.manualOverrideKey != null -> LedAmber
                                        isKeyReady -> LedEmerald
                                        else -> TextSecondary
                                    }
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (telemetry.isRunning || pedalState.manualOverrideKey != null) {
                                        "${activeDisplayKey.displayName} ${activeDisplayScale.displayName}"
                                    } else {
                                        "--"
                                    },
                                    style = MaterialTheme.typography.displayMedium.copy(
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    ),
                                    color = if (isKeyReady) TextPrimary else TextSecondary
                                )
                                Text(
                                    text = if (telemetry.isRunning && (telemetry.liveChordLabel != "--" || telemetry.detectedFreqHz > 40f)) {
                                        val notePart = if (telemetry.detectedFreqHz > 40f) {
                                            "${telemetry.detectedNoteName} (${telemetry.detectedFreqHz.roundToInt()} Hz)"
                                        } else {
                                            "Scanning..."
                                        }
                                        "Chord: ${telemetry.liveChordLabel} · Lead: $notePart"
                                    } else if (telemetry.isRunning) {
                                        "ดีดคอร์ดกีต้าร์หรือเล่นเมโลดี้ เพื่อจับคีย์ตั้งเอฟเฟ็คประสานเสียง"
                                    } else {
                                        "ต่อกีต้าร์/ไมค์ หรือนำเข้าไฟล์เพลง แล้วกด START เพื่อจับคีย์อัตโนมัติ"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                                if (telemetry.recentChords.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Progression: ${telemetry.recentChords.joinToString(" → ")}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LedAmber,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Mapped Output Integer Badge (0=C .. 11=B)
                            Surface(
                                color = Color(0xFF0A0C12),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(
                                    1.5.dp,
                                    if (isKeyReady) LedEmerald.copy(alpha = 0.7f) else StagePanelBorder
                                )
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "WS KEY ID",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "${dispatchStatus.effectiveKeyIndex}",
                                        style = MaterialTheme.typography.displayMedium.copy(
                                            fontSize = 34.sp,
                                            fontWeight = FontWeight.Black
                                        ),
                                        color = if (isKeyReady) LedEmerald else TextPrimary
                                    )
                                    Text(
                                        text = "Key ${dispatchStatus.effectiveKey.displayName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LedAmber
                                    )
                                }
                            }
                        }

                        // Confidence Progress & Live Input Level
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = dispatchStatus.detectedSourceLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Confidence $confPct%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (confPct >= (settings.minConfidenceToSend * 100f).roundToInt()) LedEmerald else LedAmber,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            LinearProgressIndicator(
                                progress = { (confPct / 100f).coerceIn(0f, 1f) },
                                color = if (isKeyReady) LedEmerald else LedBlue,
                                trackColor = Color(0xFF1E222E),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                        }

                        SignalVuMeter(
                            label = "INPUT LEVEL",
                            rmsDb = telemetry.inputRmsDb,
                            peakDb = telemetry.inputPeakDb,
                            accentColor = if (telemetry.isPeakClipping) LedPeakRed else LedEmerald
                        )
                    }
                }
            }

            // 3. WEBSOCKET AUTO-DISPATCH & EXACT COMMAND CARD
            val wsConnected = if (wsStatus.mode == WsOperationMode.CLIENT) {
                wsStatus.clientState == WsClientConnectionState.CONNECTED
            } else {
                wsStatus.isRunning && wsStatus.connectedClients > 0
            }

            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (wsConnected) LedBlue.copy(alpha = 0.65f) else StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            HardwareLed(
                                isOn = wsConnected || wsStatus.clientState == WsClientConnectionState.RECONNECTING,
                                activeColor = if (wsConnected) LedEmerald else LedAmber,
                                size = 12.dp,
                                contentDescription = "WS Connection LED"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (wsStatus.mode == WsOperationMode.CLIENT) {
                                        "WS CLIENT · ${wsStatus.clientState.label.uppercase()}"
                                    } else {
                                        "WS SERVER :${wsStatus.port} (${wsStatus.connectedClients} clients)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (wsStatus.mode == WsOperationMode.CLIENT) wsStatus.clientServerUrl else "Auto-Send เมื่อคีย์เปลี่ยน",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Auto-Send",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (settings.autoSendWsOnKeyDetect) LedEmerald else TextSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = settings.autoSendWsOnKeyDetect,
                                onCheckedChange = { viewModel.setAutoSendWsOnKeyDetect(it) },
                                modifier = Modifier.testTag("switch_auto_send_ws")
                            )
                        }
                    }

                    // Exact Formatted WS Command Payload Box
                    Surface(
                        color = Color(0xFF0A0C10),
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
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "WS COMMAND PAYLOAD (0=C, 1=C#, ... 11=B)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = dispatchStatus.formattedCommand,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = LedEmerald
                                )
                            }
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(dispatchStatus.formattedCommand)) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy WS Command",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { viewModel.forceSendCurrentCommand() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LedBlue,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_force_send_ws")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ส่งคำสั่งเดี๋ยวนี้ (Key ${dispatchStatus.effectiveKeyIndex}: ${dispatchStatus.effectiveKey.displayName})",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (!wsConnected) {
                            OutlinedButton(
                                onClick = onNavigateToWs,
                                border = BorderStroke(1.dp, LedAmber),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("ตั้งค่า WS", style = MaterialTheme.typography.labelMedium, color = LedAmber)
                            }
                        }
                    }

                    val lastSentInfo = dispatchStatus.lastSentCommand?.let { cmd ->
                        "Last TX (#${dispatchStatus.txCount}): $cmd"
                    } ?: (dispatchStatus.waitingReason ?: "พร้อมส่งทันทีเมื่อจับคีย์ได้")
                    Text(
                        text = lastSentInfo,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (dispatchStatus.lastSentCommand != null) LedEmerald else LedAmber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 4. LIVE 12-BIN CHROMAGRAM & TOP 3 CANDIDATES
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ChromagramVisualizer(
                        chromaBins = telemetry.chromaBins,
                        activeKey = activeDisplayKey,
                        activeScale = activeDisplayScale,
                        hasLockedKey = telemetry.hasLockedKey || pedalState.manualOverrideKey != null,
                        signalActive = telemetry.signalActive
                    )

                    if (telemetry.topKeyCandidates.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            telemetry.topKeyCandidates.forEachIndexed { idx, cand ->
                                val mapped = com.example.audio.KeyDetector.mapToOutputKey(
                                    cand.key,
                                    cand.scale,
                                    settings.minorKeyMapping
                                )
                                Surface(
                                    color = StagePanelElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(
                                        1.dp,
                                        if (idx == 0 && telemetry.hasLockedKey) LedEmerald.copy(alpha = 0.6f) else StagePanelBorder
                                    ),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                                    ) {
                                        Text(
                                            text = "#${idx + 1} ${cand.key.displayName} ${cand.scale.shortLabel}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (idx == 0) LedEmerald else TextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${cand.scorePct}% → ID:${mapped.semitoneOffset}",
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

            // 5. QUICK MANUAL KEY OVERRIDE / TEST PAD (0=C .. 11=B)
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MANUAL KEY PAD (0..11)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "กดเพื่อบังคับเลือกและส่งค่าคีย์ 0–11 ทันที หรือกด AUTO เพื่อกลับไปตรวจจับอัตโนมัติ",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        FilterChip(
                            selected = pedalState.manualOverrideKey == null,
                            onClick = { viewModel.selectManualKey(null) },
                            leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp)) },
                            label = { Text("AUTO", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LedEmerald.copy(alpha = 0.25f),
                                selectedLabelColor = LedEmerald
                            ),
                            modifier = Modifier.testTag("chip_manual_auto")
                        )
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MusicalKey.entries.forEach { k ->
                            val isSelected = pedalState.manualOverrideKey == k ||
                                (pedalState.manualOverrideKey == null && dispatchStatus.effectiveKey == k && isKeyReady)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectManualKey(k) },
                                label = {
                                    Text(
                                        text = "${k.semitoneOffset}: ${k.displayName}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (pedalState.manualOverrideKey == k) LedAmber else LedEmerald,
                                    selectedLabelColor = Color(0xFF06120B)
                                ),
                                modifier = Modifier.testTag("pad_key_${k.semitoneOffset}")
                            )
                        }
                    }
                }
            }
        }
    }
}
