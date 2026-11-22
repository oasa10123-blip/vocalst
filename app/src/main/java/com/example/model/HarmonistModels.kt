package com.example.model

enum class MusicalKey(val displayName: String, val semitoneOffset: Int) {
    C("C", 0),
    C_SHARP("C#", 1),
    D("D", 2),
    D_SHARP("D#", 3),
    E("E", 4),
    F("F", 5),
    F_SHARP("F#", 6),
    G("G", 7),
    G_SHARP("G#", 8),
    A("A", 9),
    A_SHARP("A#", 10),
    B("B", 11);

    companion object {
        fun fromIndex(index: Int): MusicalKey {
            val wrapped = ((index % 12) + 12) % 12
            return entries[wrapped]
        }

        fun fromString(raw: String): MusicalKey? {
            val cleaned = raw.trim().uppercase()
                .replace("DB", "C#")
                .replace("EB", "D#")
                .replace("GB", "F#")
                .replace("AB", "G#")
                .replace("BB", "A#")
            return entries.firstOrNull {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.displayName.equals(cleaned, ignoreCase = true)
            }
        }
    }
}

enum class MusicalScale(val displayName: String, val shortLabel: String, val intervals: IntArray) {
    MAJOR("Major", "Maj", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
    MINOR("Minor", "Min", intArrayOf(0, 2, 3, 5, 7, 8, 10));

    companion object {
        fun fromString(raw: String): MusicalScale? {
            val cleaned = raw.trim().uppercase()
            return when {
                cleaned == "MAJOR" || cleaned == "MAJ" -> MAJOR
                cleaned == "MINOR" || cleaned == "MIN" || cleaned == "M" -> MINOR
                else -> null
            }
        }
    }
}

enum class KeyDetectionSpeed(
    val label: String,
    val subtitle: String,
    val decay: Float,
    val holdFrames: Int
) {
    FAST("Fast (~2s)", "จับคีย์ไว เหมาะกับเมโลดี้เดี่ยว / เปลี่ยนท่อนเร็ว", 0.978f, 10),
    BALANCED("Balanced (~3.5s)", "จำชุดคอร์ดกีต้าร์ I-V-vi-IV นิ่ง แม่นยำสุด", 0.986f, 22),
    STABLE("Stage Lock (~6s)", "ล็อกคีย์ทั้งเพลง กันคอร์ดผ่าน/โซโล่หลอก", 0.992f, 32)
}

enum class MinorKeyMapping(
    val label: String,
    val description: String
) {
    RELATIVE_MAJOR(
        "Relative Major (Am → 0:C)",
        "แปลงคีย์ไมเนอร์เป็นคีย์เมเจอร์คู่ขนาน (เช่น Am ใช้สเกล C = เลข 0) ให้เสียงประสานไม่เพี้ยน"
    ),
    DIRECT_ROOT(
        "Direct Root (Am → 9:A)",
        "ส่งตัวเลขของโน้ตหลักคีย์นั้นตรง ๆ (C=0, C#=1, ..., A=9, B=11)"
    )
}

data class KeyCandidate(
    val key: MusicalKey,
    val scale: MusicalScale,
    val scorePct: Int
)

data class KeyDispatchStatus(
    val effectiveKey: MusicalKey = MusicalKey.C,
    val effectiveKeyIndex: Int = 0,
    val detectedSourceLabel: String = "รอสัญญาณเสียง...",
    val formattedCommand: String = "ch 1 harmony 0 0 0 0 100 2 100 3 80 1000 100 0 0 500 1 1",
    val lastSentCommand: String? = null,
    val lastSentKeyIndex: Int? = null,
    val lastSentTimeMs: Long = 0L,
    val txCount: Int = 0,
    val fullFileKey: MusicalKey? = null,
    val fullFileScale: MusicalScale = MusicalScale.MAJOR,
    val fullFileConfidence: Float = 0f,
    val waitingReason: String? = null
)

// Retained compatibility enums for existing Room rules & DSP theory helpers
enum class HarmonyType(
    val shortLabel: String,
    val titleTh: String,
    val description: String,
    val ledMask: Int
) {
    OCT_DOWN("OCT-", "ต่ำ 1 อ็อกเทฟ (-12 st)", "Deep octave below lead vocal", 0b1000000),
    LOWER_5TH("LOW 5", "คู่ 5 ต่ำ (-5th)", "Lower fifth harmony voice", 0b0100000),
    LOW_3RD("LOW 3", "คู่ 3 ต่ำ (-3rd)", "Warm diatonic 3rd below", 0b0010000),
    UNISON("UNISON", "ดับเบิลหนา (Double)", "Rich chorus doubling & micro-pitch", 0b0001000),
    HIGH_3RD("HIGH 3", "คู่ 3 สูง (+3rd)", "Classic diatonic 3rd above", 0b0000100),
    HIGHER_5TH("HIGH 5", "คู่ 5 สูง (+5th)", "Bright diatonic 5th above", 0b0000010),
    HIGH_AND_LOW("3+3", "คู่บน+ล่าง (±3rd)", "Dual harmony: 3rd above & 3rd below", 0b0010100),
    THIRD_AND_FIFTH("3+5", "ประสาน 2 เสียง (+3/+5)", "Dual upper choir: 3rd + 5th above", 0b0000110),
    OCT_UP("OCT+", "สูง 1 อ็อกเทฟ (+12 st)", "Octave shimmer above lead", 0b0000001);

    companion object {
        fun fromString(raw: String): HarmonyType? {
            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.shortLabel.equals(raw.trim(), ignoreCase = true)
            }
        }
    }
}

enum class EchoMode(val label: String) {
    OFF("OFF"),
    REVERB("REVERB"),
    REV_DLY("REV+DLY"),
    DELAY("DELAY");

    companion object {
        fun fromString(raw: String): EchoMode? {
            return entries.firstOrNull {
                it.name.equals(raw.trim(), ignoreCase = true) ||
                    it.label.equals(raw.trim(), ignoreCase = true)
            }
        }
    }
}

enum class LatencyProfile(val label: String, val burstMultiplier: Int, val summary: String) {
    ULTRA_LOW_1X("Ultra-Low 1x", 1, "Minimum buffer for fast detection & monitoring"),
    BALANCED_2X("Balanced 2x", 2, "Low latency with extra glitch headroom"),
    SAFE_4X("Safe 4x", 4, "Maximum stability on phone microphones")
}

enum class SampleRateOption(val label: String, val hz: Int?) {
    AUTO_NATIVE("Auto (Native)", null),
    HZ_44100("44.1 kHz", 44100),
    HZ_48000("48 kHz", 48000),
    HZ_96000("96 kHz", 96000)
}

enum class AudioBitDepth(val label: String, val shortLabel: String) {
    PCM_16BIT("16-bit PCM", "16-bit"),
    PCM_FLOAT_32BIT("32-bit Float (Hi-Res)", "32f-bit")
}

enum class BufferFramesOption(val label: String, val frames: Int?) {
    AUTO_NATIVE("Auto", null),
    FRAMES_64("64 fr", 64),
    FRAMES_96("96 fr", 96),
    FRAMES_128("128 fr", 128),
    FRAMES_256("256 fr", 256),
    FRAMES_512("512 fr", 512),
    FRAMES_1024("1024 fr", 1024)
}

enum class AudioSourceMode(val label: String) {
    LIVE_MIC("Live Mic / USB"),
    TEST_FILE("Test Audio File")
}

enum class UsbInputChannel(val label: String, val shortLabel: String) {
    MIX_1_2("Ch 1+2 (Auto Sum)", "Ch 1+2"),
    CH_1_LEFT("Input 1 Only (L)", "Ch 1"),
    CH_2_RIGHT("Input 2 Only (R)", "Ch 2")
}

enum class WsOperationMode(val label: String) {
    CLIENT("WS Client (ส่งเข้า Server)"),
    SERVER("WS Server (เปิดพอร์ต)")
}

enum class WsClientConnectionState(val label: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    RECONNECTING("Reconnecting..."),
    ERROR("Connection Error")
}

enum class MatchMode(val label: String) {
    EXACT_IGNORE_CASE("Exact (Ignore Case)"),
    EXACT_CASE("Exact (Case Sensitive)"),
    CONTAINS("Contains Substring")
}

enum class BypassAction(val label: String) {
    NONE("No Change"),
    HARMONY_ON("Harmony ON"),
    HARMONY_OFF("Harmony OFF"),
    HARMONY_TOGGLE("Toggle Harmony"),
    FULL_BYPASS_ON("Full Bypass ON"),
    FULL_BYPASS_OFF("Full Bypass OFF"),
    FULL_BYPASS_TOGGLE("Toggle Full Bypass")
}

enum class RuleCategory(val label: String) {
    KEY("Key / Scale"),
    MODE("Harmony Mode"),
    FX("FX / Bypass"),
    PRESET("Stage Preset")
}

data class PedalState(
    val key: MusicalKey = MusicalKey.C,
    val scale: MusicalScale = MusicalScale.MAJOR,
    val harmonyType: HarmonyType = HarmonyType.HIGH_3RD,
    val balance: Float = 0.52f,
    val echoMode: EchoMode = EchoMode.REV_DLY,
    val echoMix: Float = 0.28f,
    val micGainDb: Float = 2.0f,
    val noiseGateDb: Float = -56f,
    val enhanceEnabled: Boolean = true,
    val variationEnabled: Boolean = false,
    val harmonyActive: Boolean = true,
    val fullBypass: Boolean = false,
    val autoHarmonistEnabled: Boolean = true,
    val activePresetName: String = "Stage Default",
    val latencyProfile: LatencyProfile = LatencyProfile.ULTRA_LOW_1X,
    val directMonitor: Boolean = false,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.LIVE_MIC,
    val tdPsolaEnabled: Boolean = true,
    val psolaPitchSmoothing: Float = 0.60f,
    val psolaGrainOverlap: Float = 1.05f,
    val psolaPeakSearchRatio: Float = 0.30f,
    val psolaUnvoicedDamping: Float = 0.35f,
    val psolaNoteHysteresis: Float = 0.65f,
    val forceLowLatencyPath: Boolean = true,
    val usbInputChannel: UsbInputChannel = UsbInputChannel.MIX_1_2,
    val keyDetectionSpeed: KeyDetectionSpeed = KeyDetectionSpeed.BALANCED,
    val minorKeyMapping: MinorKeyMapping = MinorKeyMapping.RELATIVE_MAJOR,
    val referenceA4Hz: Float = 440f,
    val autoSendWsOnKeyDetect: Boolean = true,
    val manualOverrideKey: MusicalKey? = null
)

data class AudioTelemetry(
    val isRunning: Boolean = false,
    val inputRmsDb: Float = -60f,
    val inputPeakDb: Float = -60f,
    val isPeakClipping: Boolean = false,
    val peakClipCount: Int = 0,
    val harmonyRmsDb: Float = -60f,
    val outputRmsDb: Float = -60f,
    val outputPeakDb: Float = -60f,
    val detectedFreqHz: Float = 0f,
    val detectedMidiNote: Float = 0f,
    val detectedNoteName: String = "--",
    val harmonyNoteNames: String = "--",
    val activeSemitoneOffsets: List<Int> = emptyList(),
    // Musical Key Detection Telemetry
    val hasLockedKey: Boolean = false,
    val detectedKey: MusicalKey = MusicalKey.C,
    val detectedScale: MusicalScale = MusicalScale.MAJOR,
    val liveCandidateKey: MusicalKey = MusicalKey.C,
    val liveCandidateScale: MusicalScale = MusicalScale.MAJOR,
    val keyConfidence: Float = 0f,
    val signalActive: Boolean = false,
    val liveChordLabel: String = "--",
    val recentChords: List<String> = emptyList(),
    val chromaBins: List<Float> = List(12) { 0f },
    val topKeyCandidates: List<KeyCandidate> = emptyList(),
    // Stream & Hardware Telemetry
    val sampleRate: Int = 48000,
    val bitDepthLabel: String = "16-bit",
    val framesPerBuffer: Int = 128,
    val inputBufferCapacityFrames: Int = 0,
    val outputBufferFrames: Int = 0,
    val outputBufferCapacityFrames: Int = 0,
    val inputLatencyMs: Float = 0f,
    val dspProcessMs: Float = 0f,
    val dspMaxProcessMs: Float = 0f,
    val outputLatencyMs: Float = 0f,
    val totalLatencyMs: Float = 0f,
    val measuredHardwareLatencyMs: Float = 0f,
    val audioPathLabel: String = "Standby",
    val audioApiLabel: String = "Standby",
    val nativeEngineActive: Boolean = false,
    val hardwareBurstFrames: Int = 0,
    val xrunCount: Int = 0,
    val isLowLatencyPath: Boolean = false,
    val isUsbInterfaceActive: Boolean = false,
    val isBluetoothOutput: Boolean = false,
    val inputChannelCount: Int = 1,
    val inputDeviceName: String = "Standby",
    val outputDeviceName: String = "Standby",
    val statusMessage: String = "Ready for automatic key detection",
    val waveformInput: List<Float> = List(64) { 0f },
    val waveformOutput: List<Float> = List(64) { 0f },
    val audioSourceMode: AudioSourceMode = AudioSourceMode.LIVE_MIC,
    val testFileName: String? = null,
    val testFileDurationSec: Float = 0f,
    val testFileProgress: Float = 0f,
    val testFilePlaying: Boolean = true
)

data class AudioEndpointInfo(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val isUsb: Boolean,
    val isInput: Boolean,
    val maxChannels: Int,
    val supportedSampleRates: List<Int>
)

data class WsServerStatus(
    val mode: WsOperationMode = WsOperationMode.CLIENT,
    val isRunning: Boolean = false,
    val port: Int = 4444,
    val localUrls: List<String> = emptyList(),
    val connectedClients: Int = 0,
    val clientServerUrl: String = "ws://192.168.1.230:8080/ws",
    val clientState: WsClientConnectionState = WsClientConnectionState.DISCONNECTED,
    val lastError: String? = null
)

data class WsLogEntry(
    val id: Long,
    val timestampMs: Long,
    val clientAddress: String,
    val rawMessage: String,
    val matchedRuleName: String?,
    val appliedSummary: String,
    val matched: Boolean,
    val isOutgoing: Boolean = false
)
