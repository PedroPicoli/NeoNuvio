package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CalendarRelease
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.PosterShape
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Per-profile store of resolved calendar entries, one JSON value per source key.
 *
 * Profile scoping, corruption recovery and profile deletion clean-up come from
 * [ProfileDataStoreFactory], like every other profile-scoped cache in the app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CalendarReleaseCacheDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : CalendarReleaseCache {
    companion object {
        private const val TAG = "CalendarReleaseCache"
        private const val FEATURE = "calendar_release_cache"
        private const val ENTRY_KEY_PREFIX = "src_"
        private const val SCHEMA_VERSION = 1
    }

    private val gson = Gson()

    private fun store(profileId: Int) = factory.get(profileId, FEATURE)

    private fun entryKey(sourceKey: String) = stringPreferencesKey("$ENTRY_KEY_PREFIX$sourceKey")

    /** Entries of the active profile; switches automatically when the profile changes. */
    override val entries: Flow<Map<String, CalendarReleaseEntry>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            store(profileId).data.map { prefs -> prefs.decodeEntries() }
        }

    override suspend fun getEntries(profileId: Int): Map<String, CalendarReleaseEntry> =
        store(profileId).data.first().decodeEntries()

    /** Writes every entry in one transaction; a batch of resolutions costs a single file write. */
    override suspend fun upsertAll(profileId: Int, entries: Collection<CalendarReleaseEntry>) {
        if (entries.isEmpty()) return
        store(profileId).edit { prefs ->
            entries.forEach { entry ->
                prefs[entryKey(entry.sourceKey)] = gson.toJson(entry.toDto())
            }
        }
    }

    override suspend fun removeAll(profileId: Int, sourceKeys: Collection<String>) {
        if (sourceKeys.isEmpty()) return
        store(profileId).edit { prefs ->
            sourceKeys.forEach { prefs.remove(entryKey(it)) }
        }
    }

    override suspend fun clear(profileId: Int) {
        store(profileId).edit { it.clear() }
    }

    private fun Preferences.decodeEntries(): Map<String, CalendarReleaseEntry> {
        val decoded = LinkedHashMap<String, CalendarReleaseEntry>()
        asMap().forEach { (key, value) ->
            if (!key.name.startsWith(ENTRY_KEY_PREFIX) || value !is String) return@forEach
            val entry = runCatching { gson.fromJson(value, CalendarReleaseEntryDto::class.java).toDomain() }
                .onFailure { Log.w(TAG, "Dropping unreadable calendar entry ${key.name}: ${it.message}") }
                .getOrNull() ?: return@forEach
            decoded[entry.sourceKey] = entry
        }
        return decoded
    }

    // --- Serialization -----------------------------------------------------------------------

    /** Gson skips Kotlin constructors, so every field is nullable and validated on decode. */
    private data class CalendarReleaseEntryDto(
        val v: Int? = SCHEMA_VERSION,
        val sourceKey: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val posterShape: String? = null,
        val releases: List<CalendarReleaseDto>? = null,
        val outcome: String? = null,
        val resolvedAtMs: Long? = null,
        val expiresAtMs: Long? = null,
        val kindsResolved: Boolean? = null,
        val anime: Boolean? = null
    )

    private data class CalendarReleaseDto(
        val date: String? = null,
        val videoId: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeTitle: String? = null,
        val kind: String? = null
    )

    private fun CalendarReleaseEntry.toDto() = CalendarReleaseEntryDto(
        sourceKey = sourceKey,
        title = title,
        poster = poster,
        posterShape = posterShape.name,
        releases = releases.map {
            CalendarReleaseDto(
                date = it.date.toString(),
                videoId = it.videoId,
                season = it.season,
                episode = it.episode,
                episodeTitle = it.episodeTitle,
                kind = it.kind?.name
            )
        },
        outcome = outcome.name,
        resolvedAtMs = resolvedAtMs,
        expiresAtMs = expiresAtMs,
        kindsResolved = releaseKindsResolved,
        anime = isAnime
    )

    private fun CalendarReleaseEntryDto.toDomain(): CalendarReleaseEntry? {
        if (v != SCHEMA_VERSION) return null
        val key = sourceKey?.takeIf { it.isNotBlank() } ?: return null
        val outcome = outcome?.let { runCatching { CalendarResolveOutcome.valueOf(it) }.getOrNull() } ?: return null
        val resolvedAt = resolvedAtMs ?: return null
        val expiresAt = expiresAtMs ?: return null
        return CalendarReleaseEntry(
            sourceKey = key,
            title = title.orEmpty(),
            poster = poster,
            posterShape = posterShape?.let { runCatching { PosterShape.valueOf(it) }.getOrNull() } ?: PosterShape.POSTER,
            releases = releases.orEmpty().mapNotNull { it.toDomain() },
            outcome = outcome,
            resolvedAtMs = resolvedAt,
            expiresAtMs = expiresAt,
            releaseKindsResolved = kindsResolved ?: false,
            isAnime = anime ?: false
        )
    }

    private fun CalendarReleaseDto.toDomain(): CalendarRelease? {
        val parsedDate = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return CalendarRelease(
            date = parsedDate,
            videoId = videoId,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
            kind = kind?.let { runCatching { CalendarReleaseKind.valueOf(it) }.getOrNull() }
        )
    }
}
