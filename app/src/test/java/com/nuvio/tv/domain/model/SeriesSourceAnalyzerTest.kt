package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesSourceAnalyzerTest {

    private fun testStream(
        name: String? = null,
        title: String? = null,
        description: String? = null,
        url: String? = null,
        infoHash: String? = null,
        bingeGroup: String? = null,
        addonName: String = "TestAddon"
    ): Stream = Stream(
        name = name,
        title = title,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = bingeGroup,
            countryWhitelist = null,
            proxyHeaders = null
        ),
        addonName = addonName,
        addonLogo = null
    )

    @Test
    fun `extractSourceKey and matchesSource work across episodes with different episode numbers and dynamic file size or peers`() {
        val ep1Stream = testStream(
            name = "Torrentio\n1080p",
            title = "Breaking.Bad.S01E01.1080p.BluRay.x264-DEMAND\n💾 1.45 GB 👤 55 seeders",
            url = "https://example.com/stream-ep1",
            infoHash = "hash_ep1_only",
            addonName = "Torrentio"
        )

        val ep2StreamSameRelease = testStream(
            name = "Torrentio\n1080p",
            title = "Breaking.Bad.S01E02.1080p.BluRay.x264-DEMAND\n💾 1.32 GB 👤 34 seeders",
            url = "https://example.com/stream-ep2",
            infoHash = "hash_ep2_different",
            addonName = "Torrentio"
        )

        val ep2StreamDifferentRelease = testStream(
            name = "Torrentio\n720p",
            title = "Breaking.Bad.S01E02.720p.HDTV.x264-FOO\n💾 800 MB 👤 12 seeders",
            url = "https://example.com/stream-ep2-diff",
            infoHash = "hash_ep2_diff",
            addonName = "Torrentio"
        )

        val ep1Key = SeriesSourceAnalyzer.extractSourceKey(ep1Stream)

        // Ep 2 da mesma release DEVE corresponder à chave extraída do Ep 1
        assertTrue(SeriesSourceAnalyzer.matchesSource(ep2StreamSameRelease, ep1Key))

        // Ep 2 de uma release diferente NÃO deve corresponder
        assertFalse(SeriesSourceAnalyzer.matchesSource(ep2StreamDifferentRelease, ep1Key))
    }

    @Test
    fun `isMatchingSource matches streams across different episode coordinate formats`() {
        val s1 = testStream(
            name = "MediaFusion",
            title = "Loki 1x01 1080p DUAL WEB-DL",
            url = "https://example.com/1",
            addonName = "MediaFusion"
        )
        val s2 = testStream(
            name = "MediaFusion",
            title = "Loki 1x02 1080p DUAL WEB-DL",
            url = "https://example.com/2",
            addonName = "MediaFusion"
        )

        assertTrue(SeriesSourceAnalyzer.isMatchingSource(s1, s2))
    }

    @Test
    fun `detectLanguages identifies dublado and pt-br as pt`() {
        val dubladoStream = testStream(
            name = "Torrentio 1080p",
            title = "Serie.S01E01.1080p.Dublado.PT-BR.mkv",
            addonName = "Torrentio"
        )

        val langs = SeriesSourceAnalyzer.detectLanguages(dubladoStream)
        assertTrue(langs.contains("pt"))
        assertTrue(SeriesSourceAnalyzer.streamMatchesLanguage(dubladoStream, "pt"))
        assertFalse(SeriesSourceAnalyzer.streamMatchesLanguage(dubladoStream, "ja"))
    }

    @Test
    fun `extractFingerprint and matchesSource match PSA release group and codec across episodes`() {
        val ep1Psa = testStream(
            name = "Torrentio\n1080p",
            title = "The.Bear.S02E01.1080p.10bit.WEBRip.6CH.x265.HEVC-PSA\n💾 450 MB",
            addonName = "Torrentio"
        )
        val ep2Psa = testStream(
            name = "Torrentio\n1080p",
            title = "The.Bear.S02E02.1080p.10bit.WEBRip.6CH.x265.HEVC-PSA\n💾 480 MB",
            addonName = "Torrentio"
        )
        val ep2Other = testStream(
            name = "Torrentio\n1080p",
            title = "The.Bear.S02E02.1080p.H264-FLUX\n💾 1.2 GB",
            addonName = "Torrentio"
        )

        val key = SeriesSourceAnalyzer.extractSourceKey(ep1Psa)
        assertTrue(SeriesSourceAnalyzer.matchesSource(ep2Psa, key))
        assertFalse(SeriesSourceAnalyzer.matchesSource(ep2Other, key))
    }

    @Test
    fun `StreamAutoPlaySelector with preferredSeriesSourceAddon prioritizes preferred series language`() {
        val englishStream = testStream(
            name = "Torrentio 1080p",
            title = "Show.S01E02.1080p.English.x264",
            url = "https://example.com/en",
            addonName = "Torrentio"
        )
        val portugueseStream = testStream(
            name = "Torrentio 1080p",
            title = "Show.S01E02.1080p.Dublado.PT-BR.x264",
            url = "https://example.com/pt",
            addonName = "Torrentio"
        )
        val otherAddonPtStream = testStream(
            name = "Brazuca 1080p",
            title = "Show.S01E02.1080p.Nacional",
            url = "https://example.com/brazuca",
            addonName = "Brazuca"
        )

        val selected = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(otherAddonPtStream, englishStream, portugueseStream),
            mode = com.nuvio.tv.data.local.StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Brazuca"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredSeriesSourceAddon = "Torrentio",
            preferredSeriesLanguage = "pt"
        )

        // Deve selecionar o stream do Torrentio com idioma em português
        org.junit.Assert.assertEquals(portugueseStream, selected)
    }

    @Test
    fun `extractSourceKey and matchesSource distinguish server X vs server Y even when names and resolutions are identical`() {
        val ep1ServerX = testStream(
            name = "1080p",
            title = "Serie S01E01 Servidor X\nDublado",
            url = "https://example.com/ep1-x",
            addonName = "MeuAddon"
        )
        val ep2ServerX = testStream(
            name = "1080p",
            title = "Serie S01E02 Servidor X\nDublado",
            url = "https://example.com/ep2-x",
            addonName = "MeuAddon"
        )
        val ep2ServerY = testStream(
            name = "1080p",
            title = "Serie S01E02 Servidor Y\nDublado",
            url = "https://example.com/ep2-y",
            addonName = "MeuAddon"
        )
        val ep2ServerZ = testStream(
            name = "1080p",
            title = "Serie S01E02 Servidor Z\nDublado",
            url = "https://example.com/ep2-z",
            addonName = "MeuAddon"
        )

        val ep1Key = SeriesSourceAnalyzer.extractSourceKey(ep1ServerX)

        assertTrue("Ep 2 com Servidor X deve corresponder", SeriesSourceAnalyzer.matchesSource(ep2ServerX, ep1Key))
        assertFalse("Ep 2 com Servidor Y NÃO deve corresponder", SeriesSourceAnalyzer.matchesSource(ep2ServerY, ep1Key))
        assertFalse("Ep 2 com Servidor Z NÃO deve corresponder", SeriesSourceAnalyzer.matchesSource(ep2ServerZ, ep1Key))

        val scoreX = SeriesSourceAnalyzer.matchScore(ep2ServerX, ep1Key)
        val scoreY = SeriesSourceAnalyzer.matchScore(ep2ServerY, ep1Key)
        assertTrue("Score do Servidor X ($scoreX) deve ser maior que Servidor Y ($scoreY)", scoreX > scoreY)

        // Teste de seleção via StreamAutoPlaySelector: mesmo se o Servidor Y vier primeiro na lista, deve escolher Servidor X
        val selected = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(ep2ServerY, ep2ServerZ, ep2ServerX),
            mode = com.nuvio.tv.data.local.StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("MeuAddon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredSeriesSourceKey = ep1Key,
            preferredSeriesSourceAddon = "MeuAddon"
        )
        org.junit.Assert.assertEquals(ep2ServerX, selected)
    }

    @Test
    fun `preferred series source has priority 1 over current playing source continuity`() {
        val seriesPreferredStreamEp2 = testStream(
            name = "1080p",
            title = "Serie S01E02 Servidor Preferido\nDublado",
            url = "https://example.com/ep2-pref",
            addonName = "AddonA"
        )
        val playingContinuityStreamEp2 = testStream(
            name = "1080p",
            title = "Serie S01E02 Servidor Atual\nDublado",
            url = "https://example.com/ep2-cur",
            addonName = "AddonB"
        )

        val seriesPreferredStreamEp1 = testStream(
            name = "1080p",
            title = "Serie S01E01 Servidor Preferido\nDublado",
            url = "https://example.com/ep1-pref",
            addonName = "AddonA"
        )
        val prefKey = SeriesSourceAnalyzer.extractSourceKey(seriesPreferredStreamEp1)

        // Cenário 1: Tanto a fonte padrão quanto a fonte atual (continuidade) estão disponíveis no Ep 2.
        // A prioridade 1 DEVE ser a fonte padrão da série!
        val selectedWithBoth = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(playingContinuityStreamEp2, seriesPreferredStreamEp2),
            mode = com.nuvio.tv.data.local.StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredSeriesSourceKey = prefKey,
            preferredSeriesSourceAddon = "AddonA",
            currentPlayingAddonName = "AddonB",
            currentPlayingDescription = playingContinuityStreamEp2.description,
            currentPlayingStreamName = playingContinuityStreamEp2.name
        )
        org.junit.Assert.assertEquals("A fonte preferida da série deve ter prioridade 1 sobre a continuidade", seriesPreferredStreamEp2, selectedWithBoth)

        // Cenário 2: A fonte padrão NÃO existe no Ep 2.
        // A prioridade 2 DEVE ser a continuidade do episódio atual!
        val selectedWithoutPref = com.nuvio.tv.core.player.StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(playingContinuityStreamEp2),
            mode = com.nuvio.tv.data.local.StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = com.nuvio.tv.data.local.StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredSeriesSourceKey = prefKey,
            preferredSeriesSourceAddon = "AddonA",
            currentPlayingAddonName = "AddonB",
            currentPlayingDescription = playingContinuityStreamEp2.description,
            currentPlayingStreamName = playingContinuityStreamEp2.name
        )
        org.junit.Assert.assertEquals("Na ausência da fonte preferida, a continuidade deve ser a prioridade 2", playingContinuityStreamEp2, selectedWithoutPref)
    }
}

