@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.util.languageCodeToName
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun AudioSelectionOverlay(
    visible: Boolean,
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    isForceDubActive: Boolean = false,
    isForceDubLoading: Boolean = false,
    forceDubAppliedOffsetMs: Long? = null,
    isLive: Boolean = false,
    onForceDubToggle: () -> Unit = {},
    onForceDubOffsetChange: (Long) -> Unit = {},
    onForceDubOffsetReset: () -> Unit = {},
    onTrackSelected: (Int) -> Unit,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val forceDubFocusRequester = remember { FocusRequester() }
    val tracksFocusRequester = remember { FocusRequester() }
    val delayMinusFocusRequester = remember { FocusRequester() }
    val delayPlusFocusRequester = remember { FocusRequester() }
    val presetZeroFocusRequester = remember { FocusRequester() }
    val presetMinus500FocusRequester = remember { FocusRequester() }
    val presetPlus500FocusRequester = remember { FocusRequester() }
    val presetBlurayFocusRequester = remember { FocusRequester() }
    val ampMinusFocusRequester = remember { FocusRequester() }
    val ampPlusFocusRequester = remember { FocusRequester() }
    val centerMinusFocusRequester = remember { FocusRequester() }
    val centerPlusFocusRequester = remember { FocusRequester() }
    val persistFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    var lastFocusedAudioIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingControlFocusTarget by rememberSaveable {
        mutableStateOf<AudioControlFocusTarget?>(null)
    }

    LaunchedEffect(visible, tracks, selectedIndex) {
        if (!visible) return@LaunchedEffect

        if (tracks.isNotEmpty()) {
            val selectedListIndex = tracks.indexOfFirst { it.index == selectedIndex }.takeIf { it >= 0 } ?: 0
            listState.scrollToItem(selectedListIndex)
            delay(120)
            runCatching { tracksFocusRequester.requestFocus() }
        } else {
            delay(120)
            val initialControlsFocusRequester = when {
                canDecreaseDelay -> delayMinusFocusRequester
                canIncreaseDelay -> delayPlusFocusRequester
                canDecreaseAmp -> ampMinusFocusRequester
                canIncreaseAmp -> ampPlusFocusRequester
                canDecreaseCenterMix -> centerMinusFocusRequester
                canIncreaseCenterMix -> centerPlusFocusRequester
                else -> persistFocusRequester
            }
            runCatching { initialControlsFocusRequester.requestFocus() }
        }
    }

    LaunchedEffect(visible, pendingControlFocusTarget, audioAmplificationDb, isAmplificationAvailable) {
        if (!visible) return@LaunchedEffect
        val target = pendingControlFocusTarget ?: return@LaunchedEffect
        val targetCanFocus = when (target) {
            AudioControlFocusTarget.DelayMinus -> canDecreaseDelay
            AudioControlFocusTarget.DelayPlus -> canIncreaseDelay
            AudioControlFocusTarget.AmpMinus -> canDecreaseAmp
            AudioControlFocusTarget.AmpPlus -> canIncreaseAmp
            AudioControlFocusTarget.CenterMinus -> canDecreaseCenterMix
            AudioControlFocusTarget.CenterPlus -> canIncreaseCenterMix
            AudioControlFocusTarget.Persist -> true
        }
        if (!targetCanFocus) return@LaunchedEffect
        val controlFocusRequester = when (target) {
            AudioControlFocusTarget.DelayMinus -> delayMinusFocusRequester
            AudioControlFocusTarget.DelayPlus -> delayPlusFocusRequester
            AudioControlFocusTarget.AmpMinus -> ampMinusFocusRequester
            AudioControlFocusTarget.AmpPlus -> ampPlusFocusRequester
            AudioControlFocusTarget.CenterMinus -> centerMinusFocusRequester
            AudioControlFocusTarget.CenterPlus -> centerPlusFocusRequester
            AudioControlFocusTarget.Persist -> persistFocusRequester
        }
        delay(80)
        controlFocusRequester.requestFocusAfterFrames(frames = 2)
        delay(200)
        runCatching { controlFocusRequester.requestFocus() }
        pendingControlFocusTarget = null
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 28.dp, bottom = 64.dp)
    ) {
        Column(
            modifier = Modifier
                .width(724.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.audio_dialog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
            ) {
                Column(modifier = Modifier.width(444.dp)) {
                    val rightFocusTarget = if (!isLive) {
                        forceDubFocusRequester
                    } else if (canDecreaseDelay) {
                        delayMinusFocusRequester
                    } else {
                        delayPlusFocusRequester
                    }
                    AudioTracksContent(
                        tracks = tracks,
                        selectedIndex = selectedIndex,
                        listState = listState,
                        initialFocusRequester = tracksFocusRequester,
                        rightFocusRequester = rightFocusTarget,
                        onTrackFocused = { lastFocusedAudioIndex = it },
                        onTrackSelected = onTrackSelected
                    )
                }
                Column(modifier = Modifier.width(268.dp)) {
                    AudioControlsContent(
                        audioDelayMs = audioDelayMs,
                        audioAmplificationDb = audioAmplificationDb,
                        isAmplificationAvailable = isAmplificationAvailable,
                        centerMixLevelDb = centerMixLevelDb,
                        isCenterMixAvailable = isCenterMixAvailable,
                        persistAmplification = persistAmplification,
                        isForceDubActive = isForceDubActive,
                        isForceDubLoading = isForceDubLoading,
                        forceDubAppliedOffsetMs = forceDubAppliedOffsetMs,
                        isLive = isLive,
                        onForceDubToggle = onForceDubToggle,
                        onForceDubOffsetChange = onForceDubOffsetChange,
                        onForceDubOffsetReset = onForceDubOffsetReset,
                        forceDubFocusRequester = forceDubFocusRequester,
                        delayMinusFocusRequester = delayMinusFocusRequester,
                        delayPlusFocusRequester = delayPlusFocusRequester,
                        presetZeroFocusRequester = presetZeroFocusRequester,
                        presetMinus500FocusRequester = presetMinus500FocusRequester,
                        presetPlus500FocusRequester = presetPlus500FocusRequester,
                        presetBlurayFocusRequester = presetBlurayFocusRequester,
                        ampMinusFocusRequester = ampMinusFocusRequester,
                        ampPlusFocusRequester = ampPlusFocusRequester,
                        centerMinusFocusRequester = centerMinusFocusRequester,
                        centerPlusFocusRequester = centerPlusFocusRequester,
                        persistFocusRequester = persistFocusRequester,
                        leftFocusRequester = tracksFocusRequester,
                        onAudioDelayChange = onAudioDelayChange,
                        onAmplificationChange = { nextDb, focusTarget ->
                            pendingControlFocusTarget = focusTarget
                            onAmplificationChange(nextDb)
                        },
                        onCenterMixLevelChange = onCenterMixLevelChange,
                        onPersistAmplificationChange = onPersistAmplificationChange
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioTracksContent(
    tracks: List<TrackInfo>,
    selectedIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    onTrackFocused: (Int) -> Unit,
    onTrackSelected: (Int) -> Unit
) {
    if (tracks.isEmpty()) {
        Text(
            text = stringResource(R.string.audio_lang_default),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.md)
        )
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = NuvioTheme.spacing.sm, bottom = NuvioTheme.spacing.sm),
        modifier = Modifier
            .heightIn(max = 620.dp)
            .fillMaxWidth()
    ) {
        items(items = tracks, key = { track -> track.index }) { track ->
            AudioTrackCard(
                track = track,
                isSelected = track.index == selectedIndex,
                onFocused = { onTrackFocused(track.index) },
                onClick = { onTrackSelected(track.index) },
                rightFocusRequester = rightFocusRequester,
                focusRequester = if (
                    track.index == selectedIndex || (selectedIndex < 0 && track == tracks.firstOrNull())
                ) {
                    initialFocusRequester
                } else {
                    null
                }
            )
        }
    }
}

@Composable
private fun AudioTrackCard(
    track: TrackInfo,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    rightFocusRequester: FocusRequester,
    focusRequester: FocusRequester?
) {
    val metadata = listOfNotNull(
        track.codec,
        track.channelCount?.let { "$it ch" },
        track.sampleRate?.let { "${it / 1000} kHz" }
    ).joinToString(" | ")

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties { right = rightFocusRequester }
            .onFocusChanged { if (it.isFocused) onFocused() },
        colors = CardDefaults.colors(
            containerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (isSelected) NuvioTheme.colors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primaryTextColor = if (isSelected) NuvioTheme.colors.OnSecondary else Color.White
        val secondaryTextColor = if (isSelected) {
            NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }
        val metadataTextColor = if (isSelected) {
            NuvioTheme.colors.OnSecondary.copy(alpha = 0.72f)
        } else {
            NuvioTheme.colors.TextTertiary
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
            ) {
                Text(
                    text = track.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryTextColor
                )
                if (!track.language.isNullOrBlank() && track.language != "und") {
                    Text(
                        text = languageCodeToName(track.language),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor
                    )
                }
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = metadataTextColor
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = NuvioTheme.colors.OnSecondary
                )
            }
        }
    }
}

@Composable
private fun AudioControlsContent(
    audioDelayMs: Int,
    audioAmplificationDb: Int,
    isAmplificationAvailable: Boolean,
    centerMixLevelDb: Int,
    isCenterMixAvailable: Boolean,
    persistAmplification: Boolean,
    isForceDubActive: Boolean,
    isForceDubLoading: Boolean,
    forceDubAppliedOffsetMs: Long? = null,
    isLive: Boolean = false,
    onForceDubToggle: () -> Unit,
    onForceDubOffsetChange: (Long) -> Unit = {},
    onForceDubOffsetReset: () -> Unit = {},
    forceDubFocusRequester: FocusRequester,
    delayMinusFocusRequester: FocusRequester,
    delayPlusFocusRequester: FocusRequester,
    presetZeroFocusRequester: FocusRequester,
    presetMinus500FocusRequester: FocusRequester,
    presetPlus500FocusRequester: FocusRequester,
    presetBlurayFocusRequester: FocusRequester,
    ampMinusFocusRequester: FocusRequester,
    ampPlusFocusRequester: FocusRequester,
    centerMinusFocusRequester: FocusRequester,
    centerPlusFocusRequester: FocusRequester,
    persistFocusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    onAudioDelayChange: (Int) -> Unit,
    onAmplificationChange: (Int, AudioControlFocusTarget) -> Unit,
    onCenterMixLevelChange: (Int) -> Unit,
    onPersistAmplificationChange: (Boolean) -> Unit
) {
    val currentDelayMs = audioDelayMs.coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
    val canDecreaseDelay = currentDelayMs > AUDIO_DELAY_MIN_MS
    val canIncreaseDelay = currentDelayMs < AUDIO_DELAY_MAX_MS
    val currentDb = audioAmplificationDb.coerceIn(AUDIO_AMPLIFICATION_MIN_DB, AUDIO_AMPLIFICATION_MAX_DB)
    val canDecreaseAmp = isAmplificationAvailable && currentDb > AUDIO_AMPLIFICATION_MIN_DB
    val canIncreaseAmp = isAmplificationAvailable && currentDb < AUDIO_AMPLIFICATION_MAX_DB
    val currentCenterMixDb = centerMixLevelDb.coerceIn(CENTER_MIX_LEVEL_MIN_DB, CENTER_MIX_LEVEL_MAX_DB)
    val canDecreaseCenterMix = isCenterMixAvailable && currentCenterMixDb > CENTER_MIX_LEVEL_MIN_DB
    val canIncreaseCenterMix = isCenterMixAvailable && currentCenterMixDb < CENTER_MIX_LEVEL_MAX_DB

    val firstDelayFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        delayPlusFocusRequester
    }
    val firstAmpFocusRequester = when {
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val firstCenterFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        else -> persistFocusRequester
    }
    val persistUpFocusRequester = when {
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        else -> presetZeroFocusRequester
    }
    val delayPlusLeftFocusRequester = if (canDecreaseDelay) {
        delayMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val ampPlusLeftFocusRequester = if (canDecreaseAmp) {
        ampMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val centerPlusLeftFocusRequester = if (canDecreaseCenterMix) {
        centerMinusFocusRequester
    } else {
        leftFocusRequester
    }
    val persistLeftFocusRequester = when {
        canIncreaseCenterMix -> centerPlusFocusRequester
        canDecreaseCenterMix -> centerMinusFocusRequester
        canIncreaseAmp -> ampPlusFocusRequester
        canDecreaseAmp -> ampMinusFocusRequester
        else -> leftFocusRequester
    }
    val centerHelperText = if (isCenterMixAvailable) {
        stringResource(R.string.audio_center_mix_help)
    } else {
        stringResource(R.string.audio_center_mix_unavailable)
    }

    val amplificationHelperText = when {
        !isAmplificationAvailable -> stringResource(R.string.audio_mix_unavailable)
        persistAmplification -> stringResource(
            R.string.audio_mix_range_saved,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
        else -> stringResource(
            R.string.audio_mix_range,
            AUDIO_AMPLIFICATION_MIN_DB,
            AUDIO_AMPLIFICATION_MAX_DB
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NuvioTheme.spacing.xs, bottom = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!isLive) {
            ForceDubCard(
                isActive = isForceDubActive,
                isLoading = isForceDubLoading,
                appliedOffsetMs = forceDubAppliedOffsetMs,
                focusRequester = forceDubFocusRequester,
                leftFocusRequester = leftFocusRequester,
                downFocusRequester = firstDelayFocusRequester,
                onClick = onForceDubToggle
            )
        }

        AdjustmentSection(
            title = stringResource(R.string.audio_delay_label),
            valueText = formatAudioDelay(currentDelayMs),
            helperText = stringResource(
                R.string.audio_delay_range,
                AUDIO_DELAY_MIN_MS / 1000f,
                AUDIO_DELAY_MAX_MS / 1000f
            ),
            canDecrease = canDecreaseDelay,
            canIncrease = canIncreaseDelay,
            minusFocusRequester = delayMinusFocusRequester,
            plusFocusRequester = delayPlusFocusRequester,
            minusLeftFocusRequester = leftFocusRequester,
            plusLeftFocusRequester = delayPlusLeftFocusRequester,
            upFocusRequester = if (!isLive) forceDubFocusRequester else null,
            downFocusRequester = presetZeroFocusRequester,
            onDecrease = {
                val nextDelayMs = currentDelayMs - AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncrease = {
                val nextDelayMs = currentDelayMs + AUDIO_DELAY_STEP_MS
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            },
            onDecreaseHold = { stepMs ->
                val nextDelayMs = currentDelayMs - stepMs
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs <= AUDIO_DELAY_MIN_MS && canIncreaseDelay) {
                    runCatching { delayPlusFocusRequester.requestFocus() }
                }
            },
            onIncreaseHold = { stepMs ->
                val nextDelayMs = currentDelayMs + stepMs
                onAudioDelayChange(nextDelayMs)
                if (nextDelayMs >= AUDIO_DELAY_MAX_MS && canDecreaseDelay) {
                    runCatching { delayMinusFocusRequester.requestFocus() }
                }
            }
        )

        // Presets Rápidos de Sincronia (Quick Sync)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickPresetButton(
                text = stringResource(R.string.audio_delay_preset_zero),
                focusRequester = presetZeroFocusRequester,
                leftFocusRequester = leftFocusRequester,
                rightFocusRequester = presetMinus500FocusRequester,
                upFocusRequester = delayMinusFocusRequester,
                downFocusRequester = firstAmpFocusRequester,
                onClick = {
                    onAudioDelayChange(0)
                    if (isForceDubActive) {
                        onForceDubOffsetReset()
                    }
                }
            )
            QuickPresetButton(
                text = stringResource(R.string.audio_delay_preset_minus_half_sec),
                focusRequester = presetMinus500FocusRequester,
                leftFocusRequester = presetZeroFocusRequester,
                rightFocusRequester = presetPlus500FocusRequester,
                upFocusRequester = delayMinusFocusRequester,
                downFocusRequester = firstAmpFocusRequester,
                onClick = {
                    val next = (currentDelayMs - 500).coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
                    onAudioDelayChange(next)
                }
            )
            QuickPresetButton(
                text = stringResource(R.string.audio_delay_preset_plus_half_sec),
                focusRequester = presetPlus500FocusRequester,
                leftFocusRequester = presetMinus500FocusRequester,
                rightFocusRequester = presetBlurayFocusRequester,
                upFocusRequester = delayPlusFocusRequester,
                downFocusRequester = firstAmpFocusRequester,
                onClick = {
                    val next = (currentDelayMs + 500).coerceIn(AUDIO_DELAY_MIN_MS, AUDIO_DELAY_MAX_MS)
                    onAudioDelayChange(next)
                }
            )
            QuickPresetButton(
                text = stringResource(R.string.audio_delay_preset_bluray),
                focusRequester = presetBlurayFocusRequester,
                leftFocusRequester = presetPlus500FocusRequester,
                rightFocusRequester = FocusRequester.Default,
                upFocusRequester = delayPlusFocusRequester,
                downFocusRequester = firstAmpFocusRequester,
                onClick = {
                    if (isForceDubActive) {
                        onForceDubOffsetChange(2100L)
                        onAudioDelayChange(0)
                    } else {
                        onAudioDelayChange(2100)
                    }
                }
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdjustmentSection(
                title = stringResource(R.string.audio_mix_label),
                valueText = stringResource(R.string.audio_mix_value_db, currentDb),
                helperText = amplificationHelperText,
                canDecrease = canDecreaseAmp,
                canIncrease = canIncreaseAmp,
                minusFocusRequester = ampMinusFocusRequester,
                plusFocusRequester = ampPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = ampPlusLeftFocusRequester,
                upFocusRequester = firstDelayFocusRequester,
                downFocusRequester = firstCenterFocusRequester,
                onDecrease = {
                    val nextDb = currentDb - 1
                    val target = if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        AudioControlFocusTarget.AmpPlus
                    } else {
                        AudioControlFocusTarget.AmpMinus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb <= AUDIO_AMPLIFICATION_MIN_DB && canIncreaseAmp) {
                        runCatching { ampPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentDb + 1
                    val target = if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        AudioControlFocusTarget.AmpMinus
                    } else {
                        AudioControlFocusTarget.AmpPlus
                    }
                    onAmplificationChange(nextDb, target)
                    if (nextDb >= AUDIO_AMPLIFICATION_MAX_DB && canDecreaseAmp) {
                        runCatching { ampMinusFocusRequester.requestFocus() }
                    }
                }
            )

            AdjustmentSection(
                title = stringResource(R.string.audio_center_mix_label),
                valueText = stringResource(R.string.audio_center_mix_value_db, currentCenterMixDb),
                helperText = centerHelperText,
                canDecrease = canDecreaseCenterMix,
                canIncrease = canIncreaseCenterMix,
                minusFocusRequester = centerMinusFocusRequester,
                plusFocusRequester = centerPlusFocusRequester,
                minusLeftFocusRequester = leftFocusRequester,
                plusLeftFocusRequester = centerPlusLeftFocusRequester,
                upFocusRequester = firstAmpFocusRequester,
                downFocusRequester = persistFocusRequester,
                onDecrease = {
                    val nextDb = currentCenterMixDb - 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb <= CENTER_MIX_LEVEL_MIN_DB && canIncreaseCenterMix) {
                        runCatching { centerPlusFocusRequester.requestFocus() }
                    }
                },
                onIncrease = {
                    val nextDb = currentCenterMixDb + 1
                    onCenterMixLevelChange(nextDb)
                    if (nextDb >= CENTER_MIX_LEVEL_MAX_DB && canDecreaseCenterMix) {
                        runCatching { centerMinusFocusRequester.requestFocus() }
                    }
                }
            )

            Card(
                onClick = { onPersistAmplificationChange(!persistAmplification) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(persistFocusRequester)
                    .focusProperties {
                        left = persistLeftFocusRequester
                        up = persistUpFocusRequester
                    },
                colors = CardDefaults.colors(
                    containerColor = if (persistAmplification) NuvioTheme.colors.Secondary else Color.Transparent,
                    focusedContainerColor = if (persistAmplification) NuvioTheme.colors.Secondary else Color.Transparent
                ),
                shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
                border = CardDefaults.border(
                    border = Border(
                        border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    ),
                    focusedBorder = Border(
                        border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                        shape = RoundedCornerShape(NuvioTheme.radii.md)
                    )
                ),
                scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
            ) {
                Text(
                    text = if (persistAmplification) {
                        stringResource(R.string.audio_mix_persist_on)
                    } else {
                        stringResource(R.string.audio_mix_persist_off)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (persistAmplification) NuvioTheme.colors.OnSecondary else Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun AdjustmentSection(
    title: String,
    valueText: String,
    helperText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    minusFocusRequester: FocusRequester,
    plusFocusRequester: FocusRequester,
    minusLeftFocusRequester: FocusRequester,
    plusLeftFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    onDecreaseHold: ((Int) -> Unit)? = null,
    onIncreaseHold: ((Int) -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.92f)
        )

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StepCard(
                icon = Icons.Default.Remove,
                enabled = canDecrease,
                focusRequester = minusFocusRequester,
                leftFocusRequester = minusLeftFocusRequester,
                rightFocusRequester = if (canIncrease) plusFocusRequester else FocusRequester.Default,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onDecrease,
                onHoldTick = onDecreaseHold
            )
            StepCard(
                icon = Icons.Default.Add,
                enabled = canIncrease,
                focusRequester = plusFocusRequester,
                leftFocusRequester = plusLeftFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onClick = onIncrease,
                onHoldTick = onIncreaseHold
            )
        }

        Text(
            text = helperText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.66f)
        )
    }
}

@Composable
private fun StepCard(
    icon: ImageVector,
    enabled: Boolean,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester = FocusRequester.Default,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onClick: () -> Unit,
    onHoldTick: ((Int) -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }

    if (onHoldTick != null) {
        val isPressed by interactionSource.collectIsPressedAsState()
        val latestOnHoldTick by rememberUpdatedState(onHoldTick)
        LaunchedEffect(isPressed, enabled) {
            if (!isPressed || !enabled) return@LaunchedEffect
            // Let the initial click apply its normal (small) step; only start
            // auto-repeating once the button has been held down continuously.
            // The repeat step itself ramps up the longer the hold continues:
            // AUDIO_DELAY_HOLD_STEP_MS after AUDIO_DELAY_HOLD_THRESHOLD_MS,
            // then AUDIO_DELAY_HOLD_FAST_STEP_MS after AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS.
            delay(AUDIO_DELAY_HOLD_THRESHOLD_MS)
            var heldMs = AUDIO_DELAY_HOLD_THRESHOLD_MS
            while (isActive) {
                val step = if (heldMs >= AUDIO_DELAY_HOLD_FAST_THRESHOLD_MS) {
                    AUDIO_DELAY_HOLD_FAST_STEP_MS
                } else {
                    AUDIO_DELAY_HOLD_STEP_MS
                }
                latestOnHoldTick(step)
                delay(AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS)
                heldMs += AUDIO_DELAY_HOLD_REPEAT_INTERVAL_MS
            }
        }
    }

    Card(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .width(NuvioTheme.spacing.huge)
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enabled
                left = leftFocusRequester
                right = rightFocusRequester
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            },
        colors = CardDefaults.colors(
            containerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f),
            focusedContainerColor = if (enabled) Color.Transparent else Color.White.copy(alpha = 0.06f)
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, if (enabled) Color.White.copy(alpha = 0.18f) else Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(
                    width = NuvioTheme.spacing.xxs,
                    alpha = if (enabled) 1f else 0f
                ),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun ForceDubCard(
    isActive: Boolean,
    isLoading: Boolean,
    appliedOffsetMs: Long? = null,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                down = downFocusRequester
            },
        colors = CardDefaults.colors(
            containerColor = if (isActive) NuvioTheme.colors.Secondary else Color.Transparent,
            focusedContainerColor = if (isActive) NuvioTheme.colors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.Transparent),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primaryTextColor = if (isActive) NuvioTheme.colors.OnSecondary else Color.White
        val secondaryTextColor = if (isActive) {
            NuvioTheme.colors.OnSecondary.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = stringResource(R.string.force_dub_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryTextColor
                )
                Text(
                    text = when {
                        isLoading -> stringResource(R.string.force_dub_preparing)
                        isActive && appliedOffsetMs != null -> {
                            val sign = if (appliedOffsetMs > 0) "+" else ""
                            stringResource(R.string.force_dub_status_active) + " (${stringResource(R.string.force_dub_offset_current, "$sign$appliedOffsetMs ms")})"
                        }
                        isActive -> stringResource(R.string.force_dub_status_active)
                        else -> stringResource(R.string.force_dub_status_inactive)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor
                )
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = primaryTextColor,
                    strokeWidth = 2.dp
                )
            } else if (isActive) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = primaryTextColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun RowScope.QuickPresetButton(
    text: String,
    focusRequester: FocusRequester,
    leftFocusRequester: FocusRequester,
    rightFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .focusRequester(focusRequester)
            .focusProperties {
                left = leftFocusRequester
                right = rightFocusRequester
                upFocusRequester?.let { up = it }
                downFocusRequester?.let { down = it }
            },
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = NuvioTheme.colors.Secondary
        ),
        shape = CardDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(NuvioTheme.spacing.xxs, Color.White.copy(alpha = 0.14f)),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            ),
            focusedBorder = Border(
                border = NuvioTheme.focusRing.border(width = NuvioTheme.spacing.xxs),
                shape = RoundedCornerShape(NuvioTheme.radii.md)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

private enum class AudioControlFocusTarget {
    DelayMinus,
    DelayPlus,
    AmpMinus,
    AmpPlus,
    CenterMinus,
    CenterPlus,
    Persist
}

private fun formatAudioDelay(delayMs: Int): String {
    return if (delayMs == 0) {
        String.format(Locale.US, "%.3fs", 0f)
    } else {
        String.format(Locale.US, "%+.3fs", delayMs / 1000f)
    }
}
