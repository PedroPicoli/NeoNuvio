package com.nuvio.tv.core.dub

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Ancoragem Acústica por Isolamento da Trilha M&E (Music & Effects) via Cancelamento de Fase (L-R)
 * e Emparelhamento de Atos por Silêncio de Corte (Dynamic Silence Trimming).
 *
 * Princípios:
 * 1. Em gravações estéreo, a fala está centrada (L = R em fase). Ao calcular o Sinal Side S[n] = L[n] - R[n],
 *    as vozes em idiomas diferentes sofrem cancelamento de fase quase total, restando a trilha M&E (músicas e
 *    efeitos sonoros) que é matematicamente idêntica entre o áudio original e o dublado.
 * 2. A correlação cruzada dos envelopes M&E a 100 Hz encontra o atraso exato (Δt) com precisão de ±10 ms.
 * 3. A detecção de macro-silêncios (quedas < -45 dBFS por > 300 ms seguidas de transiente) detecta o fim exato da
 *    vinheta e pontos de corte entre atos.
 */
object AcousticSyncMatcher {

    data class AcousticMatch(
        val offsetMs: Long,
        val confidence: Float,
        val silenceOffsetMs: Long? = null
    )

    data class SilenceInterval(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Extrai o envelope de energia do sinal Side (|L - R|) a cada [windowMs] milissegundos (padrão: 10 ms = 100 Hz).
     * O sinal resultante contém primariamente música e efeitos sonoros, com a fala centralizada cancelada.
     */
    fun extractSideEnvelope(
        pcm16Samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        windowMs: Int = 10
    ): FloatArray {
        if (pcm16Samples.isEmpty() || channels <= 0 || sampleRate <= 0) return FloatArray(0)
        val samplesPerWindow = (sampleRate * windowMs) / 1000
        if (samplesPerWindow <= 0) return FloatArray(0)

        val totalFrames = pcm16Samples.size / channels
        val windowCount = totalFrames / samplesPerWindow
        if (windowCount <= 0) return FloatArray(0)

        val envelope = FloatArray(windowCount)
        var sampleIdx = 0

        for (w in 0 until windowCount) {
            var sumSideSquare = 0.0
            for (f in 0 until samplesPerWindow) {
                if (channels >= 2) {
                    val left = pcm16Samples[sampleIdx].toDouble()
                    val right = pcm16Samples[sampleIdx + 1].toDouble()
                    // Sinal Side (L - R): cancela diálogos centralizados em fase
                    val side = (left - right) / 32768.0
                    sumSideSquare += side * side
                } else {
                    // Mono fallback
                    val mono = pcm16Samples[sampleIdx].toDouble() / 32768.0
                    sumSideSquare += mono * mono
                }
                sampleIdx += channels
            }
            val rms = sqrt(sumSideSquare / samplesPerWindow)
            envelope[w] = rms.toFloat()
        }

        // Normalização de pico para correlação estável
        var maxVal = 0.00001f
        for (v in envelope) {
            if (v > maxVal) maxVal = v
        }
        for (i in envelope.indices) {
            envelope[i] /= maxVal
        }

        return envelope
    }

    /**
     * Correlação Cruzada 1D Normalizada entre os envelopes de áudio original e dublado.
     * Retorna o atraso em milissegundos e o coeficiente de confiança de Pearson (0.0 a 1.0).
     */
    fun findCrossCorrelationLag(
        origEnvelope: FloatArray,
        dubEnvelope: FloatArray,
        windowMs: Int = 10,
        maxLagMs: Long = 45_000L
    ): AcousticMatch? {
        if (origEnvelope.isEmpty() || dubEnvelope.isEmpty()) return null

        val maxLagWindows = (maxLagMs / windowMs).toInt()
        val minOverlap = minOf((5_000L / windowMs).toInt(), origEnvelope.size / 4, dubEnvelope.size / 4).coerceAtLeast(10)
        val searchMin = -maxLagWindows.coerceAtMost(origEnvelope.size - minOverlap)
        val searchMax = maxLagWindows.coerceAtMost(dubEnvelope.size - minOverlap)

        var bestLagWindows = 0
        var bestCorrelation = -1.0f

        // Se lag > 0: o áudio dublado contém vinheta extra (evento ocorre mais tarde no dublado)
        // orig[origStart + i] se alinha com dub[dubStart + i]
        for (lag in searchMin..searchMax) {
            val origStart = if (lag >= 0) 0 else -lag
            val dubStart = if (lag >= 0) lag else 0
            val overlapLength = minOf(origEnvelope.size - origStart, dubEnvelope.size - dubStart)

            if (overlapLength < minOverlap) continue

            // Zero-Mean Normalized Cross-Correlation (ZNCC)
            var sumO = 0.0
            var sumD = 0.0
            for (i in 0 until overlapLength) {
                sumO += origEnvelope[origStart + i]
                sumD += dubEnvelope[dubStart + i]
            }
            val meanO = sumO / overlapLength
            val meanD = sumD / overlapLength

            var dot = 0.0
            var varO = 0.0
            var varD = 0.0
            for (i in 0 until overlapLength) {
                val oZero = origEnvelope[origStart + i] - meanO
                val dZero = dubEnvelope[dubStart + i] - meanD
                dot += oZero * dZero
                varO += oZero * oZero
                varD += dZero * dZero
            }

            val denom = sqrt(varO * varD)
            if (denom > 0.0001) {
                val zncc = (dot / denom).toFloat()
                if (zncc > bestCorrelation) {
                    bestCorrelation = zncc
                    bestLagWindows = lag
                }
            }
        }

        if (bestCorrelation < 0.40f) return null

        val offsetMs = bestLagWindows.toLong() * windowMs.toLong()
        return AcousticMatch(
            offsetMs = offsetMs,
            confidence = bestCorrelation
        )
    }

    /**
     * Detecta Macro-Silêncios (ex: telas pretas entre vinhetas e cenas de abertura).
     * Um silêncio é caracterizado por envelope RMS abaixo de [thresholdDbfs] por mais de [minDurationMs].
     */
    fun detectSilenceIntervals(
        envelope: FloatArray,
        windowMs: Int = 10,
        thresholdDbfs: Float = -45f,
        minDurationMs: Long = 300L
    ): List<SilenceInterval> {
        if (envelope.isEmpty()) return emptyList()
        val minWindows = (minDurationMs / windowMs).toInt()
        val thresholdLinear = Math.pow(10.0, thresholdDbfs / 20.0).toFloat()

        val intervals = mutableListOf<SilenceInterval>()
        var inSilence = false
        var silenceStartWindow = 0

        for (i in envelope.indices) {
            val isSilent = envelope[i] < thresholdLinear
            if (isSilent && !inSilence) {
                inSilence = true
                silenceStartWindow = i
            } else if (!isSilent && inSilence) {
                inSilence = false
                val duration = i - silenceStartWindow
                if (duration >= minWindows) {
                    intervals.add(
                        SilenceInterval(
                            startMs = silenceStartWindow.toLong() * windowMs,
                            endMs = i.toLong() * windowMs
                        )
                    )
                }
            }
        }
        return intervals
    }

    /**
     * Emparelhamento por Transiente pós-vinheta:
     * Compara o primeiro transiente sonoro abrupto após um silêncio no início do conteúdo entre as duas fontes.
     */
    fun estimateIntroSilenceDelta(
        origSilences: List<SilenceInterval>,
        dubSilences: List<SilenceInterval>,
        maxIntroTimeMs: Long = 45_000L
    ): Long? {
        val origFirstContentStart = origSilences.firstOrNull { it.endMs in 1_000L..maxIntroTimeMs }?.endMs ?: 0L
        val dubFirstContentStart = dubSilences.firstOrNull { it.endMs in 1_000L..maxIntroTimeMs }?.endMs ?: return null

        val delta = dubFirstContentStart - origFirstContentStart
        return if (abs(delta) in 1_000L..maxIntroTimeMs) delta else null
    }
}
