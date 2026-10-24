package com.nuvio.tv.core.dub

import android.util.Log
import com.nuvio.tv.core.player.MatroskaAfrProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

internal object FastContainerHeaderProbe {

    private const val TAG = "FastContainerProbe"
    private const val PROBE_RANGE_BYTES = 1024 * 1024 // 1 MB para capturar Segment Info e Tracks de grandes MKVs
    private const val MP4_TAIL_BYTES = 512 * 1024 // 512 KB do final do arquivo para encontrar moov em MP4s sem faststart

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun probe(url: String, headers: Map<String, String>): MediaProbeResult? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return@withContext null
        }
        val bytes = fetchRange(url, headers, 0L, PROBE_RANGE_BYTES) ?: return@withContext null
        if (bytes.size < 16) return@withContext null

        // 1. Tenta Matroska / WebM
        val mkvResult = parseMatroskaHead(bytes)
        if (mkvResult != null && mkvResult.durationMs != null && mkvResult.durationMs > 0L) {
            return@withContext mkvResult
        }

        // 2. Tenta MP4 / MOV no início do arquivo
        val mp4Result = parseMp4Head(bytes)
        if (mp4Result != null && mp4Result.durationMs != null && mp4Result.durationMs > 0L) {
            return@withContext mp4Result
        }

        // 3. Se for MP4 e não tem moov no início, tenta buscar moov no final do arquivo (bytes=-524288)
        val tailBytes = fetchTail(url, headers, MP4_TAIL_BYTES)
        if (tailBytes != null && tailBytes.size > 16) {
            val tailResult = parseMp4Head(tailBytes)
            if (tailResult != null && tailResult.durationMs != null && tailResult.durationMs > 0L) {
                return@withContext tailResult
            }
        }

        mkvResult ?: mp4Result
    }

    private fun fetchRange(url: String, headers: Map<String, String>, start: Long, length: Int): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + length - 1}")
            .header("Accept-Encoding", "identity")
            .apply {
                headers.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true)) {
                        header(k, v)
                    }
                }
            }
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 206) {
                    response.body?.bytes()
                } else null
            }
        }.getOrNull()
    }

    private fun fetchTail(url: String, headers: Map<String, String>, length: Int): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=-$length")
            .header("Accept-Encoding", "identity")
            .apply {
                headers.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true) && !k.equals("Accept-Encoding", ignoreCase = true)) {
                        header(k, v)
                    }
                }
            }
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 206) {
                    response.body?.bytes()
                } else null
            }
        }.getOrNull()
    }

    private fun parseMatroskaHead(bytes: ByteArray): MediaProbeResult? {
        var pos = 0L
        var segment: MatroskaAfrProbe.EbmlElement? = null
        for (attempt in 0 until 8) {
            val element = MatroskaAfrProbe.readElement(bytes, pos) ?: return null
            if (element.id == MatroskaAfrProbe.ID_SEGMENT) {
                segment = element
                break
            }
            pos = element.endExclusiveOrNull() ?: return null
        }
        val segmentDataStart = segment?.dataOffset ?: return null

        var durationMs: Long? = null
        val languages = mutableSetOf<String>()
        var audioTracks = 0
        var videoHeight: Int? = null

        var innerPos = segmentDataStart
        while (innerPos < bytes.size - 4) {
            val elem = MatroskaAfrProbe.readElement(bytes, innerPos) ?: break
            val elemEnd = elem.endExclusiveOrNull() ?: break
            when (elem.id) {
                MatroskaAfrProbe.ID_INFO -> {
                    durationMs = parseSegmentInfoDuration(bytes, elem)
                }
                MatroskaAfrProbe.ID_TRACKS -> {
                    parseTracks(bytes, elem, languages, onAudioCount = { audioTracks++ }, onVideoHeight = { videoHeight = it })
                }
                MatroskaAfrProbe.ID_CLUSTER -> break
            }
            innerPos = elemEnd
        }

        return MediaProbeResult(
            durationMs = durationMs,
            audioLanguages = languages,
            audioTrackCount = audioTracks,
            videoHeight = videoHeight
        )
    }

    private fun parseSegmentInfoDuration(bytes: ByteArray, infoElem: MatroskaAfrProbe.EbmlElement): Long? {
        var timecodeScale = 1_000_000L
        var rawDuration: Double? = null

        val start = infoElem.dataOffset
        val end = (infoElem.endExclusiveOrNull() ?: bytes.size.toLong()).coerceAtMost(bytes.size.toLong())
        var p = start
        while (p < end - 2) {
            val child = MatroskaAfrProbe.readElement(bytes, p) ?: break
            val childEnd = child.endExclusiveOrNull() ?: break
            when (child.id) {
                0x2AD7B1L -> { // TimecodeScale
                    readUint(bytes, child.dataOffset.toInt(), child.dataSize.toInt())?.let {
                        timecodeScale = it
                    }
                }
                0x4489L -> { // Duration (float / double)
                    val size = child.dataSize.toInt()
                    if (size == 4) {
                        readUint(bytes, child.dataOffset.toInt(), 4)?.let {
                            rawDuration = java.lang.Float.intBitsToFloat(it.toInt()).toDouble()
                        }
                    } else if (size == 8) {
                        readUint(bytes, child.dataOffset.toInt(), 8)?.let {
                            rawDuration = java.lang.Double.longBitsToDouble(it)
                        }
                    }
                }
            }
            p = childEnd
        }

        return rawDuration?.let { dur ->
            ((dur * timecodeScale) / 1_000_000.0).toLong().takeIf { it > 0L }
        }
    }

    private fun parseTracks(
        bytes: ByteArray,
        tracksElem: MatroskaAfrProbe.EbmlElement,
        languages: MutableSet<String>,
        onAudioCount: () -> Unit,
        onVideoHeight: (Int) -> Unit
    ) {
        val start = tracksElem.dataOffset
        val end = (tracksElem.endExclusiveOrNull() ?: bytes.size.toLong()).coerceAtMost(bytes.size.toLong())
        var p = start
        while (p < end - 2) {
            val trackEntry = MatroskaAfrProbe.readElement(bytes, p) ?: break
            val next = trackEntry.endExclusiveOrNull() ?: break
            if (trackEntry.id == 0xAEL) { // TrackEntry
                parseTrackEntry(bytes, trackEntry, languages, onAudioCount, onVideoHeight)
            }
            p = next
        }
    }

    private fun parseTrackEntry(
        bytes: ByteArray,
        entry: MatroskaAfrProbe.EbmlElement,
        languages: MutableSet<String>,
        onAudioCount: () -> Unit,
        onVideoHeight: (Int) -> Unit
    ) {
        var trackType: Long? = null
        var lang: String? = null

        val start = entry.dataOffset
        val end = (entry.endExclusiveOrNull() ?: bytes.size.toLong()).coerceAtMost(bytes.size.toLong())
        var p = start
        while (p < end - 2) {
            val child = MatroskaAfrProbe.readElement(bytes, p) ?: break
            val next = child.endExclusiveOrNull() ?: break
            when (child.id) {
                0x83L -> trackType = readUint(bytes, child.dataOffset.toInt(), child.dataSize.toInt())
                0x22B59CL -> { // Language
                    val s = child.dataOffset.toInt()
                    val len = child.dataSize.toInt()
                    if (s >= 0 && s + len <= bytes.size) {
                        lang = String(bytes, s, len, Charsets.UTF_8).trim()
                    }
                }
                0xE0L -> { // Video
                    parseVideoSubElement(bytes, child, onVideoHeight)
                }
            }
            p = next
        }

        if (trackType == 2L) { // Audio
            onAudioCount()
            val norm = DubLanguageDetector.normalizeLanguageCode(lang)
            if (norm.isNotBlank() && norm != "und") languages.add(norm)
        }
    }

    private fun parseVideoSubElement(
        bytes: ByteArray,
        videoElem: MatroskaAfrProbe.EbmlElement,
        onVideoHeight: (Int) -> Unit
    ) {
        val start = videoElem.dataOffset
        val end = (videoElem.endExclusiveOrNull() ?: bytes.size.toLong()).coerceAtMost(bytes.size.toLong())
        var p = start
        while (p < end - 2) {
            val child = MatroskaAfrProbe.readElement(bytes, p) ?: break
            val next = child.endExclusiveOrNull() ?: break
            if (child.id == 0xBAL) { // PixelHeight
                readUint(bytes, child.dataOffset.toInt(), child.dataSize.toInt())?.let {
                    onVideoHeight(it.toInt())
                }
            }
            p = next
        }
    }

    private fun parseMp4Head(bytes: ByteArray): MediaProbeResult? {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        var durationMs: Long? = null

        try {
            var offset = 0
            while (offset < bytes.size - 8) {
                val size = dis.readInt()
                val type = ByteArray(4)
                dis.readFully(type)
                val typeStr = String(type, Charsets.US_ASCII)

                val boxSize = if (size == 1) {
                    val extended = dis.readLong()
                    offset += 16
                    extended
                } else {
                    offset += 8
                    size.toLong()
                }
                if (boxSize < 8L) break

                if (typeStr == "moov") {
                    val moovLen = (boxSize - 8).toInt().coerceAtMost(bytes.size - offset)
                    durationMs = parseMvhd(bytes, offset, moovLen)
                    break
                }
                val skip = (boxSize - 8).toInt()
                if (skip < 0 || offset + skip > bytes.size) break
                dis.skipBytes(skip)
                offset += skip
            }
        } catch (_: Exception) {
        }

        return durationMs?.let {
            MediaProbeResult(
                durationMs = it,
                audioLanguages = emptySet(),
                audioTrackCount = 1,
                videoHeight = null
            )
        }
    }

    private fun parseMvhd(bytes: ByteArray, offset: Int, length: Int): Long? {
        var p = offset
        val end = offset + length
        while (p < end - 8) {
            val size = ((bytes[p].toInt() and 0xFF) shl 24) or
                ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or
                (bytes[p + 3].toInt() and 0xFF)
            val type = String(bytes, p + 4, 4, Charsets.US_ASCII)
            if (type == "mvhd" && size >= 24) {
                val version = bytes[p + 8].toInt() and 0xFF
                return if (version == 1 && size >= 36) {
                    val timescale = readUint(bytes, p + 28, 4) ?: return null
                    val duration = readUint(bytes, p + 32, 8) ?: return null
                    if (timescale > 0L) (duration * 1000L) / timescale else null
                } else if (size >= 24) {
                    val timescale = readUint(bytes, p + 20, 4) ?: return null
                    val duration = readUint(bytes, p + 24, 4) ?: return null
                    if (timescale > 0L) (duration * 1000L) / timescale else null
                } else null
            }
            if (size <= 0) break
            p += size
        }
        return null
    }

    private fun readUint(bytes: ByteArray, offset: Int, length: Int): Long? {
        if (offset < 0 || length <= 0 || offset + length > bytes.size || length > 8) return null
        var v = 0L
        for (i in 0 until length) {
            v = (v shl 8) or (bytes[offset + i].toLong() and 0xFFL)
        }
        return v
    }
}
