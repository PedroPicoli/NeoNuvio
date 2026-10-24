package com.nuvio.tv.core.dub

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utilitário de alta performance que decodifica uma pequena janela inicial de áudio (20s a 30s)
 * de ambas as fontes via [MediaExtractor] e [MediaCodec] para encontrar a correlação M&E (L-R)
 * e o alinhamento de milissegundo real.
 */
object AcousticProbeHelper {

    private const val TAG = "AcousticProbeHelper"
    private const val PROBE_DURATION_US = 25_000_000L // 25 segundos iniciais
    private const val PROBE_TIMEOUT_MS = 3_500L

    suspend fun matchAcousticOffset(
        videoUrl: String,
        videoHeaders: Map<String, String>,
        dubAudioUrl: String,
        dubAudioHeaders: Map<String, String>
    ): AcousticSyncMatcher.AcousticMatch? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runCatching {
                val origPcm = extractInitialPcm(videoUrl, videoHeaders) ?: return@runCatching null
                val dubPcm = extractInitialPcm(dubAudioUrl, dubAudioHeaders) ?: return@runCatching null

                val origEnv = AcousticSyncMatcher.extractSideEnvelope(
                    origPcm.samples,
                    origPcm.channels,
                    origPcm.sampleRate
                )
                val dubEnv = AcousticSyncMatcher.extractSideEnvelope(
                    dubPcm.samples,
                    dubPcm.channels,
                    dubPcm.sampleRate
                )

                // 1. Correlação Cruzada dos envelopes M&E (música e efeitos)
                val match = AcousticSyncMatcher.findCrossCorrelationLag(origEnv, dubEnv)

                // 2. Detecção de silêncio de corte
                val origSilences = AcousticSyncMatcher.detectSilenceIntervals(origEnv)
                val dubSilences = AcousticSyncMatcher.detectSilenceIntervals(dubEnv)
                val silenceDelta = AcousticSyncMatcher.estimateIntroSilenceDelta(origSilences, dubSilences)

                if (match != null) {
                    Log.i(
                        TAG,
                        "ACOUSTIC MATCH encontrado: offset=${match.offsetMs}ms confiança=${match.confidence} " +
                            "silenceDelta=${silenceDelta}ms"
                    )
                    match.copy(silenceOffsetMs = silenceDelta)
                } else if (silenceDelta != null) {
                    Log.i(TAG, "ACOUSTIC MATCH por silêncio de abertura: ${silenceDelta}ms")
                    AcousticSyncMatcher.AcousticMatch(
                        offsetMs = silenceDelta,
                        confidence = 0.85f,
                        silenceOffsetMs = silenceDelta
                    )
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    private data class PcmData(
        val samples: ShortArray,
        val channels: Int,
        val sampleRate: Int
    )

    private fun extractInitialPcm(url: String, headers: Map<String, String>): PcmData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            val effectiveHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                    effectiveHeaders[k] = v
                }
            }
            if (effectiveHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                effectiveHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Android TV)"
            }
            extractor.setDataSource(url, effectiveHeaders)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }
            if (audioTrackIndex < 0 || audioFormat == null) return null

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000

            extractor.selectTrack(audioTrackIndex)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            val shortList = ArrayList<ShortArray>()
            var totalShorts = 0
            var isEos = false

            val maxSamples = sampleRate * channels * 25 // até 25 segundos

            while (!isEos && totalShorts < maxSamples) {
                val inIndex = codec.dequeueInputBuffer(10_000L)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > PROBE_DURATION_US) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val chunk = ShortArray(shortBuf.remaining())
                        shortBuf.get(chunk)
                        shortList.add(chunk)
                        totalShorts += chunk.size
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEos) {
                    break
                }
            }

            if (totalShorts == 0) return null
            val merged = ShortArray(totalShorts)
            var offset = 0
            for (arr in shortList) {
                System.arraycopy(arr, 0, merged, offset, arr.size)
                offset += arr.size
            }

            return PcmData(merged, channels, sampleRate)
        } catch (e: Exception) {
            Log.w(TAG, "extractInitialPcm falhou para $url: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
