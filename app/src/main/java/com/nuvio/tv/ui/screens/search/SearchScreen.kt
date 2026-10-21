package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.home.HeroBackdropState
import com.nuvio.tv.data.simkl.matchesWatchedId

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.snapshotFlow
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.domain.model.MetaPreview
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.ui.util.recompositionHighlighter
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.nuvio.tv.ui.util.RtlKeyUtils
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton as TvIconButton
import androidx.tv.material3.IconButtonDefaults as TvIconButtonDefaults
import androidx.tv.material3.Text
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.stableKey
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

/** Skeleton rows shown while a search is pending, matching the two mobile renders. */
private const val SEARCH_SKELETON_ROW_COUNT = 2

private val NAVIGATION_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (catalogId: String, addonId: String, type: String) -> Unit = { _, _, _ -> },
    onOpenDiscover: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val watchedMovieIds by viewModel.watchedMovieIds.collectAsState()
    val watchedSeriesIds by viewModel.watchedSeriesIds.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val strVoiceNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strVoiceMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strVoiceFailed = stringResource(R.string.search_voice_failed)
    val strVoiceUnavailable = stringResource(R.string.search_voice_unavailable)
    val voiceFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardFocusRequester = remember { FocusRequester() }
    val firstShortcutFocusRequester = remember { FocusRequester() }
    val shortcutFocusRequesters = remember { List(9) { FocusRequester() } }
    val keyFRequester = remember { FocusRequester() }
    val keyLRequester = remember { FocusRequester() }
    val keyRRequester = remember { FocusRequester() }
    val keyXRequester = remember { FocusRequester() }
    val key3Requester = remember { FocusRequester() }
    val key9Requester = remember { FocusRequester() }
    val key4Requester = remember { FocusRequester() }
    val clearKeyRequester = remember { FocusRequester() }
    val spaceKeyRequester = keyboardFocusRequester
    val backspaceKeyRequester = remember { FocusRequester() }
    var lastLeftFocusedRequester by remember { mutableStateOf<FocusRequester?>(null) }
    val discoverFirstItemFocusRequester = remember { FocusRequester() }
    var requestGridFocusCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }
    val requestGridFocus: (Int) -> Unit = { keyboardRow ->
        requestGridFocusCallback?.invoke(keyboardRow) ?: runCatching { discoverFirstItemFocusRequester.requestFocus() }
    }
    val firstRecentSearchFocusRequester = remember { FocusRequester() }
    val recentClearHistoryFocusRequester = remember { FocusRequester() }
    val discoverButtonFocusRequester = remember { FocusRequester() }
    var isSearchFieldFocused by remember { mutableStateOf(false) }
    var isRightAreaFocused by remember { mutableStateOf(false) }
    // Track the whole input row, including voice and Discover.
    var inputRowHasFocus by remember { mutableStateOf(false) }
    var isRecentSearchSectionFocused by remember { mutableStateOf(false) }
    var focusResults by remember { mutableStateOf(false) }
    var pendingFocusMoveToResultsQuery by remember { mutableStateOf<String?>(null) }
    var pendingFocusMoveSawSearching by remember { mutableStateOf(false) }
    var pendingFocusMoveHadExistingSearchRows by remember { mutableStateOf(false) }
    var isVoiceListening by remember { mutableStateOf(false) }
    var voiceRmsLevel by remember { mutableStateOf(0f) }
    var discoverFocusedItemIndex by rememberSaveable { mutableStateOf(0) }
    var restoreDiscoverFocus by rememberSaveable { mutableStateOf(false) }
    var pendingDiscoverRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    val restoringSearchFocus = remember { mutableStateOf(viewModel.hasSavedSearchFocus) }
    val didRestoreSearchFocus = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    // Latches the Back-to-field step until the user navigates, types or submits. Focus alone cannot
    // lift it: closing the Fire TV keyboard moves focus into the recent searches with no key press,
    // which would otherwise re-enable this handler.
    var backToFieldLatched by remember { mutableStateOf(false) }
    val onVoiceQueryResultState = rememberUpdatedState<(String) -> Unit> { recognized ->
        if (recognized.isNotBlank()) {
            backToFieldLatched = false
            viewModel.onEvent(SearchEvent.QueryChanged(recognized))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            focusResults = false
            pendingFocusMoveToResultsQuery = recognized
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                uiState.submittedQuery.trim().length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { it.items.isNotEmpty() }
        } else {
            Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
        }
    }
    val isVoiceSearchAvailable = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val topInputFocusRequester = remember(isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) voiceFocusRequester else searchFocusRequester
    }
    val speechRecognizer = remember(context, isVoiceSearchAvailable) {
        if (isVoiceSearchAvailable) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    val buildRecognizeIntent: () -> Intent = {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val hasRecordAudioPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var recordAudioPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestAudioPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        recordAudioPermissionGranted = granted
        if (granted) {
            isVoiceListening = true
            runCatching {
                speechRecognizer?.cancel()
                speechRecognizer?.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(hasRecordAudioPermission) {
        recordAudioPermissionGranted = hasRecordAudioPermission
    }
    DisposableEffect(speechRecognizer) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Normalize RMS dB to 0..1 range. Typical values: -2 (silence) to 10 (loud).
                voiceRmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                Log.w("SearchScreen", "Voice recognition error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        Toast.makeText(context, strVoiceNoSpeech, Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, strVoiceMicPermission, Toast.LENGTH_SHORT).show()
                    else ->
                        Toast.makeText(context, strVoiceFailed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                isVoiceListening = false
                voiceRmsLevel = 0f
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                onVoiceQueryResultState.value(recognized)
            }

            override fun onPartialResults(partialResults: Bundle?) = Unit
        }

        speechRecognizer?.setRecognitionListener(listener)
        onDispose {
            speechRecognizer?.setRecognitionListener(null)
            speechRecognizer?.destroy()
        }
    }
    val launchVoiceSearch: () -> Unit = {
        if (!isVoiceSearchAvailable || speechRecognizer == null) {
            Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
        } else if (!recordAudioPermissionGranted) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            isVoiceListening = true
            runCatching {
                speechRecognizer.cancel()
                speechRecognizer.startListening(buildRecognizeIntent())
            }.onFailure {
                isVoiceListening = false
                Toast.makeText(context, strVoiceUnavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val posterCardStyle = remember(uiState.posterCardCornerRadiusDp) {
        PosterCardStyle(
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val trimmedQuery = remember(uiState.query) { uiState.query.trim() }
    val trimmedSubmittedQuery = remember(uiState.submittedQuery) { uiState.submittedQuery.trim() }

    // Stable per-row state maps — mirrors ClassicHomeContent pattern so
    // CatalogRowSection keeps focus when placeholder→real data transitions.
    val searchRowStates = remember { mutableMapOf<String, LazyListState>() }
    val searchRowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val searchRowEntryFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val searchRowFocusedItemIndex = remember { mutableMapOf<String, Int>() }
    // Clears the row-local focus index so the row's entry requester moves to the first card.
    // Scoped to one row so a Back in one does not reset what the others remember.
    var focusResetCounter by remember { mutableIntStateOf(0) }
    var focusResetRowKey by remember { mutableStateOf<String?>(null) }
    // Mirrors the focused index for the active row. The map above is a plain MutableMap,
    // so its values cannot drive recomposition of the Back handler.
    val focusedResultItemIndex = remember { mutableIntStateOf(viewModel.savedFocusItemIndex.coerceAtLeast(0)) }
    var lastFocusedRowKey by remember { mutableStateOf(viewModel.savedFocusRowKey) }
    var posterOptionsRowKey by remember { mutableStateOf<String?>(null) }

    val saveSearchFocusForDetail: (String) -> Unit = { rowKey ->
        viewModel.savedFocusRowKey = rowKey
        viewModel.savedFocusItemIndex = searchRowFocusedItemIndex[rowKey] ?: 0
        viewModel.savedRowScrollPositions = searchRowStates.mapValues {
            it.value.firstVisibleItemIndex to it.value.firstVisibleItemScrollOffset
        }
        viewModel.hasSavedSearchFocus = true
    }

    // Clean up stale keys when the catalog rows change.
    val visibleRowKeys = remember(uiState.catalogRows) {
        uiState.catalogRows.mapTo(mutableSetOf()) {
            it.stableKey()
        }
    }
    // Stable list of non-empty catalog rows — mirrors ClassicHomeContent's
    // visibleHomeRows pattern so the LazyColumn receives a remember'd list.
    val visibleCatalogRows = remember(uiState.catalogRows) {
        uiState.catalogRows.filter { it.items.isNotEmpty() }
    }
    LaunchedEffect(visibleRowKeys) {
        searchRowStates.keys.retainAll(visibleRowKeys)
        searchRowFocusRequesters.keys.retainAll(visibleRowKeys)
        searchRowEntryFocusRequesters.keys.retainAll(visibleRowKeys)
        searchRowFocusedItemIndex.keys.retainAll(visibleRowKeys)
    }

    val isDiscoverMode = remember(uiState.discoverLocation, trimmedQuery, trimmedSubmittedQuery) {
        shouldShowDiscoverInSearch(
            discoverLocation = uiState.discoverLocation,
            query = trimmedQuery,
            submittedQuery = trimmedSubmittedQuery
        )
    }
    LaunchedEffect(Unit) {
        if (viewModel.isNavigatingToDetail) {
            viewModel.onReturnFromDetail()
        } else {
            viewModel.resetSearchDiscoverToDefaults()
        }
        viewModel.ensureDiscoverLoaded()
    }
    val hasPendingUnsubmittedQuery = remember(isDiscoverMode, trimmedQuery, trimmedSubmittedQuery) {
        !isDiscoverMode &&
            trimmedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedQuery != trimmedSubmittedQuery
    }
    val showRecentSearches = remember(
        trimmedQuery,
        uiState.recentSearches
    ) {
        trimmedQuery.isEmpty() &&
            uiState.recentSearches.isNotEmpty()
    }
    val canMoveToResults = remember(
        isDiscoverMode,
        uiState.discoverResults,
        trimmedSubmittedQuery,
        uiState.catalogRows
    ) {
        if (isDiscoverMode) {
            false
        } else {
            trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                uiState.catalogRows.any { it.items.isNotEmpty() }
        }
    }
    val submitCurrentQuery: (String) -> Unit = { submittedQuery ->
        viewModel.onEvent(SearchEvent.SubmitSearch)
        // Submitting moves focus into the results without a key event on an on-screen keyboard.
        backToFieldLatched = false
        focusResults = false
        if (submittedQuery.length >= MIN_SEARCH_QUERY_LENGTH) {
            pendingFocusMoveToResultsQuery = submittedQuery
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows =
                trimmedSubmittedQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
                    uiState.catalogRows.any { row -> row.items.isNotEmpty() }
        } else {
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }
    val handleQueryChanged: (String) -> Unit = { nextQuery ->
        // A real edit is user intent. An IME that re-commits the same text on dismiss is not.
        if (nextQuery != uiState.query) backToFieldLatched = false
        val previousQuery = uiState.query.trim()
        val trimmedNextQuery = nextQuery.trim()
        val selectedSuggestion = trimmedNextQuery.length >= MIN_SEARCH_QUERY_LENGTH &&
            trimmedNextQuery != trimmedSubmittedQuery &&
            uiState.suggestions.any { it.equals(trimmedNextQuery, ignoreCase = true) } &&
            trimmedNextQuery.startsWith(previousQuery, ignoreCase = true) &&
            trimmedNextQuery.length - previousQuery.length > 1

        focusResults = false
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
        viewModel.onEvent(SearchEvent.QueryChanged(nextQuery))
        if (selectedSuggestion) {
            submitCurrentQuery(trimmedNextQuery)
        }
    }
    val submitRecentSearch: (String) -> Unit = { recentQuery ->
        val trimmedRecentQuery = recentQuery.trim()
        if (trimmedRecentQuery.isNotEmpty()) {
            runCatching { topInputFocusRequester.requestFocus() }
            viewModel.onEvent(SearchEvent.QueryChanged(trimmedRecentQuery))
            submitCurrentQuery(trimmedRecentQuery)
        }
    }

    LaunchedEffect(focusResults, isDiscoverMode, uiState.discoverResults.size) {
        if (focusResults && isDiscoverMode && uiState.discoverResults.isNotEmpty()) {
            delay(100)
            runCatching { discoverFirstItemFocusRequester.requestFocus() }
            focusResults = false
            pendingFocusMoveToResultsQuery = null
            pendingFocusMoveSawSearching = false
            pendingFocusMoveHadExistingSearchRows = false
        }
    }

    LaunchedEffect(
        pendingFocusMoveToResultsQuery,
        pendingFocusMoveSawSearching,
        pendingFocusMoveHadExistingSearchRows,
        uiState.isSearching,
        uiState.submittedQuery,
        canMoveToResults,
        isDiscoverMode
    ) {
        val pendingQuery = pendingFocusMoveToResultsQuery ?: return@LaunchedEffect
        val currentSubmittedQuery = uiState.submittedQuery.trim()
        if (currentSubmittedQuery != pendingQuery) return@LaunchedEffect

        if (uiState.isSearching) {
            pendingFocusMoveSawSearching = true
            return@LaunchedEffect
        }

        val shouldRequireSeenSearching = pendingFocusMoveHadExistingSearchRows
        if ((shouldRequireSeenSearching && !pendingFocusMoveSawSearching) || !canMoveToResults) {
            return@LaunchedEffect
        }

        if (isDiscoverMode) {
            focusResults = true
        } else {
            // Use explicit first-item focus for deterministic landing on row 1 / column 1.
            delay(80)
            focusResults = true
        }
        pendingFocusMoveToResultsQuery = null
        pendingFocusMoveSawSearching = false
        pendingFocusMoveHadExistingSearchRows = false
    }

    val initialFocusRequester = keyboardFocusRequester

    LaunchedEffect(Unit) {
        if (viewModel.hasSavedSearchFocus) return@LaunchedEffect
        if (viewModel.savedDiscoverFocusIndex >= 0 || viewModel.isNavigatingToDetail) return@LaunchedEffect
        if (pendingDiscoverRestoreOnResume || restoreDiscoverFocus) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        runCatching { initialFocusRequester.requestFocus() }
    }

    // Push search suggestions to the native keyboard suggestion bar. Keyed on the query as well
    // as the list: the keyboard rebuilds its strip as the query changes, so completions have to
    // be pushed again even when the list is unchanged.
    LaunchedEffect(uiState.query, uiState.suggestions) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return@LaunchedEffect
        val completions = uiState.suggestions.mapIndexed { index, name ->
            CompletionInfo(index.toLong(), index, name)
        }.toTypedArray()
        imm.displayCompletions(view, completions)
    }

    var isScreenActive by remember { mutableStateOf(true) }
    val latestPendingDiscoverRestore by rememberUpdatedState(pendingDiscoverRestoreOnResume)
    val latestInitialFocusRequester by rememberUpdatedState(initialFocusRequester)
    val latestShouldKeepSearchFocus by rememberUpdatedState(
        focusResults || uiState.isSearching || isVoiceListening
    )
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isScreenActive = true
                if (latestPendingDiscoverRestore) {
                    restoreDiscoverFocus = true
                    pendingDiscoverRestoreOnResume = false
                } else if (viewModel.hasSavedSearchFocus || didRestoreSearchFocus.value) {
                    // Returning from details — don't steal focus, CatalogRowSection
                    // already restored it or will restore it via focusedItemIndex.
                    restoringSearchFocus.value = true
                    didRestoreSearchFocus.value = false
                } else if (viewModel.savedDiscoverFocusIndex >= 0 || viewModel.isNavigatingToDetail) {
                    // Returning from details to recommendations grid — keep focus on the card
                    restoreDiscoverFocus = true
                    viewModel.onReturnFromDetail()
                } else if (!latestShouldKeepSearchFocus) {
                    // Keep resume and entry consistent; ON_RESUME also fires on entry.
                    coroutineScope.launch {
                        repeat(2) { withFrameNanos { } }
                        runCatching { latestInitialFocusRequester.requestFocus() }
                    }
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                isScreenActive = false
                keyboardController?.hide()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode in NAVIGATION_KEYS
                ) {
                    backToFieldLatched = false
                }
                false
            }
            .background(NuvioTheme.colors.Background),
        contentAlignment = Alignment.TopCenter
    ) {
        val listState = rememberLazyListState()

        val canHandleBack = isRightAreaFocused || uiState.query.isNotEmpty()
        BackHandler(enabled = canHandleBack) {
            if (isRightAreaFocused) {
                coroutineScope.launch {
                    val target = lastLeftFocusedRequester ?: keyboardFocusRequester
                    for (frame in 0..3) {
                        val ok = runCatching { target.requestFocus() }.isSuccess
                        if (ok) break
                        withFrameNanos { }
                    }
                }
            } else if (uiState.query.isNotEmpty()) {
                handleQueryChanged("")
            }
        }

        // Skip the initial composition; there is nothing stale to reset yet.
        var lastScrollResetQuery by remember { mutableStateOf(uiState.query) }
        LaunchedEffect(uiState.query) {
            if (uiState.query == lastScrollResetQuery) return@LaunchedEffect
            lastScrollResetQuery = uiState.query
            // Clear stale focus so the restorer cannot return to an item from the previous query.
            searchRowFocusedItemIndex.clear()
            focusedResultItemIndex.intValue = 0
            // Clear saved offsets too; rebuilt rows can otherwise inherit the old query's position.
            viewModel.savedRowScrollPositions = emptyMap()
            listState.scrollToItem(0)
            // Snapshot the states because scrollToItem suspends.
            searchRowStates.values.toList().forEach { it.scrollToItem(0) }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            // PAINEL FLUTUANTE DA ESQUERDA (Cápsula escura arredondada fiel ao Better Nuvio)
            val kbKeyRequesters = remember {
                val allChars = ('a'..'z') + ('0'..'9')
                allChars.associateWith { char ->
                    when (char) {
                        'f' -> keyFRequester
                        'l' -> keyLRequester
                        'r' -> keyRRequester
                        'x' -> keyXRequester
                        '3' -> key3Requester
                        '9' -> key9Requester
                        '4' -> key4Requester
                        else -> FocusRequester()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(262.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF1B1C21))
                    .border(1.dp, Color(0xFF262830), RoundedCornerShape(22.dp))
                    .padding(horizontal = 7.dp, vertical = 10.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SearchQueryDisplay(
                        query = uiState.query,
                        focusRequester = searchFocusRequester,
                        onFocusChanged = { focused ->
                            if (focused) {
                                lastLeftFocusedRequester = searchFocusRequester
                            }
                        },
                        onDisplayClicked = {
                            coroutineScope.launch {
                                for (frame in 0..3) {
                                    val ok = runCatching { keyboardFocusRequester.requestFocus() }.isSuccess
                                    if (ok) break
                                    withFrameNanos { }
                                }
                            }
                        },
                        onNavigateDown = {
                            coroutineScope.launch {
                                val target = if (lastLeftFocusedRequester != null && lastLeftFocusedRequester != searchFocusRequester) {
                                    lastLeftFocusedRequester
                                } else {
                                    keyboardFocusRequester
                                }
                                var ok = target?.let { runCatching { it.requestFocus() }.isSuccess } ?: false
                                if (!ok) {
                                    for (frame in 0..3) {
                                        withFrameNanos { }
                                        ok = runCatching { keyboardFocusRequester.requestFocus() }.isSuccess
                                        if (ok) break
                                    }
                                }
                            }
                        },
                        onNavigateRight = {
                            lastLeftFocusedRequester = searchFocusRequester
                            if (trimmedSubmittedQuery.length < MIN_SEARCH_QUERY_LENGTH && !hasPendingUnsubmittedQuery) {
                                runCatching { discoverButtonFocusRequester.requestFocus() }
                            } else if (canMoveToResults) {
                                viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
                                focusResults = true
                            }
                        }
                    )

                    TvOnScreenKeyboard(
                        query = uiState.query,
                        onQueryChanged = handleQueryChanged,
                        onSubmit = { submitCurrentQuery(uiState.query.trim()) },
                        onNavigateRight = { keyboardRow ->
                            lastLeftFocusedRequester = when (keyboardRow) {
                                0 -> clearKeyRequester
                                1 -> keyFRequester
                                2 -> keyLRequester
                                3 -> keyRRequester
                                4 -> keyXRequester
                                5 -> key3Requester
                                6 -> key9Requester
                                else -> keyRRequester
                            }
                            if (trimmedSubmittedQuery.length < MIN_SEARCH_QUERY_LENGTH && !hasPendingUnsubmittedQuery) {
                                if (keyboardRow == 0) {
                                    runCatching { discoverButtonFocusRequester.requestFocus() }
                                } else {
                                    requestGridFocus(keyboardRow)
                                }
                            } else if (canMoveToResults) {
                                viewModel.onEvent(SearchEvent.RememberSearchFromTextInput)
                                focusResults = true
                            }
                        },
                        onNavigateUp = {
                            lastLeftFocusedRequester = searchFocusRequester
                            runCatching { searchFocusRequester.requestFocus() }
                        },
                        onNavigateDown = {
                            if (uiState.query.isEmpty()) {
                                coroutineScope.launch {
                                    val target = shortcutFocusRequesters.firstOrNull() ?: firstShortcutFocusRequester
                                    for (frame in 0..3) {
                                        val ok = runCatching { target.requestFocus() }.isSuccess
                                        if (ok) {
                                            lastLeftFocusedRequester = target
                                            break
                                        }
                                        withFrameNanos { }
                                    }
                                }
                            }
                        },
                        firstKeyFocusRequester = keyboardFocusRequester,
                        keyFocusRequesters = kbKeyRequesters,
                        clearKeyFocusRequester = clearKeyRequester,
                        spaceKeyFocusRequester = spaceKeyRequester,
                        backspaceKeyFocusRequester = backspaceKeyRequester,
                        onKeyFocused = { req ->
                            lastLeftFocusedRequester = req
                        }
                    )

                    if (uiState.query.isEmpty()) {
                        SearchInterestShortcutsVertical(
                            selectedQuery = uiState.query,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(bottom = 4.dp),
                            shortcutFocusRequesters = shortcutFocusRequesters,
                            firstShortcutFocusRequester = shortcutFocusRequesters.firstOrNull() ?: firstShortcutFocusRequester,
                            onShortcutFocused = { req ->
                                lastLeftFocusedRequester = req
                            },
                            onShortcutSelected = { shortcut ->
                                when (shortcut) {
                                    SearchInterestShortcut.FOR_YOU -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre(null))
                                    }
                                    SearchInterestShortcut.SERIES -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("series"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre(null))
                                    }
                                    SearchInterestShortcut.COMEDIES -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Comedy"))
                                    }
                                    SearchInterestShortcut.ACTION -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Action"))
                                    }
                                    SearchInterestShortcut.HORROR -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Horror"))
                                    }
                                    SearchInterestShortcut.ROMANCE -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Romance"))
                                    }
                                    SearchInterestShortcut.SUSPENSE -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Suspense"))
                                    }
                                    SearchInterestShortcut.DRAMA -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Drama"))
                                    }
                                    SearchInterestShortcut.DOCUMENTARIES -> {
                                        viewModel.onEvent(SearchEvent.SelectDiscoverType("movie"))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(""))
                                        viewModel.onEvent(SearchEvent.SelectDiscoverGenre("Documentary"))
                                    }
                                }
                            },
                            onNavigateUp = {
                                lastLeftFocusedRequester = key4Requester
                                coroutineScope.launch {
                                    for (frame in 0..3) {
                                        val ok = runCatching { key4Requester.requestFocus() }.isSuccess
                                        if (ok) break
                                        withFrameNanos { }
                                    }
                                }
                            },
                            onNavigateRight = { index ->
                                lastLeftFocusedRequester = shortcutFocusRequesters.getOrNull(index) ?: firstShortcutFocusRequester
                                requestGridFocus(1)
                            }
                        )
                    } else {
                        Text(
                            text = "→   Resultados",
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Color(0xFF6E737D),
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
            }

            val returnToLeftColumn: (fallback: FocusRequester) -> Unit = { fallback ->
                coroutineScope.launch {
                    val target = lastLeftFocusedRequester ?: fallback
                    var ok = runCatching { target.requestFocus() }.isSuccess
                    if (!ok) {
                        for (frame in 0..4) {
                            withFrameNanos { }
                            ok = runCatching { target.requestFocus() }.isSuccess
                            if (ok) break
                        }
                    }
                    if (!ok) {
                        runCatching { fallback.requestFocus() }
                    }
                }
            }

            // ÁREA DA DIREITA: RECOMENDAÇÕES (QUANDO VAZIO) OU RESULTADOS DA BUSCA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onFocusChanged { isRightAreaFocused = it.hasFocus }
            ) {
                if (trimmedSubmittedQuery.length < MIN_SEARCH_QUERY_LENGTH && !hasPendingUnsubmittedQuery) {
                    BetterNuvioRecommendationsSection(
                        uiState = uiState,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        firstItemFocusRequester = discoverFirstItemFocusRequester,
                        filterFocusRequester = discoverButtonFocusRequester,
                        onNavigateToDetail = onNavigateToDetail,
                        onSelectType = { type -> viewModel.onEvent(SearchEvent.SelectDiscoverType(type)) },
                        onSelectCatalog = { cat -> viewModel.onEvent(SearchEvent.SelectDiscoverCatalog(cat)) },
                        onSelectGenre = { gen -> viewModel.onEvent(SearchEvent.SelectDiscoverGenre(gen)) },
                        onSetFavorite = { type, catKey, gen -> viewModel.setFavoriteForType(type, catKey, gen) },
                        onClearFavorite = { type -> viewModel.clearFavoriteForType(type) },
                        onLoadMore = { viewModel.onEvent(SearchEvent.LoadNextDiscoverResults) },
                        onNavigateLeftToClear = {
                            returnToLeftColumn(clearKeyRequester)
                        },
                        onNavigateLeftToKeyboard = {
                            returnToLeftColumn(keyRRequester)
                        },
                        onRegisterFocusBridge = { bridge ->
                            requestGridFocusCallback = bridge
                        },
                        initialScrollIndex = viewModel.savedDiscoverScrollIndex,
                        initialScrollOffset = viewModel.savedDiscoverScrollOffset,
                        initialFocusIndex = viewModel.savedDiscoverFocusIndex,
                        restoreFocusTrigger = restoreDiscoverFocus,
                        onDiscoverFocusRestored = { restoreDiscoverFocus = false },
                        onRetry = { viewModel.onEvent(SearchEvent.Retry) },
                        onSaveScrollPosition = { idx, off, fIdx ->
                            viewModel.saveDiscoverScrollPosition(idx, off, fIdx)
                        },
                        onMarkNavigatingToDetail = {
                            viewModel.markNavigatingToDetail()
                        },
                        onItemLongClick = { item ->
                            viewModel.posterOptions.show(item, "")
                        }
                    )
                } else if ((hasPendingUnsubmittedQuery || uiState.isSearching) && visibleCatalogRows.isEmpty()) {
                    BetterNuvioSearchStatus(
                        title = stringResource(R.string.search_status_searching_addons),
                        subtitle = stringResource(R.string.search_status_searching_addons_sub)
                    )
                } else if (uiState.error != null && visibleCatalogRows.isEmpty()) {
                    ErrorState(
                        message = uiState.error ?: stringResource(R.string.search_error_failed),
                        onRetry = { viewModel.onEvent(SearchEvent.Retry) }
                    )
                } else if (!uiState.isSearching && !hasPendingUnsubmittedQuery && visibleCatalogRows.isEmpty()) {
                    BetterNuvioSearchStatus(
                        title = stringResource(R.string.search_status_no_titles),
                        subtitle = stringResource(R.string.search_status_no_titles_sub),
                        helperText = stringResource(R.string.search_status_no_titles_helper)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .recompositionHighlighter()
                            .dpadRepeatThrottle(),
                        state = listState,
                        contentPadding = PaddingValues(
                            top = NuvioTheme.spacing.sm,
                            bottom = NuvioTheme.spacing.lg
                        ),
                        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
                    ) {
                        itemsIndexed(
                            items = visibleCatalogRows,
                            key = { index, item ->
                                "${item.stableKey()}_$index"
                            },
                            contentType = { _, _ -> "catalog_row" }
                        ) { index, catalogRow ->
                            val catalogKey = catalogRow.stableKey()
                            val isPlaceholder = catalogRow.isLoading &&
                                catalogRow.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                            val hasEnoughForSeeAll = !isPlaceholder && catalogRow.items.size >= 15

                            val rowListState = searchRowStates.getOrPut(catalogKey) {
                                val saved = viewModel.savedRowScrollPositions[catalogKey]
                                LazyListState(
                                    firstVisibleItemIndex = saved?.first ?: 0,
                                    firstVisibleItemScrollOffset = saved?.second ?: 0
                                )
                            }
                            val rowFocusRequester = searchRowFocusRequesters.getOrPut(catalogKey) { FocusRequester() }
                            val entryFocusRequester = searchRowEntryFocusRequesters.getOrPut(catalogKey) { FocusRequester() }

                            CatalogRowSection(
                                catalogRow = catalogRow,
                                posterCardStyle = posterCardStyle,
                                showSeeAll = hasEnoughForSeeAll,
                                showPosterLabels = uiState.posterLabelsEnabled,
                                showAddonName = uiState.catalogAddonNameEnabled,
                                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                                enableRowFocusRestorer = true,
                                rowFocusRequester = rowFocusRequester,
                                entryFocusRequester = entryFocusRequester,
                                upFocusRequester = null,
                                listState = rowListState,
                                restorerFocusedIndex = if (restoringSearchFocus.value && catalogKey == viewModel.savedFocusRowKey) {
                                    viewModel.savedFocusItemIndex
                                } else {
                                    searchRowFocusedItemIndex[catalogKey] ?: -1
                                },
                                focusResetToken = if (catalogKey == focusResetRowKey) {
                                    "${uiState.query}#$focusResetCounter"
                                } else {
                                    uiState.query
                                },
                                isItemWatched = { item ->
                                    val isSeries = item.apiType.equals("series", ignoreCase = true) || item.apiType.equals("tv", ignoreCase = true)
                                    val isMovie = item.apiType.equals("movie", ignoreCase = true)
                                    when {
                                        isSeries -> item.id.matchesWatchedId(watchedSeriesIds) || item.id.matchesWatchedId(watchedMovieIds)
                                        isMovie -> item.id.matchesWatchedId(watchedMovieIds) || item.id.matchesWatchedId(watchedSeriesIds)
                                        else -> item.id.matchesWatchedId(watchedMovieIds) || item.id.matchesWatchedId(watchedSeriesIds)
                                    }
                                },
                                focusedItemIndex = when {
                                    restoringSearchFocus.value && catalogKey == viewModel.savedFocusRowKey ->
                                        viewModel.savedFocusItemIndex
                                    focusResults && index == 0 ->
                                        searchRowFocusedItemIndex[catalogKey] ?: 0
                                    else -> -1
                                },
                                onItemFocused = { itemIndex ->
                                    if (focusResults) {
                                        focusResults = false
                                    }
                                    if (restoringSearchFocus.value) {
                                        restoringSearchFocus.value = false
                                        didRestoreSearchFocus.value = true
                                        viewModel.hasSavedSearchFocus = false
                                    }
                                    pendingFocusMoveToResultsQuery = null
                                    searchRowFocusedItemIndex[catalogKey] = itemIndex
                                    focusedResultItemIndex.intValue = itemIndex
                                    catalogRow.items.getOrNull(itemIndex)?.let { item ->
                                        viewModel.prefetchMetaOnFocus(item.id, item.rawType)
                                    }
                                    lastFocusedRowKey = catalogKey
                                },
                                onItemClick = { id, type, addonBaseUrl ->
                                    viewModel.markNavigatingToDetail()
                                    lastFocusedRowKey = catalogKey
                                    saveSearchFocusForDetail(catalogKey)
                                    val clickedItem = catalogRow.items.firstOrNull { it.id == id }
                                    val backdrop = viewModel.getCachedBackdrop(id, type)
                                        ?: clickedItem?.backdropUrl
                                    HeroBackdropState.update(backdrop)
                                    onNavigateToDetail(id, type, addonBaseUrl)
                                },
                                onItemLongPress = { item, addonBaseUrl ->
                                    posterOptionsRowKey = catalogKey
                                    viewModel.posterOptions.show(item, addonBaseUrl)
                                },
                                onSeeAll = {
                                    onNavigateToSeeAll(
                                        catalogRow.catalogId,
                                        catalogRow.addonId,
                                        catalogRow.apiType
                                    )
                                }
                            )
                        }

                        if (uiState.isSearching || hasPendingUnsubmittedQuery) {
                            item(key = "search_loading_more") {
                                val skeletonRow = remember {
                                    com.nuvio.tv.domain.model.CatalogRow(
                                        addonId = "__skeleton",
                                        addonName = "",
                                        addonBaseUrl = "",
                                        catalogId = "skeleton_more",
                                        catalogName = "",
                                        type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                        items = (0 until 8).map { i ->
                                            com.nuvio.tv.domain.model.MetaPreview(
                                                id = "__placeholder_skeleton_more_$i",
                                                type = com.nuvio.tv.domain.model.ContentType.MOVIE,
                                                name = " ",
                                                poster = com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL,
                                                posterShape = com.nuvio.tv.domain.model.PosterShape.POSTER,
                                                background = null,
                                                logo = null,
                                                description = null,
                                                releaseInfo = " ",
                                                imdbRating = null,
                                                genres = emptyList()
                                            )
                                        },
                                        isLoading = true
                                    )
                                }
                                CatalogRowSection(
                                    catalogRow = skeletonRow,
                                    onItemClick = { _, _, _ -> },
                                    posterCardStyle = posterCardStyle,
                                    showAddonName = uiState.catalogAddonNameEnabled,
                                    modifier = Modifier.padding(bottom = 24.dp)
                                )
                            }
                        }
                    }
                }
            } // Fecha Box da coluna direita
        } // Fecha Row principal
    }

    val posterOptionsState by viewModel.posterOptions.state.collectAsState()
    com.nuvio.tv.ui.components.posteroptions.PosterOptionsHost(
        state = posterOptionsState,
        controller = viewModel.posterOptions,
        onNavigateToDetail = { id, type, addonBaseUrl ->
            viewModel.markNavigatingToDetail()
            // Consume it, so a later navigation cannot reuse this row.
            posterOptionsRowKey?.let(saveSearchFocusForDetail)
            posterOptionsRowKey = null
            val clickedItem = uiState.catalogRows
                .flatMap { it.items }
                .firstOrNull { it.id == id }
                ?: uiState.discoverResults.firstOrNull { it.id == id }
            HeroBackdropState.update(clickedItem?.backdropUrl)
            onNavigateToDetail(id, type, addonBaseUrl)
        }
    )
}

@Composable
private fun SearchQueryDisplay(
    query: String,
    focusRequester: FocusRequester,
    onDisplayClicked: () -> Unit,
    onNavigateDown: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateRight: () -> Unit = {},
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { 
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF23252A))
            .border(
                width = if (isFocused) 1.5.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0xFF2B2E35),
                shape = RoundedCornerShape(12.dp)
            )
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        onNavigateDown()
                        return@onPreviewKeyEvent true
                    }
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        onNavigateRight()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .clickable { onDisplayClicked() }
            .focusable()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) Color.White else Color(0xFF7E838E),
                modifier = Modifier.size(16.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (query.isNotEmpty()) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = query,
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(16.dp)
                            .graphicsLayer { alpha = cursorAlpha }
                            .background(Color.White)
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.search_placeholder),
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp
                    ),
                    color = Color(0xFF7E838E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class SearchInterestShortcut(val labelRes: Int) {
    FOR_YOU(R.string.search_shortcut_for_you),
    SERIES(R.string.search_shortcut_series),
    COMEDIES(R.string.search_shortcut_comedies),
    ACTION(R.string.search_shortcut_action),
    HORROR(R.string.search_shortcut_horror),
    ROMANCE(R.string.search_shortcut_romance),
    SUSPENSE(R.string.search_shortcut_suspense),
    DRAMA(R.string.search_shortcut_drama),
    DOCUMENTARIES(R.string.search_shortcut_documentaries)
}

@Composable
private fun SearchInterestShortcutsVertical(
    selectedQuery: String,
    onShortcutSelected: (SearchInterestShortcut) -> Unit,
    firstShortcutFocusRequester: FocusRequester,
    onNavigateUp: () -> Unit,
    onNavigateRight: (Int) -> Unit,
    shortcutFocusRequesters: List<FocusRequester> = emptyList(),
    onShortcutFocused: ((FocusRequester) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.search_explore_interest).uppercase(),
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            ),
            color = Color(0xFF6B707B),
            modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
        )

        val shortcutsScrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(shortcutsScrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SearchInterestShortcut.entries.forEachIndexed { index, shortcut ->
                val label = stringResource(shortcut.labelRes)
                val isFirst = index == 0
                val isSelected = selectedQuery.equals(label, ignoreCase = true)
                var isFocused by remember { mutableStateOf(false) }
                val itemRequester = shortcutFocusRequesters.getOrNull(index) ?: (if (isFirst) firstShortcutFocusRequester else null)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .then(if (itemRequester != null) Modifier.focusRequester(itemRequester) else Modifier)
                        .onFocusChanged { 
                            isFocused = it.isFocused 
                            if (it.isFocused && itemRequester != null) {
                                onShortcutFocused?.invoke(itemRequester)
                            }
                        }
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (native.action == KeyEvent.ACTION_DOWN) {
                                if (isFirst && native.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                                    onNavigateUp()
                                    return@onPreviewKeyEvent true
                                }
                                if (native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                                    if (itemRequester != null) {
                                        onShortcutFocused?.invoke(itemRequester)
                                    }
                                    onNavigateRight(index)
                                    return@onPreviewKeyEvent true
                                }
                                if (native.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                                    if (itemRequester != null) {
                                        onShortcutFocused?.invoke(itemRequester)
                                    }
                                    onShortcutSelected(shortcut)
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                        .clickable { 
                            if (itemRequester != null) {
                                onShortcutFocused?.invoke(itemRequester)
                            }
                            onShortcutSelected(shortcut) 
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isFocused || isSelected) {
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .height(11.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Spacer(modifier = Modifier.width(8.5.dp))
                    }

                    Text(
                        text = label,
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isFocused || isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp
                        ),
                        color = if (isFocused || isSelected) Color.White else Color(0xFF9EA3AC)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BetterNuvioRecommendationsSection(
    uiState: SearchUiState,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>,
    firstItemFocusRequester: FocusRequester,
    filterFocusRequester: FocusRequester,
    onNavigateToDetail: (String, String, String) -> Unit,
    onSelectType: (String) -> Unit,
    onSelectCatalog: (String) -> Unit,
    onSelectGenre: (String?) -> Unit,
    onSetFavorite: (String, String?, String?) -> Unit,
    onClearFavorite: (String) -> Unit,
    onLoadMore: () -> Unit,
    onNavigateLeftToClear: () -> Unit = {},
    onNavigateLeftToKeyboard: () -> Unit = {},
    onRegisterFocusBridge: ((keyboardRow: Int) -> Unit) -> Unit,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    initialFocusIndex: Int = -1,
    restoreFocusTrigger: Boolean = false,
    onDiscoverFocusRestored: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSaveScrollPosition: (Int, Int, Int) -> Unit = { _, _, _ -> },
    onMarkNavigatingToDetail: () -> Unit = {},
    modifier: Modifier = Modifier,
    onItemLongClick: ((MetaPreview) -> Unit)? = null
) {
    val items = uiState.discoverResults
    val currentItemsState = rememberUpdatedState(items)
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = initialScrollIndex,
        initialFirstVisibleItemScrollOffset = initialScrollOffset
    )
    var targetFocusIndex by remember { mutableIntStateOf(initialFocusIndex) }
    val cardFocusRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    var lastFocusedIndex by remember { mutableIntStateOf(if (initialFocusIndex >= 0) initialFocusIndex else initialScrollIndex) }
    val coroutineScope = rememberCoroutineScope()

    // Restaura o foco no card correto ao retornar da tela de detalhes
    LaunchedEffect(restoreFocusTrigger, initialFocusIndex) {
        if (restoreFocusTrigger || initialFocusIndex >= 0) {
            val targetIdx = if (initialFocusIndex >= 0) initialFocusIndex else lastFocusedIndex
            if (targetIdx >= 0 && items.isNotEmpty()) {
                val clamped = targetIdx.coerceIn(0, items.size - 1)
                gridState.scrollToItem(clamped)
                for (frame in 0..10) {
                    withFrameNanos { }
                    val req = cardFocusRequesters[clamped]
                    val ok = if (req != null) runCatching { req.requestFocus() }.isSuccess else false
                    if (ok) break
                }
            }
            if (restoreFocusTrigger) {
                onDiscoverFocusRestored()
            }
        }
    }

    // Ponte de foco inteligente: do teclado/atalhos para o catálogo
    LaunchedEffect(Unit) {
        onRegisterFocusBridge { keyboardRow ->
            if (keyboardRow == 0) {
                coroutineScope.launch {
                    runCatching { filterFocusRequester.requestFocus() }
                }
                return@onRegisterFocusBridge
            }
            val list = currentItemsState.value
            if (list.isEmpty()) {
                return@onRegisterFocusBridge
            }

            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val targetCardIndex = if (visibleItems.isNotEmpty()) {
                // Se o primeiro item visível tiver offset.y < 0, ele está cortado pela metade no topo.
                // Nesse caso, o primeiro item com offset.y >= 0 é o card da linha inteira centralizada!
                // Se a primeira linha tiver offset.y >= 0, ela é a linha do topo inteira.
                val firstFullItem = visibleItems.firstOrNull { it.offset.y >= 0 } ?: visibleItems.first()
                (firstFullItem.index / 4) * 4
            } else {
                (gridState.firstVisibleItemIndex / 4) * 4
            }.coerceIn(0, list.size - 1)

            coroutineScope.launch {
                var ok = cardFocusRequesters[targetCardIndex]?.let {
                    runCatching { it.requestFocus() }.isSuccess
                } ?: false

                if (!ok) {
                    for (frame in 0..6) {
                        withFrameNanos { }
                        val req = cardFocusRequesters[targetCardIndex]
                        if (req != null && runCatching { req.requestFocus() }.isSuccess) {
                            ok = true
                            break
                        }
                    }
                }
            }
        }
    }

    // Auto-paginação de recomendações
    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastIndex ->
                if (lastIndex != null && lastIndex >= items.size - 6 && !uiState.discoverLoading) {
                    onLoadMore()
                }
            }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Título
        Text(
            text = stringResource(R.string.search_recommendations_title),
            style = androidx.tv.material3.MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = Color(0xFFF0F1F5)
        )

        // Barra de 3 Filtros em Pílulas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Filtro 1: Tipo (Filmes, Séries, TV)
            val strMovies = stringResource(R.string.search_filter_movies)
            val strSeries = stringResource(R.string.search_filter_series)
            val strTv = "TV"
            val strAll = stringResource(R.string.search_filter_all)
            val strAllGenres = stringResource(R.string.search_filter_all_genres)

            val currentTypeLabel = when (uiState.selectedDiscoverType) {
                "series" -> strSeries
                "tv" -> strTv
                else -> strMovies
            }

            BetterNuvioFilterPill(
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(filterFocusRequester),
                label = stringResource(R.string.search_filter_type),
                value = currentTypeLabel,
                options = listOf("movie" to strMovies, "series" to strSeries, "tv" to strTv),
                onSelect = { onSelectType(it) },
                onNavigateLeft = onNavigateLeftToClear,
                onNavigateDown = {
                    coroutineScope.launch {
                        val firstVis = gridState.firstVisibleItemIndex
                        val req = cardFocusRequesters[firstVis] ?: cardFocusRequesters[0]
                        req?.let { runCatching { it.requestFocus() } }
                    }
                }
            )

            // Filtro 2: Catálogo
            val catalogOptions = remember(uiState.discoverCatalogs, uiState.selectedDiscoverType, strAll) {
                val isTvType = uiState.selectedDiscoverType.equals("tv", true) || uiState.selectedDiscoverType.equals("channel", true)
                val cats = uiState.discoverCatalogs.filter {
                    if (isTvType) it.type.equals("tv", true) || it.type.equals("channel", true)
                    else it.type == uiState.selectedDiscoverType
                }
                listOf("" to strAll) + cats.map { it.key to it.catalogName }
            }
            val currentCatalogName = uiState.discoverCatalogs.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey }?.catalogName ?: strAll
            BetterNuvioFilterPill(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.search_filter_catalog),
                value = currentCatalogName,
                options = catalogOptions,
                onSelect = { onSelectCatalog(it) },
                onNavigateDown = {
                    coroutineScope.launch {
                        val firstVis = gridState.firstVisibleItemIndex
                        val req = cardFocusRequesters[firstVis] ?: cardFocusRequesters[0]
                        req?.let { runCatching { it.requestFocus() } }
                    }
                }
            )

            // Filtro 3: Gênero (agrega todos os gêneros do tipo caso catálogo seja Todos)
            val genreOptions = remember(uiState.discoverCatalogs, uiState.selectedDiscoverCatalogKey, uiState.selectedDiscoverType, strAllGenres) {
                val isTvType = uiState.selectedDiscoverType.equals("tv", true) || uiState.selectedDiscoverType.equals("channel", true)
                val rawGenres = if (uiState.selectedDiscoverCatalogKey != null) {
                    val selectedCat = uiState.discoverCatalogs.firstOrNull { it.key == uiState.selectedDiscoverCatalogKey }
                    selectedCat?.genres.orEmpty()
                } else {
                    uiState.discoverCatalogs
                        .filter {
                            if (isTvType) it.type.equals("tv", true) || it.type.equals("channel", true)
                            else it.type.equals(uiState.selectedDiscoverType, true)
                        }
                        .flatMap { it.genres }
                        .distinct()
                        .sorted()
                }
                listOf("__all__" to strAllGenres) + rawGenres.map { it to it }
            }
            val currentGenreLabel = uiState.selectedDiscoverGenre ?: strAllGenres
            val currentType = uiState.selectedDiscoverType
            val currentFavGenre = uiState.favoritePerType[currentType]?.second

            BetterNuvioFilterPill(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.search_filter_genre),
                value = currentGenreLabel,
                options = genreOptions,
                isGenreDialog = true,
                favoriteGenre = currentFavGenre,
                onToggleFavorite = { chosenGenre ->
                    if (chosenGenre.equals(currentFavGenre, ignoreCase = true)) {
                        onClearFavorite(currentType)
                    } else {
                        onSetFavorite(currentType, uiState.selectedDiscoverCatalogKey, chosenGenre)
                    }
                },
                onSelect = { opt -> onSelectGenre(if (opt == "__all__") null else opt) },
                onNavigateDown = {
                    coroutineScope.launch {
                        val firstVis = gridState.firstVisibleItemIndex
                        val req = cardFocusRequesters[firstVis] ?: cardFocusRequesters[0]
                        req?.let { runCatching { it.requestFocus() } }
                    }
                }
            )
        }

        // Grade de 4 Colunas com Pôsteres
        if (items.isEmpty() && uiState.discoverLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        } else if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.search_no_results_title),
                        style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF9EA3AC)
                    )
                    androidx.tv.material3.Button(
                        onClick = onRetry,
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = Color(0xFF2C2F36),
                            focusedContainerColor = Color.White
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_retry),
                            color = Color.Unspecified
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 28.dp)
            ) {
                gridItemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}_$index" }
                ) { index, item ->
                    val isFirstCol = index % 4 == 0
                    val isFirstRow = index < 4
                    val isSeries = item.type == com.nuvio.tv.domain.model.ContentType.SERIES || item.apiType.equals("series", ignoreCase = true)
                    val isTv = uiState.selectedDiscoverType.equals("tv", ignoreCase = true) ||
                        uiState.selectedDiscoverType.equals("channel", ignoreCase = true) ||
                        item.apiType.equals("tv", ignoreCase = true) ||
                        item.apiType.equals("channel", ignoreCase = true)
                    val isWatched = if (isTv) {
                        false
                    } else if (isSeries) {
                        watchedSeriesIds.contains(item.id)
                    } else {
                        watchedMovieIds.contains(item.id)
                    }

                    val cardRequester = cardFocusRequesters.getOrPut(index) { FocusRequester() }

                    BetterNuvioPosterCard(
                        item = item,
                        index = index,
                        isTvType = isTv,
                        isWatched = isWatched,
                        focusRequester = cardRequester,
                        onFocusChanged = { focused ->
                            if (focused) {
                                lastFocusedIndex = index
                                targetFocusIndex = -1
                            }
                        },
                        onClick = {
                            onSaveScrollPosition(
                                gridState.firstVisibleItemIndex,
                                gridState.firstVisibleItemScrollOffset,
                                index
                            )
                            onMarkNavigatingToDetail()
                            HeroBackdropState.update(item.backdropUrl)
                            onNavigateToDetail(item.id, item.apiType, "")
                        },
                        onItemLongClick = if (onItemLongClick != null) { { onItemLongClick(item) } } else null,
                        onNavigateLeft = if (isFirstCol) onNavigateLeftToKeyboard else null,
                        onNavigateUp = if (isFirstRow) { { runCatching { filterFocusRequester.requestFocus() } } } else null
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    isGenreDialog: Boolean = false,
    favoriteGenre: String? = null,
    onToggleFavorite: ((String) -> Unit)? = null
) {
    val selectedIndex = remember(options, selectedValue) {
        val found = options.indexOfFirst { (key, optLabel) ->
            optLabel.equals(selectedValue, ignoreCase = true) ||
                (key.isEmpty() && (selectedValue.equals("Todos", ignoreCase = true) || selectedValue.equals("All", ignoreCase = true))) ||
                (key == "__all__" && (selectedValue.equals("Todos os gêneros", ignoreCase = true) || selectedValue.equals("All genres", ignoreCase = true) || selectedValue.equals("Todos", ignoreCase = true) || selectedValue.equals("All", ignoreCase = true))) ||
                key.equals(selectedValue, ignoreCase = true)
        }
        found.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (selectedIndex - 2).coerceAtLeast(0)
    )
    val selectedFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            listState.scrollToItem((selectedIndex - 2).coerceAtLeast(0))
            delay(70)
            runCatching { selectedFocusRequester.requestFocus() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(380.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF181A20))
                    .border(1.dp, Color(0xFF2B2E38), RoundedCornerShape(20.dp))
                    .clickable(enabled = false) {}
                    .padding(20.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = title,
                        style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = Color.White
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(options) { index, (key, optLabel) ->
                            val isSelected = index == selectedIndex
                            val isFavorite = isGenreDialog && favoriteGenre != null && (key.equals(favoriteGenre, ignoreCase = true) || optLabel.equals(favoriteGenre, ignoreCase = true))
                            var isFocused by remember { mutableStateOf(false) }
                            val itemFocusRequester = if (isSelected) selectedFocusRequester else remember { FocusRequester() }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(itemFocusRequester)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        when {
                                            isFocused -> Color.White.copy(alpha = 0.22f)
                                            isSelected -> Color(0xFFE50914).copy(alpha = 0.25f)
                                            else -> Color.Transparent
                                        }
                                    )
                                    .border(
                                        width = if (isFocused) 1.5.dp else if (isSelected) 1.dp else 0.dp,
                                        color = if (isFocused) Color.White else if (isSelected) Color(0xFFE50914).copy(alpha = 0.8f) else Color.Transparent,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        onSelect(key)
                                        onDismiss()
                                    }
                                    .onPreviewKeyEvent { event ->
                                        val native = event.nativeKeyEvent
                                        if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                                            val isOptionsKey = native.keyCode in listOf(
                                                android.view.KeyEvent.KEYCODE_MENU,
                                                android.view.KeyEvent.KEYCODE_INFO,
                                                android.view.KeyEvent.KEYCODE_SETTINGS,
                                                android.view.KeyEvent.KEYCODE_BUTTON_Y,
                                                android.view.KeyEvent.KEYCODE_PROG_RED
                                            )
                                            if (isOptionsKey && isGenreDialog && onToggleFavorite != null) {
                                                onToggleFavorite(key)
                                                return@onPreviewKeyEvent true
                                            }
                                            if (native.keyCode in listOf(
                                                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                                    android.view.KeyEvent.KEYCODE_ENTER,
                                                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                                                )
                                            ) {
                                                onSelect(key)
                                                onDismiss()
                                                return@onPreviewKeyEvent true
                                            }
                                        }
                                        false
                                    }
                                    .focusable()
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Text(
                                            text = optLabel,
                                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 14.sp,
                                                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal
                                            ),
                                            color = if (isFocused) Color.White else if (isSelected) Color(0xFFE2E4EA) else Color(0xFF9EA3AF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isFavorite) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(Color(0xFFE6A100).copy(alpha = 0.25f))
                                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.search_genre_favorite_badge),
                                                    style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = Color(0xFFFFD54F)
                                                )
                                            }
                                        }
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (isGenreDialog) {
                        Text(
                            text = stringResource(R.string.search_genre_favorite_hint),
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = Color(0xFF7A7E88),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BetterNuvioFilterPill(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    isGenreDialog: Boolean = false,
    favoriteGenre: String? = null,
    onToggleFavorite: ((String) -> Unit)? = null,
    onNavigateLeft: (() -> Unit)? = null,
    onNavigateDown: (() -> Unit)? = null
) {
    var showDialog by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    if (showDialog) {
        FilterChoiceDialog(
            title = label,
            options = options,
            selectedValue = value,
            onSelect = onSelect,
            onDismiss = { showDialog = false },
            isGenreDialog = isGenreDialog,
            favoriteGenre = favoriteGenre,
            onToggleFavorite = onToggleFavorite
        )
    }

    androidx.tv.material3.Card(
        onClick = { showDialog = true },
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && onNavigateLeft != null) {
                        onNavigateLeft()
                        return@onPreviewKeyEvent true
                    }
                    if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && onNavigateDown != null) {
                        onNavigateDown()
                        return@onPreviewKeyEvent true
                    }
                }
                false
            },
        shape = androidx.tv.material3.CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = androidx.tv.material3.CardDefaults.colors(
            containerColor = Color(0xFF212328),
            focusedContainerColor = Color(0xFF2C2F36)
        ),
        border = androidx.tv.material3.CardDefaults.border(
            border = androidx.tv.material3.Border(border = BorderStroke(1.dp, Color(0xFF2B2E36))),
            focusedBorder = androidx.tv.material3.Border(border = BorderStroke(1.5.dp, Color.White))
        ),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.0f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label: $value",
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = if (isFocused) Color.White else Color(0xFFD0D3D9),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = if (isFocused) Color.White else Color(0xFF8E939D),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BetterNuvioPosterCard(
    item: MetaPreview,
    index: Int,
    isTvType: Boolean,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onItemLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onNavigateLeft: (() -> Unit)? = null,
    onNavigateUp: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val cardShape = remember { RoundedCornerShape(10.dp) }
    val context = LocalContext.current

    val coroutineScope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var handledAsLongPress by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.tv.material3.Card(
            onClick = {
                if (!handledAsLongPress) {
                    onClick()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged {
                    isFocused = it.isFocused
                    onFocusChanged?.invoke(it.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == android.view.KeyEvent.ACTION_DOWN) {
                        val isMenuKey = native.keyCode == android.view.KeyEvent.KEYCODE_MENU ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_INFO ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_SETTINGS ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_SELECT ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_Y ||
                            native.keyCode == android.view.KeyEvent.KEYCODE_PROG_RED

                        if (isMenuKey && onItemLongClick != null) {
                            onItemLongClick()
                            return@onPreviewKeyEvent true
                        }
                        if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && onNavigateLeft != null) {
                            onNavigateLeft()
                            return@onPreviewKeyEvent true
                        }
                        if (native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && onNavigateUp != null) {
                            onNavigateUp()
                            return@onPreviewKeyEvent true
                        }
                        if (native.keyCode in listOf(
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                            ) && native.repeatCount == 0 && onItemLongClick != null
                        ) {
                            handledAsLongPress = false
                            longPressJob?.cancel()
                            longPressJob = coroutineScope.launch {
                                delay(600)
                                handledAsLongPress = true
                                onItemLongClick()
                            }
                        }
                    } else if (native.action == android.view.KeyEvent.ACTION_UP) {
                        if (native.keyCode in listOf(
                                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                                android.view.KeyEvent.KEYCODE_ENTER,
                                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                            )
                        ) {
                            longPressJob?.cancel()
                            longPressJob = null
                            if (handledAsLongPress) {
                                handledAsLongPress = false
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                },
            shape = androidx.tv.material3.CardDefaults.shape(cardShape),
            colors = androidx.tv.material3.CardDefaults.colors(
                containerColor = Color(0xFF181A20),
                focusedContainerColor = Color(0xFF181A20)
            ),
            border = androidx.tv.material3.CardDefaults.border(
                border = androidx.tv.material3.Border(border = BorderStroke(1.dp, Color(0xFF22242C))),
                focusedBorder = androidx.tv.material3.Border(border = BorderStroke(2.dp, Color.White))
            ),
            scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.04f)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val imageUrl = item.poster ?: item.background
                if (!imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = item.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF20232A))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.name,
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            ),
                            color = Color(0xFFE0E2E8),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Indicador sutil de assistido (apenas para filmes e séries, nunca para TV)
                if (isWatched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.Black.copy(alpha = 0.70f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "✔",
                            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }

        // Título exclusivo para canais de TV com efeito Marquee rolando suavemente
        if (isTvType) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.name,
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 12.sp
                ),
                color = if (isFocused) Color.White else Color(0xFFC0C3CC),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 600,
                        repeatDelayMillis = 600
                    )
            )
        }
    }
}

@Composable
private fun BetterNuvioSearchStatus(
    title: String,
    subtitle: String,
    helperText: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = androidx.tv.material3.MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = Color(0xFFF5F6FA),
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 15.sp
                ),
                color = Color(0xFF8C9099),
                textAlign = TextAlign.Center
            )
            if (helperText != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = helperText,
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp
                    ),
                    color = Color(0xFF70737C),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
