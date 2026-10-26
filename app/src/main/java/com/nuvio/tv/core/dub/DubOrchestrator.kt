package com.nuvio.tv.core.dub

import android.util.Log
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
    private const val MAX_HTTP_PROBES = 3
    private const val MAX_DEBRID_RESOLVES = 2
    private const val PROBE_TIMEOUT_MS = 2_500L

    const val STARTUP_BUDGET_MS = 16_000L
    const val MENU_BUDGET_MS = 25_000L

    private enum class Tier { HTTP, DEBRID_RESOLVE, TORRENT }

    private fun isHeavyStream(stream: Stream): Boolean {
        val text = (stream.name.orEmpty() + " " + stream.title.orEmpty()).lowercase()
        return text.contains("4k") || text.contains("2160p") || text.contains("remux") ||
               text.contains("uhd") || text.contains("bdrip") || text.contains("bluray")
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
                    ContainerProbe.probeMedia(videoUrl, videoHeaders)
                }
                val videoChaptersDeferred = async(Dispatchers.IO) {
                    runCatching { com.nuvio.tv.ui.screens.player.MatroskaChapterLoader.load(videoUrl, videoHeaders) }.getOrDefault(emptyList())
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

                val videoProbe = videoProbeDeferred.await()
                val videoChapters = videoChaptersDeferred.await()

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
                val videoKey = stableKey(videoStream, videoUrl)
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

    private fun interleaveByAddon(candidates: List<RankedCandidate>): List<RankedCandidate> {
        if (candidates.size <= 1) return candidates
        val byAddon = candidates
            .groupBy { it.stream.addonName }
            .mapValues { (_, list) ->
                list.sortedWith(
                    compareBy<RankedCandidate> { if (it.is4k) 1 else 0 }
                        .thenBy { if (isHeavyStream(it.stream)) 1 else 0 }
                )
            }
            .values
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
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): Evaluated? {
        // 1) Cache individual do episódio ou Season Pack Cache (0 ms instantâneo)
        for (candidate in ranked) {
            val url = candidate.stream.getStreamUrl()
            val audioKey = stableKey(candidate.stream, url)
            val candidateSearchText = extractStreamSearchText(candidate.stream, url)

            // Se o candidato possuir um bumper evidente (como Adult Swim de 5.1s) e o vídeo não possuir (ou vice-versa),
            // verificamos se o cache possui 0L cego e, se tiver, descartamos a entrada antiga contaminada
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

        // 2) Fontes HTTP diretas com Early Return
        val httpCandidates = ranked.filter { it.tier == Tier.HTTP }.take(MAX_HTTP_PROBES)
        val evaluatedHttp = mutableListOf<Evaluated>()
        for (candidate in httpCandidates) {
            val url = candidate.stream.getStreamUrl() ?: continue
            val headers = candidate.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
            val resolved = DubResolvedAudio(candidate.stream, url, headers, isTorrent = false)
            val evaluated = evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters, videoStreamTitle)
            if (evaluated != null) {
                if (!evaluated.is4k) {
                    Log.i(
                        TAG,
                        "Early return: fonte HTTP ideal encontrada em ${candidate.stream.name} " +
                            "(offset=${evaluated.decision.timeline.initialAudioOffsetMs}ms)"
                    )
                    return evaluated
                }
                evaluatedHttp.add(evaluated)
            }
        }
        pickBest(evaluatedHttp)?.takeIf { !it.is4k }?.let { return it }

        // 3) Debrid (priorizando fontes leves não-4k primeiro para evitar travamentos de rede):
        val debridCandidates = ranked.filter { it.tier == Tier.DEBRID_RESOLVE }
            .sortedWith(
                compareBy<RankedCandidate> { if (it.is4k) 1 else 0 }
                    .thenBy { if (isHeavyStream(it.stream)) 1 else 0 }
            )
            .take(MAX_DEBRID_RESOLVES)

        val evaluatedDebrid = mutableListOf<Evaluated>()
        for (candidate in debridCandidates) {
            val resolved = resolveAudio(candidate.stream) ?: continue
            val evaluated = evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters, videoStreamTitle)
            if (evaluated != null) {
                if (!evaluated.is4k) {
                    Log.i(
                        TAG,
                        "Early return: fonte Debrid ideal encontrada em ${candidate.stream.name} " +
                            "(offset=${evaluated.decision.timeline.initialAudioOffsetMs}ms)"
                    )
                    return evaluated
                }
                evaluatedDebrid.add(evaluated)
            }
        }

        pickBest(evaluatedHttp + evaluatedDebrid)?.let { return it }

        // 4) Torrent: último recurso.
        for (candidate in ranked.filter { it.tier == Tier.TORRENT }.take(1)) {
            val resolved = resolveAudio(candidate.stream) ?: continue
            evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters, videoStreamTitle)?.let { return it }
        }
        return null
    }

    /**
     * Pre-Flight Check Ultra-Rápido (800 ms):
     * Faz requisição HTTP Range: bytes=0-1023 com timeout de conexão de 800 ms.
     * Descarta instantaneamente candidatos com erro HTTP (>=400), HTML (text/html) ou timeout.
     */
    suspend fun quickPreFlightCheck(
        url: String,
        headers: Map<String, String>,
        streamName: String = "",
        addonName: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return@withContext true
        }
        val startTime = android.os.SystemClock.elapsedRealtime()
        val client = OkHttpClient.Builder()
            .connectTimeout(800, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .callTimeout(800, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-1023")
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                    reqBuilder.header(k, v)
                }
            }
            if (headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                reqBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Android TV)")
            }
            client.newCall(reqBuilder.build()).execute().use { response ->
                val elapsed = android.os.SystemClock.elapsedRealtime() - startTime
                val code = response.code
                val isSuccess = response.isSuccessful || code == 206 || code == 200
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val isHtml = contentType.contains("text/html")
                val accepted = isSuccess && !isHtml
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
                    return@withContext false
                }
                true
            }
        } catch (e: Exception) {
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
            false
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
        // Pre-Flight Check Ultra-Rápido (<800 ms): descarta candidatos inacessíveis ou com bloqueio antes do player
        if (!quickPreFlightCheck(resolved.url, resolved.headers, candidate.stream.name.orEmpty(), candidate.stream.addonName.orEmpty())) {
            Log.w(TAG, "Candidato descartado pelo Pre-Flight Check (<800ms): ${candidate.stream.name}")
            return@withContext null
        }

        val probeDeferred = async { ContainerProbe.probeMedia(resolved.url, resolved.headers, PROBE_TIMEOUT_MS) }
        val audioChaptersDeferred = async {
            runCatching {
                com.nuvio.tv.ui.screens.player.MatroskaChapterLoader.load(resolved.url, resolved.headers)
            }.getOrDefault(emptyList())
        }

        val probe = probeDeferred.await()
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
