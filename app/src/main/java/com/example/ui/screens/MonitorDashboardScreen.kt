package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.StageViewModel
import com.example.audio.UsbHostDiagnostic
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.BufferFramesOption
import com.example.model.KeyDispatchStatus
import com.example.model.PedalState
import com.example.ui.components.HardwareLed
import com.example.ui.components.SignalVuMeter
import com.example.ui.components.WaveformOscilloscope
import com.example.ui.theme.LedAmber
import com.example.ui.theme.LedBlue
import com.example.ui.theme.LedEmerald
import com.example.ui.theme.LedPeakRed
import com.example.ui.theme.PedalCrimson
import com.example.ui.theme.PedalCrimsonBright
import com.example.ui.theme.StagePanelBorder
import com.example.ui.theme.StagePanelElevated
import com.example.ui.theme.StageRackSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun MonitorDashboardScreen(
    pedalState: PedalState,
    telemetry: AudioTelemetry,
    dispatchStatus: KeyDispatchStatus,
    usbDiagnostic: UsbHostDiagnostic,
    hasMicPermission: Boolean,
    onRequestMicAndStart: () -> Unit,
    onPickTestAudioFile: () -> Unit,
    onSwitchSourceMode: (AudioSourceMode) -> Unit,
    viewModel: StageViewModel,
    modifier: Modifier = Modifier
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SIGNAL & LATENCY MONITOR",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = telemetry.statusMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Button(
                    onClick = {
                        if (telemetry.isRunning) {
                            viewModel.stopAudioEngine()
                        } else if (pedalState.audioSourceMode == AudioSourceMode.TEST_FILE &&
                            telemetry.testFileName != null
                        ) {
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
                        .testTag("monitor_engine_toggle")
                ) {
                    Icon(
                        imageVector = if (telemetry.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (telemetry.isRunning) "LIVE" else "START",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Quick Source Switch in Monitor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = pedalState.audioSourceMode == AudioSourceMode.LIVE_MIC,
                    onClick = { onSwitchSourceMode(AudioSourceMode.LIVE_MIC) },
                    leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("เสียงสด (Live Mic)", style = MaterialTheme.typography.labelMedium) }
                )
                FilterChip(
                    selected = pedalState.audioSourceMode == AudioSourceMode.TEST_FILE,
                    onClick = { onSwitchSourceMode(AudioSourceMode.TEST_FILE) },
                    leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("ไฟล์ทดสอบ", style = MaterialTheme.typography.labelMedium) }
                )
                Spacer(modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = onPickTestAudioFile,
                    border = BorderStroke(1.dp, StagePanelBorder),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import", style = MaterialTheme.typography.labelMedium)
                }
            }

            // Latency Hero Card
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = LedEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "ROUND-TRIP LATENCY",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    text = if (telemetry.isRunning) {
                                        String.format("%.2f ms", telemetry.totalLatencyMs)
                                    } else {
                                        "0.00 ms (Standby)"
                                    },
                                    style = MaterialTheme.typography.displaySmall,
                                    color = when {
                                        !telemetry.isRunning -> TextSecondary
                                        telemetry.totalLatencyMs < 15f -> LedEmerald
                                        telemetry.totalLatencyMs < 28f -> LedAmber
                                        else -> LedPeakRed
                                    }
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            val latencyTier = when {
                                !telemetry.isRunning -> "STANDBY"
                                telemetry.totalLatencyMs < 15f && telemetry.isLowLatencyPath -> "STAGE ULTRA-LOW"
                                telemetry.totalLatencyMs < 28f -> "STAGE LOW"
                                telemetry.isUsbInterfaceActive -> "USB HAL BUFFERED"
                                else -> "BUFFERED"
                            }
                            val tierColor = when {
                                !telemetry.isRunning -> TextSecondary
                                telemetry.isLowLatencyPath && telemetry.totalLatencyMs < 20f -> LedEmerald
                                telemetry.totalLatencyMs < 32f -> LedAmber
                                else -> LedPeakRed
                            }
                            Surface(
                                color = if (telemetry.isRunning) tierColor.copy(alpha = 0.14f) else StagePanelElevated,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (telemetry.isRunning) tierColor else StagePanelBorder
                                )
                            ) {
                                Text(
                                    text = latencyTier,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tierColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (telemetry.nativeEngineActive && telemetry.hardwareBurstFrames > 0) {
                                    "${telemetry.sampleRate} Hz · ${telemetry.bitDepthLabel} · ${telemetry.framesPerBuffer}fr (Burst ${telemetry.hardwareBurstFrames})"
                                } else {
                                    "${telemetry.sampleRate} Hz · ${telemetry.bitDepthLabel} · ${telemetry.framesPerBuffer} fr"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = telemetry.audioPathLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (telemetry.isLowLatencyPath) LedEmerald else LedAmber
                            )
                        }
                    }

                    LatencyStageBar(
                        label = "Input Queue (${telemetry.inputDeviceName}, Cap ${telemetry.inputBufferCapacityFrames} fr)",
                        ms = telemetry.inputLatencyMs,
                        maxMs = kotlin.math.max(25f, telemetry.inputLatencyMs),
                        color = LedBlue
                    )
                    LatencyStageBar(
                        label = String.format("DSP Key Chroma FFT (Peak %.2f ms)", telemetry.dspMaxProcessMs),
                        ms = telemetry.dspProcessMs,
                        maxMs = 5f,
                        color = LedAmber
                    )
                    LatencyStageBar(
                        label = "Output Pipe (${telemetry.outputDeviceName})",
                        ms = telemetry.outputLatencyMs,
                        maxMs = kotlin.math.max(25f, telemetry.outputLatencyMs),
                        color = LedEmerald
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TelemetryChip(
                            label = "XRUNS / DROPS",
                            value = "${telemetry.xrunCount}",
                            valueColor = if (telemetry.xrunCount == 0) LedEmerald else LedPeakRed,
                            modifier = Modifier.weight(0.9f)
                        )
                        TelemetryChip(
                            label = "OUT BUFFER",
                            value = if (telemetry.isRunning) {
                                "${telemetry.outputBufferFrames}/${telemetry.outputBufferCapacityFrames} fr"
                            } else {
                                "${telemetry.sampleRate / 1000}k · ${telemetry.bitDepthLabel}"
                            },
                            valueColor = LedBlue,
                            modifier = Modifier.weight(1.1f)
                        )
                        TelemetryChip(
                            label = "AUDIO ENGINE",
                            value = when {
                                telemetry.nativeEngineActive -> telemetry.audioApiLabel.uppercase()
                                telemetry.isLowLatencyPath -> "FAST MIXER"
                                telemetry.isUsbInterfaceActive -> "USB NORMAL HAL"
                                telemetry.isRunning -> "SHARED HAL"
                                else -> "STANDBY"
                            },
                            valueColor = if (telemetry.isLowLatencyPath) LedEmerald else LedAmber,
                            modifier = Modifier.weight(1.25f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = pedalState.forceLowLatencyPath,
                            onClick = { viewModel.setForceLowLatencyPath(!pedalState.forceLowLatencyPath) },
                            leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = {
                                Text(
                                    text = if (pedalState.forceLowLatencyPath) "Fast-Track: ON" else "Fast-Track: OFF",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LedEmerald.copy(alpha = 0.22f),
                                selectedLabelColor = LedEmerald,
                                selectedLeadingIconColor = LedEmerald
                            ),
                            modifier = Modifier.testTag("chip_force_fast_track")
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { viewModel.applyUltraLowLatencyPreset() },
                            border = BorderStroke(1.dp, LedEmerald.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_force_ultra_low")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = LedEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Force 64fr Oboe",
                                style = MaterialTheme.typography.labelMedium,
                                color = LedEmerald
                            )
                        }
                    }

                    if (telemetry.isUsbInterfaceActive || usbDiagnostic.audioEndpointMounted) {
                        Surface(
                            color = StagePanelElevated,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (pedalState.directMonitor) LedAmber.copy(alpha = 0.65f) else LedEmerald.copy(alpha = 0.55f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(
                                        text = if (pedalState.directMonitor) {
                                            "AudioBox iTwo · ป้องกันเสียงวนหอน (Feedback)"
                                        } else {
                                            "Hardware Mix Mode: Live Key Analysis (Mute Out)"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        color = if (pedalState.directMonitor) LedAmber else LedEmerald
                                    )
                                    Text(
                                        text = if (pedalState.directMonitor) {
                                            "กำลังเปิด Monitor Out จากแอป แนะนำให้ปิดถ้าต่อลำโพงสด (เพื่อลดเสียงก้อง/Feedback)"
                                        } else {
                                            "แอปกำลังรับเสียงเพื่อจับคีย์และส่ง WS โดยไม่ปล่อยเสียงออกลำโพง"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.setDirectMonitor(!pedalState.directMonitor) },
                                    border = BorderStroke(
                                        1.dp,
                                        if (pedalState.directMonitor) LedAmber else LedEmerald.copy(alpha = 0.6f)
                                    ),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag(
                                            if (pedalState.directMonitor) "btn_usb_zero_latency_dry_off" else "btn_usb_dry_on"
                                        )
                                ) {
                                    Text(
                                        text = if (pedalState.directMonitor) "ปิด Dry (0ms)" else "เปิด Dry",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (pedalState.directMonitor) LedAmber else LedEmerald
                                    )
                                }
                            }
                        }
                    }

                    if (telemetry.isUsbInterfaceActive && telemetry.framesPerBuffer <= 96 && telemetry.xrunCount > 8) {
                        Surface(
                            color = LedPeakRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, LedPeakRed.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "USB ${telemetry.framesPerBuffer}fr เล็กเกินไป (${telemetry.xrunCount} XRUNs) กด Auto เพื่อลดเสียงสะดุด",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LedPeakRed,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                                )
                                OutlinedButton(
                                    onClick = { viewModel.setBufferFramesOption(BufferFramesOption.AUTO_NATIVE) },
                                    border = BorderStroke(1.dp, LedAmber),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("btn_usb_fix_xruns")
                                ) {
                                    Text("ใช้ Auto USB", style = MaterialTheme.typography.labelSmall, color = LedAmber)
                                }
                            }
                        }
                    }

                    if (telemetry.isBluetoothOutput) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = LedAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "ตรวจพบหูฟัง Bluetooth (มีดีเลย์ของสัญญาณไร้สายเพิ่ม ~100-180ms แนะนำหูฟังมีสาย USB-C / USB Audio Interface)",
                                style = MaterialTheme.typography.labelSmall,
                                color = LedAmber
                            )
                        }
                    }
                }
            }

            // Live Signal Levels & Peak Monitor Card
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "SIGNAL LEVEL METERS",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            HardwareLed(
                                isOn = telemetry.isPeakClipping,
                                activeColor = LedPeakRed,
                                size = 12.dp,
                                contentDescription = "Peak clip indicator"
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.resetPeakClips() },
                            border = BorderStroke(1.dp, StagePanelBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_reset_clips")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Clips: ${telemetry.peakClipCount}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    SignalVuMeter(
                        label = if (pedalState.audioSourceMode == AudioSourceMode.TEST_FILE) {
                            "TEST AUDIO INPUT"
                        } else {
                            "LIVE MIC / LINE INPUT"
                        },
                        rmsDb = telemetry.inputRmsDb,
                        peakDb = telemetry.inputPeakDb,
                        accentColor = LedEmerald
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val confPct = (telemetry.keyConfidence * 100f).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (telemetry.signalActive) "KEY DETECTOR CONFIDENCE (SIGNAL ACTIVE)" else "KEY DETECTOR CONFIDENCE (GATE IDLE)",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "$confPct% · ${telemetry.detectedKey.displayName} ${telemetry.detectedScale.shortLabel}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (telemetry.hasLockedKey) LedEmerald else LedAmber
                            )
                        }
                        LinearProgressIndicator(
                            progress = { telemetry.keyConfidence.coerceIn(0f, 1f) },
                            color = if (telemetry.hasLockedKey) LedEmerald else LedAmber,
                            trackColor = StagePanelElevated,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                    SignalVuMeter(
                        label = if (pedalState.audioSourceMode == AudioSourceMode.TEST_FILE || pedalState.directMonitor) {
                            "MONITOR AUDIO OUTPUT"
                        } else {
                            "MONITOR OUTPUT (SILENCED FOR LIVE MIC)"
                        },
                        rmsDb = telemetry.outputRmsDb,
                        peakDb = telemetry.outputPeakDb,
                        accentColor = LedBlue
                    )

                    if (telemetry.peakClipCount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = LedPeakRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Input clipping detected (${telemetry.peakClipCount}x). Lower MIC SENS or interface gain.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LedPeakRed
                            )
                        }
                    }
                }
            }

            // Live Waveform Oscilloscope + Pitch Tracker
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "REAL-TIME PITCH & WAVEFORM",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = if (telemetry.detectedFreqHz > 20f) {
                                String.format("%.1f Hz (%s)", telemetry.detectedFreqHz, telemetry.detectedNoteName)
                            } else {
                                "Listening..."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = LedAmber
                        )
                    }

                    WaveformOscilloscope(
                        inputSamples = telemetry.waveformInput,
                        outputSamples = telemetry.waveformOutput
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LegendDot(color = Color(0xFF60A5FA), text = "Input Audio")
                            LegendDot(color = PedalCrimsonBright, text = "Monitor Out")
                        }
                        val lockTag = if (telemetry.hasLockedKey) "LOCKED" else "SCAN"
                        Text(
                            text = "$lockTag: ${telemetry.detectedKey.displayName} ${telemetry.detectedScale.shortLabel} → WS #${dispatchStatus.effectiveKeyIndex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (telemetry.hasLockedKey) LedEmerald else LedAmber
                        )
                    }
                }
            }

            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, StagePanelBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = if (telemetry.isUsbInterfaceActive) LedEmerald else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "ACTIVE HARDWARE I/O",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "IN: ${telemetry.inputDeviceName} (${if (telemetry.inputChannelCount >= 2) "2ch · ${pedalState.usbInputChannel.shortLabel}" else "Mono"})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "OUT: ${telemetry.outputDeviceName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = usbDiagnostic.summary,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (usbDiagnostic.audioEndpointMounted) LedEmerald else TextSecondary
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { viewModel.refreshAudioDevices() },
                        border = BorderStroke(1.dp, StagePanelBorder),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_refresh_audio_devices")
                    ) {
                        Text("Scan USB", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyStageBar(
    label: String,
    ms: Float,
    maxMs: Float,
    color: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )
            Text(
                text = String.format("%.2f ms", ms),
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary
            )
        }
        LinearProgressIndicator(
            progress = { (ms / maxMs).coerceIn(0.02f, 1f) },
            color = color,
            trackColor = StagePanelElevated,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )
    }
}

@Composable
private fun TelemetryChip(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = StagePanelElevated,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, StagePanelBorder),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = color,
            shape = RoundedCornerShape(50),
            modifier = Modifier.size(8.dp)
        ) {}
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}
