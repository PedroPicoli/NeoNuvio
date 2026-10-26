package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.SeriesCustomSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SeriesCustomSettingsStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    fun observe(contentId: String, profileId: Int = profileManager.activeProfileId.value): Flow<SeriesCustomSettings> {
        return factory.get(profileId, "series_custom_settings").data.map { prefs ->
            val disableSkips = prefs[booleanPreferencesKey("disable_skips:$contentId")] ?: false
            val sourceAddon = prefs[stringPreferencesKey("source_addon:$contentId")]
            val sourceKey = prefs[stringPreferencesKey("source_key:$contentId")]
            val sourceName = prefs[stringPreferencesKey("source_name:$contentId")]
            val language = prefs[stringPreferencesKey("language:$contentId")]
            val sourceEpisode = prefs[intPreferencesKey("source_episode:$contentId")]
            val sourceSeason = prefs[intPreferencesKey("source_season:$contentId")]
            val languagesRaw = prefs[stringPreferencesKey("source_languages:$contentId")]
            val detectedLanguages = languagesRaw?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            val forceDubEnabled = prefs[booleanPreferencesKey("force_dub:$contentId")]
            val forceDubTargetLanguage = prefs[stringPreferencesKey("force_dub_lang:$contentId")]
            SeriesCustomSettings(
                disableSkips = disableSkips,
                preferredSourceAddon = sourceAddon,
                preferredSourceKey = sourceKey,
                preferredSourceName = sourceName,
                preferredLanguage = language,
                sourceEpisode = sourceEpisode,
                sourceSeason = sourceSeason,
                detectedLanguages = detectedLanguages,
                forceDubEnabled = forceDubEnabled,
                forceDubTargetLanguage = forceDubTargetLanguage
            )
        }
    }

    suspend fun get(
        contentId: String,
        profileId: Int = profileManager.activeProfileId.value,
        fallbackId: String? = null
    ): SeriesCustomSettings {
        val primary = observe(contentId, profileId).first()
        if (primary.preferredSourceKey != null || primary.preferredSourceAddon != null || primary.disableSkips || primary.forceDubEnabled != null) {
            return primary
        }
        if (!fallbackId.isNullOrBlank() && fallbackId != contentId) {
            val fallback = observe(fallbackId, profileId).first()
            if (fallback.preferredSourceKey != null || fallback.preferredSourceAddon != null || fallback.disableSkips || fallback.forceDubEnabled != null) {
                return fallback
            }
        }
        return primary
    }

    suspend fun save(
        contentId: String,
        settings: SeriesCustomSettings,
        profileId: Int = profileManager.activeProfileId.value,
        aliasIds: Collection<String> = emptyList()
    ) {
        val targets = (listOf(contentId) + aliasIds).filter { it.isNotBlank() }.distinct()
        for (targetId in targets) {
            saveInternal(targetId, settings, profileId)
        }
    }

    private suspend fun saveInternal(
        contentId: String,
        settings: SeriesCustomSettings,
        profileId: Int
    ) {
        if (contentId.isBlank()) return
        factory.get(profileId, "series_custom_settings").edit { prefs ->
            prefs[booleanPreferencesKey("disable_skips:$contentId")] = settings.disableSkips
            if (settings.preferredSourceAddon != null) {
                prefs[stringPreferencesKey("source_addon:$contentId")] = settings.preferredSourceAddon
            } else {
                prefs.remove(stringPreferencesKey("source_addon:$contentId"))
            }
            if (settings.preferredSourceKey != null) {
                prefs[stringPreferencesKey("source_key:$contentId")] = settings.preferredSourceKey
            } else {
                prefs.remove(stringPreferencesKey("source_key:$contentId"))
            }
            if (settings.preferredSourceName != null) {
                prefs[stringPreferencesKey("source_name:$contentId")] = settings.preferredSourceName
            } else {
                prefs.remove(stringPreferencesKey("source_name:$contentId"))
            }
            if (settings.preferredLanguage != null) {
                prefs[stringPreferencesKey("language:$contentId")] = settings.preferredLanguage
            } else {
                prefs.remove(stringPreferencesKey("language:$contentId"))
            }
            if (settings.sourceEpisode != null) {
                prefs[intPreferencesKey("source_episode:$contentId")] = settings.sourceEpisode
            } else {
                prefs.remove(intPreferencesKey("source_episode:$contentId"))
            }
            if (settings.sourceSeason != null) {
                prefs[intPreferencesKey("source_season:$contentId")] = settings.sourceSeason
            } else {
                prefs.remove(intPreferencesKey("source_season:$contentId"))
            }
            if (settings.detectedLanguages.isNotEmpty()) {
                prefs[stringPreferencesKey("source_languages:$contentId")] = settings.detectedLanguages.joinToString(",")
            } else {
                prefs.remove(stringPreferencesKey("source_languages:$contentId"))
            }
            if (settings.forceDubEnabled != null) {
                prefs[booleanPreferencesKey("force_dub:$contentId")] = settings.forceDubEnabled
            } else {
                prefs.remove(booleanPreferencesKey("force_dub:$contentId"))
            }
            if (!settings.forceDubTargetLanguage.isNullOrBlank()) {
                prefs[stringPreferencesKey("force_dub_lang:$contentId")] = settings.forceDubTargetLanguage
            } else {
                prefs.remove(stringPreferencesKey("force_dub_lang:$contentId"))
            }
        }
    }
}
