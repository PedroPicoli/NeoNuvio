package com.nuvio.tv.core.dub

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Utilitário de alta performance para extração acústica universal.
 *
 * Arquitetura em Camadas:
 * 1. Ancoragem Absoluta por Timestamp (presentationTimeUs em array de 4000 bins = 0s..40s).
 * 2. Seek Rápido (8s com janela de até 15s sem download integral).
 * 3. Camada A: Decodificação PCM via MediaCodec em loop multi-track com cancelamento de voz (Surround 5.1/7.1 sem canal central, Estéreo Side+Mid notch)
 *    e geração simultânea do vetor vocal centralizado (Band-Pass 300Hz-2800Hz) para Cross-Modal Sub-to-Dub VAD.
 * 4. Camada B: Bitstream Frame Energy Parser para formatos de cinema sem MediaCodec nativo (DTS, DTS-HD, TrueHD),
 *    utilizando Entropia de Shannon / Variância dos Bytes dos sub-blocos + tamanho do frame.
 * 5. Extrator de Software do Media3 (MatroskaExtractor) como fallback universal quando o Stagefright omitir faixas do container.
 * 6. Telemetria diagnóstica detalhada por fonte.
 */
object AcousticProbeHelper {

    private const val TAG = "AcousticProbeHelper"
    private const val TOTAL_BINS = 4500 // 45 segundos com resolução de 10ms (100 Hz = 4500 bins)
    private const val SOURCE_READ_TIMEOUT_MS = 3_200L // Máximo 3.2s de leitura contínua por fonte (relógio real)
    private const val SAMPLE_WINDOW_US = 22_000_000L // 22s de amostras contínuas desde 0.0s (suficiente e ultra-rápido)

    @Volatile
    private var cachedVideoUrl: String? = null
    @Volatile
    private var cachedVideoData: ExtractedAudioData? = null

    @Volatile
    var lastExtractedVideoEmbeddedCues: List<SubtitleSyncCue> = emptyList()
    @Volatile
    var lastExtractedVideoEmbeddedLanguage: String? = null

    fun clearCache() {
        cachedVideoUrl = null
        cachedVideoData = null
        lastExtractedVideoEmbeddedCues = emptyList()
        lastExtractedVideoEmbeddedLanguage = null
    }

    /**
     * Procura um decodificador de áudio por software (CPU).
     */
    fun findSoftwareAudioDecoder(mime: String): String? {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (codecInfo.isEncoder) continue
                val types = codecInfo.supportedTypes ?: continue
                if (!types.any { it.equals(mime, ignoreCase = true) }) continue

                val name = codecInfo.name.lowercase()

                val isKnownHardwareVendor = name.contains("amlogic") ||
                    name.contains("mediatek") ||
                    name.contains("mtk") ||
                    name.contains("qcom") ||
                    name.contains("realtek") ||
                    name.contains("rtk") ||
                    name.contains("hisi") ||
                    name.contains("exynos") ||
                    name.contains("nvidia") ||
                    name.contains(".hw.") ||
                    name.contains("hardware")

                if (isKnownHardwareVendor) continue

                val isAndroidSoftwareNaming = name.startsWith("c2.android.") ||
                    name.startsWith("omx.google.")

                val isApiSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    codecInfo.isSoftwareOnly
                } else {
                    false
                }

                if (isAndroidSoftwareNaming || isApiSoftware) {
                    Log.d(TAG, "Decodificador de software selecionado para $mime: ${codecInfo.name}")
                    return codecInfo.name
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Retorna o decodificador apropriado com suporte a DTS Core fallback.
     */
    private fun createDecoderWithFallback(mime: String): Pair<MediaCodec?, String> {
        val swName = findSoftwareAudioDecoder(mime)
        if (!swName.isNullOrBlank()) {
            val swCodec = runCatching { MediaCodec.createByCodecName(swName) }.getOrNull()
            if (swCodec != null) return Pair(swCodec, "Software ($swName)")
        }

        val defaultCodec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull()
        if (defaultCodec != null) {
            return Pair(defaultCodec, "Default (${defaultCodec.name})")
        }

        // Se for DTS-HD e não houver decoder direto, tenta instanciar decoder para DTS Core
        if (mime.equals("audio/vnd.dts.hd", ignoreCase = true)) {
            val dtsCoreCodec = runCatching { MediaCodec.createDecoderByType("audio/vnd.dts") }.getOrNull()
            if (dtsCoreCodec != null) {
                return Pair(dtsCoreCodec, "DTS-Core (${dtsCoreCodec.name})")
            }
        }

        return Pair(null, "Sem Decoder")
    }

    /**
     * Calcula a entropia de Shannon e variância dos bytes nos primeiros 64 a 256 bytes de payload
     * de um frame comprimido de áudio (DTS, TrueHD, AC3, EAC3).
     */
    fun calculateByteEntropyAndVariance(bytes: ByteArray, length: Int): Pair<Double, Double> {
        val n = minOf(length, bytes.size, 256)
        if (n <= 16) return Pair(0.0, 0.0)

        // Pula syncwords comuns de 4 bytes se presentes
        val offset = if (n > 32 && (bytes[0].toInt() == 0x7F || bytes[0].toInt() == 0x0B || bytes[0].toInt() == 0xF8)) {
            4
        } else {
            0
        }
        val effLen = n - offset
        if (effLen <= 12) return Pair(0.0, 0.0)

        val counts = IntArray(256)
        var sum = 0.0
        for (i in offset until n) {
            val b = bytes[i].toInt() and 0xFF
            counts[b]++
            sum += b
        }
        val mean = sum / effLen

        var varianceSum = 0.0
        var entropy = 0.0
        val invN = 1.0 / effLen
        val log2 = kotlin.math.ln(2.0)

        for (c in counts) {
            if (c > 0) {
                val p = c * invN
                entropy -= p * (kotlin.math.ln(p) / log2)
            }
        }

        for (i in offset until n) {
            val b = bytes[i].toInt() and 0xFF
            val diff = b - mean
            varianceSum += diff * diff
        }
        val variance = varianceSum / effLen

        return Pair(entropy, variance)
    }

    data class ExtractedAudioData(
        val envelope: FloatArray,
        val mime: String,
        val channels: Int,
        val sampleRate: Int,
        val validBins: Int,
        val methodType: String = "PCM", // "PCM" ou "Bitstream"
        val statusMessage: String = "OK",
        val speechEnvelope: FloatArray? = null, // Vetor de atividade vocal 100 Hz (Cross-Modal VAD)
        val firstValidBin: Int = 0,
        val lastValidBin: Int = TOTAL_BINS - 1
    )

    private data class TrackCandidate(
        val index: Int,
        val mime: String,
        val format: MediaFormat,
        val priority: Int
    )

    data class ProbeResult(
        val data: ExtractedAudioData?,
        val diagStatus: String
    )

    /**
     * Varrer todas as faixas de áudio do container ordenadas por prioridade.
     */
    private fun getAudioTrackCandidates(extractor: MediaExtractor): List<TrackCandidate> {
        val list = mutableListOf<TrackCandidate>()
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mime.startsWith("audio/")) continue

            val priority = when {
                mime.equals("audio/mp4a-latm", ignoreCase = true) -> 100
                mime.equals("audio/opus", ignoreCase = true) -> 95
                mime.equals("audio/vorbis", ignoreCase = true) -> 90
                mime.equals("audio/flac", ignoreCase = true) -> 85
                mime.equals("audio/mpeg", ignoreCase = true) -> 80
                mime.equals("audio/eac3", ignoreCase = true) -> 75
                mime.equals("audio/ac3", ignoreCase = true) -> 70
                mime.equals("audio/vnd.dts", ignoreCase = true) -> 65
                mime.equals("audio/vnd.dts.hd", ignoreCase = true) -> 55
                mime.equals("audio/true-hd", ignoreCase = true) -> 30
                else -> 40
            }
            list.add(TrackCandidate(i, mime, format, priority))
        }
        return list.sortedByDescending { it.priority }
    }

    suspend fun matchAcousticOffset(
        videoUrl: String,
        videoHeaders: Map<String, String>,
        dubAudioUrl: String,
        dubAudioHeaders: Map<String, String>,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AcousticSyncMatcher.AcousticMatch? = withContext(Dispatchers.IO) {
        runCatching {
            onStatusUpdate?.invoke("Extraindo Envelopes de Áudio (Vídeo e Dublagem em Paralelo)...")

            // Execução Concorrente em Paralelo (async): dobra a velocidade sem risco de contenção de codec
            val (videoProbe, dubProbe) = coroutineScope {
                val videoDeferred = async {
                    if (videoUrl == cachedVideoUrl && cachedVideoData != null) {
                        val cached = cachedVideoData!!
                        val diag = "${cached.mime.substringAfter('/')}/${if (cached.methodType == "PCM") "${cached.channels}ch" else "Bitstream"}/${cached.validBins}b"
                        Log.d(TAG, "Usando envelope acústico de vídeo em cache: $diag")
                        ProbeResult(cached, diag)
                    } else {
                        val res = extractAdaptiveAudioEnvelope(videoUrl, videoHeaders, isVideo = true, onStatusUpdate)
                        if (res.data != null) {
                            cachedVideoUrl = videoUrl
                            cachedVideoData = res.data
                        }
                        res
                    }
                }

                val dubDeferred = async {
                    extractAdaptiveAudioEnvelope(dubAudioUrl, dubAudioHeaders, isVideo = false, onStatusUpdate)
                }

                videoDeferred.await() to dubDeferred.await()
            }

            val videoDiag = videoProbe.diagStatus
            val dubDiag = dubProbe.diagStatus

            if (videoProbe.data == null || dubProbe.data == null) {
                val failDiag = "[Vid: $videoDiag | Dub: $dubDiag]"
                Log.w(TAG, "Falha na extração acústica: $failDiag")
                onStatusUpdate?.invoke("Falha Acústica $failDiag")
                return@runCatching AcousticSyncMatcher.AcousticMatch(
                    offsetMs = 0L,
                    confidence = 0.50f,
                    maxZncc = 0f,
                    psr = 0f,
                    isValid = false,
                    telemetryDiag = failDiag,
                    dubSpeechEnvelope = dubProbe.data?.speechEnvelope,
                    dubMime = dubProbe.data?.mime
                )
            }

            val videoData = videoProbe.data
            val dubData = dubProbe.data

            onStatusUpdate?.invoke("Calculando correlação ZNCC e PSR ($videoDiag vs $dubDiag)...")

            // Preservação Estrita de Índices Temporais Absolutos:
            // O índice k representa o exato instante absoluto k * 10 ms desde o início do arquivo.
            // A janela M&E avalia o intervalo comum a partir do bin 500 (5.0s, ignorando vinhetas):
            val commonStartBin = maxOf(500, maxOf(videoData.firstValidBin, dubData.firstValidBin))
            val commonEndBin = minOf(3000, minOf(videoData.lastValidBin, dubData.lastValidBin))
            val overlapBins = maxOf(0, commonEndBin - commonStartBin)

            val hasSufficientData = (videoData.validBins >= 800 && dubData.validBins >= 800) || overlapBins >= 600

            if (!hasSufficientData) {
                val overlapSec = String.format(java.util.Locale.US, "%.1f", overlapBins * 10.0 / 1000.0)
                Log.w(TAG, "Sobreposição acústica M&E insuficiente ($overlapBins bins = ${overlapSec}s < 6.0s); descartando correlação M&E")
                val insufficientDiag = "[Vid: $videoDiag | Dub: $dubDiag | Sobreposição: ${overlapSec}s < 6.0s]"
                onStatusUpdate?.invoke("Falha Acústica: sobreposição temporal insuficiente ($insufficientDiag)")
                return@runCatching AcousticSyncMatcher.AcousticMatch(
                    offsetMs = 0L,
                    confidence = 0.50f,
                    maxZncc = 0f,
                    psr = 0f,
                    isValid = false,
                    telemetryDiag = insufficientDiag,
                    dubSpeechEnvelope = dubData.speechEnvelope,
                    dubMime = dubData.mime
                )
            }

            val effectiveStartMs = commonStartBin.toLong() * 10L
            val effectiveEndMs = commonEndBin.toLong() * 10L

            // 3. Correlação Cruzada ZNCC na janela útil com busca de lag de -5000ms a +5000ms
            val match = AcousticSyncMatcher.findCrossCorrelationLag(
                origEnvelope = videoData.envelope,
                dubEnvelope = dubData.envelope,
                windowMs = 10,
                skipInitialMs = effectiveStartMs,
                targetEndMs = effectiveEndMs,
                maxLagMs = 5_000L,
                videoDiag = videoDiag,
                dubDiag = dubDiag
            )

            // 4. Detecção de Bumper nos primeiros 10s para confirmação determinística
            // Proibição Absoluta em Bitstream: jamais executar em envelopes Bitstream comprimidos (!isPcmDecoded)
            val isVideoBitstream = videoData.methodType != "PCM"
            val bumperOffsetMs = if (!isVideoBitstream && dubData.methodType == "PCM") {
                AcousticSyncMatcher.estimateBumperOffsetMs(
                    videoEnvelope = videoData.envelope,
                    dubEnvelope = dubData.envelope,
                    isBitstream = isVideoBitstream
                )
            } else null
            if (bumperOffsetMs != null && match != null && abs(match.offsetMs - bumperOffsetMs) <= 350L) {
                Log.i(TAG, "Bumper Boundary confirmou o offset acústico M&E: bumperDelta=${bumperOffsetMs}ms vs match=${match.offsetMs}ms")
            }

            if (match != null) {
                Log.i(TAG, "Resultado Acústico: offset=${match.offsetMs}ms zncc=${match.maxZncc} psr=${match.psr} valid=${match.isValid}")
                match.copy(
                    silenceOffsetMs = bumperOffsetMs,
                    dubSpeechEnvelope = dubData.speechEnvelope,
                    dubMime = dubData.mime
                )
            } else {
                val fallbackDiag = "[Vid: $videoDiag | Dub: $dubDiag | ZNCC: 0.00 | PSR: 0.0 | BestLag: +0ms]"
                onStatusUpdate?.invoke("Falha Acústica: dados insuficientes para correlação $fallbackDiag")
                AcousticSyncMatcher.AcousticMatch(
                    offsetMs = 0L,
                    confidence = 0.50f,
                    maxZncc = 0f,
                    psr = 0f,
                    isValid = false,
                    silenceOffsetMs = bumperOffsetMs,
                    telemetryDiag = fallbackDiag,
                    dubSpeechEnvelope = dubData.speechEnvelope,
                    dubMime = dubData.mime
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "Falha em matchAcousticOffset", e)
            val errDiag = "[Erro: ${e.message ?: e.javaClass.simpleName}]"
            onStatusUpdate?.invoke("Falha Acústica $errDiag")
            AcousticSyncMatcher.AcousticMatch(
                offsetMs = 0L,
                confidence = 0.50f,
                maxZncc = 0f,
                psr = 0f,
                isValid = false,
                telemetryDiag = errDiag
            )
        }
    }

    /**
     * Extrai envelope com Nova Regra de Seek Rápido (8s), suporte Multi-Track MediaCodec (Camada A)
     * e fallback transparente para Bitstream Frame Energy Parser (Camada B).
     */
    private fun extractAdaptiveAudioEnvelope(
        url: String,
        headers: Map<String, String>,
        isVideo: Boolean,
        onStatusUpdate: ((String) -> Unit)?
    ): ProbeResult {
        val extractor = MediaExtractor()
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
            val startTime = android.os.SystemClock.elapsedRealtime()
            extractor.setDataSource(url, effectiveHeaders)

            if (isVideo) {
                runCatching {
                    val videos = mutableListOf<Map<String, Any?>>()
                    val audios = mutableListOf<Map<String, Any?>>()
                    val subs = mutableListOf<Map<String, Any?>>()
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        val trackInfo = mapOf<String, Any?>(
                            "track_index" to i,
                            "mime_type" to mime,
                            "language" to runCatching { format.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull(),
                            "channels" to runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull(),
                            "sample_rate" to runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull(),
                            "bitrate" to runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrNull(),
                            "width" to runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull(),
                            "height" to runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
                        )
                        when {
                            mime.startsWith("video/") -> videos.add(trackInfo)
                            mime.startsWith("audio/") -> audios.add(trackInfo)
                            mime.startsWith("text/") || mime.startsWith("subtitles/") || mime.contains("sub") -> subs.add(trackInfo)
                        }
                    }
                    DubDiagnosticFileLogger.setContainerTracks(videos, audios, subs)
                }
            }

            val candidates = getAudioTrackCandidates(extractor)
            if (candidates.isEmpty()) {
                Log.w(TAG, "android.media.MediaExtractor retornou 0 faixas de áudio. Acionando Extrator de Software do Media3 (MatroskaExtractor)...")
                val media3Result = extractAudioViaMedia3Matroska(url, effectiveHeaders, isVideo)
                if (media3Result != null) {
                    DubDiagnosticFileLogger.setExtractionTelemetry(
                        isVideo = isVideo,
                        extractor = "Media3 MatroskaExtractor",
                        mode = "Software_Matroska",
                        timeSpentMs = android.os.SystemClock.elapsedRealtime() - startTime,
                        firstPtsMs = media3Result.data?.firstValidBin?.toLong()?.times(10L) ?: 0L,
                        lastPtsMs = media3Result.data?.lastValidBin?.toLong()?.times(10L) ?: 0L,
                        totalBins = TOTAL_BINS,
                        filledBins = media3Result.data?.validBins ?: 0,
                        zeroBins = TOTAL_BINS - (media3Result.data?.validBins ?: 0),
                        envelope = media3Result.data?.envelope,
                        speechEnvelope = media3Result.data?.speechEnvelope
                    )
                    return media3Result
                }
                val diag = "Sem Faixa Áudio"
                Log.w(TAG, "$diag para $url")
                return ProbeResult(null, diag)
            }

            // ==========================================
            // CAMADA A: MediaCodec PCM em Loop Multi-Track
            // ==========================================
            for (candidate in candidates) {
                val pcmResult = tryDecodePcmCandidate(extractor, candidate, isVideo)
                if (pcmResult != null) {
                    val shortMime = candidate.mime.substringAfter('/')
                    val diag = "$shortMime/${pcmResult.channels}ch/${pcmResult.validBins}b"
                    Log.i(TAG, "Camada A (PCM) bem sucedida na track ${candidate.index}: $diag")
                    DubDiagnosticFileLogger.setExtractionTelemetry(
                        isVideo = isVideo,
                        extractor = "MediaExtractor + MediaCodec",
                        mode = "PCM_${candidate.mime.substringAfter('/')}",
                        timeSpentMs = android.os.SystemClock.elapsedRealtime() - startTime,
                        firstPtsMs = pcmResult.firstValidBin.toLong() * 10L,
                        lastPtsMs = pcmResult.lastValidBin.toLong() * 10L,
                        totalBins = TOTAL_BINS,
                        filledBins = pcmResult.validBins,
                        zeroBins = TOTAL_BINS - pcmResult.validBins,
                        envelope = pcmResult.envelope,
                        speechEnvelope = pcmResult.speechEnvelope
                    )
                    return ProbeResult(pcmResult, diag)
                }
            }

            // ==========================================
            // CAMADA B: Bitstream Frame Energy Parser
            // ==========================================
            val bestCandidate = candidates.first()
            val shortMime = bestCandidate.mime.substringAfter('/')
            Log.i(TAG, "MediaCodec PCM indisponível para todas as faixas. Acionando Camada B (Bitstream Parser) para $shortMime")
            val bitstreamResult = tryExtractBitstreamEnergy(extractor, bestCandidate)
            if (bitstreamResult != null) {
                val diag = "$shortMime(Bitstream)/${bitstreamResult.validBins}b"
                Log.i(TAG, "Camada B (Bitstream) bem sucedida: $diag")
                DubDiagnosticFileLogger.setExtractionTelemetry(
                    isVideo = isVideo,
                    extractor = "MediaExtractor",
                    mode = "Bitstream_VBR_${bestCandidate.mime.substringAfter('/')}",
                    timeSpentMs = android.os.SystemClock.elapsedRealtime() - startTime,
                    firstPtsMs = bitstreamResult.firstValidBin.toLong() * 10L,
                    lastPtsMs = bitstreamResult.lastValidBin.toLong() * 10L,
                    totalBins = TOTAL_BINS,
                    filledBins = bitstreamResult.validBins,
                    zeroBins = TOTAL_BINS - bitstreamResult.validBins,
                    envelope = bitstreamResult.envelope,
                    speechEnvelope = bitstreamResult.speechEnvelope
                )
                return ProbeResult(bitstreamResult, diag)
            }

            // Fallback final para o Extrator Media3
            val media3Fallback = extractAudioViaMedia3Matroska(url, effectiveHeaders, isVideo)
            if (media3Fallback != null) {
                DubDiagnosticFileLogger.setExtractionTelemetry(
                    isVideo = isVideo,
                    extractor = "Media3 MatroskaExtractor",
                    mode = "Software_Matroska_Fallback",
                    timeSpentMs = android.os.SystemClock.elapsedRealtime() - startTime,
                    firstPtsMs = media3Fallback.data?.firstValidBin?.toLong()?.times(10L) ?: 0L,
                    lastPtsMs = media3Fallback.data?.lastValidBin?.toLong()?.times(10L) ?: 0L,
                    totalBins = TOTAL_BINS,
                    filledBins = media3Fallback.data?.validBins ?: 0,
                    zeroBins = TOTAL_BINS - (media3Fallback.data?.validBins ?: 0),
                    envelope = media3Fallback.data?.envelope,
                    speechEnvelope = media3Fallback.data?.speechEnvelope
                )
                return media3Fallback
            }

            val failDiag = "$shortMime/Sem Dados"
            return ProbeResult(null, failDiag)

        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "Falha de extração via MediaExtractor para $url: $msg. Tentando Media3 MatroskaExtractor...", e)
            val media3Fallback = extractAudioViaMedia3Matroska(url, headers, isVideo)
            if (media3Fallback != null) {
                return media3Fallback
            }
            return ProbeResult(null, "HTTP/IO: $msg")
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Tenta decodificar PCM para um candidato de áudio.
     */
    private fun tryDecodePcmCandidate(
        extractor: MediaExtractor,
        candidate: TrackCandidate,
        isVideo: Boolean
    ): ExtractedAudioData? {
        var codec: MediaCodec? = null
        try {
            val (createdCodec, decoderDesc) = createDecoderWithFallback(candidate.mime)
            if (createdCodec == null) return null
            codec = createdCodec

            val audioFormat = candidate.format
            if (candidate.mime.equals("audio/vnd.dts.hd", ignoreCase = true) && decoderDesc.contains("DTS-Core")) {
                audioFormat.setString(MediaFormat.KEY_MIME, "audio/vnd.dts")
            }

            codec.configure(audioFormat, null, null, 0)
            codec.start()

            extractor.selectTrack(candidate.index)

            // UNIFICAÇÃO OBRIGATÓRIA DE JANELA TEMPORAL: Leitura estritamente desde 0.0s (sem seekTo)
            // A dublagem é leve e lê continuamente até 45.0s (para o novo VAD Morfológico Alass/FFSubSync).
            // O vídeo lê continuamente até pelo menos 30.0s.
            val targetEndPtsUs = if (isVideo) 30_000_000L else 45_000_000L
            val startTimeWallMs = System.currentTimeMillis()

            val binSumSquares = DoubleArray(TOTAL_BINS)
            val binSampleCounts = IntArray(TOTAL_BINS)
            val speechSumSquares = if (!isVideo) DoubleArray(TOTAL_BINS) else null

            var actualChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            var actualSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 48000

            var bandStop = AcousticSyncMatcher.BandStopFilter(actualSampleRate)
            var bandPass = if (!isVideo) AcousticSyncMatcher.BandPassFilter(actualSampleRate) else null

            val bufferInfo = MediaCodec.BufferInfo()
            var isEos = false
            var maxPtsReachedUs = 0L

            while (!isEos && maxPtsReachedUs < targetEndPtsUs && (System.currentTimeMillis() - startTimeWallMs) < SOURCE_READ_TIMEOUT_MS) {
                val inIndex = codec.dequeueInputBuffer(8_000L)
                if (inIndex >= 0) {
                    val inBuffer = codec.getInputBuffer(inIndex)
                    if (inBuffer != null) {
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > targetEndPtsUs + 1_000_000L) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 8_000L)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        actualChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        bandStop = AcousticSyncMatcher.BandStopFilter(actualSampleRate)
                        if (!isVideo) {
                            bandPass = AcousticSyncMatcher.BandPassFilter(actualSampleRate)
                        }
                    }
                } else if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0 && actualChannels > 0 && actualSampleRate > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val totalShorts = shortBuf.remaining()
                        val totalFrames = totalShorts / actualChannels
                        val basePtsUs = bufferInfo.presentationTimeUs

                        // Fatiamento exato a cada 10ms (100 Hz):
                        // Um buffer AAC decodificado contém 1024 frames (~21.33ms), cobrindo mais de 2 bins de 10ms!
                        // Dividimos a leitura em fatias exatas de framesPer10ms = actualSampleRate / 100,
                        // preenchendo sequencialmente cada bin de 10ms sem deixar nenhum buraco intercalado.
                        val framesPer10ms = (actualSampleRate / 100).coerceAtLeast(1)
                        val startBin = (basePtsUs / 10_000L).toInt()
                        val totalSlices = (totalFrames + framesPer10ms - 1) / framesPer10ms

                        for (slice in 0 until totalSlices) {
                            val binIndex = startBin + slice
                            if (binIndex !in 0 until TOTAL_BINS) continue

                            val sliceStartFrame = slice * framesPer10ms
                            val sliceEndFrame = minOf(totalFrames, sliceStartFrame + framesPer10ms)

                            var sliceMeSumSq = 0.0
                            var sliceSpeechSumSq = 0.0
                            var sliceCount = 0

                            for (f in sliceStartFrame until sliceEndFrame) {
                                val sampleOffset = f * actualChannels

                                // Isolamento Espacial M&E:
                                // Surround 5.1/7.1 descarta o Canal Central (short[2] vocal)
                                // Estéreo 2.0 combina Side (|L-R|) + 0.25*Mid filtrado
                                // Mono aplica Band-Stop completo
                                val meSample = when {
                                    actualChannels >= 6 -> {
                                        val fl = shortBuf.get(sampleOffset) / 32768.0f
                                        val fr = shortBuf.get(sampleOffset + 1) / 32768.0f
                                        val sl = shortBuf.get(sampleOffset + 4) / 32768.0f
                                        val sr = shortBuf.get(sampleOffset + 5) / 32768.0f
                                        abs(fl - fr) + 0.7f * (abs(sl) + abs(sr))
                                    }
                                    actualChannels == 2 -> {
                                        val l = shortBuf.get(sampleOffset) / 32768.0f
                                        val r = shortBuf.get(sampleOffset + 1) / 32768.0f
                                        val side = abs(l - r)
                                        val mid = 0.5f * (l + r)
                                        val midFiltered = abs(bandStop.filter(mid))
                                        0.75f * side + 0.25f * midFiltered
                                    }
                                    else -> {
                                        val mono = shortBuf.get(sampleOffset) / 32768.0f
                                        abs(bandStop.filter(mono))
                                    }
                                }
                                sliceMeSumSq += meSample.toDouble() * meSample.toDouble()

                                // Vetor de Atividade Vocal da Dublagem (Mid = 0.5*(L+R) filtrado em 300Hz-2800Hz)
                                if (!isVideo && speechSumSquares != null && bandPass != null) {
                                    val midVocal = when {
                                        actualChannels >= 2 -> {
                                            val l = shortBuf.get(sampleOffset) / 32768.0f
                                            val r = shortBuf.get(sampleOffset + 1) / 32768.0f
                                            0.5f * (l + r)
                                        }
                                        else -> shortBuf.get(sampleOffset) / 32768.0f
                                    }
                                    val speechFiltered = bandPass.filter(midVocal)
                                    sliceSpeechSumSq += speechFiltered.toDouble() * speechFiltered.toDouble()
                                }
                                sliceCount++
                            }

                            if (sliceCount > 0) {
                                binSumSquares[binIndex] += sliceMeSumSq
                                binSampleCounts[binIndex] += sliceCount
                                if (!isVideo && speechSumSquares != null) {
                                    speechSumSquares[binIndex] += sliceSpeechSumSq
                                }
                            }
                        }

                        val lastFramePtsUs = basePtsUs + (totalFrames * 1_000_000L / actualSampleRate)
                        if (lastFramePtsUs > maxPtsReachedUs) {
                            maxPtsReachedUs = lastFramePtsUs
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEos) {
                    break
                }
            }

            var firstValidBin = -1
            var lastValidBin = -1
            for (b in 0 until TOTAL_BINS) {
                if (binSampleCounts[b] > 0) {
                    if (firstValidBin < 0) firstValidBin = b
                    lastValidBin = b
                }
            }

            if (firstValidBin < 0 || lastValidBin < 0) {
                return null
            }

            val finalEnvelope = normalizeEnergyEnvelope(binSumSquares, binSampleCounts)
            interpolateHoles(finalEnvelope, firstValidBin, lastValidBin)

            val finalSpeechEnvelope = if (speechSumSquares != null) {
                val spEnv = normalizeEnergyEnvelope(speechSumSquares, binSampleCounts)
                interpolateHoles(spEnv, firstValidBin, lastValidBin)
                spEnv
            } else null

            var validBins = 0
            for (b in firstValidBin..lastValidBin) {
                if (finalEnvelope[b] > 0.0001f) validBins++
            }

            if (validBins < 120) {
                return null
            }

            return ExtractedAudioData(
                envelope = finalEnvelope,
                mime = candidate.mime,
                channels = actualChannels,
                sampleRate = actualSampleRate,
                validBins = validBins,
                methodType = "PCM",
                statusMessage = "PCM $actualChannels ch",
                speechEnvelope = finalSpeechEnvelope,
                firstValidBin = maxOf(0, firstValidBin),
                lastValidBin = maxOf(0, lastValidBin)
            )

        } catch (e: Exception) {
            Log.d(TAG, "PCM decoder falhou para ${candidate.mime} track ${candidate.index}: ${e.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.unselectTrack(candidate.index) }
        }
    }

    /**
     * Camada B: Extrai envelope de energia diretamente dos pacotes comprimidos do bitstream
     * para codecs que não possuem decodificador MediaCodec PCM nativo (ex: DTS-HD, TrueHD).
     */
    private fun tryExtractBitstreamEnergy(
        extractor: MediaExtractor,
        candidate: TrackCandidate
    ): ExtractedAudioData? {
        try {
            extractor.selectTrack(candidate.index)
            // UNIFICAÇÃO TEMPORAL: Leitura estritamente desde 0.0s (sem seekTo) até pelo menos 30.0s
            val targetEndPtsUs = 30_000_000L
            val startTimeWallMs = System.currentTimeMillis()

            val binSumSquares = DoubleArray(TOTAL_BINS)
            val binSampleCounts = IntArray(TOTAL_BINS)
            val buffer = ByteBuffer.allocate(65536)

            val rawSamples = mutableListOf<Triple<Long, Int, Double>>()
            var sampleCount = 0
            var maxPtsReachedUs = 0L
            while (maxPtsReachedUs < targetEndPtsUs && (System.currentTimeMillis() - startTimeWallMs) < SOURCE_READ_TIMEOUT_MS) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime > 0) {
                    val trailingLen = minOf(sampleSize, 256)
                    val startPos = maxOf(0, sampleSize - trailingLen)
                    buffer.position(startPos)
                    val trailingBytes = ByteArray(trailingLen)
                    buffer.get(trailingBytes)
                    var sumSq = 0.0
                    for (b in trailingBytes) {
                        val d = b.toDouble()
                        sumSq += d * d
                    }
                    val trailingRms = sqrt(sumSq / trailingLen)
                    rawSamples.add(Triple(sampleTime, sampleSize, trailingRms))
                    sampleCount++
                    if (sampleTime > maxPtsReachedUs) {
                        maxPtsReachedUs = sampleTime
                    }
                }
                extractor.advance()
            }

            // Piso mínimo do frame (Core) na janela para extrair o excesso VBR da extensão HD
            val validSizes = rawSamples.map { it.second }.filter { it > 64 }
            val minSampleSize = if (validSizes.isNotEmpty()) validSizes.minOrNull() ?: 0 else 0

            for (idx in rawSamples.indices) {
                val sample = rawSamples[idx]
                val sampleTime = sample.first
                val nextTime = if (idx + 1 < rawSamples.size) rawSamples[idx + 1].first else sampleTime + 15_000L
                val packetDurationUs = if (nextTime > sampleTime && (nextTime - sampleTime) in 5_000L..45_000L) {
                    nextTime - sampleTime
                } else {
                    15_000L
                }

                val binStart = (sampleTime / 10_000L).toInt()
                val binEnd = ((sampleTime + packetDurationUs) / 10_000L).toInt()

                val vbrExcess = (sample.second - minSampleSize).coerceAtLeast(0).toDouble()
                val frameEnergy = vbrExcess + (sample.third * 2.0)
                val energySq = frameEnergy * frameEnergy

                for (b in binStart..binEnd) {
                    if (b in 0 until TOTAL_BINS) {
                        binSumSquares[b] += energySq
                        binSampleCounts[b]++
                    }
                }
            }

            var firstValidBin = -1
            var lastValidBin = -1
            for (b in 0 until TOTAL_BINS) {
                if (binSampleCounts[b] > 0) {
                    if (firstValidBin < 0) firstValidBin = b
                    lastValidBin = b
                }
            }

            if (firstValidBin < 0 || lastValidBin < 0) {
                Log.w(TAG, "Bitstream parser: nenhum bin preenchido")
                return null
            }

            val finalEnvelope = normalizeEnergyEnvelope(binSumSquares, binSampleCounts)
            // Interpolação linear anti-buracos obrigatória
            interpolateHoles(finalEnvelope, firstValidBin, lastValidBin)
            // Re-escala dinâmica Percentil 10-95 no Bitstream (remove piso do DTS-HD e estende picos)
            rescalePercentile10To95(finalEnvelope, startBin = 250, endBin = lastValidBin)

            var validBins = 0
            for (b in firstValidBin..lastValidBin) {
                if (finalEnvelope[b] > 0.0001f) validBins++
            }

            if (validBins < 120) {
                Log.w(TAG, "Bitstream parser: bins insuficientes ($validBins)")
                return null
            }

            return ExtractedAudioData(
                envelope = finalEnvelope,
                mime = candidate.mime,
                channels = 1,
                sampleRate = 48000,
                validBins = validBins,
                methodType = "Bitstream",
                statusMessage = "Bitstream ${candidate.mime.substringAfter('/')}",
                firstValidBin = maxOf(0, firstValidBin),
                lastValidBin = maxOf(0, lastValidBin)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bitstream parser falhou para ${candidate.mime}: ${e.message}")
            return null
        } finally {
            runCatching { extractor.unselectTrack(candidate.index) }
        }
    }

    /**
     * Solução Universal de Extração de Container via Media3 MatroskaExtractor:
     * Faz o parsing do container .mkv em software na camada da aplicação, enxergando 100% das
     * faixas de áudio e legendas (audio/vnd.dts.hd, audio/vnd.dts, audio/true-hd, etc.),
     * imune a limitações do Stagefright nativo do Android.
     */
    @OptIn(UnstableApi::class)
    private fun extractAudioViaMedia3Matroska(
        url: String,
        headers: Map<String, String>,
        isVideo: Boolean
    ): ProbeResult? {
        var dataSource: DefaultHttpDataSource? = null
        try {
            val httpHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                    httpHeaders[k] = v
                }
            }
            if (httpHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                httpHeaders["User-Agent"] = "Mozilla/5.0 (Linux; Android 13; Android TV)"
            }

            dataSource = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13; Android TV)")
                .setDefaultRequestProperties(httpHeaders)
                .setConnectTimeoutMs(6000)
                .setReadTimeoutMs(6000)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource()

            val uri = Uri.parse(url)
            val dataSpec = DataSpec.Builder()
                .setUri(uri)
                .setLength(64L * 1024L * 1024L)
                .build()

            val openLen = dataSource.open(dataSpec)
            val extractorInput = DefaultExtractorInput(dataSource, 0L, openLen)
            val extractor = MatroskaExtractor()

            val tracks = mutableMapOf<Int, Media3ProbeTrack>()
            val extractorOutput = object : ExtractorOutput {
                override fun track(id: Int, type: Int): TrackOutput {
                    return tracks.getOrPut(id) { Media3ProbeTrack(id, type) }
                }
                override fun endTracks() {}
                override fun seekMap(seekMap: SeekMap) {}
            }

            extractor.init(extractorOutput)
            val positionHolder = PositionHolder()
            val startWallMs = System.currentTimeMillis()

            while ((System.currentTimeMillis() - startWallMs) < SOURCE_READ_TIMEOUT_MS) {
                val res = extractor.read(extractorInput, positionHolder)
                if (res == Extractor.RESULT_END_OF_INPUT) break

                val audioTrack = tracks.values.firstOrNull { it.trackType == C.TRACK_TYPE_AUDIO }
                if (audioTrack != null && audioTrack.samples.isNotEmpty()) {
                    val lastPts = audioTrack.samples.last().timeUs
                    if (lastPts >= SAMPLE_WINDOW_US) break
                }
            }

            // Registra detalhadamente as faixas de vídeo, áudio e legendas descobertas pelo MatroskaExtractor
            val discoveredVideos = mutableListOf<Map<String, Any?>>()
            val discoveredAudios = mutableListOf<Map<String, Any?>>()
            val discoveredSubs = mutableListOf<Map<String, Any?>>()
            for ((id, trk) in tracks) {
                val fmt = trk.format
                val mime = fmt?.sampleMimeType ?: when (trk.trackType) {
                    C.TRACK_TYPE_AUDIO -> "audio/vnd.dts"
                    C.TRACK_TYPE_TEXT -> "application/x-subrip"
                    else -> "video/av01"
                }
                val trackInfo = mapOf<String, Any?>(
                    "track_index" to id,
                    "mime_type" to mime,
                    "language" to fmt?.language,
                    "channels" to fmt?.channelCount,
                    "sample_rate" to fmt?.sampleRate,
                    "bitrate" to fmt?.bitrate,
                    "width" to fmt?.width,
                    "height" to fmt?.height
                )
                when (trk.trackType) {
                    C.TRACK_TYPE_VIDEO -> discoveredVideos.add(trackInfo)
                    C.TRACK_TYPE_AUDIO -> discoveredAudios.add(trackInfo)
                    C.TRACK_TYPE_TEXT -> discoveredSubs.add(trackInfo)
                }
            }
            if (discoveredAudios.isNotEmpty() || discoveredSubs.isNotEmpty()) {
                DubDiagnosticFileLogger.setContainerTracks(discoveredVideos, discoveredAudios, discoveredSubs)
            }

            if (isVideo) {
                val textTracks = tracks.values.filter { it.trackType == C.TRACK_TYPE_TEXT && it.textCues.isNotEmpty() }
                if (textTracks.isNotEmpty()) {
                    val chosenTextTrack = textTracks.firstOrNull {
                        val l = it.format?.language?.lowercase().orEmpty()
                        l.startsWith("pt") || l.startsWith("por")
                    } ?: textTracks.firstOrNull {
                        val l = it.format?.language?.lowercase().orEmpty()
                        l.startsWith("en") || l.startsWith("eng")
                    } ?: textTracks.maxByOrNull { it.textCues.size } ?: textTracks.first()

                    lastExtractedVideoEmbeddedCues = chosenTextTrack.textCues.sortedBy { it.startTimeMs }
                    lastExtractedVideoEmbeddedLanguage = chosenTextTrack.format?.language ?: "und"
                    Log.i(TAG, "Media3 MatroskaExtractor extraiu simultaneamente ${lastExtractedVideoEmbeddedCues.size} falas de legenda embutida (${lastExtractedVideoEmbeddedLanguage}) no mesmo passe!")
                }
            }

            val audioTracks = tracks.values.filter { it.trackType == C.TRACK_TYPE_AUDIO }
            if (audioTracks.isEmpty()) {
                Log.w(TAG, "Media3 MatroskaExtractor: nenhuma faixa de áudio identificada no container")
                return null
            }

            val chosenTrack = audioTracks.maxByOrNull { it.samples.size } ?: audioTracks.first()
            val trackMime = chosenTrack.format?.sampleMimeType ?: "audio/vnd.dts"
            val shortMime = trackMime.substringAfter('/')
            Log.i(TAG, "Media3 MatroskaExtractor coletou ${chosenTrack.samples.size} pacotes de áudio para $trackMime")

            if (chosenTrack.samples.isEmpty()) {
                return null
            }

            val binSumSquares = DoubleArray(TOTAL_BINS)
            val binSampleCounts = IntArray(TOTAL_BINS)

            val validSizes = chosenTrack.samples.map { it.size }.filter { it > 64 }
            val minSampleSize = if (validSizes.isNotEmpty()) validSizes.minOrNull() ?: 0 else 0

            for (idx in chosenTrack.samples.indices) {
                val sample = chosenTrack.samples[idx]
                val timeUs = sample.timeUs
                if (timeUs <= 0) continue
                val nextTime = if (idx + 1 < chosenTrack.samples.size) chosenTrack.samples[idx + 1].timeUs else timeUs + 15_000L
                val packetDurationUs = if (nextTime > timeUs && (nextTime - timeUs) in 5_000L..45_000L) {
                    nextTime - timeUs
                } else {
                    15_000L
                }

                val binStart = (timeUs / 10_000L).toInt()
                val binEnd = ((timeUs + packetDurationUs) / 10_000L).toInt()

                val vbrExcess = (sample.size - minSampleSize).coerceAtLeast(0).toDouble()
                val frameEnergy = vbrExcess + (sample.size / 50.0)
                val energySq = frameEnergy * frameEnergy

                for (b in binStart..binEnd) {
                    if (b in 0 until TOTAL_BINS) {
                        binSumSquares[b] += energySq
                        binSampleCounts[b]++
                    }
                }
            }

            var firstValidBin = -1
            var lastValidBin = -1
            for (b in 0 until TOTAL_BINS) {
                if (binSampleCounts[b] > 0) {
                    if (firstValidBin < 0) firstValidBin = b
                    lastValidBin = b
                }
            }

            if (firstValidBin < 0 || lastValidBin < 0) {
                Log.w(TAG, "Media3 MatroskaExtractor: nenhum bin preenchido")
                return null
            }

            val finalEnvelope = normalizeEnergyEnvelope(binSumSquares, binSampleCounts)
            // Interpolação linear anti-buracos obrigatória
            interpolateHoles(finalEnvelope, firstValidBin, lastValidBin)
            // Re-escala dinâmica Percentil 10-95 no Bitstream
            rescalePercentile10To95(finalEnvelope, startBin = 250, endBin = lastValidBin)

            var validBins = 0
            for (b in firstValidBin..lastValidBin) {
                if (finalEnvelope[b] > 0.0001f) validBins++
            }

            if (validBins < 120) {
                Log.w(TAG, "Media3 MatroskaExtractor: bins insuficientes ($validBins)")
                return null
            }

            val data = ExtractedAudioData(
                envelope = finalEnvelope,
                mime = trackMime,
                channels = chosenTrack.format?.channelCount ?: 2,
                sampleRate = chosenTrack.format?.sampleRate ?: 48000,
                validBins = validBins,
                methodType = "Bitstream(Media3)",
                statusMessage = "Media3 $shortMime",
                firstValidBin = maxOf(0, firstValidBin),
                lastValidBin = maxOf(0, lastValidBin)
            )
            return ProbeResult(data, "$shortMime(Media3)/${validBins}b")

        } catch (e: Exception) {
            Log.w(TAG, "Media3 MatroskaExtractor falhou para $url: ${e.message}", e)
            return null
        } finally {
            runCatching { dataSource?.close() }
        }
    }
    /**
     * Interpolação linear anti-buracos obrigatória:
     * Preenche qualquer bin isolado ou sequência de até 4 bins (40ms) que tenha ficado com 0.0f
     * entre dois bins preenchidos pela média ponderada dos vizinhos (elimina dente-de-serra e buracos).
     */
    fun interpolateHoles(array: FloatArray, firstFilledBin: Int, lastFilledBin: Int) {
        if (firstFilledBin < 0 || lastFilledBin <= firstFilledBin) return
        val start = firstFilledBin.coerceIn(0, array.size - 1)
        val end = lastFilledBin.coerceIn(start, array.size - 1)
        var i = start
        while (i <= end) {
            if (array[i] == 0.0f) {
                val holeStart = i
                while (i <= end && array[i] == 0.0f) {
                    i++
                }
                val holeLen = i - holeStart
                if (holeLen in 1..4 && holeStart > 0 && i <= end) {
                    val prevVal = array[holeStart - 1]
                    val nextVal = array[i]
                    for (k in 0 until holeLen) {
                        val alpha = (k + 1).toFloat() / (holeLen + 1).toFloat()
                        array[holeStart + k] = (1f - alpha) * prevVal + alpha * nextVal
                    }
                }
            } else {
                i++
            }
        }
    }

    /**
     * Re-escala Dinâmica Percentil 10–95 no Bitstream:
     * Subtrai o Percentil 10 (P10) dos valores ativos e divide por (P95 - P10).
     * Faz com que o piso constante de 0.89 do DTS-HD vire 0.0 e os picos de chuva/efeitos ocupem toda a escala de 0.0 a 1.0.
     */
    fun rescalePercentile10To95(array: FloatArray, startBin: Int = 250, endBin: Int = TOTAL_BINS - 1) {
        val s = startBin.coerceIn(0, array.size - 1)
        val e = endBin.coerceIn(s, array.size - 1)
        val activeValues = ArrayList<Float>(e - s + 1)
        for (i in s..e) {
            val v = array[i]
            if (v > 0.001f) {
                activeValues.add(v)
            }
        }
        if (activeValues.size < 50) return
        activeValues.sort()
        val p10Idx = (activeValues.size * 0.10f).toInt().coerceIn(0, activeValues.size - 1)
        val p95Idx = (activeValues.size * 0.95f).toInt().coerceIn(0, activeValues.size - 1)
        val p10 = activeValues[p10Idx]
        val p95 = activeValues[p95Idx]
        val range = (p95 - p10).coerceAtLeast(1e-4f)

        for (i in s..e) {
            if (array[i] > 0.001f) {
                array[i] = ((array[i] - p10) / range).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Aplica exatamente a mesma pós-normalização matemática:
     * Log-RMS por bin de 10ms + Normalização [0,1] + Onset (Derivada Positiva de Ataque) + Combinação 50/50.
     */
    private fun normalizeEnergyEnvelope(
        binSumSquares: DoubleArray,
        binSampleCounts: IntArray
    ): FloatArray {
        val logRms = FloatArray(TOTAL_BINS)
        for (b in 0 until TOTAL_BINS) {
            val count = binSampleCounts[b]
            if (count > 0) {
                val rms = sqrt(binSumSquares[b] / count).toFloat()
                logRms[b] = log10(1.0f + rms)
            }
        }

        var maxRms = 0.00001f
        for (v in logRms) if (v > maxRms) maxRms = v
        for (i in logRms.indices) logRms[i] /= maxRms

        val onsets = FloatArray(TOTAL_BINS)
        for (i in 1 until TOTAL_BINS) {
            val diff = logRms[i] - logRms[i - 1]
            onsets[i] = if (diff > 0f) diff else 0f
        }
        var maxOnset = 0.00001f
        for (v in onsets) if (v > maxOnset) maxOnset = v
        for (i in onsets.indices) onsets[i] /= maxOnset

        val finalEnvelope = FloatArray(TOTAL_BINS)
        for (i in 0 until TOTAL_BINS) {
            finalEnvelope[i] = 0.5f * logRms[i] + 0.5f * onsets[i]
        }
        return finalEnvelope
    }

    private fun skipDataReader(input: DataReader, length: Int) {
        var remaining = length
        val discard = ByteArray(minOf(remaining, 4096))
        while (remaining > 0) {
            val read = input.read(discard, 0, minOf(remaining, discard.size))
            if (read <= 0) break
            remaining -= read
        }
    }

    private data class Media3CapturedSample(
        val timeUs: Long,
        val size: Int,
        val headerBytes: ByteArray
    )

    @UnstableApi
    private class Media3ProbeTrack(val trackId: Int, val trackType: Int) : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<Media3CapturedSample>()
        val textCues = mutableListOf<SubtitleSyncCue>()
        private val headerBuf = ByteArray(256)
        private var headerPos = 0
        private var textBuf = ByteArray(4096)
        private var textPos = 0

        companion object {
            private val DISCARD_BUFFER = ByteArray(16384)
        }

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (trackType == C.TRACK_TYPE_TEXT) {
                if (textPos + length > textBuf.size) {
                    textBuf = textBuf.copyOf(maxOf(textBuf.size * 2, textPos + length))
                }
                val read = input.read(textBuf, textPos, length)
                if (read > 0) {
                    textPos += read
                }
                return if (read > 0) read else length
            }

            // DESCARTE INSTANTÂNEO DE VÍDEO 4K:
            // Faixas de vídeo são puladas imediatamente sem nenhuma alocação de heap
            if (trackType != C.TRACK_TYPE_AUDIO) {
                if (input is ExtractorInput) {
                    input.skipFully(length)
                } else {
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(DISCARD_BUFFER, 0, minOf(remaining, DISCARD_BUFFER.size))
                        if (read <= 0) break
                        remaining -= read
                    }
                }
                return length
            }

            val toCopy = minOf(length, 256 - headerPos)
            if (toCopy > 0) {
                val read = input.read(headerBuf, headerPos, toCopy)
                if (read > 0) {
                    headerPos += read
                    val left = length - read
                    if (left > 0) {
                        if (input is ExtractorInput) {
                            input.skipFully(left)
                        } else {
                            var remaining = left
                            while (remaining > 0) {
                                val r = input.read(DISCARD_BUFFER, 0, minOf(remaining, DISCARD_BUFFER.size))
                                if (r <= 0) break
                                remaining -= r
                            }
                        }
                    }
                }
                return length
            } else {
                if (input is ExtractorInput) {
                    input.skipFully(length)
                } else {
                    var remaining = length
                    while (remaining > 0) {
                        val read = input.read(DISCARD_BUFFER, 0, minOf(remaining, DISCARD_BUFFER.size))
                        if (read <= 0) break
                        remaining -= read
                    }
                }
                return length
            }
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (trackType == C.TRACK_TYPE_TEXT) {
                if (textPos + length > textBuf.size) {
                    textBuf = textBuf.copyOf(maxOf(textBuf.size * 2, textPos + length))
                }
                data.readBytes(textBuf, textPos, length)
                textPos += length
                return
            }

            if (trackType != C.TRACK_TYPE_AUDIO) {
                data.skipBytes(length)
                return
            }
            val toCopy = minOf(length, 256 - headerPos)
            if (toCopy > 0) {
                data.readBytes(headerBuf, headerPos, toCopy)
                headerPos += toCopy
                if (length > toCopy) data.skipBytes(length - toCopy)
            } else {
                data.skipBytes(length)
            }
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (trackType == C.TRACK_TYPE_TEXT) {
                val sampleLen = minOf(textPos, size)
                val rawBytes = textBuf.copyOf(sampleLen)
                textPos = 0
                val cue = parseMatroskaSubtitlePayload(timeUs, rawBytes, format?.sampleMimeType)
                if (cue != null) {
                    textCues.add(cue)
                }
                return
            }
            if (trackType != C.TRACK_TYPE_AUDIO) return
            val bytes = headerBuf.copyOf(minOf(headerPos, size))
            samples.add(Media3CapturedSample(timeUs = timeUs, size = size, headerBytes = bytes))
            headerPos = 0
        }
    }

    private fun parseMatroskaSubtitlePayload(timeUs: Long, rawBytes: ByteArray, mimeType: String?): SubtitleSyncCue? {
        if (rawBytes.isEmpty()) return null
        val text = try {
            String(rawBytes, Charsets.UTF_8).trim()
        } catch (_: Exception) { return null }
        if (text.isBlank()) return null

        val cleanText = if (mimeType?.contains("ssa", ignoreCase = true) == true) {
            val parts = text.split(",", limit = 9)
            val dialogueText = if (parts.size >= 9) parts.last() else text
            dialogueText.replace(Regex("\\{[^}]*\\}"), "").replace("\\N", " ").trim()
        } else {
            text.replace(Regex("<[^>]*>"), "").trim()
        }
        if (cleanText.isBlank()) return null
        val startMs = timeUs / 1000L
        return SubtitleSyncCue(
            startTimeMs = startMs,
            endTimeMs = startMs + 2500L,
            text = cleanText
        )
    }
}

