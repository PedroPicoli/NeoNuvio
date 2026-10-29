package com.nuvio.tv.core.dub

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.LinkedHashMap
import org.json.JSONArray
import org.json.JSONObject

object DubResultCache {

    private const val PREFS_NAME = "nuvio_dub_results_cache"
    private const val MAX_ENTRIES = 100
    private val memoryCache: MutableMap<String, DubTimelineMap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, DubTimelineMap>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DubTimelineMap>?): Boolean {
                return size > MAX_ENTRIES
            }
        }
    )
    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        runCatching {
            val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val keysToRemove = mutableListOf<String>()
            // Carrega entradas salvas em disco para o cache de memória, purgando falhas/baixa confiança
            p.all.forEach { (key, value) ->
                if (value is String) {
                    runCatching {
                        val map = deserialize(value)
                        // Invalida imediatamente entradas cacheadas geradas pela Camada 2.5: Bumper Boundary Detector
                        // (incluindo 0ms falsos gerados por deadzone antiga com 85% de confiança)
                        val isInvalidBumper = map.initialAudioOffsetMs == 4100L ||
                            map.initialAudioOffsetMs == -430L ||
                            map.initialAudioOffsetMs == 4200L ||
                            map.initialAudioOffsetMs == -420L ||
                            (map.initialAudioOffsetMs == 0L && map.confidence < 0.90f)
                        val isInDeadzone = kotlin.math.abs(map.initialAudioOffsetMs) in 1L..119L

                        if (map.isBlind || map.confidence <= 0.55f || isInvalidBumper || isInDeadzone) {
                            keysToRemove.add(key)
                        } else {
                            memoryCache[key] = map
                        }
                    }.onFailure {
                        keysToRemove.add(key)
                    }
                }
            }
            if (keysToRemove.isNotEmpty()) {
                val editor = p.edit()
                keysToRemove.forEach { editor.remove(it) }
                editor.apply()
            }
        }
    }

    /**
     * Purga imediatamente todas as entradas de cache com offsets gerados por falsos silêncios de vinheta.
     */
    fun clearBumperEntries() {
        val keysToRemove = mutableListOf<String>()
        memoryCache.forEach { (key, map) ->
            if (map.initialAudioOffsetMs == 4100L ||
                map.initialAudioOffsetMs == -430L ||
                map.initialAudioOffsetMs == 4200L ||
                map.initialAudioOffsetMs == -420L ||
                (map.initialAudioOffsetMs == 0L && map.confidence < 0.90f) ||
                kotlin.math.abs(map.initialAudioOffsetMs) in 1L..119L
            ) {
                keysToRemove.add(key)
            }
        }
        keysToRemove.forEach { memoryCache.remove(it) }
        runCatching {
            val p = prefs ?: return
            val editor = p.edit()
            p.all.forEach { (k, v) ->
                if (v is String) {
                    val map = runCatching { deserialize(v) }.getOrNull()
                    if (map != null && (
                        map.initialAudioOffsetMs == 4100L ||
                        map.initialAudioOffsetMs == -430L ||
                        map.initialAudioOffsetMs == 4200L ||
                        map.initialAudioOffsetMs == -420L ||
                        map.initialAudioOffsetMs == -2750L ||
                        map.initialAudioOffsetMs == 4410L ||
                        map.initialAudioOffsetMs == -2700L ||
                        map.initialAudioOffsetMs == 4400L ||
                        (map.initialAudioOffsetMs == 0L && map.confidence < 0.90f) ||
                        kotlin.math.abs(map.initialAudioOffsetMs) in 1L..119L
                    )) {
                        editor.remove(k)
                    }
                }
            }
            editor.apply()
        }
    }

    private fun makeKey(videoKey: String, audioKey: String, contentId: String? = null): String {
        val cid = contentId?.takeIf { it.isNotBlank() } ?: "global"
        val vSig = extractReleaseSignature(videoKey).ifBlank { videoKey.hashCode().toString() }
        val aSig = extractReleaseSignature(audioKey).ifBlank { audioKey.hashCode().toString() }
        return "sync_cache_${cid}_${vSig}_${aSig}"
    }

    fun get(videoKey: String, audioKey: String, contentId: String? = null): DubTimelineMap? {
        val key = makeKey(videoKey, audioKey, contentId)
        val legacyKey = "${videoKey.hashCode()}_${audioKey.hashCode()}"
        val entry = memoryCache[key]
            ?: memoryCache[legacyKey]
            ?: prefs?.getString(key, null)?.let { runCatching { deserialize(it) }.getOrNull() }
            ?: prefs?.getString(legacyKey, null)?.let { runCatching { deserialize(it) }.getOrNull() }
            ?: return null

        // Regra 1: Proibido recuperar resultados de falha, cegos, baixa confiança ou gerados por falsos bumpers
        val isInvalidBumper = entry.initialAudioOffsetMs == 4100L ||
            entry.initialAudioOffsetMs == -430L ||
            entry.initialAudioOffsetMs == 4200L ||
            entry.initialAudioOffsetMs == -420L ||
            entry.initialAudioOffsetMs == -2750L ||
            entry.initialAudioOffsetMs == 4410L ||
            entry.initialAudioOffsetMs == -2700L ||
            entry.initialAudioOffsetMs == 4400L ||
            (entry.initialAudioOffsetMs == 0L && entry.confidence < 0.90f)
        if (entry.isBlind || entry.confidence < 0.65f || isInvalidBumper) {
            remove(videoKey, audioKey, contentId)
            memoryCache.remove(legacyKey)
            runCatching { prefs?.edit()?.remove(legacyKey)?.apply() }
            return null
        }

        // Aplica Zona Morta de +-120ms: se for < 120ms, crava em 0ms
        val finalEntry = if (kotlin.math.abs(entry.initialAudioOffsetMs) in 1L..119L) {
            entry.copy(initialAudioOffsetMs = 0L)
        } else {
            entry
        }

        memoryCache[key] = finalEntry
        return finalEntry
    }

    fun put(videoKey: String, audioKey: String, timelineMap: DubTimelineMap, contentId: String? = null) {
        // Regra 1: Jamais salvar no cache resultados com falha, confiança baixa (< 65%) ou 0ms não confirmado (< 90%)
        if (timelineMap.isBlind || timelineMap.confidence < 0.65f || (timelineMap.initialAudioOffsetMs == 0L && timelineMap.confidence < 0.90f)) {
            return
        }
        val key = makeKey(videoKey, audioKey, contentId)
        memoryCache[key] = timelineMap
        runCatching {
            prefs?.edit()?.putString(key, serialize(timelineMap))?.apply()
        }
    }

    fun remove(videoKey: String, audioKey: String, contentId: String? = null) {
        val key = makeKey(videoKey, audioKey, contentId)
        val legacyKey = "${videoKey.hashCode()}_${audioKey.hashCode()}"
        memoryCache.remove(key)
        memoryCache.remove(legacyKey)
        runCatching {
            prefs?.edit()?.remove(key)?.remove(legacyKey)?.apply()
        }
    }

    /**
     * Extrai uma assinatura estável de release/encoder a partir do nome do arquivo ou stream.
     * Exemplo: "Rick.and.Morty.S07E01.1080p.MAX.WEB-DL.DUAL-Brazuca.mkv" -> "MAX-Brazuca"
     */
    fun extractReleaseSignature(title: String?): String {
        if (title.isNullOrBlank()) return "default"
        val clean = title.replace(".mkv", "", ignoreCase = true)
            .replace(".mp4", "", ignoreCase = true)
            .replace(".avi", "", ignoreCase = true)
            .trim()

        val groupMatch = Regex("""-([A-Za-z0-9_]+)$""").find(clean)
        val group = groupMatch?.groupValues?.getOrNull(1)?.uppercase()

        val service = when {
            clean.contains("AMZN", ignoreCase = true) || clean.contains("Prime", ignoreCase = true) -> "AMZN"
            clean.contains("MAX", ignoreCase = true) || clean.contains("HBO", ignoreCase = true) -> "MAX"
            clean.contains("DSNP", ignoreCase = true) || clean.contains("Disney", ignoreCase = true) -> "DSNP"
            clean.contains("NF", ignoreCase = true) || clean.contains("Netflix", ignoreCase = true) -> "NF"
            clean.contains("ATVP", ignoreCase = true) || clean.contains("Apple", ignoreCase = true) -> "ATVP"
            clean.contains("Adult Swim", ignoreCase = true) || clean.contains("AdultSwim", ignoreCase = true) || clean.contains(" AS ", ignoreCase = true) -> "ADULTSWIM"
            clean.contains("BluRay", ignoreCase = true) || clean.contains("BD-?Rip", ignoreCase = true) -> "BLURAY"
            clean.contains("WEB-?DL", ignoreCase = true) || clean.contains("WEBRip", ignoreCase = true) -> "WEBDL"
            clean.contains("HDTV", ignoreCase = true) -> "HDTV"
            else -> "OTHER"
        }

        return if (group != null) "${service}_$group" else service
    }

    private fun makePackKey(seriesId: String, season: Int, videoReleaseTag: String, audioReleaseTag: String): String {
        return "sync_pack_${seriesId}_S${season}_${videoReleaseTag}_$audioReleaseTag"
    }

    /**
     * Tenta recuperar o alinhamento validado da temporada inteira quando o episódio seguinte
     * pertencer ao mesmo par de releases/encoders (0 ms).
     */
    fun getSeasonPackSync(seriesId: String?, season: Int?, videoTitle: String?, audioTitle: String?): DubTimelineMap? {
        if (seriesId.isNullOrBlank() || season == null || season <= 0) return null
        val videoTag = extractReleaseSignature(videoTitle)
        val audioTag = extractReleaseSignature(audioTitle)
        val packKey = makePackKey(seriesId, season, videoTag, audioTag)

        val entry = memoryCache[packKey] ?: prefs?.getString(packKey, null)?.let {
            runCatching { deserialize(it) }.getOrNull()?.also { map -> memoryCache[packKey] = map }
        } ?: return null

        if (entry.confidence >= 0.70f) {
            return entry
        }
        return null
    }

    /**
     * Salva o alinhamento validado como perfil da temporada para aquele par de releases/encoders.
     */
    fun putSeasonPackSync(seriesId: String?, season: Int?, videoTitle: String?, audioTitle: String?, timelineMap: DubTimelineMap) {
        if (seriesId.isNullOrBlank() || season == null || season <= 0) return
        if (timelineMap.isBlind || timelineMap.confidence < 0.70f) return
        val videoTag = extractReleaseSignature(videoTitle)
        val audioTag = extractReleaseSignature(audioTitle)
        val packKey = makePackKey(seriesId, season, videoTag, audioTag)
        memoryCache[packKey] = timelineMap
        runCatching {
            prefs?.edit()?.putString(packKey, serialize(timelineMap))?.apply()
        }
    }

    fun clear() {
        memoryCache.clear()
        runCatching { prefs?.edit()?.clear()?.apply() }
    }

    private fun serialize(map: DubTimelineMap): String {
        val json = JSONObject()
        json.put("initialAudioOffsetMs", map.initialAudioOffsetMs)
        json.put("scale", map.scale)
        json.put("confidence", map.confidence.toDouble())
        json.put("isBlind", map.isBlind)
        json.put("targetAudioTrackId", map.targetAudioTrackId)
        json.put("targetAudioLanguage", map.targetAudioLanguage)

        val cutsArray = JSONArray()
        map.cuts.forEach { cut ->
            val cutObj = JSONObject()
            cutObj.put("videoPointMs", cut.videoPointMs)
            cutObj.put("audioSkipMs", cut.audioSkipMs)
            cutsArray.put(cutObj)
        }
        json.put("cuts", cutsArray)
        return json.toString()
    }

    private fun deserialize(str: String): DubTimelineMap {
        val json = JSONObject(str)
        val initialOffset = json.optLong("initialAudioOffsetMs", 0L)
        val scale = json.optDouble("scale", 1.0)
        val confidence = json.optDouble("confidence", 1.0).toFloat()
        val isBlind = json.optBoolean("isBlind", false)
        val targetTrackId = json.optString("targetAudioTrackId").takeIf { it.isNotBlank() }
        val targetLang = json.optString("targetAudioLanguage").takeIf { it.isNotBlank() }

        val cuts = mutableListOf<DubCutAdjustment>()
        val cutsArray = json.optJSONArray("cuts")
        if (cutsArray != null) {
            for (i in 0 until cutsArray.length()) {
                val cutObj = cutsArray.getJSONObject(i)
                cuts.add(
                    DubCutAdjustment(
                        videoPointMs = cutObj.getLong("videoPointMs"),
                        audioSkipMs = cutObj.getLong("audioSkipMs")
                    )
                )
            }
        }

        return DubTimelineMap(
            initialAudioOffsetMs = initialOffset,
            scale = scale,
            cuts = cuts,
            confidence = confidence,
            isBlind = isBlind,
            targetAudioTrackId = targetTrackId,
            targetAudioLanguage = targetLang
        )
    }
}
