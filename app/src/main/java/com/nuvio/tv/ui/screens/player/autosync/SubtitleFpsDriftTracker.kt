package com.nuvio.tv.ui.screens.player.autosync

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Multi-point subtitle timing and frame rate drift tracker based on Better-Nuvio's legsync.c.
 *
 * Compares video playback time with subtitle cue time across multiple spoken cues.
 * When two cues are marked with sufficient spacing (>= 120s), it evaluates whether
 * the time ratio matches common framerate conversions (e.g. 23.976 <-> 25.000 fps,
 * ratio within [0.94, 1.06]) and calculates the drift speed rate.
 */
internal class SubtitleFpsDriftTracker {

    companion object {
        const val MAX_POINTS = 16
        const val MIN_INTERVAL_SECONDS = 120.0
        const val MIN_DRIFT_RATE = 0.94
        const val MAX_DRIFT_RATE = 1.06
    }

    private val videoPoints = ArrayList<Double>()
    private val subtitlePoints = ArrayList<Double>()

    data class MarkResult(
        val type: Int, // 0 = rejected/invalid, 1 = initial delay set, 2 = fps drift rate calculated
        val delayMs: Int,
        val speedRate: Double = 1.0
    )

    fun clear() {
        videoPoints.clear()
        subtitlePoints.clear()
    }

    fun hasPoints(): Boolean = videoPoints.isNotEmpty()

    fun mark(videoSec: Double, subtitleSec: Double): MarkResult {
        if (!videoSec.isFinite() || !subtitleSec.isFinite() || videoSec < 0.0 || subtitleSec < 0.0) {
            return MarkResult(0, 0)
        }

        // A mark too close to a previous one (< 120s) resets and becomes a fresh delay adjustment,
        // not a reliable frame-rate drift calculation (user might have chosen an adjacent line).
        for (v in videoPoints) {
            if (abs(videoSec - v) < MIN_INTERVAL_SECONDS) {
                clear()
                break
            }
        }

        if (videoPoints.isEmpty()) {
            if (abs(subtitleSec - videoSec) > 180.0) {
                return MarkResult(0, 0)
            }
            videoPoints.add(videoSec)
            subtitlePoints.add(subtitleSec)
            val delayMs = ((subtitleSec - videoSec) * 1000.0).roundToInt()
            return MarkResult(1, delayMs, 1.0)
        }

        if (videoSec < videoPoints[0]) {
            clear()
            return mark(videoSec, subtitleSec)
        }

        var pos = 0
        while (pos < videoPoints.size && videoPoints[pos] < videoSec) {
            pos++
        }

        // A false pair must not produce an absurd rate for the rest of the film.
        // 0.94..1.06 includes 23.976/25 and 25/23.976 conversions.
        if (pos > 0) {
            val rate = (subtitleSec - subtitlePoints[pos - 1]) / (videoSec - videoPoints[pos - 1])
            if (rate < MIN_DRIFT_RATE || rate > MAX_DRIFT_RATE) {
                return MarkResult(0, 0)
            }
        }
        if (pos < videoPoints.size) {
            val rate = (subtitlePoints[pos] - subtitleSec) / (videoPoints[pos] - videoSec)
            if (rate < MIN_DRIFT_RATE || rate > MAX_DRIFT_RATE) {
                return MarkResult(0, 0)
            }
        }

        if (videoPoints.size == MAX_POINTS) {
            // Keep the first point (baseline delay) and the most recent ones.
            videoPoints.removeAt(1)
            subtitlePoints.removeAt(1)
            if (pos > 1) pos--
        }

        videoPoints.add(pos, videoSec)
        subtitlePoints.add(pos, subtitleSec)

        val lastIndex = videoPoints.size - 1
        val rate = (subtitlePoints[lastIndex] - subtitlePoints[lastIndex - 1]) /
            (videoPoints[lastIndex] - videoPoints[lastIndex - 1])
        val baseDelayMs = ((subtitlePoints[0] - videoPoints[0]) * 1000.0).roundToInt()

        return MarkResult(2, baseDelayMs, rate)
    }
}
