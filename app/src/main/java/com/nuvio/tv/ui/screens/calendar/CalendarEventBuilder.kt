package com.nuvio.tv.ui.screens.calendar

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarSource
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Weeks rendered by the month grid; six rows always cover any month for any first-day-of-week. */
internal const val CALENDAR_WEEK_ROWS = 6
internal const val DAYS_PER_WEEK = 7

/**
 * Turns a persisted [CalendarReleaseEntry] into the events shown for [source].
 *
 * Title and poster come from the resolved meta when present, otherwise from the source; the
 * origin caption always reflects the current source (a watched title can be saved later).
 */
internal fun buildCalendarEvents(source: CalendarSource, entry: CalendarReleaseEntry): List<CalendarEvent> {
    val title = entry.title.ifBlank { source.name }
    val poster = entry.poster ?: source.poster
    return entry.releases.map { release ->
        val isEpisode = release.season != null && release.episode != null
        CalendarEvent(
            key = when {
                isEpisode -> "${source.key}:${release.videoId}:${release.season}:${release.episode}"
                release.kind != null -> "${source.key}:${release.kind.name}"
                else -> source.key
            },
            itemId = source.id,
            itemType = source.type,
            addonBaseUrl = source.addonBaseUrl,
            title = title,
            poster = poster,
            posterShape = entry.posterShape,
            date = release.date,
            origin = source.origin,
            season = release.season,
            episode = release.episode,
            episodeTitle = release.episodeTitle,
            kind = release.kind,
            isAnime = source.isAnime || entry.isAnime,
            libraryProviderId = source.libraryProviderId,
            isWatching = source.isWatching
        )
    }
}

/** Keeps only the events the user asked to see. */
internal fun filterCalendarEvents(events: List<CalendarEvent>, filter: CalendarFilter): List<CalendarEvent> =
    when (filter) {
        CalendarFilter.ALL -> events
        CalendarFilter.MOVIES -> events.filter { it.isMovie }
        CalendarFilter.SERIES -> events.filter { !it.isMovie }
        CalendarFilter.ANIME -> events.filter { it.isAnime }
        CalendarFilter.WATCHING -> events.filter { it.isWatching }
        CalendarFilter.LIBRARY_NUVIO -> events.filter {
            it.origin == CalendarEventOrigin.LIBRARY && it.libraryProviderId == null
        }
        CalendarFilter.LIBRARY_TRAKT -> events.filter {
            it.origin == CalendarEventOrigin.LIBRARY && it.libraryProviderId == TrackingProviderId.TRAKT.storageId
        }
        CalendarFilter.LIBRARY_SIMKL -> events.filter {
            it.origin == CalendarEventOrigin.LIBRARY && it.libraryProviderId == TrackingProviderId.SIMKL.storageId
        }
        CalendarFilter.DIGITAL -> events.filter { it.kind == CalendarReleaseKind.DIGITAL }
    }

/** Groups events by day, sorted by date, then episode order, then title. */
internal fun groupEventsByDate(events: Collection<CalendarEvent>): Map<LocalDate, List<CalendarEvent>> =
    events
        .sortedWith(
            compareBy<CalendarEvent> { it.date }
                .thenBy { it.title.lowercase() }
                .thenBy { it.season ?: 0 }
                .thenBy { it.episode ?: 0 }
        )
        .groupBy { it.date }

/**
 * Builds the 6x7 grid of days for [month], starting weeks on [firstDayOfWeek].
 * Cells outside the month are `null` so the UI can render them as blanks.
 */
internal fun buildMonthGrid(
    month: YearMonth,
    firstDayOfWeek: DayOfWeek
): List<List<LocalDate?>> {
    val firstOfMonth = month.atDay(1)
    val leadingBlanks = ((firstOfMonth.dayOfWeek.value - firstDayOfWeek.value) + DAYS_PER_WEEK) % DAYS_PER_WEEK
    val totalCells = CALENDAR_WEEK_ROWS * DAYS_PER_WEEK
    val daysInMonth = month.lengthOfMonth()

    return (0 until totalCells)
        .map { index ->
            val dayOfMonth = index - leadingBlanks + 1
            if (dayOfMonth in 1..daysInMonth) month.atDay(dayOfMonth) else null
        }
        .chunked(DAYS_PER_WEEK)
}

/** Ordered weekday headers matching the grid columns. */
internal fun weekdayOrder(firstDayOfWeek: DayOfWeek): List<DayOfWeek> =
    (0 until DAYS_PER_WEEK).map { firstDayOfWeek.plus(it.toLong()) }

/**
 * Day whose first release the agenda lands on for [selectedDate]: the selected day itself when it
 * has releases, otherwise the next day with releases, otherwise the last one before it. Keeps the
 * D-pad next to "today" on an empty day instead of jumping to the top of the month.
 */
internal fun agendaAnchorDate(releaseDays: List<LocalDate>, selectedDate: LocalDate): LocalDate? =
    releaseDays.firstOrNull { !it.isBefore(selectedDate) } ?: releaseDays.lastOrNull()

/**
 * Days the agenda lists for [month]: every day with a release, plus today when it falls in the
 * month and has nothing — an empty today still deserves its place in the timeline.
 */
internal fun agendaDays(releaseDays: List<LocalDate>, today: LocalDate, month: YearMonth): List<LocalDate> =
    if (YearMonth.from(today) == month && today !in releaseDays) (releaseDays + today).sorted() else releaseDays

/**
 * Picks the day to pre-select when [month] becomes visible: today if it falls in the month,
 * otherwise the first day with events, otherwise the first of the month.
 */
internal fun defaultSelectedDate(
    month: YearMonth,
    today: LocalDate,
    eventsByDate: Map<LocalDate, List<CalendarEvent>>
): LocalDate {
    if (YearMonth.from(today) == month) return today
    return eventsByDate.keys
        .filter { YearMonth.from(it) == month }
        .minOrNull()
        ?: month.atDay(1)
}
