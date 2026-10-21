package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armazena as preferências/favoritos exclusivos da tela de Busca:
 * Para cada tipo ("movie", "series", "tv"), permite fixar um catálogo e gênero padrão
 * definido pelo usuário através do menu de opções.
 */
@Singleton
class SearchTypePreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "search_type_preferences"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun catalogKey(type: String) = stringPreferencesKey("pref_catalog_${type.lowercase()}")
    private fun genreKey(type: String) = stringPreferencesKey("pref_genre_${type.lowercase()}")

    suspend fun getPreference(type: String): Pair<String?, String?> {
        val prefs = store().data.first()
        val cat = prefs[catalogKey(type)]?.trim()?.takeIf { it.isNotEmpty() }
        val genre = prefs[genreKey(type)]?.trim()?.takeIf { it.isNotEmpty() }
        return cat to genre
    }

    suspend fun setPreference(type: String, catalogKey: String?, genre: String?) {
        store().edit { preferences ->
            if (catalogKey.isNullOrBlank()) {
                preferences.remove(catalogKey(type))
            } else {
                preferences[catalogKey(type)] = catalogKey.trim()
            }

            if (genre.isNullOrBlank() || genre == "__all__") {
                preferences.remove(genreKey(type))
            } else {
                preferences[genreKey(type)] = genre.trim()
            }
        }
    }

    suspend fun clearPreference(type: String) {
        store().edit { preferences ->
            preferences.remove(catalogKey(type))
            preferences.remove(genreKey(type))
        }
    }
}
