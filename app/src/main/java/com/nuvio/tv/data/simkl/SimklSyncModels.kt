package com.nuvio.tv.data.simkl

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class SimklMediaType(val apiValue: String) {
    @SerialName("shows") SHOWS("shows"),
    @SerialName("movies") MOVIES("movies"),
    @SerialName("anime") ANIME("anime")
}

object SimklListStatusSerializer : KSerializer<SimklListStatus> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SimklListStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SimklListStatus) {
        encoder.encodeString(value.apiValue)
    }

    override fun deserialize(decoder: Decoder): SimklListStatus {
        val raw = decoder.decodeString()
        return parseSimklListStatus(raw)
            ?: SimklListStatus.entries.firstOrNull { it.apiValue.equals(raw.trim(), ignoreCase = true) }
            ?: SimklListStatus.PLAN_TO_WATCH
    }
}

fun parseSimklListStatus(raw: String?): SimklListStatus? {
    if (raw.isNullOrBlank()) return null
    val normalized = raw.trim().lowercase().replace(" ", "").replace("_", "").replace("-", "")
    return when (normalized) {
        "watching" -> SimklListStatus.WATCHING
        "plantowatch" -> SimklListStatus.PLAN_TO_WATCH
        "hold", "onhold", "paused" -> SimklListStatus.ON_HOLD
        "completed" -> SimklListStatus.COMPLETED
        "dropped" -> SimklListStatus.DROPPED
        else -> null
    }
}

@Serializable(with = SimklListStatusSerializer::class)
enum class SimklListStatus(val apiValue: String) {
    @SerialName("watching") WATCHING("watching"),
    @SerialName("plantowatch") PLAN_TO_WATCH("plantowatch"),
    @SerialName("hold") ON_HOLD("hold"),
    @SerialName("completed") COMPLETED("completed"),
    @SerialName("dropped") DROPPED("dropped")
}

@Serializable
data class SimklMedia(
    val title: String? = null,
    val poster: String? = null,
    val year: Int? = null,
    val runtime: Int? = null,
    val ids: Map<String, JsonElement> = emptyMap()
)

@Serializable
data class SimklEpisodeMapping(
    val season: Int? = null,
    val episode: Int? = null
)

@Serializable
data class SimklEpisodeIds(
    @SerialName("tvdb_id") val tvdbId: Long? = null
)

@Serializable
data class SimklEpisode(
    val number: Int? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val tvdb: SimklEpisodeMapping? = null,
    val ids: SimklEpisodeIds? = null,
    val episode: Int? = null,
    val season: Int? = null
) {
    val episodeNumber: Int?
        get() = number ?: episode ?: tvdb?.episode
}

@Serializable
data class SimklSeason(
    val number: Int? = null,
    val episodes: List<SimklEpisode> = emptyList(),
    val season: Int? = null
) {
    val seasonNumber: Int?
        get() = number ?: season
}

@Serializable
data class SimklLibraryEntry(
    val mediaType: SimklMediaType = SimklMediaType.SHOWS,
    val localPosterUrl: String? = null,
    @SerialName("added_to_watchlist_at") val addedToWatchlistAt: String? = null,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    @SerialName("user_rated_at") val userRatedAt: String? = null,
    @SerialName("user_rating") val userRating: Int? = null,
    val status: SimklListStatus? = null,
    @SerialName("last_watched") val lastWatched: String? = null,
    @SerialName("next_to_watch") val nextToWatch: String? = null,
    @SerialName("watched_episodes_count") val watchedEpisodesCount: Int = 0,
    @SerialName("total_episodes_count") val totalEpisodesCount: Int = 0,
    @SerialName("not_aired_episodes_count") val notAiredEpisodesCount: Int = 0,
    val show: SimklMedia? = null,
    val movie: SimklMedia? = null,
    val anime: SimklMedia? = null,
    @SerialName("anime_type") val animeType: String? = null,
    val seasons: List<SimklSeason> = emptyList()
) {
    val media: SimklMedia?
        get() = movie ?: show ?: anime

    fun stableKey(): String? = media?.ids?.stableMediaId()?.let { id ->
        "${mediaType.apiValue}:$id"
    }

    /** True when this entry represents a movie — either a regular movie or an anime movie. */
    fun isMovieEntry(): Boolean {
        if (mediaType == SimklMediaType.MOVIES) return true
        if (mediaType == SimklMediaType.ANIME) {
            val type = animeType?.trim()?.lowercase()
            return type == "movie" || type == "movies"
        }
        return false
    }
}

@Serializable
data class SimklAllItemsResponse(
    val shows: List<SimklLibraryEntry>? = null,
    val movies: List<SimklLibraryEntry>? = null,
    val anime: List<SimklLibraryEntry>? = null
) {
    fun entriesFor(type: SimklMediaType): List<SimklLibraryEntry> = when (type) {
        SimklMediaType.SHOWS -> shows
        SimklMediaType.MOVIES -> movies
        SimklMediaType.ANIME -> anime
    }.orEmpty().map { entry -> entry.copy(mediaType = type) }

    fun presentTypes(): Set<SimklMediaType> = buildSet {
        if (shows != null) add(SimklMediaType.SHOWS)
        if (movies != null) add(SimklMediaType.MOVIES)
        if (anime != null) add(SimklMediaType.ANIME)
    }
}

@Serializable
data class SimklActivitySettings(
    val all: String? = null
)

@Serializable
data class SimklActivityDomain(
    val all: String? = null,
    @SerialName("rated_at") val ratedAt: String? = null,
    val playback: String? = null,
    val plantowatch: String? = null,
    val watching: String? = null,
    val completed: String? = null,
    val hold: String? = null,
    val dropped: String? = null,
    @SerialName("removed_from_list") val removedFromList: String? = null
)

@Serializable
data class SimklActivities(
    val all: String? = null,
    val settings: SimklActivitySettings = SimklActivitySettings(),
    @SerialName("tv_shows") val tvShows: SimklActivityDomain = SimklActivityDomain(),
    val movies: SimklActivityDomain = SimklActivityDomain(),
    val anime: SimklActivityDomain = SimklActivityDomain()
) {
    fun domain(type: SimklMediaType): SimklActivityDomain = when (type) {
        SimklMediaType.SHOWS -> tvShows
        SimklMediaType.MOVIES -> movies
        SimklMediaType.ANIME -> anime
    }
}

@Serializable
data class SimklPlaybackEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    @SerialName("tvdb_season") val tvdbSeason: Int? = null,
    @SerialName("tvdb_number") val tvdbNumber: Int? = null
)

@Serializable
data class SimklPlaybackSession(
    val id: Long? = null,
    val progress: Double = 0.0,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    val type: String? = null,
    val episode: SimklPlaybackEpisode? = null,
    val show: SimklMedia? = null,
    val anime: SimklMedia? = null,
    val movie: SimklMedia? = null
) {
    val media: SimklMedia?
        get() = movie ?: anime ?: show

    val mediaType: SimklMediaType
        get() = when {
            movie != null -> SimklMediaType.MOVIES
            anime != null -> SimklMediaType.ANIME
            else -> SimklMediaType.SHOWS
        }
}

@Serializable
data class SimklSyncSnapshot(
    val schemaVersion: Int = 2,
    val isInitialized: Boolean = false,
    val watermark: String? = null,
    val activities: SimklActivities? = null,
    val entries: List<SimklLibraryEntry> = emptyList(),
    val playback: List<SimklPlaybackSession> = emptyList(),
    val lastSyncedAtEpochMs: Long? = null,
    val lastCheckedAtEpochMs: Long? = null
)

data class SimklSyncState(
    val snapshot: SimklSyncSnapshot = SimklSyncSnapshot(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val projectionVersion: Long = 0L
)

fun Map<String, JsonElement>.idValue(key: String): String? {
    val element = get(key) ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    val content = primitive.content.trim()
    if (content.isEmpty() || content.equals("null", ignoreCase = true)) return null
    return content
}

fun Map<String, JsonElement>.simklIdValue(): String? = idValue("simkl") ?: idValue("simkl_id")

private fun Map<String, JsonElement>.stableMediaId(): String? {
    simklIdValue()?.let { return "simkl:$it" }
    val keys = listOf("imdb", "tmdb", "tvdb", "mal", "anidb", "anilist", "kitsu")
    return keys.firstNotNullOfOrNull { key -> idValue(key)?.let { value -> "$key:$value" } }
}
