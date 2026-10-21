package com.nuvio.tv.ui.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

private data class NeoLibraryRowData(
    val title: String,
    val items: List<LibraryEntry>
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NeoLibraryContent(
    watchlistMovies: List<LibraryEntry>,
    watchlistSeries: List<LibraryEntry>,
    rewatchItems: List<LibraryEntry>,
    likedItems: List<LibraryEntry>,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>,
    posterCardStyle: PosterCardStyle,
    onItemClick: (LibraryEntry) -> Unit,
    onItemLongPress: (LibraryEntry) -> Unit,
    onItemFocused: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows = remember(watchlistMovies, watchlistSeries, rewatchItems, likedItems) {
        buildList {
            if (watchlistMovies.isNotEmpty()) {
                add(NeoLibraryRowData(title = "", items = watchlistMovies))
            }
            if (watchlistSeries.isNotEmpty()) {
                add(NeoLibraryRowData(title = "", items = watchlistSeries))
            }
            if (rewatchItems.isNotEmpty()) {
                add(NeoLibraryRowData(title = "", items = rewatchItems))
            }
            if (likedItems.isNotEmpty()) {
                add(NeoLibraryRowData(title = "", items = likedItems))
            }
        }
    }

    if (rows.isEmpty()) {
        EmptyScreenState(
            title = stringResource(R.string.library_empty_local_title, stringResource(R.string.library_type_items)),
            subtitle = stringResource(R.string.library_empty_local_subtitle)
        )
        return
    }

    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(60)
        primaryFocusRequester.requestFocus()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.lg, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        if (watchlistMovies.isNotEmpty()) {
            item(key = "neo_row_movies") {
                NeoInfiniteRow(
                    title = stringResource(R.string.neo_library_movies),
                    items = watchlistMovies,
                    watchedMovieIds = watchedMovieIds,
                    watchedSeriesIds = watchedSeriesIds,
                    posterCardStyle = posterCardStyle,
                    firstItemFocusRequester = primaryFocusRequester,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    onItemFocused = onItemFocused
                )
            }
        }

        if (watchlistSeries.isNotEmpty()) {
            item(key = "neo_row_series") {
                NeoInfiniteRow(
                    title = stringResource(R.string.neo_library_series),
                    items = watchlistSeries,
                    watchedMovieIds = watchedMovieIds,
                    watchedSeriesIds = watchedSeriesIds,
                    posterCardStyle = posterCardStyle,
                    firstItemFocusRequester = if (watchlistMovies.isEmpty()) primaryFocusRequester else null,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    onItemFocused = onItemFocused
                )
            }
        }

        if (rewatchItems.isNotEmpty()) {
            item(key = "neo_row_rewatch") {
                NeoInfiniteRow(
                    title = stringResource(R.string.neo_library_watch_again),
                    items = rewatchItems,
                    watchedMovieIds = watchedMovieIds,
                    watchedSeriesIds = watchedSeriesIds,
                    posterCardStyle = posterCardStyle,
                    firstItemFocusRequester = if (watchlistMovies.isEmpty() && watchlistSeries.isEmpty()) primaryFocusRequester else null,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    onItemFocused = onItemFocused
                )
            }
        }

        if (likedItems.isNotEmpty()) {
            item(key = "neo_row_liked") {
                NeoInfiniteRow(
                    title = stringResource(R.string.neo_library_liked),
                    items = likedItems,
                    watchedMovieIds = watchedMovieIds,
                    watchedSeriesIds = watchedSeriesIds,
                    posterCardStyle = posterCardStyle,
                    firstItemFocusRequester = if (watchlistMovies.isEmpty() && watchlistSeries.isEmpty() && rewatchItems.isEmpty()) primaryFocusRequester else null,
                    onItemClick = onItemClick,
                    onItemLongPress = onItemLongPress,
                    onItemFocused = onItemFocused
                )
            }
        }
    }
}

private const val INFINITE_LOOP_MULTIPLIER = 10_000

@Composable
private fun NeoInfiniteRow(
    title: String,
    items: List<LibraryEntry>,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>,
    posterCardStyle: PosterCardStyle,
    firstItemFocusRequester: FocusRequester?,
    onItemClick: (LibraryEntry) -> Unit,
    onItemLongPress: (LibraryEntry) -> Unit,
    onItemFocused: (LibraryEntry) -> Unit
) {
    if (items.isEmpty()) return

    val isInfinite = items.size > 1
    // Infinito somente para a direita: começa no índice 0 (esquerda para no primeiro item).
    val virtualCount = if (isInfinite) items.size * INFINITE_LOOP_MULTIPLIER else 1
    val startIndex = 0

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            ),
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)
        )

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
        ) {
            items(
                count = virtualCount,
                key = { virtualIndex ->
                    val actualIndex = Math.floorMod(virtualIndex, items.size)
                    val item = items[actualIndex]
                    "${item.type}:${item.id}:$virtualIndex"
                },
                contentType = { virtualIndex ->
                    val actualIndex = Math.floorMod(virtualIndex, items.size)
                    items[actualIndex].type
                }
            ) { virtualIndex ->
                val actualIndex = Math.floorMod(virtualIndex, items.size)
                val item = items[actualIndex]
                val isSeries = item.type.equals("series", ignoreCase = true) || item.type.equals("tv", ignoreCase = true)
                val targetSet = if (isSeries) watchedSeriesIds else watchedMovieIds
                val itemId = item.id
                val itemKey = itemId.lowercase()
                val itemRaw = itemId.substringAfter(':').lowercase()
                val isWatched = item.isSimklCompleted() ||
                    itemId in targetSet || itemKey in targetSet || (itemRaw.isNotEmpty() && itemRaw in targetSet)
                val preview = remember(item) {
                    item.toMetaPreview().copy(posterShape = PosterShape.POSTER)
                }

                val cardFocusRequester = if (virtualIndex == startIndex && firstItemFocusRequester != null) {
                    firstItemFocusRequester
                } else {
                    null
                }

                GridContentCard(
                    item = preview,
                    posterCardStyle = posterCardStyle,
                    isWatched = isWatched,
                    focusRequester = cardFocusRequester,
                    showLabel = true,
                    onFocused = { onItemFocused(item) },
                    onClick = { onItemClick(item) },
                    onLongPress = { onItemLongPress(item) }
                )
            }
        }
    }
}
