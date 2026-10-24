@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

internal class AudioDelayMediaSource(
    mediaSource: MediaSource,
    private val audioDelayUsProvider: () -> Long,
    // Quando presente, o delay do audio varia com a posicao (microsegundos -> microsegundos).
    private val audioDelayAtUs: ((Long) -> Long)? = null
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return AudioDelayMediaPeriod(
            mediaPeriod = mediaSource.createPeriod(id, allocator, startPositionUs),
            audioDelayUsProvider = audioDelayUsProvider,
            audioDelayAtUs = audioDelayAtUs
        )
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val wrappedPeriod = (mediaPeriod as AudioDelayMediaPeriod).mediaPeriod
        mediaSource.releasePeriod(wrappedPeriod)
    }

    override fun onChildSourceInfoRefreshed(timeline: Timeline) {
        refreshSourceInfo(timeline)
    }
}

private class AudioDelayMediaPeriod(
    val mediaPeriod: MediaPeriod,
    private val audioDelayUsProvider: () -> Long,
    private val audioDelayAtUs: ((Long) -> Long)? = null
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        mediaPeriod.prepare(this, positionUs)
    }

    override fun maybeThrowPrepareError() {
        mediaPeriod.maybeThrowPrepareError()
    }

    override fun getTrackGroups(): TrackGroupArray = mediaPeriod.getTrackGroups()

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> {
        return mediaPeriod.getStreamKeys(trackSelections)
    }

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (index in streams.indices) {
            childStreams[index] = (streams[index] as? AudioDelaySampleStream)?.childStream ?: streams[index]
        }

        val selectedPositionUs = mediaPeriod.selectTracks(
            selections,
            mayRetainStreamFlags,
            childStreams,
            streamResetFlags,
            positionUs
        )

        for (index in streams.indices) {
            val childStream = childStreams[index]
            val trackType = selections[index]
                ?.selectedFormat
                ?.sampleMimeType
                ?.let(MimeTypes::getTrackType)

            streams[index] = when {
                childStream == null -> null
                trackType == C.TRACK_TYPE_VIDEO -> {
                    val currentWrapper = streams[index] as? AudioDelaySampleStream
                    if (currentWrapper?.childStream === childStream) {
                        currentWrapper
                    } else {
                        AudioDelaySampleStream(childStream, isVideo = true, audioDelayUsProvider = audioDelayUsProvider, audioDelayAtUs = audioDelayAtUs)
                    }
                }
                trackType == C.TRACK_TYPE_AUDIO -> {
                    val currentWrapper = streams[index] as? AudioDelaySampleStream
                    if (currentWrapper?.childStream === childStream) {
                        currentWrapper
                    } else {
                        AudioDelaySampleStream(childStream, isVideo = false, audioDelayUsProvider = audioDelayUsProvider, audioDelayAtUs = audioDelayAtUs)
                    }
                }
                else -> childStream
            }
        }

        return selectedPositionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        mediaPeriod.discardBuffer(positionUs, toKeyframe)
    }

    override fun readDiscontinuity(): Long = mediaPeriod.readDiscontinuity()

    override fun seekToUs(positionUs: Long): Long {
        val delayUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs - delayUs).coerceAtLeast(0L)
        val soughtUs = mediaPeriod.seekToUs(adjustedPositionUs)
        return soughtUs + delayUs
    }

    override fun getAdjustedSeekPositionUs(
        positionUs: Long,
        seekParameters: SeekParameters
    ): Long {
        val delayUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs - delayUs).coerceAtLeast(0L)
        val adjustedSeekUs = mediaPeriod.getAdjustedSeekPositionUs(adjustedPositionUs, seekParameters)
        return adjustedSeekUs + delayUs
    }

    override fun getBufferedPositionUs(): Long {
        val buffered = mediaPeriod.getBufferedPositionUs()
        if (buffered == C.TIME_END_OF_SOURCE || buffered == Long.MIN_VALUE) return buffered
        return buffered + audioDelayUsProvider()
    }

    override fun getNextLoadPositionUs(): Long = mediaPeriod.getNextLoadPositionUs()

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        return mediaPeriod.continueLoading(loadingInfo)
    }

    override fun isLoading(): Boolean = mediaPeriod.isLoading()

    override fun reevaluateBuffer(positionUs: Long) {
        mediaPeriod.reevaluateBuffer(positionUs)
    }

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(mediaPeriod: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }
}

private class AudioDelaySampleStream(
    val childStream: SampleStream,
    private val isVideo: Boolean,
    private val audioDelayUsProvider: () -> Long,
    private val audioDelayAtUs: ((Long) -> Long)? = null
) : SampleStream {

    private fun delayFor(timeUs: Long): Long =
        if (!isVideo && audioDelayAtUs != null) audioDelayAtUs.invoke(timeUs) else audioDelayUsProvider()

    override fun isReady(): Boolean = childStream.isReady()

    override fun maybeThrowError() {
        childStream.maybeThrowError()
    }

    override fun readData(
        formatHolder: FormatHolder,
        buffer: DecoderInputBuffer,
        readFlags: Int
    ): Int {
        val result = childStream.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ) {
            val delayUs = delayFor(buffer.timeUs)
            if (isVideo) {
                buffer.timeUs -= delayUs
            } else {
                buffer.timeUs += delayUs
            }
        }
        return result
    }

    override fun skipData(positionUs: Long): Int {
        val delayUs = delayFor(positionUs)
        val adjusted = if (isVideo) positionUs + delayUs else positionUs - delayUs
        return childStream.skipData(adjusted.coerceAtLeast(0L))
    }
}
