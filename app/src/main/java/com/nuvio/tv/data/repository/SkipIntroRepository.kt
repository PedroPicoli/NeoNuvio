package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AnimeSkipSettingsDataStore
import com.nuvio.tv.data.remote.api.AniSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipApi
import com.nuvio.tv.data.remote.api.AnimeSkipRequest
import com.nuvio.tv.data.remote.api.IntroDbApi
import com.nuvio.tv.data.remote.api.IntroDbSegment
import com.nuvio.tv.data.remote.api.IntroDbSegmentsResponse
import com.nuvio.tv.data.remote.api.SkipDbApi
import com.nuvio.tv.data.remote.api.SkipDbResponse
import com.nuvio.tv.data.remote.api.SkipDbSegment
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull

data class SkipInterval(
    val startTime: Double, // seconds
    val endTime: Double,   // seconds
    val type: String,      // intro/op, recap, outro/ed, movie-credits, post-credits
    val provider: String   // "introdb", "aniskip", "animeskip"
)

internal fun IntroDbSegmentsResponse.toSkipIntervals(movie: Boolean): List<SkipInterval> {
    if (!movie) return listOfNotNull(
        intro.toSkipIntervalOrNull("intro"),
        recap.toSkipIntervalOrNull("recap"),
        outro.toSkipIntervalOrNull("outro"),
        postCredits.toSkipIntervalOrNull("post-credits")
    )
    val credits = outro.toSkipIntervalOrNull("movie-credits")
    val scene = postCredits.toSkipIntervalOrNull("post-credits")
    // Skipping credits must not also skip the post-credits scene.
    val safeCredits = if (credits != null && scene != null &&
        scene.startTime < credits.endTime && scene.endTime > credits.startTime
    ) {
        credits.copy(endTime = scene.startTime).takeIf { it.endTime > it.startTime }
    } else credits
    return listOfNotNull(safeCredits, scene)
}

private fun IntroDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startSec ?: startMs?.let { it / 1000.0 } ?: return null
    val end = endSec ?: endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(startTime = start, endTime = end, type = type, provider = "introdb")
}

internal fun SkipDbResponse.toSkipIntervals(movie: Boolean): List<SkipInterval> {
    val segs = segments ?: return emptyList()
    val introInterval = segs.intro.toSkipIntervalOrNull("intro")
    val recapInterval = segs.recap.toSkipIntervalOrNull("recap")
    val outroType = if (movie) "movie-credits" else "outro"
    val outroInterval = segs.outro.toSkipIntervalOrNull(outroType)
    val previewInterval = segs.preview.toSkipIntervalOrNull("preview")
    return listOfNotNull(introInterval, recapInterval, outroInterval, previewInterval)
}

private fun SkipDbSegment?.toSkipIntervalOrNull(type: String): SkipInterval? {
    if (this == null) return null
    val start = startMs?.let { it / 1000.0 } ?: return null
    val end = endMs?.let { it / 1000.0 } ?: return null
    if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start) return null
    return SkipInterval(startTime = start, endTime = end, type = type, provider = "skipdb")
}

@Singleton
class SkipIntroRepository @Inject constructor(
    private val introDbApi: IntroDbApi,
    private val skipDbApi: SkipDbApi,
    private val aniSkipApi: AniSkipApi,
    private val animeSkipApi: AnimeSkipApi,
    private val simklResolver: SimklIdResolver,
    private val animeSkipSettingsDataStore: AnimeSkipSettingsDataStore,
    private val tmdbService: TmdbService
) {
    private val cache = ConcurrentHashMap<String, List<SkipInterval>>()
    private val animeSkipShowIdCache = ConcurrentHashMap<String, String>()
    private val introDbConfigured = BuildConfig.INTRODB_API_URL.isNotEmpty()

    suspend fun getMovieSkipIntervals(contentId: String?, videoId: String? = null): List<SkipInterval> = coroutineScope {
        val ids = listOfNotNull(contentId, videoId).distinct()
        val imdbId = ids.firstNotNullOfOrNull { id ->
            Regex("""tt\d+""").find(id)?.value
        } ?: ids.firstNotNullOfOrNull { id ->
            val parts = id.split(':')
            val prefix = parts.getOrNull(0) ?: return@firstNotNullOfOrNull null
            val value = parts.getOrNull(1)
            when {
                prefix == "tmdb" && value != null -> value.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, "movie") }
                prefix == "simkl" -> {
                    val candidateId = parts.lastOrNull { part -> part.all { it.isDigit() } }
                    candidateId?.let { simklResolver.resolveIds("simkl", it, "movie")?.imdb }
                }
                (prefix == "mal" || prefix == "kitsu") && value != null -> simklResolver.resolveIds(prefix, value)?.imdb
                prefix.all { it.isDigit() } -> prefix.toIntOrNull()?.let { tmdbService.tmdbToImdb(it, "movie") }
                else -> null
            }
        } ?: return@coroutineScope emptyList()
        val key = "movie:$imdbId"
        cache[key]?.let { return@coroutineScope it }
        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(imdbId, isMovie = true) else emptyList()
        }
        val skipDbDeferred = async {
            fetchFromSkipDb(imdbId, isMovie = true)
        }
        mergeByPriority(
            introDbDeferred.await(),
            skipDbDeferred.await()
        ).also { cache[key] = it }
    }

    /**
     * Resolves IDs from contentId, videoId, TMDB, Simkl or fallback and fetches intervals.
     */
    suspend fun getSeriesSkipIntervals(
        contentId: String?,
        videoId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        fallbackImdbId: String? = null
    ): List<SkipInterval> {
        val ids = listOfNotNull(videoId, contentId).distinct()

        var resolvedSeason = season
        var resolvedEpisode = episode
        if (resolvedSeason == null || resolvedEpisode == null) {
            for (idStr in ids) {
                val parts = idStr.split(':')
                if (parts.size >= 3) {
                    val candidateEpisode = parts.last().toIntOrNull()
                    val candidateSeason = parts[parts.size - 2].toIntOrNull()
                    if (candidateSeason != null && candidateEpisode != null) {
                        resolvedSeason = resolvedSeason ?: candidateSeason
                        resolvedEpisode = resolvedEpisode ?: candidateEpisode
                        break
                    }
                }
                val seMatch = Regex("""(?i)[sS](\d+)[eE](\d+)""").find(idStr)
                if (seMatch != null) {
                    resolvedSeason = resolvedSeason ?: seMatch.groupValues[1].toIntOrNull()
                    resolvedEpisode = resolvedEpisode ?: seMatch.groupValues[2].toIntOrNull()
                    if (resolvedSeason != null && resolvedEpisode != null) break
                }
            }
        }
        val s = resolvedSeason ?: return emptyList()
        val e = resolvedEpisode ?: return emptyList()

        val imdbId = fallbackImdbId?.takeIf { it.startsWith("tt") }
            ?: ids.firstNotNullOfOrNull { idStr ->
                Regex("""tt\d+""").find(idStr)?.value
            } ?: ids.firstNotNullOfOrNull { idStr ->
                val parts = idStr.split(':')
                val prefix = parts.getOrNull(0) ?: return@firstNotNullOfOrNull null
                val value = parts.getOrNull(1)
                when {
                    prefix == "tmdb" && value != null -> value.toIntOrNull()?.let { tmdbId ->
                        tmdbService.tmdbToImdb(tmdbId, "series") ?: tmdbService.tmdbToImdb(tmdbId, "tv")
                    }
                    prefix == "simkl" -> {
                        val candidateId = parts.lastOrNull { part -> part.all { it.isDigit() } }
                        candidateId?.let { simklResolver.resolveIds("simkl", it, "tv")?.imdb }
                    }
                    (prefix == "mal" || prefix == "kitsu") && value != null -> simklResolver.resolveIds(prefix, value)?.imdb
                    prefix.all { it.isDigit() } -> prefix.toIntOrNull()?.let { tmdbId ->
                        tmdbService.tmdbToImdb(tmdbId, "series") ?: tmdbService.tmdbToImdb(tmdbId, "tv")
                    }
                    else -> null
                }
            } ?: run {
                val rawId = contentId ?: videoId
                if (!rawId.isNullOrBlank()) {
                    runCatching {
                        val tmdbId = tmdbService.ensureTmdbId(rawId, "tv")
                            ?: tmdbService.ensureTmdbId(rawId, "series")
                        tmdbId?.toIntOrNull()?.let {
                            tmdbService.tmdbToImdb(it, "series") ?: tmdbService.tmdbToImdb(it, "tv")
                        }
                    }.getOrNull()
                } else null
            } ?: return emptyList()

        return getSkipIntervals(imdbId, s, e)
    }

    /**
     * Standard path for IMDB-identified content.
     */
    suspend fun getSkipIntervals(imdbId: String?, season: Int, episode: Int): List<SkipInterval> = coroutineScope {
        if (imdbId == null) return@coroutineScope emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val introDbDeferred = async {
            if (introDbConfigured) fetchFromIntroDb(imdbId, season, episode) else emptyList()
        }
        val skipDbDeferred = async {
            fetchFromSkipDb(imdbId, season, episode, isMovie = false)
        }
        // Resolve IMDB -> season-specific MAL/AniList via Simkl episode mapping
        val simklIdsDeferred = async { simklResolver.resolveIdsForImdbEpisode(imdbId, season, episode) }
        val simklIds = simklIdsDeferred.await()
        val malId = simklIds?.mal
        val anilistId = simklIds?.anilist

        // Remap the TVDB episode number to the anime-entry-local episode number.
        // When the resolved entry owns a specific TVDB season, its episode mapping
        // tells us which anime episode corresponds to the requested TVDB episode.
        val animeEpisode = if (simklIds != null) {
            val mapping = simklResolver.getEpisodeMapping(simklIds.simklId, simklIds.type)
            mapping.firstOrNull { it.tvdbSeason == season && it.tvdbEpisode == episode }
                ?.animeEpisode
                ?: episode
        } else episode

        val aniSkipDeferred = async {
            if (malId != null) fetchFromAniSkip(malId, animeEpisode) else emptyList()
        }
        val animeSkipDeferred = async {
            if (anilistId != null) fetchFromAnimeSkip(anilistId, animeEpisode, season = null) else emptyList()
        }

        return@coroutineScope mergeByPriority(
            introDbDeferred.await(),
            skipDbDeferred.await(),
            animeSkipDeferred.await(),
            aniSkipDeferred.await()
        ).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForMal(
        malId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null
    ): List<SkipInterval> = coroutineScope {
        val cacheKey = "mal:$malId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        val aniSkipDeferred = async { fetchFromAniSkip(malId, episode) }

        val simklIdsDeferred = async { simklResolver.resolveIds("mal", malId) }
        val simklIds = simklIdsDeferred.await()
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("mal", malId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val skipDbDeferred = async {
                fetchFromSkipDb(resolvedImdbId, introDbSeason, introDbEpisode, isMovie = false)
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = mergeByPriority(introDbDeferred.await(), skipDbDeferred.await())
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    suspend fun getSkipIntervalsForKitsu(
        kitsuId: String,
        episode: Int,
        imdbId: String? = null,
        imdbSeason: Int? = null,
        imdbEpisode: Int? = null
    ): List<SkipInterval> = coroutineScope {
        val cacheKey = "kitsu:$kitsuId:$episode"
        cache[cacheKey]?.let { return@coroutineScope it }

        // Resolve all IDs via Simkl
        val simklIdsDeferred = async { simklResolver.resolveIds("kitsu", kitsuId) }
        val simklIds = simklIdsDeferred.await()
        val malIdStr = simklIds?.mal
        val resolvedImdbId = imdbId ?: simklIds?.imdb

        val aniSkipDeferred = async {
            if (malIdStr != null) fetchFromAniSkip(malIdStr, episode) else emptyList()
        }

        val tvdbDeferred = async {
            if (resolvedImdbId != null && imdbSeason == null && simklIds != null) {
                simklResolver.resolveEpisodeTvdb("kitsu", kitsuId, episode)
            } else null
        }

        var introDb = emptyList<SkipInterval>()
        var animeSkip = emptyList<SkipInterval>()
        if (resolvedImdbId != null) {
            val tvdb = tvdbDeferred.await()
            val introDbSeason = imdbSeason ?: tvdb?.first ?: return@coroutineScope run {
                mergeByPriority(animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
            }
            val introDbEpisode = imdbEpisode ?: tvdb?.second ?: episode
            val introDbDeferred = async {
                if (introDbConfigured) fetchFromIntroDb(resolvedImdbId, introDbSeason, introDbEpisode) else emptyList()
            }
            val skipDbDeferred = async {
                fetchFromSkipDb(resolvedImdbId, introDbSeason, introDbEpisode, isMovie = false)
            }
            val anilistId = simklIds?.anilist
            val animeSkipDeferred = async {
                if (anilistId != null) fetchFromAnimeSkip(anilistId, episode, season = null) else emptyList()
            }
            introDb = mergeByPriority(introDbDeferred.await(), skipDbDeferred.await())
            animeSkip = animeSkipDeferred.await()
        } else {
            val anilistId = simklIds?.anilist
            if (anilistId != null) animeSkip = fetchFromAnimeSkip(anilistId, episode, season = null)
        }

        return@coroutineScope mergeByPriority(introDb, animeSkip, aniSkipDeferred.await()).also { cache[cacheKey] = it }
    }

    /**
     * Merge provider results into one best-of: fill each segment category (opening / ending /
     * recap) from the highest-priority provider that has it. Arguments MUST be passed in priority
     * order (IntroDB, then Anime-Skip, then AniSkip as fallback),
     * so a partial result from one provider never shadows a complete segment from another.
     */
    private fun mergeByPriority(vararg providerResults: List<SkipInterval>): List<SkipInterval> {
        val chosen = LinkedHashMap<String, SkipInterval>()
        for (result in providerResults) {
            for (interval in result) {
                val category = segmentCategory(interval.type) ?: continue
                chosen.putIfAbsent(category, interval)
            }
        }
        return chosen.values.toList()
    }

    private fun segmentCategory(type: String): String? = when (type.lowercase()) {
        "intro", "op", "mixed-op" -> "opening"
        "outro", "ed", "mixed-ed", "credits", "ending", "movie-credits" -> "ending"
        "recap" -> "recap"
        "post-credits", "post_credits" -> "post-credits"
        "preview" -> "preview"
        else -> null
    }

    private suspend fun fetchFromSkipDb(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = false
    ): List<SkipInterval> {
        return try {
            val response = if (isMovie) {
                skipDbApi.getSegments(imdbId = imdbId)
            } else {
                skipDbApi.getSegments(imdbId = imdbId, season = season, episode = episode)
            }
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.toSkipIntervals(isMovie)
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "SkipDB: no data for $imdbId S${season}E${episode}")
            emptyList()
        }
    }

    private suspend fun fetchFromIntroDb(
        imdbId: String,
        season: Int? = null,
        episode: Int? = null,
        isMovie: Boolean = false
    ): List<SkipInterval> {
        return try {
            val response = introDbApi.getSegments(imdbId, season, episode, true.takeIf { isMovie })
            if (response.isSuccessful && response.body() != null) {
                response.body()!!.toSkipIntervals(isMovie)
            } else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("SkipIntro", "IntroDB: no data for $imdbId S${season}E${episode}")
            emptyList()
        }
    }

    private suspend fun fetchFromAniSkip(malId: String, episode: Int): List<SkipInterval> {
        return try {
            val types = listOf("op", "ed", "recap", "mixed-op", "mixed-ed")
            val response = aniSkipApi.getSkipTimes(malId, episode, types)
            if (response.isSuccessful && response.body()?.found == true) {
                response.body()!!.results?.map { result ->
                    SkipInterval(
                        startTime = result.interval.startTime,
                        endTime = result.interval.endTime,
                        type = result.skipType,
                        provider = "aniskip"
                    )
                } ?: emptyList()
            } else emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AniSkip: no data for MAL $malId ep $episode")
            emptyList()
        }
    }

    // season: null when anilistId is season-specific; pass season number when using fallback ID
    private suspend fun fetchFromAnimeSkip(anilistId: String, episode: Int, season: Int?): List<SkipInterval> {
        val clientId = animeSkipSettingsDataStore.clientId.firstOrNull()?.trim()
        if (clientId.isNullOrBlank()) return emptyList()
        val enabled = animeSkipSettingsDataStore.enabled.firstOrNull() ?: false
        if (!enabled) return emptyList()
        return try {
            val showIds = resolveAnimeSkipShowIds(anilistId, clientId)
            if (showIds.isEmpty()) return emptyList()

            for (showId in showIds) {
                val episodesResponse = animeSkipApi.query(
                    clientId = clientId,
                    body = AnimeSkipRequest(
                        query = "{ findEpisodesByShowId(showId: \"$showId\") { season number timestamps { at type { name } } } }"
                    )
                )
                if (!episodesResponse.isSuccessful) continue

                val episodes = episodesResponse.body()?.data?.findEpisodesByShowId ?: continue
                val targetEpisode = episodes.firstOrNull { ep ->
                    ep.number?.toIntOrNull() == episode &&
                        (season == null || ep.season?.toIntOrNull() == season)
                } ?: continue

                val sorted = (targetEpisode.timestamps ?: continue).sortedBy { it.at }
                val result = sorted.mapIndexedNotNull { i, ts ->
                    val endTime = sorted.getOrNull(i + 1)?.at ?: Double.MAX_VALUE
                    val type = when (ts.type.name.lowercase()) {
                        "intro", "new intro" -> "op"
                        "credits", "new credits" -> "ed"
                        "mixed intro" -> "mixed-op"
                        "mixed credits" -> "mixed-ed"
                        "recap" -> "recap"
                        else -> return@mapIndexedNotNull null
                    }
                    SkipInterval(startTime = ts.at, endTime = endTime, type = type, provider = "animeskip")
                }
                if (result.isNotEmpty()) return result
            }
            emptyList()
        } catch (e: Exception) {
            Log.d("SkipIntro", "AnimeSkip: error for anilist $anilistId ep $episode: ${e.message}")
            emptyList()
        }
    }

    private suspend fun resolveAnimeSkipShowIds(anilistId: String, clientId: String): List<String> {
        animeSkipShowIdCache[anilistId]?.let { cached ->
            return if (cached == NO_ID) emptyList() else listOf(cached)
        }
        val showIds = try {
            animeSkipApi.query(
                clientId = clientId,
                body = AnimeSkipRequest(
                    query = "{ findShowsByExternalId(service: ANILIST, serviceId: \"$anilistId\") { id } }"
                )
            ).body()?.data?.findShowsByExternalId?.map { it.id } ?: emptyList()
        } catch (e: Exception) { emptyList() }
        if (showIds.size == 1) animeSkipShowIdCache[anilistId] = showIds[0]
        else if (showIds.isEmpty()) animeSkipShowIdCache[anilistId] = NO_ID
        return showIds
    }

    companion object {
        private const val NO_ID = "__none__"
    }
}
