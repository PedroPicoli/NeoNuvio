package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.layout.Arrangement
import com.nuvio.tv.domain.model.CalendarEventOrigin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Compact poster so a typical day (3-6 releases) fits the panel without scrolling. */
private val PanelPosterStyle: PosterCardStyle = PosterCardDefaults.Style.copy(width = 90.dp, height = 135.dp)
private val EmptyDayIconSize = 72.dp

/**
 * Releases for the selected day as focusable poster cards, with a watched marker on those the
 * user already saw. Cards in the first column send LEFT back to [exitLeftFocusRequester] so the
 * user lands on the day they came from. On an empty day the "next release" button takes the
 * [firstCardFocusRequester] instead, so OK on the day still has somewhere useful to go.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CalendarDayPanel(
    date: LocalDate,
    today: LocalDate,
    events: List<CalendarEvent>,
    watchedEventKeys: Set<String>,
    hasNextRelease: Boolean,
    locale: Locale,
    firstCardFocusRequester: FocusRequester,
    exitLeftFocusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onJumpToNextRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.onFocusChanged { onFocusChanged(it.hasFocus) }) {
        DayPanelHeader(date = date, today = today, releaseCount = events.size, locale = locale)
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        if (events.isEmpty()) {
            EmptyDayState(
                hasNextRelease = hasNextRelease,
                focusRequester = firstCardFocusRequester,
                exitLeftFocusRequester = exitLeftFocusRequester,
                onJumpToNextRelease = onJumpToNextRelease
            )
            return@Column
        }

        EventGrid(
            events = events,
            watchedEventKeys = watchedEventKeys,
            firstCardFocusRequester = firstCardFocusRequester,
            exitLeftFocusRequester = exitLeftFocusRequester,
            onEventClick = onEventClick
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayPanelHeader(date: LocalDate, today: LocalDate, releaseCount: Int, locale: Locale) {
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val dateLabel = remember(date, dateFormatter) {
        date.format(dateFormatter).replaceFirstChar { it.titlecase(locale) }
    }
    val relativeLabel = when (date) {
        today -> stringResource(R.string.calendar_today)
        today.plusDays(1) -> stringResource(R.string.calendar_tomorrow)
        today.minusDays(1) -> stringResource(R.string.calendar_yesterday)
        else -> null
    }
    val countLabel = if (releaseCount > 0) {
        pluralStringResource(R.plurals.calendar_releases_count, releaseCount, releaseCount)
    } else {
        null
    }
    val subtitle = listOfNotNull(relativeLabel, countLabel).joinToString(" · ").uppercase(locale)

    Text(
        text = dateLabel,
        style = MaterialTheme.typography.titleLarge,
        color = NuvioTheme.colors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (subtitle.isNotBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = if (relativeLabel != null) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextTertiary,
            letterSpacing = 1.5.sp
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyDayState(
    hasNextRelease: Boolean,
    focusRequester: FocusRequester,
    exitLeftFocusRequester: FocusRequester,
    onJumpToNextRelease: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.EventBusy,
            contentDescription = null,
            modifier = Modifier.size(EmptyDayIconSize),
            tint = NuvioTheme.colors.TextTertiary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Text(
            text = stringResource(R.string.calendar_empty_day_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.calendar_empty_day_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        if (hasNextRelease) {
            Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
            CalendarPillButton(
                text = stringResource(R.string.calendar_jump_next_release),
                onClick = onJumpToNextRelease,
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusProperties { left = exitLeftFocusRequester }
            )
        }
    }
}

@Composable
private fun EventGrid(
    events: List<CalendarEvent>,
    watchedEventKeys: Set<String>,
    firstCardFocusRequester: FocusRequester,
    exitLeftFocusRequester: FocusRequester,
    onEventClick: (CalendarEvent) -> Unit
) {
    val cardSpacing = NuvioTheme.spacing.sm
    // Room for the focused card's scale + ring, which would otherwise be clipped by the grid bounds.
    val focusBleed = NuvioTheme.spacing.sm
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Fixed column count so "first column" is known for the LEFT exit rule.
        val usableWidth = maxWidth - focusBleed * 2
        val columns = ((usableWidth + cardSpacing) / (PanelPosterStyle.width + cardSpacing))
            .toInt()
            .coerceAtLeast(1)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = focusBleed,
                end = focusBleed,
                top = focusBleed,
                bottom = NuvioTheme.spacing.xl
            ),
            horizontalArrangement = Arrangement.spacedBy(cardSpacing),
            verticalArrangement = Arrangement.spacedBy(cardSpacing)
        ) {
            itemsIndexed(events, key = { _, event -> event.key }) { index, event ->
                CalendarEventCard(
                    event = event,
                    isWatched = event.key in watchedEventKeys,
                    onClick = { onEventClick(event) },
                    focusRequester = firstCardFocusRequester.takeIf { index == 0 },
                    leftFocusRequester = exitLeftFocusRequester.takeIf { index % columns == 0 }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarEventCard(
    event: CalendarEvent,
    isWatched: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    leftFocusRequester: FocusRequester?
) {
    val preview = remember(event) { event.toMetaPreview() }
    // The card owns focus; the captions below only need to know about it to marquee in sync.
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .focusProperties {
                if (leftFocusRequester != null) left = leftFocusRequester
            }
            .onFocusChanged { isFocused = it.hasFocus }
    ) {
        GridContentCard(
            item = preview,
            onClick = onClick,
            posterCardStyle = PanelPosterStyle,
            isWatched = isWatched,
            focusRequester = focusRequester
        )
        val caption = calendarEventCaption(event)
        if (caption.isNotBlank()) {
            FocusMarqueeText(
                text = caption,
                focused = isFocused,
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier
                    .width(PanelPosterStyle.width)
                    .padding(top = NuvioTheme.spacing.xxs)
            )
        }
        if (event.origin == CalendarEventOrigin.WATCHING) {
            Text(
                text = stringResource(R.string.calendar_origin_watching).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.FocusRing,
                letterSpacing = 1.sp,
                maxLines = 1,
                modifier = Modifier.width(PanelPosterStyle.width)
            )
        }
    }
}
