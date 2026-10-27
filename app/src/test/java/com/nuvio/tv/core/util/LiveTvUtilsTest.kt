package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvUtilsTest {

    @Test
    fun `isLiveTv returns true for live channel types`() {
        assertTrue(LiveTvUtils.isLiveTv(id = "channel1", contentType = "channel"))
        assertTrue(LiveTvUtils.isLiveTv(id = "channel1", contentType = "tv_channel"))
        assertTrue(LiveTvUtils.isLiveTv(id = "channel1", contentType = "iptv"))
        assertTrue(LiveTvUtils.isLiveTv(id = "channel1", contentType = "live"))
    }

    @Test
    fun `isLiveTv returns true for stremio tv type without episodic id`() {
        assertTrue(LiveTvUtils.isLiveTv(id = "bc:premiere2", contentType = "tv"))
        assertTrue(LiveTvUtils.isLiveTv(id = "custom_channel_123", contentType = "tv"))
    }

    @Test
    fun `isLiveTv returns false for movies and series even with tv type if episodic id`() {
        assertFalse(LiveTvUtils.isLiveTv(id = "tt1234567", contentType = "movie"))
        assertFalse(LiveTvUtils.isLiveTv(id = "tt1234567", contentType = "series"))
        assertFalse(LiveTvUtils.isLiveTv(id = "tt1234567", contentType = "tv"))
        assertFalse(LiveTvUtils.isLiveTv(id = "tmdb:12345", contentType = "tv"))
        assertFalse(LiveTvUtils.isLiveTv(id = "kitsu:123", contentType = "tv"))
    }

    @Test
    fun `isLiveTv returns false when season or episode is greater than zero`() {
        assertFalse(LiveTvUtils.isLiveTv(id = "bc:premiere2", contentType = "tv", season = 1, episode = 1))
        assertFalse(LiveTvUtils.isLiveTv(id = "iptv:stream", contentType = "channel", season = 1, episode = 0))
    }

    @Test
    fun `isLiveTv returns true for live channel prefixes`() {
        assertTrue(LiveTvUtils.isLiveTv(id = "bc:globo_sp", contentType = "other"))
        assertTrue(LiveTvUtils.isLiveTv(id = "brazuca:sportv", contentType = "other"))
        assertTrue(LiveTvUtils.isLiveTv(id = "iptv:ch1", contentType = "other"))
        assertTrue(LiveTvUtils.isLiveTv(id = "m3u:channel", contentType = "other"))
    }

    @Test
    fun `isLiveTv with MetaPreview detects Brazilian IPTV channels accurately`() {
        val preview = MetaPreview(
            id = "bc:premiere2",
            type = ContentType.TV,
            rawType = "tv",
            name = "Premiere 2 HD (BC)",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "📺 Canal ao vivo em HD — Canais | Premiere",
            releaseInfo = null,
            imdbRating = null,
            genres = listOf("Canais | Premiere")
        )
        assertTrue(LiveTvUtils.isLiveTv(preview))
    }

    @Test
    fun `isLiveTv with MetaPreview returns false for normal movie`() {
        val movie = MetaPreview(
            id = "tt0111161",
            type = ContentType.MOVIE,
            rawType = "movie",
            name = "The Shawshank Redemption",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "Two imprisoned men bond over a number of years...",
            releaseInfo = "1994",
            imdbRating = 9.3f,
            genres = listOf("Drama")
        )
        assertFalse(LiveTvUtils.isLiveTv(movie))
    }

    @Test
    fun `isLiveTv with MetaPreview returns false for normal series`() {
        val series = MetaPreview(
            id = "tt0903747",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Breaking Bad",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = "A chemistry teacher diagnosed with cancer...",
            releaseInfo = "2008",
            imdbRating = 9.5f,
            genres = listOf("Crime", "Drama"),
            seasonCount = 5
        )
        assertFalse(LiveTvUtils.isLiveTv(series))
    }
}

