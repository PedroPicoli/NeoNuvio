package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.domain.model.CalendarSource
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Where a movie's theatrical/digital/physical dates come from; `null` means "could not ask". */
internal interface CalendarMovieReleaseKindsSource {
    /** False when the integration is switched off; nothing is fetched and entries are left as-is. */
    val isEnabled: Boolean

    /** Same as [isEnabled], as a stream, so the screen can react when the user flips the setting. */
    val enabled: Flow<Boolean>

    suspend fun fetch(source: CalendarSource): List<CalendarReleasePolicy.TmdbReleaseDate>?
}

/**
 * TMDB `movie/{id}/release_dates`, gated by the user's TMDB setting. Costs at most two requests
 * per movie per TTL: an id lookup (cached by [TmdbService]) and the release-dates call.
 */
@Singleton
internal class TmdbCalendarMovieReleaseKindsSource @Inject constructor(
    private val tmdbSettings: TmdbSettingsDataStore,
    private val tmdbService: TmdbService,
    private val tmdbApi: TmdbApi
) : CalendarMovieReleaseKindsSource {

    companion object {
        private const val TAG = "CalendarTmdb"
        private const val TMDB_ID_PREFIX = "tmdb:"
    }

    /** A build without a TMDB key cannot answer, so the feature is simply not offered. */
    private val hasApiKey: Boolean
        get() = tmdbService.apiKey().isNotBlank()

    override val isEnabled: Boolean
        get() = hasApiKey && tmdbSettings.settings.value.enabled

    override val enabled: Flow<Boolean> = tmdbSettings.settings.map { hasApiKey && it.enabled }.distinctUntilChanged()

    override suspend fun fetch(source: CalendarSource): List<CalendarReleasePolicy.TmdbReleaseDate>? {
        // The id lookup reports "unknown" and "request failed" the same way; treating both as
        // "could not ask" keeps the entry retryable (bounded by FAILED_TTL) instead of freezing it.
        val tmdbId = resolveTmdbId(source) ?: return null
        return try {
            val response = tmdbApi.getMovieReleaseDates(tmdbId, tmdbService.apiKey())
            if (!response.isSuccessful) return null
            response.body()?.results.orEmpty().flatMap { country ->
                val code = country.iso31661?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
                country.releaseDates.orEmpty().map { item ->
                    CalendarReleasePolicy.TmdbReleaseDate(
                        countryCode = code,
                        type = item.type,
                        date = item.releaseDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "release dates failed for ${source.key}: ${e.message}")
            null
        }
    }

    private suspend fun resolveTmdbId(source: CalendarSource): Int? {
        source.tmdbId?.let { return it }
        val rawId = source.id.trim()
        if (rawId.startsWith(TMDB_ID_PREFIX)) return rawId.removePrefix(TMDB_ID_PREFIX).substringBefore(':').toIntOrNull()
        if (rawId.startsWith("tt")) return tmdbService.imdbToTmdb(rawId.substringBefore(':'), "movie")
        return rawId.toIntOrNull()
    }
}
