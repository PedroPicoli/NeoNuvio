package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.CalendarRefreshProgress
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Release dates for everything the user saved or is watching, resolved once per TTL and kept
 * on disk per profile so opening the calendar never re-queries the whole library.
 */
interface CalendarReleaseRepository {
    /** Library items plus actively watched series, deduplicated by key. */
    val sources: Flow<List<CalendarSource>>

    /** Persisted resolutions for the active profile, keyed by [CalendarSource.key]. */
    val entries: Flow<Map<String, CalendarReleaseEntry>>

    /** True when movie releases carry theatrical/digital/physical kinds (TMDB enabled in settings). */
    val supportsReleaseKinds: Flow<Boolean>

    /** Progress of the running refresh pass; `null` when idle. */
    val refreshProgress: StateFlow<CalendarRefreshProgress?>

    /**
     * Marks the calendar screen as visible or hidden. While visible a refresh pass resolves
     * everything stale at the interactive concurrency; once hidden the pass winds down after
     * the current batch and leaves the rest to the periodic refresh.
     */
    fun setScreenVisible(visible: Boolean)

    /** Resolves missing and expired entries now, prioritised by relevance. No-op if a pass is running. */
    fun requestRefresh()

    /** Starts the foreground ticker that keeps the cache fresh in small batches. */
    fun startPeriodicRefresh()

    fun stopPeriodicRefresh()

    /** Drops every persisted entry for the active profile. */
    suspend fun clearCache()
}
