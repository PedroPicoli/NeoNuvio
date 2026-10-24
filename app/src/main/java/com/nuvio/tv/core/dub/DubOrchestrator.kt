package com.nuvio.tv.core.dub

import android.util.Log
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val audioFromCache: Boolean
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
    private const val MAX_HTTP_PROBES = 4
    private const val MAX_DEBRID_RESOLVES = 2
    private const val PROBE_TIMEOUT_MS = 5_000L

    const val STARTUP_BUDGET_MS = 25_000L
    const val MENU_BUDGET_MS = 80_000L

    private enum class Tier { HTTP, DEBRID_RESOLVE, TORRENT }

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
        fetchStreams: suspend () -> List<Stream>,
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): DubOutcome {
        val normTarget = DubLanguageDetector.normalizeLanguageCode(targetLanguage)
        if (normTarget.isBlank()) return DubOutcome.NoCandidate

        if (DubLanguageDetector.streamMatchesTargetLanguage(videoStream, normTarget)) {
            Log.d(TAG, "Fonte de vídeo já possui '$normTarget' (texto). Dublagem desnecessária.")
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
                if (videoProbe != null && videoProbe.audioLanguages.contains(normTarget)) {
                    Log.d(TAG, "Container do vídeo já tem áudio '$normTarget'. Dublagem desnecessária.")
                    return@coroutineScope DubOutcome.NotNeeded
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
                    resolveAudio = resolveAudio
                ) ?: return@coroutineScope DubOutcome.NoCandidate

                val warnings = buildList {
                    if (best.is4k) add(ForceDubWarningType.WARNING_4K_AUDIO)
                }
                DubOutcome.Ready(
                    DubPlan(
                        audio = best.resolved,
                        timeline = best.decision.timeline,
                        warnings = warnings,
                        videoCacheKey = videoKey,
                        audioCacheKey = stableKey(best.resolved.stream, best.resolved.url),
                        audioFromCache = best.fromCache
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
        return streams.asSequence()
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
            .sortedWith(compareBy<RankedCandidate>({ it.tier.ordinal }, { it.is4k }))
            .toList()
    }

    private suspend fun evaluate(
        ranked: List<RankedCandidate>,
        videoKey: String,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationMs: Long?,
        targetLanguage: String,
        videoChapters: List<com.nuvio.tv.ui.screens.player.PlayerChapter> = emptyList(),
        resolveAudio: suspend (Stream) -> DubResolvedAudio?
    ): Evaluated? {
        // 1) Já deu certo antes: usa o cache e pula todo o probe.
        for (candidate in ranked) {
            val url = candidate.stream.getStreamUrl()
            val audioKey = stableKey(candidate.stream, url)
            val cached = DubResultCache.get(videoKey, audioKey) ?: continue
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
                decision = DubSyncEstimator.SyncDecision(cached, 0L, cached.isBlind),
                is4k = candidate.is4k,
                tier = candidate.tier,
                fromCache = true
            )
        }

        // 2) Fontes HTTP diretas: probe paralelo das melhores.
        val http = ranked.filter { it.tier == Tier.HTTP }.take(MAX_HTTP_PROBES)
        val httpResults = coroutineScope {
            http.map { candidate ->
                async {
                    val url = candidate.stream.getStreamUrl() ?: return@async null
                    val headers = candidate.stream.behaviorHints?.proxyHeaders?.request.orEmpty()
                    val resolved = DubResolvedAudio(candidate.stream, url, headers, isTorrent = false)
                    evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters)
                }
            }.awaitAll()
        }.filterNotNull()
        pickBest(httpResults)?.let { return it }

        // 3) Debrid que precisa de resolução local: resolução e probe em paralelo dos melhores.
        val debrid = ranked.filter { it.tier == Tier.DEBRID_RESOLVE }.take(MAX_DEBRID_RESOLVES)
        if (debrid.isNotEmpty()) {
            val debridResults = coroutineScope {
                debrid.map { candidate ->
                    async {
                        val resolved = resolveAudio(candidate.stream) ?: return@async null
                        evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters)
                    }
                }.awaitAll()
            }.filterNotNull()
            pickBest(debridResults)?.let { return it }
        }

        // 4) Torrent: último recurso.
        for (candidate in ranked.filter { it.tier == Tier.TORRENT }.take(1)) {
            val resolved = resolveAudio(candidate.stream) ?: continue
            evaluateOne(resolved, candidate, videoUrl, videoHeaders, videoDurationMs, targetLanguage, videoChapters)?.let { return it }
        }
        return null
    }

    private suspend fun evaluateOne(
        resolved: DubResolvedAudio,
        candidate: RankedCandidate,
        videoUrl: String,
        videoHeaders: Map<String, String>,
        videoDurationMs: Long?,
        targetLanguage: String,
        videoChapters: List<com.nuvio.tv.ui.screens.player.PlayerChapter> = emptyList()
    ): Evaluated? = withContext(Dispatchers.IO) {
        val probeDeferred = async { ContainerProbe.probeMedia(resolved.url, resolved.headers, PROBE_TIMEOUT_MS) }
        val audioChaptersDeferred = async {
            runCatching {
                com.nuvio.tv.ui.screens.player.MatroskaChapterLoader.load(resolved.url, resolved.headers)
            }.getOrDefault(emptyList())
        }
        val acousticMatchDeferred = async {
            AcousticProbeHelper.matchAcousticOffset(videoUrl, videoHeaders, resolved.url, resolved.headers)
        }

        val probe = probeDeferred.await()
        val audioChapters = audioChaptersDeferred.await()
        val acousticMatch = acousticMatchDeferred.await()

        val decision = DubSyncEstimator.estimate(
            videoDurationMs = videoDurationMs,
            audioDurationMs = probe?.durationMs,
            targetLanguage = targetLanguage,
            audioChapters = audioChapters,
            videoChapters = videoChapters,
            streamTitle = candidate.stream.name ?: candidate.stream.title,
            acousticMatch = acousticMatch
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
                { it.tier.ordinal },
                { it.decision.durationGapMs },
                { it.is4k }
            )
        )
}
