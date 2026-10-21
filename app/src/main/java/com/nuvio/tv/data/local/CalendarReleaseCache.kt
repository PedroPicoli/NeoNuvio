package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.CalendarReleaseEntry
import kotlinx.coroutines.flow.Flow

/** Persistence seam for calendar resolutions; the production implementation is profile-scoped DataStore. */
interface CalendarReleaseCache {
    /** Entries of the active profile; re-emits after every write and when the profile changes. */
    val entries: Flow<Map<String, CalendarReleaseEntry>>

    suspend fun getEntries(profileId: Int): Map<String, CalendarReleaseEntry>

    /** Writes every entry in one transaction. */
    suspend fun upsertAll(profileId: Int, entries: Collection<CalendarReleaseEntry>)

    suspend fun removeAll(profileId: Int, sourceKeys: Collection<String>)

    suspend fun clear(profileId: Int)
}
