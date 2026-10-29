package com.nuvio.tv.core.dub

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class ContainerInfo(
    val format: String,
    val durationMs: Long? = null,
    val audioTracks: List<ProbedAudioTrack> = emptyList(),
    val videoResolution: String? = null,
    val is4kOrHigher: Boolean = false
)

data class ProbedAudioTrack(
    val id: String,
    val language: String?,
    val name: String?,
    val codec: String?,
    val channels: Int?
)

/** Resultado do probe real de um arquivo remoto (duração + idiomas de áudio). */
data class MediaProbeResult(
    val durationMs: Long?,
    val audioLanguages: Set<String>,
    val audioTrackCount: Int,
    val videoHeight: Int?
)

object ContainerProbe {

    private const val TAG = "ContainerProbe"
    private const val DEFAULT_PROBE_TIMEOUT_MS = 8_000L
    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Lê o cabeçalho do arquivo remoto com [MediaExtractor] e devolve a duração e os idiomas
     * das faixas de áudio. Devolve null quando não for possível ler dentro de [timeoutMs].
     */
    suspend fun probeMedia(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS
    ): MediaProbeResult? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return withContext(Dispatchers.IO) {
            // Leitura direta rápida e 100% cancelável de cabeçalho por HTTP Range via OkHttp.
            // IMPORTANTE: Nunca chamar MediaExtractor.setDataSource() em URLs HTTP remotas,
            // pois o NuCachedSource2 nativo (C++ Stagefright) faz 10 retentativas bloqueantes
            // de 4s (40s totais) em JNI sem respeitar cancelamento de corrotina quando há erro HTTP!
            val effectiveTimeoutMs = minOf(timeoutMs, 3_500L)
            withTimeoutOrNull(effectiveTimeoutMs) {
                FastContainerHeaderProbe.probe(url, headers)
            }
        }
    }

    private fun runExtractor(url: String, headers: Map<String, String>): MediaProbeResult? {
        val extractor = MediaExtractor()
        return try {
            val effectiveHeaders = LinkedHashMap<String, String>()
            headers.forEach { (k, v) ->
                if (!k.equals("Range", ignoreCase = true) && k.isNotBlank() && v.isNotBlank()) {
                    effectiveHeaders[k] = v
                }
            }
            if (effectiveHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                effectiveHeaders["User-Agent"] = FALLBACK_USER_AGENT
            }
            extractor.setDataSource(url, effectiveHeaders)

            var durationUs = 0L
            var videoHeight: Int? = null
            var audioCount = 0
            val languages = mutableSetOf<String>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = maxOf(durationUs, format.getLong(MediaFormat.KEY_DURATION))
                }
                when {
                    mime.startsWith("audio/") -> {
                        audioCount++
                        val lang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
                            format.getString(MediaFormat.KEY_LANGUAGE)
                        } else {
                            null
                        }
                        val norm = DubLanguageDetector.normalizeLanguageCode(lang)
                        if (norm.isNotBlank() && norm != "und") languages.add(norm)
                    }
                    mime.startsWith("video/") -> {
                        if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            videoHeight = maxOf(videoHeight ?: 0, format.getInteger(MediaFormat.KEY_HEIGHT))
                        }
                    }
                }
            }
            MediaProbeResult(
                durationMs = if (durationUs > 0L) durationUs / 1000L else null,
                audioLanguages = languages,
                audioTrackCount = audioCount,
                videoHeight = videoHeight
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor falhou: ${e.message}")
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Inferência barata (sem rede) baseada apenas nos textos do stream. */
    fun inferInfo(stream: Stream): ContainerInfo {
        val text = listOfNotNull(stream.name, stream.title, stream.description, stream.quality)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        val is4k = Regex("\\b(4k|2160p|uhd)\\b").containsMatchIn(text) ||
            Regex("\\b(8k|4320p)\\b").containsMatchIn(text)
        val resolution = when {
            is4k -> "4K"
            Regex("\\b(1080p|fhd)\\b").containsMatchIn(text) -> "1080p"
            Regex("\\b720p\\b").containsMatchIn(text) -> "720p"
            Regex("\\b480p\\b").containsMatchIn(text) -> "480p"
            else -> stream.quality ?: "Desconhecida"
        }

        val languages = DubLanguageDetector.detectLanguages(stream)
        val audioTracks = languages.mapIndexed { index, lang ->
            ProbedAudioTrack(
                id = "meta_$index",
                language = lang,
                name = "Track $index ($lang)",
                codec = null,
                channels = null
            )
        }

        return ContainerInfo(
            format = "Stream",
            durationMs = null,
            audioTracks = audioTracks,
            videoResolution = resolution,
            is4kOrHigher = is4k
        )
    }
}
