package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.delay
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

private val IconButtonSize = 40.dp
private const val BUTTON_FOCUSED_SCALE = 1.05f

/** Round icon-only button used for month navigation. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = CircleShape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BUTTON_FOCUSED_SCALE),
        modifier = modifier.size(IconButtonSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = NuvioTheme.colors.TextPrimary,
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.xs)
        )
    }
}

/** Pill-shaped text button ("Today", "Next release"). */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.lg)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                shape = shape
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = shape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = BUTTON_FOCUSED_SCALE),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm)
        )
    }
}

/** One entry of [CalendarDropdown]. */
internal data class CalendarDropdownOption<T>(val value: T, val label: String)

/**
 * Pill anchor that opens a small menu; the selected option is highlighted and focused on open.
 * Same visual language as the Library pickers, sized for the month toolbar.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun <T> CalendarDropdown(
    label: String,
    selected: T,
    options: List<CalendarDropdownOption<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val selectedFocusRequester = remember { FocusRequester() }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label.orEmpty()

    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        var focused = selectedFocusRequester.requestFocusAfterFrames(frames = 3)
        var attempt = 0
        while (!focused && attempt < 6) {
            delay(32)
            focused = runCatching { selectedFocusRequester.requestFocus() }.getOrDefault(false)
            attempt++
        }
    }

    Box(modifier = modifier.onSizeChanged { anchorWidth = it.width }) {
        CalendarPillButton(
            text = "$label: $selectedLabel ▾",
            onClick = { expanded = !expanded }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = with(LocalDensity.current) { anchorWidth.toDp() }),
            shape = RoundedCornerShape(NuvioTheme.radii.lg),
            containerColor = NuvioTheme.colors.BackgroundCard,
            tonalElevation = NuvioTheme.spacing.none,
            shadowElevation = NuvioTheme.spacing.sm,
            border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
        ) {
            options.forEach { option ->
                var isFocused by remember { mutableStateOf(false) }
                val isSelected = option.value == selected
                val background = when {
                    isFocused -> NuvioTheme.colors.Secondary
                    isSelected -> NuvioTheme.colors.FocusBackground
                    else -> Color.Transparent
                }
                val textColor = if (isFocused) NuvioTheme.colors.OnSecondary else NuvioTheme.colors.TextPrimary
                DropdownMenuItem(
                    modifier = Modifier
                        .then(if (isSelected) Modifier.focusRequester(selectedFocusRequester) else Modifier)
                        .padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs)
                        .background(background, RoundedCornerShape(NuvioTheme.radii.md))
                        .onFocusChanged { isFocused = it.isFocused || it.hasFocus },
                    text = { Text(text = option.label, color = textColor, maxLines = 1) },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                    colors = MenuDefaults.itemColors(textColor = textColor)
                )
            }
        }
    }
}
