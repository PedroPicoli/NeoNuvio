package com.nuvio.tv.core.dub

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

/**
 * Caixa preta diagnóstica de telemetria ("Blackbox Dump") para o sistema Forçar Dublagem.
 *
 * Registra com precisão forense:
 * 1. Auditoria de saúde do áudio do player e do Android TV (foco, volumes, mutes, aid).
 * 2. Metadados completos das fontes e faixas dos containers.
 * 3. Envelopes acústicos das fontes amostrados a 10 Hz (400 pontos de 0s a 40s) para inspeção de vinhetas.
 * 4. Auditoria de legendas embutidas vs externas (primeiras 20 falas).
 * 5. Motores de correlação com Top 10 lags, ZNCC e PSR de cada camada.
 * 6. Linha do tempo cronológica com marcos de tempo em milissegundos (+0ms, +140ms...).
 *
 * IMPORTANTE: Ativo estritamente em compilações DEBUG (BuildConfig.DEBUG == true).
 * Em builds RELEASE, todos os métodos são no-ops imediatos com overhead zero e nenhum arquivo no disco.
 */
object DubDiagnosticFileLogger {

    private const val TAG = "DubDiagnosticLogger"
    private const val MAX_HISTORY_ENTRIES = 5

    private val timelineEvents = ConcurrentLinkedQueue<String>()
    private var sessionStartTimeMs: Long = 0L

    @Volatile private var contentId: String = ""
    @Volatile private var contentTitle: String = ""
    @Volatile private var targetLanguage: String = ""
    @Volatile private var initialEngine: String = "ExoPlayer"
    @Volatile private var engineFailoverOccurred: Boolean = false
    @Volatile private var engineFailoverReason: String = ""

    // Audio Health
    @Volatile private var androidVolume: Int = -1
    @Volatile private var androidMuted: Boolean = false
    @Volatile private var exoVolume: Float = 1.0f
    @Volatile private var exoAudioDisabled: Boolean = false
    @Volatile private var mpvAid: String = ""
    @Volatile private var mpvMute: String = ""
    @Volatile private var mpvVolume: Double = 100.0
    @Volatile private var mpvAudioDelay: Double = 0.0
    @Volatile private var mpvAvSync: Double = 0.0
    @Volatile private var activeAudioUrl: String = ""

    // Streams & Container
    private val dubCandidatesList = mutableListOf<JSONObject>()
    @Volatile private var videoSourceJson = JSONObject()
    private val videoTracksList = mutableListOf<JSONObject>()
    private val audioTracksList = mutableListOf<JSONObject>()
    private val subtitleTracksList = mutableListOf<JSONObject>()

    // Extraction Telemetry
    @Volatile private var videoExtractionJson = JSONObject()
    @Volatile private var dubExtractionJson = JSONObject()
    @Volatile private var videoEnvelope10Hz: List<Float> = emptyList()
    @Volatile private var dubEnvelope10Hz: List<Float> = emptyList()
    @Volatile private var dubSpeech10Hz: List<Float> = emptyList()
    @Volatile private var subMask10Hz: List<Float> = emptyList()

    // Subtitles Audit
    @Volatile private var embeddedSubsCount: Int = 0
    @Volatile private var externalSubsCount: Int = 0
    @Volatile private var chosenSubOrigin: String = ""
    @Volatile private var chosenSubLang: String = ""
    @Volatile private var chosenSubTotalCues: Int = 0
    private val first20CuesList = mutableListOf<JSONObject>()

    // Correlation Results
    private val correlationMethodsList = mutableListOf<JSONObject>()

    @Synchronized
    fun startSession(
        id: String,
        title: String,
        targetLang: String,
        engine: String
    ) {
        if (!BuildConfig.DEBUG) return

        timelineEvents.clear()
        sessionStartTimeMs = SystemClock.elapsedRealtime()
        contentId = id
        contentTitle = title
        targetLanguage = targetLang
        initialEngine = engine
        engineFailoverOccurred = false
        engineFailoverReason = ""

        dubCandidatesList.clear()
        videoSourceJson = JSONObject()
        videoTracksList.clear()
        audioTracksList.clear()
        subtitleTracksList.clear()

        videoExtractionJson = JSONObject()
        dubExtractionJson = JSONObject()
        videoEnvelope10Hz = emptyList()
        dubEnvelope10Hz = emptyList()
        dubSpeech10Hz = emptyList()
        subMask10Hz = emptyList()

        embeddedSubsCount = 0
        externalSubsCount = 0
        chosenSubOrigin = ""
        chosenSubLang = ""
        chosenSubTotalCues = 0
        first20CuesList.clear()

        correlationMethodsList.clear()

        recordTimelineEvent("INIT", "Sessão de diagnóstico iniciada para '$title' (id=$id, targetLang=$targetLang, engine=$engine)")
    }

    fun recordTimelineEvent(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val elapsed = if (sessionStartTimeMs > 0L) SystemClock.elapsedRealtime() - sessionStartTimeMs else 0L
        val entry = "+${elapsed}ms [$tag] $message"
        timelineEvents.add(entry)
        Log.d(TAG, entry)
    }

    fun recordEngineFailover(reason: String) {
        if (!BuildConfig.DEBUG) return
        engineFailoverOccurred = true
        engineFailoverReason = reason
        recordTimelineEvent("FAILOVER", "Engine failover acionado: $reason")
    }

    fun recordAudioHealthAudit(
        context: Context,
        exoPlayer: androidx.media3.exoplayer.ExoPlayer?,
        mpvView: com.nuvio.tv.ui.screens.player.NuvioMpvSurfaceView?,
        activeAudioUrl: String?
    ) {
        if (!BuildConfig.DEBUG) return
        var androidVol = -1
        var androidMute = false
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (audioManager != null) {
                androidVol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    androidMute = audioManager.isStreamMute(android.media.AudioManager.STREAM_MUSIC)
                }
            }
        }
        var exoVol: Float? = null
        var exoAudioDis: Boolean? = null
        exoPlayer?.let { player ->
            runCatching {
                exoVol = player.volume
                exoAudioDis = player.trackSelectionParameters.disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_AUDIO)
            }
        }
        var aid: String? = null
        var muteProp: String? = null
        var volProp: Double? = null
        var delayProp: Double? = null
        var avSyncProp: Double? = null
        if (mpvView != null && mpvView.isInitialized) {
            runCatching {
                aid = mpvView.mpv.getPropertyString("aid")
                muteProp = mpvView.mpv.getPropertyString("mute")
                volProp = mpvView.mpv.getPropertyDouble("volume")
                delayProp = mpvView.mpv.getPropertyDouble("audio-delay")
                avSyncProp = mpvView.mpv.getPropertyDouble("avsync")
            }
        } else {
            aid = if (mpvView == null) "no_view" else "uninitialized"
        }
        updateAudioHealth(
            androidVol = androidVol,
            androidMute = androidMute,
            exoVol = exoVol,
            exoAudioDis = exoAudioDis,
            aid = aid,
            muteProp = muteProp,
            volProp = volProp,
            delayProp = delayProp,
            avSync = avSyncProp,
            audioUrl = activeAudioUrl
        )
    }

    fun updateAudioHealth(
        androidVol: Int,
        androidMute: Boolean,
        exoVol: Float?,
        exoAudioDis: Boolean?,
        aid: String?,
        muteProp: String?,
        volProp: Double?,
        delayProp: Double?,
        avSync: Double? = null,
        audioUrl: String?
    ) {
        if (!BuildConfig.DEBUG) return
        androidVolume = androidVol
        androidMuted = androidMute
        exoVolume = exoVol ?: 1.0f
        exoAudioDisabled = exoAudioDis ?: false
        mpvAid = aid.orEmpty()
        mpvMute = muteProp.orEmpty()
        mpvVolume = volProp ?: 100.0
        mpvAudioDelay = delayProp ?: 0.0
        mpvAvSync = avSync ?: 0.0
        activeAudioUrl = maskUrl(audioUrl)

        recordTimelineEvent("AUDIO_HEALTH", "Audio audit: AndroidVol=$androidVol Muted=$androidMute | ExoVol=$exoVol AudioDis=$exoAudioDis | MpvAid=$aid MpvMute=$muteProp Delay=$delayProp AvSync=$avSync")
    }

    fun setVideoSourceMetadata(name: String, addon: String, url: String, responseMs: Long, statusCode: Int) {
        if (!BuildConfig.DEBUG) return
        videoSourceJson = JSONObject().apply {
            put("name", name)
            put("addon", addon)
            put("url_masked", maskUrl(url))
            put("preflight_response_ms", responseMs)
            put("status_code", statusCode)
        }
        recordTimelineEvent("STREAM", "Video Source: $name ($addon) HTTP $statusCode em ${responseMs}ms")
    }

    @Synchronized
    fun addDubCandidate(name: String, addon: String, url: String, responseMs: Long, statusCode: Int, isChosen: Boolean) {
        if (!BuildConfig.DEBUG) return
        val obj = JSONObject().apply {
            put("name", name)
            put("addon", addon)
            put("url_masked", maskUrl(url))
            put("response_time_ms", responseMs)
            put("status_code", statusCode)
            put("is_chosen", isChosen)
        }
        dubCandidatesList.add(obj)
        recordTimelineEvent("CANDIDATE", "Dub candidate: $name ($addon) HTTP $statusCode ${responseMs}ms chosen=$isChosen")
    }

    @Synchronized
    fun setContainerTracks(
        videos: List<Map<String, Any?>>,
        audios: List<Map<String, Any?>>,
        subs: List<Map<String, Any?>>
    ) {
        if (!BuildConfig.DEBUG) return
        videoTracksList.clear()
        audioTracksList.clear()
        subtitleTracksList.clear()
        audios.forEach { audioTracksList.add(mapToJson(it)) }
        videos.forEach { videoTracksList.add(mapToJson(it)) }
        subs.forEach { subtitleTracksList.add(mapToJson(it)) }
        recordTimelineEvent("TRACKS", "Container tracks: ${videos.size} video, ${audios.size} audio, ${subs.size} subs")
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (k, v) ->
            runCatching {
                if (v != null) json.put(k, v)
            }
        }
        return json
    }

    fun setExtractionTelemetry(
        isVideo: Boolean,
        extractor: String,
        mode: String,
        timeSpentMs: Long,
        firstPtsMs: Long,
        lastPtsMs: Long,
        totalBins: Int,
        filledBins: Int,
        zeroBins: Int,
        envelope: FloatArray?,
        speechEnvelope: FloatArray? = null
    ) {
        if (!BuildConfig.DEBUG) return
        val stats = calculateStats(envelope)
        val obj = JSONObject().apply {
            put("extractor", extractor)
            put("mode", mode)
            put("time_spent_ms", timeSpentMs)
            put("first_pts_ms", firstPtsMs)
            put("last_pts_ms", lastPtsMs)
            put("total_bins", totalBins)
            put("filled_bins_count", filledBins)
            put("zero_bins_count", zeroBins)
            put("min_val", stats.minVal)
            put("max_val", stats.maxVal)
            put("mean_val", stats.meanVal)
            put("std_dev", stats.stdDev)
        }
        if (isVideo) {
            videoExtractionJson = obj
            videoEnvelope10Hz = downsampleEnvelopeTo10Hz(envelope)
            recordTimelineEvent("PROBE_VIDEO", "$extractor ($mode) ${filledBins}b pts=${firstPtsMs}..${lastPtsMs}ms em ${timeSpentMs}ms")
        } else {
            dubExtractionJson = obj
            dubEnvelope10Hz = downsampleEnvelopeTo10Hz(envelope)
            if (speechEnvelope != null) {
                dubSpeech10Hz = downsampleEnvelopeTo10Hz(speechEnvelope)
            }
            recordTimelineEvent("PROBE_DUB", "$extractor ($mode) ${filledBins}b pts=${firstPtsMs}..${lastPtsMs}ms em ${timeSpentMs}ms")
        }
    }

    @Synchronized
    fun setSubtitlesAudit(
        embeddedCount: Int,
        externalCount: Int,
        chosenOrigin: String,
        chosenLang: String,
        cues: List<SubtitleSyncCue>
    ) {
        if (!BuildConfig.DEBUG) return
        embeddedSubsCount = embeddedCount
        externalSubsCount = externalCount
        chosenSubOrigin = chosenOrigin
        chosenSubLang = chosenLang
        chosenSubTotalCues = cues.size

        first20CuesList.clear()
        cues.take(20).forEachIndexed { index, cue ->
            val cueObj = JSONObject().apply {
                put("index", index)
                put("start_ms", cue.startTimeMs)
                put("end_ms", cue.endTimeMs)
                put("duration_ms", cue.endTimeMs - cue.startTimeMs)
                put("text", cue.text.take(60))
            }
            first20CuesList.add(cueObj)
        }
        subMask10Hz = buildSubMask10Hz(cues)
        recordTimelineEvent("SUBS_AUDIT", "Subtitles: $embeddedCount embutidas, $externalCount externas. Escolhida: $chosenOrigin ($chosenLang) com ${cues.size} cues")
    }

    @Synchronized
    fun recordCorrelationResult(
        methodName: String,
        searchWindow: String,
        bestLagMs: Long,
        zncc: Float,
        psr: Float,
        topLags: List<AcousticSyncMatcher.LagResult>,
        passed: Boolean,
        decisionReason: String
    ) {
        if (!BuildConfig.DEBUG) return
        val lagsArray = JSONArray()
        topLags.take(10).forEach { lag ->
            lagsArray.put(JSONObject().apply {
                put("lag_ms", lag.lagMs)
                put("zncc", ((lag.zncc * 1000).toInt() / 1000.0f))
                put("psr", ((lag.psr * 10).toInt() / 10.0f))
            })
        }

        val obj = JSONObject().apply {
            put("method_name", methodName)
            put("search_window", searchWindow)
            put("best_lag_ms", bestLagMs)
            put("max_zncc", ((zncc * 1000).toInt() / 1000.0f))
            put("psr", ((psr * 10).toInt() / 10.0f))
            put("top_10_lags", lagsArray)
            put("passed_filters", passed)
            put("decision_reason", decisionReason)
        }
        correlationMethodsList.add(obj)
        recordTimelineEvent("CORRELATION", "Método $methodName: lag=${bestLagMs}ms zncc=$zncc psr=$psr passed=$passed ($decisionReason)")
    }

    @Synchronized
    fun finalizeAndDump(
        context: Context,
        winningLayer: String,
        appliedOffsetMs: Long,
        confidence: Float
    ): String {
        if (!BuildConfig.DEBUG) return ""
        recordTimelineEvent("FINAL_DECISION", "Vencedora: $winningLayer | Offset=${appliedOffsetMs}ms | Conf=${(confidence * 100).toInt()}%")

        val root = JSONObject()

        // 1. session_info & audio_health_audit
        val sessionObj = JSONObject().apply {
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            put("content_id", contentId)
            put("title", contentTitle)
            put("target_language", targetLanguage)
            put("initial_engine", initialEngine)
            put("engine_failover_occurred", engineFailoverOccurred)
            put("engine_failover_reason", engineFailoverReason)
            put("android_stream_music_volume", androidVolume)
            put("android_is_muted", androidMuted)
            put("exoplayer_volume", exoVolume)
            put("exoplayer_audio_track_disabled", exoAudioDisabled)
            put("mpv_aid_property", mpvAid)
            put("mpv_mute_property", mpvMute)
            put("mpv_volume_property", mpvVolume)
            put("mpv_audio_delay_property", mpvAudioDelay)
            put("mpv_avsync_diff_sec", ((mpvAvSync * 1000).toInt() / 1000.0))
            put("active_audio_source_url", activeAudioUrl)
        }
        root.put("session_info_and_audio_health", sessionObj)

        // 2. streams_metadata & container_tracks
        val streamsObj = JSONObject().apply {
            put("video_source", videoSourceJson)
            put("dub_candidates", JSONArray(dubCandidatesList))
            put("container_video_tracks", JSONArray(videoTracksList))
            put("container_audio_tracks", JSONArray(audioTracksList))
            put("container_subtitle_tracks", JSONArray(subtitleTracksList))
        }
        root.put("streams_metadata_and_container_tracks", streamsObj)

        // 3. extraction_telemetry
        val extractionObj = JSONObject().apply {
            put("video_extraction", videoExtractionJson)
            put("dub_extraction", dubExtractionJson)
            put("wave_samples_10hz", JSONObject().apply {
                put("video_me_envelope_10hz", JSONArray(videoEnvelope10Hz))
                put("dub_me_envelope_10hz", JSONArray(dubEnvelope10Hz))
                put("dub_vad_speech_10hz", JSONArray(dubSpeech10Hz))
                put("sub_mask_10hz", JSONArray(subMask10Hz))
            })
        }
        root.put("extraction_telemetry", extractionObj)

        // 4. subtitles_audit
        val subsObj = JSONObject().apply {
            put("embedded_subtitles_found", embeddedSubsCount)
            put("external_subtitles_found", externalSubsCount)
            put("chosen_subtitle_origin", chosenSubOrigin)
            put("chosen_subtitle_language", chosenSubLang)
            put("chosen_subtitle_total_cues", chosenSubTotalCues)
            put("first_20_cues", JSONArray(first20CuesList))
        }
        root.put("subtitles_audit", subsObj)

        // 5. correlation_engine_results
        val correlationObj = JSONObject().apply {
            put("methods_evaluated", JSONArray(correlationMethodsList))
            put("final_decision", JSONObject().apply {
                put("winning_layer", winningLayer)
                put("applied_offset_ms", appliedOffsetMs)
                put("confidence", confidence)
            })
        }
        root.put("correlation_engine_results", correlationObj)

        // 6. chronological_event_timeline
        root.put("chronological_event_timeline", JSONArray(timelineEvents.toList()))

        val jsonString = root.toString(2)
        var primarySavedPath = ""

        // Grava no App External Files Dir: /Android/data/com.nuvio.tv/files/neonuvio_dub_diagnostic.json
        runCatching {
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val appFile = File(baseDir, "neonuvio_dub_diagnostic.json")
            appFile.writeText(jsonString)
            primarySavedPath = appFile.absolutePath
            Log.i(TAG, "Dump gravado em: $primarySavedPath")
        }.onFailure { Log.w(TAG, "Falha ao gravar dump no app external files dir: ${it.message}") }

        // Grava em Downloads público: /sdcard/Download/neonuvio_dub_diagnostic.json
        runCatching {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir != null) {
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val downloadFile = File(downloadsDir, "neonuvio_dub_diagnostic.json")
                downloadFile.writeText(jsonString)
                primarySavedPath = downloadFile.absolutePath
                Log.i(TAG, "Dump gravado em Downloads: $primarySavedPath")

                // Histórico dos últimos 5 testes
                val historyFile = File(downloadsDir, "neonuvio_dub_diagnostic_history.log")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val summary = "[$timestamp] $contentTitle ($contentId) -> Offset: ${appliedOffsetMs}ms (Conf: ${(confidence * 100).toInt()}%) Layer: $winningLayer\n"
                val existingLines = if (historyFile.exists()) historyFile.readLines() else emptyList()
                val newHistory = (existingLines + summary).takeLast(MAX_HISTORY_ENTRIES)
                historyFile.writeText(newHistory.joinToString("\n") + "\n")
            }
        }.onFailure { Log.w(TAG, "Falha ao gravar dump em Downloads: ${it.message}") }

        return primarySavedPath
    }

    private fun maskUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.contains("://")) {
            val scheme = url.substringBefore("://")
            val hostAndRest = url.substringAfter("://")
            val host = hostAndRest.substringBefore("/")
            "$scheme://$host/...(${url.takeLast(16)})"
        } else {
            url.take(40)
        }
    }

    private data class Stats(
        val minVal: Float,
        val maxVal: Float,
        val meanVal: Float,
        val stdDev: Float
    )

    private fun calculateStats(array: FloatArray?): Stats {
        if (array == null || array.isEmpty()) return Stats(0f, 0f, 0f, 0f)
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE
        var sum = 0.0
        for (v in array) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = (sum / array.size).toFloat()
        var varSum = 0.0
        for (v in array) {
            val diff = v - mean
            varSum += diff * diff
        }
        val std = sqrt(varSum / array.size).toFloat()
        return Stats(
            minVal = ((min * 1000).toInt() / 1000.0f),
            maxVal = ((max * 1000).toInt() / 1000.0f),
            meanVal = ((mean * 1000).toInt() / 1000.0f),
            stdDev = ((std * 1000).toInt() / 1000.0f)
        )
    }

    private fun downsampleEnvelopeTo10Hz(envelope: FloatArray?, totalSeconds: Int = 40): List<Float> {
        if (envelope == null || envelope.isEmpty()) return emptyList()
        val targetPoints = totalSeconds * 10 // 400 pontos
        val result = ArrayList<Float>(targetPoints)
        for (i in 0 until targetPoints) {
            val startBin = i * 10
            val endBin = minOf(envelope.size, startBin + 10)
            var maxVal = 0.0f
            if (startBin < envelope.size) {
                for (b in startBin until endBin) {
                    if (envelope[b] > maxVal) maxVal = envelope[b]
                }
            }
            result.add(((maxVal * 1000).toInt() / 1000.0f))
        }
        return result
    }

    private fun buildSubMask10Hz(cues: List<SubtitleSyncCue>, totalSeconds: Int = 40): List<Float> {
        val targetPoints = totalSeconds * 10 // 400 pontos
        val mask = FloatArray(targetPoints)
        val clean = DubSubtitleSyncAligner.sanitizeCues(cues)
        for (cue in clean) {
            val startBin = (cue.startTimeMs / 100L).toInt()
            val pulseEndMs = cue.startTimeMs + 350L // Onset Pulse Train: apenas primeiros 350ms
            val endBin = (pulseEndMs / 100L).toInt()
            for (b in maxOf(0, startBin)..minOf(targetPoints - 1, endBin)) {
                mask[b] = 1.0f
            }
        }
        return mask.map { ((it * 100).toInt() / 100.0f) }
    }
}
