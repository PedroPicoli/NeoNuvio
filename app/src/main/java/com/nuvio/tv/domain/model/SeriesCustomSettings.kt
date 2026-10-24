package com.nuvio.tv.domain.model

data class SeriesCustomSettings(
    val disableSkips: Boolean = false,
    val preferredSourceAddon: String? = null,
    val preferredSourceKey: String? = null,
    val preferredSourceName: String? = null,
    val preferredLanguage: String? = null,
    val sourceEpisode: Int? = null,
    val sourceSeason: Int? = null,
    val detectedLanguages: List<String> = emptyList(),
    val forceDubEnabled: Boolean? = null
)
