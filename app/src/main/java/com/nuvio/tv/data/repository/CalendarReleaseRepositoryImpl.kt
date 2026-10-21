package com.nuvio.tv.data.repository

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CalendarReleaseCache
import com.nuvio.tv.domain.model.CalendarRefreshProgress
import com.nuvio.tv.domain.model.CalendarAnimeDetection
import com.nuvio.tv.domain.model.CalendarReleaseEntry
import com.nuvio.tv.domain.model.CalendarResolveOutcome
import com.nuvio.tv.domain.model.CalendarSource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CalendarReleaseRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps [CalendarReleaseCache] fresh without ever touching the whole library at once.
 *
 * - Opening the calendar renders the persisted entries immediately.
 * - A refresh pass resolves only missing or expired sources, most relevant first, through a
 *   small worker pool; results are persisted in batches so a pass costs a handful of writes.
 * - An interactive pass (screen visible) runs until the queue is empty or the screen is hidden;
 *   a periodic pass (app in foreground, screen hidden) takes a bounded slice per tick.
 * - Passes never overlap; work survives leaving the screen because it runs in the repository scope.
 */
@Singleton
internal class CalendarReleaseRepositoryImpl internal constructor(
    private val libraryRepository: LibraryRepository,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val cache: CalendarReleaseCache,
    private val profileManager: ProfileManager,
    private val releaseKindsSource: CalendarMovieReleaseKindsSource,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate,
    private val region: () -> String
) : CalendarReleaseRepository {

    @Inject
    constructor(
        libraryRepository: LibraryRepository,
        watchProgressRepository: WatchProgressRepository,
        metaRepository: MetaRepository,
        cache: CalendarReleaseCache,
        profileManager: ProfileManager,
        releaseKindsSource: CalendarMovieReleaseKindsSource
    ) : this(
        libraryRepository = libraryRepository,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        cache = cache,
        profileManager = profileManager,
        releaseKindsSource = releaseKindsSource,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        today = { LocalDate.now() },
        region = { Locale.getDefault().country }
    )

    companion object {
        private const val TAG = "CalendarRelease"
        /** Same fan-out the Home hero enrichment uses; bounds both sockets and transient heap. */
        internal const val INTERACTIVE_CONCURRENCY = 4
        internal const val PERIODIC_CONCURRENCY = 2
        /** Upper bound of resolutions per periodic tick; the rest waits for the next tick. */
        internal const val PERIODIC_BATCH_LIMIT = 40
        /** Measured from process start, not from each activity start, so short sessions still tick. */
        private const val PERIODIC_INITIAL_DELAY_MS = 2 * 60_000L
        private const val PERIODIC_INTERVAL_MS = 30 * 60_000L
        private const val PERIODIC_MIN_DELAY_MS = 15_000L
        /** A hung addon must not pin a worker for the full OkHttp read timeout. */
        private const val RESOLVE_TIMEOUT_MS = 15_000L
        /**
         * Persist after this many resolutions or this much time, whichever comes first. Every
         * write re-emits the store, which the screen decodes and regroups, so batches are sized to
         * keep a 400-title first build under ~20 writes while the grid still fills in visibly.
         */
        private const val FLUSH_EVERY = 24
        private const val FLUSH_INTERVAL_MS = 3_000L
        /** Keep the combined library/watching flow alive briefly between subscribers. */
        private const val SOURCES_SHARE_TIMEOUT_MS = 5_000L
        /**
         * Consecutive failures that end a pass early. An unreachable addon would otherwise pin the
         * workers for [RESOLVE_TIMEOUT_MS] per item; the failed entries keep
         * [CalendarReleasePolicy.FAILED_TTL_MS] and are retried on a later pass.
         */
        internal const val CIRCUIT_BREAKER_FAILURES = 8
    }

    private enum class PassMode { INTERACTIVE, PERIODIC }

    private sealed class MetaFetch {
        data class Found(val meta: Meta) : MetaFetch()
        data object NotFound : MetaFetch()
        data object Failed : MetaFetch()
    }

    private class PassStats {
        val resolved = AtomicInteger()
        val fromMetaCache = AtomicInteger()
        val outcomes = CalendarResolveOutcome.entries.associateWith { AtomicInteger() }
    }

    private val passMutex = Mutex()
    private val passLock = Any()
    private val screenVisible = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val createdAtMs = System.currentTimeMillis()
    @Volatile private var lastPassCompletedAtMs = 0L
    @Volatile private var passJob: Job? = null
    @Volatile private var periodicJob: Job? = null

    private val _refreshProgress = MutableStateFlow<CalendarRefreshProgress?>(null)
    override val refreshProgress: StateFlow<CalendarRefreshProgress?> = _refreshProgress.asStateFlow()

    override val sources: Flow<List<CalendarSource>> =
        combine(libraryRepository.libraryItems, watchingSeriesSources()) { library, watching ->
            // A title saved to a library AND in Continue Watching keeps its LIBRARY origin (caption,
            // recency) but must still satisfy the "Watching" filter — isWatching tracks that
            // independently of which source object won the merge below.
            val watchingKeys = watching.mapTo(HashSet()) { it.key }
            val librarySources = library.map { entry ->
                val source = CalendarSource.fromLibrary(entry)
                if (source.key in watchingKeys) source.copy(isWatching = true) else source
            }
            val libraryKeys = librarySources.mapTo(HashSet()) { it.key }
            (librarySources + watching.filter { it.key !in libraryKeys }).distinctBy { it.key }
        }
            .distinctUntilChanged()
            .shareIn(scope, SharingStarted.WhileSubscribed(SOURCES_SHARE_TIMEOUT_MS), replay = 1)

    override val entries: Flow<Map<String, CalendarReleaseEntry>> = cache.entries

    override val supportsReleaseKinds: Flow<Boolean> = releaseKindsSource.enabled

    override fun setScreenVisible(visible: Boolean) {
        screenVisible.set(visible)
        if (visible) requestRefresh()
    }

    override fun requestRefresh() {
        launchPass(currentMode())
    }

    override fun startPeriodicRefresh() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (true) {
                delay(nextPeriodicDelayMs())
                // Only users who opened the calendar at least once have a cache worth keeping
                // fresh; everyone else costs nothing in the background.
                val hasCache = cache.getEntries(profileManager.activeProfileId.value).isNotEmpty()
                if (!screenVisible.get() && hasCache) launchPass(PassMode.PERIODIC)
                lastPassCompletedAtMs = System.currentTimeMillis()
            }
        }
    }

    override fun stopPeriodicRefresh() {
        periodicJob?.cancel()
        periodicJob = null
    }

    override suspend fun clearCache() {
        cache.clear(profileManager.activeProfileId.value)
    }

    /** Waits for the running pass loop, if any; used by tests. */
    internal suspend fun awaitIdle() {
        passJob?.join()
    }

    /**
     * Time until the next periodic tick: the first one comes shortly after process start, later
     * ones a full interval after the last completed pass of any kind.
     */
    private fun nextPeriodicDelayMs(): Long {
        val now = System.currentTimeMillis()
        val untilFirst = PERIODIC_INITIAL_DELAY_MS - (now - createdAtMs)
        val untilNext = if (lastPassCompletedAtMs == 0L) 0L else PERIODIC_INTERVAL_MS - (now - lastPassCompletedAtMs)
        return maxOf(untilFirst, untilNext, PERIODIC_MIN_DELAY_MS)
    }

    private fun currentMode(): PassMode =
        if (screenVisible.get()) PassMode.INTERACTIVE else PassMode.PERIODIC

    /** Series the user is actively watching (in progress or awaiting the next episode). */
    private fun watchingSeriesSources(): Flow<List<CalendarSource>> =
        combine(
            watchProgressRepository.continueWatching,
            watchProgressRepository.observeNextUpSeeds()
        ) { inProgress, nextUpSeeds ->
            (inProgress + nextUpSeeds)
                .filter { ContentType.fromString(it.contentType) == ContentType.SERIES }
                .distinctBy { it.contentId }
                .map(CalendarSource::fromWatchProgress)
        }

    private fun launchPass(mode: PassMode) {
        synchronized(passLock) {
            if (passJob?.isActive == true) {
                rerunRequested.set(true)
                return
            }
            passJob = scope.launch { runPassLoop(mode) }
        }
    }

    /** Runs passes until no refresh was requested while the previous one ran. */
    private suspend fun runPassLoop(initialMode: PassMode) {
        var mode = initialMode
        while (true) {
            rerunRequested.set(false)
            runCatching { runPass(mode) }
                .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Refresh pass failed", it) }
            lastPassCompletedAtMs = System.currentTimeMillis()
            mode = currentMode()
            val runAgain = synchronized(passLock) {
                // Decided under the lock so a request racing with this exit either loops here or
                // finds passJob cleared and starts its own job.
                if (rerunRequested.getAndSet(false)) true else { passJob = null; false }
            }
            if (!runAgain) return
        }
    }

    private suspend fun runPass(mode: PassMode) = passMutex.withLock {
        val startedAtMs = System.currentTimeMillis()
        val rxBytesAtStart = TrafficStats.getUidRxBytes(Process.myUid())
        val profileId = profileManager.activeProfileId.value
        val today = today()
        val window = CalendarReleasePolicy.window(today)
        val zoneId = ZoneId.systemDefault()

        val sourceList = sources.first()
        if (sourceList.isEmpty()) return@withLock
        val current = cache.getEntries(profileId)
        // Entries for titles no longer saved or watched are dropped once they expire, never on
        // sight: a transiently empty library (profile switch, provider re-sync) must not wipe them.
        val activeKeys = sourceList.mapTo(HashSet()) { it.key }
        cache.removeAll(profileId, current.filter { (key, entry) -> key !in activeKeys && entry.isExpired(startedAtMs) }.keys)

        val wantKinds = releaseKindsSource.isEnabled
        val stale = CalendarReleasePolicy.stale(sourceList, current, startedAtMs, today, requireReleaseKinds = wantKinds)
        val queue = if (mode == PassMode.PERIODIC) stale.take(PERIODIC_BATCH_LIMIT) else stale
        if (queue.isEmpty()) {
            Log.i(TAG, "pass mode=$mode sources=${sourceList.size} cached=${current.size} queued=0 (nothing stale)")
            return@withLock
        }

        _refreshProgress.value = CalendarRefreshProgress(resolved = 0, total = queue.size, isRunning = true)
        val stats = PassStats()
        val consecutiveFailures = AtomicInteger()
        val writer = BatchWriter(profileId)
        val queueIterator = queue.iterator()
        val queueMutex = Mutex()
        val concurrency = if (mode == PassMode.INTERACTIVE) INTERACTIVE_CONCURRENCY else PERIODIC_CONCURRENCY

        try {
            coroutineScope {
                repeat(concurrency) {
                    launch {
                        while (true) {
                            if (mode == PassMode.INTERACTIVE && !screenVisible.get()) break
                            if (consecutiveFailures.get() >= CIRCUIT_BREAKER_FAILURES) break
                            val source = queueMutex.withLock { if (queueIterator.hasNext()) queueIterator.next() else null }
                                ?: break
                            val entry = resolve(source, current[source.key], window, today, zoneId, wantKinds, stats)
                            writer.add(entry)
                            stats.outcomes.getValue(entry.outcome).incrementAndGet()
                            if (entry.outcome == CalendarResolveOutcome.FAILED) {
                                if (consecutiveFailures.incrementAndGet() == CIRCUIT_BREAKER_FAILURES) {
                                    Log.w(TAG, "pass mode=$mode stopped early after $CIRCUIT_BREAKER_FAILURES consecutive failures")
                                }
                            } else {
                                consecutiveFailures.set(0)
                            }
                            _refreshProgress.value = CalendarRefreshProgress(
                                resolved = stats.resolved.incrementAndGet(),
                                total = queue.size,
                                isRunning = true
                            )
                        }
                    }
                }
            }
        } finally {
            writer.flush()
            _refreshProgress.value = null
        }

        val rxBytesAtEnd = TrafficStats.getUidRxBytes(Process.myUid())
        val rxKb = if (rxBytesAtStart < 0 || rxBytesAtEnd < 0) "n/a" else ((rxBytesAtEnd - rxBytesAtStart) / 1024).toString()
        Log.i(
            TAG,
            "pass mode=$mode sources=${sourceList.size} cached=${current.size} queued=${queue.size} " +
                "resolved=${stats.resolved.get()} fromMetaCache=${stats.fromMetaCache.get()} " +
                "outcomes=${stats.outcomes.entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value.get()}" }} " +
                "writes=${writer.writeCount} durationMs=${System.currentTimeMillis() - startedAtMs} rxKb=$rxKb"
        )
    }

    /**
     * Resolves one source. A transient failure or a "not found" answer never discards what an
     * earlier resolution found: [previous] releases are carried over and only the outcome and
     * expiry change, so a network blip cannot empty a day on the grid.
     */
    private suspend fun resolve(
        source: CalendarSource,
        previous: CalendarReleaseEntry?,
        window: CalendarReleasePolicy.Window,
        today: LocalDate,
        zoneId: ZoneId,
        wantKinds: Boolean,
        stats: PassStats
    ): CalendarReleaseEntry {
        val fetch = try {
            withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { fetchMeta(source, stats) } ?: MetaFetch.Failed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Resolve failed for ${source.key}: ${e.message}")
            MetaFetch.Failed
        }
        val nowMs = System.currentTimeMillis()
        val meta = (fetch as? MetaFetch.Found)?.meta
        val addonReleases = meta?.let { CalendarReleasePolicy.extractReleases(it, window, zoneId) }.orEmpty()
        // Movies: ask TMDB which of theatrical/digital/physical each date is. Not asking (setting
        // off, TMDB failure) leaves the flag false so a later pass can fill it in.
        var kindsResolved = false
        val fetchedReleases = if (meta != null && wantKinds && source.isMovie) {
            val tmdbDates = releaseKindsSource.fetch(source)
            kindsResolved = tmdbDates != null
            if (tmdbDates != null) {
                CalendarReleasePolicy.mergeMovieReleases(addonReleases, tmdbDates, region(), window)
            } else {
                addonReleases
            }
        } else {
            addonReleases
        }
        val outcome = when {
            meta == null && fetch is MetaFetch.NotFound -> CalendarResolveOutcome.NOT_FOUND
            meta == null -> CalendarResolveOutcome.FAILED
            fetchedReleases.isEmpty() -> CalendarResolveOutcome.NO_DATES
            else -> CalendarResolveOutcome.RESOLVED
        }
        val releases = if (meta == null) previous?.releases.orEmpty().filter { it.date in window } else fetchedReleases
        return CalendarReleaseEntry(
            sourceKey = source.key,
            title = meta?.name?.takeIf { it.isNotBlank() } ?: previous?.title?.takeIf { it.isNotBlank() } ?: source.name,
            poster = meta?.poster ?: previous?.poster ?: source.poster,
            posterShape = meta?.posterShape ?: previous?.posterShape ?: PosterShape.POSTER,
            releases = releases,
            outcome = outcome,
            resolvedAtMs = nowMs,
            expiresAtMs = CalendarReleasePolicy.expiresAt(nowMs, CalendarReleasePolicy.ttlMs(outcome, releases, today)),
            releaseKindsResolved = if (meta == null) previous?.releaseKindsResolved ?: false else kindsResolved,
            isAnime = if (meta == null) {
                previous?.isAnime ?: false
            } else {
                CalendarAnimeDetection.matches(
                    id = meta.id,
                    genres = meta.genres,
                    language = meta.language,
                    country = meta.country
                )
            }
        )
    }

    /**
     * Prefers the addon the item was saved from, then falls back to every installed addon.
     * A meta already held by [MetaRepository] short-circuits without a network call.
     */
    private suspend fun fetchMeta(source: CalendarSource, stats: PassStats): MetaFetch {
        metaRepository.getCachedMeta(source.type, source.id)?.let {
            stats.fromMetaCache.incrementAndGet()
            return MetaFetch.Found(it)
        }

        val sourceAddon = source.addonBaseUrl?.takeIf { it.isNotBlank() }
        if (sourceAddon != null) {
            val fromSource = metaRepository.getMeta(sourceAddon, source.type, source.id)
                .first { it !is NetworkResult.Loading }
            if (fromSource is NetworkResult.Success) return MetaFetch.Found(fromSource.data)
        }

        return when (val fromAny = metaRepository.getMetaFromAllAddons(source.type, source.id)
            .first { it !is NetworkResult.Loading }) {
            is NetworkResult.Success -> MetaFetch.Found(fromAny.data)
            is NetworkResult.Error ->
                if (fromAny.code == NetworkResult.META_NOT_FOUND_CODE) MetaFetch.NotFound else MetaFetch.Failed
            NetworkResult.Loading -> MetaFetch.Failed
        }
    }

    /** Coalesces resolved entries into a few cache transactions per pass. */
    private inner class BatchWriter(private val profileId: Int) {
        private val mutex = Mutex()
        private val pending = ArrayList<CalendarReleaseEntry>()
        private var lastFlushMs = System.currentTimeMillis()
        private val writes = AtomicInteger()
        val writeCount: Int
            get() = writes.get()

        suspend fun add(entry: CalendarReleaseEntry) {
            val batch = mutex.withLock {
                pending += entry
                val due = pending.size >= FLUSH_EVERY ||
                    System.currentTimeMillis() - lastFlushMs >= FLUSH_INTERVAL_MS
                if (due) drainLocked() else null
            }
            batch?.let { write(it) }
        }

        suspend fun flush() {
            mutex.withLock { drainLocked() }?.let { write(it) }
        }

        private fun drainLocked(): List<CalendarReleaseEntry>? {
            if (pending.isEmpty()) return null
            val batch = pending.toList()
            pending.clear()
            lastFlushMs = System.currentTimeMillis()
            return batch
        }

        private suspend fun write(batch: List<CalendarReleaseEntry>) {
            cache.upsertAll(profileId, batch)
            writes.incrementAndGet()
        }
    }
}
