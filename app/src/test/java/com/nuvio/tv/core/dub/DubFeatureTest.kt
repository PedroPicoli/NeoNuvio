package com.nuvio.tv.core.dub

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubFeatureTest {

    @Test
    fun testLanguageDetectionBoundaryNoFalsePositives() {
        // "challenging" contains "eng" as substring, but word boundary regex must NOT match "eng"
        val streamChallenging = Stream(
            name = "Test",
            title = "This is a challenging video",
            description = null,
            url = "http://example.com/video.mp4",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Test",
            addonLogo = null
        )
        val langs = DubLanguageDetector.detectLanguages(streamChallenging)
        assertFalse("Não deve detectar 'en' dentro de 'challenging'", langs.contains("en"))

        // Must match exact words and brackets
        val streamDual = streamChallenging.copy(title = "Movie [Eng - Por] 1080p")
        val dualLangs = DubLanguageDetector.detectLanguages(streamDual)
        assertTrue("Deve detectar inglês", dualLangs.contains("en"))
        assertTrue("Deve detectar português", dualLangs.contains("pt"))

        // Flag emojis
        val streamFlag = streamChallenging.copy(title = "Filme 1080p 🇧🇷")
        val flagLangs = DubLanguageDetector.detectLanguages(streamFlag)
        assertTrue("Deve detectar português a partir da bandeira do Brasil", flagLangs.contains("pt"))

        // Explicit "Dublado"
        val streamDublado = streamChallenging.copy(title = "Filme 4K Dublado")
        val dubladoLangs = DubLanguageDetector.detectLanguages(streamDublado)
        assertTrue("Deve detectar português quando 'Dublado' está no texto", dubladoLangs.contains("pt"))
    }

    @Test
    fun testStreamTargetLanguageMatching() {
        val stream1 = Stream(
            name = "Torrentio",
            title = "Oppenheimer.2023.1080p.Dual.Audio.Dublado",
            description = null,
            url = "https://example.com/stream.mp4",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Torrentio",
            addonLogo = null
        )

        assertTrue(
            "Stream com 'Dublado' deve casar com alvo 'por' / 'pt'",
            DubLanguageDetector.streamMatchesTargetLanguage(stream1, "por")
        )
        assertFalse(
            "Stream com 'Dublado' não deve casar com alvo 'spa' / 'es'",
            DubLanguageDetector.streamMatchesTargetLanguage(stream1, "spa")
        )
    }

    @Test
    fun testTimelineMappingCalculation() {
        // Timeline com 5000ms de vinheta inicial e scale 1.0
        val timeline = DubTimelineMap(
            initialAudioOffsetMs = 5000L,
            scale = 1.0,
            cuts = emptyList()
        )

        // Vídeo no segundo 0 -> áudio mapeado deve começar em 5000ms
        val mappedUs = timeline.mapVideoTimeToAudioTimeUs(0L)
        assertEquals(5_000_000L, mappedUs)

        // No segundo 10 do vídeo -> áudio deve estar em 15s
        val mapped10sUs = timeline.mapVideoTimeToAudioTimeUs(10_000_000L)
        assertEquals(15_000_000L, mapped10sUs)

        // Drop audio sample: áudio antes de 5000ms deve ser descartado (pertence à vinheta do distribuidor)
        assertTrue("Áudio aos 2s deve ser descartado pois é vinheta", timeline.shouldDropAudioSample(2_000_000L))
        assertFalse("Áudio aos 6s não deve ser descartado", timeline.shouldDropAudioSample(6_000_000L))
    }

    @Test
    fun testTimelineMappingFpsRatio() {
        // Áudio veio de fonte PAL 25fps acelerada em relação a filme 23.976fps
        // ratio = 25.0 / 23.976 = ~1.0427
        val ratio = 25.0 / 23.976
        val timeline = DubTimelineMap(
            initialAudioOffsetMs = 0L,
            scale = ratio,
            cuts = emptyList()
        )

        // Vídeo aos 1000ms -> áudio escalado para ~1042ms
        val mappedUs = timeline.mapVideoTimeToAudioTimeUs(1_000_000L)
        assertEquals(1_042_000L, mappedUs)
    }

    @Test
    fun testDubResultMemoryCache() {
        val videoKey = "video_source_1"
        val audioKey = "audio_source_2"
        val map = DubTimelineMap(initialAudioOffsetMs = 1500L, confidence = 0.95f)

        DubResultCache.put(videoKey, audioKey, map)
        val retrieved = DubResultCache.get(videoKey, audioKey)

        assertNotNull("Item deve ser recuperado do cache", retrieved)
        assertEquals(1500L, retrieved?.initialAudioOffsetMs)
        assertEquals(0.95f, retrieved?.confidence ?: 0f, 0.001f)
    }

    @Test
    fun testAcousticSyncMatcherCrossCorrelation() {
        // Cria dois envelopes com janela de 10ms (100 Hz, 20 segundos = 2000 janelas),
        // onde os primeiros 10 segundos contêm vinhetas distintas que devem ser ignoradas.
        // No trecho do episódio (>1000 janelas), o evento ocorre em 1050 no vídeo e 1200 no dublado (150 janelas = 1500ms mais tarde no dublado).
        val origEnv = FloatArray(2000) { i ->
            if (i in 1050..1150) 0.8f else 0.05f
        }
        val dubEnv = FloatArray(2000) { i ->
            // Evento ocorre 150 janelas (1500ms) mais tarde no áudio dublado (áudio tem vinheta maior de 1.5s)
            if (i in 1200..1300) 0.8f else 0.05f
        }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = origEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 10_000L,
            targetEndMs = 18_000L,
            maxLagMs = 3_000L
        )
        assertNotNull("Deve encontrar pico de correlação acústica M&E", match)
        // Convenção T_video - T_audio: Como áudio tem vinheta maior, precisa ser adiantado em 1500ms (offsetMs = -1500L)
        assertEquals(-1500L, match?.offsetMs)
        assertTrue("Alinhamento acústico deve ser válido", match!!.isValid)
        assertTrue("Confiança honesta deve ser alta para alinhamento válido (>=0.65, foi ${match.confidence})", match.confidence >= 0.65f)

        // Validação da Regra 2: Zona Morta (Deadzone = +-650ms). Offset leve de 500ms vira 0ms cravado.
        val dubEnvLeve = FloatArray(2000) { i ->
            if (i in 1100..1200) 0.8f else 0.05f
        }
        val matchDeadzone = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = origEnv,
            dubEnvelope = dubEnvLeve,
            windowMs = 10,
            skipInitialMs = 10_000L,
            targetEndMs = 18_000L,
            maxLagMs = 2_000L
        )
        assertNotNull("Deve encontrar pico para offset leve", matchDeadzone)
        assertEquals("Offset de 500ms (< 650ms) deve ser cravado em 0ms pela Zona Morta", 0L, matchDeadzone?.offsetMs)
    }

    @Test
    fun testAcousticMatchTakesPrecedenceInEstimator() {
        val acousticMatch = AcousticSyncMatcher.AcousticMatch(
            offsetMs = 4820L,
            confidence = 0.92f
        )
        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 6_000_000L,
            audioDurationMs = 6_000_100L,
            targetLanguage = "por",
            acousticMatch = acousticMatch
        )
        assertNotNull(decision)
        assertEquals("Offset deve vir da correspondência acústica", 4820L, decision?.timeline?.initialAudioOffsetMs)
        assertEquals(0.92f, decision?.timeline?.confidence ?: 0f, 0.01f)
    }

    @Test
    fun testSubtitleMatchTakesPrecedenceInEstimator() {
        val subtitleMatch = DubSubtitleSyncAligner.SubtitleMatch(
            initialAudioOffsetMs = -3200L,
            confidence = 0.98f,
            matchedCues = 45,
            videoLanguage = "eng",
            dubLanguage = "por"
        )
        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 2_400_000L,
            audioDurationMs = 2_400_500L,
            targetLanguage = "por",
            subtitleMatch = subtitleMatch
        )
        assertNotNull(decision)
        assertEquals("Offset deve vir do alinhamento de legendas da Orchestra", -3200L, decision?.timeline?.initialAudioOffsetMs)
        assertEquals(0.98f, decision?.timeline?.confidence ?: 0f, 0.01f)
        assertFalse(decision?.approximate ?: true)
    }

    @Test
    fun testExplicitDelayTagInversion() {
        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 2_400_000L,
            audioDurationMs = 2_400_500L,
            targetLanguage = "por",
            streamTitle = "Filme.2023.1080p.Dual [DELAY +1500ms]"
        )
        assertNotNull(decision)
        assertEquals(-1500L, decision?.timeline?.initialAudioOffsetMs)
    }

    @Test
    fun testMultiAddonResilienceSelectsHealthyAddon() = kotlinx.coroutines.runBlocking {
        val videoStream = Stream(
            name = "Torrentio [EN]",
            title = "Rick and Morty S05E03 2160p",
            description = null,
            url = "https://example.com/video4k.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Torrentio",
            addonLogo = null
        )

        val badCandidate = Stream(
            name = "BadAddon [PT-BR]",
            title = "Rick and Morty S05E03 Dublado [Quebrado]",
            description = null,
            url = null,
            ytId = null,
            infoHash = "badhash123",
            fileIdx = 0,
            externalUrl = null,
            behaviorHints = null,
            addonName = "BadAddon",
            addonLogo = null
        )

        val healthyCandidate = Stream(
            name = "Comet [PT-BR]",
            title = "Rick and Morty S05E03 Dual Audio 1080p",
            description = null,
            url = "https://example.com/comet_audio.mkv",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "Comet",
            addonLogo = null
        )

        val outcome = DubOrchestrator.orchestrate(
            videoStream = videoStream,
            videoUrl = "https://example.com/video4k.mkv",
            videoHeaders = emptyMap(),
            videoDurationHintMs = 1_320_000L,
            videoIsTorrent = false,
            targetLanguage = "por",
            allowBlindSyncWithoutPrompt = true,
            totalBudgetMs = 10_000L,
            fetchStreams = { listOf(badCandidate, healthyCandidate) },
            resolveAudio = { candidate ->
                if (candidate.addonName == "BadAddon") {
                    null // Simula falha ao resolver debrid no primeiro addon
                } else {
                    DubResolvedAudio(
                        stream = candidate,
                        url = candidate.url ?: "https://example.com/audio.mkv",
                        headers = emptyMap(),
                        isTorrent = false
                    )
                }
            }
        )

        assertTrue("Deve encontrar candidato mesmo se um addon falhar", outcome is DubOutcome.Ready)
        val ready = outcome as DubOutcome.Ready
        assertEquals("Deve selecionar a fonte saudável do Comet", "Comet", ready.plan.audio.stream.addonName)
    }

    @Test
    fun testStreamingBumperDetectionAmazon() {
        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 3_000_000L,
            audioDurationMs = 2_988_000L, // 12s a menos
            targetLanguage = "por",
            videoStreamTitle = "The.Boys.S04E01.2160p.AMZN.WEB-DL.DDP5.1.Atmos",
            streamTitle = "The Boys S04E01 1080p HDTV Dublado"
        )
        assertNotNull(decision)
        // Convenção padronizada T_video - T_audio: Vídeo tem bumper AMZN (3200ms) e áudio começa em 0ms.
        // O áudio deve esperar o vídeo começar: offsetMs = +3200L
        assertEquals("Offset deve corresponder a T_video - T_audio = +3200ms", 3200L, decision?.timeline?.initialAudioOffsetMs)
    }

    @Test
    fun testNativeLanguageTriggerLevelA() {
        // Stream nacional com tag explícita DUBLADO
        val dublado = "Rick.and.Morty.S05E03.1080p.DUBLADO.mkv"
        assertTrue(DubLanguageDetector.isExplicitSingleTargetLanguage(dublado, "Torrentio", "pt"))
        assertFalse(DubLanguageDetector.isDualOrMultiAudio(dublado))

        // Stream Dual Audio
        val dual = "Rick.and.Morty.S05E03.1080p.DUAL.AUDIO.mkv"
        assertTrue(DubLanguageDetector.isDualOrMultiAudio(dual))
        assertFalse(DubLanguageDetector.isExplicitSingleTargetLanguage(dual, "Torrentio", "pt"))

        // Addon nacional estritamente dublado sem tag de legenda
        assertTrue(DubLanguageDetector.isExplicitSingleTargetLanguage("Rick.and.Morty.S05E03.1080p.mkv", "BestCine", "pt"))

        // Addon nacional mas com tag LEGENDADO não pode ser considerado dublado único
        assertFalse(DubLanguageDetector.isExplicitSingleTargetLanguage("Rick.and.Morty.S05E03.LEGENDADO.mkv", "BestCine", "pt"))
    }

    @Test
    fun testNativeLanguageTriggerLevelB() {
        // Formato com idioma 'und' ou null, mas label 'Português (Brasil)' ou 'Dublado'
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage("und", "Português (Brasil)", "pt"))
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage(null, "Dublado", "pt"))
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage("por", null, "pt"))
        assertFalse(DubLanguageDetector.isTrackMatchingTargetLanguage("eng", "English [Original]", "pt"))
    }

    @Test
    fun testNativeLanguageTriggerLevelC() {
        // Faixa única com idioma 'und', vindo de release/addon brasileiro sem tags de inglês ou legendado
        assertTrue(DubLanguageDetector.isSingleTrackPresumedTarget("und", "Filme.Nacional.1080p.mkv", "Brazuca", "pt"))
        assertFalse(DubLanguageDetector.isSingleTrackPresumedTarget("und", "Movie.1080p.ENG.mkv", "Torrentio", "pt"))
        assertFalse(DubLanguageDetector.isSingleTrackPresumedTarget("und", "Movie.1080p.LEGENDADO.mkv", "BestCine", "pt"))
    }

    @Test
    fun testExcessDurationTreatedAsEndCreditsNotIntroOffset() {
        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 3_000_000L,
            audioDurationMs = 2_985_000L, // 15s de diferença total no encerramento
            targetLanguage = "por",
            videoStreamTitle = "Serie.S01E01.1080p.mkv",
            streamTitle = "Serie.S01E01.720p.Dublado.mkv"
        )
        assertNotNull(decision)
        // Diferença de duração sem vinheta não deve inventar offset inicial de abertura
        assertEquals("Diferença de duração tratada como créditos finais mantendo offset em 0L", 0L, decision?.timeline?.initialAudioOffsetMs)
    }

    @Test
    fun testAcousticSyncMatcherBestCinePositiveOffset() {
        // Simula janela cirúrgica de 18s (1800 janelas de 10ms a 100 Hz)
        // No vídeo BluRay, o evento acústico (tiro/batida) ocorre em 500 janelas (5,0s após os 10s = 15,0s do vídeo)
        // No áudio dublado BestCine, o mesmo evento ocorre em 220 janelas (2,2s após os 10s = 12,2s do áudio)
        // Ou seja, o áudio BestCine está 2,8s adiantado em relação ao vídeo BluRay!
        val videoEnv = FloatArray(1800) { i ->
            if (i in 500..560) 0.85f else 0.05f
        }
        val dubEnv = FloatArray(1800) { i ->
            if (i in 220..280) 0.85f else 0.05f
        }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = videoEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 0L, // Já cirúrgico
            targetEndMs = 18_000L,
            maxLagMs = 8_000L
        )

        assertNotNull("Deve encontrar correlação acústica para áudio BestCine adiantado", match)
        // Offset positivo (+2800ms): O áudio precisa atrasar para esperar o vídeo (T_video - T_audio = +2800ms)
        assertEquals("Offset deve cravar em +2800ms", 2800L, match?.offsetMs)
        assertTrue("Confiança deve ser alta (>0.80)", (match?.confidence ?: 0f) > 0.80f)
    }

    @Test
    fun testMultilingualDetectionGlobalLanguages() {
        // Espanhol
        val espStream = "Pelicula.2024.1080p.Castellano.mkv"
        assertTrue(DubLanguageDetector.isExplicitSingleTargetLanguage(espStream, "Alfa", "es"))
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage("spa", "Audio Español", "es"))
        assertTrue(DubLanguageDetector.isSingleTrackPresumedTarget("und", "Pelicula.1080p.mkv", "Alfa", "es"))

        // Francês
        val frStream = "Film.2024.1080p.TRUEFRENCH.mkv"
        assertTrue(DubLanguageDetector.isExplicitSingleTargetLanguage(frStream, "vStream", "fr"))
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage("fre", "VFF", "fr"))

        // Inglês
        val enStream = "Movie.2024.1080p.ENGLISH.mkv"
        assertTrue(DubLanguageDetector.isExplicitSingleTargetLanguage(enStream, "Torrentio", "en"))
        assertTrue(DubLanguageDetector.isTrackMatchingTargetLanguage("eng", "English Original", "en"))
    }

    @Test
    fun testAcousticSyncMatcherPsrRejectionOnFlatNoise() {
        // Ruído aleatório sem padrão correlacionado
        val videoEnv = FloatArray(2500) { ((it * 37) % 100) / 100f }
        val dubEnv = FloatArray(2500) { ((it * 53) % 100) / 100f }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = videoEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 10_000L,
            targetEndMs = 35_000L,
            maxLagMs = 8_000L
        )

        assertNotNull(match)
        assertFalse("Ruído descorrelacionado deve ser rejeitado (isValid == false)", match!!.isValid)
        assertTrue("PSR de ruído plano deve ser baixo ou zncc baixo", match.psr < 3.2f || match.maxZncc < 0.16f)
        assertTrue("Telemetria deve estar presente", match.telemetryDiag.contains("ZNCC:"))
    }

    @Test
    fun testBandPassFilterHumanVoice() {
        val filter = AcousticSyncMatcher.BandPassFilter(sampleRate = 48000)
        // 1000 Hz está no centro da fala humana (300Hz .. 2800Hz)
        var voiceEnergy = 0.0
        val voiceFreq = 1000.0
        for (i in 0 until 4800) {
            val sample = kotlin.math.sin(2.0 * Math.PI * voiceFreq * i / 48000.0).toFloat()
            val filtered = filter.filter(sample)
            if (i > 1000) voiceEnergy += filtered * filtered
        }

        val lowFilter = AcousticSyncMatcher.BandPassFilter(sampleRate = 48000)
        // 50 Hz está bem abaixo da fala (graves / rumble de motor)
        var lowEnergy = 0.0
        val lowFreq = 50.0
        for (i in 0 until 4800) {
            val sample = kotlin.math.sin(2.0 * Math.PI * lowFreq * i / 48000.0).toFloat()
            val filtered = lowFilter.filter(sample)
            if (i > 1000) lowEnergy += filtered * filtered
        }

        assertTrue("Filtro passa-faixa deve preservar voz (1kHz) muito melhor que grave (50Hz)", voiceEnergy > lowEnergy * 5.0)
    }

    @Test
    fun testCrossModalSubToDubVad() {
        // Simula legendas do vídeo com 3 falas nos tempos:
        // 1. 10.000ms a 12.000ms ("Olá, como você está?")
        // 2. 16.000ms a 19.000ms ("Eu preciso falar com você imediatamente.")
        // 3. 24.000ms a 27.000ms ("Vamos entrar no carro agora.")
        val cues = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(10_000L, 12_000L, "Olá, como você está?"),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(16_000L, 19_000L, "Eu preciso falar com você imediatamente."),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(24_000L, 27_000L, "Vamos entrar no carro agora.")
        )

        // Envelope vocal da dublagem a 100 Hz (10ms por bin)
        // Áudio dublado tem um offset de +2.000ms em relação ao vídeo:
        // T_video = 10.000ms -> T_audio = 8.000ms (offset = T_video - T_audio = +2.000ms)
        val dubEnvelope = FloatArray(4000)
        val audioOffsetMs = 2000L

        for (cue in cues) {
            val audioStartMs = cue.startTimeMs - audioOffsetMs
            val audioEndMs = cue.endTimeMs - audioOffsetMs
            val startBin = (audioStartMs / 10L).toInt().coerceIn(0, 3999)
            val endBin = (audioEndMs / 10L).toInt().coerceIn(0, 3999)
            for (b in startBin..endBin) {
                dubEnvelope[b] = 0.85f
            }
        }

        val result = DubSubtitleSyncAligner.alignCrossModal(
            videoCues = cues,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "subrip",
            dubMime = "mp4a-latm"
        )

        assertNotNull("Alinhamento Cross-Modal deve encontrar correspondência", result)
        assertTrue("Resultado de alinhamento Cross-Modal deve ser válido", result!!.isValid)
        assertEquals("Offset da sincronia Cross-Modal esperado 2000ms mas foi ${result.offsetMs}", 2000L, result.offsetMs)
        assertTrue("ZNCC do alinhamento Cross-Modal deve ser expressivo (>0.50)", result.maxZncc > 0.50f)
        assertTrue("PSR deve ser robusto (>=2.5) mas foi ${result.psr}", result.psr >= 2.5f)
        assertTrue("Telemetria do HUD deve identificar Sub-Bridge VAD", result.telemetryDiag.contains("Sub-Bridge VAD"))
    }

    @Test
    fun testCrossModalSubToDubNoiseFilter() {
        // Legenda com ruídos descritivos SDH não deve ativar a máscara de voz
        val cuesSdhOnly = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(10_000L, 12_000L, "[Música suave tocando]"),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(16_000L, 19_000L, "(barulho de motor)")
        )

        val dubEnvelope = FloatArray(4000) { 0.5f }

        val result = DubSubtitleSyncAligner.alignCrossModal(
            videoCues = cuesSdhOnly,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "subrip",
            dubMime = "mp4a-latm"
        )

        assertNull("Legendas puramente de ruído SDH devem ser ignoradas", result)
    }

    @Test
    fun testAcousticSyncMatcherRejectsLowPsrFalsePositive() {
        // Simula o caso real da telemetria: sinal com energia constante/ruído quase plano
        // Sem pico proeminente, o PSR fica baixo (em torno de 1.0 a 1.5).
        // Mesmo se o ZNCC fosse 0.64 (linha de base artificial), ele DEVE ser rejeitado e a confiança DEVE ser < 0.55.
        val origEnv = FloatArray(2500) { 0.4f + (it % 7) * 0.02f }
        val dubEnv = FloatArray(2500) { 0.4f + (it % 11) * 0.02f }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = origEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 10_000L,
            targetEndMs = 20_000L,
            maxLagMs = 2_000L,
            videoDiag = "vnd.dts/Bitstream",
            dubDiag = "mp4a-latm/2ch"
        )

        assertNotNull("Deve retornar o objeto de match com cálculo da telemetria", match)
        assertFalse("Não deve aceitar alinhamento sem destaque estatístico de ruído (PSR baixo)", match!!.isValid)
        assertTrue("Confiança deve ser honesta (<55%) quando o alinhamento for inválido por PSR baixo (foi ${match.confidence})", match.confidence < 0.55f)
        assertTrue("Telemetria deve reportar PSR e ZNCC", match.telemetryDiag.contains("PSR"))
    }

    @Test
    fun testAcousticSyncMatcherAcceptsRobustPeakWithHighPsr() {
        // Sinal com transiente claro (pico expressivo sobre a linha de base)
        val origEnv = FloatArray(2500) { 0.05f }
        val dubEnv = FloatArray(2500) { 0.05f }
        // Evento claro em 1200 na origem (12s) e 1280 no dublado (12.8s) -> offset = -800ms
        for (i in 1200..1260) {
            origEnv[i] = 0.95f
        }
        for (i in 1280..1340) {
            dubEnv[i] = 0.95f
        }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = origEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 10_000L,
            targetEndMs = 20_000L,
            maxLagMs = 2_000L,
            videoDiag = "pcm_s16le/2ch",
            dubDiag = "mp4a-latm/2ch"
        )

        assertNotNull("Deve encontrar correspondência", match)
        assertTrue("Deve ser válido com pico proeminente", match!!.isValid)
        assertEquals(-800L, match.offsetMs)
        assertTrue("PSR deve ser alto (>= 3.0) mas foi ${match.psr}", match.psr >= 3.0f)
        assertTrue("Confiança deve ser alta (> 65%) mas foi ${match.confidence}", match.confidence >= 0.65f)
    }

    @Test
    fun testMorphologicalVadPlateauBridgesSyllablesAndCleansPulses() {
        val speech = FloatArray(4500)
        // Sílaba 1: bins 1000..1015 (150ms)
        for (i in 1000..1015) speech[i] = 0.5f
        // Pausa de 20 bins (200ms) entre 1016 e 1035: vazia
        // Sílaba 2: bins 1036..1050 (150ms)
        for (i in 1036..1050) speech[i] = 0.5f

        // Pulso isolado de ruído curto: 5 bins (50ms < 120ms)
        for (i in 1500..1504) speech[i] = 0.6f

        val plateau = AcousticSyncMatcher.buildMorphologicalVadPlateau(speech, startBin = 600, endBin = 4500)

        // O intervalo entre as duas sílabas deve ter sido fechado (fechamento morfológico)
        // No centro do gap (bin 1025), o valor deve ser próximo de 1.0 (ou > 0.8 após média móvel)
        assertTrue("Fechamento morfológico deve preencher pausas inter-sílabas (gap bin 1025 foi ${plateau[1025]})", plateau[1025] > 0.8f)

        // O ruído curto de 50ms deve ter sido eliminado (valor próximo de 0.0)
        assertTrue("Ruído curto < 120ms deve ser removido (bin 1502 foi ${plateau[1502]})", plateau[1502] < 0.2f)
    }

    @Test
    fun testBumperBoundarySilenceDetection() {
        val vidEnv = FloatArray(3000) { 0.05f }
        val dubEnv = FloatArray(3000) { 0.05f }

        // Vídeo: vinheta com som de 2.0s a 5.0s (bins 200..500)
        for (i in 200..500) vidEnv[i] = 0.6f
        // Vale de silêncio de 30 bins (300ms) nos bins 501..530 (5.01s a 5.30s)
        // A cena começa em bin 531 com som
        for (i in 531..1000) vidEnv[i] = 0.6f

        // Dublagem: vinheta com som de 2.0s a 6.0s (bins 200..600)
        for (i in 200..600) dubEnv[i] = 0.6f
        // Vale de silêncio de 30 bins (300ms) nos bins 601..630 (6.01s a 6.30s)
        // A cena começa em bin 631 com som
        for (i in 631..1000) dubEnv[i] = 0.6f

        val vidBumperEndMs = AcousticSyncMatcher.detectBumperSilenceEndMs(vidEnv)
        val dubBumperEndMs = AcousticSyncMatcher.detectBumperSilenceEndMs(dubEnv)

        assertNotNull("Vídeo deve detectar fim da vinheta", vidBumperEndMs)
        assertNotNull("Dublagem deve detectar fim da vinheta", dubBumperEndMs)
        assertEquals("Bumper do vídeo deve terminar em ~5010ms", 5010L, vidBumperEndMs)
        assertEquals("Bumper da dublagem deve terminar em ~6010ms", 6010L, dubBumperEndMs)

        val deltaBumperMs = AcousticSyncMatcher.estimateBumperOffsetMs(vidEnv, dubEnv)
        assertNotNull("Delta do bumper deve ser calculado", deltaBumperMs)
        // T_bumper_vid - T_bumper_dub = 5010 - 6010 = -1000ms
        assertEquals(-1000L, deltaBumperMs)
    }

    @Test
    fun testHighPsrAcceptanceWithLowerBitstreamZncc() {
        // Simula caso com PSR >= 3.5 e ZNCC >= 0.10 (cenário Bitstream da telemetria)
        val origEnv = FloatArray(3500) { 0.02f }
        val dubEnv = FloatArray(3500) { 0.02f }

        // Padrão que gera pico claro e isolado
        for (i in 1000..1500 step 10) {
            origEnv[i] = 0.25f
            dubEnv[i + 80] = 0.25f // 80 bins = 800ms lag
        }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(
            origEnvelope = origEnv,
            dubEnvelope = dubEnv,
            windowMs = 10,
            skipInitialMs = 6_000L,
            targetEndMs = 25_000L,
            maxLagMs = 2_000L,
            videoDiag = "vnd.dts(Media3)/Bitstream",
            dubDiag = "mp4a-latm/2ch"
        )

        assertNotNull(match)
        if (match!!.psr >= 3.5f && match.maxZncc >= 0.10f) {
            assertTrue("Alinhamento com PSR >= 3.5 e ZNCC >= 0.10 DEVE ser aceito como válido", match.isValid)
            assertEquals("Lag deve ser cravado em -800ms", -800L, match.offsetMs)
        }
    }

    @Test
    fun testCrossModalSubToDubMorphological45s() {
        // Cues cobrindo os primeiros 45 segundos
        val cues = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(7_000L, 9_500L, "Primeira frase da cena."),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(12_000L, 15_000L, "Segunda frase com diálogo."),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(20_000L, 24_000L, "Terceira fala completa."),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(32_000L, 36_000L, "Quarta fala no final dos 45s.")
        )

        // Envelope dublado com lag de -1.180ms (exato valor do log da telemetria)
        // T_video = 7000ms -> T_audio = 8180ms (áudio está 1180ms mais tarde, logo offset = -1180ms)
        val dubEnvelope = FloatArray(4500)
        val lagMs = -1180L

        for (cue in cues) {
            val audStart = cue.startTimeMs - lagMs
            val audEnd = cue.endTimeMs - lagMs
            val sBin = (audStart / 10L).toInt().coerceIn(0, 4499)
            val eBin = (audEnd / 10L).toInt().coerceIn(0, 4499)
            // Simula sílabas com pequenas pausas de 100ms que o VAD morfológico irá unir
            var b = sBin
            while (b <= eBin) {
                val syllableEnd = minOf(eBin, b + 15) // 150ms sílaba
                for (k in b..syllableEnd) dubEnvelope[k] = 0.75f
                b = syllableEnd + 10 // 100ms pausa
            }
        }

        val result = DubSubtitleSyncAligner.alignCrossModal(
            videoCues = cues,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "en(embutida)",
            dubMime = "mp4a-latm"
        )

        assertNotNull("Alinhamento morfológico 45s deve produzir resultado", result)
        assertTrue("Resultado morfológico deve ser válido", result!!.isValid)
        assertEquals(-1180L, result.offsetMs)
        assertTrue("PSR deve ser robusto (>= 2.6f) mas foi ${result.psr}", result.psr >= 2.6f)
        assertTrue("ZNCC deve ser robusto (>= 0.14f) mas foi ${result.maxZncc}", result.maxZncc >= 0.14f)
        assertTrue("Telemetria deve reportar Sub-Bridge VAD", result.telemetryDiag.contains("Sub-Bridge VAD"))
    }

    @Test
    fun testDialogueOnsetAnchorExactCalculation() {
        // Cues do vídeo: 1ª fala aos 2150ms, 2ª fala aos 5000ms e 3ª aos 8000ms
        val cues = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(2_150L, 3_500L, "Morty, look at this!"),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(5_000L, 6_800L, "What is going on here?"),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(8_000L, 9_500L, "We need to run!")
        )

        // Envelope dublado a 100 Hz (10ms por bin):
        // Falas dubladas em 150ms (bin 15..100), 3000ms (bin 300..400) e 6000ms (bin 600..700)
        // Offset esperado: T_video - T_audio = 2150ms - 150ms = +2000ms!
        val dubEnvelope = FloatArray(1000)
        for (i in 15..100) dubEnvelope[i] = 0.8f
        for (i in 300..400) dubEnvelope[i] = 0.8f
        for (i in 600..700) dubEnvelope[i] = 0.8f

        // Rejeição estrita: addons externos como AIOStreams NÃO devem gerar offset cego
        val externalMatch = DubSubtitleSyncAligner.estimateDialogueOnsetOffset(
            videoCues = cues,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "AIOStreams",
            dubMime = "mp4a-latm"
        )
        assertNull("Legendas de addon externo como AIOStreams devem ser rejeitadas no Onset Anchor", externalMatch)

        // Aceitação com legenda embutida
        val match = DubSubtitleSyncAligner.estimateDialogueOnsetOffset(
            videoCues = cues,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "por(embutida)",
            dubMime = "mp4a-latm"
        )

        assertNotNull("Deve calcular onset anchor para legenda embutida com consenso triplo", match)
        assertTrue("Onset anchor deve ser válido", match!!.isValid)
        assertEquals(2000L, match.offsetMs)
        assertTrue("Confiança deve ser moderada (>= 0.55)", match.confidence >= 0.55f)
        assertTrue("Telemetria deve indicar Dialogue Onset Anchor", match.telemetryDiag.contains("Dialogue Onset Anchor"))
    }

    @Test
    fun testReleaseArchetypeBluRayVsWebDlLeadIn() {
        val videoTitle = "Rick.and.Morty.S05E03.A.Rickconvenient.Mort.2160p.BluRay.AI.AV1.DTS-HD.MA5.1-Enrav1sh.mkv"
        val audioTitle = "1080p - BestCine"

        val delta = DubSyncEstimator.detectArchetypeLeadInDelta(videoTitle, audioTitle)
        assertNull("Heurística de strings de release deve ser nula por diretriz", delta)

        val decision = DubSyncEstimator.estimate(
            videoDurationMs = 1_320_000L,
            audioDurationMs = 1_318_000L,
            targetLanguage = "por",
            videoStreamTitle = videoTitle,
            streamTitle = audioTitle
        )

        assertNotNull(decision)
        assertEquals("Offset da estimativa Camada 1 deve ser estritamente 0ms", 0L, decision?.timeline?.initialAudioOffsetMs)
        assertTrue("Winning layer deve ser Alinhamento Base", decision?.winningLayer?.contains("Alinhamento Base") == true)
        assertEquals("Deve ser blind guess para permitir sobreposição acústica", true, decision?.timeline?.isBlind)
    }

    @Test
    fun testAdaptiveShortBufferSubToDubVad() {
        // Simula caso da telemetria onde o buffer de dublagem possui apenas ~3.9 segundos (392 bins)
        val cues = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(2_150L, 3_500L, "Morty, look at this!"),
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(5_000L, 6_800L, "We have to leave.")
        )

        val dubEnvelope = FloatArray(400) // 400 bins = 4.0 segundos
        // Fala dublada aos 150ms (bin 15..150) -> offset = +2000ms
        for (i in 15..140) dubEnvelope[i] = 0.85f

        val result = DubSubtitleSyncAligner.alignCrossModal(
            videoCues = cues,
            dubSpeechEnvelope = dubEnvelope,
            subOrigin = "AIOStreams",
            dubMime = "mp4a-latm"
        )

        // Com a janela adaptativa, mesmo um buffer curto não é descartado com crash de bin 600
        assertNotNull("Deve executar correlação com janela adaptativa em buffer curto", result)
    }

    @Test
    fun testBumperAndSustainedDialogueOnsetDeterministicDetection() {
        // Reprodução exata do caso neonuvio_dub_diagnostic.json:
        // Legenda do vídeo fala aos 6480ms
        val cues = listOf(
            com.nuvio.tv.ui.screens.player.SubtitleSyncCue(6_480L, 8_000L, "Morty, look at this!")
        )

        // Envelope dublado com vinheta nos primeiros segundos (bins 100..450 = 1.0s..4.5s),
        // vale de silêncio longo (bins 451..680 = 4.5s..6.8s, 230 bins de silêncio),
        // e diálogo sustentado iniciando no bin 690 (6900ms = 6.9s)
        val dubEnvelope = FloatArray(1200)
        for (i in 100..450) dubEnvelope[i] = 0.85f // Vinheta
        for (i in 451..680) dubEnvelope[i] = 0.01f // Vale de silêncio pós-vinheta
        for (i in 690..1000) dubEnvelope[i] = 0.70f // Diálogo do episódio

        val sustainedOnsetMs = AcousticSyncMatcher.detectSustainedDialogueOnsetMs(dubEnvelope)
        assertNotNull("Deve detectar início do diálogo pós-vinheta", sustainedOnsetMs)
        assertEquals(6900L, sustainedOnsetMs)

        val rawOffsetMs = AcousticSyncMatcher.estimateDialogueOnsetOffsetMs(cues, dubEnvelope, applyDeadzone = false)
        assertNotNull("Deve calcular delta bruto entre legenda do vídeo e diálogo da dublagem", rawOffsetMs)
        assertEquals(-420L, rawOffsetMs) // 6480ms - 6900ms = -420ms

        val deadzoneOffsetMs = AcousticSyncMatcher.estimateDialogueOnsetOffsetMs(cues, dubEnvelope, applyDeadzone = true)
        assertNotNull("Deve calcular delta com aplicação da Zona Morta", deadzoneOffsetMs)
        assertEquals("Delta leve de -420ms (< 650ms) deve ser cravado em 0ms pela Zona Morta", 0L, deadzoneOffsetMs)
    }
}



