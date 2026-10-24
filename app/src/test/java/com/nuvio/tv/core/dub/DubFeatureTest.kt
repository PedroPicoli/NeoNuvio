package com.nuvio.tv.core.dub

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Cria dois envelopes com janela de 10ms (100 Hz), onde o sinal dublado está atrasado em 500ms (50 janelas)
        val origEnv = FloatArray(200) { i ->
            if (i in 50..90) 0.8f else 0.05f
        }
        val dubEnv = FloatArray(200) { i ->
            // Sinal atrasado por 50 janelas (500ms)
            if (i in 100..140) 0.8f else 0.05f
        }

        val match = AcousticSyncMatcher.findCrossCorrelationLag(origEnv, dubEnv, windowMs = 10)
        assertNotNull("Deve encontrar pico de correlação acústica", match)
        assertEquals(500L, match?.offsetMs)
        assertTrue("Confiança deve ser alta (>0.80)", (match?.confidence ?: 0f) > 0.80f)
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
}
