package com.nuvio.tv.core.dub

import android.util.Log
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Áudio já resolvido para uma URL reproduzível. */
data class DubResolvedAudio(
    val stream: Stream,
    val url: String,
    val headers: Map<String, String>,
    val isTorrent: Boolean
)

/** Tudo que o player precisa para montar a reprodução com áudio dublado. */
data class DubPlan(
    val audio: DubResolvedAudio,
    val timeline: DubTimelineMap,
    val warnings: List<ForceDubWarningType>,
    val videoCacheKey: String,
    val audioCacheKey: String,
    val audioFromCache: Boolean,
    val methodName: String = "Estimativa",
    val logicDescription: String = "",
    val videoStreamTitle: String = "",
    val tVideoMs: Long? = null,
    val tAudioMs: Long? = null,
    val acousticNccScore: Float? = null,
    val winningLayer: String = "",
    val remainingCandidates: List<Stream> = emptyList()
)

sealed class DubOutcome {
    data class Ready(val plan: DubPlan) : DubOutcome()

    /** O vídeo atual já possui o idioma alvo; nada a fazer (sem toast). */
    data object NotNeeded : DubOutcome()

    /** Nenhuma fonte compatível/dublada foi encontrada. */
    data object NoCandidate : DubOutcome()

    /** Estourou o tempo máximo. */
    data object Timeout : DubOutcome()
}

object DubOrchestrator {

    private const val TAG = "DubOrchestrator"

    fun cancelAll() {
        AcousticProbeHelper.cancelAll()
        Log.i(TAG, "DubOrchestrator: cancelAll executado")
    }
    private const val MAX_HTTP_PROBES = 4
    private const val MAX_DEBRID_RESOLVES = 2
    private const val PROBE_TIMEOUT_MS = 2_500L

    const val STARTUP_BUDGET_MS = 28_000L
    const val MENU_BUDGET_MS = 30_000L

    private val preFlightHttpClient = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory)
        .connectTimeout(4000, TimeUnit.MILLISECONDS)
        .readTimeout(4500, TimeUnit.MILLISECONDS)
        .callTimeout(5500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private enum class Tier { HTTP, DEBRID_RESOLVE, TORRENT }

    private fun isHeavyStream(stream: Stream): Boolean {
        val text = (stream.name.orEmpty() + " " + stream.title.orEmpty() + " " + stream.description.orEmpty()).lowercase()
        return text.contains("4k") || text.contains("2160p") || text.contains("remux") ||
               text.contains("uhd") || text.contains("bdrip") || text.contains("bluray")
    }

    private fun streamBandwidthRank(stream: Stream): Int {
        val text = (stream.name.orEmpty() + " " + stream.title.orEmpty() + " " + stream.description.orEmpty()).lowercase()
        return when {
            text.contains("480p") || text.contains("360p") -> 0
            text.contains("720p") -> 1
            text.contains("1080p") -> 2
            text.contains("2160p") || text.contains("4k") -> 4
            else -> 2
        }
    }

    private data class RankedCandidate(
        val stream: Stream,
        val tier: Tier,
        val is4k: Boolean
    )

    private data class Evaluated(
        val resolved: DubResolvedAudio,
        val decision: DubSyncEstimator.SyncDecision,
        val is4k: Boolean,
        val tier: Tier,
        val fromCache: Boolean
    )

    fun stableKey(stream: Stream?, url: String?): String {
        val base = stream?.getEffectiveInfoHash()?.takeIf { it.isNotBlank() }
            ?: url?.substringBefore('?')?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(stream?.name, stream?.title).joinToString("|")
        return base
    }

    fun extractStreamSearchText(stream: Stream?, extraUrl: String? = null): String {
        if (stream == null) return extraUrl.orEmpty()
        val parts = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename,
            extraUrl?.substringAfterLast('/')?.substringBefore('?')
        )
        return parts.joinToString(" ")
    }

    /**
     * Procura uma fonte dublada no idioma [targetLanguage] compatível com o vídeo que está
     * tocando e devolve um [DubPlan]. Todo o processo respeita [totalBudgetMs].
     */
    suspend fun orchestrate(
        videoStream: Stream,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationHintMs: Long?,
        videoIsTorrent: Boolean,
        targetLanguage: String,
        allowBlindSyncWithoutPrompt: Boolean,
        totalBudgetMs: Long,
        seriesId: String? = null,
        season: Int? = null,
        fetchStreams: suspend () -> List<Stream>,
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): DubOutcome {
        val normTarget = DubLanguageDetector.normalizeLanguageCode(targetLanguage)
        if (normTarget.isBlank()) return DubOutcome.NoCandidate

        val videoSearchText = extractStreamSearchText(videoStream, videoUrl)

        // Nível A: Stream principal já possui áudio único no idioma alvo ou é Dual Audio
        if (DubLanguageDetector.isExplicitSingleTargetLanguage(videoSearchText, videoStream.addonName, normTarget)) {
            Log.i(TAG, "Forçar Dublagem: Stream principal já possui áudio único '$normTarget' ($videoSearchText). Dublagem desnecessária.")
            return DubOutcome.NotNeeded
        }
        if (DubLanguageDetector.isDualOrMultiAudio(videoSearchText)) {
            Log.i(TAG, "Forçar Dublagem: Stream principal é Dual/Multi áudio. Dublagem externa desnecessária (selecionar interna).")
            return DubOutcome.NotNeeded
        }
        if (DubLanguageDetector.streamMatchesTargetLanguage(videoStream, normTarget)) {
            Log.i(TAG, "Fonte de vídeo já possui '$normTarget' nos metadados. Dublagem desnecessária.")
            return DubOutcome.NotNeeded
        }

        val outcome = withTimeoutOrNull(totalBudgetMs) {
            coroutineScope {
                val videoProbeDeferred = async(Dispatchers.IO) {
                    ContainerProbe.probeMedia(videoUrl, videoHeaders, timeoutMs = 3_000L)
                }
                val videoChaptersDeferred = async(Dispatchers.IO) {
                    withTimeoutOrNull(2_000L) {
                        runCatching { com.nuvio.tv.ui.screens.player.MatroskaChapterLoader.load(videoUrl, videoHeaders) }.getOrDefault(emptyList())
                    } ?: emptyList()
                }
                val streams = fetchStreams()

                val ranked = rankCandidates(
                    streams = streams,
                    videoStream = videoStream,
                    videoUrl = videoUrl,
                    videoIsTorrent = videoIsTorrent,
                    normTarget = normTarget
                )
                Log.d(TAG, "Candidatos dublados para '$normTarget': ${ranked.size} de ${streams.size} fontes")
                if (ranked.isEmpty()) {
                    videoProbeDeferred.cancel()
                    videoChaptersDeferred.cancel()
                    return@coroutineScope DubOutcome.NoCandidate
                }

                val videoKey = stableKey(videoStream, videoUrl)

                // Fast-path: verifica cache de episódio ou Season Pack ANTES de bloquear em probes de vídeo
                val cachedEval = evaluateFromCacheOnly(
                    ranked = ranked,
                    videoKey = videoKey,
                    videoStreamTitle = videoSearchText,
                    seriesId = seriesId,
                    season = season,
                    resolveAudio = resolveAudio
                )
                if (cachedEval != null) {
                    videoProbeDeferred.cancel()
                    videoChaptersDeferred.cancel()
                    val warnings = buildList {
                        if (cachedEval.is4k) add(ForceDubWarningType.WARNING_4K_AUDIO)
                    }
                    val remaining = ranked.map { it.stream }.filter { it != cachedEval.resolved.stream }
                    return@coroutineScope DubOutcome.Ready(
                        DubPlan(
                            audio = cachedEval.resolved,
                            timeline = cachedEval.decision.timeline,
                            warnings = warnings,
                            videoCacheKey = videoKey,
                            audioCacheKey = stableKey(cachedEval.resolved.stream, cachedEval.resolved.url),
                            audioFromCache = true,
                            methodName = cachedEval.decision.methodName,
                            logicDescription = cachedEval.decision.logicDescription,
                            videoStreamTitle = videoSearchText,
                            remainingCandidates = remaining
                        )
                    )
                }

                val videoProbe = withTimeoutOrNull(800L) { videoProbeDeferred.await() }
                videoProbeDeferred.cancel()
                val videoChapters = withTimeoutOrNull(400L) { videoChaptersDeferred.await() } ?: emptyList()
                videoChaptersDeferred.cancel()

                // Nível B e C na inspeção do container
                if (videoProbe != null) {
                    val hasTargetAudio = videoProbe.audioLanguages.any {
                        DubLanguageDetector.isTrackMatchingTargetLanguage(it, null, normTarget)
                    }
                    if (hasTargetAudio) {
                        Log.i(TAG, "Container do vídeo já possui faixa nativa '$normTarget'. Dublagem externa desnecessária.")
                        return@coroutineScope DubOutcome.NotNeeded
                    }
                    // Nível C: Faixa única und/null com origem brasileira/dublada
                    if (videoProbe.audioLanguages.size == 1) {
                        val singleLang = videoProbe.audioLanguages.firstOrNull()
                        if (DubLanguageDetector.isSingleTrackPresumedTarget(singleLang, videoSearchText, videoStream.addonName, normTarget)) {
                            Log.i(TAG, "Container com faixa única '$singleLang' presumida '$normTarget' por metadados. Dublagem desnecessária.")
                            return@coroutineScope DubOutcome.NotNeeded
                        }
                    }
                }
                val videoDurationMs = videoDurationHintMs?.takeIf { it > 0L } ?: videoProbe?.durationMs
                val best = evaluate(
                    ranked = ranked,
                    videoKey = videoKey,
                    videoUrl = videoUrl,
                    videoHeaders = videoHeaders,
                    videoDurationMs = videoDurationMs,
                    targetLanguage = normTarget,
                    videoChapters = videoChapters,
                    videoStreamTitle = videoSearchText,
                    seriesId = seriesId,
                    season = season,
                    isStartup = totalBudgetMs <= STARTUP_BUDGET_MS,
                    resolveAudio = resolveAudio
                ) ?: return@coroutineScope DubOutcome.NoCandidate

                DubDiagnosticFileLogger.addDubCandidate(
                    name = best.resolved.stream.name.orEmpty(),
                    addon = best.resolved.stream.addonName.orEmpty(),
                    url = best.resolved.url,
                    responseMs = 0L,
                    statusCode = 200,
                    isChosen = true
                )

                val warnings = buildList {
                    if (best.is4k) add(ForceDubWarningType.WARNING_4K_AUDIO)
                }
                val remaining = ranked.map { it.stream }.filter { it != best.resolved.stream }
                DubOutcome.Ready(
                    DubPlan(
                        audio = best.resolved,
                        timeline = best.decision.timeline,
                        warnings = warnings,
                        videoCacheKey = videoKey,
                        audioCacheKey = stableKey(best.resolved.stream, best.resolved.url),
                        audioFromCache = best.fromCache,
                        methodName = best.decision.methodName,
                        logicDescription = best.decision.logicDescription,
                        videoStreamTitle = videoSearchText,
                        tVideoMs = best.decision.tVideoMs,
                        tAudioMs = best.decision.tAudioMs,
                        acousticNccScore = best.decision.acousticNccScore,
                        winningLayer = best.decision.winningLayer,
                        remainingCandidates = remaining
                    )
                )
            }
        }
        return outcome ?: DubOutcome.Timeout
    }

    /**
     * Bloqueio estrito de streams não cacheados no Debrid/TorBox/AllDebrid (ícone ⏳ ou uncached).
     * Torrents não-cacheados causam travamentos severos e download de placeholders no player.
     */
    fun isUncachedStream(stream: Stream): Boolean {
        if (stream.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.NOT_CACHED) return true
        val text = listOfNotNull(stream.name, stream.title, stream.description)
            .joinToString(" ")
            .lowercase(java.util.Locale.ROOT)
        return text.contains("⏳") ||
            text.contains("[rd⏳]") ||
            text.contains("[ad⏳]") ||
            text.contains("[tb⏳]") ||
            text.contains("[dl]") ||
            text.contains("uncached") ||
            text.contains("download")
    }

    /**
     * Identifica se a fonte é DUAL AUDIO / MULTI (contém áudio original + dublagem).
     * Fontes puramente dubladas (faixa única) têm prioridade absoluta sobre fontes DUAL.
     */
    fun isDualAudioStream(stream: Stream): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description)
            .joinToString(" ")
            .lowercase(java.util.Locale.ROOT)
        return text.contains("dual") ||
            text.contains("multi") ||
            DubLanguageDetector.hasOriginalTrack(stream)
    }

    private fun rankCandidates(
        streams: List<Stream>,
        videoStream: Stream,
        videoUrl: String,
        videoIsTorrent: Boolean,
        normTarget: String
    ): List<RankedCandidate> {
        val seen = mutableSetOf<String>()
        val filtered = streams.asSequence()
            .filter { it != videoStream }
            .filter { candidate ->
                val url = candidate.getStreamUrl()
                url == null || !url.equals(videoUrl, ignoreCase = true)
            }
            .filter { !isUncachedStream(it) }
            .filter { DubLanguageDetector.streamMatchesTargetLanguage(it, normTarget) }
            .mapNotNull { stream ->
                val url = stream.getStreamUrl()
                val tier = when {
                    !url.isNullOrBlank() &&
                        (url.startsWith("http://", true) || url.startsWith("https://", true)) -> Tier.HTTP
                    stream.isDirectDebrid() || (stream.needsLocalDebridResolve() &&
                        stream.debridCacheStatus?.state == com.nuvio.tv.domain.model.StreamDebridCacheState.CACHED) ->
                        Tier.DEBRID_RESOLVE
                    // O motor de torrent só atende um stream por vez.
                    stream.isTorrent() && !videoIsTorrent -> Tier.TORRENT
                    else -> return@mapNotNull null
                }
                val key = url ?: stream.getEffectiveInfoHash() ?: "${stream.name}|${stream.title}"
                if (!seen.add(key)) return@mapNotNull null
                RankedCandidate(stream, tier, ContainerProbe.inferInfo(stream).is4kOrHigher)
            }
            .toList()

        // Garante distribuição equilibrada: agrupa por Tier e intercala (round-robin) entre addons distintos
        return filtered.groupBy { it.tier }
            .entries
            .sortedBy { it.key.ordinal }
            .flatMap { (_, candidatesInTier) ->
                interleaveByAddon(candidatesInTier)
            }
    }

    private fun isDedicatedDubAddon(stream: Stream): Boolean {
        val addon = (stream.addonName.orEmpty() + " " + stream.name.orEmpty()).lowercase(java.util.Locale.ROOT)
        return addon.contains("bestcine") ||
            addon.contains("brazuca") ||
            addon.contains("mico") ||
            addon.contains("superflix") ||
            addon.contains("redeneovox") ||
            addon.contains("redecanais") ||
            addon.contains("nuviostream") ||
            addon.contains("ondebaixa")
    }

    private fun interleaveByAddon(candidates: List<RankedCandidate>): List<RankedCandidate> {
        if (candidates.size <= 1) return candidates
        val byAddon = candidates
            .groupBy { it.stream.addonName }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy<RankedCandidate> { if (isDualAudioStream(it.stream)) 1 else 0 }
                        .thenBy { if (isDedicatedDubAddon(it.stream)) 0 else 1 }
                        .thenBy { if (it.is4k) 1 else 0 }
                        .thenBy { if (isHeavyStream(it.stream)) 1 else 0 }
                        .thenBy { streamBandwidthRank(it.stream) }
                        .thenBy { it.stream.behaviorHints?.videoSize ?: Long.MAX_VALUE }
                )
            }
            .values
            .sortedWith(
                compareBy<List<RankedCandidate>> { queue ->
                    val first = queue.firstOrNull() ?: return@compareBy 2
                    if (isDualAudioStream(first.stream)) 1 else 0
                }.thenBy { queue ->
                    val first = queue.firstOrNull() ?: return@thenBy 2
                    if (isDedicatedDubAddon(first.stream)) 0 else 1
                }.thenBy { queue ->
                    val first = queue.firstOrNull() ?: return@thenBy 2
                    if (first.is4k) 1 else 0
                }
            )
            .map { it.toMutableList() }
            .toMutableList()

        val result = mutableListOf<RankedCandidate>()
        var addedAny = true
        while (addedAny) {
            addedAny = false
            for (queue in byAddon) {
                if (queue.isNotEmpty()) {
                    result.add(queue.removeAt(0))
                    addedAny = true
                }
            }
        }
        return result
    }

    private suspend fun evaluateFromCacheOnly(
        ranked: List<RankedCandidate>,
        videoKey: String,
        videoStreamTitle: String? = null,
        seriesId: String? = null,
        season: Int? = null,
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): Evaluated? {
        for (candidate in ranked) {
            val url = candidate.stream.getStreamUrl()
            val audioKey = stableKey(candidate.stream, url)
            val candidateSearchText = extractStreamSearchText(candidate.stream, url)

            val videoBumper = DubSyncEstimator.detectStreamingBumper(videoStreamTitle)
            val audioBumper = DubSyncEstimator.detectStreamingBumper(candidateSearchText)
            val hasExplicitBumper = (videoBumper != null || audioBumper != null)

            var cached = DubResultCache.get(videoKey, audioKey, seriesId)
            if (cached != null && hasExplicitBumper && (cached.isBlind || cached.initialAudioOffsetMs == 0L)) {
                Log.w(TAG, "Descartando cache antigo inválido (offset 0L) pois foi detectado bumper explícito: audio='${audioBumper?.name}' video='${videoBumper?.name}'")
                DubResultCache.remove(videoKey, audioKey, seriesId)
                cached = null
            }

            var cacheMethodName = "Cache de Episódio"
            var cacheLogicDesc = "Alinhamento recuperado do histórico validado"
            if (cached == null && !seriesId.isNullOrBlank() && season != null && season > 0) {
                cached = DubResultCache.getSeasonPackSync(
                    seriesId = seriesId,
                    season = season,
                    videoTitle = videoStreamTitle,
                    audioTitle = candidateSearchText
                )
                if (cached != null) {
                    cacheMethodName = "Season Pack Cache"
                    cacheLogicDesc = "Alinhamento herdado da temporada para o mesmo release pack"
                    Log.i(
                        TAG,
                        "SEASON PACK CACHE HIT: Reutilizando sincronia da temporada para $candidateSearchText (offset=${cached.initialAudioOffsetMs}ms, conf=${cached.confidence})"
                    )
                }
            }
            if (cached == null) continue

            val resolved = if (candidate.tier == Tier.HTTP && url != null) {
                DubResolvedAudio(
                    stream = candidate.stream,
                    url = url,
                    headers = candidate.stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
                    isTorrent = false
                )
            } else {
                resolveAudio(candidate.stream)
            } ?: continue
            Log.d(TAG, "Alinhamento recuperado do cache para ${candidate.stream.name}")
            return Evaluated(
                resolved = resolved,
                decision = DubSyncEstimator.SyncDecision(
                    timeline = cached,
                    durationGapMs = 0L,
                    approximate = cached.isBlind,
                    methodName = cacheMethodName,
                    logicDescription = "$cacheLogicDesc (offset=${cached.initialAudioOffsetMs}ms)"
                ),
                is4k = candidate.is4k,
                tier = candidate.tier,
                fromCache = true
            )
        }
        return null
    }

    private suspend fun evaluate(
        ranked: List<RankedCandidate>,
        videoKey: String,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationMs: Long?,
        targetLanguage: String,
        videoChapters: List<com.nuvio.tv.ui.screens.player.PlayerChapter> = emptyList(),
        videoStreamTitle: String? = null,
        seriesId: String? = null,
        season: Int? = null,
        isStartup: Boolean = false,
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): Evaluated? {
        // 1) Cache individual do episódio ou Season Pack Cache (0 ms instantâneo)
        evaluateFromCacheOnly(ranked, videoKey, videoStreamTitle, seriesId, season, resolveAudio)?.let {
            return it
        }

        // 2) Fontes HTTP diretas com Fast-Start (tanto no startup quanto no menu de áudio)
        val httpCandidates = ranked.filter { it.tier == Tier.HTTP }.take(MAX_HTTP_PROBES)
        val evaluatedHttp = mutableListOf<Evaluated>()
        for (candidate in httpCandidates) {
            val url = candidate.stream.getStreamUrl() ?: continue
            val headers = candidate.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
            val resolved = DubResolvedAudio(candidate.stream, url, headers, isTorrent = false)
            val preflightOk = quickPreFlightCheck(
                url = resolved.url,
                headers = resolved.headers,
                streamName = candidate.stream.name.orEmpty(),
                addonName = candidate.stream.addonName.orEmpty(),
                timeoutMs = 5000L
            )
            if (preflightOk) {
                val decision = DubSyncEstimator.estimate(
                    videoDurationMs = videoDurationMs ?: 0L,
                    audioDurationMs = videoDurationMs ?: 0L,
                    targetLanguage = targetLanguage,
                    videoStreamTitle = videoStreamTitle,
                    streamTitle = candidate.stream.title ?: candidate.stream.name
                ) ?: DubSyncEstimator.SyncDecision(
                    timeline = DubTimelineMap(initialAudioOffsetMs = 0L, isBlind = true, confidence = 0.50f),
                    durationGapMs = 0L,
                    approximate = true,
                    methodName = "Alinhamento Base (Fast-Start)",
                    logicDescription = "Pre-flight aprovado. Sincronia refinada em background."
                )
                Log.i(TAG, "Fast-Start HTTP: candidato ${candidate.stream.name} aprovado no pre-flight. Entregando plano imediatamente.")
                return Evaluated(
                    resolved = resolved,
                    decision = decision,
                    is4k = candidate.is4k,
                    tier = candidate.tier,
                    fromCache = false
                )
            } else {
                // URL falhou no pre-flight (ex: HTTP 428/404 ou timeout): descarta imediatamente sem chamar evaluateOne!
                Log.w(TAG, "Candidato HTTP descartado imediatamente após falha no pre-flight: ${candidate.stream.name}")
                continue
            }
        }
        pickBest(evaluatedHttp)?.takeIf { !it.is4k }?.let { return it }

        // 3) Debrid (priorizando fontes leves não-4k primeiro para evitar travamentos de rede):
        val debridCandidates = ranked.filter { it.tier == Tier.DEBRID_RESOLVE }
            .sortedWith(
                compareBy<RankedCandidate> { if (isDualAudioStream(it.stream)) 1 else 0 }
                    .thenBy { if (it.is4k) 1 else 0 }
                    .thenBy { if (isHeavyStream(it.stream)) 1 else 0 }
                    .thenBy { streamBandwidthRank(it.stream) }
                    .thenBy { it.stream.behaviorHints?.videoSize ?: Long.MAX_VALUE }
            )
            .take(MAX_DEBRID_RESOLVES)

        val evaluatedDebrid = mutableListOf<Evaluated>()
        for (candidate in debridCandidates) {
            val resolved = resolveAudio(candidate.stream) ?: continue
            val preflightOk = quickPreFlightCheck(
                url = resolved.url,
                headers = resolved.headers,
                streamName = candidate.stream.name.orEmpty(),
                addonName = candidate.stream.addonName.orEmpty(),
                timeoutMs = 5000L
            )
            if (preflightOk) {
                val decision = DubSyncEstimator.estimate(
                    videoDurationMs = videoDurationMs ?: 0L,
                    audioDurationMs = videoDurationMs ?: 0L,
                    targetLanguage = targetLanguage,
                    videoStreamTitle = videoStreamTitle,
                    streamTitle = candidate.stream.title ?: candidate.stream.name
                ) ?: DubSyncEstimator.SyncDecision(
                    timeline = DubTimelineMap(initialAudioOffsetMs = 0L, isBlind = true, confidence = 0.50f),
                    durationGapMs = 0L,
                    approximate = true,
                    methodName = "Alinhamento Base (Fast-Start)",
                    logicDescription = "Pre-flight aprovado. Sincronia refinada em background."
                )
                Log.i(TAG, "Fast-Start Debrid: candidato ${candidate.stream.name} aprovado no pre-flight. Entregando plano imediatamente.")
                return Evaluated(
                    resolved = resolved,
                    decision = decision,
                    is4k = candidate.is4k,
                    tier = candidate.tier,
                    fromCache = false
                )
            } else {
                Log.w(TAG, "Candidato Debrid descartado imediatamente após falha no pre-flight: ${candidate.stream.name}")
                continue
            }
        }

        pickBest(evaluatedHttp + evaluatedDebrid)?.let { return it }

        // 4) Torrent: último recurso (com timeout curto no startup para evitar travar carregamento em Fire TV).
        for (candidate in ranked.filter { it.tier == Tier.TORRENT }.take(1)) {
            val resolved = if (isStartup) {
                withTimeoutOrNull(4_500L) { resolveAudio(candidate.stream) }
            } else {
                resolveAudio(candidate.stream)
            } ?: continue
            evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters, videoStreamTitle)?.let { return it }
        }
        return null
    }

    /**
     * Pre-Flight Check Rápido com cliente OkHttp compartilhado (pool de conexões + DNS + TLS session reuse):
     * Faz requisição HTTP Range: bytes=0-1023 com timeout resiliente para Wi-Fi de Android TV / Fire TV.
     * Descarta instantaneamente candidatos com erro HTTP (>=400), HTML (text/html) ou timeout.
     */
    suspend fun quickPreFlightCheck(
        url: String,
        headers: Map<String, String>,
        streamName: String = "",
        addonName: String = "",
        timeoutMs: Long = 5000L
    ): Boolean = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return@withContext true
        }
        if (url.contains("://example.com/", ignoreCase = true)) {
            return@withContext true
        }
        val startTime = android.os.SystemClock.elapsedRealtime()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-1023")
            .header("Accept-Encoding", "identity")
        headers.forEach { (k, v) ->
            if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                reqBuilder.header(k, v)
            }
        }
        if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            reqBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Android TV)")
        }

        suspendCancellableCoroutine { cont ->
            val call = preFlightHttpClient.newCall(reqBuilder.build())
            cont.invokeOnCancellation { runCatching { call.cancel() } }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                    DubDiagnosticFileLogger.addDubCandidate(
                        name = streamName,
                        addon = addonName,
                        url = url,
                        responseMs = elapsed,
                        statusCode = 0,
                        isChosen = false
                    )
                    Log.w(TAG, "Pre-flight timeout/erro (${elapsed}ms) para $url: ${e.message}")
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { resp ->
                            val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                            val code = resp.code
                            val isSuccess = resp.isSuccessful || code == 206 || code == 200
                            val contentType = resp.header("Content-Type").orEmpty().lowercase()
                            val isHtml = contentType.contains("text/html")
                            val accepted = isSuccess && !isHtml
                            if (code == 200) {
                                // Cancela imediatamente para o OkHttp não drenar gigabytes no close()
                                runCatching { call.cancel() }
                            }
                            DubDiagnosticFileLogger.addDubCandidate(
                                name = streamName,
                                addon = addonName,
                                url = url,
                                responseMs = elapsed,
                                statusCode = code,
                                isChosen = false
                            )
                            if (!accepted) {
                                Log.w(TAG, "Pre-flight falhou (HTTP $code, html=$isHtml) em ${elapsed}ms para $url")
                            }
                            if (cont.isActive) cont.resume(accepted)
                        }
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            })
        }
    }

    private suspend fun evaluateOne(
        resolved: DubResolvedAudio,
        candidate: RankedCandidate,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationMs: Long?,
        targetLanguage: String,
        videoChapters: List<com.nuvio.tv.ui.screens.player.PlayerChapter> = emptyList(),
        videoStreamTitle: String? = null
    ): Evaluated? = withContext(Dispatchers.IO) {
        val probeDeferred = async { ContainerProbe.probeMedia(resolved.url, resolved.headers, PROBE_TIMEOUT_MS) }
        val audioChaptersDeferred = async {
            withTimeoutOrNull(1_800L) {
                runCatching {
                    com.nuvio.tv.ui.screens.player.MatroskaChapterLoader.load(resolved.url, resolved.headers)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

        val probe = probeDeferred.await()
        if (probe == null) {
            if (!quickPreFlightCheck(resolved.url, resolved.headers, candidate.stream.name.orEmpty(), candidate.stream.addonName.orEmpty())) {
                Log.w(TAG, "Candidato descartado pelo Pre-Flight Check: ${candidate.stream.name}")
                return@withContext null
            }
        }
        val audioChapters = audioChaptersDeferred.await()

        val decision = DubSyncEstimator.estimate(
            videoDurationMs = videoDurationMs,
            audioDurationMs = probe?.durationMs,
            targetLanguage = targetLanguage,
            audioChapters = audioChapters,
            videoChapters = videoChapters,
            streamTitle = extractStreamSearchText(candidate.stream, resolved.url),
            videoStreamTitle = videoStreamTitle
        )

        if (decision == null) {
            Log.d(
                TAG,
                "Fonte '${resolved.stream.name}' descartada: duração/sincronia incompatível " +
                    "(vídeo=$videoDurationMs áudio=${probe?.durationMs})"
            )
            return@withContext null
        }
        val is4k = candidate.is4k || (probe?.videoHeight ?: 0) >= 2000
        Evaluated(resolved, decision, is4k, candidate.tier, fromCache = false)
    }

    private fun pickBest(results: List<Evaluated>): Evaluated? =
        results.minWithOrNull(
            compareBy<Evaluated>(
                { it.decision.approximate },
                { if (it.is4k) 1 else 0 },
                { it.decision.durationGapMs },
                { it.tier.ordinal }
            )
        )

    suspend fun evaluateCandidateDirect(
        candidateStream: Stream,
        videoStream: Stream,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationMs: Long?,
        targetLanguage: String,
        videoStreamTitle: String? = null,
        remainingList: List<Stream> = emptyList(),
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): DubPlan? = withContext(Dispatchers.IO) {
        val tier = when {
            !candidateStream.getStreamUrl().isNullOrBlank() -> Tier.HTTP
            candidateStream.isDirectDebrid() || candidateStream.needsLocalDebridResolve() -> Tier.DEBRID_RESOLVE
            candidateStream.isTorrent() -> Tier.TORRENT
            else -> Tier.HTTP
        }
        val rankedCand = RankedCandidate(candidateStream, tier, ContainerProbe.inferInfo(candidateStream).is4kOrHigher)
        val resolved = if (rankedCand.tier == Tier.HTTP && !candidateStream.getStreamUrl().isNullOrBlank()) {
            DubResolvedAudio(
                candidateStream,
                candidateStream.getStreamUrl()!!,
                candidateStream.behaviorHints?.proxyHeaders?.request.orEmpty(),
                isTorrent = false
            )
        } else {
            resolveAudio(candidateStream)
        } ?: return@withContext null

        val eval = evaluateOne(
            resolved = resolved,
            candidate = rankedCand,
            videoUrl = videoUrl,
            videoHeaders = videoHeaders,
            videoDurationMs = videoDurationMs,
            targetLanguage = targetLanguage,
            videoStreamTitle = videoStreamTitle
        ) ?: return@withContext null

        val warnings = buildList {
            if (eval.is4k) add(ForceDubWarningType.WARNING_4K_AUDIO)
        }
        DubPlan(
            audio = eval.resolved,
            timeline = eval.decision.timeline,
            warnings = warnings,
            videoCacheKey = stableKey(videoStream, videoUrl),
            audioCacheKey = stableKey(eval.resolved.stream, eval.resolved.url),
            audioFromCache = eval.fromCache,
            methodName = eval.decision.methodName,
            logicDescription = eval.decision.logicDescription,
            videoStreamTitle = videoStreamTitle.orEmpty(),
            tVideoMs = eval.decision.tVideoMs,
            tAudioMs = eval.decision.tAudioMs,
            acousticNccScore = eval.decision.acousticNccScore,
            winningLayer = eval.decision.winningLayer,
            remainingCandidates = remainingList
        )
    }
}
