package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Posters stacked inside a day cell before collapsing the rest into a "+N" badge. */
private const val MAX_STACKED_POSTERS = 3
private const val PAST_DAY_CONTENT_ALPHA = 0.45f
private val MiniPosterWidth = 18.dp
private val MiniPosterHeight = 27.dp
private val MiniPosterOverlapStep = 12.dp
private val DayNumberSize = 20.dp

/**
 * Weekday header plus a 6x7 grid of focusable day cells. Focusing a cell selects that day;
 * OK on a day that has releases hands focus to the day panel via [onDayClick].
 */
@Composable
internal fun CalendarMonthGrid(
    uiState: CalendarUiState,
    firstDayOfWeek: DayOfWeek,
    locale: Locale,
    selectedDayFocusRequester: FocusRequester,
    onDayFocused: (LocalDate) -> Unit,
    onDayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val weeks = remember(uiState.visibleMonth, firstDayOfWeek) {
        buildMonthGrid(uiState.visibleMonth, firstDayOfWeek)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(firstDayOfWeek = firstDayOfWeek, locale = locale)
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
        ) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(modifier = Modifier.weight(1f).fillMaxHeight())
                        } else {
                            DayCell(
                                date = date,
                                events = uiState.eventsOn(date),
                                isToday = date == uiState.today,
                                isPast = date.isBefore(uiState.today),
                                locale = locale,
                                focusRequester = selectedDayFocusRequester.takeIf { date == uiState.selectedDate },
                                onFocused = { onDayFocused(date) },
                                onClick = onDayClick,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WeekdayHeader(firstDayOfWeek: DayOfWeek, locale: Locale) {
    val weekdays = remember(firstDayOfWeek) { weekdayOrder(firstDayOfWeek) }
    Row(modifier = Modifier.fillMaxWidth()) {
        weekdays.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayCell(
    date: LocalDate,
    events: List<CalendarEvent>,
    isToday: Boolean,
    isPast: Boolean,
    locale: Locale,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val hasEvents = events.isNotEmpty()
    // One thumbnail per distinct title so a double episode doesn't show the same poster twice.
    val distinctTitles = remember(events) { events.distinctBy { it.itemType + it.itemId } }
    val dateFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val releasesLabel = pluralStringResource(R.plurals.calendar_releases_count, events.size, events.size)
    val cellDescription = remember(date, events.size, releasesLabel) {
        "${date.format(dateFormatter)}, $releasesLabel"
    }

    Surface(
        // OK hands focus to the day panel (first card, or the "next release" button on empty days).
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { contentDescription = cellDescription },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (hasEvents) NuvioTheme.colors.BackgroundCard else NuvioTheme.colors.Surface,
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
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        // Dim only the content: the focus ring must stay fully visible on past days.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(NuvioTheme.spacing.xs)
                .alpha(if (isPast) PAST_DAY_CONTENT_ALPHA else 1f),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DayNumber(day = date.dayOfMonth, isToday = isToday)
                val overflow = distinctTitles.size - MAX_STACKED_POSTERS
                if (overflow > 0) OverflowBadge(count = overflow)
            }
            StackedPosters(events = distinctTitles.take(MAX_STACKED_POSTERS))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayNumber(day: Int, isToday: Boolean) {
    Box(
        modifier = Modifier
            .size(DayNumberSize)
            .then(if (isToday) Modifier.background(NuvioTheme.colors.FocusRing, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
            color = if (isToday) NuvioTheme.colors.Background else NuvioTheme.colors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverflowBadge(count: Int) {
    Text(
        text = stringResource(R.string.calendar_more_count, count),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = Modifier
            .background(NuvioTheme.colors.SurfaceVariant, CircleShape)
            .padding(horizontal = NuvioTheme.spacing.xs, vertical = 1.dp)
    )
}

/** Up to [MAX_STACKED_POSTERS] thumbnails fanned horizontally so they fit a narrow cell. */
@Composable
private fun StackedPosters(events: List<CalendarEvent>) {
    if (events.isEmpty()) {
        Spacer(modifier = Modifier.height(MiniPosterHeight))
        return
    }
    val stackWidth = MiniPosterWidth + MiniPosterOverlapStep * (events.size - 1)
    Box(modifier = Modifier.size(width = stackWidth, height = MiniPosterHeight)) {
        // Later posters are drawn first so the leftmost one sits on top of the stack.
        events.asReversed().forEachIndexed { reversedIndex, event ->
            val index = events.lastIndex - reversedIndex
            MiniPoster(
                event = event,
                modifier = Modifier.offset(x = MiniPosterOverlapStep * index)
            )
        }
    }
}

@Composable
private fun MiniPoster(event: CalendarEvent, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(NuvioTheme.radii.xxs)
    val request = remember(event.poster) {
        ImageRequest.Builder(context)
            .data(event.poster)
            .crossfade(true)
            .build()
    }
    Box(
        modifier = modifier
            .size(width = MiniPosterWidth, height = MiniPosterHeight)
            .clip(shape)
            .background(NuvioTheme.colors.SurfaceVariant)
    ) {
        if (!event.poster.isNullOrBlank()) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
