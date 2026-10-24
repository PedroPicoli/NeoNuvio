package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.R
import com.nuvio.tv.core.dub.DubLanguageDetector
import com.nuvio.tv.core.dub.DubOrchestrator
import com.nuvio.tv.core.dub.DubOutcome
import com.nuvio.tv.core.dub.DubPlan
import com.nuvio.tv.core.dub.DubResolvedAudio
import com.nuvio.tv.core.dub.DubResultCache
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

private const val FORCE_DUB_STREAM_FETCH_TIMEOUT_MS = 8_000L
private const val FORCE_DUB_VERIFY_TIMEOUT_MS = 20_000L

/** Prefixo do grupo de faixas que o MergingMediaSource atribui à fonte de índice 1 (áudio dublado). */
private const val DUB_SOURCE_GROUP_PREFIX = "1:"
private const val VIDEO_SOURCE_GROUP_PREFIX = "0:"

internal val PlayerRuntimeController.isExoPlayerEngineActive: Boolean
    get() = _uiState.value.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER

internal suspend fun PlayerRuntimeController.isForceDubEffectivelyEnabledForCurrent(): Boolean {
    // Sobrescrita manual do usuário na reprodução atual
    forceDubUserOverridden?.let { return it }

    // Se for série, verificar configuração específica da série nos detalhes
    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val cid = contentId
    if (isSeries && !cid.isNullOrBlank()) {
        try {
            val fallbackId = navigationArgs.videoId?.substringBefore(":")
                ?: currentVideoId?.substringBefore(":")
            val seriesSettings = seriesCustomSettingsStore.get(cid, profileId, fallbackId = fallbackId)
            if (seriesSettings.forceDubEnabled != null) {
                return seriesSettings.forceDubEnabled == true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Failed to load series settings for force dub: ${e.message}")
        }
    }

    // Configuração global
    return playerSettingsDataStore.playerSettings.first().forceDubEnabled
}

internal fun PlayerRuntimeController.resolveForceDubWarning(continueWithDub: Boolean) {
    _uiState.update { it.copy(forceDubWarningDialog = null) }
    val callback = pendingWarningResolution
    pendingWarningResolution = null
    callback?.invoke(continueWithDub)
}

internal fun PlayerRuntimeController.toggleForceDub() {
    when {
        currentForceDubActive -> {
            // Desativar dublagem e voltar para o áudio original
            forceDubUserOverridden = false
            cancelForceDubWork()
            clearForceDubState(stopTorrent = true, keepPlan = true)
            scope.launch { rebuildMediaSourceToOriginal() }
        }
        _uiState.value.isForceDubLoading -> {
            // Cancelar uma tentativa em andamento
            forceDubUserOverridden = false
            cancelForceDubWork()
            clearForceDubState(stopTorrent = true)
        }
        else -> {
            forceDubUserOverridden = true
            startForceDubFromMenu()
        }
    }
}

/** Ativa a dublagem enquanto o vídeo já está tocando (menu de áudio). */
private fun PlayerRuntimeController.startForceDubFromMenu() {
    if (!isExoPlayerEngineActive) {
        showToastOnUiThread(context.getString(R.string.force_dub_subtitle))
        forceDubUserOverridden = null
        return
    }
    // Uma nova tentativa manual sempre é permitida.
    forceDubFailedStreamUrls.remove(currentStreamUrl)
    cancelForceDubWork()
    forceDubJob = scope.launch {
        _uiState.update { it.copy(isForceDubEnabled = true, isForceDubLoading = true) }
        try {
            val plan = prepareForceDubPlan(fromMenu = true)
            if (plan == null) {
                clearForceDubState(stopTorrent = true)
                return@launch
            }
            applyDubPlanLive(plan)
            showToastOnUiThread(context.getString(R.string.player_loading_dubbed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(PlayerRuntimeController.TAG, "Force dub from menu failed", e)
            failForceDub(restoreOriginal = false)
        }
    }
}

/**
 * Resolve o plano de dublagem (ou null para tocar o original). Mostra toast em caso de falha e
 * pergunta ao usuário quando houver alertas (4K / sincronização aproximada).
 *
 * Roda dentro do orçamento de tempo: 20 s na abertura e 1 min 20 s quando acionado pelo menu.
 */
internal suspend fun PlayerRuntimeController.prepareForceDubPlan(fromMenu: Boolean): DubPlan? {
    val streamUrl = currentStreamUrl
    forceDubPlanCache?.takeIf { forceDubPlanStreamUrl == streamUrl }?.let { return it }

    val settings = playerSettingsDataStore.playerSettings.first()
    val targetLanguage = settings.forceDubTargetLanguage
    val budgetMs = if (fromMenu) DubOrchestrator.MENU_BUDGET_MS else DubOrchestrator.STARTUP_BUDGET_MS

    val realVideoStream = _uiState.value.sourceAllStreams.firstOrNull { it.getStreamUrl() == streamUrl }
    val videoStream = realVideoStream ?: Stream(
        name = streamName ?: _uiState.value.currentStreamName,
        title = currentFilename,
        description = currentStreamDescription,
        url = streamUrl,
        ytId = null,
        infoHash = currentInfoHash,
        fileIdx = currentFileIdx,
        externalUrl = null,
        behaviorHints = if (currentHeaders.isNotEmpty()) {
            StreamBehaviorHints(
                notWebReady = false,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = ProxyHeaders(
                    request = currentHeaders,
                    response = currentStreamResponseHeaders.ifEmpty { null }
                )
            )
        } else {
            null
        },
        addonName = currentAddonName ?: "Default",
        addonLogo = currentAddonLogo
    )

    val videoDurationHintMs = if (fromMenu) {
        _exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L }
    } else {
        null
    }

    var torrentStartedHere = false
    val outcome = DubOrchestrator.orchestrate(
        videoStream = videoStream,
        videoUrl = streamUrl,
        videoHeaders = currentHeaders,
        videoDurationHintMs = videoDurationHintMs,
        videoIsTorrent = isTorrentStream,
        targetLanguage = targetLanguage,
        allowBlindSyncWithoutPrompt = settings.forceDubAllowBlindSync,
        totalBudgetMs = budgetMs,
        fetchStreams = { collectDubStreamPool(targetLanguage) },
        resolveAudio = { candidate ->
            resolveDubAudio(candidate) { torrentStartedHere = true }
        }
    )

    suspend fun discardTorrent() {
        if (torrentStartedHere) stopDubTorrentIfStarted()
    }

    return when (outcome) {
        is DubOutcome.Ready -> {
            val plan = outcome.plan
            // O torrent só deve continuar vivo se o plano escolhido realmente o usa.
            if (torrentStartedHere && !plan.audio.isTorrent) discardTorrent()
            val accepted = confirmForceDubWarnings(plan)
            if (!accepted) {
                discardTorrent()
                return null
            }
            forceDubPlanCache = plan
            forceDubPlanStreamUrl = streamUrl
            plan
        }
        DubOutcome.NotNeeded -> {
            discardTorrent()
            null
        }
        DubOutcome.NoCandidate, DubOutcome.Timeout -> {
            discardTorrent()
            if (!fromMenu) forceDubFailedStreamUrls.add(streamUrl)
            showToastOnUiThread(context.getString(R.string.force_dub_fallback_toast))
            null
        }
    }
}

private suspend fun PlayerRuntimeController.confirmForceDubWarnings(plan: DubPlan): Boolean {
    for (warning in plan.warnings) {
        val deferred = CompletableDeferred<Boolean>()
        pendingWarningResolution = { accepted -> deferred.complete(accepted) }
        _uiState.update { it.copy(forceDubWarningDialog = warning) }
        val accepted = try {
            deferred.await()
        } finally {
            pendingWarningResolution = null
            _uiState.update { it.copy(forceDubWarningDialog = null) }
        }
        if (!accepted) return false
    }
    return true
}

/** Junta as fontes já carregadas na tela de fontes com uma busca nova ultra-rápida (com early exit ao encontrar candidatos). */
private suspend fun PlayerRuntimeController.collectDubStreamPool(targetLanguage: String? = null): List<Stream> {
    val existing = _uiState.value.sourceAllStreams
    if (existing.isNotEmpty()) return existing

    val videoId = currentVideoId ?: contentId ?: return emptyList()
    val type = contentType ?: "movie"
    val normTarget = targetLanguage?.let { DubLanguageDetector.normalizeLanguageCode(it) }.orEmpty()
    var collected: List<Stream> = emptyList()

    withTimeoutOrNull(FORCE_DUB_STREAM_FETCH_TIMEOUT_MS) {
        var shouldStop = false
        try {
            streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = videoId,
                season = currentSeason,
                episode = currentEpisode,
                forceRefresh = false
            ).takeWhile { !shouldStop }.collect { result ->
                if (result is NetworkResult.Success) {
                    val all = result.data.flatMap { it.streams }
                    if (all.size >= collected.size) {
                        collected = all
                        if (normTarget.isNotBlank()) {
                            val dubCandidates = all.count { DubLanguageDetector.streamMatchesTargetLanguage(it, normTarget) }
                            if (dubCandidates >= 2) {
                                // Addons rápidos já responderam com múltiplos candidatos dublados!
                                shouldStop = true
                            }
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // Early-exit intencional para máxima rapidez
        }
    }
    return collected
}

private suspend fun PlayerRuntimeController.resolveDubAudio(
    candidate: Stream,
    onTorrentStarted: () -> Unit
): DubResolvedAudio? {
    fun headersOf(stream: Stream): Map<String, String> =
        stream.behaviorHints?.proxyHeaders?.request.orEmpty()

    val direct = candidate.getStreamUrl()
    if (!direct.isNullOrBlank()) {
        return DubResolvedAudio(candidate, direct, headersOf(candidate), isTorrent = false)
    }

    val needsDebrid = candidate.isDirectDebrid() ||
        (candidate.needsLocalDebridResolve() &&
            candidate.debridCacheStatus?.state == StreamDebridCacheState.CACHED)
    if (needsDebrid) {
        val resolved = resolveDirectDebridStreamIfNeeded(candidate, currentSeason, currentEpisode) ?: return null
        val url = resolved.getStreamUrl() ?: return null
        return DubResolvedAudio(resolved, url, headersOf(resolved), isTorrent = false)
    }

    if (candidate.isTorrent() && !isTorrentStream) {
        val infoHash = candidate.getEffectiveInfoHash() ?: return null
        val trackers = candidate.sources
            ?.filter { it.startsWith("tracker:") }
            ?.map { it.removePrefix("tracker:") }
            ?: emptyList()
        val localUrl = try {
            torrentService.startStream(
                infoHash,
                candidate.getEffectiveFileIdx(),
                candidate.behaviorHints?.filename,
                trackers
            )
        } catch (e: CancellationException) {
            runCatching { torrentService.stopStream() }
            throw e
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Torrent para áudio dublado falhou: ${e.message}")
            runCatching { torrentService.stopStream() }
            return null
        }
        forceDubTorrentStartedForAudio = true
        onTorrentStarted()
        return DubResolvedAudio(candidate, localUrl, emptyMap(), isTorrent = true)
    }
    return null
}

private fun PlayerRuntimeController.stopDubTorrentIfStarted() {
    if (forceDubTorrentStartedForAudio) {
        forceDubTorrentStartedForAudio = false
        if (!isTorrentStream) {
            runCatching { torrentService.stopStream() }
        }
    }
}

/** Monta a fonte mesclada (vídeo original + áudio dublado) para o stream atual. */
internal fun PlayerRuntimeController.createDubbedSourceForCurrent(
    plan: DubPlan,
    subtitleConfigurations: List<androidx.media3.common.MediaItem.SubtitleConfiguration>,
    subtitleRoutes: Map<String, SubtitleRoute>
): MediaSource = mediaSourceFactory.createDubbedMediaSource(
    context = context,
    videoUrl = currentStreamUrl,
    videoHeaders = currentHeaders,
    audioUrl = plan.audio.url,
    audioHeaders = plan.audio.headers,
    audioOffsetMs = plan.timeline.initialAudioOffsetMs,
    scale = plan.timeline.scale,
    audioDelayUsProvider = audioDelayUs::get,
    subtitleConfigurations = subtitleConfigurations,
    subtitleRoutes = subtitleRoutes,
    filename = currentFilename,
    responseHeaders = currentStreamResponseHeaders,
    mimeTypeOverride = currentStreamMimeType,
    mediaMetadata = buildMediaSessionMetadata(),
    cacheKey = currentStreamCacheKey
)

/** Registra que a dublagem foi aplicada ao player e inicia a verificação de que o áudio trocou. */
internal fun PlayerRuntimeController.markForceDubApplied(plan: DubPlan) {
    currentForceDubActive = true
    currentForceDubPlan = plan
    forceDubVerifiedForPlan = false
    _uiState.update {
        it.copy(
            isForceDubEnabled = true,
            isForceDubActive = true,
            isForceDubLoading = true,
            forceDubAudioStreamName = plan.audio.stream.name
        )
    }
    forceDubVerifyJob?.cancel()
    forceDubVerifyJob = scope.launch {
        delay(FORCE_DUB_VERIFY_TIMEOUT_MS)
        if (currentForceDubActive && !forceDubVerifiedForPlan) {
            Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: áudio dublado não ficou ativo a tempo; voltando ao original")
            failForceDub(restoreOriginal = true)
        }
    }
}

private suspend fun PlayerRuntimeController.applyDubPlanLive(plan: DubPlan) {
    val player = _exoPlayer ?: return
    val currentPos = player.currentPosition
    val wasPlaying = player.playWhenReady

    val startupSubtitlePreparation = prepareStartupSubtitles()
    val source = createDubbedSourceForCurrent(
        plan = plan,
        subtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation),
        subtitleRoutes = subtitleRoutes(startupSubtitlePreparation.attachedSubtitles)
    )
    markForceDubApplied(plan)
    player.setMediaSource(source, currentPos)
    player.prepare()
    player.playWhenReady = wasPlaying
}

/**
 * Chamado a cada mudança de faixas. Em reprodução dublada, força o vídeo da fonte original (grupos "0:")
 * e o áudio da fonte dublada (grupos "1:"), e confirma o sucesso quando o áudio dublado estiver ativo.
 */
internal fun PlayerRuntimeController.enforceDubTrackSelection(tracks: Tracks) {
    if (!currentForceDubActive) return
    val plan = currentForceDubPlan ?: return
    val player = _exoPlayer ?: return

    val dubAudioGroups = tracks.groups.filter {
        it.type == C.TRACK_TYPE_AUDIO && it.mediaTrackGroup.id.startsWith(DUB_SOURCE_GROUP_PREFIX)
    }
    if (dubAudioGroups.isEmpty()) return

    if (dubAudioGroups.any { it.isSelected }) {
        onForceDubVerified(plan)
        return
    }

    val targetNorm = DubLanguageDetector.normalizeLanguageCode(plan.timeline.targetAudioLanguage)
    var best: Pair<Tracks.Group, Int>? = null
    var bestScore = Int.MIN_VALUE
    for (group in dubAudioGroups) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            var score = 0
            if (targetNorm.isNotBlank() &&
                DubLanguageDetector.normalizeLanguageCode(format.language) == targetNorm
            ) {
                score += 100
            }
            val label = format.label.orEmpty().lowercase()
            if (label.contains("dublado") || label.contains("dub")) score += 20
            if (label.contains("commentary") || label.contains("comentário")) score -= 50
            if (format.channelCount in 1..2) score += 1
            if (score > bestScore) {
                bestScore = score
                best = group to i
            }
        }
    }
    val (group, trackIndex) = best ?: return

    val builder = player.trackSelectionParameters.buildUpon()
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))

    // Garante que o vídeo continue vindo estritamente da fonte original (grupo "0:"),
    // mesmo que a fonte dublada (grupo "1:") tenha resolução ou bitrate maiores.
    val videoGroups = tracks.groups.filter {
        it.type == C.TRACK_TYPE_VIDEO && it.mediaTrackGroup.id.startsWith(VIDEO_SOURCE_GROUP_PREFIX)
    }
    val currentSelectedVideo = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
    val shouldOverrideVideo = currentSelectedVideo == null || !currentSelectedVideo.mediaTrackGroup.id.startsWith(VIDEO_SOURCE_GROUP_PREFIX)
    if (shouldOverrideVideo && videoGroups.isNotEmpty()) {
        val vg = videoGroups.firstOrNull { g -> (0 until g.length).any { g.isTrackSupported(it) } }
        val vIndex = vg?.let { g -> (0 until g.length).firstOrNull { g.isTrackSupported(it) } }
        if (vg != null && vIndex != null) {
            builder.setOverrideForType(TrackSelectionOverride(vg.mediaTrackGroup, vIndex))
        }
    }
    player.trackSelectionParameters = builder.build()
}

private fun PlayerRuntimeController.onForceDubVerified(plan: DubPlan) {
    if (forceDubVerifiedForPlan) return
    forceDubVerifiedForPlan = true
    forceDubVerifyJob?.cancel()
    forceDubVerifyJob = null
    _uiState.update { it.copy(isForceDubLoading = false, isForceDubActive = true) }
    // Só guarda em cache depois que a dublagem realmente tocou.
    DubResultCache.put(plan.videoCacheKey, plan.audioCacheKey, plan.timeline)
    Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: áudio dublado ativo (${plan.audio.stream.name})")
}

/** Erro de reprodução enquanto a dublagem está ativa: volta ao original com toast. */
internal fun PlayerRuntimeController.handleForceDubPlaybackError(): Boolean {
    if (!currentForceDubActive) return false
    Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: erro de reprodução com dublagem ativa; voltando ao original")
    failForceDub(restoreOriginal = true)
    return true
}

/**
 * Falha na dublagem: reseta o estado, avisa com toast e (opcionalmente) reconstrói a fonte original.
 * O stream fica marcado como falho para que reinícios do player não tentem de novo (sem loop).
 */
internal fun PlayerRuntimeController.failForceDub(restoreOriginal: Boolean) {
    forceDubFailedStreamUrls.add(currentStreamUrl)
    cancelForceDubWork()
    clearForceDubState(stopTorrent = true)
    showToastOnUiThread(context.getString(R.string.force_dub_fallback_toast))
    if (restoreOriginal) {
        scope.launch { rebuildMediaSourceToOriginal() }
    }
}

internal fun PlayerRuntimeController.cancelForceDubWork() {
    forceDubJob?.cancel()
    forceDubJob = null
    forceDubVerifyJob?.cancel()
    forceDubVerifyJob = null
    pendingWarningResolution = null
}

internal fun PlayerRuntimeController.clearForceDubState(stopTorrent: Boolean, keepPlan: Boolean = false) {
    val planUsesTorrent = currentForceDubPlan?.audio?.isTorrent == true || forceDubPlanCache?.audio?.isTorrent == true
    currentForceDubActive = false
    currentForceDubPlan = null
    forceDubVerifiedForPlan = false
    if (!keepPlan || planUsesTorrent) {
        forceDubPlanCache = null
        forceDubPlanStreamUrl = null
    }
    if (stopTorrent) stopDubTorrentIfStarted()
    _uiState.update {
        it.copy(
            isForceDubActive = false,
            isForceDubLoading = false,
            forceDubAudioStreamName = null,
            forceDubWarningDialog = null
        )
    }
}

private suspend fun PlayerRuntimeController.rebuildMediaSourceToOriginal() {
    val player = _exoPlayer ?: return
    isRestoringOriginalAfterDub = true
    _uiState.update {
        it.copy(
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_starting),
            loadingProgress = null
        )
    }
    try {
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady

        // 1. Remove seleções forçadas da dublagem para voltar à faixa padrão da fonte original
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()

        // 2. Para a reprodução para liberar decodificadores e AudioTrack suavemente
        player.stop()

        val startupSubtitlePreparation = prepareStartupSubtitles()
        val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)

        val originalSource = mediaSourceFactory.createMediaSource(
            context = context,
            url = currentStreamUrl,
            headers = currentHeaders,
            subtitleConfigurations = startupSubtitleConfigurations,
            subtitleRoutes = subtitleRoutes(startupSubtitlePreparation.attachedSubtitles),
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders,
            mimeTypeOverride = currentStreamMimeType,
            audioDelayUsProvider = audioDelayUs::get,
            mediaMetadata = buildMediaSessionMetadata(),
            cacheKey = currentStreamCacheKey
        )

        player.setMediaSource(originalSource, currentPos)
        player.prepare()
        player.playWhenReady = wasPlaying

        // Aguarda até o player voltar a estar pronto para reproduzir a fonte original
        withTimeoutOrNull(10_000L) {
            while (isActive && _exoPlayer?.playbackState != Player.STATE_READY) {
                delay(100)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(PlayerRuntimeController.TAG, "Failed to restore original media source", e)
    } finally {
        isRestoringOriginalAfterDub = false
        _uiState.update {
            it.copy(
                showLoadingOverlay = false,
                loadingMessage = null
            )
        }
    }
}

internal fun PlayerRuntimeController.showToastOnUiThread(message: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
