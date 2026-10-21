package com.nuvio.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarEventOrigin
import com.nuvio.tv.domain.model.CalendarReleaseKind
import com.nuvio.tv.ui.components.FocusMarqueeText
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Date column on the left of every day group; wide enough for "MER" over a two-digit number. */
private val DateRailWidth = 72.dp
private val TodayBadgeSize = 44.dp
private val RowPosterWidth = 64.dp
private val RowPosterHeight = 96.dp
private val EmptyMonthIconSize = 72.dp
private const val PAST_ROW_CONTENT_ALPHA = 0.5f
private const val INITIAL_FOCUS_ATTEMPTS = 10
private const val INITIAL_FOCUS_RETRY_MS = 48L
private val DashedBorderInterval = floatArrayOf(12f, 10f)

/** One row of the agenda. Day groups have no separate header: the first row of a day carries the date rail. */
private sealed class AgendaRow(val key: String) {
    class Release(val event: CalendarEvent, val isFirstOfDay: Boolean, val dayCount: Int) :
        AgendaRow("event:${event.key}")

    /** Today without releases: keeps the day in the timeline so the eye (and the scroll) can anchor on it. */
    class EmptyToday(val date: LocalDate, val nextRelease: LocalDate?) : AgendaRow("today:$date")
}

/**
 * Month as a vertical agenda, in the style of a TV "coming soon" timeline: a date rail on the
 * left (weekday, big day number, release count) and one focusable card per release next to it.
 *
 * Focus lands on the anchor day — the selected day, else the next day with releases — and a
 * programmatic landing never moves the selection, so switching back to the grid returns to the
 * day the user was on. Moving the D-pad over a row selects its day; OK opens the title.
 */
@Composable
internal fun CalendarAgendaList(
    uiState: CalendarUiState,
    locale: Locale,
    selectedDayFocusRequester: FocusRequester,
    exitUpFocusRequester: FocusRequester,
    /** Bumped by the screen whenever it re-requests focus on the selected day (e.g. "Today"). */
    landingRequestId: Int,
    onDayFocused: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    /** DPAD_UP on the first row moves here instead of the toolbar when an earlier month exists. */
    onRequestPreviousMonth: () -> Unit,
    /** DPAD_DOWN on the last row moves here when a later month exists. */
    onRequestNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val releaseDays = uiState.visibleMonthDays
    val rows = remember(uiState.eventsByDate, uiState.visibleMonth, uiState.today) {
        agendaDays(releaseDays, uiState.today, uiState.visibleMonth).flatMap { date ->
            val events = uiState.eventsOn(date)
            if (events.isEmpty()) {
                listOf(AgendaRow.EmptyToday(date, uiState.nextReleaseAfter(date)))
            } else {
                events.mapIndexed { index, event -> AgendaRow.Release(event, isFirstOfDay = index == 0, dayCount = events.size) }
            }
        }
    }
    if (releaseDays.isEmpty()) {
        EmptyMonthState(focusRequester = selectedDayFocusRequester, modifier = modifier)
        return
    }
    // Row keys (not event keys) so the anchor can be looked up in [rows] directly.
    val releaseRows = rows.filterIsInstance<AgendaRow.Release>()
    val firstRowKey = releaseRows.firstOrNull()?.key
    val lastRowKey = releaseRows.lastOrNull()?.key
    val anchorDate = remember(releaseDays, uiState.selectedDate) { agendaAnchorDate(releaseDays, uiState.selectedDate) }
    val anchorKey = remember(rows, anchorDate) { releaseRows.firstOrNull { it.event.date == anchorDate }?.key }

    val listState = rememberLazyListState()
    // The key the next focus event is allowed to ignore: set right before every programmatic
    // landing, cleared by the first row that reports focus. User D-pad moves always select.
    var pendingLandingKey by remember { mutableStateOf<String?>(null) }
    // Rows arrive after the first frame (the store is read asynchronously), so the screen-level
    // focus request can find nothing to land on. Land here, once the anchor row exists.
    //
    // Deliberately NOT keyed on anchorKey/visibleMonth directly: both change on every plain toolbar
    // month-arrow press too (the anchor is month-derived), which would steal focus back into the
    // list on every press instead of leaving it on the arrow for fast paging. Filter changes still
    // relant (the focused row may have just been filtered out); explicit jumps (Today, jump-to-next-
    // release, crossing a month boundary from this list) opt in via landingRequestId.
    LaunchedEffect(uiState.filter, landingRequestId) {
        anchorKey ?: return@LaunchedEffect
        pendingLandingKey = anchorKey
        // A scroll before the first measure pass is dropped: wait until the list has laid out rows.
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        val anchorIndex = rows.indexOfFirst { it.key == anchorKey }
        if (anchorIndex >= 0) {
            // Keep an empty "today" placeholder visible above the next release it points to.
            val target = if (anchorIndex > 0 && rows[anchorIndex - 1] is AgendaRow.EmptyToday) anchorIndex - 1 else anchorIndex
            listState.scrollToItem(target)
        }
        // requestFocus() answers false (no exception) while the row is composed but not yet placed.
        repeat(INITIAL_FOCUS_ATTEMPTS) {
            if (runCatching { selectedDayFocusRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(INITIAL_FOCUS_RETRY_MS)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is AgendaRow.EmptyToday -> EmptyTodayRow(row = row, locale = locale)
                is AgendaRow.Release -> {
                    val isPast = row.event.date.isBefore(uiState.today)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (row.isFirstOfDay) NuvioTheme.spacing.sm else NuvioTheme.spacing.none),
                        verticalAlignment = Alignment.Top
                    ) {
                        DateRail(
                            date = row.event.date.takeIf { row.isFirstOfDay },
                            isToday = row.event.date == uiState.today,
                            isPast = isPast,
                            count = row.dayCount,
                            locale = locale
                        )
                        AgendaReleaseRow(
                            event = row.event,
                            isPast = isPast,
                            focusRequester = selectedDayFocusRequester.takeIf { row.key == anchorKey },
                            // The rail is not focusable, so the first card must hand UP to the toolbar itself
                            // — but only when there is no earlier month to cross into instead.
                            upFocusRequester = exitUpFocusRequester.takeIf {
                                row.key == firstRowKey && !uiState.canShowPreviousMonth
                            },
                            onUpAtBoundary = onRequestPreviousMonth.takeIf {
                                row.key == firstRowKey && uiState.canShowPreviousMonth
                            },
                            onDownAtBoundary = onRequestNextMonth.takeIf {
                                row.key == lastRowKey && uiState.canShowNextMonth
                            },
                            onFocused = {
                                val wasLanding = pendingLandingKey == row.key
                                pendingLandingKey = null
                                if (!wasLanding) onDayFocused(row.event.date)
                            },
                            onClick = { onEventClick(row.event) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** Weekday, big day number and release count; [date] is null on rows that continue a day. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DateRail(date: LocalDate?, isToday: Boolean, isPast: Boolean, count: Int, locale: Locale) {
    Column(
        modifier = Modifier
            .width(DateRailWidth)
            .padding(top = NuvioTheme.spacing.xs, end = NuvioTheme.spacing.sm)
            .alpha(if (isPast) PAST_ROW_CONTENT_ALPHA else 1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (date == null) return@Column
        Text(
            text = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale).uppercase(locale),
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextTertiary,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp
        )
        Box(
            modifier = Modifier
                .size(TodayBadgeSize)
                .then(if (isToday) Modifier.background(NuvioTheme.colors.FocusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (isToday) NuvioTheme.colors.Background else NuvioTheme.colors.TextPrimary
            )
        }
        if (count > 1) {
            Text(
                text = pluralStringResource(R.plurals.calendar_releases_count, count, count),
                style = MaterialTheme.typography.labelSmall,
                color = NuvioTheme.colors.TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** Today with nothing on it: a dashed, non-focusable placeholder that points at the next release. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyTodayRow(row: AgendaRow.EmptyToday, locale: Locale) {
    val borderColor = NuvioTheme.colors.Border
    val radius = NuvioTheme.radii.sm
    val formatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val nextLabel = row.nextRelease?.let { next ->
        stringResource(R.string.calendar_next_release_hint, next.format(formatter).replaceFirstChar { it.titlecase(locale) })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        DateRail(date = row.date, isToday = true, isPast = false, count = 0, locale = locale)
        Column(
            modifier = Modifier
                .weight(1f)
                .drawBehind {
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(DashedBorderInterval))
                    )
                }
                .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            Text(
                text = stringResource(R.string.calendar_no_releases_today),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextSecondary
            )
            if (nextLabel != null) {
                Text(
                    text = nextLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextTertiary
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AgendaReleaseRow(
    event: CalendarEvent,
    isPast: Boolean,
    focusRequester: FocusRequester?,
    upFocusRequester: FocusRequester?,
    /** Non-null on the first row when an earlier month exists: DPAD_UP loads it instead of moving focus. */
    onUpAtBoundary: (() -> Unit)?,
    /** Non-null on the last row when a later month exists: DPAD_DOWN loads it instead of doing nothing. */
    onDownAtBoundary: (() -> Unit)?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (upFocusRequester != null) Modifier.focusProperties { up = upFocusRequester } else Modifier)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionUp -> onUpAtBoundary?.let { it(); true } ?: false
                    Key.DirectionDown -> onDownAtBoundary?.let { it(); true } ?: false
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            focusedContainerColor = NuvioTheme.colors.FocusBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border), shape = shape),
            focusedBorder = Border(NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
        ),
        // No scale: a full-width row would clip against the list bounds; the ring and tint carry the focus.
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.sm)
                .alpha(if (isPast) PAST_ROW_CONTENT_ALPHA else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            RowPoster(poster = event.poster)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                FocusMarqueeText(
                    text = event.title,
                    focused = isFocused,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                val caption = agendaEpisodeCaption(event)
                if (caption.isNotBlank()) {
                    FocusMarqueeText(
                        text = caption,
                        focused = isFocused,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(NuvioTheme.spacing.xxs))
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                    event.kind?.let { kind -> AgendaChip(text = stringResource(kind.labelRes()), accent = kind == CalendarReleaseKind.DIGITAL) }
                    if (event.isAnime) AgendaChip(text = stringResource(R.string.calendar_filter_anime))
                    if (event.origin == CalendarEventOrigin.WATCHING) {
                        AgendaChip(text = stringResource(R.string.calendar_origin_watching), accent = true)
                    }
                }
            }
        }
    }
}

/** Small uppercase pill; [accent] ones use the focus colour so they read from across the room. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AgendaChip(text: String, accent: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = if (accent) NuvioTheme.colors.FocusRing else NuvioTheme.colors.TextSecondary,
        modifier = Modifier
            .background(NuvioTheme.colors.SurfaceVariant, RoundedCornerShape(NuvioTheme.radii.full))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
    )
}

@Composable
private fun RowPoster(poster: String?) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(NuvioTheme.radii.xs)
    val request = remember(poster) { ImageRequest.Builder(context).data(poster).crossfade(true).build() }
    Box(
        modifier = Modifier
            .size(width = RowPosterWidth, height = RowPosterHeight)
            .clip(shape)
            .background(NuvioTheme.colors.SurfaceVariant)
    ) {
        if (!poster.isNullOrBlank()) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyMonthState(focusRequester: FocusRequester, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.EventBusy,
            contentDescription = null,
            modifier = Modifier.size(EmptyMonthIconSize),
            tint = NuvioTheme.colors.TextTertiary
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        Text(
            text = stringResource(R.string.calendar_empty_month_title),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.xs))
        Text(
            text = stringResource(R.string.calendar_empty_month_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            textAlign = TextAlign.Center
        )
        // Keeps the shared requester attached so month navigation never targets a detached node.
        Spacer(modifier = Modifier.width(1.dp).focusRequester(focusRequester))
    }
}

/** "S1E5 · Episode title" for episodes; movies carry their kind as a chip instead. */
@Composable
private fun agendaEpisodeCaption(event: CalendarEvent): String = buildList {
    if (event.isEpisode) {
        add(stringResource(R.string.calendar_episode_label, event.season ?: 0, event.episode ?: 0))
        event.episodeTitle?.let(::add)
    }
}.joinToString(" · ")

/** "S1E5 · Episode title" for episodes, "Digital" / "Theaters" / "Physical" for typed movie dates. */
@Composable
internal fun calendarEventCaption(event: CalendarEvent): String = buildList {
    if (event.isEpisode) {
        add(stringResource(R.string.calendar_episode_label, event.season ?: 0, event.episode ?: 0))
        event.episodeTitle?.let(::add)
    }
    event.kind?.let { kind -> add(stringResource(kind.labelRes())) }
}.joinToString(" · ")

internal fun CalendarReleaseKind.labelRes(): Int = when (this) {
    CalendarReleaseKind.THEATRICAL -> R.string.calendar_release_theatrical
    CalendarReleaseKind.DIGITAL -> R.string.calendar_release_digital
    CalendarReleaseKind.PHYSICAL -> R.string.calendar_release_physical
}
