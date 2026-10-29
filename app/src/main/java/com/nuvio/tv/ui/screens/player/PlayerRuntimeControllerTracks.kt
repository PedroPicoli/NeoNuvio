package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.nuvio.tv.ui.util.languageCodeToName
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncCandidateScope

@UnstableApi
internal fun PlayerRuntimeController.updateAvailableTracks(tracks: Tracks) {
    logSwitchTrace(
        stage = "exo-tracks-update-start",
        message = "groupCount=${tracks.groups.size} uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} " +
                "uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )
    val audioTracks = mutableListOf<TrackInfo>()
    val supportedAudioIndices = mutableSetOf<Int>()
    val subtitleTracks = mutableListOf<TrackInfo>()
    var selectedAudioIndex = -1
    var selectedSubtitleIndex = -1

    var hasVideoTrack = false
    var firstVideoFormat: Format? = null
    var selectedVideoFormat: Format? = null
    var bestVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE
    var selectedVideoTrackSupport = C.FORMAT_UNSUPPORTED_TYPE

    tracks.groups.forEachIndexed { groupIndex, trackGroup ->
        val trackType = trackGroup.type

        when (trackType) {
            C.TRACK_TYPE_VIDEO -> {
                if (trackGroup.length > 0) {
                    hasVideoTrack = true
                    if (firstVideoFormat == null) {
                        firstVideoFormat = trackGroup.getTrackFormat(0)
                    }
                }

                for (i in 0 until trackGroup.length) {
                    val support = trackGroup.getTrackSupport(i)
                    if (formatSupportRank(support) > formatSupportRank(bestVideoTrackSupport)) {
                        bestVideoTrackSupport = support
                    }
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        val currentSelected = selectedVideoFormat
                        if (currentSelected == null || format.width > currentSelected.width ||
                            (format.width == currentSelected.width && (format.bitrate > currentSelected.bitrate))
                        ) {
                            selectedVideoFormat = format
                            selectedVideoTrackSupport = support
                        }
                    }
                }

                selectedVideoFormat?.let { format ->
                    if (format.frameRate > 0f) {
                        val raw = format.frameRate
                        val snapped = FrameRateUtils.snapToStandardRate(raw)
                        val ambiguousCinemaTrack = PlayerFrameRateHeuristics.isAmbiguousCinema24(raw)
                        if (!ambiguousCinemaTrack) {
                            frameRateProbeJob?.cancel()
                        }
                        _uiState.update { currentState ->
                            if (currentState.detectedFrameRateSource == FrameRateSource.PROBE &&
                                currentState.detectedFrameRate > 0f
                            ) {
                                currentState
                            } else {
                                currentState.copy(
                                    detectedFrameRateRaw = raw,
                                    detectedFrameRate = snapped,
                                    detectedFrameRateSource = FrameRateSource.TRACK
                                )
                            }
                        }
                    }
                    // Extract video codec, resolution, and bitrate for stream info
                    currentVideoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                        ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
                    currentVideoWidth = format.width.takeIf { it > 0 }
                    currentVideoHeight = format.height.takeIf { it > 0 }
                    currentVideoBitrate = format.bitrate.takeIf { it > 0 }
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    val trackIdx = audioTracks.size
                    if (isSelected) selectedAudioIndex = trackIdx
                    if (trackGroup.isTrackSupported(i)) {
                        supportedAudioIndices.add(trackIdx)
                    }

                    val codecName = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                    val channelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(
                        format.channelCount
                    )
                    val langDisplay = format.language?.takeIf { it != "und" }?.let {
                        com.nuvio.tv.ui.util.languageCodeToName(it)
                    }
                    val baseName = format.label ?: langDisplay ?: context.getString(com.nuvio.tv.R.string.player_track_audio_fallback, audioTracks.size + 1)
                    val suffix = listOfNotNull(codecName, channelLayout).joinToString(" ")
                    val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

                    audioTracks.add(
                        TrackInfo(
                            index = audioTracks.size,
                            name = displayName,
                            language = format.language,
                            trackId = format.id,
                            codec = codecName,
                            channelCount = format.channelCount.takeIf { it > 0 },
                            isSelected = isSelected,
                            sampleRate = format.sampleRate.takeIf { it > 0 }
                        )
                    )
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    // Skip addon subtitle tracks — they are managed separately
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedSubtitleIndex = subtitleTracks.size

                    val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val nameHintForced = isTrackHintingForced(format.label, format.language, format.id)
                    val isSongsAndSigns = listOfNotNull(format.label, format.language, format.id).any {
                        it.contains("songs", ignoreCase = true) && it.contains("sign", ignoreCase = true)
                    }

                    subtitleTracks.add(
                        TrackInfo(
                            index = subtitleTracks.size,
                            name = format.label ?: format.language ?: context.getString(com.nuvio.tv.R.string.player_track_subtitle_fallback, subtitleTracks.size + 1),
                            language = format.language,
                            trackId = format.id,
                            codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType),
                            isForced = hasForcedFlag || nameHintForced || isSongsAndSigns,
                            isSelected = isSelected
                        )
                    )
                }
            }
        }
    }

    // ── Auto-seleção de áudio baseada em idioma da série ou PicBR (apenas UMA vez no início do vídeo) ──
    val primaryPreferredAudioTarget = seriesPreferredAudioLanguageSetting?.takeIf { it.isNotBlank() }
        ?: mpvPreferredAudioLanguages.firstOrNull()?.takeIf { it.isNotBlank() }
    val isPicBr = currentAddonName?.contains("PicBR", ignoreCase = true) == true ||
            _uiState.value.currentStreamName?.contains("PicBR", ignoreCase = true) == true

    if (!currentForceDubActive) {
        if (audioTracks.isNotEmpty() && lastPicBrAutoSelectedStreamUrl != currentStreamUrl) {
            val targetIndex = if (!primaryPreferredAudioTarget.isNullOrBlank()) {
                audioTracks.indices.firstOrNull { idx ->
                    (supportedAudioIndices.isEmpty() || supportedAudioIndices.contains(idx)) &&
                        audioTrackMatchesLanguage(audioTracks[idx], primaryPreferredAudioTarget)
                } ?: -1
            } else if (isPicBr) {
                audioTracks.indices.firstOrNull { idx ->
                    val track = audioTracks[idx]
                    val lang = track.language?.lowercase().orEmpty()
                    val name = track.name.lowercase()
                    (supportedAudioIndices.isEmpty() || supportedAudioIndices.contains(idx)) &&
                        (lang.startsWith("por") || lang.startsWith("pt") || name.contains("portugu"))
                } ?: -1
            } else -1

            if (targetIndex >= 0) {
                lastPicBrAutoSelectedStreamUrl = currentStreamUrl
                if (selectedAudioIndex != targetIndex) {
                    selectAudioTrack(targetIndex, audioTracks[targetIndex], seekAfterSelect = false)
                    selectedAudioIndex = targetIndex
                }
            } else {
                // Fallback: respeita a faixa suportada já selecionada pelo DefaultTrackSelector do ExoPlayer.
                // Só força seleção manual se o ExoPlayer não tiver selecionado nenhuma faixa!
                lastPicBrAutoSelectedStreamUrl = currentStreamUrl
                if (selectedAudioIndex < 0 || audioTracks.none { it.isSelected }) {
                    val fallbackIdx = supportedAudioIndices.firstOrNull() ?: 0
                    selectAudioTrack(fallbackIdx, audioTracks[fallbackIdx], seekAfterSelect = false)
                    selectedAudioIndex = fallbackIdx
                }
            }
        } else if (audioTracks.isNotEmpty() && (selectedAudioIndex < 0 || audioTracks.none { it.isSelected })) {
            val fallbackIdx = supportedAudioIndices.firstOrNull() ?: 0
            selectAudioTrack(fallbackIdx, audioTracks[fallbackIdx], seekAfterSelect = false)
            selectedAudioIndex = fallbackIdx
        }
    } else if (audioTracks.isNotEmpty()) {
        // Durante a dublagem forçada, a faixa de áudio dublada do MergingMediaSource é a última do array
        val dubIdx = audioTracks.size - 1
        selectedAudioIndex = dubIdx
        for (i in audioTracks.indices) {
            val track = audioTracks[i]
            audioTracks[i] = track.copy(isSelected = (i == dubIdx))
        }
    }

    currentStreamHasVideoTrack = hasVideoTrack
    val effectiveVideoFormat = selectedVideoFormat ?: firstVideoFormat
    val isHdrVideo = effectiveVideoFormat?.isHdrVideoFormat() == true
    if (effectiveVideoFormat != null) {
        currentVideoTrackMimeType = effectiveVideoFormat.sampleMimeType
        currentVideoTrackCodecs = effectiveVideoFormat.codecs
        currentVideoTrackWidth = effectiveVideoFormat.width.coerceAtLeast(0)
        currentVideoTrackHeight = effectiveVideoFormat.height.coerceAtLeast(0)
        currentVideoTrackBitrate = effectiveVideoFormat.bitrate
        currentVideoTrackColorTransfer = effectiveVideoFormat.colorInfo?.colorTransfer
        currentVideoTrackSelected = selectedVideoFormat != null
        currentVideoTrackBestSupport = if (selectedVideoFormat != null) {
            selectedVideoTrackSupport
        } else {
            bestVideoTrackSupport
        }
        currentVideoTrackIsLikelyVc1 = Vc1VideoFormatHeuristics.isLikelyVc1(
            sampleMimeType = effectiveVideoFormat.sampleMimeType,
            codecs = effectiveVideoFormat.codecs,
            label = effectiveVideoFormat.label
        )
        playbackAnalyticsDiagnostics.onVideoTrackSnapshot(
            format = effectiveVideoFormat,
            support = Util.getFormatSupportString(currentVideoTrackBestSupport),
            selected = currentVideoTrackSelected
        )
        val videoTrackSignature = buildString {
            append(currentVideoTrackMimeType ?: "unknown")
            append('|')
            append(currentVideoTrackCodecs ?: "unknown")
            append('|')
            append(currentVideoTrackWidth)
            append('x')
            append(currentVideoTrackHeight)
            append("|vc1=")
            append(currentVideoTrackIsLikelyVc1)
            append("|selected=")
            append(currentVideoTrackSelected)
            append("|support=")
            append(Util.getFormatSupportString(currentVideoTrackBestSupport))
            append("|vc1TrackBypass=")
            append(isVc1TrackSelectionBypassActiveForCurrentPlayback)
        }
        if (videoTrackSignature != lastLoggedVideoTrackSignature) {
            lastLoggedVideoTrackSignature = videoTrackSignature
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: mime=${currentVideoTrackMimeType ?: "unknown"} " +
                        "codecs=${currentVideoTrackCodecs ?: "unknown"} " +
                        "size=${currentVideoTrackWidth}x${currentVideoTrackHeight} " +
                        "vc1=$currentVideoTrackIsLikelyVc1 " +
                        "selected=$currentVideoTrackSelected " +
                        "support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} " +
                        "vc1TrackBypassActive=$isVc1TrackSelectionBypassActiveForCurrentPlayback"
            )
        }
        if (currentVideoTrackIsLikelyVc1 &&
            !currentVideoTrackSelected &&
            !isVc1TrackSelectionBypassActiveForCurrentPlayback
        ) {
            val currentPosition = _exoPlayer?.currentPosition ?: 0L
            vc1TrackSelectionBypassStreamUrls.add(currentStreamUrl)
            Log.w(
                PlayerRuntimeController.TAG,
                "VIDEO_TRACK: VC-1 present but unselected (audio-only); forcing video selection so the decoder can fail visibly " +
                    "support=${Util.getFormatSupportString(currentVideoTrackBestSupport)} positionMs=$currentPosition"
            )
            retryCurrentStreamWithVc1TrackSelectionBypass(currentPosition)
            return
        }
    } else {
        currentVideoTrackMimeType = null
        currentVideoTrackCodecs = null
        currentVideoTrackWidth = 0
        currentVideoTrackHeight = 0
        currentVideoTrackColorTransfer = null
        currentVideoTrackSelected = false
        currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
        currentVideoTrackIsLikelyVc1 = false
        lastLoggedVideoTrackSignature = null
    }

    hasScannedTextTracksOnce = true
    Log.d(
        PlayerRuntimeController.TAG,
        "TRACKS updated: internalSubs=${subtitleTracks.size}, selectedInternalIndex=$selectedSubtitleIndex, " +
                "selectedAddon=${_uiState.value.selectedAddonSubtitle?.lang}, " +
                "pendingAddonLang=$pendingAddonSubtitleLanguage, pendingAddonTrackId=$pendingAddonSubtitleTrackId"
    )

    val pendingAddonTrackId = pendingAddonSubtitleTrackId
    if (!pendingAddonTrackId.isNullOrBlank()) {
        if (applyAddonSubtitleOverride(pendingAddonTrackId)) {
            Log.d(PlayerRuntimeController.TAG, "Selecting pending addon subtitle track id=$pendingAddonTrackId")
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
        }
    }

    val pendingLang = pendingAddonSubtitleLanguage
    if (
        pendingAddonSubtitleTrackId.isNullOrBlank() &&
        pendingLang != null &&
        subtitleTracks.isNotEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        val preferredIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = subtitleTracks,
            targets = listOf(pendingLang)
        )
        if (preferredIndex >= 0) {
            selectSubtitleTrack(preferredIndex)
            selectedSubtitleIndex = preferredIndex
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "Skipping pending subtitle track switch: no text track matches language=$pendingLang"
            )
        }
        pendingAddonSubtitleLanguage = null
    }

    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)?.let { restoredIndex ->
        selectedAudioIndex = restoredIndex
    }

    _uiState.update { state ->
        val finalSubtitleIndex = when {
            state.selectedAddonSubtitle != null -> -1
            selectedSubtitleIndex >= 0 -> selectedSubtitleIndex
            else -> state.selectedSubtitleTrackIndex
        }
        state.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = finalSubtitleIndex,
            isHdrVideo = isHdrVideo
        )
    }
    updateAudioControlAvailability(audioTracks, selectedAudioIndex)
    logSwitchTrace(
        stage = "exo-tracks-update-end",
        message = "audioCount=${audioTracks.size} subtitleCount=${subtitleTracks.size} " +
                "selectedAudioIndex=$selectedAudioIndex selectedSubtitleIndex=$selectedSubtitleIndex"
    )
    rememberEffectiveExoSubtitleSelectionForEngineSwitch(
        subtitleTracks = subtitleTracks,
        selectedSubtitleIndex = selectedSubtitleIndex
    )
    applyPersistedTrackPreference(
        audioTracks = if (currentForceDubActive) emptyList() else audioTracks,
        subtitleTracks = subtitleTracks
    )
    if (currentStreamHasVideoTrack) {
        maybeScheduleFirstFrameWatchdog()
    } else {
        cancelFirstFrameWatchdog()
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    maybeAdjustLibassPipelineForTracks(tracks)
}

private fun formatSupportRank(@C.FormatSupport formatSupport: Int): Int {
    return when (formatSupport) {
        C.FORMAT_HANDLED -> 4
        C.FORMAT_EXCEEDS_CAPABILITIES -> 3
        C.FORMAT_UNSUPPORTED_DRM -> 2
        C.FORMAT_UNSUPPORTED_SUBTYPE -> 1
        else -> 0
    }
}

private fun PlayerRuntimeController.rememberEffectiveExoSubtitleSelectionForEngineSwitch(
    subtitleTracks: List<TrackInfo>,
    selectedSubtitleIndex: Int
) {
    if (isUsingMpvEngine()) return

    val selection = when {
        selectedSubtitleIndex >= 0 -> {
            val selectedTrack = subtitleTracks.getOrNull(selectedSubtitleIndex) ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = _uiState.value,
                    language = selectedTrack.language,
                    name = selectedTrack.name,
                    trackId = selectedTrack.trackId,
                    isForced = selectedTrack.isForced,
                    selectedUiTrackOverride = selectedTrack
                )
            )
        }
        _uiState.value.selectedAddonSubtitle != null -> {
            val addon = _uiState.value.selectedAddonSubtitle ?: return
            PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                id = addon.id,
                url = addon.url,
                language = addon.lang,
                addonName = addon.addonName
            )
        }
        else -> null
    }

    if (selection != null) {
        logSwitchTrace(
            stage = "remember-effective-exo-subtitle",
            message = "selection=${describeRememberedSubtitleForSwitchTrace(selection)} selectedSubtitleIndex=$selectedSubtitleIndex"
        )
        effectiveSubtitleSelectionForEngineSwitch =
            PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
                streamUrl = currentStreamUrl,
                selection = selection
            )
    } else {
        logSwitchTrace(
            stage = "remember-effective-exo-subtitle",
            message = "selection=none selectedSubtitleIndex=$selectedSubtitleIndex addonSelected=${_uiState.value.selectedAddonSubtitle != null}"
        )
    }
}

internal fun PlayerRuntimeController.maybeAdjustLibassPipelineForTracks(tracks: Tracks) {
    if (libassPipelineSwitchInFlight) return
    if (currentForceDubActive || _uiState.value.isForceDubLoading || isRestoringOriginalAfterDub) return

    val hasAssSsaTrack = tracks.hasAssSsaTextTrack()
    if (hasAssSsaTrack) {
        hasDetectedAssSsaTrackForCurrentStream = true
    }
    // Don't rebuild just to drop libass, except when it's holding the MKV extractor on a DV
    // file and blocking conversion; then drop it so the DV extractor runs.
    val desiredUseLibass = requestedUseLibassByUser && hasDetectedAssSsaTrackForCurrentStream
    if (desiredUseLibass == activePlayerUsesLibass) return
    if (!desiredUseLibass) {
        val libassBlockingDvConvert = activePlayerUsesLibass &&
                isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                tracks.hasDolbyVisionConvertibleVideoTrack()
        if (!libassBlockingDvConvert) return
    }

    val player = _exoPlayer ?: return
    val resumePosition = player.currentPosition.takeIf { it > 0L }
    libassPipelineOverrideForCurrentStream = desiredUseLibass
    libassPipelineSwitchInFlight = true

    _uiState.update { state ->
        state.copy(
            pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
            showLoadingOverlay = state.loadingOverlayEnabled
        )
    }

    scope.launch {
        releasePlayer(flushPlaybackState = true, keepTorrentStream = true)
        initializePlayer(currentStreamUrl, currentHeaders)
    }
}

private fun Tracks.hasAssSsaTextTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (index in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(index)
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) return true

            val hasAssCodec = format.codecs
                ?.split(',')
                ?.asSequence()
                ?.map { it.trim().lowercase(Locale.US) }
                ?.any { codec ->
                    codec == MimeTypes.TEXT_SSA ||
                            codec == "s_text/ass" ||
                            codec == "s_text/ssa" ||
                            codec.endsWith("/x-ssa")
                } == true
            if (hasAssCodec) return true
        }
    }
    return false
}

// Profile 7 only: it's the profile AUTO conversion rewrites. DV5/DV8 don't convert, so they
// shouldn't trigger a libass-drop reload.
private val DV_PROFILE_7_CODEC = Regex("^(dvhe|dvh1|dvav|dva1)\\.0?7\\.")

private val DV_CODEC_PREFIX = Regex("^(dvhe|dvh1|dvav|dva1|dav1)\\.")

/** PQ (HDR10/HDR10+), HLG or Dolby Vision video, whose bright output calls for the HDR subtitle style. */
internal fun Format.isHdrVideoFormat(): Boolean {
    val transfer = colorInfo?.colorTransfer?.takeIf { it != Format.NO_VALUE } ?: hevcSpsColorTransfer()
    if (transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG) return true
    if (sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return true
    return DV_CODEC_PREFIX.containsMatchIn(codecs?.lowercase(Locale.US).orEmpty())
}

private const val H265_NAL_UNIT_TYPE_SPS = 33

/**
 * Transfer characteristics from the HEVC SPS in the initialization data. Matroska files without a
 * Colour element only carry their HDR signalling there, and Dolby Vision streams played on their
 * HDR10 base layer arrive as plain HEVC, so the container gives no colour info for either.
 */
internal fun Format.hevcSpsColorTransfer(): Int? {
    if (sampleMimeType != MimeTypes.VIDEO_H265) return null
    for (data in initializationData) {
        val prefixFlags = BooleanArray(3)
        var start = NalUnitUtil.findNalUnit(data, 0, data.size, prefixFlags)
        while (start < data.size) {
            val next = NalUnitUtil.findNalUnit(data, start + 3, data.size, prefixFlags)
            if (NalUnitUtil.getH265NalUnitType(data, start) == H265_NAL_UNIT_TYPE_SPS) {
                return runCatching {
                    NalUnitUtil.parseH265SpsNalUnit(data, start + 3, next, null).colorTransfer
                }.getOrNull()?.takeIf { it != Format.NO_VALUE }
            }
            start = next
        }
    }
    return null
}

private fun Tracks.hasDolbyVisionProfile7VideoTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (index in 0 until trackGroup.length) {
            val codec = trackGroup.getTrackFormat(index).codecs?.lowercase(Locale.US).orEmpty()
            if (DV_PROFILE_7_CODEC.containsMatchIn(codec)) return true
        }
    }
    return false
}

// Profiles that the DolbyVisionExtractorsFactory actually converts (7 always, 5 when enabled).
private val DV_CONVERTIBLE_CODEC = Regex("^(dvhe|dvh1|dvav|dva1)\\.0?[57]\\.")

private fun Tracks.hasDolbyVisionConvertibleVideoTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (index in 0 until trackGroup.length) {
            val codec = trackGroup.getTrackFormat(index).codecs?.lowercase(Locale.US).orEmpty()
            if (DV_CONVERTIBLE_CODEC.containsMatchIn(codec)) return true
        }
    }
    return false
}

internal fun PlayerRuntimeController.normalizeTrackMatchValue(value: String?): String? = value
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

internal fun PlayerRuntimeController.maybeRestorePendingAudioSelectionAfterSubtitleRefresh(
    audioTracks: List<TrackInfo>
): Int? {
    val pending = pendingAudioSelectionAfterSubtitleRefresh ?: return null
    if (pending.streamUrl != currentStreamUrl) {
        logSwitchTrace(
            stage = "restore-audio-after-subtitle-refresh",
            message = "action=clear reason=stream-mismatch pendingStream=${pending.streamUrl} currentStream=$currentStreamUrl"
        )
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }
    if (audioTracks.isEmpty()) return null

    val targetLang = normalizeTrackMatchValue(pending.language)
    val targetName = normalizeTrackMatchValue(pending.name)

    fun languageMatches(trackLanguage: String?): Boolean {
        val trackLang = normalizeTrackMatchValue(trackLanguage)
        return !targetLang.isNullOrBlank() &&
                !trackLang.isNullOrBlank() &&
                (trackLang == targetLang ||
                        trackLang.startsWith("$targetLang-") ||
                        trackLang.startsWith("${targetLang}_"))
    }

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName
        }
    } else {
        -1
    }

    val nameContainsIndex = if (exactNameIndex < 0 && !targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true
        }
    } else {
        -1
    }

    val languageIndex = if (exactNameIndex < 0 && nameContainsIndex < 0) {
        audioTracks.indexOfFirst { track -> languageMatches(track.language) }
    } else {
        -1
    }

    val index = when {
        exactNameIndex >= 0 -> exactNameIndex
        nameContainsIndex >= 0 -> nameContainsIndex
        else -> languageIndex
    }

    pendingAudioSelectionAfterSubtitleRefresh = null
    if (index < 0) {
        logSwitchTrace(
            stage = "restore-audio-after-subtitle-refresh",
            message = "result=no-match lang=$targetLang name=$targetName candidates=${describeTrackCandidatesForRestoreLog(audioTracks)}"
        )
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio restore skipped after subtitle refresh: no match for lang=$targetLang name=$targetName"
        )
        return null
    }

    val restoredTrack = audioTracks[index]
    logSwitchTrace(
        stage = "restore-audio-after-subtitle-refresh",
        message = "result=match index=$index lang=${restoredTrack.language} name=${restoredTrack.name}"
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Restoring audio after subtitle refresh index=$index lang=${restoredTrack.language} name=${restoredTrack.name}"
    )
    selectAudioTrack(index)
    return index
}

internal fun PlayerRuntimeController.findMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val strictIndex = findStrictMatchingTrackIndex(
        tracks = tracks,
        target = target
    )
    if (strictIndex >= 0) {
        logSwitchTrace(
            stage = "track-match-regular",
            message = "result=strict index=$strictIndex target=${describeRememberedTrackForSwitchTrace(target)}"
        )
        return strictIndex
    }

    val fallbackIndex = findLanguageFallbackTrackIndex(
        tracks = tracks,
        target = target
    )
    logSwitchTrace(
        stage = "track-match-regular",
        message = "result=language-fallback index=$fallbackIndex target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return fallbackIndex
}

internal fun PlayerRuntimeController.findMatchingTrackIndexForEngineSwitchToMpv(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection,
    sourceEngine: InternalPlayerEngine
): Int {
    val strictIndex = findStrictMatchingTrackIndex(
        tracks = tracks,
        target = target
    )
    if (strictIndex >= 0) {
        logSwitchTrace(
            stage = "track-match-switch",
            message = "result=strict index=$strictIndex sourceEngine=$sourceEngine target=${describeRememberedTrackForSwitchTrace(target)}"
        )
        return strictIndex
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER && isUsingMpvEngine()) {
        val hintedIndex = findEngineSwitchHintTrackIndex(
            tracks = tracks,
            target = target
        )
        if (hintedIndex >= 0) {
            logSwitchTrace(
                stage = "track-match-switch",
                message = "result=hint index=$hintedIndex sourceEngine=$sourceEngine target=${describeRememberedTrackForSwitchTrace(target)}"
            )
            return hintedIndex
        }
    }

    val fallbackIndex = findLanguageFallbackTrackIndex(
        tracks = tracks,
        target = target
    )
    logSwitchTrace(
        stage = "track-match-switch",
        message = "result=language-fallback index=$fallbackIndex sourceEngine=$sourceEngine " +
                "target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return fallbackIndex
}

private fun PlayerRuntimeController.describeTrackInfoForRestoreLog(track: TrackInfo): String {
    return "index=${track.index} lang=${track.language} name=${track.name} id=${track.trackId} " +
            "forced=${track.isForced} selected=${track.isSelected}"
}

private fun PlayerRuntimeController.describeTrackCandidatesForRestoreLog(
    tracks: List<TrackInfo>
): String {
    return tracks.joinToString(prefix = "[", postfix = "]") { track ->
        "{${describeTrackInfoForRestoreLog(track)}}"
    }
}

private fun PlayerRuntimeController.describeRememberedTrackForSwitchTrace(
    selection: PlayerRuntimeController.RememberedTrackSelection?
): String {
    if (selection == null) return "none"
    return "lang=${selection.language} name=${selection.name} trackId=${selection.trackId} " +
            "indexHint=${selection.indexHint} languageIndexHint=${selection.languageIndexHint} " +
            "forcedHint=${selection.isForcedHint}"
}

private fun PlayerRuntimeController.describeRememberedSubtitleForSwitchTrace(
    selection: PlayerRuntimeController.RememberedSubtitleSelection?
): String {
    return when (selection) {
        null -> "none"
        PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "disabled"
        is PlayerRuntimeController.RememberedSubtitleSelection.Internal ->
            "internal:${describeRememberedTrackForSwitchTrace(selection.track)}"
        is PlayerRuntimeController.RememberedSubtitleSelection.Addon ->
            "addon:${selection.language}/${selection.addonName}/${selection.id}"
    }
}

private fun PlayerRuntimeController.findStrictMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetTrackId = normalizeTrackMatchValue(target.trackId)
    val targetName = normalizeTrackMatchValue(target.name)
    val targetLang = normalizeTrackMatchValue(target.language)

    val exactTrackIdIndex = if (!targetTrackId.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.trackId) == targetTrackId &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang) &&
                    (targetName.isNullOrBlank() || normalizeTrackMatchValue(track.name) == targetName ||
                            normalizeTrackMatchValue(track.name)?.contains(targetName) == true)
        }
    } else {
        -1
    }
    if (exactTrackIdIndex >= 0) return exactTrackIdIndex

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (exactNameIndex >= 0) return exactNameIndex

    val nameContainsIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true &&
                    (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (nameContainsIndex >= 0) return nameContainsIndex

    return -1
}

private fun PlayerRuntimeController.findLanguageFallbackTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetLang = target.language
    if (targetLang.isNullOrBlank() && target.name.isNullOrBlank()) return -1

    val isTargetPt = isPortugueseTarget(targetLang) || listOfNotNull(target.name, target.trackId).any { text ->
        val lower = text.lowercase(Locale.ROOT)
        lower.contains("portugu") || lower.contains("dublado") || lower.contains("brazilian")
    }

    // 1. Candidatos pelo código de idioma (incluindo família de idiomas e aliases ISO: por/pt/pt-br, eng/en, spa/es, etc.)
    val langCandidates = tracks.indices.filter { index ->
        val track = tracks[index]
        val trackLang = track.language
        when {
            // Caso especial Português
            isTargetPt && isAudioPortuguese(track) -> true
            // Match direto de código de idioma ou família (en/eng, es/spa, etc.)
            !trackLang.isNullOrBlank() && !targetLang.isNullOrBlank() &&
                (PlayerSubtitleUtils.matchesLanguageCode(trackLang, targetLang) ||
                 isSameLanguageFamily(trackLang, targetLang) ||
                 trackLang.equals(targetLang, ignoreCase = true) ||
                 trackLang.startsWith("${targetLang}-", ignoreCase = true) ||
                 trackLang.startsWith("${targetLang}_", ignoreCase = true)) -> true
            // Match pelo nome da faixa (ex: "English", "Portuguese")
            !target.name.isNullOrBlank() && !track.name.isNullOrBlank() &&
                track.name.contains(target.name, ignoreCase = true) -> true
            else -> false
        }
    }

    // Se for legenda e forçada, filtrar forçadas
    val filteredCandidates = if (target.isForcedHint == true) {
        langCandidates.filter { index -> tracks[index].isForced }
    } else {
        langCandidates
    }

    val result = if (filteredCandidates.size <= 1) {
        filteredCandidates.firstOrNull() ?: -1
    } else {
        val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = target.language,
            name = target.name,
            trackId = target.trackId
        )
        filteredCandidates.firstOrNull { index ->
            val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                language = tracks[index].language,
                name = tracks[index].name,
                trackId = tracks[index].trackId
            )
            trackVariant == targetVariant
        } ?: filteredCandidates.first()
    }

    logSwitchTrace(
        stage = "track-match-language-fallback",
        message = "targetLang=$targetLang result=$result target=${describeRememberedTrackForSwitchTrace(target)}"
    )
    return result
}

private fun PlayerRuntimeController.findEngineSwitchHintTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val indexHint = target.indexHint?.takeIf { it >= 0 } ?: -1
    val languageIndexHint = target.languageIndexHint?.takeIf { it >= 0 }
    val targetForced = target.isForcedHint
    val sparseMetadata = hasSparseMpvSubtitleMetadataForEngineSwitch(tracks)
    val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = target.language,
        name = target.name,
        trackId = target.trackId
    )

    val baseCandidates = tracks.indices.filter { index ->
        val track = tracks[index]
        target.language.isNullOrBlank() ||
                PlayerSubtitleUtils.matchesLanguageCode(track.language, target.language) ||
                PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                ) == targetVariant
    }
    val sparseCandidates = if (sparseMetadata) {
        if (targetForced == null) {
            tracks.indices.toList()
        } else {
            tracks.indices.filter { index -> tracks[index].isForced == targetForced }
                .ifEmpty { tracks.indices.toList() }
        }
    } else {
        emptyList()
    }

    if (baseCandidates.isEmpty()) {
        if (indexHint in sparseCandidates) {
            logSwitchTrace(
                stage = "track-match-hint",
                message = "result=indexHint-from-sparse index=$indexHint " +
                        "indexHint=$indexHint languageIndexHint=$languageIndexHint targetForced=$targetForced"
            )
            return indexHint
        }
        if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
            val resolved = sparseCandidates[languageIndexHint]
            logSwitchTrace(
                stage = "track-match-hint",
                message = "result=languageIndexHint-from-sparse index=$resolved " +
                        "indexHint=$indexHint languageIndexHint=$languageIndexHint targetForced=$targetForced"
            )
            return resolved
        }
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=-1 reason=empty-base-and-no-sparse-match indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced sparseMetadata=$sparseMetadata"
        )
        return -1
    }

    val preferredCandidates = if (targetForced == null) {
        baseCandidates
    } else {
        baseCandidates.filter { index -> tracks[index].isForced == targetForced }
            .ifEmpty { baseCandidates }
    }

    if (indexHint in preferredCandidates) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-preferred index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in preferredCandidates.indices) {
        val resolved = preferredCandidates[languageIndexHint]
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=languageIndexHint-preferred index=$resolved indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates sparseMetadata=$sparseMetadata"
        )
        return resolved
    }
    if (indexHint in baseCandidates) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-base index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (indexHint in sparseCandidates) {
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=indexHint-sparse index=$indexHint indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates sparseMetadata=$sparseMetadata"
        )
        return indexHint
    }
    if (languageIndexHint != null && languageIndexHint in sparseCandidates.indices) {
        val resolved = sparseCandidates[languageIndexHint]
        logSwitchTrace(
            stage = "track-match-hint",
            message = "result=languageIndexHint-sparse index=$resolved indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                    "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates sparseMetadata=$sparseMetadata"
        )
        return resolved
    }

    logSwitchTrace(
        stage = "track-match-hint",
        message = "result=-1 reason=no-hint-match indexHint=$indexHint languageIndexHint=$languageIndexHint " +
                "targetForced=$targetForced baseCandidates=$baseCandidates preferredCandidates=$preferredCandidates " +
                "sparseCandidates=$sparseCandidates sparseMetadata=$sparseMetadata"
    )
    return -1
}

private fun PlayerRuntimeController.hasSparseMpvSubtitleMetadataForEngineSwitch(
    tracks: List<TrackInfo>
): Boolean {
    if (tracks.isEmpty()) return false
    val sparseCount = tracks.count { track ->
        val normalizedName = normalizeTrackMatchValue(track.name)
        track.language.isNullOrBlank() &&
                (
                        normalizedName.isNullOrBlank() ||
                                normalizedName == "subtitle" ||
                                normalizedName.startsWith("subtitle ")
                        )
    }
    return sparseCount > 0 && sparseCount * 2 >= tracks.size
}

internal fun PlayerRuntimeController.applyPersistedTrackPreference(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>
) {
    val switchPending = pendingEngineSwitchTrackPreference
        ?.takeIf { it.streamUrl == currentStreamUrl }
    if (pendingEngineSwitchTrackPreference != null && switchPending == null) {
        logSwitchTrace(
            stage = "restore-switch-pref-clear",
            message = "reason=stream-mismatch pendingStream=${pendingEngineSwitchTrackPreference?.streamUrl} currentStream=$currentStreamUrl"
        )
        pendingEngineSwitchTrackPreference = null
    }
    val usingSwitchPending = switchPending != null
    val pendingCandidate = switchPending?.preference ?: persistedTrackPreference
    logSwitchTrace(
        stage = "restore-enter",
        message = "usingSwitchPending=$usingSwitchPending switchPending=${switchPending != null} persisted=${persistedTrackPreference != null} " +
                "audioTracks=${audioTracks.size} subtitleTracks=${subtitleTracks.size} " +
                "uiAudioIndex=${_uiState.value.selectedAudioTrackIndex} uiSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex} " +
                "pendingAudio=${describeRememberedTrackForSwitchTrace(pendingCandidate?.audio)} " +
                "pendingSubtitle=${describeRememberedSubtitleForSwitchTrace(pendingCandidate?.subtitle)}"
    )
    val pending: PlayerRuntimeController.TrackPreference = pendingCandidate ?: run {
        logSwitchTrace(
            stage = "restore-skip",
            message = "reason=no-pending-preference"
        )
        return
    }
    val switchSourceEngine = switchPending?.sourceEngine
    var updatedPending = pending
    var updatedSubtitleIndex: Int? = null
    var updatedAddonSubtitle: com.nuvio.tv.domain.model.Subtitle? = null

    pending.audio?.let { audioSelection ->
        if (audioTracks.isEmpty()) {
            logSwitchTrace(
                stage = "restore-audio",
                message = "result=defer reason=no-audio-tracks"
            )
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio deferred (no tracks yet)")
        } else {
            val index = findMatchingTrackIndex(audioTracks, audioSelection)
            if (index >= 0) {
                val alreadySelected = audioTracks.getOrNull(index)?.isSelected == true
                logSwitchTrace(
                    stage = "restore-audio",
                    message = "result=match index=$index alreadySelected=$alreadySelected " +
                            "target=${describeRememberedTrackForSwitchTrace(audioSelection)} " +
                            "matched=${audioTracks.getOrNull(index)?.let { describeTrackInfoForRestoreLog(it) }}"
                )
                if (!alreadySelected) {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index lang=${audioTracks[index].language} name=${audioTracks[index].name}")
                    selectAudioTrack(index)
                    _uiState.update { it.copy(selectedAudioTrackIndex = index) }
                } else {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index already selected, clearing")
                    updatedPending = updatedPending.copy(audio = null)
                }
            } else {
                logSwitchTrace(
                    stage = "restore-audio",
                    message = "result=no-match target=${describeRememberedTrackForSwitchTrace(audioSelection)} " +
                            "candidates=${describeTrackCandidatesForRestoreLog(audioTracks)}"
                )
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio no match for lang=${audioSelection.language} name=${audioSelection.name}, clearing")
                updatedPending = updatedPending.copy(audio = null)
            }
        }
    }

    when (val subtitleSelection = pending.subtitle) {
        null -> Unit
        PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> {
            val alreadyDisabled = subtitleTracks.none { it.isSelected }
            logSwitchTrace(
                stage = "restore-subtitle-disabled",
                message = "alreadyDisabled=$alreadyDisabled subtitleTrackCount=${subtitleTracks.size}"
            )
            if (!alreadyDisabled) {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle disabled (re-applying)")
                autoSubtitleSelected = true
                isUserExplicitSubtitleSelection = true
                subtitleDisabledByPersistedPreference = true
                disableSubtitles()
                updatedSubtitleIndex = -1
            } else {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle already disabled, clearing")
                autoSubtitleSelected = true
                isUserExplicitSubtitleSelection = true
                subtitleDisabledByPersistedPreference = true
                updatedSubtitleIndex = -1
                updatedPending = updatedPending.copy(subtitle = null)
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> {
            if (subtitleTracks.isEmpty()) {
                logSwitchTrace(
                    stage = "restore-subtitle-internal",
                    message = "result=defer reason=no-subtitle-tracks target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)}"
                )
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle deferred (no tracks yet)")
            } else {
                val index = if (usingSwitchPending && switchSourceEngine != null) {
                    findMatchingTrackIndexForEngineSwitchToMpv(
                        tracks = subtitleTracks,
                        target = subtitleSelection.track,
                        sourceEngine = switchSourceEngine
                    )
                } else {
                    findMatchingTrackIndex(subtitleTracks, subtitleSelection.track)
                }
                logSwitchTrace(
                    stage = "restore-subtitle-internal",
                    message = "mode=${if (usingSwitchPending && switchSourceEngine != null) "switch-hint-aware" else "regular"} " +
                            "sourceEngine=$switchSourceEngine resultIndex=$index target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)}"
                )
                if (index >= 0) {
                    val alreadySelected = subtitleTracks.getOrNull(index)?.isSelected == true
                    logSwitchTrace(
                        stage = "restore-subtitle-internal-match",
                        message = "index=$index alreadySelected=$alreadySelected " +
                                "matched=${subtitleTracks.getOrNull(index)?.let { describeTrackInfoForRestoreLog(it) }}"
                    )
                    if (!alreadySelected) {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index (re-applying)")
                        autoSubtitleSelected = true
                        isUserExplicitSubtitleSelection = true
                        selectSubtitleTrack(index)
                        updatedSubtitleIndex = index
                        updatedPending = updatedPending.copy(subtitle = null)
                    } else {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index already selected, keeping for pipeline restart")
                        autoSubtitleSelected = true
                        isUserExplicitSubtitleSelection = true
                        updatedSubtitleIndex = index
                    }
                } else {
                    val shouldDeferSwitchRestore = usingSwitchPending &&
                            switchSourceEngine == InternalPlayerEngine.EXOPLAYER &&
                            isUsingMpvEngine() &&
                            hasSparseMpvSubtitleMetadataForEngineSwitch(subtitleTracks)
                    logSwitchTrace(
                        stage = "restore-subtitle-internal-no-match",
                        message = "shouldDeferSwitchRestore=$shouldDeferSwitchRestore " +
                                "target=${describeRememberedTrackForSwitchTrace(subtitleSelection.track)} " +
                                "candidates=${describeTrackCandidatesForRestoreLog(subtitleTracks)}"
                    )
                    val resolvedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                        language = subtitleSelection.track.language,
                        name = subtitleSelection.track.name,
                        trackId = subtitleSelection.track.trackId
                    )
                    if (shouldDeferSwitchRestore) {
                        logSwitchTrace(
                            stage = "restore-subtitle-internal-no-match",
                            message = "action=defer reason=sparse-mpv-metadata"
                        )
                    } else {
                        val state = _uiState.value
                        val addonFallback = state.addonSubtitles.firstOrNull { subtitle ->
                            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, resolvedVariant)
                        }
                        if (addonFallback != null) {
                            logSwitchTrace(
                                stage = "restore-subtitle-internal-fallback-addon",
                                message = "addonId=${addonFallback.id} addonLang=${addonFallback.lang} variant=$resolvedVariant"
                            )
                            Log.d(
                                PlayerRuntimeController.TAG,
                                "TRACK_PREF restore: internal no match, falling back to addon lang=${addonFallback.lang} variant=$resolvedVariant"
                            )
                            autoSubtitleSelected = true
                            isUserExplicitSubtitleSelection = true
                            subtitleAddonRestoredByPersistedPreference = true
                            pendingRestoredAddonSubtitle = addonFallback
                            selectAddonSubtitle(addonFallback)
                            maybeRunAutomaticSubtitleSync(addonFallback) // AutoSync hook
                            updatedAddonSubtitle = addonFallback
                            updatedPending = updatedPending.copy(subtitle = null)
                        } else {
                            logSwitchTrace(
                                stage = "restore-subtitle-internal-no-match",
                                message = "action=clear reason=no-addon-fallback variant=$resolvedVariant"
                            )
                            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle no match, no addon fallback for variant=$resolvedVariant, clearing")
                            updatedPending = updatedPending.copy(subtitle = null)
                        }
                    }
                }
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> {
            val state = _uiState.value
            // Only restore addon subtitle on exact match (same addon + same track ID).
            // Looser matches (same addon + same language, or any addon + same language)
            // should NOT override the normal auto-selection logic which prefers embedded
            // tracks over addon subtitles.
            val addonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                subtitle.addonName == subtitleSelection.addonName && subtitle.id == subtitleSelection.id
            }
            if (addonMatch != null) {
                logSwitchTrace(
                    stage = "restore-subtitle-addon",
                    message = "result=match addonId=${addonMatch.id} addonLang=${addonMatch.lang} addon=${addonMatch.addonName}"
                )
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Restoring same-series addon subtitle lang=${addonMatch.lang} id=${addonMatch.id}"
                )
                autoSubtitleSelected = true
                isUserExplicitSubtitleSelection = true
                subtitleAddonRestoredByPersistedPreference = true
                pendingRestoredAddonSubtitle = addonMatch
                selectAddonSubtitle(addonMatch)
                maybeRunAutomaticSubtitleSync(addonMatch) // AutoSync hook
                updatedAddonSubtitle = addonMatch
                val shouldKeepPendingUntilMpvConfirmsSelection =
                    usingSwitchPending && isUsingMpvEngine()
                val addonSelectedInMpv =
                    !shouldKeepPendingUntilMpvConfirmsSelection ||
                            isMpvAddonSubtitleTrackActive(addonMatch)
                if (addonSelectedInMpv) {
                    updatedPending = updatedPending.copy(subtitle = null)
                } else {
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=defer-clear reason=mpv-addon-not-active-yet " +
                                "addonId=${addonMatch.id} addonLang=${addonMatch.lang}"
                    )
                }
            } else {
                val addonSubtitlesStillLoading = state.isLoadingAddonSubtitles || state.addonSubtitles.isEmpty()
                if (addonSubtitlesStillLoading) {
                    // Addon subtitles haven't loaded yet — keep the preference
                    // so it can be restored once they arrive. Block auto-selection
                    // to prevent internal tracks from overriding the user's choice.
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=defer targetAddonId=${subtitleSelection.id} targetLang=${subtitleSelection.language} " +
                            "addonPool=${state.addonSubtitles.size} isLoadingAddonSubtitles=${state.isLoadingAddonSubtitles}"
                    )
                    autoSubtitleSelected = true
                    isUserExplicitSubtitleSelection = true
                    subtitleAddonRestoredByPersistedPreference = true
                } else {
                    logSwitchTrace(
                        stage = "restore-subtitle-addon",
                        message = "result=no-exact-match targetAddonId=${subtitleSelection.id} targetLang=${subtitleSelection.language} " +
                            "addonPool=${state.addonSubtitles.size}, falling back to auto-selection"
                    )
                    // Clear the persisted subtitle preference so tryAutoSelectPreferredSubtitleFromAvailableTracks
                    // can run its normal logic (prefer embedded over addon).
                    updatedPending = updatedPending.copy(subtitle = null)
                    // Reset auto-select flag in case it was set during the defer
                    // phase — allows tryAutoSelect to pick an embedded track.
                    autoSubtitleSelected = false
                    isUserExplicitSubtitleSelection = false
                    subtitleAddonRestoredByPersistedPreference = false
                }
            }
        }
    }

    _uiState.update { state ->
        state.copy(
            selectedSubtitleTrackIndex = updatedSubtitleIndex ?: state.selectedSubtitleTrackIndex,
            selectedAddonSubtitle = updatedAddonSubtitle ?: if (updatedSubtitleIndex != null) null else state.selectedAddonSubtitle
        )
    }
    val normalizedPending = updatedPending.takeUnless { it.audio == null && it.subtitle == null }
    if (usingSwitchPending) {
        logSwitchTrace(
            stage = "restore-exit-switch",
            message = "remainingAudio=${describeRememberedTrackForSwitchTrace(normalizedPending?.audio)} " +
                    "remainingSubtitle=${describeRememberedSubtitleForSwitchTrace(normalizedPending?.subtitle)}"
        )
        pendingEngineSwitchTrackPreference = normalizedPending?.let { preference ->
            PlayerRuntimeController.PendingEngineSwitchTrackPreference(
                streamUrl = currentStreamUrl,
                preference = preference,
                sourceEngine = switchSourceEngine ?: currentInternalPlayerEngine
            )
        }
    } else {
        logSwitchTrace(
            stage = "restore-exit-persisted",
            message = "remainingAudio=${describeRememberedTrackForSwitchTrace(normalizedPending?.audio)} " +
                    "remainingSubtitle=${describeRememberedSubtitleForSwitchTrace(normalizedPending?.subtitle)}"
        )
        persistedTrackPreference = normalizedPending
    }
}

internal fun PlayerRuntimeController.subtitleLanguageTargets(): List<String> {
    val preferred = _uiState.value.subtitleStyle.preferredLanguage.lowercase()
    if (preferred == "none") return emptyList()
    val secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage?.lowercase()
    return listOfNotNull(preferred, secondary)
}

internal fun PlayerRuntimeController.findBestInternalSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>,
    forcedOnly: Boolean = false,
    normalOnly: Boolean = false,
    selectedAudioTrack: TrackInfo? = null
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        if (forcedOnly) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(
                subtitleTracks = subtitleTracks,
                target = target,
                selectedAudioTrack = selectedAudioTrack
            )
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            val track = subtitleTracks[index]
            (!normalOnly || !track.isForced) && subtitleTrackMatchesLanguage(track, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracks(subtitleTracks, normalOnly)
                if (brazilianFromGenericPt >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal pt-br via generic-pt tags index=$brazilianFromGenericPt"
                    )
                    return brazilianFromGenericPt
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            if (normalizedTarget == "es-419") {
                val latinoFromGenericEs = findLatinoSpanishInGenericEsTracks(subtitleTracks, normalOnly)
                if (latinoFromGenericEs >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal es-419 via generic-es tags index=$latinoFromGenericEs"
                    )
                    return latinoFromGenericEs
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        val preferredCandidateIndexes = candidateIndexes.filter { index -> !subtitleTracks[index].isForced }
            .takeIf { it.isNotEmpty() }
            ?: if (normalOnly) {
                // Forced tracks are the only candidates but we explicitly want non-forced.
                // Continue to the next target or let addon fallback handle it.
                continue
            } else {
                candidateIndexes
            }
        if (preferredCandidateIndexes.size == 1) {
            // For regional targets, verify the single candidate is actually the right variant.
            // A track with language="por" matches both "pt" and "pt-br" by language code,
            // but may be the wrong accent based on its name tags.
            if (normalizedTarget == "pt" || normalizedTarget == "es") {
                val track = subtitleTracks[preferredCandidateIndexes.first()]
                val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                )
                if (variant != normalizedTarget && variant != track.language?.lowercase()) {
                    // Single candidate is a different variant (e.g. PT-BR when we want PT).
                    // Skip it so the search can continue to secondary target or addon fallback.
                    continue
                }
            }
            return preferredCandidateIndexes.first()
        }

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferredCandidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        if (normalizedTarget == "es" || normalizedTarget == "es-419") {
            val tieBroken = breakSpanishSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = preferredCandidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return preferredCandidateIndexes.first()
    }
    return -1
}

internal fun isSameLanguageFamily(lang1: String?, lang2: String?): Boolean {
    if (lang1.isNullOrBlank() || lang2.isNullOrBlank()) return false
    val n1 = PlayerSubtitleUtils.normalizeLanguageCode(lang1)
    val n2 = PlayerSubtitleUtils.normalizeLanguageCode(lang2)
    if (n1 == n2) return true

    val isPt1 = n1 == "pt" || n1 == "pt-br" || n1 == "por" || n1 == "pob"
    val isPt2 = n2 == "pt" || n2 == "pt-br" || n2 == "por" || n2 == "pob"
    if (isPt1 && isPt2) return true

    val isEs1 = n1 == "es" || n1 == "es-419" || n1 == "spa"
    val isEs2 = n2 == "es" || n2 == "es-419" || n2 == "spa"
    if (isEs1 && isEs2) return true

    val base1 = n1.substringBefore('-').substringBefore('_')
    val base2 = n2.substringBefore('-').substringBefore('_')
    return base1.isNotBlank() && base1 == base2
}

internal fun isAudioPortuguese(audioTrack: TrackInfo): Boolean {
    val lang = audioTrack.language?.lowercase(Locale.ROOT)
    if (lang != null && (lang == "pt" || lang == "por" || lang == "pt-br" || lang == "pt_br" || lang == "pob")) return true
    val haystack = listOfNotNull(audioTrack.name, audioTrack.trackId).joinToString(" ").lowercase(Locale.ROOT)
    return haystack.contains("portugu") ||
        haystack.contains("dublado") ||
        haystack.contains("nacional") ||
        haystack.contains("brazilian") ||
        haystack.contains("brasileiro") ||
        haystack.contains("pt-br") ||
        haystack.contains("pt_br") ||
        haystack.contains("pob")
}

internal fun isPortugueseTarget(target: String?): Boolean {
    if (target.isNullOrBlank()) return false
    val n = PlayerSubtitleUtils.normalizeLanguageCode(target)
    return n == "pt" || n == "pt-br" || n == "por" || n == "pob"
}

internal fun isPortugueseSubtitleTrack(track: TrackInfo): Boolean {
    val lang = track.language?.lowercase(Locale.ROOT)
    if (lang != null && (lang == "pt" || lang == "por" || lang == "pt-br" || lang == "pt_br" || lang == "pob")) return true
    val haystack = listOfNotNull(track.name, track.trackId).joinToString(" ").lowercase(Locale.ROOT)
    return haystack.contains("portugu") ||
        haystack.contains("brazilian") ||
        haystack.contains("brasileiro") ||
        haystack.contains("pt-br") ||
        haystack.contains("pt_br") ||
        haystack.contains("pob")
}

internal fun calculateReleaseMatchScore(
    subtitle: Subtitle,
    streamName: String?,
    streamUrl: String?
): Int {
    val streamHaystack = listOfNotNull(streamName, streamUrl).joinToString(" ").lowercase(Locale.ROOT)
    val subHaystack = listOfNotNull(subtitle.fileName, subtitle.id, subtitle.addonName, subtitle.url).joinToString(" ").lowercase(Locale.ROOT)
    if (streamHaystack.isBlank() || subHaystack.isBlank()) return 0

    var score = 0
    val keyTokens = listOf(
        "bluray", "bdrip", "brrip", "remux", "web-dl", "webrip", "web", "hdtv", "dvdrip",
        "amzn", "amazon", "netflix", "nf", "hbo", "max", "disney", "dsnp", "apple", "atvp",
        "flux", "rarbg", "yify", "yts", "sparks", "galaxyrg", "tgx", "evo", "qxr", "ntb",
        "23.976", "24fps", "25fps", "1080p", "2160p", "4k", "720p"
    )

    for (token in keyTokens) {
        val inStream = streamHaystack.contains(token)
        val inSub = subHaystack.contains(token)
        if (inStream && inSub) {
            score += when (token) {
                "bluray", "web-dl", "webrip", "remux" -> 50
                "flux", "rarbg", "yify", "yts", "sparks", "ntb" -> 80
                "amzn", "netflix", "nf", "hbo", "dsnp" -> 40
                "23.976", "24fps", "25fps" -> 60
                else -> 20
            }
        } else if (inStream && !inSub) {
            if (token == "bluray" && (subHaystack.contains("web-dl") || subHaystack.contains("webrip"))) {
                score -= 40
            } else if ((token == "web-dl" || token == "webrip") && subHaystack.contains("bluray")) {
                score -= 40
            }
        }
    }
    return score
}

internal fun isPortugueseAddonSubtitle(subtitle: Subtitle): Boolean {
    if (isPortugueseTarget(subtitle.lang)) return true
    val haystack = listOfNotNull(subtitle.id, subtitle.url, subtitle.addonName, subtitle.lang)
        .joinToString(" ").lowercase(Locale.ROOT)
    return haystack.contains("portugu") ||
        haystack.contains("pt-br") ||
        haystack.contains("pt_br") ||
        haystack.contains("brazilian") ||
        haystack.contains("brasileiro") ||
        haystack.contains("pob")
}

internal fun isTrackHintingForced(name: String?, language: String?, trackId: String?): Boolean {
    val haystack = listOfNotNull(name, language, trackId).joinToString(" ").lowercase(Locale.ROOT)
    return haystack.contains("forced") ||
        haystack.contains("forçada") ||
        haystack.contains("forcada") ||
        haystack.contains("forçado") ||
        haystack.contains("forcado") ||
        haystack.contains("forzad") ||
        haystack.contains("foreign") ||
        haystack.contains("sinais") ||
        haystack.contains("partes estrangeiras") ||
        haystack.contains("falas estrangeiras") ||
        haystack.contains("narrative") ||
        Regex("(^|[^a-z0-9])fn([^a-z0-9]|\$)").containsMatchIn(haystack) ||
        Regex("(^|[^a-z0-9])signs?([^a-z0-9]|\$)").containsMatchIn(haystack)
}

internal fun hasExplicitOtherLanguage(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    val lower = name.lowercase(Locale.ROOT)
    return lower.contains("english") || lower.contains("inglês") || lower.contains("ingles") ||
        lower.contains("spanish") || lower.contains("español") || lower.contains("espanhol") ||
        lower.contains("french") || lower.contains("francês") || lower.contains("frances") ||
        lower.contains("german") || lower.contains("alemão") || lower.contains("alemao") ||
        lower.contains("italian") || lower.contains("italiano") ||
        lower.contains("japanese") || lower.contains("japonês") || lower.contains("japones")
}

private fun findBestForcedSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    target: String,
    selectedAudioTrack: TrackInfo?
): Int {
    if (subtitleTracks.isEmpty()) return -1

    val isAudioPt = selectedAudioTrack != null && isAudioPortuguese(selectedAudioTrack)

    // 1. Direct match: track is marked forced, and language family matches target or selected audio
    val directMatch = subtitleTracks.indexOfFirst { track ->
        track.isForced && (
            isSameLanguageFamily(track.language, target) ||
            (selectedAudioTrack != null && isSameLanguageFamily(track.language, selectedAudioTrack.language)) ||
            (isAudioPt && isPortugueseSubtitleTrack(track))
        )
    }
    if (directMatch >= 0) return directMatch

    // 2. Track has forced hint in name/label, and language family matches
    val nameHintMatch = subtitleTracks.indexOfFirst { track ->
        isTrackHintingForced(track.name, track.language, track.trackId) && (
            isSameLanguageFamily(track.language, target) ||
            (selectedAudioTrack != null && isSameLanguageFamily(track.language, selectedAudioTrack.language)) ||
            (isAudioPt && isPortugueseSubtitleTrack(track))
        )
    }
    if (nameHintMatch >= 0) return nameHintMatch

    // 3. Fallback for MKVs with untagged language (language is null, blank or "und"):
    // If the track is marked forced (flag or name like \forced), and does NOT explicitly belong to another language
    val untaggedForced = subtitleTracks.indexOfFirst { track ->
        (track.isForced || isTrackHintingForced(track.name, track.language, track.trackId)) &&
            (track.language.isNullOrBlank() || track.language.equals("und", ignoreCase = true)) &&
            !hasExplicitOtherLanguage(track.name)
    }
    if (untaggedForced >= 0) return untaggedForced

    return -1
}

private fun subtitleTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
    return trackMatchesLanguage(
        name = track.name,
        language = track.language,
        trackId = track.trackId,
        target = target
    )
}

internal fun audioTrackMatchesLanguage(track: TrackInfo, target: String): Boolean {
    return trackMatchesLanguage(
        name = track.name,
        language = track.language,
        trackId = track.trackId,
        target = target
    )
}

internal fun trackMatchesLanguage(
    name: String?,
    language: String?,
    trackId: String?,
    target: String
): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(language, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOfNotNull(name, language, trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

internal fun PlayerRuntimeController.selectedAudioMatchesResolvedPreferredAudio(track: TrackInfo): Boolean {
    return mpvPreferredAudioLanguages.any { target -> audioTrackMatchesLanguage(track, target) }
}

private fun languageCodeAppearsInHaystack(haystack: String, normalizedTarget: String): Boolean {
    if (normalizedTarget.isBlank()) return false
    var searchFrom = 0
    while (searchFrom <= haystack.length - normalizedTarget.length) {
        val matchIndex = haystack.indexOf(normalizedTarget, startIndex = searchFrom)
        if (matchIndex < 0) return false

        val before = matchIndex - 1
        val after = matchIndex + normalizedTarget.length
        val startsAtBoundary = before < 0 || !haystack[before].isLetterOrDigit()
        val endsAtBoundary = after >= haystack.length || !haystack[after].isLetterOrDigit()
        if (startsAtBoundary && endsAtBoundary) return true

        searchFrom = matchIndex + 1
    }
    return false
}

private fun subtitleTrackMatchesSelectedAudioLanguage(
    subtitleTrack: TrackInfo,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (subtitleTrackMatchesLanguage(subtitleTrack, audioLanguage)) return true
    }

    val subtitleLanguageName = subtitleTrack.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.name,
        selectedAudioTrack.language,
        selectedAudioTrack.trackId
    ).joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun selectedAudioTrackForSubtitleMatching(state: PlayerUiState): TrackInfo? {
    return state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }
}

internal fun selectedAudioLanguageTarget(track: TrackInfo): String? {
    track.language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { return it }

    val haystack = listOfNotNull(track.name, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return AVAILABLE_SUBTITLE_LANGUAGES.firstOrNull { language ->
        val code = language.code.lowercase(Locale.ROOT)
        val name = languageCodeToName(language.code).lowercase(Locale.ROOT)
        languageCodeAppearsInHaystack(haystack, code) || (name.isNotBlank() && haystack.contains(name))
    }?.code
}

internal fun addonSubtitleIsForced(subtitle: Subtitle): Boolean {
    val haystacks = listOfNotNull(subtitle.id, subtitle.url, subtitle.addonName, subtitle.lang, subtitle.fileName)
    return haystacks.any { text ->
        val lower = text.lowercase(Locale.ROOT)
        lower.contains("forced") ||
            lower.contains("forçada") ||
            lower.contains("forcada") ||
            lower.contains("forçado") ||
            lower.contains("forcado") ||
            lower.contains("forzad") ||
            lower.contains("foreign") ||
            lower.contains("sinais") ||
            lower.contains("partes estrangeiras") ||
            lower.contains("falas estrangeiras") ||
            lower.contains("non-english") ||
            lower.contains("non english") ||
            lower.contains("narrative") ||
            Regex("(^|[^a-z0-9])fn([^a-z0-9]|\$)").containsMatchIn(lower) ||
            Regex("(^|[^a-z0-9])signs?([^a-z0-9]|\$)").containsMatchIn(lower)
    }
}

internal fun PlayerRuntimeController.probeAndSelectForcedAddonSubtitleAsync(
    candidates: List<Subtitle>,
    audioTarget: String?
) {
    // Probing de legendas forçadas de addon desativado
}

private fun addonSubtitleMatchesLanguage(subtitle: Subtitle, target: String): Boolean {
    if (PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)) return true
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val targetName = languageCodeToName(target).lowercase(Locale.ROOT)
    val haystack = listOf(subtitle.lang, subtitle.id, subtitle.url, subtitle.addonName)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return languageCodeAppearsInHaystack(haystack, normalizedTarget) ||
        (targetName.isNotBlank() && haystack.contains(targetName))
}

private fun addonSubtitleMatchesSelectedAudioLanguage(
    subtitle: Subtitle,
    selectedAudioTrack: TrackInfo
): Boolean {
    selectedAudioLanguageTarget(selectedAudioTrack)?.let { audioLanguage ->
        if (addonSubtitleMatchesLanguage(subtitle, audioLanguage)) return true
    }

    val subtitleLanguageName = subtitle.lang
        .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { languageCodeToName(it).lowercase(Locale.ROOT) }
    val audioHaystack = listOfNotNull(
        selectedAudioTrack.name,
        selectedAudioTrack.language,
        selectedAudioTrack.trackId
    ).joinToString(" ").lowercase(Locale.ROOT)
    return !subtitleLanguageName.isNullOrBlank() && audioHaystack.contains(subtitleLanguageName)
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        if (normalOnly && subtitleTracks[index].isForced) return@filter false
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    val brazilianNonForced = genericPtIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
                subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }
    if (brazilianNonForced.isNotEmpty()) return brazilianNonForced.first()

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.findLatinoSpanishInGenericEsTracks(
    subtitleTracks: List<TrackInfo>,
    normalOnly: Boolean = false
): Int {
    val genericEsIndexes = subtitleTracks.indices.filter { index ->
        if (normalOnly && subtitleTracks[index].isForced) return@filter false
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "es"
    }
    if (genericEsIndexes.isEmpty()) return -1

    val latinoNonForced = genericEsIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
                subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }
    if (latinoNonForced.isNotEmpty()) return latinoNonForced.first()

    return genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
                !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    } ?: genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakSpanishSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasLatinoTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    }

    fun hasCastilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }

    return if (normalizedTarget == "es-419") {
        candidateIndexes.firstOrNull { hasLatinoTags(it) && !hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasLatinoTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasCastilianTags(it) && !hasLatinoTags(it) }
            ?: candidateIndexes.firstOrNull { hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { !hasLatinoTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.subtitleHasAnyTag(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

/**
 * Checks whether the selected audio track matches the subtitle target language for the purpose
 * of activating forced-only subtitle mode. This is more lenient than [audioTrackMatchesLanguage]
 * for regional variants: if the target is "pt-br" and the audio language is generic "pt",
 * we consider it a match because the audio is likely Brazilian Portuguese even without explicit
 * regional tags. Same logic applies to "es-419" matching generic "es" audio.
 */
private fun audioMatchesSubtitleTargetForForced(audioTrack: TrackInfo, target: String): Boolean {
    if (audioTrackMatchesLanguage(audioTrack, target)) return true

    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
    val baseTarget = normalizedTarget.substringBefore('-')
    if (baseTarget == normalizedTarget) return false // not a regional variant

    // Check if the audio track's base language matches the target's base language.
    // e.g. audio="pt" matches target="pt-br", audio="es" matches target="es-419"
    val audioVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = audioTrack.language,
        name = audioTrack.name,
        trackId = audioTrack.trackId
    )
    // If audio is generic base (e.g. "pt") or the same regional variant (e.g. "pt-br"), match.
    return audioVariant == baseTarget || audioVariant == normalizedTarget
}

internal fun PlayerRuntimeController.tryAutoSelectPreferredSubtitleFromAvailableTracks() {
    if (subtitleDisabledByPersistedPreference) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: subtitle explicitly disabled by persisted preference for this stream")
        disableSubtitles()
        _uiState.update { it.copy(selectedSubtitleTrackIndex = -1, selectedAddonSubtitle = null) }
        return
    }
    if (isUserExplicitSubtitleSelection) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: user explicitly selected current subtitle")
        return
    }
    if (hasBurnedInServerSubtitle) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: server is burning in a subtitle")
        return
    }
    val state = _uiState.value
    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val subtitleStyle = state.subtitleStyle
    val useForcedSubtitles = subtitleStyle.useForcedSubtitles
    val preferredTargets = subtitleLanguageTargets()
    val primaryTarget = preferredTargets.firstOrNull()

    val isAudioPt = selectedAudioTrack != null && isAudioPortuguese(selectedAudioTrack)
    val seriesAudioLang = seriesPreferredAudioLanguageSetting?.takeIf { it.isNotBlank() }
    val isAudioSeriesPreferred = seriesAudioLang != null && selectedAudioTrack != null &&
        audioMatchesSubtitleTargetForForced(selectedAudioTrack, seriesAudioLang)

    val shouldDisableForCurrentAudio = subtitleStyle.disableForAudioLanguages &&
        selectedAudioTrack != null && (
            (isAudioPt && listOfNotNull(
                subtitleStyle.disableForAudioLanguage1,
                subtitleStyle.disableForAudioLanguage2
            ).any { isPortugueseTarget(it) }) ||
            listOfNotNull(
                subtitleStyle.disableForAudioLanguage1?.takeIf { it.isNotBlank() && it != "none" },
                subtitleStyle.disableForAudioLanguage2?.takeIf { it.isNotBlank() && it != "none" }
            ).any { ignoredLang ->
                audioMatchesSubtitleTargetForForced(selectedAudioTrack, ignoredLang)
            } ||
            (isAudioSeriesPreferred && listOfNotNull(
                subtitleStyle.disableForAudioLanguage1?.takeIf { it.isNotBlank() && it != "none" },
                subtitleStyle.disableForAudioLanguage2?.takeIf { it.isNotBlank() && it != "none" }
            ).any { ignoredLang ->
                audioMatchesSubtitleTargetForForced(selectedAudioTrack, ignoredLang) ||
                    PlayerSubtitleUtils.matchesLanguageCode(ignoredLang, seriesAudioLang)
            })
        )

    if (shouldDisableForCurrentAudio && selectedAudioTrack != null) {
        val forcedTargetsForAudio = if (isAudioPt) {
            listOf("pt-br", "pt")
        } else {
            listOfNotNull(
                selectedAudioLanguageTarget(selectedAudioTrack),
                selectedAudioTrack.language,
                primaryTarget
            ).distinct()
        }

        // 1. Prioridade: legenda forçada interna embutida na própria fonte
        val forcedInternalIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = state.subtitleTracks,
            targets = forcedTargetsForAudio,
            forcedOnly = true,
            normalOnly = false,
            selectedAudioTrack = selectedAudioTrack
        )

        if (forcedInternalIndex >= 0) {
            autoSubtitleSelected = true
            val currentInternal = state.selectedSubtitleTrackIndex
            val currentAddon = state.selectedAddonSubtitle
            if (currentInternal != forcedInternalIndex || currentAddon != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB pick internal forced track (disabled audio language mode): index=$forcedInternalIndex lang=${state.subtitleTracks[forcedInternalIndex].language}"
                )
                selectSubtitleTrack(forcedInternalIndex)
                _uiState.update {
                    it.copy(
                        selectedSubtitleTrackIndex = forcedInternalIndex,
                        selectedAddonSubtitle = null
                    )
                }
            }
            return
        }

        // 2. Fallback: legenda forçada vinda de addon (ex: opensubtitlesv3) com identificação de forçada
        val forcedAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
            addonSubtitleIsForced(subtitle) &&
                forcedTargetsForAudio.any { target ->
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) ||
                        addonSubtitleMatchesLanguage(subtitle, target)
                }
        }

        if (forcedAddonMatch != null) {
            autoSubtitleSelected = true
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB pick addon forced track fallback (disabled audio language mode): lang=${forcedAddonMatch.lang} id=${forcedAddonMatch.id}"
            )
            selectAddonSubtitle(forcedAddonMatch)
            maybeRunAutomaticSubtitleSync(forcedAddonMatch)
            return
        }

        // Se os addons ainda estão carregando, não desativa ainda para permitir que a legenda forçada de addon apareça
        if (state.isLoadingAddonSubtitles) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer: waiting for addon subtitles to check forced fallback")
            return
        }

        // 3. Nenhuma legenda forçada disponível: desativa legendas normais para o idioma selecionado
        autoSubtitleSelected = true
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB stop: disabled for audio language (${selectedAudioTrack.language}/${selectedAudioTrack.name}) and no forced subtitle found"
        )
        if (state.selectedSubtitleTrackIndex >= 0 || state.selectedAddonSubtitle != null) {
            disableSubtitles()
            _uiState.update {
                it.copy(
                    selectedSubtitleTrackIndex = -1,
                    selectedAddonSubtitle = null
                )
            }
        }
        return
    }

    if (autoSubtitleSelected) {
        val currentSelectedLang = when {
            state.selectedAddonSubtitle != null -> state.selectedAddonSubtitle?.lang
            state.selectedSubtitleTrackIndex >= 0 -> state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.language
            else -> null
        }
        val isPrimarySatisfied = primaryTarget != null && currentSelectedLang != null &&
            PlayerSubtitleUtils.matchesLanguageCode(currentSelectedLang, primaryTarget)
        val hasBetterAddonMatch = !isPrimarySatisfied && primaryTarget != null &&
            state.addonSubtitles.any { PlayerSubtitleUtils.matchesLanguageCode(it.lang, primaryTarget) }

        if (!hasBetterAddonMatch) return
    }

    val forcedTarget = when {
        !useForcedSubtitles -> null
        primaryTarget != null && selectedAudioTrack != null && (
            audioMatchesSubtitleTargetForForced(selectedAudioTrack, primaryTarget) ||
            (isAudioPortuguese(selectedAudioTrack) && isPortugueseTarget(primaryTarget))
        ) ->
            primaryTarget
        primaryTarget == null &&
            selectedAudioTrack != null &&
            selectedAudioMatchesResolvedPreferredAudio(selectedAudioTrack) ->
            selectedAudioLanguageTarget(selectedAudioTrack)
        else -> null
    }
    val forcedOnly = forcedTarget != null
    val targets = when {
        forcedTarget != null -> listOf(forcedTarget)
        primaryTarget != null -> preferredTargets
        else -> emptyList()
    }
    Log.d(
        PlayerRuntimeController.TAG,
        "AUTO_SUB eval: targets=$targets, forcedOnly=$forcedOnly, selectedAudio=${selectedAudioTrack?.language}/${selectedAudioTrack?.name}, scannedText=$hasScannedTextTracksOnce, " +
            "internalCount=${state.subtitleTracks.size}, selectedInternal=${state.selectedSubtitleTrackIndex}, " +
            "addonCount=${state.addonSubtitles.size}, selectedAddon=${state.selectedAddonSubtitle?.lang}"
    )
    if ((useForcedSubtitles || subtitleStyle.disableForAudioLanguages) && selectedAudioTrack == null) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer: selected audio track unknown")
        return
    }
    if (targets.isEmpty()) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred=none")
        disableSubtitles()
        _uiState.update { it.copy(selectedSubtitleTrackIndex = -1, selectedAddonSubtitle = null) }
        if (isUsingMpvEngine()) {
            mpvView?.disableSubtitles()
        }
        return
    }

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets,
        forcedOnly = forcedOnly,
        normalOnly = !forcedOnly,
        selectedAudioTrack = selectedAudioTrack
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        // Determine which target position this internal match satisfies,
        // taking regional variant into account so that e.g. a PT-BR track
        // is not treated as a primary match when the user wants PT.
        val matchedTrack = state.subtitleTracks[internalIndex]
        val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.name,
            trackId = matchedTrack.trackId
        )
        val matchedTargetPosition = targets.indexOfFirst { target ->
            val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                    PlayerSubtitleUtils.matchesLanguageCode(trackVariant, target)
        }
        val addonSubtitlesLoaded = !state.isLoadingAddonSubtitles
        if (matchedTargetPosition > 0 && !addonSubtitlesLoaded) {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB defer: internal match is secondary target pos=$matchedTargetPosition, addons still loading"
            )
            return
        }
        // If internal match is secondary and a primary addon match exists, prefer the addon.
        if (matchedTargetPosition > 0 && addonSubtitlesLoaded) {
            val primaryTarget = targets.first()
            val primaryAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, primaryTarget)
            }
            if (primaryAddonMatch != null) {
                autoSubtitleSelected = true
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB pick addon (primary) over internal (secondary): addon lang=${primaryAddonMatch.lang} vs internal variant=$trackVariant"
                )
                selectAddonSubtitle(primaryAddonMatch)
                maybeRunAutomaticSubtitleSync(primaryAddonMatch) // AutoSync hook
                return
            }
        }
        autoSubtitleSelected = true
        val currentInternal = state.selectedSubtitleTrackIndex
        val currentAddon = state.selectedAddonSubtitle
        if (currentInternal != internalIndex || currentAddon != null) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal index=$internalIndex lang=${state.subtitleTracks[internalIndex].language}")
            selectSubtitleTrack(internalIndex)
            _uiState.update { it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null) }
        } else {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred internal already selected")
        }
        return
    }

    if (forcedOnly) {
        val forcedAddon = state.addonSubtitles.firstOrNull { subtitle ->
            addonSubtitleIsForced(subtitle) &&
                targets.any { target ->
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) ||
                        addonSubtitleMatchesLanguage(subtitle, target)
                }
        }
        if (forcedAddon != null) {
            autoSubtitleSelected = true
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB pick forced addon fallback: lang=${forcedAddon.lang} id=${forcedAddon.id}"
            )
            selectAddonSubtitle(forcedAddon)
            maybeRunAutomaticSubtitleSync(forcedAddon)
            return
        }

        if (state.isLoadingAddonSubtitles) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer: forced only, waiting for addon subtitles")
            return
        }

        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: forced subtitles requested but no internal or addon forced match found")
        disableSubtitles()
        _uiState.update { it.copy(selectedSubtitleTrackIndex = -1, selectedAddonSubtitle = null) }
        return
    }

    val selectedAddon = state.selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(selectedAddon.lang, target) }
    if (selectedAddonMatchesTarget) {
        val matchingSelectedAddon = selectedAddon ?: return
        val selectedMatchesPrimary = PlayerSubtitleUtils.matchesLanguageCode(
            matchingSelectedAddon.lang, targets.first()
        )
        if (selectedMatchesPrimary) {
            autoSubtitleSelected = true
            maybeRunAutomaticSubtitleSync(matchingSelectedAddon) // AutoSync hook
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (primary match)")
            return
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB: selected addon ${matchingSelectedAddon.lang} matches secondary target, checking for primary addon"
        )
    }

    // Wait until we have at least one full text-track scan to avoid choosing addon too early.
    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: text tracks not scanned yet")
        return
    }

    val playerReady = if (isUsingMpvEngine()) {
        mpvView != null
    } else {
        _exoPlayer?.playbackState == Player.STATE_READY
    }
    if (!playerReady) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: player not ready")
        return
    }

    val addonMatch = run {
        // Try each target in priority order so primary language is preferred over secondary.
        for (target in targets) {
            val match = state.addonSubtitles.firstOrNull { subtitle ->
                (!useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
            if (match != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB addon fallback: target=$target matched addon lang=${match.lang} id=${match.id} " +
                            "(addons=${state.addonSubtitles.size}, targets=$targets)"
                )
                return@run match
            }
        }
        null
    }
    if (addonMatch != null) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick addon lang=${addonMatch.lang} id=${addonMatch.id}")
        selectAddonSubtitle(addonMatch)
        maybeRunAutomaticSubtitleSync(addonMatch) // AutoSync hook
    } else {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB no addon match for targets=$targets")
    }
}

internal fun PlayerRuntimeController.startFrameRateProbe(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingEnabled: Boolean,
    preserveCurrentDetection: Boolean = false,
    allowAmbiguousTrackOverride: Boolean = false
) {
    frameRateProbeJob?.cancel()
    _uiState.update { state ->
        if (!preserveCurrentDetection) {
            state.clearFrameRateDetection().copy(afrProbeRunning = false)
        } else {
            state.copy(afrProbeRunning = false)
        }
    }
    if (!frameRateMatchingEnabled) return

    val token = ++frameRateProbeToken
    frameRateProbeJob = scope.launch(Dispatchers.IO) {
        try {
            delay(PlayerRuntimeController.TRACK_FRAME_RATE_GRACE_MS)
            if (!isActive) return@launch
            val stateSnapshot = withContext(Dispatchers.Main) { _uiState.value }
            val trackAlreadySet = stateSnapshot.detectedFrameRateSource == FrameRateSource.TRACK &&
                    stateSnapshot.detectedFrameRate > 0f
            if (trackAlreadySet) {
                if (!allowAmbiguousTrackOverride) return@launch

                val trackRaw = if (stateSnapshot.detectedFrameRateRaw > 0f) {
                    stateSnapshot.detectedFrameRateRaw
                } else {
                    stateSnapshot.detectedFrameRate
                }
                if (!PlayerFrameRateHeuristics.isAmbiguousCinema24(trackRaw)) return@launch
            }

            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = true) }
                }
            }

            val detection = FrameRateUtils.detectFrameRateFromSource(context, url, headers)
                ?: return@launch
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    val state = _uiState.value
                    val shouldApplyInitial = state.detectedFrameRate <= 0f
                    val shouldOverrideAmbiguousTrack = allowAmbiguousTrackOverride &&
                            PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection)

                    if (shouldApplyInitial || shouldOverrideAmbiguousTrack) {
                        _uiState.update {
                            it.copy(
                                detectedFrameRateRaw = detection.raw,
                                detectedFrameRate = detection.snapped,
                                detectedFrameRateSource = FrameRateSource.PROBE
                            )
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = false) }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.applySubtitlePreferences(preferred: String, secondary: String?) {
    if (isUserExplicitSubtitleSelection) return
    if (isUsingMpvEngine()) {
        mpvView?.applySubtitleLanguagePreferences(preferred, secondary)
        if (preferred == "none") {
            mpvView?.disableSubtitles()
            _uiState.update {
                it.copy(
                    selectedSubtitleTrackIndex = -1,
                    selectedAddonSubtitle = null
                )
            }
        }
        return
    }

    _exoPlayer?.let { player ->
        val builder = player.trackSelectionParameters.buildUpon()

        if (preferred == "none") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.setPreferredTextLanguage(null)
        } else {
            val userDisabledSubtitles = autoSubtitleSelected &&
                    _uiState.value.selectedSubtitleTrackIndex == -1 &&
                    _uiState.value.selectedAddonSubtitle == null
            // Suppress ExoPlayer auto-select when forced mode is active —
            // our custom logic handles track selection.
            val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
            val shouldSuppressExoAutoSelect = useForcedSubtitles && !autoSubtitleSelected
            if (!userDisabledSubtitles && !shouldSuppressExoAutoSelect) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            if (!shouldSuppressExoAutoSelect) {
                builder.setPreferredTextLanguage(preferred)
            }
        }

        // Update forced flag handling: when forced subtitles are disabled,
        // tell ExoPlayer to ignore SELECTION_FLAG_FORCED.
        val useForcedSubtitles = _uiState.value.subtitleStyle.useForcedSubtitles
        val currentFlags = player.trackSelectionParameters.ignoredTextSelectionFlags
        val newFlags = if (!useForcedSubtitles) {
            currentFlags or C.SELECTION_FLAG_FORCED
        } else {
            currentFlags and C.SELECTION_FLAG_FORCED.inv()
        }
        builder.setIgnoredTextSelectionFlags(newFlags)

        player.trackSelectionParameters = builder.build()
    }
}
