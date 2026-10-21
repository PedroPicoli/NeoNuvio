package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/** Where a calendar item comes from; drives the small caption under the card. */
enum class CalendarEventOrigin {
    /** Saved in the library (any list). */
    LIBRARY,
    /** Not saved, but the user is watching it (Continue Watching / Next Up). */
    WATCHING
}

/** Minimal description of a title whose release dates should appear on the calendar. */
@Immutable
data class CalendarSource(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val addonBaseUrl: String?,
    val origin: CalendarEventOrigin,
    /** When the user last interacted with the title (saved / watched); newer resolves first. */
    val recencyMs: Long = 0L,
    /** Known TMDB id (tracking providers supply it); saves a lookup when resolving release kinds. */
    val tmdbId: Int? = null,
    /** True when the library/tracker already knows the title is anime; the resolved meta can add to it. */
    val isAnime: Boolean = false,
    /** `TrackingProviderId.storageId` ("trakt", "simkl") when a tracker owns this entry; null for a
     *  plain Nuvio library save or a Continue Watching source, where it is meaningless. */
    val libraryProviderId: String? = null,
    /** In Continue Watching / Next Up, independent of [origin] — a title saved to a library keeps
     *  its LIBRARY origin (for the caption and library filters) but can still be watching. */
    val isWatching: Boolean = false
) {
    val isMovie: Boolean
        get() = ContentType.fromString(type) == ContentType.MOVIE

    val key: String
        get() = "$type:$id"

    companion object {
        fun fromLibrary(entry: LibraryEntry) = CalendarSource(
            id = entry.id,
            type = entry.type,
            name = entry.name,
            poster = entry.poster,
            addonBaseUrl = entry.addonBaseUrl,
            origin = CalendarEventOrigin.LIBRARY,
            recencyMs = entry.listedAt,
            tmdbId = entry.tmdbId,
            isAnime = entry.mediaCategory.equals("anime", ignoreCase = true) ||
                CalendarAnimeDetection.matches(id = entry.id, genres = entry.genres),
            libraryProviderId = entry.trackingProviderId
        )

        fun fromWatchProgress(progress: WatchProgress) = CalendarSource(
            id = progress.contentId,
            type = progress.contentType,
            name = progress.name,
            poster = progress.poster,
            addonBaseUrl = progress.addonBaseUrl,
            origin = CalendarEventOrigin.WATCHING,
            recencyMs = progress.lastWatched,
            isAnime = CalendarAnimeDetection.matches(id = progress.contentId, genres = emptyList()),
            isWatching = true
        )
    }
}

/**
 * Best-effort anime detection from what addons and trackers expose. A title counts as anime when
 * its id comes from an anime tracker, a genre says so outright, or it is animation in Japanese.
 */
object CalendarAnimeDetection {
    private val ANIME_ID_PREFIXES = setOf("kitsu", "mal", "anilist", "anidb")
    private val ANIMATION_GENRES = setOf("animation", "animazione", "anime")
    /** Co-productions list every country ("Japan, United States"); a single Japanese credit is enough. */
    private val MULTI_VALUE_SPLIT = Regex("[,/]")

    fun matches(id: String, genres: List<String>, language: String? = null, country: String? = null): Boolean {
        val prefix = id.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (prefix in ANIME_ID_PREFIXES) return true
        val normalizedGenres = genres.map { it.trim().lowercase() }
        if (normalizedGenres.any { it == "anime" || it.startsWith("anime ") || it.endsWith(" anime") }) return true
        val isAnimation = normalizedGenres.any { it in ANIMATION_GENRES }
        if (!isAnimation) return false
        val lang = normalizeLanguageCode(language)
        if (lang == "ja") return true
        if (lang != null) return false
        return country.orEmpty().split(MULTI_VALUE_SPLIT).any { countryToLanguageCode(it.trim()) == "ja" }
    }
}

/** How a movie reaches the audience on a given date; `null` when the addon only gives one date. */
enum class CalendarReleaseKind {
    THEATRICAL,
    DIGITAL,
    PHYSICAL
}

/** One dated release of a title: a movie premiere or a single episode. */
@Immutable
data class CalendarRelease(
    val date: LocalDate,
    val videoId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val kind: CalendarReleaseKind? = null
)

/** How the last resolution of a source ended; drives how long the entry stays valid. */
enum class CalendarResolveOutcome {
    /** Meta fetched and at least one dated release found in the tracked window. */
    RESOLVED,
    /** Meta fetched but nothing could be placed on a day (e.g. year-only release). */
    NO_DATES,
    /** Every addon answered "no such item"; a retry is unlikely to change that soon. */
    NOT_FOUND,
    /** Network or addon error; worth retrying soon. */
    FAILED
}

/**
 * Persisted resolution of one [CalendarSource]: the releases inside the tracked window plus
 * the metadata needed to decide when to ask the addon again.
 */
@Immutable
data class CalendarReleaseEntry(
    val sourceKey: String,
    /** Title as reported by the addon; falls back to the source name when blank. */
    val title: String,
    val poster: String?,
    val posterShape: PosterShape,
    val releases: List<CalendarRelease>,
    val outcome: CalendarResolveOutcome,
    val resolvedAtMs: Long,
    val expiresAtMs: Long,
    /** True once TMDB was asked about theatrical/digital/physical dates (movies only). */
    val releaseKindsResolved: Boolean = false,
    /** Anime as judged from the resolved meta (genres, language); the source may know it too. */
    val isAnime: Boolean = false
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/** How the calendar lays out a month. */
enum class CalendarViewMode {
    GRID,
    LIST
}

/** Which releases the calendar shows. */
enum class CalendarFilter {
    ALL,
    MOVIES,
    SERIES,
    /** Anime movies and series (tracker id, genre or Japanese animation). */
    ANIME,
    /** Not saved, but in Continue Watching / Next Up. */
    WATCHING,
    /** Saved locally in Nuvio's own library, with no tracking provider. */
    LIBRARY_NUVIO,
    /** Saved through the Trakt integration. */
    LIBRARY_TRAKT,
    /** Saved through the Simkl integration. */
    LIBRARY_SIMKL,
    /** Movies on their digital/streaming release date only; needs TMDB. */
    DIGITAL
}

/** Months around today whose releases are tracked; the grid cannot navigate outside them. */
object CalendarReleaseWindow {
    /** Releases older than this are never stored. */
    const val PAST_MONTHS = 6L
    /** Releases further out than this are not stored yet; they enter the window on a later refresh. */
    const val FUTURE_MONTHS = 12L
}

/** Live progress of a calendar refresh pass, for the screen header. */
@Immutable
data class CalendarRefreshProgress(
    val resolved: Int,
    val total: Int,
    val isRunning: Boolean
)
