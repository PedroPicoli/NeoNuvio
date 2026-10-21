package com.nuvio.tv.ui.screens.library

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTitleLocalizationTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `TMDB localized title is applied to library items`() = runTest {
        val f = LibraryViewModelTestFixture()
        coEvery {
            f.tmdbMetadataService.fetchLocalizedTitle("100", ContentType.MOVIE, "pt-BR")
        } returns "A Origem"

        val entry = LibraryEntry(
            id = "movie:tmdb:100",
            type = "movie",
            name = "Inception",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = 8.8f,
            genres = listOf("Sci-Fi"),
            addonBaseUrl = null,
            listKeys = setOf(f.tab.key),
            tmdbId = 100
        )

        f.items.value = listOf(entry)
        runCurrent()

        val visible = f.viewModel.uiState.value.visibleItems
        assertEquals(1, visible.size)
        assertEquals("A Origem", visible.first().name)
        assertEquals("A Origem", f.viewModel.uiState.value.allItems.first().name)
    }

    @Test
    fun `Original title is preserved if TMDB returns no localized title`() = runTest {
        val f = LibraryViewModelTestFixture()
        coEvery {
            f.tmdbMetadataService.fetchLocalizedTitle("200", ContentType.MOVIE, "pt-BR")
        } returns null

        val entry = LibraryEntry(
            id = "movie:tmdb:200",
            type = "movie",
            name = "Original Title Only",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2020",
            imdbRating = 7.0f,
            genres = listOf("Drama"),
            addonBaseUrl = null,
            listKeys = setOf(f.tab.key),
            tmdbId = 200
        )

        f.items.value = listOf(entry)
        runCurrent()

        val visible = f.viewModel.uiState.value.visibleItems
        assertEquals("Original Title Only", visible.first().name)
    }

    @Test
    fun `Relocalizes titles when TMDB language setting changes`() = runTest {
        val f = LibraryViewModelTestFixture()
        coEvery {
            f.tmdbMetadataService.fetchLocalizedTitle("100", ContentType.MOVIE, "pt-BR")
        } returns "A Origem"
        coEvery {
            f.tmdbMetadataService.fetchLocalizedTitle("100", ContentType.MOVIE, "es-ES")
        } returns "El Origen"

        val entry = LibraryEntry(
            id = "movie:tmdb:100",
            type = "movie",
            name = "Inception",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2010",
            imdbRating = 8.8f,
            genres = listOf("Sci-Fi"),
            addonBaseUrl = null,
            listKeys = setOf(f.tab.key),
            tmdbId = 100
        )

        f.items.value = listOf(entry)
        runCurrent()
        assertEquals("A Origem", f.viewModel.uiState.value.visibleItems.first().name)

        // Change TMDB language to Spanish
        f.tmdbSettings.value = TmdbSettings(language = "es-ES")
        runCurrent()

        assertEquals("El Origen", f.viewModel.uiState.value.visibleItems.first().name)
    }
}
