package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.domain.model.LibraryEntry

/**
 * True quando o item está na lista "Completed" do Simkl. Esse status é a fonte de verdade para
 * "assistido": independe de os IDs (imdb/tmdb/simkl) coincidirem com os conjuntos de assistidos locais.
 */
internal fun LibraryEntry.isSimklCompleted(): Boolean =
    listKeys.any { it.equals("simkl:status:completed", ignoreCase = true) }

/**
 * True apenas para itens de fato em "Minha Lista" (Plan to Watch do Simkl ou watchlist de outro provedor).
 */
internal fun LibraryEntry.isInWatchlistKeys(): Boolean =
    listKeys.any { key ->
        key.contains("plantowatch", ignoreCase = true) ||
            key.contains("plan_to_watch", ignoreCase = true) ||
            key.contains("watchlist", ignoreCase = true)
    }
