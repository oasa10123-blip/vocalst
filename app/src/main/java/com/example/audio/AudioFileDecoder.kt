package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.math.min

data class DecodedAudioClip(
    val displayName: String,
    val sampleRate: Int,
    val monoSamples: FloatArray
) {
    val durationSec: Float
        get() = if (sampleRate > 0) monoSamples.size.toFloat() / sampleRate else 0f
}

/**
 * Decodes user-selected audio files (WAV, MP3, M4A, AAC, FLAC, OGG) via Storage Access Framework Uri
 * into normalized mono float PCM for real-time vocal harmonizer DSP testing.
 */
object AudioFileDecoder {

    private const val MAX_SAMPLES_CAP = 48000 * 180 // Up to 3 minutes mono to prevent OOM

    suspend fun decodeUri(context: Context, uri: Uri): DecodedAudioClip = withContext(Dispatchers.IO) {
        val displayName = queryDisplayName(context, uri) ?: "Imported Audio"
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            require(audioTrackIndex >= 0 && trackFormat != null) {
                "No audio track found in selected file"
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = if (trackFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val collected = ArrayList<Float>(sampleRate * 10)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && collected.size < MAX_SAMPLES_CAP) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            channels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floatBuf = outBuf.asFloatBuffer()
                                val totalFloats = floatBuf.remaining()
                                val frameCount = totalFloats / channels
                                val temp = FloatArray(totalFloats)
                                floatBuf.get(temp)
                                val limit = min(frameCount, MAX_SAMPLES_CAP - collected.size)
                                for (f in 0 until limit) {
                                    var sum = 0f
                                    val base = f * channels
                                    for (c in 0 until channels) sum += temp[base + c]
                                    collected.add((sum / channels).coerceIn(-1f, 1f))
                                }
                            } else {
                                val shortBuf = outBuf.asShortBuffer()
                                val totalShorts = shortBuf.remaining()
                                val frameCount = totalShorts / channels
                                val temp = ShortArray(totalShorts)
                                shortBuf.get(temp)
                                val limit = min(frameCount, MAX_SAMPLES_CAP - collected.size)
                                val inv = 1f / 32768f
                                for (f in 0 until limit) {
                                    var sum = 0f
                                    val base = f * channels
                                    for (c in 0 until channels) sum += temp[base + c] * inv
                                    collected.add((sum / channels).coerceIn(-1f, 1f))
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            require(collected.isNotEmpty()) { "Decoded audio file is empty" }
            DecodedAudioClip(
                displayName = displayName,
                sampleRate = sampleRate,
                monoSamples = collected.toFloatArray()
            )
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }
}
