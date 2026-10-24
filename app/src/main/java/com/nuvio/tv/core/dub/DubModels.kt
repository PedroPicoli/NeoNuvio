package com.nuvio.tv.core.dub

import com.nuvio.tv.domain.model.Stream

/**
 * Ajuste de corte em um ponto do conteúdo (ex: corte de anúncio ou pausa de tela preta).
 */
data class DubCutAdjustment(
    val videoPointMs: Long,
    val audioSkipMs: Long
)

/**
 * Mapa de sincronização temporal para alinhar o áudio de outra fonte ao vídeo principal.
 */
data class DubTimelineMap(
    val initialAudioOffsetMs: Long = 0L,
    val scale: Double = 1.0,
    val cuts: List<DubCutAdjustment> = emptyList(),
    val confidence: Float = 1.0f,
    val isBlind: Boolean = false,
    val targetAudioTrackId: String? = null,
    val targetAudioLanguage: String? = null
) {
    /**
     * Mapeia a posição do vídeo (em microssegundos) para a posição correspondente no arquivo de áudio.
     */
    fun mapVideoTimeToAudioTimeUs(videoUs: Long): Long {
        val videoMs = videoUs / 1000L
        var offsetMs = initialAudioOffsetMs
        for (cut in cuts) {
            if (videoMs >= cut.videoPointMs) {
                offsetMs += cut.audioSkipMs
            }
        }
        val scaledVideoMs = (videoMs * scale).toLong()
        val mappedAudioMs = scaledVideoMs + offsetMs
        return (mappedAudioMs * 1000L).coerceAtLeast(0L)
    }

    /**
     * Retorna o atraso (delay) dinâmico a ser aplicado na amostra de áudio para a posição atual.
     */
    fun getAudioDelayUs(audioTimeUs: Long): Long {
        val audioTimeMs = audioTimeUs / 1000L
        var accumulatedCutsMs = 0L
        for (cut in cuts) {
            if (audioTimeMs >= (cut.videoPointMs + initialAudioOffsetMs + accumulatedCutsMs)) {
                accumulatedCutsMs += cut.audioSkipMs
            }
        }
        val totalOffsetMs = initialAudioOffsetMs + accumulatedCutsMs
        // delay a ser subtraído ou somado
        return totalOffsetMs * 1000L
    }

    /**
     * Determina se uma amostra de áudio deve ser descartada (ex: está na vinheta inicial
     * ou dentro de um trecho que foi cortado na versão da imagem).
     */
    fun shouldDropAudioSample(audioTimeUs: Long): Boolean {
        val audioTimeMs = audioTimeUs / 1000L
        if (initialAudioOffsetMs > 0 && audioTimeMs < initialAudioOffsetMs) {
            // A fonte de áudio possui vinheta antes do início do vídeo
            return true
        }
        var accumulatedCutsMs = 0L
        for (cut in cuts) {
            val cutStartAudioMs = cut.videoPointMs + initialAudioOffsetMs + accumulatedCutsMs
            val cutEndAudioMs = cutStartAudioMs + cut.audioSkipMs
            if (cut.audioSkipMs > 0 && audioTimeMs in cutStartAudioMs until cutEndAudioMs) {
                return true
            }
            accumulatedCutsMs += cut.audioSkipMs
        }
        return false
    }
}

/**
 * Informações sobre um stream candidato a fornecer áudio dublado.
 */
data class DubCandidate(
    val stream: Stream,
    val resolution: String,
    val is4kOrHigher: Boolean,
    val isTorrent: Boolean,
    val hasOriginalTrack: Boolean,
    val detectedLanguages: Set<String>,
    val priorityScore: Int
)

/**
 * Tipo de alerta/diálogo que requer decisão do usuário antes de prosseguir com a dublagem forçada.
 */
enum class ForceDubWarningType {
    WARNING_4K_AUDIO,
    WARNING_BLIND_SYNC
}
