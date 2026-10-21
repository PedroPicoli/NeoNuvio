package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarViewMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Per-profile calendar display choices (layout and filter); survive restarts. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CalendarPreferencesDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "calendar_prefs"
    }

    private val viewModeKey = stringPreferencesKey("view_mode")
    private val filterKey = stringPreferencesKey("filter")

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    val viewMode: Flow<CalendarViewMode> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs ->
            prefs[viewModeKey]?.let { runCatching { CalendarViewMode.valueOf(it) }.getOrNull() } ?: CalendarViewMode.GRID
        }
    }

    val filter: Flow<CalendarFilter> = profileManager.activeProfileId.flatMapLatest { profileId ->
        store(profileId).data.map { prefs ->
            prefs[filterKey]?.let { runCatching { CalendarFilter.valueOf(it) }.getOrNull() } ?: CalendarFilter.ALL
        }
    }

    suspend fun setViewMode(mode: CalendarViewMode) {
        store().edit { it[viewModeKey] = mode.name }
    }

    suspend fun setFilter(filter: CalendarFilter) {
        store().edit { it[filterKey] = filter.name }
    }
}
