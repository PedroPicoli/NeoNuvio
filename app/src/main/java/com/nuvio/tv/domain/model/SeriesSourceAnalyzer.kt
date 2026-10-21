package com.nuvio.tv.domain.model

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.util.Locale

data class VerifiedSource(
    val id: String,
    val addonName: String,
    val title: String,
    val quality: String?,
    val languages: List<String>,
    val sampleStream: Stream,
    val season: Int? = null,
    val episode: Int? = null
)

data class VerifiedAddonGroup(
    val addonName: String,
    val sources: List<VerifiedSource>
)

data class LanguageOption(
    val code: String,
    val label: String
)

object SeriesSourceAnalyzer {

    val ALL_SUPPORTED_LANGUAGES = listOf(
        LanguageOption("pt", "Português"),
        LanguageOption("en", "Inglês"),
        LanguageOption("es", "Espanhol"),
        LanguageOption("ja", "Japonês"),
        LanguageOption("fr", "Francês"),
        LanguageOption("de", "Alemão"),
        LanguageOption("it", "Italiano"),
        LanguageOption("ko", "Coreano"),
        LanguageOption("zh", "Chinês"),
        LanguageOption("ru", "Russo")
    )

    fun getLanguageLabel(code: String): String {
        return ALL_SUPPORTED_LANGUAGES.firstOrNull { it.code.equals(code, ignoreCase = true) }?.label
            ?: code.uppercase(Locale.ROOT)
    }

    /**
     * Analisa as fontes de um episódio da série, retornando as fontes
     * agrupadas por add-on, com qualidade e idiomas detectados.
     */
    fun analyzeEpisodeSources(
        episodeStreams: List<AddonStreams>,
        season: Int? = null,
        episode: Int? = null
    ): List<VerifiedAddonGroup> {
        val result = mutableListOf<VerifiedAddonGroup>()

        for (group in episodeStreams) {
            val addonName = group.addonName
            val verifiedSources = mutableListOf<VerifiedSource>()
            val seenKeys = mutableSetOf<String>()

            for (s in group.streams) {
                val sKey = extractSourceKey(s)
                if (sKey in seenKeys) continue
                seenKeys.add(sKey)

                val langs = detectLanguages(s).toList()
                val serverName = extractDisplayServerName(s)
                val baseTitle = s.title?.takeIf { it.isNotBlank() } ?: s.getDisplayNameOrNull() ?: s.name ?: sKey
                val displayTitle = if (!serverName.isNullOrBlank() && !baseTitle.contains(serverName, ignoreCase = true)) {
                    "$baseTitle • $serverName"
                } else {
                    baseTitle
                }
                val quality = s.quality ?: s.behaviorHints?.videoSize?.let { formatFileSize(it) }

                verifiedSources.add(
                    VerifiedSource(
                        id = sKey,
                        addonName = addonName,
                        title = displayTitle,
                        quality = quality,
                        languages = langs,
                        sampleStream = s,
                        season = season,
                        episode = episode
                    )
                )
            }

            if (verifiedSources.isNotEmpty()) {
                result.add(
                    VerifiedAddonGroup(
                        addonName = addonName,
                        sources = verifiedSources
                    )
                )
            }
        }

        return result
    }

    fun analyzeMatchingSources(
        firstEpisodeStreams: List<AddonStreams>,
        lastEpisodeStreams: List<AddonStreams> = emptyList()
    ): List<VerifiedAddonGroup> {
        return analyzeEpisodeSources(firstEpisodeStreams)
    }

    fun isMatchingSource(s1: Stream, s2: Stream): Boolean {
        val h1 = s1.getEffectiveInfoHash()
        val h2 = s2.getEffectiveInfoHash()
        // Se ambos possuem infoHash, só são correspondentes se o hash for idêntico (pack completo)
        if (!h1.isNullOrBlank() && !h2.isNullOrBlank() && h1.equals(h2, ignoreCase = true)) {
            return true
        }

        // 2. Mesmo bingeGroup (atribuído por addons para a mesma release/encoder)
        val bg1 = s1.behaviorHints?.bingeGroup?.trim()
        val bg2 = s2.behaviorHints?.bingeGroup?.trim()
        if (!bg1.isNullOrBlank() && !bg2.isNullOrBlank() && bg1.equals(bg2, ignoreCase = true)) {
            return true
        }

        // 3. Normalização de assinatura de release
        val sig1 = normalizeReleaseSignature(s1)
        val sig2 = normalizeReleaseSignature(s2)
        if (sig1.isNotBlank() && sig2.isNotBlank()) {
            return sig1 == sig2 || sig1.contains(sig2) || sig2.contains(sig1)
        }

        return false
    }

    data class ReleaseFingerprint(
        val group: String?,
        val resolution: String?,
        val codec: String?,
        val qualitySource: String?,
        val isDualOrDubbed: Boolean,
        val cleanTokens: Set<String>,
        val addonName: String?,
        val bingeGroup: String?,
        val infoHash: String?,
        val serverTags: Set<String> = emptySet(),
        val exactAgnosticSignature: String = ""
    )

    fun extractFingerprint(stream: Stream): ReleaseFingerprint {
        val fullCombinedText = listOfNotNull(stream.name, stream.title, stream.description)
            .filter { it.isNotBlank() }
            .joinToString(" \n ")

        val group = extractReleaseGroup(fullCombinedText)
        val resolution = extractResolution(fullCombinedText)
        val codec = extractCodec(fullCombinedText)
        val qualitySource = extractQualitySource(fullCombinedText)
        val isDualOrDubbed = isDualOrDubbed(fullCombinedText)
        val tokens = extractSignificantTokens(fullCombinedText)
        val serverTags = extractServerTags(fullCombinedText)
        val exactAgnosticSignature = extractAgnosticSignature(fullCombinedText)

        return ReleaseFingerprint(
            group = group,
            resolution = resolution,
            codec = codec,
            qualitySource = qualitySource,
            isDualOrDubbed = isDualOrDubbed,
            cleanTokens = tokens,
            addonName = stream.addonName.takeIf { it.isNotBlank() },
            bingeGroup = stream.behaviorHints?.bingeGroup?.trim()?.takeIf { it.isNotBlank() },
            infoHash = stream.getEffectiveInfoHash()?.trim()?.takeIf { it.isNotBlank() },
            serverTags = serverTags,
            exactAgnosticSignature = exactAgnosticSignature
        )
    }

    fun extractSourceKey(stream: Stream): String {
        val fp = extractFingerprint(stream)
        val parts = mutableListOf<String>()
        parts.add("fp:v3")
        fp.infoHash?.let { parts.add("hash=$it") }
        fp.bingeGroup?.let { parts.add("bg=$it") }
        fp.addonName?.let { parts.add("addon=$it") }
        fp.group?.let { parts.add("grp=$it") }
        fp.resolution?.let { parts.add("res=$it") }
        fp.codec?.let { parts.add("cdc=$it") }
        if (fp.isDualOrDubbed) parts.add("dual=1")
        if (fp.serverTags.isNotEmpty()) {
            parts.add("srv=" + fp.serverTags.joinToString(","))
        }
        if (fp.exactAgnosticSignature.isNotBlank()) {
            val safeSig = fp.exactAgnosticSignature.replace("|", "/").take(150)
            parts.add("esig=$safeSig")
        }
        if (fp.cleanTokens.isNotEmpty()) {
            parts.add("tok=" + fp.cleanTokens.joinToString(","))
        }
        return parts.joinToString("|")
    }

    fun matchesSource(stream: Stream, sourceKey: String): Boolean {
        if (sourceKey.isBlank()) return false
        val cleanKey = sourceKey.trim()

        val streamFp = extractFingerprint(stream)

        // 1. Checagem direta de InfoHash (caso seja torrent season pack comum para todos episódios)
        val h = streamFp.infoHash
        if (!h.isNullOrBlank()) {
            if (cleanKey.contains("hash=$h", ignoreCase = true) || h.equals(cleanKey, ignoreCase = true)) {
                return true
            }
        }

        // 2. Checagem direta de BingeGroup (se o add-on gerou o mesmo bingeGroup para a série)
        val bg = streamFp.bingeGroup
        if (!bg.isNullOrBlank()) {
            if (cleanKey.contains("bg=$bg", ignoreCase = true) || bg.equals(cleanKey, ignoreCase = true)) {
                return true
            }
        }

        // 3. Chave estruturada fp:v3, fp:v2 ou fp:v1
        if (cleanKey.startsWith("fp:v3") || cleanKey.startsWith("fp:v2") || cleanKey.startsWith("fp:v1")) {
            val keyMap = cleanKey.split("|")
                .drop(1)
                .mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
                }
                .toMap()

            val targetGroup = keyMap["grp"]
            val targetRes = keyMap["res"]
            val targetCodec = keyMap["cdc"]
            val targetSrv = keyMap["srv"]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val targetEsig = keyMap["esig"]?.trim()
            val targetTokens = keyMap["tok"]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()

            // 3a. Se a chave alvo especificou servidor/host, exige correspondência do servidor
            val targetAddon = keyMap["addon"]
            val addonMatches = targetAddon.isNullOrBlank() || streamFp.addonName.isNullOrBlank() ||
                    targetAddon.equals(streamFp.addonName, ignoreCase = true)

            var serverMatches = true
            if (targetSrv.isNotEmpty()) {
                val candidateSrv = streamFp.serverTags
                val commonSrv = targetSrv.intersect(candidateSrv)
                if (commonSrv.isNotEmpty()) {
                    serverMatches = true
                } else {
                    // Confere se alguma das palavras do servidor alvo está presente no texto do stream candidato
                    val lowerCandidateText = stream.let {
                        listOfNotNull(it.name, it.title, it.description).joinToString(" ").lowercase(Locale.ROOT)
                    }
                    val foundWord = targetSrv.any { srvTag ->
                        val plainWord = srvTag.removePrefix("srv_").removePrefix("host_").lowercase(Locale.ROOT)
                        plainWord.isNotBlank() && plainWord.length >= 2 && (
                            lowerCandidateText.contains(plainWord) ||
                            streamFp.cleanTokens.contains(plainWord) ||
                            streamFp.exactAgnosticSignature.contains(plainWord)
                        )
                    }
                    serverMatches = foundWord
                }
                if (!serverMatches) {
                    return false
                }
                // Se ambos possuem tags explícitas de servidor (ex: srv_1 vs srv_2) e nenhuma coincide, rejeita
                val explicitTargetSrv = targetSrv.filter { it.startsWith("srv_") || it.startsWith("host_") }.toSet()
                val explicitCandidateSrv = candidateSrv.filter { it.startsWith("srv_") || it.startsWith("host_") }.toSet()
                if (explicitTargetSrv.isNotEmpty() && explicitCandidateSrv.isNotEmpty() && explicitTargetSrv.intersect(explicitCandidateSrv).isEmpty()) {
                    return false
                }
            }

            // Se ambos especificaram grupo de release e são diferentes (ex: PSA vs FLUX), rejeita
            if (!targetGroup.isNullOrBlank() && !streamFp.group.isNullOrBlank() && !targetGroup.equals(streamFp.group, ignoreCase = true)) {
                return false
            }

            // Se ambos especificaram resolução e são diferentes (ex: 1080p vs 720p), rejeita
            if (!targetRes.isNullOrBlank() && !streamFp.resolution.isNullOrBlank() && !targetRes.equals(streamFp.resolution, ignoreCase = true)) {
                return false
            }

            // Se o add-on coincide, e (servidor coincide com alvo ou grupo de release coincide com alvo) e a resolução coincide: é a mesma fonte!
            if (addonMatches) {
                val resMatches = targetRes.isNullOrBlank() || streamFp.resolution.isNullOrBlank() ||
                        targetRes.equals(streamFp.resolution, ignoreCase = true)
                val groupMatches = !targetGroup.isNullOrBlank() && targetGroup.equals(streamFp.group, ignoreCase = true)
                val srvMatches = targetSrv.isNotEmpty() && serverMatches
                if (resMatches && (groupMatches || srvMatches)) {
                    return true
                }
            }

            // 3b. Correspondência exata da assinatura agnóstica de episódio (maior precisão de palavras)
            if (!targetEsig.isNullOrBlank()) {
                val cleanCandSig = streamFp.exactAgnosticSignature.replace("/", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
                val cleanTargetSig = targetEsig.replace("/", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
                if (cleanCandSig.isNotBlank() && cleanTargetSig.isNotBlank()) {
                    if (cleanCandSig == cleanTargetSig ||
                        cleanCandSig.startsWith(cleanTargetSig) ||
                        cleanTargetSig.startsWith(cleanCandSig)
                    ) {
                        return true
                    }
                }
                if (addonMatches) {
                    val candTitleAgnostic = extractAgnosticSignature(
                        listOfNotNull(stream.getDisplayNameOrNull(), stream.name, stream.title).joinToString(" ")
                    ).replace("/", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
                    if (candTitleAgnostic.isNotBlank() && cleanTargetSig.isNotBlank()) {
                        if (candTitleAgnostic == cleanTargetSig) {
                            return true
                        }
                    }
                }
            }

            // 3c. Se temos Release Group reconhecido e coincide
            if (!targetGroup.isNullOrBlank() && !streamFp.group.isNullOrBlank()) {
                if (targetGroup.equals(streamFp.group, ignoreCase = true)) {
                    if (targetRes.isNullOrBlank() || streamFp.resolution.isNullOrBlank() || targetRes.equals(streamFp.resolution, ignoreCase = true)) {
                        return true
                    }
                }
            }

            // 3d. Se os tokens da release tiverem alta sobreposição
            if (targetTokens.isNotEmpty() && streamFp.cleanTokens.isNotEmpty()) {
                val intersection = targetTokens.intersect(streamFp.cleanTokens).size
                val union = targetTokens.union(streamFp.cleanTokens).size
                if (union > 0) {
                    val similarity = intersection.toDouble() / union.toDouble()
                    if (similarity >= 0.4) {
                        if (targetRes.isNullOrBlank() || streamFp.resolution.isNullOrBlank() || targetRes.equals(streamFp.resolution, ignoreCase = true)) {
                            return true
                        }
                    }
                }
            }
        }

        // 4. Fallback legado de assinatura normalizada
        val targetSig = if (cleanKey.startsWith("sig:")) {
            cleanKey.removePrefix("sig:").trim()
        } else {
            normalizeReleaseSignature(cleanKey)
        }
        val streamSig = normalizeReleaseSignature(stream)

        if (targetSig.isNotBlank() && streamSig.isNotBlank()) {
            if (streamSig == targetSig) return true
            if (streamSig.contains(targetSig) || targetSig.contains(streamSig)) return true
        }

        val title = listOfNotNull(stream.title, stream.name, stream.description).joinToString(" ")
        if (title.isNotBlank()) {
            val normTitle = normalizeReleaseSignature(title)
            if (normTitle.isNotBlank() && targetSig.isNotBlank()) {
                if (normTitle == targetSig || normTitle.contains(targetSig) || targetSig.contains(normTitle)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Calcula pontuação de afinidade entre o stream candidato e a chave de fonte preferida.
     * Quanto maior a pontuação, mais precisa é a correspondência (desempate para autoplay).
     */
    fun matchScore(stream: Stream, sourceKey: String): Int {
        if (sourceKey.isBlank()) return 0
        val cleanKey = sourceKey.trim()
        val streamFp = extractFingerprint(stream)

        var score = 0
        if (!streamFp.infoHash.isNullOrBlank() && cleanKey.contains("hash=${streamFp.infoHash}", ignoreCase = true)) {
            score += 2000
        }
        if (!streamFp.bingeGroup.isNullOrBlank() && cleanKey.contains("bg=${streamFp.bingeGroup}", ignoreCase = true)) {
            score += 1500
        }

        val keyMap = cleanKey.split("|")
            .drop(1)
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
            }
            .toMap()

        val targetEsig = keyMap["esig"]?.trim()
        if (!targetEsig.isNullOrBlank() && streamFp.exactAgnosticSignature.isNotBlank()) {
            val cleanCandSig = streamFp.exactAgnosticSignature.replace("/", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
            val cleanTargetSig = targetEsig.replace("/", " ").replace("|", " ").replace(Regex("\\s+"), " ").trim()
            if (cleanCandSig == cleanTargetSig) {
                score += 1000
            } else if (cleanCandSig.startsWith(cleanTargetSig) || cleanTargetSig.startsWith(cleanCandSig)) {
                score += 800
            }
        }

        val targetAddon = keyMap["addon"]
        if (!targetAddon.isNullOrBlank() && targetAddon.equals(streamFp.addonName, ignoreCase = true)) {
            score += 600
        }

        val targetSrv = keyMap["srv"]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
        if (targetSrv.isNotEmpty()) {
            val candidateSrv = streamFp.serverTags
            val commonSrv = targetSrv.intersect(candidateSrv).size
            if (commonSrv > 0) {
                score += commonSrv * 10000
            } else {
                val lowerCandidateText = stream.let {
                    listOfNotNull(it.name, it.title, it.description).joinToString(" ").lowercase(Locale.ROOT)
                }
                val hasWord = targetSrv.any { srvTag ->
                    val plainWord = srvTag.removePrefix("srv_").removePrefix("host_").lowercase(Locale.ROOT)
                    plainWord.length >= 2 && (
                        lowerCandidateText.contains(plainWord) ||
                        streamFp.cleanTokens.contains(plainWord)
                    )
                }
                if (hasWord) {
                    score += 5000
                } else if (candidateSrv.isNotEmpty()) {
                    score -= 10000
                }
            }
        }

        val targetGroup = keyMap["grp"]
        if (!targetGroup.isNullOrBlank() && targetGroup.equals(streamFp.group, ignoreCase = true)) {
            score += 400
        }

        val targetRes = keyMap["res"]
        if (!targetRes.isNullOrBlank() && targetRes.equals(streamFp.resolution, ignoreCase = true)) {
            score += 500
        }

        val targetCodec = keyMap["cdc"]
        if (!targetCodec.isNullOrBlank() && targetCodec.equals(streamFp.codec, ignoreCase = true)) {
            score += 100
        }

        val targetTokens = keyMap["tok"]?.split(",")?.filter { it.isNotBlank() }?.toSet().orEmpty()
        if (targetTokens.isNotEmpty() && streamFp.cleanTokens.isNotEmpty()) {
            val intersection = targetTokens.intersect(streamFp.cleanTokens).size
            score += intersection * 30
        }

        return score
    }

    fun streamMatchesLanguage(stream: Stream, preferredLanguage: String?): Boolean {
        if (preferredLanguage.isNullOrBlank()) return true
        val langs = detectLanguages(stream)
        return langs.any { it.equals(preferredLanguage, ignoreCase = true) }
    }

    fun normalizeReleaseSignature(stream: Stream): String {
        val titleText = stream.title?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }
            ?: stream.description.orEmpty()
        return normalizeReleaseSignature(titleText)
    }

    fun normalizeReleaseSignature(rawText: String): String {
        if (rawText.isBlank()) return ""
        return rawText
            .lines()
            .filterNot { line ->
                val l = line.lowercase(Locale.ROOT)
                l.contains("💾") || l.contains("👤") || l.contains("👥") ||
                    l.contains("seeders") || l.contains("peers") || l.contains("leechers")
            }
            .joinToString(" ")
            .replace(Regex("(?i)\\b\\d+(\\.\\d+)?\\s*(gb|mb|gib|mib|kb)\\b"), "")
            .replace(Regex("(?i)\\b\\d+\\s*(seeders?|seeds?|peers?|leechers?)\\b"), "")
            .replace(Regex("(?i)\\b(s\\d{1,2}\\s*e\\d{1,3}|t\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|\\d+ª\\s*temporada|episode\\s*\\d+|episodio\\s*\\d+|episódio\\s*\\d+|capitulo\\s*\\d+|ep\\.?\\s*\\d+|e\\d{1,3})\\b"), "")
            .replace(Regex("(?i)\\.(mkv|mp4|avi|ts|m4v)\\b"), "")
            .replace(Regex("[._\\-]"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun extractReleaseGroup(text: String): String? {
        if (text.isBlank()) return null
        val clean = text.replace(Regex("(?i)\\.(mkv|mp4|avi|ts|m4v)\\b"), "")

        // Padrão Scene no final: "-NOME" (ex: "-PSA", "-FLUX", "-RARBG", "-TGx")
        val sceneMatch = Regex("(?i)-([a-zA-Z0-9]+)$").find(clean)
        if (sceneMatch != null) {
            val g = sceneMatch.groupValues[1]
            if (g.length in 2..15 && !g.equals("ita", ignoreCase = true) && !g.equals("eng", ignoreCase = true)) {
                return g.uppercase(Locale.ROOT)
            }
        }

        // Padrão Anime / Grupos entre colchetes: "[NOME]" (ex: "[SubsPlease]", "[Brazuca]")
        val bracketMatch = Regex("\\[([a-zA-Z0-9_\\-]+)\\]").find(clean)
        if (bracketMatch != null) {
            val g = bracketMatch.groupValues[1]
            if (g.length in 2..20 && !g.matches(Regex("(?i)\\d+p|hevc|x264|x265|dual.*|dublado.*"))) {
                return g.uppercase(Locale.ROOT)
            }
        }

        // Grupos conhecidos
        val knownGroups = listOf(
            "PSA", "RARBG", "TGx", "FLUX", "MeGusta", "Brazuca", "PicBR", "GalaxyTV",
            "VXT", "YIFY", "Pahe", "ION10", "EZTV", "ETRG", "NTb", "CasStudio", "Lapuma",
            "Tigole", "QxR", "Judas", "EMBER", "SubsPlease", "Erai-raws", "Comando"
        )
        for (kg in knownGroups) {
            if (Regex("(?i)\\b${Regex.escape(kg)}\\b").containsMatchIn(clean)) {
                return kg.uppercase(Locale.ROOT)
            }
        }
        return null
    }

    private fun extractResolution(text: String): String? {
        val match = Regex("(?i)\\b(2160p|4k|1080p|720p|480p)\\b").find(text)
        return match?.value?.lowercase(Locale.ROOT)
    }

    private fun extractCodec(text: String): String? {
        val match = Regex("(?i)\\b(x265|hevc|h265|x264|h264|avc|10bit)\\b").find(text)
        return match?.value?.lowercase(Locale.ROOT)
    }

    private fun extractQualitySource(text: String): String? {
        val match = Regex("(?i)\\b(bluray|bdrip|web-dl|webdl|webrip|hdtv|dvdrip)\\b").find(text)
        return match?.value?.lowercase(Locale.ROOT)
    }

    private fun isDualOrDubbed(text: String): Boolean {
        val t = text.lowercase(Locale.ROOT)
        return t.contains("dual") || t.contains("dublado") || t.contains("multi") || t.contains("nacional")
    }

    private fun extractSignificantTokens(text: String): Set<String> {
        val normalized = normalizeReleaseSignature(text)
        if (normalized.isBlank()) return emptySet()
        val stopWords = setOf("the", "and", "season", "episode", "completo", "series", "serie", "tv")
        return normalized.split(" ")
            .filter { it.length >= 3 && it !in stopWords }
            .toSet()
    }

    fun extractDisplayServerName(stream: Stream): String? {
        val allLines = listOfNotNull(stream.description, stream.title, stream.name)
            .flatMap { it.lines() }
            .map { it.trim() }
            .filter { it.isNotBlank() }

        for (line in allLines) {
            val lower = line.lowercase(Locale.ROOT)
            if (line.contains("⚡") ||
                lower.startsWith("servidor") || lower.startsWith("server") ||
                lower.startsWith("host") || lower.startsWith("opção") || lower.startsWith("opcao")
            ) {
                val cleaned = line
                    .replace(Regex("(?i)\\b(dublado|legendado|dual|multi|1080p|720p|4k|full hd)\\b"), "")
                    .replace(Regex("[🇧🇷🇺🇸🇬🇧]"), "")
                    .trim()
                if (cleaned.isNotBlank() && cleaned.length in 2..40) {
                    return cleaned
                }
            }
        }
        return null
    }

    fun extractServerTags(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val tags = mutableSetOf<String>()
        val lower = text.lowercase(Locale.ROOT)
        val stopWords = setOf("servidor", "server", "host", "opcao", "opção", "dublado", "legendado", "dual", "multi", "audio", "full", "hd")

        // 1. Emoji ⚡ seguido de qualquer texto na linha (ex: "⚡ Servidor Zara", "⚡ Zara", "⚡ Opção 1")
        val emojiLineRegex = Regex("⚡\\s*([^\\n\\r]+)")
        emojiLineRegex.findAll(text).forEach { match ->
            val line = match.groupValues[1].lowercase(Locale.ROOT)
            val tokens = line.split(Regex("[^a-zA-Z0-9]+")).filter { it.length >= 1 }
            val meaningful = tokens.filterNot { it in stopWords }
            meaningful.forEach {
                tags.add("srv_$it")
                tags.add(it)
            }
            if (meaningful.isNotEmpty()) {
                tags.add("srv_" + meaningful.joinToString("_"))
            }
        }

        // 2. Padrões de servidor ou opção ("servidor 1", "server x", "opcao 2", "servidor: zara", "⚡ Servidor Zara")
        val serverRegex = Regex("(?i)\\b(servidor|server|host|opção|opcao)\\s*[:\\-]?\\s*([a-zA-Z0-9_\\-]+)")
        serverRegex.findAll(lower).forEach { match ->
            val prefix = match.groupValues[1].replace("ção", "cao")
            val target = match.groupValues[2]
            if (target !in stopWords) {
                tags.add("${prefix}_$target")
                tags.add("srv_$target")
                tags.add(target)
            }
        }

        // 3. Tags Debrid explícitas
        if (lower.contains("rd+") || lower.contains("[rd+]") || lower.contains("realdebrid")) tags.add("debrid_rd")
        if (lower.contains("ad+") || lower.contains("[ad+]") || lower.contains("alldebrid")) tags.add("debrid_ad")
        if (lower.contains("pm+") || lower.contains("[pm+]") || lower.contains("premiumize")) tags.add("debrid_pm")

        // 4. Provedores / Servidores conhecidos
        val knownHosts = listOf(
            "mega", "gdrive", "google drive", "fembed", "mixdrop", "streamtape",
            "dood", "doodstream", "filemoon", "upstream", "vidoza", "streamwish",
            "dropload", "vidlox", "rapidvideo", "openload", "netu", "direct", "hls"
        )
        for (host in knownHosts) {
            if (Regex("(?i)\\b${Regex.escape(host)}\\b").containsMatchIn(lower)) {
                val cleanHost = host.replace(" ", "_")
                tags.add("host_$cleanHost")
                tags.add("srv_$cleanHost")
            }
        }

        return tags
    }

    fun matchesCurrentPlayingSource(
        candidate: Stream,
        currentAddonName: String?,
        currentDescription: String?,
        currentStreamName: String?
    ): Boolean {
        if (currentAddonName.isNullOrBlank() || !candidate.addonName.equals(currentAddonName, ignoreCase = true)) {
            return false
        }
        val currentCombined = listOfNotNull(currentStreamName, currentDescription).joinToString(" ")
        val currentSrv = extractServerTags(currentCombined)
        val candidateFp = extractFingerprint(candidate)

        if (currentSrv.isNotEmpty()) {
            val common = currentSrv.intersect(candidateFp.serverTags)
            if (common.isEmpty()) {
                val candText = listOfNotNull(candidate.name, candidate.title, candidate.description)
                    .joinToString(" ").lowercase(Locale.ROOT)
                val hasWord = currentSrv.any { tag ->
                    val word = tag.removePrefix("srv_").removePrefix("host_")
                    word.length >= 2 && candText.contains(word)
                }
                if (!hasWord) return false
            }
        }

        val currentRes = extractResolution(currentCombined)
        if (!currentRes.isNullOrBlank() && !candidateFp.resolution.isNullOrBlank()) {
            if (!currentRes.equals(candidateFp.resolution, ignoreCase = true)) {
                return false
            }
        }

        return true
    }

    fun matchesNormalized(streamName: String?, filename: String?, sourceKey: String): Boolean {
        if (sourceKey.isBlank()) return false
        val cleanKey = sourceKey.trim()
        val keyMap = cleanKey.split("|")
            .drop(1)
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
            }
            .toMap()
        val targetAddon = keyMap["addon"]
        val targetRes = keyMap["res"]
        val combined = listOfNotNull(streamName, filename).joinToString(" ").lowercase(Locale.ROOT)
        if (!targetAddon.isNullOrBlank() && !combined.contains(targetAddon.lowercase(Locale.ROOT))) {
            return false
        }
        if (!targetRes.isNullOrBlank() && !combined.contains(targetRes.lowercase(Locale.ROOT))) {
            return false
        }
        val targetSrv = keyMap["srv"]?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        if (targetSrv.isNotEmpty()) {
            val hasSrv = targetSrv.any { tag ->
                val w = tag.removePrefix("srv_").removePrefix("host_")
                w.length >= 2 && combined.contains(w)
            }
            if (!hasSrv) return false
        }
        return true
    }

    private fun extractAgnosticSignature(rawText: String): String {
        if (rawText.isBlank()) return ""
        return rawText
            .lines()
            .filterNot { line ->
                val l = line.lowercase(Locale.ROOT)
                l.contains("💾") || l.contains("👤") || l.contains("👥") ||
                    l.contains("seeders") || l.contains("peers") || l.contains("leechers")
            }
            .joinToString(" ")
            .replace(Regex("(?i)\\b\\d+(\\.\\d+)?\\s*(gb|mb|gib|mib|kb)\\b"), "")
            .replace(Regex("(?i)\\b\\d+\\s*(seeders?|seeds?|peers?|leechers?)\\b"), "")
            .replace(Regex("(?i)\\b(s\\d{1,2}\\s*e\\d{1,3}|t\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|season\\s*\\d+|temporada\\s*\\d+|\\d+ª\\s*temporada|episode\\s*\\d+|episodio\\s*\\d+|episódio\\s*\\d+|capitulo\\s*\\d+|ep\\.?\\s*\\d+|e\\d{1,3})\\b"), "")
            .replace(Regex("(?i)\\b(ep\\s*\\d+|e\\d{2,3})\\b"), "")
            .replace(Regex("(?i)\\.(mkv|mp4|avi|ts|m4v)\\b"), "")
            .replace(Regex("[._\\-]"), " ")
            .replace(Regex("[^a-zA-Z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    fun detectLanguages(stream: Stream): Set<String> {
        val languages = mutableSetOf<String>()

        // 1. Languages do clientResolve
        stream.clientResolve?.stream?.raw?.parsed?.languages?.forEach { lang: String ->
            mapLanguageToken(lang)?.let { languages.add(it) }
        }

        // 2. Badges
        stream.badges.forEach { badge ->
            mapLanguageToken(badge.name)?.let { languages.add(it) }
        }

        // 3. Texto geral (nome, título, descrição)
        val haystack = listOfNotNull(stream.name, stream.title, stream.description)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        if (haystack.contains("portugu") || haystack.contains("dublado") ||
            haystack.contains("pt-br") || haystack.contains("pt_br") || haystack.contains("pob") ||
            haystack.contains("brazilian") || haystack.contains("🇧🇷") || haystack.contains("🇵🇹")) {
            languages.add("pt")
        }
        if (haystack.contains("english") || haystack.contains("inglês") || haystack.contains("ingles") ||
            haystack.contains("eng") || haystack.contains("original") || haystack.contains("🇺🇸") || haystack.contains("🇬🇧")) {
            languages.add("en")
        }
        if (haystack.contains("spanish") || haystack.contains("espanhol") || haystack.contains("castellano") ||
            haystack.contains("latino") || haystack.contains("spa") || haystack.contains("🇪🇸") || haystack.contains("🇲🇽")) {
            languages.add("es")
        }
        if (haystack.contains("japanese") || haystack.contains("japonês") || haystack.contains("japones") ||
            haystack.contains("jpn") || haystack.contains("jap") || haystack.contains("🇯🇵")) {
            languages.add("ja")
        }
        if (haystack.contains("french") || haystack.contains("francês") || haystack.contains("frances") ||
            haystack.contains("fra") || haystack.contains("🇫🇷")) {
            languages.add("fr")
        }
        if (haystack.contains("german") || haystack.contains("alemão") || haystack.contains("alemao") ||
            haystack.contains("ger") || haystack.contains("deu") || haystack.contains("🇩🇪")) {
            languages.add("de")
        }
        if (haystack.contains("italian") || haystack.contains("italiano") ||
            haystack.contains("ita") || haystack.contains("🇮🇹")) {
            languages.add("it")
        }
        if (haystack.contains("korean") || haystack.contains("coreano") ||
            haystack.contains("kor") || haystack.contains("🇰🇷")) {
            languages.add("ko")
        }
        if (haystack.contains("chinese") || haystack.contains("chinês") || haystack.contains("chines") ||
            haystack.contains("chi") || haystack.contains("zho") || haystack.contains("🇨🇳")) {
            languages.add("zh")
        }
        if (haystack.contains("russian") || haystack.contains("russo") ||
            haystack.contains("rus") || haystack.contains("🇷🇺")) {
            languages.add("ru")
        }

        // Se encontrou "multi" ou "dual", adiciona idiomas prováveis da região (Português + Inglês por padrão)
        if (haystack.contains("dual") || haystack.contains("multi")) {
            languages.add("pt")
            languages.add("en")
        }

        return languages
    }

    private fun mapLanguageToken(token: String): String? {
        val t = token.trim().lowercase(Locale.ROOT)
        return when {
            t == "pt" || t == "por" || t == "pt-br" || t == "pob" || t == "portuguese" || t == "português" -> "pt"
            t == "en" || t == "eng" || t == "english" || t == "inglês" -> "en"
            t == "es" || t == "spa" || t == "spanish" || t == "espanhol" -> "es"
            t == "ja" || t == "jpn" || t == "japanese" || t == "japonês" -> "ja"
            t == "fr" || t == "fra" || t == "french" || t == "francês" -> "fr"
            t == "de" || t == "ger" || t == "deu" || t == "german" || t == "alemão" -> "de"
            t == "it" || t == "ita" || t == "italian" || t == "italiano" -> "it"
            t == "ko" || t == "kor" || t == "korean" || t == "coreano" -> "ko"
            t == "zh" || t == "chi" || t == "zho" || t == "chinese" || t == "chinês" -> "zh"
            t == "ru" || t == "rus" || t == "russian" || t == "russo" -> "ru"
            else -> null
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes.toDouble() / (1024 * 1024)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
