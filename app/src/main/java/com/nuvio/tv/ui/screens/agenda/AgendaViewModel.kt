package com.nuvio.tv.ui.screens.agenda

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.simkl.SimklLibraryEntry
import com.nuvio.tv.data.simkl.SimklListStatus
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.data.simkl.idValue
import com.nuvio.tv.data.simkl.resolvedPosterUrl
import com.nuvio.tv.domain.repository.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nuvio.tv.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class AgendaItem(
    val id: String,
    val type: String,
    val title: String,
    val episodeInfo: String? = null,
    val episodeInfoRes: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseDateText: String = "",
    val daysLeftText: String = "",
    val daysLeftRes: Int? = null,
    val daysLeftArg: Int? = null,
    val heroBackdropUrl: String? = null,
    val daysLeftNumber: Long = 0L
)

data class AgendaSection(
    val title: String,
    val items: List<AgendaItem>,
    val titleRes: Int? = null
)

data class AgendaUiState(
    val isLoading: Boolean = true,
    val sections: List<AgendaSection> = emptyList(),
    val errorMessage: String? = null
)

private fun calculateDaysFromToday(dateStr: String?): Int? {
    if (dateStr.isNullOrBlank()) return null
    return try {
        val cleanDate = dateStr.trim().take(10)
        val parts = cleanDate.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null

        val calToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val calTarget = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffMs = calTarget.timeInMillis - calToday.timeInMillis
        (diffMs / (24 * 60 * 60 * 1000L)).toInt()
    } catch (_: Exception) {
        null
    }
}

private fun formatDateExtenso(dateStr: String?): String {
    if (dateStr.isNullOrBlank()) return ""
    return try {
        val cleanDate = dateStr.trim().take(10)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val date = sdf.parse(cleanDate) ?: return dateStr
        val outSdf = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault())
        outSdf.format(date)
    } catch (_: Exception) {
        dateStr ?: ""
    }
}

@HiltViewModel
class AgendaViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val simklSyncRepository: SimklSyncRepository,
    private val tmdbApi: TmdbApi,
    private val tmdbService: TmdbService
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgendaUiState())
    val uiState: StateFlow<AgendaUiState> = _uiState.asStateFlow()

    init {
        loadAgenda()
    }

    fun refresh() {
        loadAgenda()
    }

    private fun loadAgenda() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val snapshot = simklSyncRepository.state.value.snapshot
                val libraryEntries = libraryRepository.libraryItems.firstOrNull().orEmpty()

                val snapshotEntries: List<SimklLibraryEntry> = snapshot.entries
                val candidates = mutableListOf<Pair<String, SimklLibraryEntry>>()

                if (snapshotEntries.isNotEmpty()) {
                    snapshotEntries.forEach { entry ->
                        if (entry.status == SimklListStatus.DROPPED || entry.status == SimklListStatus.ON_HOLD) {
                            return@forEach
                        }
                        val media = entry.media ?: return@forEach
                        val rawId = media.ids.idValue("tmdb")
                            ?: media.ids.idValue("imdb")
                            ?: media.ids.idValue("simkl")
                            ?: return@forEach

                        candidates.add(rawId to entry)
                    }
                }

                val todayItems = mutableListOf<AgendaItem>()
                val tomorrowItems = mutableListOf<AgendaItem>()
                val thisWeekItems = mutableListOf<AgendaItem>()
                val upcomingItems = mutableListOf<AgendaItem>()
                val pendingDateItems = mutableListOf<AgendaItem>()

                val apiKey = BuildConfig.TMDB_API_KEY

                withContext(Dispatchers.IO) {
                    val deferredList = candidates.map { (rawId, entry) ->
                        async {
                            try {
                                val isMovie = entry.isMovieEntry()
                                val mediaTypeStr = if (isMovie) "movie" else "tv"
                                val media = entry.media ?: return@async null

                                val numericTmdbIdStr = tmdbService.ensureTmdbId(rawId, mediaTypeStr, media.ids.idValue("imdb"))
                                val numericTmdbId = numericTmdbIdStr?.toIntOrNull()

                                val title = media.title ?: return@async null
                                val poster = entry.resolvedPosterUrl()
                                    ?: media.ids.idValue("tmdb")?.let { "https://image.tmdb.org/t/p/w500/$it" }
                                    ?: entry.localPosterUrl

                                val id = "tmdb:${numericTmdbIdStr ?: rawId}"

                                if (!isMovie && numericTmdbId != null) {
                                    val tvDetailsResponse = tmdbApi.getTvDetails(numericTmdbId, apiKey, "pt-BR").body()
                                    val status = tvDetailsResponse?.status?.lowercase().orEmpty()

                                    // Exclude ended or canceled shows
                                    if (status.contains("ended") || status.contains("cancel")) {
                                        return@async null
                                    }

                                    val nextEp = tvDetailsResponse?.nextEpisodeToAir
                                    if (nextEp != null && !nextEp.airDate.isNullOrBlank()) {
                                        val days = calculateDaysFromToday(nextEp.airDate)
                                        if (days != null && days >= 0) {
                                            val epCode = if (nextEp.seasonNumber != null && nextEp.episodeNumber != null) {
                                                "T${nextEp.seasonNumber}E${nextEp.episodeNumber}"
                                            } else {
                                                ""
                                            }
                                            val epName = nextEp.name?.takeIf { it.isNotBlank() }.orEmpty()
                                            val infoText = if (epCode.isNotBlank() && epName.isNotBlank()) {
                                                "$epCode · $epName"
                                            } else epCode.ifBlank { epName }

                                            val (dRes, dArg) = when (days) {
                                                0 -> R.string.agenda_today to null
                                                1 -> R.string.agenda_tomorrow to null
                                                in 2..7 -> R.string.agenda_in_days to days
                                                else -> null to null
                                            }
                                            val fallbackDate = if (dRes == null) formatDateExtenso(nextEp.airDate) else ""

                                            return@async AgendaItem(
                                                id = id,
                                                type = "series",
                                                title = title,
                                                episodeInfo = infoText.ifBlank { null },
                                                episodeInfoRes = if (infoText.isBlank()) R.string.agenda_next_episode else null,
                                                posterUrl = poster,
                                                backdropUrl = poster,
                                                releaseDateText = fallbackDate,
                                                daysLeftText = fallbackDate,
                                                daysLeftRes = dRes,
                                                daysLeftArg = dArg,
                                                heroBackdropUrl = poster,
                                                daysLeftNumber = days.toLong()
                                            )
                                        }
                                    } else {
                                        // Returning series or in production without announced next episode date
                                        if ((status.contains("return") || status.contains("produc")) && !status.contains("ended") && !status.contains("cancel")) {
                                            return@async AgendaItem(
                                                id = id,
                                                type = "series",
                                                title = title,
                                                episodeInfoRes = R.string.agenda_no_date_announced,
                                                posterUrl = poster,
                                                backdropUrl = poster,
                                                daysLeftRes = R.string.agenda_waiting_date,
                                                heroBackdropUrl = poster,
                                                daysLeftNumber = 999L
                                            )
                                        }
                                    }
                                } else if (isMovie && numericTmdbId != null) {
                                    val langTag = Locale.getDefault().toLanguageTag()
                                    val movieDetails = tmdbApi.getMovieDetails(numericTmdbId, apiKey, langTag).body()
                                    val releaseDate = movieDetails?.releaseDate
                                    val days = calculateDaysFromToday(releaseDate)
                                    val movieStatus = movieDetails?.status?.lowercase().orEmpty()
                                    val isAlreadyReleased = (days != null && days < 0) || movieStatus == "released"

                                    if (days != null && days >= 0) {
                                        val (dRes, dArg) = when (days) {
                                            0 -> R.string.agenda_today to null
                                            1 -> R.string.agenda_tomorrow to null
                                            in 2..7 -> R.string.agenda_in_days to days
                                            else -> null to null
                                        }
                                        val fallbackDate = if (dRes == null) formatDateExtenso(releaseDate) else ""

                                        return@async AgendaItem(
                                            id = id,
                                            type = "movie",
                                            title = title,
                                            episodeInfoRes = R.string.agenda_movie_premiere,
                                            posterUrl = poster,
                                            backdropUrl = poster,
                                            releaseDateText = fallbackDate,
                                            daysLeftText = fallbackDate,
                                            daysLeftRes = dRes,
                                            daysLeftArg = dArg,
                                            heroBackdropUrl = poster,
                                            daysLeftNumber = days.toLong()
                                        )
                                    } else if (!isAlreadyReleased && (entry.status == SimklListStatus.PLAN_TO_WATCH || movieStatus.contains("produc") || movieStatus.contains("plan"))) {
                                        return@async AgendaItem(
                                            id = id,
                                            type = "movie",
                                            title = title,
                                            episodeInfoRes = R.string.agenda_movie_premiere,
                                            posterUrl = poster,
                                            backdropUrl = poster,
                                            daysLeftRes = R.string.agenda_waiting_date,
                                            heroBackdropUrl = poster,
                                            daysLeftNumber = 999L
                                        )
                                    }
                                }
                                null
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }

                    val results = deferredList.awaitAll().filterNotNull()

                    results.forEach { item ->
                        when {
                            item.daysLeftNumber == 0L -> todayItems.add(item)
                            item.daysLeftNumber == 1L -> tomorrowItems.add(item)
                            item.daysLeftNumber in 2L..7L -> thisWeekItems.add(item)
                            item.daysLeftNumber in 8L..365L -> upcomingItems.add(item)
                            else -> pendingDateItems.add(item)
                        }
                    }
                }

                val sections = mutableListOf<AgendaSection>()
                if (todayItems.isNotEmpty()) sections.add(AgendaSection("Today", todayItems, R.string.agenda_today))
                if (tomorrowItems.isNotEmpty()) sections.add(AgendaSection("Tomorrow", tomorrowItems, R.string.agenda_tomorrow))
                if (thisWeekItems.isNotEmpty()) sections.add(AgendaSection("This Week", thisWeekItems, R.string.agenda_this_week))
                if (upcomingItems.isNotEmpty()) sections.add(AgendaSection("Upcoming", upcomingItems, R.string.agenda_upcoming))
                if (pendingDateItems.isNotEmpty()) sections.add(AgendaSection("No Announced Date", pendingDateItems, R.string.agenda_no_date))

                _uiState.value = AgendaUiState(
                    isLoading = false,
                    sections = sections
                )
            } catch (e: Exception) {
                _uiState.value = AgendaUiState(
                    isLoading = false,
                    sections = emptyList(),
                    errorMessage = e.message
                )
            }
        }
    }
}
