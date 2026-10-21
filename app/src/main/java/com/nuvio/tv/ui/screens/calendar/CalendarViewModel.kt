package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.CalendarPreferencesDataStore
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarRefreshProgress
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseWindow
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.CalendarViewMode
import com.nuvio.tv.domain.repository.CalendarReleaseRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val SOURCE_CHANGE_DEBOUNCE_MS = 500L

/** Events computed off the main thread from the persisted entries and the current sources. */
private data class CalendarContent(
    val eventsByDate: Map<LocalDate, List<CalendarEvent>>,
    val isLibraryEmpty: Boolean,
    val filter: CalendarFilter,
    val supportsKinds: Boolean,
    val hasAnime: Boolean,
    val hasWatching: Boolean,
    val hasNuvioLibrary: Boolean,
    val hasTraktLibrary: Boolean,
    val hasSimklLibrary: Boolean
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarReleaseRepository: CalendarReleaseRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val calendarPreferences: CalendarPreferencesDataStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init {
        observeCalendarContent()
        observeRefreshProgress()
        observeViewMode()
        refreshWhenSourcesChange()
        observeWatchedStateForSelectedDay()
    }

    /** Called by the screen; a visible calendar drives an interactive refresh of stale dates. */
    fun onScreenVisibilityChanged(visible: Boolean) {
        calendarReleaseRepository.setScreenVisible(visible)
    }

    fun setViewMode(mode: CalendarViewMode) {
        viewModelScope.launch { calendarPreferences.setViewMode(mode) }
    }

    fun toggleViewMode() {
        val next = if (_uiState.value.viewMode == CalendarViewMode.GRID) CalendarViewMode.LIST else CalendarViewMode.GRID
        setViewMode(next)
    }

    fun setFilter(filter: CalendarFilter) {
        viewModelScope.launch { calendarPreferences.setFilter(filter) }
    }

    fun showNextMonth() = shiftMonth(1)

    fun showPreviousMonth() = shiftMonth(-1)

    /**
     * Continues scrolling past the top of the agenda into the previous month, landing on its
     * *last* release day — the natural continuation of scrolling a timeline backwards — rather
     * than [showPreviousMonth]'s first-of-month landing, which suits an explicit toolbar jump.
     */
    fun continueIntoPreviousMonth() {
        _uiState.update { current ->
            val month = current.visibleMonth.minusMonths(1)
            if (month.isBefore(current.earliestMonth)) return@update current
            val lastReleaseDay = current.eventsByDate.keys.filter { YearMonth.from(it) == month }.maxOrNull()
            current.copy(
                visibleMonth = month,
                selectedDate = lastReleaseDay ?: defaultSelectedDate(month, current.today, current.eventsByDate)
            )
        }
    }

    /**
     * Continues scrolling past the bottom of the agenda into the next month, landing on its
     * first release day (or today) — same landing [showNextMonth] already uses for a toolbar jump,
     * kept as its own entry point so the two directions can diverge without surprise.
     */
    fun continueIntoNextMonth() = showNextMonth()

    fun showToday() {
        _uiState.update { current ->
            val today = LocalDate.now()
            current.copy(today = today, visibleMonth = YearMonth.from(today), selectedDate = today)
        }
    }

    /**
     * Re-reads the system date and, when it has actually advanced since the last check, jumps the
     * visible month and selection to the new today. A same-day recheck (e.g. re-entering the
     * screen after a Detail push/pop, or after the drawer restores this destination unchanged) is
     * a no-op, so exploring a future day mid-session is never disturbed — only a real day rollover
     * resets it. This is also what fixes the ViewModel surviving for days in the drawer's
     * save/restoreState cache with a stale selection from whenever it was first created.
     */
    fun refreshToday() {
        val today = LocalDate.now()
        _uiState.update { current ->
            if (current.today == today) {
                current
            } else {
                current.copy(today = today, visibleMonth = YearMonth.from(today), selectedDate = today)
            }
        }
    }

    fun selectDate(date: LocalDate) {
        _uiState.update { current ->
            if (current.selectedDate == date) current else current.copy(selectedDate = date)
        }
    }

    /** Jumps to the first day after the current selection that has a release, changing month if needed. */
    fun jumpToNextRelease() {
        _uiState.update { current ->
            val next = current.nextReleaseAfter(current.selectedDate) ?: return@update current
            current.copy(visibleMonth = YearMonth.from(next), selectedDate = next)
        }
    }

    private fun shiftMonth(months: Long) {
        _uiState.update { current ->
            val month = current.visibleMonth.plusMonths(months)
            if (month.isBefore(current.earliestMonth) || month.isAfter(current.latestMonth)) return@update current
            current.copy(
                visibleMonth = month,
                selectedDate = defaultSelectedDate(month, current.today, current.eventsByDate)
            )
        }
    }

    private fun observeCalendarContent() {
        viewModelScope.launch {
            combine(
                calendarReleaseRepository.sources,
                calendarReleaseRepository.entries,
                calendarPreferences.filter,
                calendarReleaseRepository.supportsReleaseKinds
            ) { sources, entries, savedFilter, supportsKinds ->
                val events = buildEvents(sources, entries)
                val hasAnime = events.any { it.isAnime }
                val isLibraryEvent = { event: CalendarEvent -> event.origin == CalendarEventOrigin.LIBRARY }
                val hasWatching = events.any { it.isWatching }
                val hasNuvioLibrary = events.any { isLibraryEvent(it) && it.libraryProviderId == null }
                val hasTraktLibrary = events.any { isLibraryEvent(it) && it.libraryProviderId == TrackingProviderId.TRAKT.storageId }
                val hasSimklLibrary = events.any { isLibraryEvent(it) && it.libraryProviderId == TrackingProviderId.SIMKL.storageId }
                // A saved filter that can no longer match anything (TMDB switched off, last anime
                // removed, tracker disconnected) would hide everything: fall back to "All".
                val filter = when {
                    savedFilter == CalendarFilter.DIGITAL && !supportsKinds -> CalendarFilter.ALL
                    savedFilter == CalendarFilter.ANIME && !hasAnime -> CalendarFilter.ALL
                    savedFilter == CalendarFilter.WATCHING && !hasWatching -> CalendarFilter.ALL
                    savedFilter == CalendarFilter.LIBRARY_NUVIO && !hasNuvioLibrary -> CalendarFilter.ALL
                    savedFilter == CalendarFilter.LIBRARY_TRAKT && !hasTraktLibrary -> CalendarFilter.ALL
                    savedFilter == CalendarFilter.LIBRARY_SIMKL && !hasSimklLibrary -> CalendarFilter.ALL
                    else -> savedFilter
                }
                CalendarContent(
                    eventsByDate = groupEventsByDate(filterCalendarEvents(events, filter)),
                    isLibraryEmpty = sources.isEmpty(),
                    filter = filter,
                    supportsKinds = supportsKinds,
                    hasAnime = hasAnime,
                    hasWatching = hasWatching,
                    hasNuvioLibrary = hasNuvioLibrary,
                    hasTraktLibrary = hasTraktLibrary,
                    hasSimklLibrary = hasSimklLibrary
                )
            }
                .conflate()
                .flowOn(Dispatchers.Default)
                .collect { content ->
                    _uiState.update { current ->
                        current.copy(
                            eventsByDate = content.eventsByDate,
                            isLibraryEmpty = content.isLibraryEmpty,
                            filter = content.filter,
                            isDigitalFilterAvailable = content.supportsKinds,
                            isAnimeFilterAvailable = content.hasAnime,
                            isWatchingFilterAvailable = content.hasWatching,
                            isNuvioLibraryFilterAvailable = content.hasNuvioLibrary,
                            isTraktLibraryFilterAvailable = content.hasTraktLibrary,
                            isSimklLibraryFilterAvailable = content.hasSimklLibrary
                        )
                    }
                }
        }
    }

    private fun observeViewMode() {
        viewModelScope.launch {
            calendarPreferences.viewMode.collect { mode ->
                _uiState.update { current -> if (current.viewMode == mode) current else current.copy(viewMode = mode) }
            }
        }
    }

    /** Header counter only; kept apart from the content flow so it never triggers a regroup. */
    private fun observeRefreshProgress() {
        viewModelScope.launch {
            calendarReleaseRepository.refreshProgress.collect { progress: CalendarRefreshProgress? ->
                _uiState.update { current ->
                    current.copy(
                        isLoading = progress?.isRunning == true,
                        loadedCount = progress?.resolved ?: 0,
                        totalCount = progress?.total ?: 0
                    )
                }
            }
        }
    }

    /** A saved or newly watched title should show up without waiting for the periodic tick. */
    private fun refreshWhenSourcesChange() {
        viewModelScope.launch {
            calendarReleaseRepository.sources
                .map { sources -> sources.mapTo(HashSet()) { it.key } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(SOURCE_CHANGE_DEBOUNCE_MS)
                .collect { calendarReleaseRepository.requestRefresh() }
        }
        // Turning TMDB on makes every movie eligible for release kinds: fetch them now, not next tick.
        viewModelScope.launch {
            calendarReleaseRepository.supportsReleaseKinds
                .distinctUntilChanged()
                .drop(1)
                .collect { calendarReleaseRepository.requestRefresh() }
        }
    }

    /** Keeps [CalendarUiState.watchedEventKeys] in sync with the releases of the selected day only. */
    private fun observeWatchedStateForSelectedDay() {
        viewModelScope.launch {
            _uiState
                .map { it.selectedDateEvents }
                .distinctUntilChanged()
                .flatMapLatest { events -> watchedKeysFlow(events) }
                .distinctUntilChanged()
                .collect { watched ->
                    _uiState.update { current ->
                        if (current.watchedEventKeys == watched) current else current.copy(watchedEventKeys = watched)
                    }
                }
        }
    }

    private fun watchedKeysFlow(events: List<CalendarEvent>): Flow<Set<String>> {
        if (events.isEmpty()) return flowOf(emptySet())
        val flows = events.map { event ->
            watchProgressRepository
                .isWatched(contentId = event.itemId, season = event.season, episode = event.episode)
                .map { watched -> event.key to watched }
        }
        return combine(flows) { pairs -> pairs.filter { it.second }.map { it.first }.toSet() }
    }

    private fun buildEvents(
        sources: List<CalendarSource>,
        entries: Map<String, CalendarReleaseEntry>
    ): List<CalendarEvent> = sources.flatMap { source ->
        entries[source.key]?.let { entry -> buildCalendarEvents(source, entry) }.orEmpty()
    }

    private fun initialState(): CalendarUiState {
        val today = LocalDate.now()
        return CalendarUiState(
            today = today,
            visibleMonth = YearMonth.from(today),
            selectedDate = today,
            earliestMonth = YearMonth.from(today.minusMonths(CalendarReleaseWindow.PAST_MONTHS)),
            latestMonth = YearMonth.from(today.plusMonths(CalendarReleaseWindow.FUTURE_MONTHS))
        )
    }
}
