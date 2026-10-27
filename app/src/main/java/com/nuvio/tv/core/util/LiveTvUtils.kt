package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview

object LiveTvUtils {

    private val KNOWN_NON_LIVE_PREFIXES = listOf(
        "tt", "tmdb:", "kitsu:", "mal:", "simkl:"
    )

    private val KNOWN_LIVE_PREFIXES = listOf(
        "iptv:", "iptv_", "channel:", "tv_channel:", "live:", "tv:",
        "bc:", "bc_", "brazuca:", "brazuca_", "m3u:", "m3u_",
        "hlsvariant:", "stream:"
    )

    private val LIVE_CONTENT_TYPES = setOf(
        "channel", "tv_channel", "iptv", "live", "livetv", "live_tv"
    )

    private val LIVE_GENRE_KEYWORDS = listOf(
        "canais", "canal", "channel", "channels", "ao vivo", "live", "iptv",
        "premiere", "sportv", "espn", "telecine", "combate", "notícias", "news",
        "abertos", "fechados", "pay-per-view", "ppv"
    )

    private val LIVE_TEXT_KEYWORDS = listOf(
        "📺", "canal ao vivo", "tv ao vivo", "transmissão ao vivo", "em direto", "(bc)", "[bc]"
    )

    /**
     * Accurately determines if content represents a live TV channel / broadcast.
     * Prevents false positives with movies, series, or anime.
     */
    fun isLiveTv(
        id: String?,
        contentType: String?,
        season: Int? = null,
        episode: Int? = null
    ): Boolean {
        // If it has seasons or episodes, it is an episodic series/anime, NOT live TV
        if ((season != null && season > 0) || (episode != null && episode > 0)) {
            return false
        }

        val cleanId = id?.trim()?.lowercase().orEmpty()
        val cleanType = contentType?.trim()?.lowercase().orEmpty()

        // Known non-live ID prefixes
        if (KNOWN_NON_LIVE_PREFIXES.any { cleanId.startsWith(it) }) {
            return false
        }

        // Known non-live content types
        if (cleanType == "movie" || cleanType == "series" || cleanType == "anime") {
            return false
        }

        // Standard live TV content types
        if (cleanType in LIVE_CONTENT_TYPES) {
            return true
        }

        // In Stremio / IPTV addons, channels publish catalog as type "tv".
        // Traditional series use "series", and movies use "movie".
        if (cleanType == "tv") {
            return true
        }

        // Known live TV ID prefixes
        if (KNOWN_LIVE_PREFIXES.any { cleanId.startsWith(it) }) {
            return true
        }

        return false
    }

    fun isLiveTv(item: MetaPreview): Boolean {
        if (item.type == ContentType.CHANNEL) return true
        if (item.seasonCount != null && item.seasonCount > 0) return false

        val cleanId = item.id.trim().lowercase()
        val cleanType = (item.rawType.ifBlank { item.apiType }).trim().lowercase()

        // Non-live standard IDs (IMDb, TMDB, Anime)
        if (KNOWN_NON_LIVE_PREFIXES.any { cleanId.startsWith(it) }) {
            return false
        }

        // ContentType.TV or rawType "tv" when not an episodic series
        if (item.type == ContentType.TV || cleanType == "tv") {
            return true
        }

        if (isLiveTv(id = item.id, contentType = cleanType)) {
            return true
        }

        // Check genres for live TV / channel indicators
        if (cleanType != "movie" && cleanType != "series" && cleanType != "anime") {
            val hasLiveGenre = item.genres.any { genre ->
                val lowerGenre = genre.lowercase()
                LIVE_GENRE_KEYWORDS.any { lowerGenre.contains(it) }
            }
            if (hasLiveGenre) return true

            // Check title and description for live TV indicators
            val lowerName = item.name.lowercase()
            val lowerDesc = item.description?.lowercase().orEmpty()
            val hasLiveText = LIVE_TEXT_KEYWORDS.any { lowerName.contains(it) || lowerDesc.contains(it) }
            if (hasLiveText) return true
        }

        return false
    }
}

object LiveChannelClickState {
    @Volatile
    var lastClickedItem: MetaPreview? = null

    fun consume(id: String): MetaPreview? {
        val item = lastClickedItem
        if (item?.id == id) {
            lastClickedItem = null
            return item
        }
        return null
    }
}
