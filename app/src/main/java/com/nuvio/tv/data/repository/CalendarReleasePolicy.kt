package com.nuvio.tv.data.repository

import com.nuvio.tv.core.util.parseEpisodeReleaseLocalDate
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarRelease
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarReleaseWindow
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.Meta
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * Pure rules of the calendar cache: which dates are worth keeping, how long a resolution stays
 * valid and in which order stale sources are refreshed. No I/O, so every rule is unit-testable.
 */
internal object CalendarReleasePolicy {
    private const val HOUR_MS = 60L * 60 * 1000
    private const val DAY_MS = 24 * HOUR_MS

    /** A title with a release today or later can still change dates: same TTL as the meta cache. */
    const val ACTIVE_TTL_MS = 6 * HOUR_MS
    /** Aired within the last month; the next date usually appears within a day. */
    const val RECENT_TTL_MS = DAY_MS
    /** Nothing upcoming and nothing recent (ended shows, released movies): weekly check. */
    const val DORMANT_TTL_MS = 7 * DAY_MS
    /** Nothing placeable (year-only date) or unknown to every addon: weekly check. */
    const val UNRESOLVABLE_TTL_MS = 7 * DAY_MS
    /** Network or addon failure: retry soon, but not on every refresh tick. */
    const val FAILED_TTL_MS = HOUR_MS

    private const val RECENT_WINDOW_DAYS = 30L
    /** Spread expiries so a library resolved in one burst does not expire in one burst. */
    private const val TTL_JITTER_FRACTION = 0.15

    data class Window(val start: LocalDate, val endInclusive: LocalDate) {
        operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(endInclusive)
    }

    fun window(today: LocalDate): Window =
        Window(
            start = today.minusMonths(CalendarReleaseWindow.PAST_MONTHS),
            endInclusive = today.plusMonths(CalendarReleaseWindow.FUTURE_MONTHS)
        )

    /**
     * Extracts the dated releases of [meta] inside [window].
     *
     * - Series: one release per regular episode (season > 0) with a parseable date; an addon
     *   listing the same episode twice yields one release, so grid keys stay unique.
     * - Movies, or series without dated episodes: the top-level release date when it is a full
     *   date. Year-only values cannot be placed on a day and produce nothing.
     */
    fun extractReleases(meta: Meta, window: Window, zoneId: ZoneId): List<CalendarRelease> {
        val episodes = meta.videos
            .filter { video -> (video.season ?: 0) > 0 && video.episode != null }
            .mapNotNull { video ->
                val date = parseEpisodeReleaseLocalDate(video.released, zoneId) ?: return@mapNotNull null
                CalendarRelease(
                    date = date,
                    videoId = video.id,
                    season = video.season,
                    episode = video.episode,
                    episodeTitle = video.title.takeIf { it.isNotBlank() }
                )
            }
        if (episodes.isNotEmpty()) {
            return episodes
                .filter { it.date in window }
                .distinctBy { Triple(it.videoId, it.season, it.episode) }
        }

        val released = parseEpisodeReleaseLocalDate(meta.released, zoneId) ?: return emptyList()
        return if (released in window) listOf(CalendarRelease(date = released)) else emptyList()
    }

    /** A dated release TMDB reports for one country; `type` follows TMDB's release_dates codes. */
    data class TmdbReleaseDate(val countryCode: String, val type: Int?, val date: LocalDate?)

    private const val TMDB_TYPE_THEATRICAL_LIMITED = 2
    private const val TMDB_TYPE_THEATRICAL = 3
    private const val TMDB_TYPE_DIGITAL = 4
    private const val TMDB_TYPE_PHYSICAL = 5
    private const val FALLBACK_REGION = "US"

    /**
     * Replaces a movie's single addon date with TMDB's theatrical/digital/physical dates for the
     * viewer's [region] (falling back to US), earliest date per kind, inside [window]. When TMDB
     * has nothing usable the addon releases are kept unchanged.
     */
    fun mergeMovieReleases(
        addonReleases: List<CalendarRelease>,
        tmdbDates: List<TmdbReleaseDate>,
        region: String,
        window: Window
    ): List<CalendarRelease> {
        val byCountry = tmdbDates.groupBy { it.countryCode.uppercase() }
        val candidates = byCountry[region.uppercase()].orEmpty().ifEmpty { byCountry[FALLBACK_REGION].orEmpty() }
        val kinds = candidates
            .mapNotNull { entry ->
                val kind = when (entry.type) {
                    TMDB_TYPE_THEATRICAL_LIMITED, TMDB_TYPE_THEATRICAL -> CalendarReleaseKind.THEATRICAL
                    TMDB_TYPE_DIGITAL -> CalendarReleaseKind.DIGITAL
                    TMDB_TYPE_PHYSICAL -> CalendarReleaseKind.PHYSICAL
                    else -> null
                } ?: return@mapNotNull null
                val date = entry.date ?: return@mapNotNull null
                kind to date
            }
            .groupBy({ it.first }, { it.second })
            .map { (kind, dates) -> CalendarRelease(date = dates.min(), kind = kind) }
            .filter { it.date in window }
            .sortedBy { it.date }
        return kinds.ifEmpty { addonReleases }
    }

    /** Validity of a resolution given what it found, relative to [today]. */
    fun ttlMs(outcome: CalendarResolveOutcome, releases: List<CalendarRelease>, today: LocalDate): Long =
        when (outcome) {
            CalendarResolveOutcome.FAILED -> FAILED_TTL_MS
            CalendarResolveOutcome.NOT_FOUND, CalendarResolveOutcome.NO_DATES -> UNRESOLVABLE_TTL_MS
            CalendarResolveOutcome.RESOLVED -> {
                val latest = releases.maxOfOrNull { it.date } ?: return DORMANT_TTL_MS
                when {
                    !latest.isBefore(today) -> ACTIVE_TTL_MS
                    !latest.isBefore(today.minusDays(RECENT_WINDOW_DAYS)) -> RECENT_TTL_MS
                    else -> DORMANT_TTL_MS
                }
            }
        }

    fun expiresAt(nowMs: Long, ttlMs: Long, random: Random = Random.Default): Long {
        val jitter = (ttlMs * TTL_JITTER_FRACTION).toLong()
        return nowMs + ttlMs + random.nextLong(-jitter, jitter + 1)
    }

    /**
     * Sources that need a resolution now, most relevant first:
     * 1. never resolved, actively watched titles
     * 2. never resolved library titles, most recently saved first
     * 3. expired entries with the soonest upcoming release
     * 4. remaining expired entries, most recently resolved last
     *
     * With [requireReleaseKinds] a movie whose TMDB dates were never fetched counts as expired,
     * but not more often than [FAILED_TTL_MS], so a TMDB outage cannot re-queue it every pass.
     */
    fun stale(
        sources: List<CalendarSource>,
        entries: Map<String, CalendarReleaseEntry>,
        nowMs: Long,
        today: LocalDate,
        requireReleaseKinds: Boolean = false
    ): List<CalendarSource> {
        val missing = ArrayList<CalendarSource>()
        val expired = ArrayList<Pair<CalendarSource, CalendarReleaseEntry>>()
        sources.forEach { source ->
            val entry = entries[source.key]
            val kindsMissing = requireReleaseKinds && source.isMovie && entry != null &&
                !entry.releaseKindsResolved && nowMs - entry.resolvedAtMs >= FAILED_TTL_MS
            when {
                entry == null -> missing += source
                entry.isExpired(nowMs) || kindsMissing -> expired += source to entry
            }
        }
        val orderedMissing = missing.sortedWith(
            compareBy<CalendarSource> { it.origin != CalendarEventOrigin.WATCHING }
                .thenByDescending { it.recencyMs }
        )
        val orderedExpired = expired
            .sortedWith(
                compareBy<Pair<CalendarSource, CalendarReleaseEntry>> { (_, entry) ->
                    entry.releases.map { it.date }.filter { !it.isBefore(today) }.minOrNull() ?: LocalDate.MAX
                }.thenBy { (_, entry) -> entry.resolvedAtMs }
            )
            .map { it.first }
        return orderedMissing + orderedExpired
    }
}
