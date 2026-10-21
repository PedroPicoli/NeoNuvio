package com.nuvio.tv.ui.screens.calendar

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarViewMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import java.time.YearMonth

/**
 * A single release shown on the calendar: either a movie release or one episode of a series.
 */
@Immutable
data class CalendarEvent(
    /** Stable unique key, e.g. `series:tt123:tt123:2:5` or `movie:tt1:DIGITAL`. */
    val key: String,
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String?,
    /** Movie or series title. */
    val title: String,
    val poster: String?,
    val posterShape: PosterShape,
    val date: LocalDate,
    val origin: CalendarEventOrigin,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    /** Movies only: how this date reaches the audience, when TMDB provided it. */
    val kind: CalendarReleaseKind? = null,
    val isAnime: Boolean = false,
    /** `TrackingProviderId.storageId` when [origin] is LIBRARY and a tracker owns the save; null otherwise. */
    val libraryProviderId: String? = null,
    /** In Continue Watching / Next Up, independent of [origin] — see `CalendarSource.isWatching`. */
    val isWatching: Boolean = false
) {
    val isEpisode: Boolean
        get() = season != null && episode != null

    val isMovie: Boolean
        get() = ContentType.fromString(itemType) == ContentType.MOVIE

    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = itemId,
        type = ContentType.fromString(itemType),
        rawType = itemType,
        name = title,
        poster = poster,
        posterShape = posterShape,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}

@Immutable
data class CalendarUiState(
    val today: LocalDate = LocalDate.now(),
    val visibleMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    /** Earliest and latest month the grid can navigate to; matches the tracked release window. */
    val earliestMonth: YearMonth = YearMonth.now(),
    val latestMonth: YearMonth = YearMonth.now(),
    /** Events after [filter] was applied. */
    val eventsByDate: Map<LocalDate, List<CalendarEvent>> = emptyMap(),
    /** Keys of [CalendarEvent]s on the selected day that the user has already watched. */
    val watchedEventKeys: Set<String> = emptySet(),
    val viewMode: CalendarViewMode = CalendarViewMode.GRID,
    val filter: CalendarFilter = CalendarFilter.ALL,
    /** Whether the "Digital" filter can be offered (needs TMDB release kinds). */
    val isDigitalFilterAvailable: Boolean = false,
    /** Whether the "Anime" filter can be offered (at least one tracked title is anime). */
    val isAnimeFilterAvailable: Boolean = false,
    /** Whether "Watching" has at least one event not already saved to a library. */
    val isWatchingFilterAvailable: Boolean = false,
    /** Whether the library has at least one plain Nuvio save (no tracking provider). */
    val isNuvioLibraryFilterAvailable: Boolean = false,
    val isTraktLibraryFilterAvailable: Boolean = false,
    val isSimklLibraryFilterAvailable: Boolean = false,
    val isLibraryEmpty: Boolean = false,
    /** True while a refresh pass is resolving dates; the cached calendar stays interactive meanwhile. */
    val isLoading: Boolean = false,
    val loadedCount: Int = 0,
    val totalCount: Int = 0
) {
    fun eventsOn(date: LocalDate): List<CalendarEvent> = eventsByDate[date].orEmpty()

    val selectedDateEvents: List<CalendarEvent>
        get() = eventsOn(selectedDate)

    val canShowPreviousMonth: Boolean
        get() = visibleMonth.isAfter(earliestMonth)

    val canShowNextMonth: Boolean
        get() = visibleMonth.isBefore(latestMonth)

    /** Days of the visible month that have at least one release, in order; drives the list view. */
    val visibleMonthDays: List<LocalDate>
        get() = eventsByDate.keys.filter { YearMonth.from(it) == visibleMonth }.sorted()

    /** First day strictly after [date] that has at least one release, if any. */
    fun nextReleaseAfter(date: LocalDate): LocalDate? =
        eventsByDate.keys.filter { it.isAfter(date) }.minOrNull()
}
