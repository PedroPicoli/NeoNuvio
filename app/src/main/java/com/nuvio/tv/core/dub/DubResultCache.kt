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
    fun init(context: Context) {
        // Limpa permanentemente qualquer cache legado gravado em disco de sessões anteriores
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private fun makeKey(videoKey: String, audioKey: String): String {
        return "${videoKey.hashCode()}_${audioKey.hashCode()}"
    }

    fun get(videoKey: String, audioKey: String): DubTimelineMap? {
        val key = makeKey(videoKey, audioKey)
        return memoryCache[key]
    }

    fun put(videoKey: String, audioKey: String, timelineMap: DubTimelineMap) {
        val key = makeKey(videoKey, audioKey)
        memoryCache[key] = timelineMap
    }

    fun clear() {
        memoryCache.clear()
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
