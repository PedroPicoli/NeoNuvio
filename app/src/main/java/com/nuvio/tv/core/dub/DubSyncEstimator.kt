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
        val approximate: Boolean
    )

    private val DELAY_TAG_REGEX = Regex("""(?i)\b(delay|sync|offset)\s*[:=]?\s*([+-]?\d+(?:\.\d+)?)\s*(ms|s)?\b""")

    fun estimate(
        videoDurationMs: Long?,
        audioDurationMs: Long?,
        targetLanguage: String?,
        audioChapters: List<PlayerChapter> = emptyList(),
        videoChapters: List<PlayerChapter> = emptyList(),
        streamTitle: String? = null,
        acousticMatch: AcousticSyncMatcher.AcousticMatch? = null
    ): SyncDecision? {
        // 0. Evidência Acústica Mestre (Correlação Cruzada M&E L-R / Transientes de Silêncio)
        if (acousticMatch != null && acousticMatch.confidence >= 0.50f) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = acousticMatch.offsetMs,
                    confidence = acousticMatch.confidence,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(acousticMatch.offsetMs),
                approximate = false
            )
        }

        // 1. Extração de tag explícita de delay/sync no título do release (ex: "DELAY +1200ms", "sync=-800ms")
        val explicitDelay = extractExplicitDelayFromTitle(streamTitle)
        if (explicitDelay != null) {
            return SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = explicitDelay,
                    confidence = 0.99f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(explicitDelay),
                approximate = false
            )
        }

        // 2. Se temos capítulos em ambas as fontes, detectamos o offset de abertura por capítulos
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
                approximate = false
            )
        }

        // Detecção de vinheta em qualquer uma das fontes (mesmo se a outra fonte não tiver capítulos)
        val audioIntroOffset = detectIntroChapterOffset(audioChapters)
        val videoIntroOffset = detectIntroChapterOffset(videoChapters)
        val asymmetricIntroOffset: Long? = when {
            audioIntroOffset != null && videoIntroOffset != null -> audioIntroOffset - videoIntroOffset
            audioIntroOffset != null -> audioIntroOffset
            videoIntroOffset != null -> -videoIntroOffset
            else -> null
        }

        // 3. Se não temos a duração de ambos
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
                approximate = offset == 0L
            )
        }

        val diff = audioDurationMs - videoDurationMs

        // Rejeita fontes com diferença excessiva (cortes totalmente diferentes, versão de cinema vs estendida, etc.)
        // Se o áudio for mais de 20s mais curto que o vídeo (sem vinheta correspondente no vídeo), é corte incompatível/resumido.
        val maxAllowedShorter = if (videoIntroOffset != null) videoIntroOffset + 15_000L else 20_000L
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
                approximate = false
            )
        }

        return when {
            // 1. Quase idênticos (mesma release/corte): alinhamento direto
            abs(diff) <= ALIGNED_TOLERANCE_MS -> SyncDecision(
                timeline = DubTimelineMap(
                    initialAudioOffsetMs = 0L,
                    scale = scale,
                    confidence = 0.98f,
                    isBlind = false,
                    targetAudioLanguage = targetLanguage
                ),
                durationGapMs = abs(diff),
                approximate = false
            )

            // 2. Áudio mais longo: fonte dublada possui vinheta/abertura adicional no início
            diff > 0L -> {
                val effectiveOffset = if (audioIntroOffset != null && abs(audioIntroOffset - diff) < 5_000L) {
                    audioIntroOffset
                } else if (audioIntroOffset != null && audioIntroOffset in 2_000L..45_000L) {
                    audioIntroOffset
                } else {
                    diff
                }
                SyncDecision(
                    timeline = DubTimelineMap(
                        initialAudioOffsetMs = effectiveOffset,
                        scale = scale,
                        confidence = 0.90f,
                        isBlind = false,
                        targetAudioLanguage = targetLanguage
                    ),
                    durationGapMs = diff,
                    approximate = false
                )
            }

            // 3. Vídeo mais longo: o vídeo possui vinheta adicional no início (offset negativo = áudio atrasado)
            else -> {
                val delayMs = if (videoIntroOffset != null && abs(videoIntroOffset - abs(diff)) < 5_000L) {
                    videoIntroOffset
                } else {
                    abs(diff)
                }
                SyncDecision(
                    timeline = DubTimelineMap(
                        initialAudioOffsetMs = -delayMs,
                        scale = scale,
                        confidence = 0.85f,
                        isBlind = false,
                        targetAudioLanguage = targetLanguage
                    ),
                    durationGapMs = delayMs,
                    approximate = false
                )
            }
        }
    }

    private fun extractExplicitDelayFromTitle(title: String?): Long? {
        if (title.isNullOrBlank()) return null
        val match = DELAY_TAG_REGEX.find(title) ?: return null
        val numStr = match.groupValues.getOrNull(1) ?: return null
        val unit = match.groupValues.getOrNull(2)?.lowercase() ?: "ms"
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
