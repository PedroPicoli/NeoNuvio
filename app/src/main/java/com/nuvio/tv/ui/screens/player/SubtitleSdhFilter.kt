package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput

internal object SubtitleSdhFilter {
    @Volatile
    var isEnabled: Boolean = false

    // Normalizes special unicode spaces (NBSP \u00A0, figure space, thin space, etc.) to standard ASCII space
    private val unicodeSpaces = Regex("[\u00A0\u2000-\u200B\u202F\u205F\u3000\uFEFF]")
    // Normalizes unicode dashes (en-dash, em-dash, horizontal bar) to standard hyphen
    private val unicodeDashes = Regex("[\u2012\u2013\u2014\u2015]")
    // Normalizes smart quotes
    private val smartSingleQuotes = Regex("[\u2018\u2019\u201A\u201B]")
    private val smartDoubleQuotes = Regex("[\u201C\u201D\u201E\u201F]")

    // ">>" marks a speaker change and ">>>" a topic change in CEA-608 style captions
    private val speakerChevrons = Regex("[<>]{2,}[ \t]*")

    // Full lines containing music notes (♪, ♫, ♩) or music captions
    private val musicLine = Regex("(?m)^[ \\t]*[♪♫♩#].*?$")
    private val musicSymbols = Regex("[♪♫♩]+")
    private val musicHash = Regex("(?m)^[ \\t]*#[^#\\r\\n]+#[ \\t]*$")

    // Sounds in asterisks: *sighs*, *chuckles*, *applause*
    private val asterisks = Regex("\\*[^*\\r\\n]+\\*[ \\t]*")

    // Square brackets: [screaming], [door closes], [man 1], etc.
    private val squareBrackets = Regex("\\[[^\\]]*\\][ \\t]*")

    // Curly brackets: {screaming}, {door closes}, etc.
    private val curlyBrackets = Regex("\\{[^}]*\\}[ \\t]*")

    // Parentheses: (GASPS!), (GUNSHOT!), (doorbell rings?), (JOHN), (sighs), etc.
    // Excludes pure numeric parentheses like (1999) or (1)
    private val parentheses = Regex(
        "(?:\\((?!\\s*\\d+\\s*\\))[^)]*\\)|\uFF08(?!\\s*\\d+\\s*\uFF09)[^\uFF09]*\uFF09)[ \\t]*"
    )

    // Speaker labels before a colon or dash:
    // E.g.: "JOHN: Hello", "- SARAH (whispering): Hi", "<i>MAN:</i> What?", "DETECTIVE #1:", "SAMANTHA - Hello"
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?(?:<[^>]+>\\s*)*(?:([ \\t]*-[ \\t]*))?([A-Za-z\u00C0-\u024F0-9 ()'#.,_ -]+|\\[[^\\]]*\\])(?:\\s*<[^>]+>)*(?::|--|[-–—])[ \\t]*"
    )

    // Basic HTML/WebVTT tags commonly found in subtitles: <i>, </i>, <b>, </b>, <u>, </u>, <font...>, </font>, <v...>, </v>, <c...>, </c>
    private val htmlTags = Regex("</?[a-zA-Z0-9]+[^>]*>")

    fun filterCues(cues: List<Cue>): List<Cue> {
        return cues.mapNotNull { cue ->
            val text = cue.text?.toString() ?: return@mapNotNull cue
            val filtered = filterPlainText(text) ?: return@mapNotNull null
            if (filtered == text) cue else cue.buildUpon().setText(filtered).build()
        }
    }

    internal fun filterPlainText(text: String): String? {
        var filtered = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(unicodeSpaces, " ")
            .replace(unicodeDashes, "-")
            .replace(smartSingleQuotes, "'")
            .replace(smartDoubleQuotes, "\"")

        // Strip HTML tags early so tags around speaker names or sound descriptions don't prevent regex matches
        filtered = htmlTags.replace(filtered, "")
        filtered = musicLine.replace(filtered, "")
        filtered = musicSymbols.replace(filtered, "")
        filtered = musicHash.replace(filtered, "")
        filtered = speakerChevrons.replace(filtered, "")
        filtered = speakerLabel.replace(filtered) { match ->
            match.groups[1]?.value?.takeIf { it.isNotEmpty() } ?: match.groups[2]?.value.orEmpty()
        }
        filtered = squareBrackets.replace(filtered, "")
        filtered = curlyBrackets.replace(filtered, "")
        filtered = parentheses.replace(filtered, "")
        filtered = asterisks.replace(filtered, "")
        filtered = htmlTags.replace(filtered, "")

        val rawLines = filtered.lines().map { it.trim() }
        val cleanedLines = mutableListOf<String>()

        for (idx in rawLines.indices) {
            val line = rawLines[idx]
            val stripped = line.removePrefix("-").removePrefix("--").trim()
            if (!stripped.any { it.isLetterOrDigit() }) {
                continue
            }
            // Standalone uppercase speaker name: e.g. "JOHN", "- JOHN", "DETECTIVE MILLER" followed by dialogue
            val isStandaloneSpeaker = idx < rawLines.size - 1 &&
                stripped.length in 2..35 &&
                stripped.none { it.isLowerCase() } &&
                stripped.any { it.isLetter() } &&
                !stripped.endsWith("!") &&
                !stripped.endsWith("?") &&
                rawLines.drop(idx + 1).any { nextLine -> nextLine.any { it.isLowerCase() } }

            if (!isStandaloneSpeaker) {
                cleanedLines.add(line)
            }
        }

        return cleanedLines
            .map { it.trim() }
            .filter { line -> line.any { it.isLetterOrDigit() } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }

    internal fun filterRawSubtitleFile(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")

        // Handle ASS/SSA subtitles
        if (normalized.contains("[Events]") || normalized.contains("[V4+ Styles]") || normalized.contains("[V4 Styles]")) {
            return filterAssSubtitleFile(normalized)
        }

        // Handle WebVTT and SRT
        val isVtt = normalized.startsWith("WEBVTT") || normalized.contains("WEBVTT\n")
        val lines = normalized.lines()
        val result = StringBuilder()

        var i = 0
        if (isVtt) {
            // Write WebVTT header up to the first cue
            while (i < lines.size) {
                val line = lines[i]
                if (line.contains("-->")) {
                    break
                }
                result.append(line).append("\n")
                if (line.isBlank() && i > 0 && lines[0].startsWith("WEBVTT")) {
                    i++
                    break
                }
                i++
            }
        }

        var cueNumber = 1
        while (i < lines.size) {
            // Skip empty lines between cues
            while (i < lines.size && lines[i].isBlank()) {
                i++
            }
            if (i >= lines.size) break

            var identifier: String? = null
            var timingLine: String? = null

            if (lines[i].contains("-->")) {
                timingLine = lines[i]
                i++
            } else if (i + 1 < lines.size && lines[i + 1].contains("-->")) {
                identifier = lines[i]
                timingLine = lines[i + 1]
                i += 2
            } else {
                i++
                continue
            }

            val payloadLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("-->")) {
                payloadLines.add(lines[i])
                i++
            }

            val rawPayload = payloadLines.joinToString("\n")
            val filteredPayload = filterPlainText(rawPayload)

            // If the cue is purely SDH descriptions, omit it entirely from the output file
            if (!filteredPayload.isNullOrBlank()) {
                if (isVtt) {
                    if (identifier != null && !identifier.all { it.isDigit() }) {
                        result.append(identifier).append("\n")
                    }
                    result.append(timingLine).append("\n")
                    result.append(filteredPayload).append("\n\n")
                } else {
                    result.append(cueNumber++).append("\n")
                    result.append(timingLine).append("\n")
                    result.append(filteredPayload).append("\n\n")
                }
            }
        }

        return result.toString().trimEnd() + "\n"
    }

    private fun filterAssSubtitleFile(text: String): String {
        val lines = text.lines()
        val result = StringBuilder()
        for (line in lines) {
            if (line.startsWith("Dialogue:", ignoreCase = true) || line.startsWith("Comment:", ignoreCase = true)) {
                val parts = line.split(",", limit = 10)
                if (parts.size == 10) {
                    val prefix = parts.subList(0, 9).joinToString(",") + ","
                    val rawText = parts[9]
                    val plainText = rawText.replace("\\N", "\n").replace("\\n", "\n")
                    val filtered = filterPlainText(plainText)
                    if (filtered.isNullOrBlank()) {
                        // Drop pure SDH cue
                        continue
                    }
                    val assPayload = filtered.replace("\n", "\\N")
                    result.append(prefix).append(assPayload).append("\n")
                    continue
                }
            }
            result.append(line).append("\n")
        }
        return result.toString()
    }
}

internal class SdhFilteringTextOutput(
    private val delegate: TextOutput,
    private val enabled: () -> Boolean
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        if (!enabled()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(
            CueGroup(SubtitleSdhFilter.filterCues(cueGroup.cues), cueGroup.presentationTimeUs)
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(if (enabled()) SubtitleSdhFilter.filterCues(cues) else cues)
    }
}
