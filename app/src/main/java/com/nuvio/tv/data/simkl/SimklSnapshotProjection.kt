package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem

internal class SimklSnapshotProjection private constructor(
    val library: SimklLibraryProjection,
    val watched: SimklWatchedProjection,
    val progress: List<WatchProgress>,
    val watchedMovieIds: Set<String>,
    val completedSeriesIds: Set<String>,
    val incompleteSeriesIds: Set<String>,
    val droppedOrHoldContentIds: Set<String>,
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>>,
    val showIdSiblings: Map<String, Set<String>>,
    private val recentNextUp: List<WatchProgress>,
    private val furthestNextUp: List<WatchProgress>,
    private val canonicalIdByAlias: Map<String, String>,
    private val watchedItemsByContent: Map<String, List<WatchedItem>>,
    private val watchedKeys: Set<SimklProjectionItemKey>,
    private val playbackByEpisode: Map<SimklProjectionItemKey, WatchProgress>,
    private val hiddenContentIds: Set<String>,
    private val watchedAnimeEpisodes: Map<String, Set<Int>>,
    private val membershipByContent: Map<SimklProjectionMembershipKey, SimklProjectionMembership>,
    val ratingsByContentId: Map<String, Int>
) {
    fun nextUp(preferFurthestEpisode: Boolean): List<WatchProgress> =
        if (preferFurthestEpisode) furthestNextUp else recentNextUp

    fun resolveShowMatchKeys(contentId: String): Set<String> = buildSet {
        val key = contentId.simklLookupKey()
        val rawKey = key.substringAfter(':')
        val pureId = key.substringAfterLast(':')
        add(key)
        if (rawKey.isNotEmpty()) add(rawKey)
        if (pureId.isNotEmpty()) add(pureId)

        canonicalIdByAlias[key]?.let { add(it.simklLookupKey()) }
        canonicalIdByAlias[rawKey]?.let { add(it.simklLookupKey()) }
        canonicalIdByAlias[pureId]?.let { add(it.simklLookupKey()) }

        showIdSiblings[key]?.forEach { add(it.simklLookupKey()) }
        showIdSiblings[rawKey]?.forEach { add(it.simklLookupKey()) }
        showIdSiblings[pureId]?.forEach { add(it.simklLookupKey()) }

        // Dynamic cross-resolution between IMDb <-> TMDB via TmdbService global cache
        val imdbCandidate = when {
            key.startsWith("tt") -> key
            rawKey.startsWith("tt") -> rawKey
            pureId.startsWith("tt") -> pureId
            else -> null
        }
        if (imdbCandidate != null) {
            TmdbService.cachedTmdbIdGlobal(imdbCandidate)?.let { tmdbId ->
                val tStr = tmdbId.toString()
                add(tStr)
                add("tmdb:$tStr")
                add("series:tmdb:$tStr")
                add("series:$tStr")
                add("tv:$tStr")
                canonicalIdByAlias[tStr]?.let { add(it.simklLookupKey()) }
                canonicalIdByAlias["tmdb:$tStr"]?.let { add(it.simklLookupKey()) }
                showIdSiblings[tStr]?.forEach { add(it.simklLookupKey()) }
                showIdSiblings["tmdb:$tStr"]?.forEach { add(it.simklLookupKey()) }
            }
        }

        val tmdbCandidate = when {
            key.all { it.isDigit() } -> key.toIntOrNull()
            rawKey.all { it.isDigit() } -> rawKey.toIntOrNull()
            pureId.all { it.isDigit() } -> pureId.toIntOrNull()
            else -> null
        }
        if (tmdbCandidate != null) {
            TmdbService.cachedImdbIdGlobal(tmdbCandidate)?.let { imdbId ->
                val iStr = imdbId.lowercase()
                add(iStr)
                add("imdb:$iStr")
                add("series:imdb:$iStr")
                add("series:$iStr")
                canonicalIdByAlias[iStr]?.let { add(it.simklLookupKey()) }
                canonicalIdByAlias["imdb:$iStr"]?.let { add(it.simklLookupKey()) }
                showIdSiblings[iStr]?.forEach { add(it.simklLookupKey()) }
                showIdSiblings["imdb:$iStr"]?.forEach { add(it.simklLookupKey()) }
            }
        }
    }

    fun episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> {
        val matchKeys = resolveShowMatchKeys(contentId)
        val completed = linkedMapOf<Pair<Int, Int>, WatchProgress>()
        matchKeys.forEach { matchKey ->
            watchedItemsByContent[matchKey].orEmpty().forEach { item ->
                val season = item.season ?: return@forEach
                val episode = item.episode ?: return@forEach
                completed[season to episode] = item.toSimklCompletedProgress()
            }
        }
        matchKeys.forEach { matchKey ->
            watchedShowEpisodes[matchKey].orEmpty().forEach { (season, episode) ->
                completed.putIfAbsent(
                    season to episode,
                    WatchProgress(
                        contentId = contentId,
                        contentType = "series",
                        name = "",
                        poster = null,
                        backdrop = null,
                        logo = null,
                        videoId = "$contentId:$season:$episode",
                        season = season,
                        episode = episode,
                        episodeTitle = null,
                        position = 1L,
                        duration = 1L,
                        lastWatched = 1L,
                        progressPercent = 100f,
                        source = WatchProgress.SOURCE_SIMKL_PLAYBACK
                    )
                )
            }
        }
        progress.filter { item ->
            item.contentId.simklLookupKey() in matchKeys && item.season != null && item.episode != null
        }.forEach { item ->
            completed[requireNotNull(item.season) to requireNotNull(item.episode)] = item
        }
        return completed
    }

    fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Boolean {
        if (contentId.matchesWatchedId(droppedOrHoldContentIds)) return false
        if (videoId != null && videoId.matchesWatchedId(droppedOrHoldContentIds)) return false

        val directKey = SimklProjectionItemKey(contentId.simklLookupKey(), season, episode)
        val playback = playbackByEpisode[directKey]
            ?: canonicalIdByAlias[directKey.contentId]?.let { resolvedId ->
                playbackByEpisode[SimklProjectionItemKey(resolvedId.simklLookupKey(), season, episode)]
            }
        if (playback != null && playback.progressPercentage > 0f && !playback.isCompleted()) return false

        if (season == null && episode == null) {
            if (contentId.matchesWatchedId(watchedMovieIds) || contentId.matchesWatchedId(completedSeriesIds)) {
                return true
            }
            if (videoId != null && (videoId.matchesWatchedId(watchedMovieIds) || videoId.matchesWatchedId(completedSeriesIds))) {
                return true
            }
        }

        if (season != null && episode != null) {
            val matchKeys = resolveShowMatchKeys(contentId)
            val hasEpisodeData = matchKeys.any { mk -> watchedShowEpisodes.containsKey(mk) }
            if (hasEpisodeData) {
                for (mk in matchKeys) {
                    if (watchedShowEpisodes[mk]?.contains(season to episode) == true) return true
                    if (SimklProjectionItemKey(mk, season, episode) in watchedKeys) return true
                }
                return false
            }
            if (contentId.matchesWatchedId(completedSeriesIds)) return true
        }

        if (directKey in watchedKeys) return true
        val resolvedId = canonicalIdByAlias[directKey.contentId]
        if (resolvedId != null) {
            val resolvedKey = SimklProjectionItemKey(resolvedId.simklLookupKey(), season, episode)
            if (resolvedKey in watchedKeys) return true
        }
        return videoId != null && episode != null && isWatchedByVideoId(videoId, episode)
    }

    fun isHidden(contentId: String): Boolean = contentId.simklLookupKey() in hiddenContentIds

    fun isWatchedByVideoId(videoId: String, episode: Int): Boolean {
        val parsed = parseSimklProjectionVideoId(videoId) ?: return false
        return (parsed.episode ?: episode) in watchedAnimeEpisodes[parsed.lookupKey].orEmpty()
    }

    fun membershipKey(contentId: String, contentType: String): String? =
        membershipByContent[
            SimklProjectionMembershipKey(contentId.simklLookupKey(), contentType.simklLookupKey())
        ]?.listKey

    fun userRating(contentId: String): Int? {
        val key = contentId.simklLookupKey()
        val resolvedKey = canonicalIdByAlias[key]?.simklLookupKey()
        return ratingsByContentId[key] ?: resolvedKey?.let { ratingsByContentId[it] }
    }

    companion object {
        val Empty = create(SimklSyncSnapshot())

        fun create(snapshot: SimklSyncSnapshot): SimklSnapshotProjection {
            val library = snapshot.toSimklLibraryProjection()
            val watched = snapshot.toSimklWatchedProjection()
            val progress = snapshot.toSimklProgressEntries()
            val canonicalIdByAlias = linkedMapOf<String, String>()
            val siblings = linkedMapOf<String, MutableSet<String>>()
            val hiddenContentIds = linkedSetOf<String>()
            val membershipByContent = linkedMapOf<SimklProjectionMembershipKey, SimklProjectionMembership>()
            val watchedAnimeEpisodes = linkedMapOf<String, Set<Int>>()
            val ratingsByContentId = linkedMapOf<String, Int>()

            snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val imdb = media.ids.idValue("imdb")
                val tmdb = media.ids.idValue("tmdb")?.toIntOrNull()
                if (imdb != null && tmdb != null) {
                    TmdbService.get()?.preCacheMapping(imdb, tmdb, if (entry.isMovieEntry()) "movie" else "series")
                }
                val ids = media.simklTrackingContentIds()
                val canonicalId = media.canonicalContentId()
                ids.forEach { id ->
                    canonicalId?.let { canonicalIdByAlias.putIfAbsent(id.simklLookupKey(), it) }
                    siblings.getOrPut(id) { linkedSetOf() }.addAll(ids - id)
                }
                val userRating = entry.userRating
                if (userRating != null && userRating in 1..10) {
                    ids.forEach { id ->
                        ratingsByContentId.putIfAbsent(id.simklLookupKey(), userRating)
                    }
                }
                if (entry.status.hidesContinueWatching()) {
                    ids.mapTo(hiddenContentIds, String::simklLookupKey)
                }
                val listKey = entry.status?.let { status ->
                    simklLibraryStatusDefinitions.firstOrNull { definition -> definition.status == status }?.key
                }
                val membershipType = if (entry.mediaType == SimklMediaType.MOVIES) "movie" else "series"
                ids.forEach { id ->
                    membershipByContent.putIfAbsent(
                        SimklProjectionMembershipKey(id.simklLookupKey(), membershipType),
                        SimklProjectionMembership(listKey)
                    )
                }
                if (entry.mediaType == SimklMediaType.ANIME) {
                    val watchedEpisodes = entry.seasons.asSequence()
                        .flatMap { season -> season.episodes.asSequence() }
                        .filter { episode -> episode.watchedAt != null }
                        .mapNotNull(SimklEpisode::number)
                        .toSet()
                    SIMKL_PROJECTION_ANIME_IDS.forEach { prefix ->
                        media.ids.idValue(prefix)?.toLongOrNull()?.let { id ->
                            watchedAnimeEpisodes.putIfAbsent("$prefix:$id", watchedEpisodes)
                        }
                    }
                }
            }

            val aliasesByCanonicalId = linkedMapOf<String, Set<String>>()
            snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val canonical = media.canonicalContentId() ?: return@forEach
                val allAliases = media.simklTrackingContentIds().map { it.simklLookupKey() }.toSet()
                aliasesByCanonicalId[canonical.simklLookupKey()] = allAliases
            }

            val watchedKeys = linkedSetOf<SimklProjectionItemKey>()
            val watchedItemsByContent = linkedMapOf<String, MutableList<WatchedItem>>()
            val watchedShowEpisodes = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()

            watched.items.filter { it.season != null && it.episode != null }.forEach { item ->
                val canonicalKey = item.contentId.simklLookupKey()
                val aliases = aliasesByCanonicalId[canonicalKey] ?: setOf(canonicalKey)
                val epPair = requireNotNull(item.season) to requireNotNull(item.episode)

                aliases.forEach { alias ->
                    watchedItemsByContent.getOrPut(alias) { mutableListOf() }.add(item)
                    watchedShowEpisodes.getOrPut(alias) { linkedSetOf() }.add(epPair)
                    watchedKeys.add(SimklProjectionItemKey(alias, item.season, item.episode))
                }
            }

            snapshot.entries.forEach { entry ->
                val media = entry.media ?: return@forEach
                val aliases = media.simklTrackingContentIds().map { it.simklLookupKey() }
                entry.seasons.forEach { season ->
                    val sNum = season.seasonNumber
                    season.episodes.forEach { episode ->
                        if (!episode.watchedAt.isNullOrBlank()) {
                            val pairs = mutableListOf<Pair<Int, Int>>()
                            val eNum = episode.episodeNumber
                            val stdS = sNum ?: episode.season
                            if (stdS != null && eNum != null) pairs.add(stdS to eNum)
                            val tvdbS = episode.tvdb?.season
                            val tvdbE = episode.tvdb?.episode
                            if (tvdbS != null && tvdbE != null && (tvdbS != stdS || tvdbE != eNum)) {
                                pairs.add(tvdbS to tvdbE)
                            }

                            aliases.forEach { alias ->
                                pairs.forEach { (s, e) ->
                                    watchedShowEpisodes.getOrPut(alias) { linkedSetOf() }.add(s to e)
                                    watchedKeys.add(SimklProjectionItemKey(alias, s, e))
                                }
                            }
                        }
                    }
                }
            }

            val playbackByEpisode = linkedMapOf<SimklProjectionItemKey, WatchProgress>()
            progress.forEach { item ->
                val canonicalKey = item.contentId.simklLookupKey()
                val aliases = aliasesByCanonicalId[canonicalKey] ?: setOf(canonicalKey)
                aliases.forEach { alias ->
                    playbackByEpisode.putIfAbsent(
                        SimklProjectionItemKey(alias, item.season, item.episode),
                        item
                    )
                }
            }

            val nextUpCandidates = watched.items.asSequence()
                .filterNot { item -> item.contentId.simklLookupKey() in hiddenContentIds }
                .filter { item -> item.season != null && item.episode != null && item.season != 0 }
                .map(WatchedItem::toSimklCompletedProgress)
                .toList()

            return SimklSnapshotProjection(
                library = library,
                watched = watched,
                progress = progress,
                watchedMovieIds = snapshot.simklWatchedMovieIds(progress),
                completedSeriesIds = snapshot.simklCompletedSeriesIds(),
                incompleteSeriesIds = snapshot.simklIncompleteSeriesIds(),
                droppedOrHoldContentIds = snapshot.simklDroppedOrHoldIds(),
                watchedShowEpisodes = watchedShowEpisodes.mapValues { (_, eps) -> eps.toSet() },
                showIdSiblings = siblings.mapValues { (_, ids) -> ids.toSet() },
                recentNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, false),
                furthestNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, true),
                canonicalIdByAlias = canonicalIdByAlias,
                watchedItemsByContent = watchedItemsByContent,
                watchedKeys = watchedKeys,
                playbackByEpisode = playbackByEpisode,
                hiddenContentIds = hiddenContentIds,
                watchedAnimeEpisodes = watchedAnimeEpisodes,
                membershipByContent = membershipByContent,
                ratingsByContentId = ratingsByContentId
            )
        }
    }
}

internal fun SimklSyncSnapshot.toSimklNextUpSeeds(
    preferFurthestEpisode: Boolean
): List<WatchProgress> = SimklSnapshotProjection.create(this).nextUp(preferFurthestEpisode)

private fun SimklSyncSnapshot.simklWatchedMovieIds(progress: List<WatchProgress>): Set<String> {
    val watched = linkedSetOf<String>()
    entries.forEach { entry ->
        if (!entry.isMovieEntry()) return@forEach
        if (entry.status == SimklListStatus.DROPPED || entry.status == SimklListStatus.ON_HOLD) return@forEach
        val isWatched = entry.status == SimklListStatus.COMPLETED ||
            !entry.lastWatchedAt.isNullOrBlank() ||
            !entry.lastWatched.isNullOrBlank() ||
            entry.userRating != null ||
            !entry.userRatedAt.isNullOrBlank() ||
            (entry.mediaType == SimklMediaType.MOVIES && entry.status != SimklListStatus.PLAN_TO_WATCH)
        if (!isWatched) return@forEach
        val media = entry.media ?: return@forEach
        val trackingIds = media.simklTrackingContentIds()
        val hasActivePlayback = progress.any { prog ->
            (prog.contentType == "movie" || prog.contentType.isBlank()) &&
                prog.progressPercentage > 0f &&
                !prog.isCompleted() &&
                (trackingIds.any { id -> id.simklLookupKey() == prog.contentId.simklLookupKey() } ||
                    entry.matchesSimklContentId(prog.contentId))
        }
        if (hasActivePlayback) return@forEach
        trackingIds.forEach { id ->
            watched.add(id)
            watched.add(id.lowercase())
            val raw = id.substringAfter(':')
            if (raw.isNotEmpty()) {
                watched.add(raw)
                watched.add(raw.lowercase())
                watched.add("movie:$raw")
                watched.add("movie:${raw.lowercase()}")
                watched.add("movie:tmdb:$raw")
                watched.add("movie:imdb:$raw")
            }
        }
    }
    return watched
}

private fun SimklSyncSnapshot.simklCompletedSeriesIds(): Set<String> {
    val completed = linkedSetOf<String>()
    entries.forEach { entry ->
        if (entry.isMovieEntry()) return@forEach
        if (entry.status == SimklListStatus.DROPPED || entry.status == SimklListStatus.ON_HOLD || entry.status == SimklListStatus.PLAN_TO_WATCH) return@forEach
        val airedCount = entry.totalEpisodesCount - entry.notAiredEpisodesCount
        val isCompleted = entry.status == SimklListStatus.COMPLETED ||
            (entry.status != SimklListStatus.WATCHING &&
                ((entry.totalEpisodesCount > 0 && airedCount > 0 && entry.watchedEpisodesCount >= airedCount) ||
                 (entry.totalEpisodesCount > 0 && entry.watchedEpisodesCount >= entry.totalEpisodesCount)))
        if (!isCompleted) return@forEach
        val media = entry.media ?: return@forEach
        media.simklTrackingContentIds().forEach { id ->
            completed.add(id)
            completed.add(id.lowercase())
            val raw = id.substringAfter(':')
            if (raw.isNotEmpty()) {
                completed.add(raw)
                completed.add(raw.lowercase())
                completed.add("series:$raw")
                completed.add("series:${raw.lowercase()}")
                completed.add("tv:$raw")
                completed.add("tv:${raw.lowercase()}")
            }
        }
    }
    return completed
}

internal fun SimklSyncSnapshot.simklIncompleteSeriesIds(): Set<String> {
    val incomplete = linkedSetOf<String>()
    val completed = simklCompletedSeriesIds()
    entries.forEach { entry ->
        if (entry.isMovieEntry()) return@forEach
        val media = entry.media ?: return@forEach
        val ids = media.simklTrackingContentIds()
        if (ids.none { it in completed }) {
            ids.forEach { id ->
                incomplete.add(id)
                incomplete.add(id.lowercase())
                val raw = id.substringAfter(':')
                if (raw.isNotEmpty()) {
                    incomplete.add(raw)
                    incomplete.add(raw.lowercase())
                    incomplete.add("series:$raw")
                    incomplete.add("series:${raw.lowercase()}")
                    incomplete.add("tv:$raw")
                    incomplete.add("tv:${raw.lowercase()}")
                }
            }
        }
    }
    return incomplete
}

internal fun SimklSyncSnapshot.simklDroppedOrHoldIds(): Set<String> {
    val droppedOrHold = linkedSetOf<String>()
    entries.forEach { entry ->
        if (entry.status != SimklListStatus.DROPPED && entry.status != SimklListStatus.ON_HOLD) return@forEach
        val media = entry.media ?: return@forEach
        media.simklTrackingContentIds().forEach { id ->
            droppedOrHold.add(id)
            droppedOrHold.add(id.lowercase())
            val raw = id.substringAfter(':')
            if (raw.isNotEmpty()) {
                droppedOrHold.add(raw)
                droppedOrHold.add(raw.lowercase())
                droppedOrHold.add("movie:$raw")
                droppedOrHold.add("movie:${raw.lowercase()}")
                droppedOrHold.add("series:$raw")
                droppedOrHold.add("series:${raw.lowercase()}")
            }
        }
    }
    return droppedOrHold
}

internal fun String.matchesWatchedId(ids: Set<String>): Boolean {
    val clean = trim()
    if (clean.isEmpty()) return false
    if (clean in ids || clean.lowercase() in ids) return true
    val withoutPrefix = clean.substringAfter(':')
    if (withoutPrefix.isNotEmpty() && (withoutPrefix in ids || withoutPrefix.lowercase() in ids)) return true
    val pureId = clean.substringAfterLast(':')
    if (pureId.isNotEmpty() && (pureId in ids || pureId.lowercase() in ids)) return true
    if ("tmdb:$clean" in ids || "tvdb:$clean" in ids || "simkl:$clean" in ids || "imdb:$clean" in ids || "movie:$clean" in ids || "series:$clean" in ids || "tv:$clean" in ids) return true
    if (withoutPrefix.isNotEmpty() && ("tmdb:$withoutPrefix" in ids || "tvdb:$withoutPrefix" in ids || "simkl:$withoutPrefix" in ids || "imdb:$withoutPrefix" in ids || "movie:$withoutPrefix" in ids || "series:$withoutPrefix" in ids || "tv:$withoutPrefix" in ids)) return true
    if (pureId.isNotEmpty() && ("tmdb:$pureId" in ids || "tvdb:$pureId" in ids || "simkl:$pureId" in ids || "imdb:$pureId" in ids || "movie:$pureId" in ids || "series:$pureId" in ids || "tv:$pureId" in ids)) return true

    // Check TMDB <-> IMDb cross-mapping from cache
    val imdbCandidate = when {
        clean.startsWith("tt", ignoreCase = true) -> clean
        withoutPrefix.startsWith("tt", ignoreCase = true) -> withoutPrefix
        pureId.startsWith("tt", ignoreCase = true) -> pureId
        else -> null
    }
    if (imdbCandidate != null) {
        val tmdbId = TmdbService.cachedTmdbIdGlobal(imdbCandidate)
        if (tmdbId != null) {
            val tStr = tmdbId.toString()
            if (tStr in ids || "tmdb:$tStr" in ids || "movie:tmdb:$tStr" in ids || "series:tmdb:$tStr" in ids || "movie:$tStr" in ids || "series:$tStr" in ids) {
                return true
            }
        }
    }

    val tmdbCandidate = when {
        clean.all { it.isDigit() } -> clean.toIntOrNull()
        withoutPrefix.all { it.isDigit() } -> withoutPrefix.toIntOrNull()
        pureId.all { it.isDigit() } -> pureId.toIntOrNull()
        else -> null
    }
    if (tmdbCandidate != null) {
        val imdbId = TmdbService.cachedImdbIdGlobal(tmdbCandidate)
        if (imdbId != null) {
            if (imdbId in ids || imdbId.lowercase() in ids || "imdb:$imdbId" in ids || "movie:imdb:$imdbId" in ids || "series:imdb:$imdbId" in ids || "movie:$imdbId" in ids || "series:$imdbId" in ids) {
                return true
            }
        }
    }

    return false
}

internal fun SimklMedia.simklTrackingContentIds(): Set<String> = buildSet {
    canonicalContentId()?.let(::add)
    val imdb = ids.idValue("imdb")
    val tmdb = ids.idValue("tmdb")
    val tvdb = ids.idValue("tvdb")
    val simkl = ids.simklIdValue()

    imdb?.let {
        add(it)
        add(it.lowercase())
        add("imdb:$it")
        add("imdb:${it.lowercase()}")
        add("movie:imdb:$it")
        add("series:imdb:$it")
        add("movie:$it")
        add("series:$it")
    }
    tmdb?.let {
        add(it)
        add("tmdb:$it")
        add("movie:tmdb:$it")
        add("series:tmdb:$it")
        add("movie:$it")
        add("series:$it")
    }
    tvdb?.let {
        add(it)
        add("tvdb:$it")
        add("series:tvdb:$it")
        add("tv:$it")
        add("series:$it")
    }
    simkl?.let {
        add(it)
        add("simkl:$it")
        add("movie:simkl:$it")
        add("series:simkl:$it")
        add("movie:$it")
        add("series:$it")
    }

    // Cross-resolve missing external IDs from TmdbService cache
    if (imdb == null && tmdb != null) {
        tmdb.toIntOrNull()?.let { numTmdb ->
            TmdbService.cachedImdbIdGlobal(numTmdb)?.let { resolvedImdb ->
                add(resolvedImdb)
                add(resolvedImdb.lowercase())
                add("imdb:$resolvedImdb")
                add("imdb:${resolvedImdb.lowercase()}")
                add("movie:imdb:$resolvedImdb")
                add("series:imdb:$resolvedImdb")
                add("movie:$resolvedImdb")
                add("series:$resolvedImdb")
            }
        }
    }
    if (tmdb == null && imdb != null) {
        TmdbService.cachedTmdbIdGlobal(imdb)?.let { resolvedTmdb ->
            val numStr = resolvedTmdb.toString()
            add(numStr)
            add("tmdb:$numStr")
            add("movie:tmdb:$numStr")
            add("series:tmdb:$numStr")
            add("movie:$numStr")
            add("series:$numStr")
        }
    }
}

private fun WatchedItem.toSimklCompletedProgress(): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = contentType,
    name = title,
    poster = poster,
    backdrop = null,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:$season:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = watchedAt,
    progressPercent = 100f,
    source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
    trackingProviderId = trackingProviderId,
    trackingProviderItemId = trackingProviderItemId,
    trackingSourceUrl = trackingSourceUrl
)

private fun parseSimklProjectionVideoId(videoId: String): SimklProjectionVideoId? {
    val trimmed = videoId.trim()
    val prefix = trimmed.substringBefore(':').lowercase()
    if (prefix !in SIMKL_PROJECTION_ANIME_IDS) return null
    val remainder = trimmed.substringAfter(':', "")
    val id = remainder.substringBefore(':').toLongOrNull() ?: return null
    val episode = remainder.substringAfter(':', "").substringBefore(':').toIntOrNull()
    return SimklProjectionVideoId("$prefix:$id", episode)
}

private fun String.simklLookupKey(): String = trim().lowercase()

private data class SimklProjectionItemKey(
    val contentId: String,
    val season: Int?,
    val episode: Int?
)

private data class SimklProjectionMembershipKey(val contentId: String, val contentType: String)

private data class SimklProjectionMembership(val listKey: String?)

private data class SimklProjectionVideoId(val lookupKey: String, val episode: Int?)

private val SIMKL_PROJECTION_ANIME_IDS = setOf("mal", "anidb", "anilist", "kitsu")
