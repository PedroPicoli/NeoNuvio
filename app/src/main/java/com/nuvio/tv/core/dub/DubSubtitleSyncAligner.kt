package com.nuvio.tv.core.dub

import android.util.Log
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSampledReferenceAligner
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Maestro de Sincronia de Legendas da Orchestra:
 * Extrai cues de legendas das fontes de vídeo e de áudio (embutidas no MKV via HTTP Range ou externas)
 * e calcula o alinhamento temporal e delay exato entre as falas da série/filme com precisão de milissegundos.
 */
object DubSubtitleSyncAligner {

    private const val TAG = "DubSubtitleSyncAligner"
    private const val TIMEOUT_MS = 4_000L

    data class SubtitleMatch(
        val initialAudioOffsetMs: Long,
        val confidence: Float,
        val matchedCues: Int,
        val videoLanguage: String?,
        val dubLanguage: String?
    )

    private val SDH_BRACKETS_REGEX = Regex("""\[.*?\]""")
    private val SDH_PARENS_REGEX = Regex("""\(.*?\)""")
    private val SDH_MUSIC_REGEX = Regex("""[♪♫#*]""")

    /**
     * Remove ruídos SDH (audiodescrição para deficientes auditivos, efeitos sonoros e placas curtas).
     */
    fun sanitizeCues(cues: List<SubtitleSyncCue>): List<SubtitleSyncCue> {
        return cues.mapNotNull { cue ->
            var text = cue.text
            text = SDH_BRACKETS_REGEX.replace(text, "")
            text = SDH_PARENS_REGEX.replace(text, "")
            text = SDH_MUSIC_REGEX.replace(text, "")
            text = text.trim()

            // Remove cartelas curtas em caixa alta no primeiro minuto (ex: "ADULT SWIM", "NETFLIX", "WARNER")
            if (cue.startTimeMs <= 60_000L && text.length > 2 && text.uppercase() == text && text.split("\\s+".toRegex()).size <= 2) {
                return@mapNotNull null
            }

            if (text.isBlank()) null else cue.copy(text = text)
        }
    }

    /**
     * Alinhamento por Cadência de Diálogo (RANSAC / Consensus Delta):
     * Em vez de confiar na primeira fala, analisa os primeiros 90 segundos das falas limpas
     * e calcula a moda estatística das diferenças cruzadas (Δ = dub - video).
     */
    fun findConsensusOffsetMs(
        videoCues: List<SubtitleSyncCue>,
        dubCues: List<SubtitleSyncCue>,
        toleranceBinMs: Long = 150L
    ): Pair<Long, Float>? {
        val cleanVideo = sanitizeCues(videoCues).filter { it.startTimeMs in 0L..90_000L }.take(15)
        val cleanDub = sanitizeCues(dubCues).filter { it.startTimeMs in 0L..90_000L }.take(15)

        if (cleanVideo.size < 3 || cleanDub.size < 3) return null

        val deltaVotes = mutableMapOf<Long, MutableList<Long>>()
        for (v in cleanVideo) {
            for (d in cleanDub) {
                val delta = d.startTimeMs - v.startTimeMs
                // Considera offsets plausíveis de vinhetas/aberturas de -30s a +30s
                if (abs(delta) > 35_000L) continue
                val bin = ((delta + (toleranceBinMs / 2)) / toleranceBinMs) * toleranceBinMs
                deltaVotes.getOrPut(bin) { mutableListOf() }.add(delta)
            }
        }

        if (deltaVotes.isEmpty()) return null

        val bestBin = deltaVotes.maxByOrNull { it.value.size } ?: return null
        val voteCount = bestBin.value.size
        val totalPairs = cleanVideo.size * cleanDub.size

        if (voteCount >= 3) {
            val refinedOffset = bestBin.value.average().roundToLong()
            val confidence = (voteCount.toFloat() / cleanVideo.size).coerceIn(0.70f, 0.98f)
            return Pair(refinedOffset, confidence)
        }
        return null
    }

    suspend fun alignBySubtitles(
        videoUrl: String,
        videoHeaders: Map<String, String>,
        dubAudioUrl: String,
        dubAudioHeaders: Map<String, String>,
        externalVideoCues: List<SubtitleSyncCue> = emptyList(),
        externalDubCues: List<SubtitleSyncCue> = emptyList()
    ): SubtitleMatch? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                val videoTimelineDeferred = async {
                    EmbeddedSubtitleTimelineLoader.load(videoUrl, videoHeaders)
                }
                val dubTimelineDeferred = async {
                    EmbeddedSubtitleTimelineLoader.load(dubAudioUrl, dubAudioHeaders)
                }

                val videoTimeline = videoTimelineDeferred.await()
                val dubTimeline = dubTimelineDeferred.await()

                val videoCueCandidateLists = mutableListOf<Pair<String?, List<SubtitleSyncCue>>>()
                if (externalVideoCues.size >= 5) {
                    videoCueCandidateLists.add("external" to externalVideoCues)
                }
                videoTimeline?.tracks?.forEach { track ->
                    if (track.cues.size >= 5) {
                        videoCueCandidateLists.add(track.language to track.cues)
                    }
                }

                val dubCueCandidateLists = mutableListOf<Pair<String?, List<SubtitleSyncCue>>>()
                if (externalDubCues.size >= 5) {
                    dubCueCandidateLists.add("external" to externalDubCues)
                }
                dubTimeline?.tracks?.forEach { track ->
                    if (track.cues.size >= 5) {
                        dubCueCandidateLists.add(track.language to track.cues)
                    }
                }

                if (videoCueCandidateLists.isEmpty() || dubCueCandidateLists.isEmpty()) {
                    Log.d(
                        TAG,
                        "Cues de legendas insuficientes para alinhamento (videoLists=${videoCueCandidateLists.size}, dubLists=${dubCueCandidateLists.size})"
                    )
                    return@runCatching null
                }

                var bestMatch: SubtitleMatch? = null

                for ((vLang, vCues) in videoCueCandidateLists) {
                    for ((dLang, dCues) in dubCueCandidateLists) {
                        // 1. Tenta consenso rápido por RANSAC / Moda Estatística de Intervalos
                        val consensus = findConsensusOffsetMs(vCues, dCues)
                        if (consensus != null) {
                            val (offsetMs, conf) = consensus
                            Log.i(
                                TAG,
                                "SUBTITLE RANSAC MATCH: vLang=$vLang dLang=$dLang offset=${offsetMs}ms conf=$conf"
                            )
                            if (bestMatch == null || conf > bestMatch.confidence) {
                                bestMatch = SubtitleMatch(
                                    initialAudioOffsetMs = offsetMs,
                                    confidence = conf,
                                    matchedCues = (conf * vCues.size).toInt(),
                                    videoLanguage = vLang,
                                    dubLanguage = dLang
                                )
                            }
                            continue
                        }

                        // 2. Fallback para AutoSyncSampledReferenceAligner
                        val alignment = AutoSyncSampledReferenceAligner.findDelay(
                            reference = vCues,
                            target = dCues
                        ) ?: continue

                        val offsetMs = alignment.offsetMs.roundToLong()
                        val initialOffsetMs = -offsetMs
                        val confidence = alignment.score.toFloat().coerceIn(0.70f, 0.95f)

                        Log.i(
                            TAG,
                            "SUBTITLE ALIGNMENT ENCONTRADO: vLang=$vLang dLang=$dLang offset=${offsetMs}ms " +
                                "initialAudioOffsetMs=${initialOffsetMs}ms score=${alignment.score}"
                        )

                        if (bestMatch == null || alignment.score > bestMatch.confidence) {
                            bestMatch = SubtitleMatch(
                                initialAudioOffsetMs = initialOffsetMs,
                                confidence = confidence,
                                matchedCues = (alignment.score * vCues.size).toInt(),
                                videoLanguage = vLang,
                                dubLanguage = dLang
                            )
                        }
                    }
                }

                bestMatch
            }.getOrNull()
        }
    }

    /**
     * Máscara Temporal de Fala da Legenda do Vídeo:
     * - Quando [onsetOnly] = false (padrão para VAD Morfológico Alass/FFSubSync): preenche o ataque inicial
     *   de 350ms (+1.0f) e a sustentação da fala (+0.75f) com vales obrigatórios entre falas próximas.
     * - Quando [onsetOnly] = true (Trem de Pulsos de Início de Frase): coloca 1.0f apenas nos primeiros 350ms
     *   (startMs .. startMs + 350ms) e 0.0f no restante da frase, gerando picos nítidos e isolados.
     */
    fun generateSubtitleSpeechMask(
        cues: List<SubtitleSyncCue>,
        totalBins: Int = 4500,
        onsetOnly: Boolean = false
    ): FloatArray {
        val mask = FloatArray(totalBins)
        val clean = sanitizeCues(cues).sortedBy { it.startTimeMs }
        val maxMs = totalBins * 10L

        for (k in clean.indices) {
            val cue = clean[k]
            if (cue.startTimeMs >= maxMs) continue

            val cueStartMs = cue.startTimeMs
            var cueEndMs = if (onsetOnly) cueStartMs + 350L else cue.endTimeMs

            if (!onsetOnly && k + 1 < clean.size) {
                val nextStartMs = clean[k + 1].startTimeMs
                if (nextStartMs > cueStartMs && (nextStartMs - cueEndMs) < 120L) {
                    cueEndMs = minOf(cueEndMs, nextStartMs - 80L)
                }
            }

            val startBin = (cueStartMs / 10L).toInt().coerceIn(0, totalBins - 1)
            val endBin = (cueEndMs / 10L).toInt().coerceIn(startBin, totalBins - 1)
            val attackEndBin = minOf(endBin, startBin + 35) // Primeiros 350ms (35 bins)

            // Impulso de ataque (+1.0f) na entrada da frase
            for (b in startBin..attackEndBin) {
                mask[b] = 1.0f
            }
            if (!onsetOnly) {
                // Sustentação normal da fala (+0.75f) no restante
                for (b in (attackEndBin + 1)..endBin) {
                    mask[b] = 0.75f
                }
            }
        }
        return mask
    }

    /**
     * Trem de Pulsos de Início de Frase (Onset Pulse Train a 100 Hz):
     * Para cada fala da legenda limpa, coloca 1.0f apenas nos primeiros 350 ms (startMs .. startMs + 350ms)
     * e 0.0f no restante da frase, gerando picos isolados e eliminando a parede de 1s.
     */
    fun generateSubtitleOnsetPulseTrain(cues: List<SubtitleSyncCue>, totalBins: Int = 4500): FloatArray {
        return generateSubtitleSpeechMask(cues, totalBins = totalBins, onsetOnly = true)
    }

    /**
     * Extrai apenas as subidas de ataque vocal (Onset Vocal Derivative):
     * max(0f, speech[i] - speech[i - 25])
     * Destaca os transientes de início de fala humana eliminando platôs constantes.
     */
    fun extractVocalAttacks(speech: FloatArray, attackWindow: Int = 25): FloatArray {
        if (speech.isEmpty()) return speech
        val attacks = FloatArray(speech.size)
        for (i in speech.indices) {
            val prev = if (i >= attackWindow) speech[i - attackWindow] else 0f
            val diff = speech[i] - prev
            attacks[i] = if (diff > 0f) diff else 0f
        }
        return attacks
    }

    /**
     * Alinhamento Cross-Modal Sub-to-Dub VAD (Algoritmo Morfológico FFSubSync / Alass):
     * Conecta a melhor legenda da fonte de vídeo diretamente com o envelope de voz da dublagem
     * na janela expandida de 6.0s a 45.0s (bins 600..4500).
     */
    fun alignCrossModal(
        videoCues: List<SubtitleSyncCue>,
        dubSpeechEnvelope: FloatArray,
        subOrigin: String = "subrip",
        dubMime: String = "mp4a-latm"
    ): AcousticSyncMatcher.AcousticMatch? {
        if (videoCues.isEmpty() || dubSpeechEnvelope.isEmpty()) return null
        val totalBins = maxOf(4500, dubSpeechEnvelope.size)
        val subMask = generateSubtitleSpeechMask(videoCues, totalBins = totalBins, onsetOnly = false)
        return AcousticSyncMatcher.findCrossModalSubToDubLag(
            subSpeechMask = subMask,
            dubSpeechEnvelope = dubSpeechEnvelope,
            windowMs = 10,
            skipInitialMs = 6_000L,
            targetEndMs = 45_000L,
            maxLagMs = 10_000L,
            subOrigin = subOrigin,
            dubMime = dubMime
        )
    }

    /**
     * Ancoragem Rápida por Início de Diálogo (Dialogue Onset Anchor):
     * Determina o offset de abertura cruzando cues de fala consistentes da legenda
     * com ataques vocais da dublagem.
     * Restrito estritamente a legendas EMBUTIDAS no arquivo de vídeo para evitar falsos positivos
     * provenientes de legendas de outros releases baixadas por addons externos.
     */
    fun estimateDialogueOnsetOffset(
        videoCues: List<SubtitleSyncCue>,
        dubSpeechEnvelope: FloatArray,
        subOrigin: String = "subrip",
        dubMime: String = "mp4a-latm"
    ): AcousticSyncMatcher.AcousticMatch? {
        val isExternalAddon = subOrigin.contains("AIOStreams", ignoreCase = true) ||
            subOrigin.contains("OpenSubtitles", ignoreCase = true) ||
            subOrigin.contains("SubDL", ignoreCase = true)
        if (isExternalAddon) {
            // NUNCA confiar em início de diálogo vindo de addons de terceiros da web (como AIOStreams / OpenSubtitles)
            return null
        }
        val clean = sanitizeCues(videoCues)
        val offsetMs = AcousticSyncMatcher.estimateDialogueOnsetOffsetMs(clean, dubSpeechEnvelope) ?: return null
        val telemetry = "Dialogue Onset Anchor [Sub: $subOrigin | Dub: $dubMime | Offset: ${if (offsetMs >= 0) "+" else ""}${offsetMs}ms]"
        return AcousticSyncMatcher.AcousticMatch(
            offsetMs = offsetMs,
            confidence = 0.60f,
            maxZncc = 0.50f,
            psr = 3.5f,
            isValid = true,
            telemetryDiag = telemetry
        )
    }
}
