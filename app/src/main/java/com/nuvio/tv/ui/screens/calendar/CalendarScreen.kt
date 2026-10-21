package com.nuvio.tv.ui.screens.calendar

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CalendarFilter
import com.nuvio.tv.domain.model.CalendarViewMode
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos

private const val DAY_PANEL_WIDTH_FRACTION = 0.4f
/** Lazy rows compose a few frames after a layout switch or cold start; keep asking a little longer. */
private const val FOCUS_RETRY_ATTEMPTS = 12
private const val FOCUS_RETRY_DELAY_MS = 48L

/**
 * Monthly calendar of releases for everything saved in the library.
 *
 * Grid mode — left: 7-column month grid with stacked mini posters per day; right: the focused
 * day's releases as poster cards. OK on a day jumps into that panel, LEFT/BACK from the panel
 * returns to the day. List mode — the month as a full-width agenda, one row per release.
 * Clicking a card or row opens the detail screen (episode pre-focused for series).
 */
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (
        itemId: String,
        itemType: String,
        addonBaseUrl: String?,
        season: Int?,
        episode: Int?
    ) -> Unit
) {
    // Corrects a stale selection the instant this screen (re-)composes — e.g. after the drawer's
    // saveState cache kept this ViewModel alive across a real day rollover — *before* the first
    // frame is built. Doing this here (composition), not in an effect, is what avoids the race:
    // any post-commit effect runs after the first frame is already laid out with the stale
    // selectedDate, so the always-fires-once "land focus" effect below would land on the stale
    // day's cell and its onFocusChanged -> selectDate() would immediately stomp the correction.
    remember(viewModel) { viewModel.refreshToday() }
    val uiState by viewModel.uiState.collectAsState()
    val locale = Locale.getDefault()
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val selectedDayFocusRequester = remember { FocusRequester() }
    val panelFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val toolbarFocusRequester = remember { FocusRequester() }
    var isPanelFocused by remember { mutableStateOf(false) }
    var isScreenFocused by remember { mutableStateOf(false) }
    var isToolbarFocused by remember { mutableStateOf(false) }
    // Bumped whenever the selection moves programmatically (e.g. "next release") so focus follows it.
    var dayFocusRequestId by remember { mutableIntStateOf(0) }
    val onEventClick: (CalendarEvent) -> Unit = { event ->
        onNavigateToDetail(event.itemId, event.itemType, event.addonBaseUrl, event.season, event.episode)
    }

    // While the calendar is on screen, stale release dates are refreshed at interactive priority;
    // leaving it hands the remaining work to the periodic background refresh.
    DisposableEffect(viewModel) {
        viewModel.onScreenVisibilityChanged(true)
        onDispose { viewModel.onScreenVisibilityChanged(false) }
    }

    // Covers the app sitting in the background overnight while already parked on this screen (the
    // remember(viewModel) correction above only runs once, on this composable's initial mount).
    // Unlike that mount-time case, there is no competing "land focus" effect racing to grab a
    // stale cell here — this IS what triggers that effect, via the bump below — so the only race
    // to guard is against the effect firing before recomposition has caught up with the jump;
    // the frame wait covers that.
    LifecycleResumeEffect(Unit) {
        scope.launch {
            viewModel.refreshToday()
            withFrameNanos { }
            dayFocusRequestId++
        }
        onPauseOrDispose { }
    }

    // Land the D-pad on the selected day when the screen opens, the layout changes or a jump happens.
    // Deliberately NOT keyed on visibleMonth: the toolbar's month arrows must leave focus on
    // themselves (so paging through months stays fast), not steal it to the grid/list every press.
    LaunchedEffect(uiState.viewMode, dayFocusRequestId) {
        repeat(FOCUS_RETRY_ATTEMPTS) {
            // false (no exception) means the target exists but is not placed yet: keep trying.
            if (runCatching { selectedDayFocusRequester.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(FOCUS_RETRY_DELAY_MS)
        }
    }

    // BACK while browsing the day's releases returns to the calendar instead of leaving the screen.
    BackHandler(enabled = isPanelFocused) {
        runCatching { selectedDayFocusRequester.requestFocus() }
    }
    // BACK from anywhere in the agenda jumps to the toolbar; UP would walk through every earlier row.
    // Tracked from the screen root and the toolbar rather than the lazy list, whose focus report
    // is unreliable when the first row is focused programmatically.
    val isAgendaFocused = uiState.viewMode == CalendarViewMode.LIST && isScreenFocused && !isToolbarFocused
    BackHandler(enabled = isAgendaFocused) {
        runCatching { toolbarFocusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { isScreenFocused = it.hasFocus }
            .background(NuvioTheme.colors.Background)
            .dpadRepeatThrottle()
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = NuvioTheme.spacing.xl,
                bottom = NuvioTheme.spacing.xl
            )
    ) {
        CalendarHeader(uiState = uiState, showBuiltInHeader = showBuiltInHeader)

        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        if (uiState.isLibraryEmpty) {
            EmptyScreenState(
                title = stringResource(R.string.calendar_empty_library_title),
                subtitle = stringResource(R.string.calendar_empty_library_subtitle),
                icon = Icons.Default.CalendarMonth
            )
            return@Column
        }

        CalendarToolbar(
            uiState = uiState,
            locale = locale,
            onPreviousMonth = viewModel::showPreviousMonth,
            onNextMonth = viewModel::showNextMonth,
            onToday = {
                viewModel.showToday()
                dayFocusRequestId++
            },
            onToggleViewMode = viewModel::toggleViewMode,
            onFilterSelected = viewModel::setFilter,
            viewToggleFocusRequester = toolbarFocusRequester,
            modifier = Modifier.onFocusChanged { isToolbarFocused = it.hasFocus }
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.md))

        when (uiState.viewMode) {
            CalendarViewMode.LIST -> CalendarAgendaList(
                uiState = uiState,
                locale = locale,
                selectedDayFocusRequester = selectedDayFocusRequester,
                exitUpFocusRequester = toolbarFocusRequester,
                landingRequestId = dayFocusRequestId,
                onDayFocused = viewModel::selectDate,
                onEventClick = onEventClick,
                onRequestPreviousMonth = {
                    viewModel.continueIntoPreviousMonth()
                    dayFocusRequestId++
                },
                onRequestNextMonth = {
                    viewModel.continueIntoNextMonth()
                    dayFocusRequestId++
                },
                modifier = Modifier.weight(1f)
            )
            CalendarViewMode.GRID -> Row(modifier = Modifier.weight(1f)) {
                CalendarMonthGrid(
                    uiState = uiState,
                    firstDayOfWeek = firstDayOfWeek,
                    locale = locale,
                    selectedDayFocusRequester = selectedDayFocusRequester,
                    onDayFocused = viewModel::selectDate,
                    onDayClick = { runCatching { panelFocusRequester.requestFocus() } },
                    modifier = Modifier
                        .weight(1f - DAY_PANEL_WIDTH_FRACTION)
                        .fillMaxHeight()
                )

                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xxl))

                CalendarDayPanel(
                    date = uiState.selectedDate,
                    today = uiState.today,
                    events = uiState.selectedDateEvents,
                    watchedEventKeys = uiState.watchedEventKeys,
                    hasNextRelease = uiState.nextReleaseAfter(uiState.selectedDate) != null,
                    locale = locale,
                    firstCardFocusRequester = panelFocusRequester,
                    exitLeftFocusRequester = selectedDayFocusRequester,
                    onFocusChanged = { isPanelFocused = it },
                    onEventClick = onEventClick,
                    onJumpToNextRelease = {
                        viewModel.jumpToNextRelease()
                        dayFocusRequestId++
                    },
                    modifier = Modifier
                        .weight(DAY_PANEL_WIDTH_FRACTION)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarHeader(uiState: CalendarUiState, showBuiltInHeader: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.calendar_title),
            style = MaterialTheme.typography.headlineMedium,
            color = if (showBuiltInHeader) NuvioTheme.colors.TextPrimary else Color.Transparent,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            if (uiState.isLoading) {
                LoadingIndicator(modifier = Modifier.size(20.dp))
            }
            Text(
                text = if (uiState.isLoading) {
                    stringResource(R.string.calendar_loading_progress, uiState.loadedCount, uiState.totalCount)
                } else {
                    stringResource(R.string.calendar_subtitle_library)
                }.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = NuvioTheme.colors.TextTertiary,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp
            )
        }
    }
}

/** Month navigation on the left; layout toggle and content filter on the right. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarToolbar(
    uiState: CalendarUiState,
    locale: Locale,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onToday: () -> Unit,
    onToggleViewMode: () -> Unit,
    onFilterSelected: (CalendarFilter) -> Unit,
    viewToggleFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val monthLabel = remember(uiState.visibleMonth, monthFormatter) {
        uiState.visibleMonth.format(monthFormatter).replaceFirstChar { it.titlecase(locale) }
    }
    val filterOptions = buildList {
        add(CalendarDropdownOption(CalendarFilter.ALL, stringResource(R.string.calendar_filter_all)))
        add(CalendarDropdownOption(CalendarFilter.MOVIES, stringResource(R.string.calendar_filter_movies)))
        add(CalendarDropdownOption(CalendarFilter.SERIES, stringResource(R.string.calendar_filter_series)))
        if (uiState.isAnimeFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.ANIME, stringResource(R.string.calendar_filter_anime)))
        }
        if (uiState.isWatchingFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.WATCHING, stringResource(R.string.calendar_filter_watching)))
        }
        if (uiState.isNuvioLibraryFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.LIBRARY_NUVIO, stringResource(R.string.calendar_filter_library_nuvio)))
        }
        if (uiState.isTraktLibraryFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.LIBRARY_TRAKT, stringResource(R.string.calendar_filter_library_trakt)))
        }
        if (uiState.isSimklLibraryFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.LIBRARY_SIMKL, stringResource(R.string.calendar_filter_library_simkl)))
        }
        if (uiState.isDigitalFilterAvailable) {
            add(CalendarDropdownOption(CalendarFilter.DIGITAL, stringResource(R.string.calendar_filter_digital)))
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        CalendarIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = stringResource(R.string.calendar_previous_month),
            onClick = onPreviousMonth
        )
        Text(
            text = monthLabel,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(MonthLabelWidth)
        )
        CalendarIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = stringResource(R.string.calendar_next_month),
            onClick = onNextMonth
        )
        CalendarPillButton(text = stringResource(R.string.calendar_today), onClick = onToday)

        Spacer(modifier = Modifier.weight(1f))

        val isList = uiState.viewMode == CalendarViewMode.LIST
        CalendarIconButton(
            icon = if (isList) Icons.Default.GridView else Icons.AutoMirrored.Filled.ViewList,
            contentDescription = stringResource(if (isList) R.string.calendar_view_grid else R.string.calendar_view_list),
            onClick = onToggleViewMode,
            modifier = Modifier.focusRequester(viewToggleFocusRequester)
        )
        CalendarDropdown(
            label = stringResource(R.string.calendar_filter_label),
            selected = uiState.filter,
            options = filterOptions,
            onSelect = onFilterSelected
        )
    }
}

private val MonthLabelWidth = 260.dp
