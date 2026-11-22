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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.StageViewModel
import com.example.audio.UsbHostDiagnostic
import com.example.data.StageSettings
import com.example.data.VocalPresetEntity
import com.example.model.AudioBitDepth
import com.example.model.AudioEndpointInfo
import com.example.model.AudioSourceMode
import com.example.model.AudioTelemetry
import com.example.model.BufferFramesOption
import com.example.model.KeyDetectionSpeed
import com.example.model.LatencyProfile
import com.example.model.MinorKeyMapping
import com.example.model.PedalState
import com.example.model.SampleRateOption
import com.example.model.UsbInputChannel
import com.example.ui.theme.LedAmber
import com.example.ui.theme.LedBlue
import com.example.ui.theme.LedEmerald
import com.example.ui.theme.PedalCrimsonBright
import com.example.ui.theme.StagePanelBorder
import com.example.ui.theme.StagePanelElevated
import com.example.ui.theme.StageRackSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetsAndSetupScreen(
    pedalState: PedalState,
    telemetry: AudioTelemetry,
    presets: List<VocalPresetEntity>,
    settings: StageSettings,
    inputDevices: List<AudioEndpointInfo>,
    outputDevices: List<AudioEndpointInfo>,
    usbDiagnostic: UsbHostDiagnostic,
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
            // Audio Format (Bit Depth / Buffer / Sample Rate) & Source Import Card
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
                                text = "AUDIO FORMAT, BIT / BUFFER / SAMPLE RATE",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Active: ${telemetry.sampleRate} Hz · ${telemetry.bitDepthLabel} · Cb ${telemetry.framesPerBuffer}fr · Out ${telemetry.outputBufferFrames}/${telemetry.outputBufferCapacityFrames} fr (${telemetry.audioApiLabel})",
                                style = MaterialTheme.typography.labelMedium,
                                color = LedEmerald
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.applyUltraLowLatencyPreset() },
                            border = BorderStroke(1.dp, LedEmerald.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_setup_force_ultra_low")
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = LedEmerald, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Force 64fr Oboe", style = MaterialTheme.typography.labelSmall, color = LedEmerald)
                        }
                    }

                    Text("Sample Rate", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SampleRateOption.entries.forEach { sr ->
                            FilterChip(
                                selected = settings.sampleRateOption == sr,
                                onClick = { viewModel.setSampleRateOption(sr) },
                                label = { Text(sr.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedBlue.copy(alpha = 0.25f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("chip_sr_${sr.name.lowercase()}")
                            )
                        }
                    }

                    Text("Bit Depth / Encoding", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AudioBitDepth.entries.forEach { bd ->
                            FilterChip(
                                selected = settings.bitDepth == bd,
                                onClick = { viewModel.setBitDepth(bd) },
                                label = { Text(bd.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedAmber.copy(alpha = 0.25f),
                                    selectedLabelColor = LedAmber
                                ),
                                modifier = Modifier.testTag("chip_bit_${bd.name.lowercase()}")
                            )
                        }
                    }

                    Text("Buffer Size (Frames)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BufferFramesOption.entries.forEach { bf ->
                            FilterChip(
                                selected = settings.bufferFramesOption == bf,
                                onClick = { viewModel.setBufferFramesOption(bf) },
                                label = { Text(bf.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedEmerald.copy(alpha = 0.25f),
                                    selectedLabelColor = LedEmerald
                                ),
                                modifier = Modifier.testTag("chip_buf_${bf.name.lowercase()}")
                            )
                        }
                    }

                    Text("Audio Input Channel & Test File", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = pedalState.audioSourceMode == AudioSourceMode.LIVE_MIC,
                            onClick = { onSwitchSourceMode(AudioSourceMode.LIVE_MIC) },
                            leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("เสียงสด (Live Mic/USB)", style = MaterialTheme.typography.labelMedium) }
                        )
                        FilterChip(
                            selected = pedalState.audioSourceMode == AudioSourceMode.TEST_FILE,
                            onClick = { onSwitchSourceMode(AudioSourceMode.TEST_FILE) },
                            leadingIcon = { Icon(Icons.Default.AudioFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            label = { Text("ไฟล์เสียงทดสอบ", style = MaterialTheme.typography.labelMedium) }
                        )
                        OutlinedButton(
                            onClick = onPickTestAudioFile,
                            border = BorderStroke(1.dp, StagePanelBorder),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("นำเข้าไฟล์เสียง (WAV/MP3/M4A)", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (telemetry.testFileName != null) {
                        Text(
                            text = "Loaded test clip: ${telemetry.testFileName} (${String.format("%.1fs", telemetry.testFileDurationSec)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = LedAmber
                        )
                    }
                }
            }

            // Auto Key Detector & Reference Pitch Tuning Card
            Surface(
                color = StageRackSurface,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, LedEmerald.copy(alpha = 0.5f))
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
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = LedEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "AUTO KEY DETECTOR TUNING",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "ตั้งค่าความไวในการจับคีย์เพลง มาตรฐานความถี่ A4 และการแปลงค่าคีย์ Minor",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.setReferenceA4Hz(440f) },
                            border = BorderStroke(1.dp, StagePanelBorder),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("btn_reset_a4_440")
                        ) {
                            Text("A4 = 440Hz", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Text("Detection Speed & Hold Stability", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        KeyDetectionSpeed.entries.forEach { speed ->
                            FilterChip(
                                selected = settings.keyDetectionSpeed == speed,
                                onClick = { viewModel.setKeyDetectionSpeed(speed) },
                                label = { Text(speed.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedEmerald.copy(alpha = 0.22f),
                                    selectedLabelColor = LedEmerald
                                ),
                                modifier = Modifier.testTag("chip_speed_${speed.name.lowercase()}")
                            )
                        }
                    }
                    Text(
                        text = settings.keyDetectionSpeed.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Text("Minor Key Mapping → WS Key Number (0..11)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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
                                    selectedContainerColor = LedBlue.copy(alpha = 0.24f),
                                    selectedLabelColor = Color.White
                                ),
                                modifier = Modifier.testTag("chip_minor_${mapping.name.lowercase()}")
                            )
                        }
                    }
                    Text(
                        text = settings.minorKeyMapping.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )

                    PsolaParamSlider(
                        title = "Reference Tuning (A4 Calibration สำหรับดนตรีตั้งสายต่ำ/สูง)",
                        valueText = String.format("%.1f Hz", settings.referenceA4Hz),
                        value = settings.referenceA4Hz,
                        valueRange = 430f..450f,
                        enabled = true,
                        accentColor = LedEmerald,
                        testTag = "slider_ref_a4_hz",
                        onValueChange = { viewModel.setReferenceA4Hz(it) }
                    )

                    PsolaParamSlider(
                        title = "Min Key Confidence Threshold (ขั้นต่ำก่อนส่งเข้า WS)",
                        valueText = "${(settings.minConfidenceToSend * 100f).roundToInt()}%",
                        value = settings.minConfidenceToSend,
                        valueRange = 0.25f..0.80f,
                        enabled = true,
                        accentColor = LedAmber,
                        testTag = "slider_min_confidence",
                        onValueChange = { viewModel.setMinConfidenceToSend(it) }
                    )

                    SettingToggleRow(
                        title = "Auto-Send WS on Key Lock",
                        subtitle = "ส่งคำสั่ง \"ch 1 harmony <0..11> ...\" ออกทาง WebSocket อัตโนมัติเมื่อคีย์เปลี่ยน",
                        checked = settings.autoSendWsOnKeyDetect,
                        onCheckedChange = { viewModel.setAutoSendWsOnKeyDetect(it) }
                    )
                    SettingToggleRow(
                        title = "Send Current Key on WS Connect",
                        subtitle = "ส่งคีย์ที่ล็อกไว้ล่าสุดทันทีเมื่อเชื่อมต่อกับ Server สำเร็จ",
                        checked = settings.sendOnWsConnect,
                        onCheckedChange = { viewModel.setSendOnWsConnect(it) }
                    )
                }
            }

            // USB Audio Interface & Routing Card
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
                                text = "AUDIO INTERFACE & HARDWARE ROUTING",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = "Auto-detects USB Audio Interfaces for stage XLR/Line routing",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.refreshAudioDevices() },
                            border = BorderStroke(1.dp, StagePanelBorder),
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text("Auto-Buffer Multiplier (when Buffer = Auto)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LatencyProfile.entries.forEach { profile ->
                            FilterChip(
                                selected = settings.latencyProfile == profile,
                                onClick = { viewModel.setLatencyProfile(profile) },
                                label = { Text(profile.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedEmerald.copy(alpha = 0.25f),
                                    selectedLabelColor = LedEmerald
                                )
                            )
                        }
                    }
                    Text(
                        text = settings.latencyProfile.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Text("Input Device (Mic / USB Interface)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    DeviceSelectorChips(
                        devices = inputDevices,
                        selectedId = settings.preferredInputDeviceId,
                        onSelect = { viewModel.setPreferredInputDevice(it) },
                        isInput = true
                    )

                    Text("USB Stereo Input Channel (สำหรับ Interface 2-Ch เช่น AudioBox iTwo)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        UsbInputChannel.entries.forEach { ch ->
                            FilterChip(
                                selected = settings.usbInputChannel == ch,
                                onClick = { viewModel.setUsbInputChannel(ch) },
                                label = { Text(ch.label, style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LedEmerald.copy(alpha = 0.22f),
                                    selectedLabelColor = LedEmerald
                                ),
                                modifier = Modifier.testTag("chip_usb_ch_${ch.name.lowercase()}")
                            )
                        }
                    }

                    Text("Output Device (PA / USB Line Out)", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    DeviceSelectorChips(
                        devices = outputDevices,
                        selectedId = settings.preferredOutputDeviceId,
                        onSelect = { viewModel.setPreferredOutputDevice(it) },
                        isInput = false
                    )

                    Surface(
                        color = StagePanelElevated,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (usbDiagnostic.audioEndpointMounted) LedEmerald.copy(alpha = 0.5f) else StagePanelBorder
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = if (usbDiagnostic.audioEndpointMounted) LedEmerald else LedAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = usbDiagnostic.summary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (usbDiagnostic.audioEndpointMounted) LedEmerald else LedAmber
                                )
                            }
                            Text(
                                text = "หมายเหตุ AudioBox iTwo / USB Interface: Android ใช้มาตรฐาน UAC1/UAC2 (ไม่ใช้ ASIO ของ Windows) · เปิด Mix Knob ไปทาง Playback หรือปิด Direct Monitor หากฟังผ่านฮาร์ดแวร์ · หากเปิด +48V Phantom แนะนำต่อผ่าน USB Hub มีไฟเลี้ยง",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Stage Noise Gate Threshold", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            Text(
                                text = "${settings.noiseGateDb.roundToInt()} dB",
                                style = MaterialTheme.typography.labelLarge,
                                color = LedAmber
                            )
                        }
                        Slider(
                            value = settings.noiseGateDb,
                            onValueChange = { viewModel.setNoiseGateDb(it) },
                            valueRange = -70f..-20f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = LedAmber
                            )
                        )
                    }

                    SettingToggleRow(
                        title = "Force Fast-Track Low-Latency Path",
                        subtitle = "Bypass OEM mixer FX & force double-burst output buffer (fixes ~80ms HyperOS/Snapdragon buffer lock)",
                        checked = settings.forceLowLatencyPath,
                        onCheckedChange = { viewModel.setForceLowLatencyPath(it) }
                    )
                    SettingToggleRow(
                        title = "Live Mic Monitor Out",
                        subtitle = "เปิด/ปิดการส่งเสียงไมค์ออกลำโพงขณะจับคีย์สด (แนะนำปิดไว้เพื่อกันเสียงวนหอน)",
                        checked = settings.directMonitor,
                        onCheckedChange = { viewModel.setDirectMonitor(it) }
                    )
                    SettingToggleRow(
                        title = "Background Stage Foreground Service",
                        subtitle = "Keep live key detection and WebSocket running when screen dims",
                        checked = settings.keepAliveService,
                        onCheckedChange = { viewModel.setKeepAliveService(it) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceSelectorChips(
    devices: List<AudioEndpointInfo>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    isInput: Boolean
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Auto (Prefer USB)", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = LedBlue.copy(alpha = 0.25f),
                selectedLabelColor = Color.White
            )
        )
        devices.forEach { dev ->
            FilterChip(
                selected = selectedId == dev.id,
                onClick = { onSelect(dev.id) },
                label = {
                    Text(
                        text = "${dev.name} (${dev.typeLabel})",
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = when {
                            dev.isUsb -> Icons.Default.Usb
                            isInput -> Icons.Default.Mic
                            else -> Icons.Default.Speaker
                        },
                        contentDescription = null,
                        tint = if (dev.isUsb) LedEmerald else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun PsolaParamSlider(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    accentColor: Color,
    testTag: String,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) TextSecondary else TextSecondary.copy(alpha = 0.45f),
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) accentColor else TextSecondary.copy(alpha = 0.45f)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Color.White else TextSecondary,
                activeTrackColor = accentColor,
                inactiveTrackColor = StagePanelBorder
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
