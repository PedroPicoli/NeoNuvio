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

    data class LagResult(
        val lagMs: Long,
        val zncc: Float,
        val psr: Float
    )

    data class AcousticMatch(
        val offsetMs: Long,
        val confidence: Float,
        val maxZncc: Float = 0f,
        val psr: Float = 0f,
        val isValid: Boolean = true,
        val minPsr: Float = 3.0f,
        val minZncc: Float = 0.16f,
        val silenceOffsetMs: Long? = null,
        val tVideoMs: Long? = null,
        val tAudioMs: Long? = null,
        val telemetryDiag: String = "",
        val dubSpeechEnvelope: FloatArray? = null,
        val dubMime: String? = null,
        val topLags: List<LagResult> = emptyList()
    )

    data class SilenceInterval(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = endMs - startMs
    }

    /**
     * Filtro IIR Notch de 2ª ordem para atenuação de voz humana (300Hz - 2800Hz).
     */
    class BandStopFilter(sampleRate: Int) {
        private val safeSampleRate = if (sampleRate > 0) sampleRate else 48000
        private val fCenter = 1500.0 / safeSampleRate
        private val bw = 2500.0 / safeSampleRate
        private val r = (1.0 - 3.0 * bw).coerceIn(0.01, 0.99)
        private val cosCenter = kotlin.math.cos(2.0 * Math.PI * fCenter)
        private val k = (1.0 - 2.0 * r * cosCenter + r * r) /
            (2.0 - 2.0 * cosCenter)
        private val a0 = k
        private val a1 = -2.0 * k * cosCenter
        private val a2 = k
        private val b1 = 2.0 * r * cosCenter
        private val b2 = -r * r

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun filter(x: Float): Float {
            val xd = x.toDouble()
            val y = a0 * xd + a1 * x1 + a2 * x2 + b1 * y1 + b2 * y2
            x2 = x1
            x1 = xd
            y2 = y1
            y1 = y
            return y.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    /**
     * Aplica filtro Band-Stop (Notch 300Hz - 2800Hz) atenuando a fala humana e destacando a trilha M&E (Music & Effects).
     */
    fun applyBandStopFilter(pcm: ShortArray, sampleRate: Int): ShortArray {
        if (pcm.isEmpty() || sampleRate <= 0) return pcm
        val out = ShortArray(pcm.size)
        val filter = BandStopFilter(sampleRate)
        for (i in pcm.indices) {
            val norm = pcm[i] / 32768.0f
            val filtered = filter.filter(norm)
            out[i] = (filtered * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /**
     * Derivada de primeira ordem (Onset Detection) destacando transientes sonoros e batidas da trilha M&E.
     */
    fun computeOnsetEnvelope(envelope: FloatArray): FloatArray {
        if (envelope.size <= 1) return envelope
        val onsets = FloatArray(envelope.size)
        for (i in 1 until envelope.size) {
            val diff = envelope[i] - envelope[i - 1]
            onsets[i] = if (diff > 0f) diff else 0f
        }
        return onsets
    }

    /**
     * Extrai o envelope M&E combinando Isolamento Espacial de canais + Log-RMS + Onset de ataques a cada 10ms.
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

        val bandStop = BandStopFilter(sampleRate)
        val logRms = FloatArray(windowCount)
        var sampleIdx = 0

        for (w in 0 until windowCount) {
            var sumSquare = 0.0
            for (f in 0 until samplesPerWindow) {
                val meSample = when {
                    channels >= 6 -> {
                        // 5.1/7.1 Surround: Descarte do Canal Central (vocal)
                        val fl = pcm16Samples[sampleIdx] / 32768.0f
                        val fr = pcm16Samples[sampleIdx + 1] / 32768.0f
                        val sl = pcm16Samples[sampleIdx + 4] / 32768.0f
                        val sr = pcm16Samples[sampleIdx + 5] / 32768.0f
                        abs(fl - fr) + 0.7f * (abs(sl) + abs(sr))
                    }
                    channels == 2 -> {
                        // Estéreo 2.0: Side (|L - R|) + 0.25 * Mid filtrado
                        val l = pcm16Samples[sampleIdx] / 32768.0f
                        val r = pcm16Samples[sampleIdx + 1] / 32768.0f
                        val side = abs(l - r)
                        val mid = 0.5f * (l + r)
                        val midFiltered = abs(bandStop.filter(mid))
                        0.75f * side + 0.25f * midFiltered
                    }
                    else -> {
                        // Mono 1.0: Notch Band-Stop
                        val mono = pcm16Samples[sampleIdx] / 32768.0f
                        abs(bandStop.filter(mono))
                    }
                }
                sumSquare += meSample * meSample
                sampleIdx += channels
            }
            val rms = sqrt(sumSquare / samplesPerWindow).toFloat()
            logRms[w] = log10(1.0f + rms)
        }

        // Normalização de pico Log-RMS
        var maxRms = 0.00001f
        for (v in logRms) if (v > maxRms) maxRms = v
        for (i in logRms.indices) logRms[i] /= maxRms

        // Curva Onset (ataques de transiente)
        val onsets = computeOnsetEnvelope(logRms)
        var maxOnset = 0.00001f
        for (v in onsets) if (v > maxOnset) maxOnset = v
        for (i in onsets.indices) onsets[i] /= maxOnset

        // Combinação equilibrada 50% Log-RMS + 50% Onset
        val result = FloatArray(windowCount)
        for (i in 0 until windowCount) {
            result[i] = 0.5f * logRms[i] + 0.5f * onsets[i]
        }
        return result
    }

    /**
     * Derivada Positiva de Ataque (Onset Flux):
     * onset[i] = maxOf(0f, env[i] - env[maxOf(0, i - 20)])
     * Zera platôs saturados (como blocos contínuos de 1.0) e destaca exclusivamente
     * as subidas abruptas de cena e transientes de som/efeitos.
     */
    fun computeOnsetFlux(env: FloatArray, attackWindowBins: Int = 20): FloatArray {
        if (env.isEmpty()) return env
        val onsets = FloatArray(env.size)
        for (i in env.indices) {
            val prev = if (i >= attackWindowBins) env[i - attackWindowBins] else 0f
            val diff = env[i] - prev
            onsets[i] = if (diff > 0f) diff else 0f
        }
        return onsets
    }

    /**
     * Correlação Acústica M&E com Ancoragem Temporal Absoluta e Critério de Prominência Estatística (PSR / Z-Score).
     *
     * Analisa o intervalo entre 10s e 35s (evitando vinhetas iniciais divergentes).
     * Busca de lags de -10.000ms a +10.000ms.
     * Aceita o alinhamento sempre que (maxZncc >= 0.16f && psr >= 3.2f) || maxZncc >= 0.40f.
     */
    fun findCrossCorrelationLag(
        origEnvelope: FloatArray,
        dubEnvelope: FloatArray,
        windowMs: Int = 10,
        skipInitialMs: Long = 10_000L,
        targetEndMs: Long = 35_000L,
        maxLagMs: Long = 10_000L,
        videoDiag: String = "áudio",
        dubDiag: String = "áudio",
        subBridgeVadLagMs: Long? = null
    ): AcousticMatch? {
        if (origEnvelope.isEmpty() || dubEnvelope.isEmpty()) return null

        val startBin = (skipInitialMs / windowMs).toInt().coerceIn(0, maxOf(0, origEnvelope.size - 200))
        val endBin = (targetEndMs / windowMs).toInt().coerceIn(startBin + 200, origEnvelope.size)
        val patternLength = endBin - startBin
        if (patternLength < 200) return null

        // Converte ambos os sinais para Derivada Positiva de Ataque (Onset Flux) de 200ms
        // Eliminando platôs estáticos de 1.0 e mantendo apenas as subidas abruptas de cena
        val xSignal = computeOnsetFlux(origEnvelope, attackWindowBins = 20)
        val ySignal = computeOnsetFlux(dubEnvelope, attackWindowBins = 20)

        val maxLagWindows = (maxLagMs / windowMs).toInt()
        val searchMin = -maxLagWindows
        val searchMax = maxLagWindows

        // 1. Split-Window Consensus (Duas Metades Independentes):
        // Janela 1: Início do Episódio (ex: bins 550..1400) -> tau1
        // Janela 2: Desenvolvimento (ex: bins 1400..2200) -> tau2
        val splitMidBin = if (patternLength >= 600) {
            startBin + (patternLength / 2)
        } else -1

        val tau1 = if (splitMidBin > startBin) {
            findBestLagInBinRange(xSignal, ySignal, startBin, splitMidBin, searchMin, searchMax, windowMs)
        } else null

        val tau2 = if (splitMidBin > startBin) {
            findBestLagInBinRange(xSignal, ySignal, splitMidBin, endBin, searchMin, searchMax, windowMs)
        } else null

        // 2. Janela Global (bins startBin..endBin):
        val znccMap = LinkedHashMap<Int, Float>()

        for (lag in searchMin..searchMax) {
            val dubStart = startBin + lag
            val validDubStart = maxOf(0, dubStart)
            val validDubEnd = minOf(ySignal.size, dubStart + patternLength)
            val overlap = validDubEnd - validDubStart
            if (overlap < 200) continue

            val origOffset = validDubStart - dubStart
            val xStart = startBin + origOffset

            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until overlap) {
                sumX += xSignal[xStart + i]
                sumY += ySignal[validDubStart + i]
            }
            val meanX = sumX / overlap
            val meanY = sumY / overlap

            var dot = 0.0
            var varX = 0.0
            var varY = 0.0
            for (i in 0 until overlap) {
                val xZero = xSignal[xStart + i] - meanX
                val yZero = ySignal[validDubStart + i] - meanY
                dot += xZero * yZero
                varX += xZero * xZero
                varY += yZero * yZero
            }

            val denom = sqrt(varX * varY)
            if (denom > 0.0001) {
                val zncc = (dot / denom).toFloat()
                znccMap[lag] = zncc
            }
        }

        if (znccMap.isEmpty()) return null

        val bestEntry = znccMap.maxByOrNull { it.value } ?: return null
        val bestLag = bestEntry.key
        val maxZncc = bestEntry.value
        val bestLagMs = -(bestLag.toLong() * windowMs.toLong())

        // Cálculo da Prominência Estatística (PSR / Z-Score) excluindo margem de ±200ms (±20 bins)
        val sidelobes = znccMap.filter { abs(it.key - bestLag) > 20 }.values
        val meanSidelobe = if (sidelobes.isNotEmpty()) sidelobes.average().toFloat() else 0f
        val variance = if (sidelobes.isNotEmpty()) {
            sidelobes.map { (it - meanSidelobe) * (it - meanSidelobe) }.average().toFloat()
        } else 0f
        val stdSidelobe = sqrt(maxOf(1e-6f, variance))
        val psr = if (stdSidelobe > 0.0001f) (maxZncc - meanSidelobe) / stdSidelobe else 0f

        // Cálculo dos Top 10 lags para diagnóstico detalhado
        val topLags = znccMap.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { entry ->
                val lagMs = -(entry.key.toLong() * windowMs.toLong())
                val curZncc = entry.value
                val curPsr = if (stdSidelobe > 0.0001f) (curZncc - meanSidelobe) / stdSidelobe else 0f
                LagResult(lagMs, curZncc, curPsr)
            }

        // Regra de Consenso Estrito (Anti-Alucinação):
        // abs(tau1 - tau2) <= 250ms OU abs(bestLagMs - subBridgeVadLagMs) <= 300ms
        val splitConsensus = if (tau1 != null && tau2 != null) {
            abs(tau1 - tau2) <= 250L
        } else true

        val vadConsensus = subBridgeVadLagMs != null && abs(bestLagMs - subBridgeVadLagMs) <= 300L
        val consensusPassed = splitConsensus || vadConsensus

        val minPsr = 3.5f
        val minZncc = 0.20f
        val isValid = consensusPassed && psr >= minPsr && maxZncc >= minZncc

        val consensusDiag = if (!splitConsensus && !vadConsensus) {
            " [Split-Window REJEITADO: tau1=${tau1}ms vs tau2=${tau2}ms]"
        } else if (tau1 != null && tau2 != null) {
            " [Split-Window OK: diff=${abs(tau1 - tau2)}ms]"
        } else ""

        val telemetry = "[Vid: $videoDiag | Dub: $dubDiag | ZNCC: ${String.format(java.util.Locale.US, "%.2f", maxZncc)} | PSR: ${String.format(java.util.Locale.US, "%.1f", psr)} | BestLag: ${if (bestLagMs >= 0) "+" else ""}${bestLagMs}ms$consensusDiag]"

        // Fórmula Honesta de Confiança: Rejeição no Split-Window reduz confiança para < 45%
        val confidence = if (isValid) {
            (0.70f + ((psr - minPsr) / 4.0f) * 0.28f).coerceIn(0.70f, 0.98f)
        } else {
            (psr / minPsr * 0.40f).coerceIn(0.10f, 0.45f)
        }

        val baseVideoMs = startBin.toLong() * windowMs.toLong()
        val tVideo = baseVideoMs
        // Regra 2: Zona Morta de Sincronia Nativa (Deadzone = +-650 ms)
        val effectiveOffsetMs = if (abs(bestLagMs) < 650L) 0L else bestLagMs
        val tAudio = (tVideo - effectiveOffsetMs).coerceAtLeast(0L)

        return AcousticMatch(
            offsetMs = effectiveOffsetMs,
            confidence = confidence,
            maxZncc = maxZncc,
            psr = psr,
            isValid = isValid,
            minPsr = minPsr,
            minZncc = minZncc,
            tVideoMs = tVideo,
            tAudioMs = tAudio,
            telemetryDiag = telemetry,
            topLags = topLags
        )
    }

    private fun findBestLagInBinRange(
        xSignal: FloatArray,
        ySignal: FloatArray,
        rangeStartBin: Int,
        rangeEndBin: Int,
        searchMin: Int,
        searchMax: Int,
        windowMs: Int
    ): Long? {
        val patternLen = rangeEndBin - rangeStartBin
        if (patternLen < 150) return null

        var bestLag = 0
        var maxZncc = -1f

        for (lag in searchMin..searchMax) {
            val dubStart = rangeStartBin + lag
            val validDubStart = maxOf(0, dubStart)
            val validDubEnd = minOf(ySignal.size, dubStart + patternLen)
            val overlap = validDubEnd - validDubStart
            if (overlap < 120) continue

            val origOffset = validDubStart - dubStart
            val xStart = rangeStartBin + origOffset

            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until overlap) {
                sumX += xSignal[xStart + i]
                sumY += ySignal[validDubStart + i]
            }
            val meanX = sumX / overlap
            val meanY = sumY / overlap

            var dot = 0.0
            var varX = 0.0
            var varY = 0.0
            for (i in 0 until overlap) {
                val xZero = xSignal[xStart + i] - meanX
                val yZero = ySignal[validDubStart + i] - meanY
                dot += xZero * yZero
                varX += xZero * xZero
                varY += yZero * yZero
            }

            val denom = sqrt(varX * varY)
            if (denom > 0.0001) {
                val zncc = (dot / denom).toFloat()
                if (zncc > maxZncc) {
                    maxZncc = zncc
                    bestLag = lag
                }
            }
        }
        return if (maxZncc > 0.05f) {
            -(bestLag.toLong() * windowMs.toLong())
        } else null
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

        // Convenção padronizada: T_video - T_audio
        val delta = origFirstContentStart - dubFirstContentStart
        return if (abs(delta) in 1_000L..maxIntroTimeMs) delta else null
    }

    /**
     * Filtro IIR Band-Pass (Passa-Faixa) de 2ª ordem para voz humana (300Hz - 2800Hz).
     * Isola a energia vocal dos dubladores no canal central (Mid = 0.5 * (L + R)).
     */
    class BandPassFilter(sampleRate: Int) {
        private val safeSampleRate = if (sampleRate > 0) sampleRate.toDouble() else 48000.0

        // High-Pass a 300Hz (Q = 0.707)
        private val hpW0 = 2.0 * Math.PI * 300.0 / safeSampleRate
        private val hpAlpha = kotlin.math.sin(hpW0) / (2.0 * 0.707)
        private val hpCos = kotlin.math.cos(hpW0)
        private val hpB0 = (1.0 + hpCos) / 2.0
        private val hpB1 = -(1.0 + hpCos)
        private val hpB2 = (1.0 + hpCos) / 2.0
        private val hpA0 = 1.0 + hpAlpha
        private val hpA1 = -2.0 * hpCos
        private val hpA2 = 1.0 - hpAlpha

        private var hpX1 = 0.0
        private var hpX2 = 0.0
        private var hpY1 = 0.0
        private var hpY2 = 0.0

        // Low-Pass a 2800Hz (Q = 0.707)
        private val lpW0 = 2.0 * Math.PI * 2800.0 / safeSampleRate
        private val lpAlpha = kotlin.math.sin(lpW0) / (2.0 * 0.707)
        private val lpCos = kotlin.math.cos(lpW0)
        private val lpB0 = (1.0 - lpCos) / 2.0
        private val lpB1 = 1.0 - lpCos
        private val lpB2 = (1.0 - lpCos) / 2.0
        private val lpA0 = 1.0 + lpAlpha
        private val lpA1 = -2.0 * lpCos
        private val lpA2 = 1.0 - lpAlpha

        private var lpX1 = 0.0
        private var lpX2 = 0.0
        private var lpY1 = 0.0
        private var lpY2 = 0.0

        fun filter(x: Float): Float {
            val xd = x.toDouble()
            // 1. High-Pass 300Hz
            val hpOut = (hpB0 * xd + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2) / hpA0
            hpX2 = hpX1
            hpX1 = xd
            hpY2 = hpY1
            hpY1 = hpOut

            // 2. Low-Pass 2800Hz
            val lpOut = (lpB0 * hpOut + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2) / lpA0
            lpX2 = lpX1
            lpX1 = hpOut
            lpY2 = lpY1
            lpY1 = lpOut

            return lpOut.coerceIn(-1.0, 1.0).toFloat()
        }
    }

    /**
     * Média móvel suave de raio [windowRadius] (janela de 2 * radius + 1 bins).
     */
    fun applyMovingAverage(arr: FloatArray, windowRadius: Int = 7): FloatArray {
        if (arr.isEmpty()) return arr
        val result = FloatArray(arr.size)
        var windowSum = 0.0
        val n = arr.size
        for (i in 0 until minOf(n, windowRadius)) {
            windowSum += arr[i]
        }
        for (i in 0 until n) {
            val addIdx = i + windowRadius
            val remIdx = i - windowRadius - 1
            if (addIdx < n) windowSum += arr[addIdx]
            if (remIdx >= 0) windowSum -= arr[remIdx]
            val count = minOf(n - 1, i + windowRadius) - maxOf(0, i - windowRadius) + 1
            result[i] = (windowSum / count).toFloat()
        }
        return result
    }

    /**
     * Passo B — Conversão do Áudio Dublado em "Blocos de Frase" (VAD com Fechamento Morfológico estilo FFSubSync/Alass):
     * 1. Threshold dinâmico no percentil 55 dos valores positivos entre 6s e 45s.
     * 2. Binarização rawVad.
     * 3. Fechamento Morfológico: preenche pausas inter-sílabas de até 350 ms (35 bins).
     * 4. Remoção de ruídos curtos (< 120 ms / 12 bins).
     * 5. Média móvel suave de 150 ms (15 bins = raio 7).
     */
    fun buildMorphologicalVadPlateau(
        speechEnv: FloatArray,
        startBin: Int = 600,
        endBin: Int = 4500
    ): FloatArray {
        if (speechEnv.isEmpty()) return FloatArray(0)
        val sBin = startBin.coerceIn(0, speechEnv.size)
        val eBin = endBin.coerceIn(sBin, speechEnv.size)

        val positiveValues = mutableListOf<Float>()
        for (i in sBin until eBin) {
            val v = speechEnv[i]
            if (v > 0.01f) positiveValues.add(v)
        }

        val threshold = if (positiveValues.size >= 20) {
            positiveValues.sort()
            val p55Idx = (positiveValues.size * 0.55).toInt().coerceIn(0, positiveValues.size - 1)
            positiveValues[p55Idx].coerceIn(0.04f, 0.40f)
        } else {
            0.08f
        }

        val rawVad = FloatArray(speechEnv.size) { i ->
            if (speechEnv[i] > threshold) 1.0f else 0.0f
        }

        // Fechamento Morfológico: preenche pausas inter-sílabas de até 350 ms (35 bins)
        val closedVad = rawVad.copyOf()
        var lastVoiceBin = -1
        for (i in closedVad.indices) {
            if (closedVad[i] > 0.5f) {
                if (lastVoiceBin >= 0 && (i - lastVoiceBin) <= 35) {
                    for (k in (lastVoiceBin + 1) until i) {
                        closedVad[k] = 1.0f
                    }
                }
                lastVoiceBin = i
            }
        }

        // Remoção de pulsos isolados < 120 ms (12 bins)
        var pulseStart = -1
        for (i in 0..closedVad.size) {
            val hasVoice = i < closedVad.size && closedVad[i] > 0.5f
            if (hasVoice && pulseStart < 0) {
                pulseStart = i
            } else if (!hasVoice && pulseStart >= 0) {
                val pulseLen = i - pulseStart
                if (pulseLen < 12) {
                    for (k in pulseStart until i) {
                        closedVad[k] = 0.0f
                    }
                }
                pulseStart = -1
            }
        }

        // Média móvel suave de 150 ms (15 bins)
        return applyMovingAverage(closedVad, windowRadius = 7)
    }

    /**
     * Passo C — Combinação de Platô (70%) e Derivada de Bordas (30%):
     * Marca com alta precisão os instantes exatos de início e término das falas.
     */
    fun applyEdgeDetectionAndBlend(
        plateau: FloatArray,
        plateauWeight: Float = 0.70f,
        edgeWeight: Float = 0.30f
    ): FloatArray {
        if (plateau.isEmpty()) return plateau
        val blended = FloatArray(plateau.size)
        val edges = FloatArray(plateau.size)
        var maxEdge = 0.0001f

        for (i in 1 until plateau.size) {
            val diff = abs(plateau[i] - plateau[i - 1])
            edges[i] = diff
            if (diff > maxEdge) maxEdge = diff
        }

        for (i in plateau.indices) {
            val normEdge = edges[i] / maxEdge
            blended[i] = plateauWeight * plateau[i] + edgeWeight * normEdge
        }
        return blended
    }

    /**
     * Pilar 3: Detecção Direta de Fim de Vinheta (Bumper Boundary Detector nos Primeiros 10s):
     * Identifica o vale de silêncio abrupto (150ms a 600ms de queda brusca de energia)
     * que encerra a vinheta de abertura da distribuidora (de 2s a 8s) antes da primeira cena.
     */
    fun detectBumperSilenceEndMs(
        envelope: FloatArray,
        windowMs: Int = 10,
        searchStartMs: Long = 1_500L,
        searchEndMs: Long = 10_000L
    ): Long? {
        if (envelope.isEmpty()) return null
        val startBin = (searchStartMs / windowMs).toInt().coerceIn(0, envelope.size)
        val endBin = (searchEndMs / windowMs).toInt().coerceIn(startBin, envelope.size)

        var hasIntroSound = false
        var silenceStartBin = -1

        for (i in startBin until endBin) {
            val energy = envelope[i]
            if (energy > 0.18f) {
                hasIntroSound = true
            }
            val isSilence = energy < 0.08f
            if (hasIntroSound && isSilence && silenceStartBin < 0) {
                silenceStartBin = i
            } else if (hasIntroSound && !isSilence && silenceStartBin >= 0) {
                val silenceDurationBins = i - silenceStartBin
                // Vale de silêncio pós-vinheta de 150ms a 2.500ms (15 a 250 bins)
                if (silenceDurationBins in 15..250) {
                    return silenceStartBin.toLong() * windowMs.toLong()
                }
                silenceStartBin = -1
            }
        }
        return null
    }

    /**
     * Estima o delta entre o fim das vinhetas de abertura (T_bumper_vid - T_bumper_dub).
     * Jamais executado sobre envelopes Bitstream comprimidos (!isPcmDecoded).
     */
    fun estimateBumperOffsetMs(
        videoEnvelope: FloatArray,
        dubEnvelope: FloatArray,
        isBitstream: Boolean = false
    ): Long? {
        if (isBitstream) return null
        val vidBumper = detectBumperSilenceEndMs(videoEnvelope) ?: return null
        val dubBumper = detectBumperSilenceEndMs(dubEnvelope) ?: return null
        val delta = vidBumper - dubBumper
        return if (abs(delta) < 650L) 0L else delta
    }

    /**
     * Detecta deterministicamente o início do diálogo sustentado pós-vinheta na dublagem:
     * 1. Vinheta inicial (1.0s..5.5s com energia consistente).
     * 2. Vale de silêncio limpo (>= 600ms onde dub_vad_speech < 0.08f, ex: 4.8s..6.8s).
     * 3. Início exato da 1ª fala contínua sustentada (ex: índice 69 = 6.90s com energia >= 0.15f).
     */
    fun detectSustainedDialogueOnsetMs(
        speechEnvelope: FloatArray,
        windowMs: Int = 10,
        maxScanMs: Long = 18_000L
    ): Long? {
        val scanBins = minOf(speechEnvelope.size, (maxScanMs / windowMs).toInt())
        if (scanBins < 250) return null

        // 1. Vinheta inicial entre 1.0s e 5.0s (pelo menos 15 bins com energia > 0.12f)
        var introEnergyBins = 0
        var introEndBin = -1
        for (i in 100 until minOf(520, scanBins)) {
            if (speechEnvelope[i] > 0.12f) {
                introEnergyBins++
                introEndBin = i
            }
        }
        if (introEnergyBins < 12 || introEndBin < 120) return null

        // 2. Vale de silêncio longo (>= 600ms onde dub_vad_speech < 0.08f, tolerando ruído pontual residual)
        var bestSilenceStart = -1
        var bestSilenceEnd = -1
        var currentSilenceStart = -1
        var currentSilenceLen = 0

        for (i in introEndBin until scanBins) {
            if (speechEnvelope[i] < 0.08f) {
                if (currentSilenceStart < 0) currentSilenceStart = i
                currentSilenceLen++
            } else {
                if (currentSilenceLen >= 60) { // pelo menos 600ms contínuos
                    bestSilenceStart = currentSilenceStart
                    bestSilenceEnd = i
                    break
                }
                currentSilenceStart = -1
                currentSilenceLen = 0
            }
        }

        if (bestSilenceEnd <= 0 && currentSilenceLen >= 60) {
            bestSilenceStart = currentSilenceStart
            bestSilenceEnd = currentSilenceStart + currentSilenceLen
        }

        if (bestSilenceEnd <= 0) return null

        // 3. Primeira fala sustentada pós-silêncio:
        val searchLimit = minOf(bestSilenceEnd + 200, scanBins - 30)
        for (i in bestSilenceEnd until searchLimit) {
            if (speechEnvelope[i] >= 0.14f) {
                val checkWindow = minOf(80, scanBins - i)
                if (checkWindow < 20) break
                var sum = 0.0
                var peak = 0.0f
                for (k in 0 until checkWindow) {
                    val v = speechEnvelope[i + k]
                    sum += v
                    if (v > peak) peak = v
                }
                val avg = (sum / checkWindow).toFloat()
                if (avg >= 0.10f || peak >= 0.25f) {
                    return i.toLong() * windowMs.toLong()
                }
            }
        }
        return null
    }

    /**
     * Ancoragem Direta por Início de Diálogo (Dialogue Onset Lead-In Anchor):
     * Analisa o instante da primeira fala nas legendas reais do vídeo contra o primeiro ataque
     * de energia vocal sustentada no envelope da dublagem nos primeiros 30 segundos.
     *
     * Convenção Universal: Offset = T_video - T_audio
     * Exemplo: Legenda do vídeo fala em 2150ms; voz da dublagem começa em 150ms -> Offset = +2000ms.
     */
    /**
     * Consenso de Múltiplas Transições Vocais (0s..42s) com Zona Morta de Sincronia Nativa (±650 ms):
     * Analisa todas as fronteiras claras de diálogo nos primeiros 42 segundos:
     * (a) Início da 1ª fala (Cue 0);
     * (b) Inícios e fins de falas da legenda com pausa adjacente de pelo menos 300ms.
     * Mapeia contra os onsets vocais correspondentes em dubSpeechEnvelope e calcula a mediana dos deltas.
     */
    fun estimateDialogueOnsetOffsetMs(
        cleanSubtitleCues: List<com.nuvio.tv.ui.screens.player.SubtitleSyncCue>,
        dubSpeechEnvelope: FloatArray,
        windowMs: Int = 10,
        maxScanMs: Long = 42_000L,
        applyDeadzone: Boolean = true
    ): Long? {
        if (cleanSubtitleCues.isEmpty() || dubSpeechEnvelope.isEmpty()) return null

        val candidateCues = cleanSubtitleCues
            .filter { it.startTimeMs in 500L..maxScanMs && it.text.trim().length >= 2 }
            .sortedBy { it.startTimeMs }
        if (candidateCues.isEmpty()) return null

        val firstCue = candidateCues.first()
        val firstSubStartMs = firstCue.startTimeMs

        val scanBins = minOf(dubSpeechEnvelope.size, (maxScanMs / windowMs).toInt())
        val positiveVals = mutableListOf<Float>()
        for (i in 0 until scanBins) {
            val v = dubSpeechEnvelope[i]
            if (v > 0.02f) positiveVals.add(v)
        }
        val threshold = if (positiveVals.size >= 10) {
            positiveVals.sort()
            val p50 = positiveVals[(positiveVals.size * 0.50).toInt().coerceIn(0, positiveVals.size - 1)]
            p50.coerceIn(0.06f, 0.35f)
        } else {
            0.08f
        }

        val vocalOnsetsMs = mutableListOf<Long>()
        var pulseStart = -1
        for (i in 0 until scanBins) {
            val isVoice = dubSpeechEnvelope[i] > threshold
            if (isVoice && pulseStart < 0) {
                pulseStart = i
            } else if (!isVoice && pulseStart >= 0) {
                val len = i - pulseStart
                if (len >= 12) {
                    vocalOnsetsMs.add(pulseStart.toLong() * windowMs.toLong())
                }
                pulseStart = -1
            }
        }
        if (pulseStart >= 0 && (scanBins - pulseStart) >= 12) {
            vocalOnsetsMs.add(pulseStart.toLong() * windowMs.toLong())
        }

        // 1. Extração das Fronteiras Claras de Diálogo da Legenda (0s..42s)
        val subBoundaries = mutableListOf<Long>()
        for (k in candidateCues.indices) {
            val cue = candidateCues[k]
            if (k == 0) {
                subBoundaries.add(cue.startTimeMs)
            } else {
                val prev = candidateCues[k - 1]
                if (cue.startTimeMs - prev.endTimeMs >= 300L) {
                    subBoundaries.add(cue.startTimeMs)
                }
            }
            if (k + 1 < candidateCues.size) {
                val next = candidateCues[k + 1]
                if (next.startTimeMs - cue.endTimeMs >= 300L) {
                    subBoundaries.add(cue.endTimeMs)
                }
            } else {
                subBoundaries.add(cue.endTimeMs)
            }
        }

        // 2. RANSAC de Intervalos Múltiplos
        var ransacCandidate: Long? = null
        if (candidateCues.size >= 2 && vocalOnsetsMs.size >= 2) {
            val secondCue = candidateCues[1]
            val subInterGap = secondCue.startTimeMs - firstSubStartMs

            for (i in 0 until (vocalOnsetsMs.size - 1)) {
                for (j in (i + 1) until vocalOnsetsMs.size) {
                    val dubInterGap = vocalOnsetsMs[j] - vocalOnsetsMs[i]
                    if (abs(subInterGap - dubInterGap) <= 200L) {
                        val offsetCandidate = firstSubStartMs - vocalOnsetsMs[i]
                        if (candidateCues.size >= 3 && vocalOnsetsMs.size >= 3) {
                            val thirdCue = candidateCues[2]
                            val subGap2 = thirdCue.startTimeMs - secondCue.startTimeMs
                            val hasThirdMatch = (j + 1 until vocalOnsetsMs.size).any { k ->
                                abs((vocalOnsetsMs[k] - vocalOnsetsMs[j]) - subGap2) <= 250L
                            }
                            if (hasThirdMatch && abs(offsetCandidate) <= 15_000L) {
                                ransacCandidate = offsetCandidate
                                break
                            }
                        } else if (abs(offsetCandidate) <= 10_000L) {
                            ransacCandidate = offsetCandidate
                            break
                        }
                    }
                }
                if (ransacCandidate != null) break
            }
        }

        // 3. Consenso de Mediana de Múltiplas Transições Vocais
        val deltas = mutableListOf<Long>()
        if (ransacCandidate != null) {
            deltas.add(ransacCandidate)
            for (subPoint in subBoundaries) {
                val expectedDubPoint = subPoint - ransacCandidate
                val closestOnset = vocalOnsetsMs.minByOrNull { abs(it - expectedDubPoint) }
                if (closestOnset != null && abs(closestOnset - expectedDubPoint) <= 350L) {
                    deltas.add(subPoint - closestOnset)
                }
            }
        }

        if (deltas.isNotEmpty()) {
            val sorted = deltas.sorted()
            val medianDelta = sorted[sorted.size / 2]
            return if (applyDeadzone && abs(medianDelta) < 650L) 0L else medianDelta
        }

        // 4. Detecção Determinística Geometria Vinheta -> Silêncio Longo -> Fala Sustentada
        val sustainedDubOnsetMs = detectSustainedDialogueOnsetMs(dubSpeechEnvelope, windowMs = windowMs)
        if (sustainedDubOnsetMs != null) {
            val deterministicDelta = firstSubStartMs - sustainedDubOnsetMs
            if (abs(deterministicDelta) <= 12_000L) {
                return if (applyDeadzone && abs(deterministicDelta) < 650L) 0L else deterministicDelta
            }
        }

        return null
    }

    /**
     * Pilar 1: Motor Principal — Alinhamento Morfológico Estilo FFSubSync / Alass (Janela Adaptativa):
     * Correlaciona a máscara de fala da legenda do vídeo contra o envelope vocal da dublagem
     * com fechamento morfológico (0s..45s), unindo sílabas em blocos de frase e combinando
     * 70% de platô com 30% de bordas de transição.
     * Adapta dinamicamente o início e tamanho da janela ao buffer disponível (inclusive buffers curtos de 3.5s a 10s).
     * Aceitação: psr >= 2.8f && maxZncc >= 0.16f (ou psr >= 4.5f para legendas externas de addons).
     */
    fun findCrossModalSubToDubLag(
        subSpeechMask: FloatArray,
        dubSpeechEnvelope: FloatArray,
        windowMs: Int = 10,
        skipInitialMs: Long = 6_000L,
        targetEndMs: Long = 45_000L,
        maxLagMs: Long = 10_000L,
        subOrigin: String = "subrip",
        dubMime: String = "mp4a-latm"
    ): AcousticMatch? {
        if (subSpeechMask.isEmpty() || dubSpeechEnvelope.isEmpty()) return null

        val availableDubDurationMs = dubSpeechEnvelope.size.toLong() * windowMs.toLong()
        val effectiveSkipInitialMs = if (availableDubDurationMs < 12_000L) 0L else skipInitialMs.coerceAtMost(availableDubDurationMs / 4)
        val effectiveTargetEndMs = minOf(targetEndMs, availableDubDurationMs)

        // 1. Processamento Morfológico do Áudio Dublado (VAD Plateau + Bordas)
        val dubPlateau = buildMorphologicalVadPlateau(
            speechEnv = dubSpeechEnvelope,
            startBin = (effectiveSkipInitialMs / windowMs).toInt(),
            endBin = (effectiveTargetEndMs / windowMs).toInt()
        )
        val dubSignal = applyEdgeDetectionAndBlend(dubPlateau)

        // 2. Processamento Morfológico da Legenda (Média Móvel Suave 150ms + Bordas)
        val smoothedSub = applyMovingAverage(subSpeechMask, windowRadius = 7)
        val subSignal = applyEdgeDetectionAndBlend(smoothedSub)

        val minOverlap = if (availableDubDurationMs < 8_000L) 150 else 200
        val startBin = (effectiveSkipInitialMs / windowMs).toInt().coerceIn(0, maxOf(0, subSignal.size - minOverlap))
        val endBin = (effectiveTargetEndMs / windowMs).toInt().coerceIn(startBin + minOverlap, subSignal.size)
        val patternLength = endBin - startBin
        if (patternLength < minOverlap) return null

        val isExternalAddon = subOrigin.contains("AIOStreams", ignoreCase = true) ||
            subOrigin.contains("OpenSubtitles", ignoreCase = true) ||
            subOrigin.contains("SubDL", ignoreCase = true)
        val isEmbeddedSub = !isExternalAddon || subOrigin.contains("embutida", ignoreCase = true)

        // Legendas externas de addons não podem aceitar lags gigantes como -3900ms sem evidência esmagadora
        val effectiveMaxLagMs = if (isEmbeddedSub) maxLagMs else minOf(maxLagMs, 3_500L)
        val maxLagWindows = (effectiveMaxLagMs / windowMs).toInt()
        val searchMin = -maxLagWindows
        val searchMax = maxLagWindows

        val znccMap = LinkedHashMap<Int, Float>()

        for (lag in searchMin..searchMax) {
            val dubStart = startBin + lag
            val validDubStart = maxOf(0, dubStart)
            val validDubEnd = minOf(dubSignal.size, dubStart + patternLength)
            val overlap = validDubEnd - validDubStart
            if (overlap < minOverlap) continue

            val subOffset = validDubStart - dubStart
            val xStart = startBin + subOffset

            var sumX = 0.0
            var sumY = 0.0
            for (i in 0 until overlap) {
                sumX += subSignal[xStart + i]
                sumY += dubSignal[validDubStart + i]
            }
            val meanX = sumX / overlap
            val meanY = sumY / overlap

            var dot = 0.0
            var varX = 0.0
            var varY = 0.0
            for (i in 0 until overlap) {
                val xZero = subSignal[xStart + i] - meanX
                val yZero = dubSignal[validDubStart + i] - meanY
                dot += xZero * yZero
                varX += xZero * xZero
                varY += yZero * yZero
            }

            val denom = sqrt(varX * varY)
            if (denom > 0.0001) {
                val zncc = (dot / denom).toFloat()
                znccMap[lag] = zncc
            }
        }

        if (znccMap.isEmpty()) return null

        val bestEntry = znccMap.maxByOrNull { it.value } ?: return null
        val bestLag = bestEntry.key
        val maxZncc = bestEntry.value
        val bestLagMs = -(bestLag.toLong() * windowMs.toLong())

        // Cálculo da Prominência Estatística (PSR) excluindo a vizinhança de ±200ms
        val sidelobes = znccMap.filter { abs(it.key - bestLag) > 20 }.values
        val meanSidelobe = if (sidelobes.isNotEmpty()) sidelobes.average().toFloat() else 0f
        val variance = if (sidelobes.isNotEmpty()) {
            sidelobes.map { (it - meanSidelobe) * (it - meanSidelobe) }.average().toFloat()
        } else 0f
        val stdSidelobe = sqrt(maxOf(1e-6f, variance))
        val psr = if (stdSidelobe > 0.0001f) (maxZncc - meanSidelobe) / stdSidelobe else 0f

        val topLags = znccMap.entries
            .sortedByDescending { it.value }
            .take(10)
            .map { entry ->
                val lagMs = -(entry.key.toLong() * windowMs.toLong())
                val curZncc = entry.value
                val curPsr = if (stdSidelobe > 0.0001f) (curZncc - meanSidelobe) / stdSidelobe else 0f
                LagResult(lagMs, curZncc, curPsr)
            }

        // Validação Estrita:
        // Para legendas embutidas: psr >= 2.8f && maxZncc >= 0.16f
        // Para legendas externas de addons: psr >= 4.5f && maxZncc >= 0.35f (evita falsos picos)
        val minPsr = if (isEmbeddedSub) 2.8f else 4.5f
        val minZncc = if (isEmbeddedSub) 0.16f else 0.35f
        val isValid = psr >= minPsr && maxZncc >= minZncc

        val telemetry = "Sub-Bridge VAD [Sub: $subOrigin | Dub: $dubMime | ZNCC: ${String.format(java.util.Locale.US, "%.2f", maxZncc)} | PSR: ${String.format(java.util.Locale.US, "%.1f", psr)} | Offset: ${if (bestLagMs >= 0) "+" else ""}${bestLagMs}ms]"

        val confidence = if (isValid) {
            (0.65f + ((psr - minPsr) / 3.5f) * 0.30f).coerceIn(0.65f, 0.98f)
        } else {
            (psr / minPsr * 0.48f).coerceIn(0.10f, 0.54f)
        }

        val baseVideoMs = startBin.toLong() * windowMs.toLong()
        val tVideo = baseVideoMs
        // Regra 2: Zona Morta de Sincronia Nativa (Deadzone = +-650 ms)
        val effectiveOffsetMs = if (abs(bestLagMs) < 650L) 0L else bestLagMs
        val tAudio = (tVideo - effectiveOffsetMs).coerceAtLeast(0L)

        return AcousticMatch(
            offsetMs = effectiveOffsetMs,
            confidence = confidence,
            maxZncc = maxZncc,
            psr = psr,
            isValid = isValid,
            minPsr = minPsr,
            minZncc = minZncc,
            tVideoMs = tVideo,
            tAudioMs = tAudio,
            telemetryDiag = telemetry,
            topLags = topLags
        )
    }
}
