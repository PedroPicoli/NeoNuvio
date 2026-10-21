package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry
import java.util.Calendar

/**
 * Motor inteligente de recomendação em memória para a NeoBiblioteca.
 * Executa computação puramente matemática não bloqueante.
 */
object NeoLibraryRecommendationEngine {

    private val LIGHT_DAYTIME_GENRES = setOf(
        "animation", "comedy", "family", "adventure", "documentary", "kids", "musical"
    )

    private val DENSE_NIGHT_GENRES = setOf(
        "drama", "thriller", "crime", "mystery", "horror", "sci-fi", "science fiction", "action", "war"
    )

    /**
     * Ordena uma lista de entradas da biblioteca considerando:
     * - Momento do dia (conteúdos leves de dia, densos à noite/fim de semana)
     * - Afinidade com gêneros assistidos recentemente
     * - Novas temporadas ou lançamentos recentes de séries
     * - Decaimento temporal com gatilho determinístico de "Revival" diário para itens esquecidos
     * - Popularidade social e premiações
     */
    fun rank(
        items: List<LibraryEntry>,
        recentWatchedGenres: Set<String> = emptySet(),
        currentTimeMillis: Long = System.currentTimeMillis()
    ): List<LibraryEntry> {
        if (items.size <= 1) return items

        val calendar = Calendar.getInstance().apply { timeInMillis = currentTimeMillis }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isDaytime = hour in 6..17
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY || (dayOfWeek == Calendar.FRIDAY && hour >= 18)

        val normalizedRecentGenres = recentWatchedGenres.map { it.trim().lowercase() }.toSet()

        // Itens adicionados há mais de 45 dias são elegíveis para o gatilho de Revival
        val fortyFiveDaysAgo = currentTimeMillis - (45L * 24 * 60 * 60 * 1000)
        val oldItems = items.filter { it.listedAt > 0L && it.listedAt < fortyFiveDaysAgo }
        val daySeed = calendar.get(Calendar.YEAR) * 366 + calendar.get(Calendar.DAY_OF_YEAR)
        val revivalItemId = if (oldItems.isNotEmpty()) {
            val selectedIdx = Math.floorMod(daySeed, oldItems.size)
            oldItems[selectedIdx].id
        } else {
            null
        }

        return items.sortedByDescending { entry ->
            computeScore(
                entry = entry,
                isDaytime = isDaytime,
                isWeekend = isWeekend,
                normalizedRecentGenres = normalizedRecentGenres,
                revivalItemId = revivalItemId,
                currentTimeMillis = currentTimeMillis
            )
        }
    }

    private fun computeScore(
        entry: LibraryEntry,
        isDaytime: Boolean,
        isWeekend: Boolean,
        normalizedRecentGenres: Set<String>,
        revivalItemId: String?,
        currentTimeMillis: Long
    ): Float {
        var score = 100f

        val entryGenres = entry.genres.map { it.trim().lowercase() }

        // 1. Momento do dia / Fim de semana
        if (isDaytime) {
            if (entryGenres.any { it in LIGHT_DAYTIME_GENRES }) {
                score += 30f
            }
        } else {
            if (entryGenres.any { it in DENSE_NIGHT_GENRES }) {
                score += 30f
            }
        }
        if (isWeekend) {
            if (entryGenres.any { it in setOf("action", "adventure", "sci-fi", "fantasy") }) {
                score += 20f
            }
        }

        // 2. Afinidade com assistidos recentemente
        val matchingRecentCount = entryGenres.count { it in normalizedRecentGenres }
        score += (matchingRecentCount * 25f).coerceAtMost(75f)

        // 3. Novas temporadas / lançamentos recentes de séries
        val isSeries = !entry.type.equals("movie", ignoreCase = true)
        if (isSeries) {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            val releaseYear = entry.releaseInfo?.filter { it.isDigit() }?.take(4)?.toIntOrNull()
            if (releaseYear != null && releaseYear >= currentYear - 1) {
                score += 35f
            }
        }

        // 4. Decaimento temporal e gatilho diário de Revival
        val ageDays = if (entry.listedAt > 0L) {
            ((currentTimeMillis - entry.listedAt) / (1000L * 60 * 60 * 24)).coerceAtLeast(0)
        } else {
            30L
        }

        if (entry.id == revivalItemId) {
            // Revival: Super boost para trazer o item esquecido para o topo do dia!
            score += 150f
        } else if (ageDays <= 14) {
            // Frescor de itens adicionados recentemente
            score += (14 - ageDays) * 3f
        } else {
            // Decaimento temporal suave
            score -= (ageDays * 0.15f).coerceAtMost(30f)
        }

        // 5. Gatilho social de popularidade / prêmios
        entry.imdbRating?.let { rating ->
            score += rating * 4f
        }
        val descLower = (entry.description.orEmpty() + " " + entry.genres.joinToString()).lowercase()
        if (descLower.contains("award") || descLower.contains("oscar") || descLower.contains("emmy") || descLower.contains("vencedor")) {
            score += 20f
        }

        return score
    }
}
