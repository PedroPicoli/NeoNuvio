package com.nuvio.tv.data.simkl

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class SimklSyncRepository @Inject constructor(
    private val engine: SimklSyncEngine,
    private val storage: SimklSyncStorage,
    private val authRepository: SimklAuthRepository,
    private val authStorage: SimklAuthStorage,
    private val profileManager: ProfileManager,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val refreshGate = SimklRefreshGate()
    private val _state = MutableStateFlow(SimklSyncState())
    private val _projection = MutableStateFlow(SimklSnapshotProjection.Empty)
    private var loadedProfileId: Int? = null
    private var profileGeneration = 0L
    private var lastSimklCompletedSeriesIds: Set<String> = emptySet()
    private var realtimePollingJob: Job? = null

    val state: StateFlow<SimklSyncState> = _state.asStateFlow()
    internal val projection: StateFlow<SimklSnapshotProjection> = _projection.asStateFlow()
    private val _optimisticRatings = MutableStateFlow<Map<String, Int?>>(emptyMap())

    fun getEffectiveUserRating(contentId: String): Int? {
        val key = contentId.trim().lowercase()
        val optimistic = _optimisticRatings.value
        if (optimistic.containsKey(key)) {
            return optimistic[key]
        }
        return _projection.value.userRating(contentId)
    }

    fun setOptimisticRating(contentId: String, rating: Int?) {
        val key = contentId.trim().lowercase()
        _optimisticRatings.update { it + (key to rating) }
    }

    init {
        scope.launch {
            profileManager.activeProfileId.collect { profileId ->
                if (loadedProfileId != profileId) {
                    profileGeneration += 1L
                    loadedProfileId = null
                    _state.value = SimklSyncState()
                    _projection.value = SimklSnapshotProjection.Empty
                    loadProfile(profileId)
                }
            }
        }
        scope.launch {
            authStorage.state.map { it.isAuthenticated }
                .distinctUntilChanged()
                .collectLatest { authenticated ->
                    if (authenticated) {
                        startRealtimePolling()
                    } else {
                        stopRealtimePolling()
                    }
                }
        }
    }

    private fun startRealtimePolling() {
        realtimePollingJob?.cancel()
        realtimePollingJob = scope.launch {
            while (isActive) {
                delay(SIMKL_REALTIME_POLL_INTERVAL_MS)
                if (!authStorage.state.value.isAuthenticated) break
                try {
                    refresh(TrackingRefreshIntent.AUTOMATIC)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // Ignored in background polling
                }
            }
        }
    }

    private fun stopRealtimePolling() {
        realtimePollingJob?.cancel()
        realtimePollingJob = null
    }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        if (loadedProfileId != profileId) loadProfile(profileId)
    }

    fun refreshAsync(intent: TrackingRefreshIntent) {
        scope.launch { refresh(intent) }
    }

    suspend fun refresh(intent: TrackingRefreshIntent) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        refreshGate.runIfNeeded(
            profileGeneration = generation,
            shouldRun = {
                val current = _state.value
                profileId == profileManager.activeProfileId.value &&
                    generation == profileGeneration &&
                    authStorage.state.value.isAuthenticated &&
                    shouldRunSimklRefresh(
                        intent = intent,
                        lastCheckedAtEpochMs = current.snapshot.lastCheckedAtEpochMs,
                        nowEpochMs = System.currentTimeMillis(),
                        hasError = current.errorMessage != null,
                        automaticIntervalMs = SIMKL_REALTIME_POLL_INTERVAL_MS
                    )
            }
        ) {
            refreshSnapshot(profileId, generation)
        }
    }

    suspend fun clearCurrentProfile() = withContext(Dispatchers.IO) {
        val profileId = profileManager.activeProfileId.value
        profileGeneration += 1L
        storage.remove(profileId)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            clearSimklWatchedSeries()
            _projection.value = SimklSnapshotProjection.Empty
            _state.value = SimklSyncState(hasLoaded = true)
        }
    }

    suspend fun removeProfile(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == profileManager.activeProfileId.value) {
            clearCurrentProfile()
        } else {
            storage.remove(profileId)
        }
    }

    suspend fun removePlaybackSessions(sessionIds: Set<Long>) = withContext(Dispatchers.IO) {
        if (sessionIds.isEmpty()) return@withContext
        ensureLoaded()
        snapshotMutex.withLock {
            val profileId = profileManager.activeProfileId.value
            val current = _state.value
            val playback = current.snapshot.playback.filterNot { session -> session.id in sessionIds }
            if (playback.size == current.snapshot.playback.size) return@withLock
            val snapshot = current.snapshot.copy(playback = playback)
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (profileId == profileManager.activeProfileId.value) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
                syncWatchedSeriesStateHolder(projection)
            }
        }
    }

    internal suspend fun commitScrobble(result: SimklScrobbleResult) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyScrobbleResult(
                result = result,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
                syncWatchedSeriesStateHolder(projection)
            }
        }
    }

    internal suspend fun commitMutation(receipt: SimklMutationReceipt) = withContext(Dispatchers.IO) {
        ensureLoaded()
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        snapshotMutex.withLock {
            if (!isCurrent(profileId, generation)) return@withLock
            val current = _state.value
            val snapshot = current.snapshot.applyMutationReceipt(
                receipt = receipt,
                committedAtEpochMs = System.currentTimeMillis()
            )
            if (snapshot == current.snapshot) return@withLock
            val projection = buildProjection(snapshot)
            storage.save(profileId, encodeSnapshot(snapshot))
            if (isCurrent(profileId, generation)) {
                _projection.value = projection
                _state.value = current.copy(snapshot = snapshot)
                syncWatchedSeriesStateHolder(projection)
            }
        }
    }

    private suspend fun loadProfile(profileId: Int) = loadMutex.withLock {
        if (loadedProfileId == profileId) return@withLock
        val snapshot = storage.load(profileId)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { decodeSnapshot(payload) }
                    .onFailure { error -> Log.w(TAG, "Unable to decode Simkl snapshot", error) }
                    .getOrNull()
            }
            ?.let { parsed ->
                if (parsed.schemaVersion < 2) {
                    parsed.copy(schemaVersion = 2, isInitialized = false, watermark = null)
                } else {
                    parsed
                }
            }
            ?: SimklSyncSnapshot()
        val projection = buildProjection(snapshot)
        if (profileId == profileManager.activeProfileId.value) {
            loadedProfileId = profileId
            _projection.value = projection
            _state.value = SimklSyncState(snapshot = snapshot, hasLoaded = true)
            syncWatchedSeriesStateHolder(projection)
        }
    }

    private suspend fun refreshSnapshot(profileId: Int, generation: Long) = snapshotMutex.withLock {
        val previous = _state.value
        _state.value = previous.copy(isLoading = true, errorMessage = null)
        val result = try {
            engine.synchronize(previous.snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w(TAG, "Simkl sync failed", error)
            if (isCurrent(profileId, generation)) {
                _state.value = previous.copy(
                    isLoading = false,
                    hasLoaded = true,
                    errorMessage = error.message ?: "Unable to sync Simkl"
                )
            }
            return@withLock
        }
        if (!isCurrent(profileId, generation)) return@withLock
        // Simkl publishes a new position on /sync/playback a moment after it answers a stop or a
        // pause, so a read that follows the user's own write can still describe the previous viewing.
        // Keep our own newer rows over the fetched ones; the account stays authoritative for the rest.
        val merged = result.copy(
            playback = mergeFetchedPlayback(result.playback, previous.snapshot.playback)
        )
        val projection = if (
            merged.entries === previous.snapshot.entries &&
            merged.playback === previous.snapshot.playback
        ) {
            _projection.value
        } else {
            buildProjection(merged)
        }
        authRepository.synchronizeUserSettings(merged.activities?.settings?.all)
        if (!isCurrent(profileId, generation)) return@withLock
        storage.save(profileId, encodeSnapshot(merged))
        if (isCurrent(profileId, generation)) {
            _projection.value = projection
            _state.value = SimklSyncState(snapshot = merged, hasLoaded = true)
            syncWatchedSeriesStateHolder(projection)
        }
    }

    fun isDroppedOrOnHold(contentId: String): Boolean {
        val key = contentId.trim().lowercase()
        val rawKey = key.substringAfter(':')
        val droppedSet = _projection.value.droppedOrHoldContentIds
        return key in droppedSet || (rawKey.isNotEmpty() && rawKey in droppedSet)
    }

    private fun syncWatchedSeriesStateHolder(projection: SimklSnapshotProjection) {
        val newCompleted = projection.completedSeriesIds
        val droppedOrHold = projection.droppedOrHoldContentIds
        val incomplete = projection.incompleteSeriesIds
        val current = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val updated = (current - lastSimklCompletedSeriesIds - droppedOrHold - incomplete) + newCompleted
        lastSimklCompletedSeriesIds = newCompleted
        if (updated != current) {
            val now = System.currentTimeMillis()
            val revalidateAt = newCompleted.associateWith { now + 30L * 24 * 60 * 60 * 1000 }
            watchedSeriesStateHolder.updateWithValidation(
                ids = updated,
                validatedIds = newCompleted,
                revalidateAt = revalidateAt
            )
        }
    }

    private fun clearSimklWatchedSeries() {
        if (lastSimklCompletedSeriesIds.isEmpty()) return
        val current = watchedSeriesStateHolder.fullyWatchedSeriesIds.value
        val updated = current - lastSimklCompletedSeriesIds
        lastSimklCompletedSeriesIds = emptySet()
        watchedSeriesStateHolder.update(updated)
    }

    private fun isCurrent(profileId: Int, generation: Long): Boolean =
        profileId == profileManager.activeProfileId.value && generation == profileGeneration

    fun invalidateProjections(animeIdPreference: SimklAnimeIdPreference? = null) {
        animeIdPreference?.let { preference -> SimklAnimeIdPreferenceHolder.current = preference }
        val profileId = profileManager.activeProfileId.value
        val generation = profileGeneration
        scope.launch {
            snapshotMutex.withLock {
                if (!isCurrent(profileId, generation)) return@withLock
                val current = _state.value
                val projection = buildProjection(current.snapshot)
                if (!isCurrent(profileId, generation) || current.snapshot !== _state.value.snapshot) {
                    return@withLock
                }
                _projection.value = projection
                _state.value = current.copy(projectionVersion = current.projectionVersion + 1L)
                syncWatchedSeriesStateHolder(projection)
            }
        }
    }

    private suspend fun buildProjection(snapshot: SimklSyncSnapshot): SimklSnapshotProjection =
        withContext(Dispatchers.Default) { SimklSnapshotProjection.create(snapshot) }

    private suspend fun decodeSnapshot(payload: String): SimklSyncSnapshot =
        withContext(Dispatchers.Default) { json.decodeFromString(payload) }

    private suspend fun encodeSnapshot(snapshot: SimklSyncSnapshot): String =
        withContext(Dispatchers.Default) { json.encodeToString(snapshot) }

    private companion object {
        const val TAG = "SimklSync"
    }
}
