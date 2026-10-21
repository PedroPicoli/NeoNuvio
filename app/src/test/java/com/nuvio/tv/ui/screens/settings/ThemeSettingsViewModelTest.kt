package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.data.local.ThemeDataStore
import com.nuvio.tv.data.repository.MemberAccessRepository
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.domain.model.CustomThemeColors
import com.nuvio.tv.domain.model.MemberAccess
import com.nuvio.tv.domain.model.SettingsUiStyle
import com.nuvio.tv.domain.model.ThemeSelection
import com.nuvio.tv.launcher.AppIconManager
import com.nuvio.tv.launcher.AppIconSettingsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeSettingsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val gradient = CustomThemeColors(0x112233, 0x445566, 0x778899)
    private val solid = CustomThemeColors.solid(0x445566)
    private val selection = MutableStateFlow(ThemeSelection(AppTheme.CUSTOM, gradient))
    private val access = MutableStateFlow(MemberAccess.None)
    private val store = mockk<ThemeDataStore> {
        every { themeSelection } returns selection
        every { selectedFont } returns flowOf(AppFont.INTER)
        every { amoledMode } returns flowOf(false)
        every { amoledSurfacesMode } returns flowOf(false)
        every { settingsUiStyle } returns flowOf(SettingsUiStyle.CLASSIC)
        coEvery { setCustomTheme(any()) } coAnswers {
            selection.value = ThemeSelection(AppTheme.CUSTOM, firstArg())
        }
    }

    @Test
    fun allUsersSeeAndSaveGradientCustomThemes() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        assertTrue(AppTheme.CUSTOM in viewModel.uiState.value.availableThemes)
        assertEquals(AppTheme.CUSTOM, viewModel.uiState.value.selectedTheme)
        assertEquals(gradient, viewModel.uiState.value.customThemeColors)
        assertTrue(viewModel.uiState.value.customThemeGradientEnabled)

        viewModel.onEvent(ThemeSettingsEvent.SaveCustomTheme(gradient))
        runCurrent()

        coVerify(exactly = 1) { store.setCustomTheme(gradient) }
        assertEquals(ThemeSelection(AppTheme.CUSTOM, gradient), selection.value)
    }

    private fun createViewModel(): ThemeSettingsViewModel {
        val members = mockk<MemberAccessRepository> { every { access } returns this@ThemeSettingsViewModelTest.access }
        val icons = mockk<AppIconManager> { every { state } returns MutableStateFlow(AppIconSettingsState()) }
        return ThemeSettingsViewModel(store, members, icons)
    }
}
