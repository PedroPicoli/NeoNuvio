package com.nuvio.tv.core.dub

import com.nuvio.tv.ui.screens.player.PlayerChapter
import kotlin.math.abs

/**
 * Decide a compatibilidade da fonte de áudio e calcula o deslocamento inicial (offset de abertura)
 * e o alinhamento com base nas durações reais e capítulos das fontes.
 */
object DubSyncEstimator {

    /** Tolerância de corte para áudio mais curto (vídeo tem vinheta internacional adicional). */
    private const val MAX_SHORTER_MS = 30_000L

    /** Diferença máxima (áudio mais longo com vinheta nacional/abertura). */
    private const val MAX_LONGER_MS = 120_000L

    /** Abaixo disso as durações são consideradas idênticas (mesmo corte/distribuição). */
    private const val ALIGNED_TOLERANCE_MS = 800L

    data class SyncDecision(
        val timeline: DubTimelineMap,
        val durationGapMs: Long,
        val approximate: Boolean,
        val methodName: String = "Estimativa",
        val logicDescription: String = "",
        val tVideoMs: Long? = null,
        val tAudioMs: Long? = null,
        val acousticNccScore: Float? = null,
        val winningLayer: String = ""
    )

    data class BumperInfo(
        val name: String,
        val offsetMs: Long
    )

    private val DELAY_TAG_REGEX = Regex("""(?i)\b(delay|sync|offset)\s*[:=]?\s*([+-]?\d+(?:\.\d+)?)\s*(ms|s)?\b""")

    private val NETFLIX_REGEX = Regex("""(?i)\b(NF|Netflix)\b""")
    private val AMAZON_REGEX = Regex("""(?i)\b(AMZN|Amazon|Prime)\b""")
    private val HBO_REGEX = Regex("""(?i)\b(MAX|HMAX|HBO|Warner)\b""")
    private val DISNEY_REGEX = Regex("""(?i)\b(DSNP|Disney)\b""")
    private val APPLE_REGEX = Regex("""(?i)\b(ATVP|Apple)\b""")
    private val PARAMOUNT_REGEX = Regex("""(?i)\b(PMTP|Paramount)\b""")
    private val HULU_REGEX = Regex("""(?i)\b(HULU|Star)\b""")
    private val ADULT_SWIM_REGEX = Regex("""(?i)\b(Adult\s*Swim|AdultSwim|\bAS\b|ASWIM)\b""")
    private val CARTOON_NETWORK_REGEX = Regex("""(?i)\b(Cartoon\s*Network|\bCN\b)\b""")
    private val WEBDL_REGEX = Regex("""(?i)\b(WEB-?DL|WEBRip)\b""")
    private val HDTV_REGEX = Regex("""(?i)\b(HDTV|PDTV|DSRip)\b""")
    private val BLURAY_REGEX = Regex("""(?i)\b(BluRay|BDRip|BRRip|BD-?Remux|UHD[-.]?BluRay)\b""")
    private val WEB_OR_TV_REGEX = Regex("""(?i)\b(WEB-?DL|WEBRip|HDTV|PDTV|DSRip|BestCine|Brazuca|Comet|HMax|MAX|Dual\s*Audio)\b""")

    fun detectStreamingBumper(rawTitle: String?): BumperInfo? {
        if (rawTitle.isNullOrBlank()) return null
        val title = rawTitle.replace(".", " ").replace("_", " ").replace("-", " ")
        return when {
            ADULT_SWIM_REGEX.containsMatchIn(title) -> BumperInfo("Adult Swim", 5_100L)
            NETFLIX_REGEX.containsMatchIn(title) -> BumperInfo("Netflix", 4_000L)
            AMAZON_REGEX.containsMatchIn(title) -> BumperInfo("Amazon Prime", 3_200L)
            HBO_REGEX.containsMatchIn(title) -> BumperInfo("HBO / MAX", 3_000L)
            DISNEY_REGEX.containsMatchIn(title) -> BumperInfo("Disney+", 4_500L)
            APPLE_REGEX.containsMatchIn(title) -> BumperInfo("Apple TV+", 4_000L)
            PARAMOUNT_REGEX.containsMatchIn(title) -> BumperInfo("Paramount+", 4_500L)
            HULU_REGEX.containsMatchIn(title) -> BumperInfo("Hulu / Star", 3_000L)
            CARTOON_NETWORK_REGEX.containsMatchIn(title) -> BumperInfo("Cartoon Network", 3_500L)
            else -> null
        }
    }

    fun detectStreamingBumperMs(title: String?): Long? = detectStreamingBumper(title)?.offsetMs

    fun estimate(
        videoDurationMs: Long?,
        audioDurationMs: Long?,
        targetLanguage: String?,
        audioChapters: List<PlayerChapter> = emptyList(),
        videoChapters: List<PlayerChapter> = emptyList(),
        streamTitle: String? = null,
        videoStreamTitle: String? = null,
        subtitleMatch: DubSubtitleSyncAligner.SubtitleMatch? = null,
        acousticMatch: AcousticSyncMatcher.AcousticMatch? = null
    ): SyncDecision? {
        // 0. Evidência de Alinhamento de Legendas (AutoSync Cue Timeline Cross-Matching)
        if (subtitleMatch != null && subtitleMatch.confidence >= 0.65f) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = subtitleMatch.initialAudioOffsetMs,
                    confidence = subtitleMatch.confidence,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(subtitleMatch.initialAudioOffsetMs),
                approximate = false,
                methodName = "Sub-Bridge Legendas (RANSAC)",
                logicDescription = "Consenso de ${subtitleMatch.matchedCues} falas (offset=${subtitleMatch.initialAudioOffsetMs}ms, conf=${String.format(java.util.Locale.US, "%.0f%%", subtitleMatch.confidence * 100)})",
                tVideoMs = null,
                tAudioMs = null,
                acousticNccScore = null,
                winningLayer = "Camada 3: Sub-Bridge Legendas (RANSAC)"
            )
        }

        // 1. Evidência Acústica Mestre (Correlação Cruzada M&E L-R / Transientes de Silêncio)
        if (acousticMatch != null && acousticMatch.confidence >= 0.45f) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = acousticMatch.offsetMs,
                    confidence = acousticMatch.confidence,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(acousticMatch.offsetMs),
                approximate = false,
                methodName = "Análise Acústica M&E (100% CPU)",
                logicDescription = "Correlação M&E L-R (offset=${acousticMatch.offsetMs}ms, NCC=${String.format(java.util.Locale.US, "%.0f%%", acousticMatch.confidence * 100)})",
                tVideoMs = acousticMatch.tVideoMs,
                tAudioMs = acousticMatch.tAudioMs,
                acousticNccScore = acousticMatch.confidence * 100f,
                winningLayer = "Camada 4: Acústica M&E (NCC ${String.format(java.util.Locale.US, "%.0f%%", acousticMatch.confidence * 100)})"
            )
        }

        // 2. Extração de tag explícita de delay/sync no título do release (ex: "DELAY +1200ms", "sync=-800ms")
        val explicitDelay = extractExplicitDelayFromTitle(streamTitle)
        if (explicitDelay != null) {
            val offset = -explicitDelay
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = offset,
                    confidence = 0.99f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(explicitDelay),
                approximate = false,
                methodName = "Tag de Delay do Release",
                logicDescription = "Tag explícita no título: delay ${explicitDelay}ms (offset=${offset}ms)",
                winningLayer = "Camada 2: Tag do Release"
            )
        }

        // 3. Se temos capítulos em ambas as fontes, detectamos o offset de abertura por capítulos
        val chapterOffset = detectChapterOffset(audioChapters, videoChapters)
        if (chapterOffset != null) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = chapterOffset,
                    confidence = 0.95f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(chapterOffset),
                approximate = false,
                methodName = "Capítulos Matroska",
                logicDescription = "Casamento de capítulos de corte/abertura (offset=${chapterOffset}ms)",
                winningLayer = "Camada 2: Capítulos Matroska"
            )
        }

        // 4. Detecção de vinheta em qualquer uma das fontes por capítulos assíncronos
        val audioIntroOffset = detectIntroChapterOffset(audioChapters)
        val videoIntroOffset = detectIntroChapterOffset(videoChapters)
        val asymmetricIntroOffset: Long? = when {
            audioIntroOffset != null && videoIntroOffset != null -> videoIntroOffset - audioIntroOffset
            audioIntroOffset != null -> -audioIntroOffset
            videoIntroOffset != null -> videoIntroOffset
            else -> null
        }

        // 5. Se não temos a duração de ambos
        if (videoDurationMs == null || audioDurationMs == null || videoDurationMs <= 0L || audioDurationMs <= 0L) {
            val offset = asymmetricIntroOffset ?: 0L
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = offset,
                    confidence = if (offset != 0L) 0.85f else 0.5f,
                    isBlind = offset == 0L,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = if (offset != 0L) abs(offset) else Long.MAX_VALUE,
                approximate = offset == 0L,
                methodName = if (offset != 0L) "Capítulo de Abertura" else "Fallback sem Duração",
                logicDescription = "Sem metadados de duração das duas fontes (offset=${offset}ms)",
                winningLayer = "Camada 1: Fallback sem Duração"
            )
        }

        val diff = audioDurationMs - videoDurationMs

        // Rejeita fontes com diferença excessiva (cortes totalmente diferentes, versão de cinema vs estendida, etc.)
        val maxAllowedShorter = if (videoIntroOffset != null) videoIntroOffset + 15_000L else 35_000L
        val maxAllowedLonger = if (audioIntroOffset != null) audioIntroOffset + 30_000L else 60_000L
        if (diff < -maxAllowedShorter || diff > maxAllowedLonger) return null

        // Verificação de PAL Speedup (áudio a 25 fps vs vídeo a 23.976 fps: ratio ~0.959)
        val ratio = audioDurationMs.toDouble() / videoDurationMs.toDouble()
        val isPalSpeedup = ratio in 0.955..0.965
        val scale = if (isPalSpeedup) ratio else 1.0

        // Se houver vinheta detectada assimetricamente por capítulos, priorizamos esse offset real
        if (asymmetricIntroOffset != null && abs(asymmetricIntroOffset) in 1_000L..90_000L) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = asymmetricIntroOffset,
                    scale = scale,
                    confidence = 0.94f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(diff),
                approximate = false,
                methodName = "Capítulo de Abertura",
                logicDescription = "Vinheta identificada por capítulos (offset=${asymmetricIntroOffset}ms)",
                tVideoMs = videoIntroOffset ?: 0L,
                tAudioMs = audioIntroOffset ?: 0L,
                winningLayer = "Camada 2: Capítulos de Abertura"
            )
        }

        // 6. Detecção de vinheta de streaming por bumpers de plataforma nos títulos
        // Convenção matemática rigorosa: offsetMs = T_video - T_audio
        val videoBumper = detectStreamingBumper(videoStreamTitle)
        val audioBumper = detectStreamingBumper(streamTitle)
        val tVideo = videoBumper?.offsetMs ?: 0L
        val tAudio = audioBumper?.offsetMs ?: 0L

        if (videoBumper != null || audioBumper != null) {
            val streamingBumperOffset = tVideo - tAudio
            val bumperDetail = when {
                videoBumper != null && audioBumper == null -> "Vídeo com vinheta ${videoBumper.name} (T_video=${tVideo}ms); áudio sem vinheta (T_audio=0ms)"
                audioBumper != null && videoBumper == null -> "Áudio dublado com vinheta ${audioBumper.name} (T_audio=${tAudio}ms); vídeo sem vinheta (T_video=0ms)"
                else -> "Vídeo ${videoBumper?.name} (T_video=${tVideo}ms) vs Áudio ${audioBumper?.name} (T_audio=${tAudio}ms)"
            }
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = streamingBumperOffset,
                    scale = scale,
                    confidence = 0.91f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(diff),
                approximate = false,
                methodName = "Bumper de Plataforma (Regex)",
                logicDescription = "$bumperDetail -> offsetMs = T_video - T_audio = ${streamingBumperOffset}ms",
                tVideoMs = tVideo,
                tAudioMs = tAudio,
                winningLayer = "Camada 2: Bumper de Plataforma (Regex)"
            )
        }

        // 7. Sem evidências prévias de vinhetas ou capítulos:
        // A duração total do container (diff) varia devido a créditos de dublagem finais (1s a 45s)
        // e nunca reflete a abertura. Portanto, assumimos offset 0ms inicial com baixa confiança,
        // aguardando o refinamento real da Correlação Acústica M&E e Sub-Bridge em background.
        return SyncDecision(
            timeline = DubTimelineMap(
                initialAudioOffsetMs = 0L,
                scale = scale,
                confidence = 0.50f,
                isBlind = true,
                targetAudioLanguage = targetLanguage
            ),
            durationGapMs = abs(diff),
            approximate = true,
            methodName = "Alinhamento Base (Aguardando Acústico)",
            logicDescription = "Duração de container não usada para vinhetas. Início em 0ms aguardando análise acústica M&E.",
            tVideoMs = 0L,
            tAudioMs = 0L,
            acousticNccScore = null,
            winningLayer = "Camada 1: Alinhamento Base (Fallback)"
        )
    }

    fun detectArchetypeLeadInDelta(videoTitle: String?, audioTitle: String?): Long? {
        // Desativado por diretriz: heurísticas baseadas em strings de release geram falsos positivos.
        // O offset base deve ser estritamente 0ms caso não haja evidência física/acústica ou de capítulos.
        return null
    }

    private fun extractExplicitDelayFromTitle(title: String?): Long? {
        if (title.isNullOrBlank()) return null
        val match = DELAY_TAG_REGEX.find(title) ?: return null
        val numStr = match.groupValues.getOrNull(2) ?: return null
        val unit = match.groupValues.getOrNull(3)?.lowercase() ?: "ms"
        val num = numStr.toDoubleOrNull() ?: return null
        return if (unit == "s") (num * 1000.0).toLong() else num.toLong()
    }

    private fun detectChapterOffset(
        audioChapters: List<PlayerChapter>,
        videoChapters: List<PlayerChapter>
    ): Long? {
        if (audioChapters.size < 2 || videoChapters.size < 2) return null
        // 1. Tenta correspondência do segundo capítulo (início do conteúdo após vinheta)
        val audioSecond = audioChapters.getOrNull(1)?.startMs ?: return null
        val videoSecond = videoChapters.getOrNull(1)?.startMs ?: return null
        val secondDelta = audioSecond - videoSecond
        if (abs(secondDelta) in 1_000L..120_000L) {
            return secondDelta
        }

        val audioFirstContent = audioChapters.firstOrNull { it.startMs in 1_000L..90_000L } ?: return null
        val videoFirstContent = videoChapters.firstOrNull { it.startMs in 1_000L..90_000L } ?: return null

        val delta = audioFirstContent.startMs - videoFirstContent.startMs
        return if (abs(delta) in 1_000L..90_000L) delta else null
    }

    private fun detectIntroChapterOffset(audioChapters: List<PlayerChapter>): Long? {
        if (audioChapters.size < 2) return null
        val first = audioChapters.firstOrNull() ?: return null
        val second = audioChapters.getOrNull(1) ?: return null

        val firstName = first.title.orEmpty().lowercase()
        val isExplicitIntro = firstName.contains("intro") || firstName.contains("opening") ||
            firstName.contains("abertura") || firstName.contains("vinheta") || firstName.contains("logo")

        if (isExplicitIntro && second.startMs in 2_000L..90_000L) {
            return second.startMs
        }
        // Se o primeiro capítulo termina entre 3s e 40s e o segundo começa ali, é típico de vinheta
        if (second.startMs in 3_000L..40_000L) {
            return second.startMs
        }
        return null
    }
}
