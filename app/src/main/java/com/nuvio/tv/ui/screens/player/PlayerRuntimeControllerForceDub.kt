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
import com.nuvio.tv.core.dub.AcousticProbeHelper
import com.nuvio.tv.core.dub.AcousticSyncMatcher
import com.nuvio.tv.core.dub.DubSubtitleSyncAligner
import com.nuvio.tv.core.dub.DubSyncEstimator
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import android.os.SystemClock
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import com.nuvio.tv.core.dub.DubDiagnosticFileLogger
import android.content.Context
import android.os.Build
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.AudioAttributes

private const val FORCE_DUB_STREAM_FETCH_TIMEOUT_STARTUP_MS = 3_500L
private const val FORCE_DUB_STREAM_FETCH_TIMEOUT_MENU_MS = 7_000L
private const val FORCE_DUB_VERIFY_TIMEOUT_MS = 20_000L

/**
 * Watchdog de Restauração de Áudio Obrigatório:
 * Garante que a faixa nativa de áudio seja reabilitada e desmutada no ExoPlayer, Libmpv e AudioManager.
 * Deve ser chamado sempre que:
 * (a) um novo vídeo iniciar,
 * (b) o Forçar Dublagem estiver desligado,
 * (c) o usuário desligar o Forçar Dublagem manualmente no menu, ou
 * (d) o Forçar Dublagem falhar / sofrer fallback.
 */
internal fun PlayerRuntimeController.ensureNativeAudioRestored() {
    // 0. Limpa bloqueios persistidos acidentais de áudio desabilitado
    audioDisabledForcedStreamUrls.remove(currentStreamUrl)
    isAudioDisabledForCurrentPlayback = false

    // 1. Android TV / Sistema AudioManager: desmuta STREAM_MUSIC e solicita foco de áudio
    try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }
    } catch (e: Exception) {
        Log.w(PlayerRuntimeController.TAG, "ensureNativeAudioRestored: Falha no AudioManager", e)
    }

    // 2. Libmpv (MPV): desmuta, volume 100%, delay 0, fallback PCM (downmix=no, spdif="") e aid nativo (auto/1)
    mpvView?.let { view ->
        if (currentForceDubActive) {
            view.removeExternalAudioTrack()
        }
        view.ensureNativeAudioRestored()
    }

    // 3. ExoPlayer: volume 1.0f, clearOverrides de áudio e desabilitação desfeita
    _exoPlayer?.let { player ->
        try {
            player.volume = 1.0f
            val currentParams = player.trackSelectionParameters
            val newParams = currentParams.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
            if (currentParams != newParams) {
                player.trackSelectionParameters = newParams
            }
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "ensureNativeAudioRestored: Falha no ExoPlayer", e)
        }
    }

    // Auditoria de saúde do áudio no logger de diagnóstico
    DubDiagnosticFileLogger.recordAudioHealthAudit(
        context = context,
        exoPlayer = _exoPlayer,
        mpvView = mpvView,
        activeAudioUrl = currentForceDubPlan?.audio?.url ?: currentStreamUrl
    )
    Log.i(PlayerRuntimeController.TAG, "ensureNativeAudioRestored: Áudio nativo restaurado com sucesso (AudioManager, ExoPlayer, Libmpv).")
}

internal val PlayerRuntimeController.isExoPlayerEngineActive: Boolean
    get() = _uiState.value.internalPlayerEngine != InternalPlayerEngine.MVP_PLAYER

internal suspend fun PlayerRuntimeController.resolveTargetLanguage(): String {
    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val cid = contentId
    if (isSeries && !cid.isNullOrBlank()) {
        try {
            val fallbackId = navigationArgs.videoId?.substringBefore(":")
                ?: currentVideoId?.substringBefore(":")
            val seriesSettings = seriesCustomSettingsStore.get(cid, profileId, fallbackId = fallbackId)
            val seriesLang = seriesSettings.forceDubTargetLanguage
            if (!seriesLang.isNullOrBlank()) {
                return seriesLang
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
    return playerSettingsDataStore.playerSettings.first().forceDubTargetLanguage
}

internal suspend fun PlayerRuntimeController.isForceDubEffectivelyEnabledForCurrent(): Boolean {
    // Sobrescrita manual do usuário na reprodução atual
    forceDubUserOverridden?.let { return it }

    val settings = playerSettingsDataStore.playerSettings.first()
    val globalEnabled = settings.forceDubEnabled

    // Se for série, verificar configuração específica da série nos detalhes
    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val cid = contentId
    var seriesForceDub: Boolean? = null
    var seriesTargetLang: String? = null

    if (isSeries && !cid.isNullOrBlank()) {
        try {
            val fallbackId = navigationArgs.videoId?.substringBefore(":")
                ?: currentVideoId?.substringBefore(":")
            val seriesSettings = seriesCustomSettingsStore.get(cid, profileId, fallbackId = fallbackId)
            seriesForceDub = seriesSettings.forceDubEnabled
            seriesTargetLang = seriesSettings.forceDubTargetLanguage
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(PlayerRuntimeController.TAG, "Failed to load series settings for force dub: ${e.message}")
        }
    }

    val isEnabled = when (seriesForceDub) {
        true -> true
        false -> false
        null -> globalEnabled
    }
    if (!isEnabled) return false

    // Só é efetivamente ativo se houver um idioma alvo definido (na série ou global)
    val effectiveLang = seriesTargetLang?.takeIf { it.isNotBlank() } ?: settings.forceDubTargetLanguage
    return effectiveLang.isNotBlank()
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
            scope.launch {
                if (isExoPlayerEngineActive) {
                    rebuildMediaSourceToOriginal()
                } else {
                    mpvView?.removeExternalAudioTrack()
                }
                ensureNativeAudioRestored()
                _uiState.update { it.copy(showAudioOverlay = false) }
            }
        }
        _uiState.value.isForceDubLoading -> {
            // Cancelar uma tentativa em andamento
            forceDubUserOverridden = false
            cancelForceDubWork()
            clearForceDubState(stopTorrent = true)
            ensureNativeAudioRestored()
            _uiState.update { it.copy(showAudioOverlay = false) }
        }
        else -> {
            scope.launch {
                val targetLang = resolveTargetLanguage()
                if (targetLang.isBlank()) {
                    showToastOnUiThread(context.getString(R.string.force_dub_no_target_lang_toast))
                    return@launch
                }
                forceDubUserOverridden = true
                startForceDubFromMenu()
            }
        }
    }
}

/** Ativa a dublagem enquanto o vídeo já está tocando (menu de áudio). */
private fun PlayerRuntimeController.startForceDubFromMenu() {
    // Uma nova tentativa manual sempre é permitida.
    forceDubFailedStreamUrls.remove(currentStreamUrl)
    cancelForceDubWork()
    forceDubJob = scope.launch {
        _uiState.update { it.copy(isForceDubEnabled = true, isForceDubLoading = true) }
        try {
            val plan = prepareForceDubPlan(fromMenu = true)
            if (plan == null) {
                clearForceDubState(stopTorrent = true)
                ensureNativeAudioRestored()
                _uiState.update { it.copy(showAudioOverlay = false) }
                return@launch
            }
            applyDubPlanLive(plan)
            showToastOnUiThread(context.getString(R.string.player_loading_dubbed))
            _uiState.update { it.copy(showAudioOverlay = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(PlayerRuntimeController.TAG, "Force dub from menu failed", e)
            failForceDub(restoreOriginal = false)
            _uiState.update { it.copy(showAudioOverlay = false) }
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
    val targetLanguage = resolveTargetLanguage()
    if (targetLanguage.isBlank()) {
        Log.w(PlayerRuntimeController.TAG, "Force dub: target language is empty. Aborting.")
        ensureNativeAudioRestored()
        return null
    }
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

    val initialEngine = if (isExoPlayerEngineActive) "ExoPlayer" else "MPV"
    val contentTitle = currentFilename ?: streamName ?: _uiState.value.currentStreamName
    val cid = contentId ?: navigationArgs.videoId ?: currentVideoId ?: ""
    DubDiagnosticFileLogger.startSession(
        id = cid,
        title = contentTitle.orEmpty(),
        targetLang = targetLanguage,
        engine = initialEngine
    )
    DubDiagnosticFileLogger.recordAudioHealthAudit(
        context = context,
        exoPlayer = _exoPlayer,
        mpvView = mpvView,
        activeAudioUrl = streamUrl
    )
    DubDiagnosticFileLogger.setVideoSourceMetadata(
        name = videoStream.name.orEmpty(),
        addon = videoStream.addonName.orEmpty(),
        url = streamUrl,
        responseMs = 0L,
        statusCode = 200
    )

    // Nível A (Metadados do Stream Principal):
    val videoSearchText = DubOrchestrator.extractStreamSearchText(videoStream, streamUrl)
    val normTarget = DubLanguageDetector.normalizeLanguageCode(targetLanguage)

    if (DubLanguageDetector.isExplicitSingleTargetLanguage(videoSearchText, videoStream.addonName, normTarget)) {
        Log.i(PlayerRuntimeController.TAG, "Forçar Dublagem: Stream principal já possui áudio único '$normTarget' ($videoSearchText). Abortando dublagem externa e tocando áudio nativo.")
        ensureNativeAudioRestored()
        updateForceDubDebugInfo(
            plan = null,
            isActive = false,
            isLoading = false,
            methodOverride = "Áudio Nativo Detectado",
            logicOverride = "Stream já é nacional/dublado ($videoSearchText)",
            winningLayerOverride = "Nível A: Áudio Nativo"
        )
        return null
    }

    if (DubLanguageDetector.isDualOrMultiAudio(videoSearchText)) {
        Log.i(PlayerRuntimeController.TAG, "Forçar Dublagem: Stream principal é Dual/Multi áudio ($videoSearchText). Selecionando faixa interna de português nativamente.")
        ensureNativeAudioRestored()
        updateForceDubDebugInfo(
            plan = null,
            isActive = false,
            isLoading = false,
            methodOverride = "Dual Audio Nativo",
            logicOverride = "Stream possui múltiplas faixas internas; selecionando '$normTarget' nativamente via ExoPlayer.",
            winningLayerOverride = "Nível A: Dual Audio Interno"
        )
        return null
    }

    val videoDurationHintMs = if (fromMenu) {
        playbackTimeline.value.duration.takeIf { it > 0L }
            ?: _exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L }
    } else {
        null
    }

    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val season = currentSeason

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
        seriesId = if (isSeries) cid else null,
        season = if (isSeries) season else null,
        fetchStreams = { collectDubStreamPool(targetLanguage, fromMenu) },
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
                ensureNativeAudioRestored()
                return null
            }
            forceDubPlanCache = plan
            forceDubPlanStreamUrl = streamUrl
            updateForceDubDebugInfo(plan = plan, isActive = false, isLoading = true)
            plan
        }
        DubOutcome.NotNeeded -> {
            discardTorrent()
            ensureNativeAudioRestored()
            null
        }
        DubOutcome.NoCandidate, DubOutcome.Timeout -> {
            discardTorrent()
            if (!fromMenu) forceDubFailedStreamUrls.add(streamUrl)
            currentForceDubActive = false
            forceDubUserOverridden = false
            ensureNativeAudioRestored()
            _uiState.update {
                it.copy(
                    isForceDubActive = false,
                    isForceDubLoading = false,
                    isForceDubEnabled = false,
                    forceDubAudioStreamName = null
                )
            }
            val reason = if (outcome is DubOutcome.Timeout) "TIMEOUT" else "SEM CANDIDATO"
            val msg = if (outcome is DubOutcome.Timeout) {
                "Timeout ao buscar dublagem externa; reproduzindo áudio original."
            } else {
                "Nenhum áudio dublado compatível encontrado; reproduzindo áudio original."
            }
            val dumpPath = DubDiagnosticFileLogger.finalizeAndDump(
                context = context,
                winningLayer = "Fallback: Áudio Nativo ($reason)",
                appliedOffsetMs = 0L,
                confidence = 0f
            )
            updateForceDubDebugInfo(
                plan = null,
                isActive = false,
                isLoading = false,
                methodOverride = "Áudio Original",
                logicOverride = msg,
                winningLayerOverride = "Fallback: Áudio Nativo",
                isFallbackToOriginal = true,
                fallbackReason = reason,
                diagnosticFilePathOverride = dumpPath
            )
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

/** Junta as fontes já carregadas na tela de fontes com uma busca em todos os addons (garantindo diversidade de múltiplos addons). */
private suspend fun PlayerRuntimeController.collectDubStreamPool(
    targetLanguage: String? = null,
    fromMenu: Boolean = false
): List<Stream> {
    val existing = _uiState.value.sourceAllStreams
    val normTarget = targetLanguage?.let { DubLanguageDetector.normalizeLanguageCode(it) }.orEmpty()

    // Se as fontes já existentes na memória tiverem candidatos dublados, reaproveita imediatamente
    if (existing.isNotEmpty() && normTarget.isNotBlank()) {
        val existingDub = existing.filter { DubLanguageDetector.streamMatchesTargetLanguage(it, normTarget) }
        if (existingDub.isNotEmpty()) {
            Log.d(PlayerRuntimeController.TAG, "Dublagem forçada: reaproveitando ${existing.size} fontes existentes (${existingDub.size} dubladas)")
            return existing
        }
    }

    val videoId = currentVideoId ?: contentId ?: return existing
    val type = contentType ?: "movie"

    // 1. Tenta recuperar do cache de busca dos addons (já carregado na tela de detalhes/fontes)
    val cached = streamRepository.getCachedStreamsFromAllAddons(
        type = type,
        videoId = videoId,
        season = currentSeason,
        episode = currentEpisode
    )?.flatMap { it.streams }.orEmpty()

    val combinedInitial = (existing + cached).distinctBy {
        it.getStreamUrl() ?: it.getEffectiveInfoHash() ?: "${it.name}|${it.title}"
    }

    if (normTarget.isNotBlank()) {
        val cachedDub = combinedInitial.filter { DubLanguageDetector.streamMatchesTargetLanguage(it, normTarget) }
        if (cachedDub.isNotEmpty()) {
            Log.d(PlayerRuntimeController.TAG, "Dublagem forçada: reaproveitando ${combinedInitial.size} fontes já em cache (${cachedDub.size} dubladas)")
            return combinedInitial
        }
    }

    val pool = combinedInitial.toMutableList()
    val fetchTimeout = if (fromMenu) FORCE_DUB_STREAM_FETCH_TIMEOUT_MENU_MS else FORCE_DUB_STREAM_FETCH_TIMEOUT_STARTUP_MS
    withTimeoutOrNull(fetchTimeout) {
        var shouldStop = false
        val targetCount = if (fromMenu) 2 else 1
        try {
            streamRepository.getStreamsFromAllAddons(
                type = type,
                videoId = videoId,
                season = currentSeason,
                episode = currentEpisode,
                forceRefresh = false
            ).takeWhile { !shouldStop }.collect { result ->
                if (result is NetworkResult.Success) {
                    val incoming = result.data.flatMap { it.streams }
                    pool.addAll(incoming)
                    if (normTarget.isNotBlank()) {
                        val currentDub = pool.filter { DubLanguageDetector.streamMatchesTargetLanguage(it, normTarget) }
                        if (currentDub.size >= targetCount) {
                            shouldStop = true
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // Early-exit intencional
        }
    }
    return pool.distinctBy {
        it.getStreamUrl() ?: it.getEffectiveInfoHash() ?: "${it.name}|${it.title}"
    }
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
): MediaSource {
    // Convenção padronizada: offsetMs = T_video - T_audio
    currentDubAudioOffsetUs.set(plan.timeline.initialAudioOffsetMs * 1000L)
    return mediaSourceFactory.createDubbedMediaSource(
        context = context,
        videoUrl = currentStreamUrl,
        videoHeaders = currentHeaders,
        audioUrl = plan.audio.url,
        audioHeaders = plan.audio.headers,
        audioOffsetMs = plan.timeline.initialAudioOffsetMs,
        scale = plan.timeline.scale,
        audioDelayUsProvider = audioDelayUs::get,
        dubAudioOffsetUsProvider = currentDubAudioOffsetUs::get,
        subtitleConfigurations = subtitleConfigurations,
        subtitleRoutes = subtitleRoutes,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders,
        mimeTypeOverride = currentStreamMimeType,
        mediaMetadata = buildMediaSessionMetadata(),
        cacheKey = currentStreamCacheKey
    )
}

/** Registra que a dublagem foi aplicada ao player e inicia a verificação de que o áudio trocou. */
internal fun PlayerRuntimeController.markForceDubApplied(plan: DubPlan) {
    currentForceDubActive = true
    currentForceDubPlan = plan
    forceDubVerifiedForPlan = false
    updateForceDubDebugInfo(plan = plan, isActive = false, isLoading = true)
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
    if (!isExoPlayerEngineActive) {
        applyDubPlanLiveMpv(plan)
        return
    }
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

internal fun PlayerRuntimeController.applyDubPlanLiveMpv(plan: DubPlan) {
    val view = mpvView ?: return
    markForceDubApplied(plan)
    view.addExternalAudioTrack(plan.audio.url, plan.timeline.initialAudioOffsetMs, plan.audio.headers)
    view.updateExternalAudioOffset(plan.timeline.initialAudioOffsetMs)
    onForceDubVerified(plan)
    showToastOnUiThread(context.getString(R.string.player_loading_dubbed))
    _uiState.update {
        it.copy(
            isForceDubActive = true,
            isForceDubLoading = false,
            isForceDubEnabled = true,
            forceDubAudioStreamName = plan.audio.stream.name
        )
    }
    startBackgroundOrchestraSync(plan)
}

internal fun PlayerRuntimeController.handleMpvFileLoadedForDub() {
    val plan = pendingMpvDubPlan ?: (if (currentForceDubActive) currentForceDubPlan else null) ?: return
    pendingMpvDubPlan = null
    val view = mpvView ?: return

    Log.i(PlayerRuntimeController.TAG, "FORCE_DUB (MPV): MPV_EVENT_FILE_LOADED recebido. Injetando faixa externa: ${plan.audio.stream.name} (offset=${plan.timeline.initialAudioOffsetMs}ms)")
    scope.launch {
        try {
            markForceDubApplied(plan)
            // 1. Injeta a faixa externa e aplica o offset de áudio no Libmpv
            view.addExternalAudioTrack(plan.audio.url, plan.timeline.initialAudioOffsetMs, plan.audio.headers)

            // 2. Aguarda estabilização para o MPV atualizar a track-list e registrar a faixa externa
            delay(250)
            markMpvTracksStale()
            updateMpvAvailableTracks()

            // 3. Verifica se a faixa externa está selecionada no MPV
            val currentAid = runCatching { view.mpv.getPropertyString("aid") }.getOrNull()
            Log.i(PlayerRuntimeController.TAG, "FORCE_DUB (MPV): aid ativo pós-FILE_LOADED = $currentAid")

            // 4. Aplica rigorosamente o audio-delay
            val offsetSec = plan.timeline.initialAudioOffsetMs / 1000.0
            runCatching { view.mpv.setPropertyDouble("audio-delay", offsetSec) }
            view.updateExternalAudioOffset(plan.timeline.initialAudioOffsetMs)

            // Micro-seek de estabilização pós-carregamento no MPV (sincroniza PTS de áudio e vídeo)
            delay(150)
            val timePos = runCatching { view.mpv.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
            if (timePos > 0.5) {
                runCatching {
                    view.mpv.command("seek", "0", "relative+exact")
                    Log.i(PlayerRuntimeController.TAG, "FORCE_DUB (MPV): Micro-seek de estabilização pós-FILE_LOADED executado em pos=${timePos}s")
                }
            }

            // 5. Confirmação do estado e notificação do Toast ao usuário
            onForceDubVerified(plan)
            showToastOnUiThread(context.getString(R.string.player_loading_dubbed))
            _uiState.update {
                it.copy(
                    isForceDubActive = true,
                    isForceDubLoading = false,
                    isForceDubEnabled = true,
                    forceDubAudioStreamName = plan.audio.stream.name
                )
            }

            // 6. Inicia o refinamento acústico em segundo plano
            startBackgroundOrchestraSync(plan)

        } catch (e: Exception) {
            Log.e(PlayerRuntimeController.TAG, "FORCE_DUB (MPV): falha ao injetar faixa externa após FILE_LOADED", e)
            showToastOnUiThread(context.getString(R.string.force_dub_fallback_toast))
            failForceDub(restoreOriginal = false)
        }
    }
}

/**
 * Chamado a cada mudança de faixas. Em reprodução dublada, força o vídeo da fonte original
 * e o áudio da fonte dublada no idioma pretendido, e confirma o sucesso quando o áudio estiver ativo.
 */
internal fun PlayerRuntimeController.enforceDubTrackSelection(tracks: Tracks) {
    if (!currentForceDubActive) {
        // Se a dublagem externa não estiver ativa, mas o vídeo principal possuir faixa interna no idioma alvo (Dual Audio / Nível B & C):
        checkAndSelectNativeTargetLanguageTrack(tracks)
        return
    }
    val plan = currentForceDubPlan ?: return
    val player = _exoPlayer ?: return

    val builder = player.trackSelectionParameters.buildUpon()
    var changed = false

    // 1. VÍDEO: Garantir que o vídeo venha da fonte original na máxima resolução (4K / máxima fidelidade)
    val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
    if (videoGroups.isNotEmpty()) {
        val currentSelectedVideo = videoGroups.firstOrNull { it.isSelected }
        var bestGroup: Tracks.Group? = null
        var bestTrackIdx = 0
        var bestResolution = -1
        var bestBitrate = -1

        for (group in videoGroups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val res = format.width * format.height
                val bitrate = format.bitrate
                if (res > bestResolution || (res == bestResolution && bitrate > bestBitrate)) {
                    bestResolution = res
                    bestBitrate = bitrate
                    bestGroup = group
                    bestTrackIdx = i
                }
            }
        }

        if (bestGroup != null && (currentSelectedVideo == null || !bestGroup.isSelected || !bestGroup.isTrackSelected(bestTrackIdx))) {
            val existingOverride = player.trackSelectionParameters.overrides[bestGroup.mediaTrackGroup]
            if (existingOverride == null || !existingOverride.trackIndices.contains(bestTrackIdx)) {
                builder.setOverrideForType(TrackSelectionOverride(bestGroup.mediaTrackGroup, bestTrackIdx))
                changed = true
            }
        }
    }

    // 2. ÁUDIO: Seleção da faixa de áudio da fonte dublada no idioma alvo
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isNotEmpty()) {
        val targetNorm = DubLanguageDetector.normalizeLanguageCode(plan.timeline.targetAudioLanguage)
        var bestAudioGroup: Tracks.Group? = null
        var bestAudioIdx = 0
        var bestScore = Int.MIN_VALUE

        for ((groupIndex, group) in audioGroups.withIndex()) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                var score = 0
                val formatLang = DubLanguageDetector.normalizeLanguageCode(format.language)
                if (targetNorm.isNotBlank() && formatLang == targetNorm) {
                    score += 100
                }
                val label = format.label.orEmpty().lowercase()
                if (label.contains("dublado") || label.contains("dub")) score += 20
                if (label.contains("commentary") || label.contains("comentário")) score -= 50
                if (format.channelCount in 1..2) score += 1
                // No MergingMediaSource, as faixas do segundo source (áudio dublado) vêm depois no array de grupos.
                // Prioridade máxima para grupos do container secundário para garantir que o áudio primário NUNCA toque:
                if (groupIndex == audioGroups.size - 1) score += 500
                score += groupIndex * 50

                if (score > bestScore) {
                    bestScore = score
                    bestAudioGroup = group
                    bestAudioIdx = i
                }
            }
        }

        if (bestAudioGroup != null) {
            if (bestAudioGroup.isSelected && bestAudioGroup.isTrackSelected(bestAudioIdx)) {
                onForceDubVerified(plan)
            } else {
                val existingOverride = player.trackSelectionParameters.overrides[bestAudioGroup.mediaTrackGroup]
                if (existingOverride == null || !existingOverride.trackIndices.contains(bestAudioIdx)) {
                    builder.setOverrideForType(TrackSelectionOverride(bestAudioGroup.mediaTrackGroup, bestAudioIdx))
                    changed = true
                }
            }
        } else if (audioGroups.any { it.isSelected }) {
            onForceDubVerified(plan)
        }
    }

    if (changed) {
        player.trackSelectionParameters = builder.build()
    }
}

private fun PlayerRuntimeController.onForceDubVerified(plan: DubPlan) {
    if (forceDubVerifiedForPlan) return
    forceDubVerifiedForPlan = true
    forceDubVerifyJob?.cancel()
    forceDubVerifyJob = null
    updateForceDubDebugInfo(plan = plan, isActive = true, isLoading = false)
    _uiState.update { it.copy(isForceDubLoading = false, isForceDubActive = true) }
    
    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val cid = contentId ?: navigationArgs.videoId?.substringBefore(":") ?: currentVideoId?.substringBefore(":")
    val season = currentSeason
    // Regra 1: Só guarda em cache após sucesso real confirmado (confiança >= 65%)
    if (plan.timeline.confidence >= 0.65f && !plan.timeline.isBlind) {
        DubResultCache.put(plan.videoCacheKey, plan.audioCacheKey, plan.timeline, cid)
        if (isSeries && !cid.isNullOrBlank() && season != null) {
            val videoTitle = currentFilename ?: streamName ?: _uiState.value.currentStreamName
            val audioTitle = plan.audio.stream.name ?: plan.audio.stream.title
            DubResultCache.putSeasonPackSync(cid, season, videoTitle, audioTitle, plan.timeline)
        }
    }
    Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: áudio dublado ativo (${plan.audio.stream.name})")

    // Dispara a afinação milimétrica da Orchestra em background sem travar a reprodução
    startBackgroundOrchestraSync(plan)
}

/**
 * Atualiza suavemente o deslocamento do áudio dublado em tempo de execução
 * quando a Orchestra encontra uma sincronia refinada por legendas ou análise acústica.
 */
internal fun PlayerRuntimeController.applyRefinedDubAudioOffset(
    refinedOffsetMs: Long,
    confidence: Float,
    plan: DubPlan,
    methodName: String = "Orchestra Dinâmica (Refinada)",
    logicDescription: String = "",
    tVideoMs: Long? = null,
    tAudioMs: Long? = null,
    acousticNccScore: Float? = null,
    winningLayer: String = ""
) {
    if (!currentForceDubActive) return
    // Regra 2: Zona Morta de Sincronia Nativa (Deadzone = +-650 ms)
    // Se o offset analítico for menor que 650ms, crava obrigatoriamente em 0ms
    val effectiveOffsetMs = if (kotlin.math.abs(refinedOffsetMs) < 650L) 0L else refinedOffsetMs

    val oldOffsetMs = currentDubAudioOffsetUs.get() / 1000L
    val diff = kotlin.math.abs(effectiveOffsetMs - oldOffsetMs)
    // Convenção padronizada: offsetMs = T_video - T_audio
    currentDubAudioOffsetUs.set(effectiveOffsetMs * 1000L)
    mpvView?.updateExternalAudioOffset(effectiveOffsetMs)

    // Se o offset variou mais de 80ms, força o flush da SampleQueue do ExoPlayer ou micro-seek no MPV
    if (diff > 80L) {
        val player = _exoPlayer
        if (player != null && isExoPlayerEngineActive) {
            val currentPos = player.currentPosition
            player.seekTo(currentPos)
            Log.i(
                PlayerRuntimeController.TAG,
                "FORCE_DUB: micro-seek executado em pos=${currentPos}ms para flush da SampleQueue com novo offset=${effectiveOffsetMs}ms (diff=${diff}ms)"
            )
        } else if (!isExoPlayerEngineActive && mpvView != null) {
            val view = mpvView
            val timePos = runCatching { view?.mpv?.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
            if (timePos > 0.5) {
                runCatching {
                    view?.mpv?.command("seek", "0", "relative+exact")
                    Log.i(PlayerRuntimeController.TAG, "FORCE_DUB (MPV): micro-seek executado em pos=${timePos}s para flush de offset=${effectiveOffsetMs}ms (diff=${diff}ms)")
                }
            }
        }
    }

    val updatedTimeline = plan.timeline.copy(
        initialAudioOffsetMs = refinedOffsetMs,
        confidence = confidence,
        isBlind = false
    )
    val updatedPlan = plan.copy(
        timeline = updatedTimeline,
        methodName = methodName,
        logicDescription = logicDescription.ifBlank { "Refinamento milimétrico durante reprodução (offset=${refinedOffsetMs}ms, conf=${String.format(java.util.Locale.US, "%.0f%%", confidence * 100)})" },
        tVideoMs = tVideoMs ?: plan.tVideoMs,
        tAudioMs = tAudioMs ?: plan.tAudioMs,
        acousticNccScore = acousticNccScore ?: plan.acousticNccScore,
        winningLayer = winningLayer.ifBlank { plan.winningLayer }
    )
    currentForceDubPlan = updatedPlan

    DubDiagnosticFileLogger.recordAudioHealthAudit(
        context = context,
        exoPlayer = _exoPlayer,
        mpvView = mpvView,
        activeAudioUrl = updatedPlan.audio.url
    )

    val dumpPath = DubDiagnosticFileLogger.finalizeAndDump(
        context = context,
        winningLayer = winningLayer.ifBlank { methodName },
        appliedOffsetMs = refinedOffsetMs,
        confidence = confidence
    )

    updateForceDubDebugInfo(
        plan = updatedPlan,
        isActive = true,
        isLoading = false,
        methodOverride = methodName,
        logicOverride = updatedPlan.logicDescription,
        offsetOverride = refinedOffsetMs,
        confidenceOverride = confidence,
        tVideoOverride = tVideoMs,
        tAudioOverride = tAudioMs,
        nccOverride = acousticNccScore,
        winningLayerOverride = winningLayer,
        diagnosticFilePathOverride = dumpPath
    )
    val isSeries = contentType?.lowercase() in listOf("series", "tv")
    val cid = contentId ?: navigationArgs.videoId?.substringBefore(":") ?: currentVideoId?.substringBefore(":")
    val season = currentSeason
    if (confidence >= 0.65f) {
        DubResultCache.put(plan.videoCacheKey, plan.audioCacheKey, updatedTimeline, cid)
        if (isSeries && !cid.isNullOrBlank() && season != null) {
            val videoTitle = currentFilename ?: streamName ?: _uiState.value.currentStreamName
            val audioTitle = plan.audio.stream.name ?: plan.audio.stream.title
            DubResultCache.putSeasonPackSync(cid, season, videoTitle, audioTitle, updatedTimeline)
        }
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "FORCE_DUB: Orchestra afinou a sincronia dinamicamente (offset=${refinedOffsetMs}ms, conf=${confidence}, layer=$winningLayer, dump=$dumpPath)"
    )
}

/**
 * Permite ao usuário sobrescrever manualmente o offset da dublagem (em milissegundos)
 * com aplicação instantânea no MPV e ExoPlayer, cancelando jobs de background e persistindo.
 */
internal fun PlayerRuntimeController.manualApplyDubAudioOffset(offsetMs: Long) {
    if (!currentForceDubActive) return
    forceDubOrchestraJob?.cancel()
    forceDubOrchestraJob = null
    val currentPlan = currentForceDubPlan ?: return
    applyRefinedDubAudioOffset(
        refinedOffsetMs = offsetMs,
        confidence = 1.0f,
        plan = currentPlan,
        methodName = "Ajuste Manual do Usuário",
        logicDescription = "Offset ajustado manualmente pelo usuário para ${offsetMs}ms",
        winningLayer = "Manual: Override do Usuário"
    )
}

/**
 * Reseta o offset da dublagem imediatamente para 0ms.
 */
internal fun PlayerRuntimeController.resetForceDubOffset() {
    manualApplyDubAudioOffset(0L)
}

/**
 * Conecta a Orchestra às legendas reais do player:
 * PRIORIDADE 1 ABSOLUTA: Trilhas de texto embutidas no container do vídeo via EmbeddedSubtitleTimelineLoader (subrip, ssa, vtt).
 * O idioma da legenda do vídeo NÃO importa (en, pt, es, fr...), pois serve exclusivamente para mapear quando a boca se move.
 * PRIORIDADE 2: Apenas se o container de vídeo tiver 0 faixas embutidas de texto, recorre a legendas de addons (OpenSubtitles, etc.).
 */
internal suspend fun PlayerRuntimeController.collectAvailableVideoSubtitleCues(): Pair<String, List<SubtitleSyncCue>> {
    val videoUrl = currentStreamUrl
    val videoHeaders = currentHeaders

    var embeddedCount = 0
    var externalCount = 0

    // 0. PRIORIDADE ABSOLUTA Nº 0: Legendas embutidas extraídas no mesmo passe pelo MatroskaExtractor
    if (videoUrl.isNotBlank() && AcousticProbeHelper.lastExtractedVideoEmbeddedCues.isEmpty()) {
        val waitStart = System.currentTimeMillis()
        while (AcousticProbeHelper.lastExtractedVideoEmbeddedCues.isEmpty() && (System.currentTimeMillis() - waitStart) < 2200L) {
            delay(100)
        }
    }
    if (AcousticProbeHelper.lastExtractedVideoEmbeddedCues.isNotEmpty()) {
        val cues = AcousticProbeHelper.lastExtractedVideoEmbeddedCues
        val langLabel = AcousticProbeHelper.lastExtractedVideoEmbeddedLanguage ?: "und"
        val origin = "$langLabel(embutida-matroska)"
        Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: Prioridade 1 Absoluta: Legenda EMBUTIDA no container extraída via MatroskaExtractor ($origin com ${cues.size} falas)")
        DubDiagnosticFileLogger.setSubtitlesAudit(
            embeddedCount = cues.size,
            externalCount = _uiState.value.addonSubtitles.size,
            chosenOrigin = origin,
            chosenLang = langLabel,
            cues = cues
        )
        return origin to cues
    }

    // 1. PRIORIDADE ABSOLUTA Nº 1: Trilhas de legenda de texto EMBUTIDAS no container do vídeo
    if (videoUrl.isNotBlank()) {
        val embeddedTimeline = withTimeoutOrNull(3500L) {
            EmbeddedSubtitleTimelineLoader.load(videoUrl, videoHeaders)
        }
        if (embeddedTimeline != null && embeddedTimeline.tracks.isNotEmpty()) {
            embeddedCount = embeddedTimeline.tracks.size
            val bestTrack = embeddedTimeline.tracks.maxByOrNull { it.cues.size }
            if (bestTrack != null && bestTrack.cues.size >= 5) {
                val langLabel = bestTrack.language ?: bestTrack.label ?: "subrip"
                val origin = "$langLabel(embutida)"
                Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: Prioridade 1 Absoluta: Legenda EMBUTIDA no container selecionada ($origin com ${bestTrack.cues.size} falas)")
                DubDiagnosticFileLogger.setSubtitlesAudit(
                    embeddedCount = embeddedCount,
                    externalCount = _uiState.value.addonSubtitles.size,
                    chosenOrigin = origin,
                    chosenLang = langLabel,
                    cues = bestTrack.cues
                )
                return origin to bestTrack.cues
            }
        }
    }

    // 2. PRIORIDADE 2: Apenas se o container de vídeo tiver 0 faixas de texto embutidas:
    val existingCues = _uiState.value.subtitleAutoSyncCues.ifEmpty { xrayBackgroundCues }
    if (existingCues.size >= 5) {
        val origin = "AutoSync/X-Ray(externa)"
        Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: Legenda externa em memória utilizada (${existingCues.size} falas)")
        DubDiagnosticFileLogger.setSubtitlesAudit(
            embeddedCount = embeddedCount,
            externalCount = _uiState.value.addonSubtitles.size.coerceAtLeast(1),
            chosenOrigin = origin,
            chosenLang = "externa",
            cues = existingCues
        )
        return origin to existingCues
    }

    // 3. Aguarda até 2.000 ms se addons externos ainda estiverem buscando legendas
    if (_uiState.value.isLoadingAddonSubtitles) {
        val waitStart = System.currentTimeMillis()
        while (_uiState.value.isLoadingAddonSubtitles && (System.currentTimeMillis() - waitStart) < 2000L) {
            delay(100)
        }
    }

    // 4. Legendas externas carregadas pelos addons de legenda
    val addonSubs = _uiState.value.addonSubtitles
    externalCount = addonSubs.size
    if (addonSubs.isNotEmpty()) {
        val preferredLang = (contentLanguage?.ifBlank { "pt" } ?: "pt").lowercase()
        val chosenSub = addonSubs.firstOrNull { it.lang.lowercase().startsWith(preferredLang) }
            ?: addonSubs.firstOrNull { it.lang.lowercase().startsWith("en") }
            ?: addonSubs.first()

        val body = runCatching {
            withTimeoutOrNull(2500L) {
                com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync.downloadSubtitleBody(
                    url = chosenSub.url,
                    headers = chosenSub.headers ?: emptyMap(),
                    languageHint = chosenSub.lang
                )
            }
        }.getOrNull()

        if (!body.isNullOrBlank()) {
            val parsed = PlayerSubtitleCueParser.parseFromText(body, chosenSub.url)
            if (parsed.size >= 5) {
                val origin = "${chosenSub.addonName.ifBlank { "OpenSubtitles" }}(externa)"
                Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: Legenda externa de addon carregada ($origin com ${parsed.size} cues)")
                DubDiagnosticFileLogger.setSubtitlesAudit(
                    embeddedCount = embeddedCount,
                    externalCount = externalCount,
                    chosenOrigin = origin,
                    chosenLang = chosenSub.lang,
                    cues = parsed
                )
                return origin to parsed
            }
        }
    }

    DubDiagnosticFileLogger.setSubtitlesAudit(
        embeddedCount = embeddedCount,
        externalCount = externalCount,
        chosenOrigin = "Nenhuma",
        chosenLang = "",
        cues = emptyList<SubtitleSyncCue>()
    )
    return "" to emptyList()
}

internal fun PlayerRuntimeController.onUserSeekOccurred() {
    if (forceDubOrchestraJob?.isActive == true) {
        Log.i(PlayerRuntimeController.TAG, "FORCE_DUB: Usuário realizou seek; cancelando sonda acústica/VAD em background para priorizar reprodução")
        forceDubOrchestraJob?.cancel()
        forceDubOrchestraJob = null
    }
}

/**
 * Maestro de Sincronia da Orchestra executado em segundo plano enquanto o vídeo já está rodando.
 * Conecta o Sub-Bridge às legendas reais e aciona o novo motor Cross-Modal Sub-to-Dub VAD
 * (sincroniza a legenda do vídeo diretamente com o envelope de voz da dublagem).
 */
internal fun PlayerRuntimeController.startBackgroundOrchestraSync(plan: DubPlan) {
    if (plan.timeline.confidence >= 0.90f && !plan.timeline.isBlind) return
    forceDubOrchestraJob?.cancel()
    forceDubOrchestraJob = scope.launch(Dispatchers.IO) {
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }

        // 1. Prioridade para o Player Iniciar Primeiro:
        // Aguarda o player renderizar o primeiro frame ou atingir STATE_READY antes de disparar
        // as conexões HTTP pesadas da sonda acústica, evitando concorrência e throttling de rede no segundo zero.
        val waitStartWallMs = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - waitStartWallMs < 8000L) {
            if (hasRenderedFirstFrame) {
                break
            }
            val isPlayerReady = withContext(Dispatchers.Main) {
                val exoReady = _exoPlayer?.let { it.playbackState == Player.STATE_READY } == true
                val mpvReady = mpvView?.let { it.isInitialized } == true
                hasRenderedFirstFrame || exoReady || mpvReady
            }
            if (isPlayerReady) {
                break
            }
            delay(150)
        }
        // Grace delay para o buffer inicial do player assentar antes das conexões de sonda
        delay(600)

        val videoUrl = currentStreamUrl
        val videoHeaders = currentHeaders
        val audioUrl = plan.audio.url
        val audioHeaders = plan.audio.headers

        // 2. Execução 100% Paralela das 3 tarefas sob coroutineScope com timeout curto (<= 4.5s)
        val parallelResult: Pair<Pair<String, List<SubtitleSyncCue>>, AcousticSyncMatcher.AcousticMatch?> = coroutineScope {
            val subDeferred = async(Dispatchers.IO) {
                collectAvailableVideoSubtitleCues()
            }
            val acousticDeferred = async(Dispatchers.IO) {
                AcousticProbeHelper.matchAcousticOffset(
                    videoUrl = videoUrl,
                    videoHeaders = videoHeaders,
                    dubAudioUrl = audioUrl,
                    dubAudioHeaders = audioHeaders,
                    onStatusUpdate = { status ->
                        scope.launch(Dispatchers.Main) {
                            updateForceDubDebugInfo(
                                plan = currentForceDubPlan ?: plan,
                                isActive = currentForceDubActive,
                                isLoading = true,
                                logicOverride = status
                            )
                        }
                    }
                )
            }
            Pair(subDeferred.await(), acousticDeferred.await())
        }

        val subOrigin = parallelResult.first.first
        val videoCues = parallelResult.first.second
        val acousticMatch = parallelResult.second

        // 3. Se houver legendas para ambas as fontes (vídeo e dublagem), tenta alinhamento mútuo RANSAC
        if (videoCues.isNotEmpty()) {
            val subtitleMatch = DubSubtitleSyncAligner.alignBySubtitles(
                videoUrl = videoUrl,
                videoHeaders = videoHeaders,
                dubAudioUrl = audioUrl,
                dubAudioHeaders = audioHeaders,
                externalVideoCues = videoCues
            )

            val ransacPassed = subtitleMatch != null && subtitleMatch.confidence >= 0.65f
            DubDiagnosticFileLogger.recordCorrelationResult(
                methodName = "Sub-Bridge Legendas (RANSAC)",
                searchWindow = "Cues Vídeo (${videoCues.size}) vs Cues Dublagem",
                bestLagMs = subtitleMatch?.initialAudioOffsetMs ?: 0L,
                zncc = subtitleMatch?.confidence ?: 0f,
                psr = (subtitleMatch?.matchedCues ?: 0).toFloat(),
                topLags = emptyList(),
                passed = ransacPassed,
                decisionReason = if (ransacPassed) "Consenso de ${subtitleMatch?.matchedCues} falas" else "Sem consenso RANSAC"
            )

            if (ransacPassed && subtitleMatch != null) {
                val winLayer = "Camada 3: Sub-Bridge Legendas (RANSAC)"
                val logic = "Consenso de ${subtitleMatch.matchedCues} falas (offset=${subtitleMatch.initialAudioOffsetMs}ms, conf=${String.format(java.util.Locale.US, "%.0f%%", subtitleMatch.confidence * 100)})"
                withContext(Dispatchers.Main) {
                    applyRefinedDubAudioOffset(
                        refinedOffsetMs = subtitleMatch.initialAudioOffsetMs,
                        confidence = subtitleMatch.confidence,
                        plan = plan,
                        methodName = "Sub-Bridge Legendas (RANSAC)",
                        logicDescription = logic,
                        winningLayer = winLayer
                    )
                }
                return@launch
            }
        }

        val isBitstream = acousticMatch?.telemetryDiag?.contains("Bitstream", ignoreCase = true) == true
        val acousticValid = acousticMatch != null && acousticMatch.isValid
        val acousticPsr = acousticMatch?.psr ?: 0f

        DubDiagnosticFileLogger.recordCorrelationResult(
            methodName = "Acoustic M&E",
            searchWindow = "0..3000 bins (0..30s)",
            bestLagMs = acousticMatch?.offsetMs ?: 0L,
            zncc = acousticMatch?.maxZncc ?: 0f,
            psr = acousticMatch?.psr ?: 0f,
            topLags = acousticMatch?.topLags ?: emptyList(),
            passed = acousticValid,
            decisionReason = acousticMatch?.telemetryDiag ?: "Sem correlação acústica válida"
        )

        // 3. Competição de Maior PSR e Resiliência Multi-Camadas (FFSubSync Morfológico + Onset Anchor + Bumper):
        var vadMatch: AcousticSyncMatcher.AcousticMatch? = null
        val dubEnvelope = acousticMatch?.dubSpeechEnvelope
        if (videoCues.isNotEmpty() && dubEnvelope != null) {
            vadMatch = DubSubtitleSyncAligner.alignCrossModal(
                videoCues = videoCues,
                dubSpeechEnvelope = dubEnvelope,
                subOrigin = subOrigin.ifBlank { "subrip" },
                dubMime = acousticMatch.dubMime?.substringAfter('/') ?: "mp4a-latm"
            )
            DubDiagnosticFileLogger.recordCorrelationResult(
                methodName = "Sub-Bridge VAD (Alass)",
                searchWindow = "Cross-Modal 100Hz",
                bestLagMs = vadMatch?.offsetMs ?: 0L,
                zncc = vadMatch?.maxZncc ?: 0f,
                psr = vadMatch?.psr ?: 0f,
                topLags = vadMatch?.topLags ?: emptyList(),
                passed = vadMatch?.isValid == true,
                decisionReason = vadMatch?.telemetryDiag ?: "Sem correlação VAD"
            )
        }

        // Ancoragem Direta por Início de Diálogo (Dialogue Onset Anchor)
        var onsetMatch: AcousticSyncMatcher.AcousticMatch? = null
        if (videoCues.isNotEmpty() && dubEnvelope != null) {
            onsetMatch = DubSubtitleSyncAligner.estimateDialogueOnsetOffset(
                videoCues = videoCues,
                dubSpeechEnvelope = dubEnvelope,
                subOrigin = subOrigin.ifBlank { "subrip" },
                dubMime = acousticMatch.dubMime?.substringAfter('/') ?: "mp4a-latm"
            )
            DubDiagnosticFileLogger.recordCorrelationResult(
                methodName = "Dialogue Onset Anchor",
                searchWindow = "Início de Falas",
                bestLagMs = onsetMatch?.offsetMs ?: 0L,
                zncc = onsetMatch?.maxZncc ?: 0f,
                psr = onsetMatch?.psr ?: 0f,
                topLags = onsetMatch?.topLags ?: emptyList(),
                passed = onsetMatch?.isValid == true,
                decisionReason = onsetMatch?.telemetryDiag ?: "Sem ancoragem Onset"
            )
        }

        val vadValid = vadMatch != null && vadMatch.isValid
        val vadPsr = vadMatch?.psr ?: 0f

        val cleanVideoCues = DubSubtitleSyncAligner.sanitizeCues(videoCues).sortedBy { it.startTimeMs }
        val firstSubOnsetMs = cleanVideoCues.firstOrNull { it.startTimeMs in 500L..30_000L }?.startTimeMs
        val sustainedDubOnsetMs = if (dubEnvelope != null) {
            AcousticSyncMatcher.detectSustainedDialogueOnsetMs(dubEnvelope, windowMs = 10)
        } else null

        val isVideoBitstream = isBitstream || (acousticMatch?.telemetryDiag?.contains("Bitstream", ignoreCase = true) == true)
        val bumperDeltaMs = if (firstSubOnsetMs != null && sustainedDubOnsetMs != null) {
            val raw = firstSubOnsetMs - sustainedDubOnsetMs
            if (abs(raw) < 650L) 0L else raw
        } else if (isVideoBitstream) {
            null // Proibição em Bitstream: jamais aceitar falso silêncio de pacotes comprimidos
        } else {
            val silenceOffset = acousticMatch?.silenceOffsetMs
            if (silenceOffset != null && abs(silenceOffset) < 650L) 0L else silenceOffset
        }

        // Um bumper válido tem magnitude macroscópica (>= 750ms). Se for 0ms (deadzone), é sincronia nativa!
        val bumperPassed = bumperDeltaMs != null && (bumperDeltaMs == 0L || abs(bumperDeltaMs) in 750L..15_000L)
        val bumperReason = if (firstSubOnsetMs != null && sustainedDubOnsetMs != null) {
            val raw = firstSubOnsetMs - sustainedDubOnsetMs
            "T_sub_onset=${firstSubOnsetMs}ms, T_speech_dub=${sustainedDubOnsetMs}ms, Δ=${raw}ms${if (abs(raw) < 650L) " -> Deadzone 0ms" else ""}"
        } else if (bumperPassed) {
            "Δ=${bumperDeltaMs}ms"
        } else {
            "Sem Bumper válido"
        }

        DubDiagnosticFileLogger.recordCorrelationResult(
            methodName = "Bumper Boundary Detector",
            searchWindow = "0..1800 bins (18s)",
            bestLagMs = bumperDeltaMs ?: 0L,
            zncc = if (bumperPassed) 0.90f else 0f,
            psr = if (bumperPassed) 5.0f else 0f,
            topLags = emptyList(),
            passed = bumperPassed,
            decisionReason = bumperReason
        )

        // Decisão 1: Acústica M&E direta (Sinal de Áudio do Vídeo vs Áudio da Dublagem) quando válida
        if (acousticMatch != null && acousticMatch.isValid) {
            val nccScore = acousticMatch.maxZncc * 100f
            val winLayer = "Camada 4: Acústica M&E (ZNCC ${String.format(java.util.Locale.US, "%.2f", acousticMatch.maxZncc)} | PSR ${String.format(java.util.Locale.US, "%.1f", acousticMatch.psr)})"
            val logic = "Alinhamento M&E cravou offset=${acousticMatch.offsetMs}ms ${acousticMatch.telemetryDiag}"
            withContext(Dispatchers.Main) {
                applyRefinedDubAudioOffset(
                    refinedOffsetMs = acousticMatch.offsetMs,
                    confidence = acousticMatch.confidence,
                    plan = plan,
                    methodName = "Correlação Acústica M&E",
                    logicDescription = logic,
                    tVideoMs = acousticMatch.tVideoMs,
                    tAudioMs = acousticMatch.tAudioMs,
                    acousticNccScore = nccScore,
                    winningLayer = winLayer
                )
            }
            return@launch
        }

        // Decisão 2: Sub-Bridge VAD Morfológico com maior destaque estatístico
        if (vadMatch != null && vadMatch.isValid) {
            val winLayer = "Camada 3.5: ${vadMatch.telemetryDiag}"
            val logic = "Sub-Bridge VAD (Alass) venceu com PSR ${String.format(java.util.Locale.US, "%.1f", vadPsr)} vs Acústica ${String.format(java.util.Locale.US, "%.1f", acousticPsr)}"
            withContext(Dispatchers.Main) {
                applyRefinedDubAudioOffset(
                    refinedOffsetMs = vadMatch.offsetMs,
                    confidence = vadMatch.confidence,
                    plan = plan,
                    methodName = "Sub-Bridge VAD (Alass)",
                    logicDescription = logic,
                    tVideoMs = vadMatch.tVideoMs,
                    tAudioMs = vadMatch.tAudioMs,
                    acousticNccScore = vadMatch.maxZncc * 100f,
                    winningLayer = winLayer
                )
            }
            return@launch
        }

        // Decisão 3: Dialogue Onset Anchor (Compara início de fala da legenda com onset vocal da dublagem)
        if (onsetMatch != null && onsetMatch.isValid) {
            val winLayer = "Camada 3.2: ${onsetMatch.telemetryDiag}"
            val logic = "Dialogue Onset Anchor alinhou início de fala em ${onsetMatch.offsetMs}ms (${videoCues.size} falas analisadas)"
            withContext(Dispatchers.Main) {
                applyRefinedDubAudioOffset(
                    refinedOffsetMs = onsetMatch.offsetMs,
                    confidence = onsetMatch.confidence,
                    plan = plan,
                    methodName = "Dialogue Onset Anchor",
                    logicDescription = logic,
                    tVideoMs = onsetMatch.tVideoMs,
                    tAudioMs = onsetMatch.tAudioMs,
                    acousticNccScore = onsetMatch.maxZncc * 100f,
                    winningLayer = winLayer
                )
            }
            return@launch
        }

        // Decisão 4: Detecção de Bumper nos primeiros 10s-18s (Onsets Vinheta -> Silêncio -> Fala)
        if (bumperPassed && bumperDeltaMs != null) {
            val effectiveBumper = if (abs(bumperDeltaMs) < 650L) 0L else bumperDeltaMs
            if (isVideoBitstream && firstSubOnsetMs == null) {
                Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: Bumper ignorado em vídeo Bitstream sem legenda para evitar falso silêncio")
            } else {
                val winLayer = "Camada 2.5: Bumper Boundary Detector ($bumperReason)"
                val logic = "Detecção de transição pós-vinheta cravou offset=${effectiveBumper}ms"
                withContext(Dispatchers.Main) {
                    applyRefinedDubAudioOffset(
                        refinedOffsetMs = effectiveBumper,
                        confidence = 0.85f,
                        plan = plan,
                        methodName = "Bumper Boundary Detector",
                        logicDescription = logic,
                        winningLayer = winLayer
                    )
                }
                return@launch
            }
        }

        // REMOVIDO: Heurística de Arquétipo de Release (BluRay vs Web/TV +2100ms)
        // Se nenhuma camada analítica atingir o limiar estatístico, o offset base é estritamente mantido.

        // 4. Se todas as tentativas de refinamento falharem:
        // O candidato atual é considerado Incompatível / Episódio Incorreto.
        val remaining = plan.remainingCandidates
        if (remaining.isNotEmpty()) {
            val nextStream = remaining.first()
            val nextRemaining = remaining.drop(1)
            Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: Candidato '${plan.audio.stream.name}' incompatível/episódio incorreto. Testando próximo candidato: '${nextStream.name}' (${nextRemaining.size} restantes)")

            withContext(Dispatchers.Main) {
                updateForceDubDebugInfo(
                    plan = plan,
                    isActive = currentForceDubActive,
                    isLoading = true,
                    logicOverride = "Candidato incompatível. Testando: ${nextStream.name.orEmpty()}..."
                )
            }

            val currentVideo = _uiState.value.sourceAllStreams.firstOrNull { it.getStreamUrl() == videoUrl } ?: Stream(
                name = currentFilename ?: _uiState.value.currentStreamName,
                title = currentFilename,
                description = currentStreamDescription,
                url = videoUrl,
                ytId = null,
                infoHash = currentInfoHash,
                fileIdx = currentFileIdx,
                externalUrl = null,
                behaviorHints = if (videoHeaders.isNotEmpty()) {
                    StreamBehaviorHints(
                        notWebReady = false,
                        bingeGroup = null,
                        countryWhitelist = null,
                        proxyHeaders = ProxyHeaders(
                            request = videoHeaders,
                            response = currentStreamResponseHeaders.ifEmpty { null }
                        )
                    )
                } else null,
                addonName = currentAddonName ?: "Default",
                addonLogo = currentAddonLogo
            )
            var nextTorrentStarted = false
            val currentDurationMs = playbackTimeline.value.duration.takeIf { it > 0L }
                ?: _exoPlayer?.duration?.takeIf { it != C.TIME_UNSET && it > 0L }
            val nextPlan = DubOrchestrator.evaluateCandidateDirect(
                candidateStream = nextStream,
                videoStream = currentVideo,
                videoUrl = videoUrl,
                videoHeaders = videoHeaders,
                videoDurationMs = currentDurationMs,
                targetLanguage = plan.timeline.targetAudioLanguage?.ifBlank { resolveTargetLanguage() } ?: resolveTargetLanguage(),
                videoStreamTitle = plan.videoStreamTitle,
                remainingList = nextRemaining,
                resolveAudio = { c -> resolveDubAudio(c) { nextTorrentStarted = true } }
            )

            if (nextPlan != null) {
                withContext(Dispatchers.Main) {
                    applyDubPlanLive(nextPlan)
                    startBackgroundOrchestraSync(nextPlan)
                }
                return@launch
            }
        }

        // Se todos os candidatos falharem a sincronização estrita:
        // Desativa a forçagem de dublagem, volta para o áudio original do vídeo e avisa o usuário via Toast:
        Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: Todos os candidatos dublados foram rejeitados por incompatibilidade temporal. Desativando forçagem e voltando ao áudio original.")
        val dumpPath = DubDiagnosticFileLogger.finalizeAndDump(
            context = context,
            winningLayer = "Fallback: Áudio Original (Dublagem Incompatível)",
            appliedOffsetMs = 0L,
            confidence = 0f
        )
        withContext(Dispatchers.Main) {
            failForceDub(restoreOriginal = true, customToast = context.getString(R.string.force_dub_incompatible))
        }
    }
}

/** Erro de reprodução enquanto a dublagem está ativa: se for erro de decoder de vídeo, migra para MPV; se for áudio/rede da dublagem, volta ao original. */
internal fun PlayerRuntimeController.handleForceDubPlaybackError(
    error: androidx.media3.common.PlaybackException,
    allowEngineFailover: Boolean
): Boolean {
    if (!currentForceDubActive) return false
    val detailedError = error.toDisplayMessage(context)
    val errorCodeName = error.errorCodeName

    // Erros da fonte secundária de áudio (decodificação de áudio, I/O ou parsing da URL dublada)
    // NÃO devem disparar o failover de vídeo do ExoPlayer -> Libmpv!
    val isAudioOrDubbingError = detailedError.contains("audio", ignoreCase = true) ||
        errorCodeName.contains("AUDIO", ignoreCase = true) ||
        errorCodeName.contains("PARSER", ignoreCase = true) ||
        errorCodeName.contains("IO", ignoreCase = true) ||
        error.cause?.message?.contains("audio", ignoreCase = true) == true

    val isVideoDecoderCapabilityError = !isAudioOrDubbingError &&
        currentInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER &&
        (detailedError.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
         detailedError.contains("ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES", ignoreCase = true) ||
         detailedError.contains("ERROR_CODE_DECODER_INIT_FAILED", ignoreCase = true) ||
         detailedError.contains("ERROR_CODE_DECODING_FAILED", ignoreCase = true))

    if (isVideoDecoderCapabilityError) {
        Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: erro de decoder de VÍDEO com dublagem ativa; migrando automaticamente para MPV")
        if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
            return true
        }
    }

    Log.w(PlayerRuntimeController.TAG, "FORCE_DUB: falha isolada na faixa de áudio da dublagem ($detailedError); revertendo suavemente para áudio nativo do vídeo")
    failForceDub(restoreOriginal = true)
    return true
}

/**
 * Falha na dublagem: reseta o estado, avisa com toast e (opcionalmente) reconstrói a fonte original.
 * O stream fica marcado como falho para que reinícios do player não tentem de novo (sem loop).
 */
internal fun PlayerRuntimeController.failForceDub(restoreOriginal: Boolean, customToast: String? = null) {
    forceDubFailedStreamUrls.add(currentStreamUrl)
    forceDubUserOverridden = false
    cancelForceDubWork()
    clearForceDubState(stopTorrent = true)
    ensureNativeAudioRestored()

    val dumpPath = DubDiagnosticFileLogger.finalizeAndDump(
        context = context,
        winningLayer = "Fallback: Áudio Original (Falha na Reprodução)",
        appliedOffsetMs = 0L,
        confidence = 0f
    )

    updateForceDubDebugInfo(
        plan = null,
        isActive = false,
        isLoading = false,
        methodOverride = "Áudio Original",
        logicOverride = "Falha na reprodução da dublagem; revertido para áudio original.",
        winningLayerOverride = "Fallback: Áudio Nativo",
        isFallbackToOriginal = true,
        fallbackReason = "ERRO REPRODUÇÃO",
        diagnosticFilePathOverride = dumpPath
    )
    showToastOnUiThread(customToast ?: context.getString(R.string.force_dub_fallback_toast))
    if (restoreOriginal) {
        scope.launch {
            rebuildMediaSourceToOriginal()
            ensureNativeAudioRestored()
        }
    }
}

internal fun PlayerRuntimeController.cancelForceDubWork() {
    forceDubJob?.cancel()
    forceDubJob = null
    forceDubVerifyJob?.cancel()
    forceDubVerifyJob = null
    forceDubOrchestraJob?.cancel()
    forceDubOrchestraJob = null
    pendingWarningResolution = null
}

internal fun PlayerRuntimeController.clearForceDubState(stopTorrent: Boolean, keepPlan: Boolean = false) {
    forceDubOrchestraJob?.cancel()
    forceDubOrchestraJob = null
    val planUsesTorrent = currentForceDubPlan?.audio?.isTorrent == true || forceDubPlanCache?.audio?.isTorrent == true
    val hasPendingMpvPlan = pendingMpvDubPlan != null
    currentForceDubActive = hasPendingMpvPlan
    currentForceDubPlan = null
    forceDubVerifiedForPlan = false
    if (!keepPlan || planUsesTorrent) {
        forceDubPlanCache = null
        forceDubPlanStreamUrl = null
        if (!hasPendingMpvPlan) {
            pendingMpvDubPlan = null
        }
    }
    if (stopTorrent) stopDubTorrentIfStarted()
    if (!hasPendingMpvPlan) {
        updateForceDubDebugInfo(plan = null, isActive = false, isLoading = false)
        _uiState.update {
            it.copy(
                isForceDubActive = false,
                isForceDubLoading = false,
                isForceDubEnabled = false,
                forceDubAudioStreamName = null,
                forceDubWarningDialog = null
            )
        }
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

        // 1. Remove seleções forçadas da dublagem e força reativação do áudio nativo
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        player.volume = 1.0f
        ensureNativeAudioRestored()

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
        ensureNativeAudioRestored()
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

internal fun PlayerRuntimeController.checkAndSelectNativeTargetLanguageTrack(tracks: Tracks) {
    val player = _exoPlayer ?: return
    val targetLanguage = resolveTargetLanguageSynchronous()
    val normTarget = DubLanguageDetector.normalizeLanguageCode(targetLanguage)
    if (normTarget.isBlank()) return

    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    if (audioGroups.isEmpty()) return

    // Nível C: Faixa única und/null de origem brasileira/dublada
    if (audioGroups.size == 1 && audioGroups[0].length == 1) {
        val format = audioGroups[0].getTrackFormat(0)
        val streamSearchText = DubOrchestrator.extractStreamSearchText(
            _uiState.value.sourceAllStreams.firstOrNull { it.getStreamUrl() == currentStreamUrl },
            currentStreamUrl
        )
        if (DubLanguageDetector.isSingleTrackPresumedTarget(format.language, streamSearchText, currentAddonName, normTarget)) {
            Log.d(PlayerRuntimeController.TAG, "Faixa única presumida '$normTarget' mantida.")
            return
        }
    }

    var bestGroup: Tracks.Group? = null
    var bestIdx = 0
    var bestScore = 0

    for (group in audioGroups) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val format = group.getTrackFormat(i)
            if (DubLanguageDetector.isTrackMatchingTargetLanguage(format.language, format.label, normTarget)) {
                var score = 50
                if (DubLanguageDetector.normalizeLanguageCode(format.language) == normTarget) score += 50
                val label = format.label.orEmpty().lowercase()
                if (label.contains("dublado") || label.contains("dub")) score += 20
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                    bestIdx = i
                }
            }
        }
    }

    if (bestGroup != null && (!bestGroup.isSelected || !bestGroup.isTrackSelected(bestIdx))) {
        val existingOverride = player.trackSelectionParameters.overrides[bestGroup.mediaTrackGroup]
        if (existingOverride == null || !existingOverride.trackIndices.contains(bestIdx)) {
            Log.i(PlayerRuntimeController.TAG, "Selecionando faixa nativa '$normTarget' do container principal (score=$bestScore)")
            val builder = player.trackSelectionParameters.buildUpon()
            builder.setOverrideForType(TrackSelectionOverride(bestGroup.mediaTrackGroup, bestIdx))
            player.trackSelectionParameters = builder.build()
        }
    }
}

private fun PlayerRuntimeController.resolveTargetLanguageSynchronous(): String {
    return _uiState.value.forceDubDebugInfo.targetLanguage.takeIf { it.isNotBlank() } ?: "pt"
}

internal fun PlayerRuntimeController.updateForceDubDebugInfo(
    plan: DubPlan? = currentForceDubPlan,
    isActive: Boolean = currentForceDubActive,
    isLoading: Boolean = _uiState.value.isForceDubLoading,
    methodOverride: String? = null,
    logicOverride: String? = null,
    offsetOverride: Long? = null,
    confidenceOverride: Float? = null,
    tVideoOverride: Long? = null,
    tAudioOverride: Long? = null,
    nccOverride: Float? = null,
    winningLayerOverride: String? = null,
    isFallbackToOriginal: Boolean = false,
    fallbackReason: String = "",
    diagnosticFilePathOverride: String? = null
) {
    val current = _uiState.value.forceDubDebugInfo
    val effectiveDumpPath = diagnosticFilePathOverride ?: current.diagnosticFilePath
    if (plan == null) {
        val hasOverride = methodOverride != null || logicOverride != null || isFallbackToOriginal
        _uiState.update {
            it.copy(
                forceDubDebugInfo = current.copy(
                    isEnabled = hasOverride,
                    isActive = false,
                    isLoading = false,
                    method = methodOverride ?: current.method,
                    logicApplied = logicOverride ?: current.logicApplied,
                    winningLayer = winningLayerOverride ?: current.winningLayer,
                    isFallbackToOriginal = isFallbackToOriginal,
                    fallbackReason = fallbackReason,
                    diagnosticFilePath = effectiveDumpPath
                )
            )
        }
        return
    }

    val engine = if (isExoPlayerEngineActive) "ExoPlayer" else "MPV"
    val engineering = buildList {
        if (isExoPlayerEngineActive) {
            add("AudioDelayMediaSource (PTS = PTS + offsetUs)")
            add("Descarte condicional PTS < 0 (apenas adiantamento)")
        } else {
            add("MPV audio-add")
            add("audio-delay dinâmico")
        }
        if (plan.audioFromCache) {
            add("Camada 1 Pack/Episode Cache Hit")
        } else {
            add("Camada 2 Bumper Detector")
        }
        add("Camada 3 Sub-Bridge RANSAC")
        add("Camada 4 CPU Software M&E Decoder")
    }.joinToString(" • ")

    val audioTitle = listOfNotNull(
        plan.audio.stream.name,
        plan.audio.stream.title,
        plan.audio.stream.behaviorHints?.filename
    ).joinToString(" | ")

    val appliedOffset = offsetOverride ?: plan.timeline.initialAudioOffsetMs
    val confidence = confidenceOverride ?: plan.timeline.confidence
    val method = methodOverride ?: plan.methodName
    val logic = logicOverride ?: plan.logicDescription
    val tVideo = tVideoOverride ?: plan.tVideoMs
    val tAudio = tAudioOverride ?: plan.tAudioMs
    val ncc = nccOverride ?: plan.acousticNccScore
    val winLayer = winningLayerOverride?.takeIf { it.isNotBlank() } ?: plan.winningLayer

    _uiState.update {
        it.copy(
            forceDubDebugInfo = ForceDubDebugInfo(
                isEnabled = true,
                isActive = isActive,
                isLoading = isLoading,
                targetLanguage = plan.timeline.targetAudioLanguage ?: "pt-BR",
                appliedOffsetMs = appliedOffset,
                method = method,
                logicApplied = logic,
                confidence = confidence,
                isBlind = plan.timeline.isBlind,
                fromCache = plan.audioFromCache,
                audioStreamName = plan.audio.stream.name.orEmpty(),
                audioStreamTitle = audioTitle,
                audioStreamUrl = plan.audio.url,
                videoStreamTitle = plan.videoStreamTitle.ifBlank { currentFilename ?: streamName ?: "" },
                engineeringSummary = engineering,
                engine = engine,
                tVideoMs = tVideo,
                tAudioMs = tAudio,
                acousticNccScore = ncc,
                winningLayer = winLayer,
                diagnosticFilePath = effectiveDumpPath
            )
        )
    }
}
