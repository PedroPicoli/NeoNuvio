package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAccessTest {
    @Test
    fun allThemesAndGradientsAreAvailableToAllUsers() {
        val themes = availableAppThemes(CosmeticEntitlements.None)

        assertEquals(AppTheme.IMMERSIVE, themes.first())
        assertTrue(AppTheme.CUSTOM in themes)
        assertTrue(AppTheme.GOLD in themes)
        assertTrue(AppTheme.JADE in themes)
        assertTrue(AppTheme.ROSE_GOLD in themes)
        assertTrue(AppTheme.ARCTIC_BLUE in themes)
        assertTrue(AppTheme.GRAPHITE in themes)
        assertEquals(AppTheme.CUSTOM, resolveAppTheme(AppTheme.CUSTOM, CosmeticEntitlements.None))
        assertEquals(AppTheme.GOLD, resolveAppTheme(AppTheme.GOLD, CosmeticEntitlements.None))
        assertEquals(AppTheme.WHITE, resolveAppTheme(null, CosmeticEntitlements.None))
        assertEquals(themes.size, themes.distinct().size)
    }

    @Test
    fun allUsersCanApplyMultipleCustomColors() {
        val gradient = CustomThemeColors(0xFF0000, 0x00FF00, 0x0000FF)
        val solid = CustomThemeColors.solid(0x00FF00)

        assertEquals(gradient, resolveCustomThemeColors(gradient, null))
        assertEquals(solid, resolveCustomThemeColors(solid, null))
    }

    @Test
    fun explicitThemeSelectionIsPreserved() {
        assertEquals(
            AppTheme.OCEAN,
            resolveAppTheme(AppTheme.OCEAN, CosmeticEntitlements.None)
        )
    }
}
