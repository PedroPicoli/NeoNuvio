package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.MetaCastMember
import java.text.Normalizer
import java.util.Locale

data class XRayCastMember(
    val member: MetaCastMember,
    val isInScene: Boolean = false,
    val isSpeakingNow: Boolean = false,
    val detectedTag: String? = null
)

object XRaySceneMatcher {

    // Regex seguro: usa apenas espaços horizontais para não consumir quebras de linha em falas com 2 pessoas
    private val SPEAKER_REGEX = Regex(
        """(?:\[([A-Za-zÀ-ÖØ-öø-ÿ0-9 .'-]{2,30})\]|\(([A-Za-zÀ-ÖØ-öø-ÿ0-9 .'-]{2,30})\)|(?:^|\n)(?:-\s*)?([A-Za-zÀ-ÖØ-öø-ÿ0-9 .'-]{2,30}):)"""
    )

    private val STOPWORDS = setOf(
        "não", "nao", "sim", "mas", "ola", "olá", "olha", "bem", "vamos", "ei", "hey",
        "well", "yes", "no", "what", "where", "how", "quando", "como", "porque", "por",
        "que", "para", "com", "ele", "ela", "eles", "elas", "você", "voce", "eu", "nos",
        "nós", "ok", "certo", "beleza", "então", "entao", "cara", "amigo", "gente", "pessoal",
        "the", "and", "or", "but", "so", "if", "then", "just", "now", "here", "there"
    )

    private val GENERIC_ROLE_TOKENS = setOf(
        "man", "woman", "guy", "girl", "boy", "doctor", "guard", "officer", "driver",
        "cop", "police", "soldier", "waiter", "host", "nurse", "pilot", "clerk", "agent",
        "homem", "mulher", "guarda", "medico", "médico", "policial", "motorista", "soldado",
        "garçom", "garcom", "enfermeira", "piloto", "agente", "padre", "pastor"
    )

    /**
     * Identifica e classifica os atores presentes ou em destaque na cena atual
     * com base nas legendas recentes e nos metadados do TMDB.
     */
    fun matchSceneCast(
        positionMs: Long,
        castMembers: List<MetaCastMember>,
        cues: List<SubtitleSyncCue>,
        activeSubtitleText: String? = null,
        recentWindowMs: Long = 25_000L
    ): List<XRayCastMember> {
        if (castMembers.isEmpty()) return emptyList()

        val windowStart = (positionMs - recentWindowMs).coerceAtLeast(0L)
        // Margem de tolerância estreita para evitar ler falas do futuro
        val windowEnd = positionMs + 500L

        // Cues que coincidem com o momento da pausa (com pequena tolerância de 200ms)
        val exactCues = cues.filter { positionMs in (it.startTimeMs - 200L)..(it.endTimeMs + 200L) }
        // Cues na janela recente da cena
        val recentCues = cues.filter { it.endTimeMs >= windowStart && it.startTimeMs <= windowEnd }

        val exactSpeakers = mutableSetOf<String>()
        val recentSpeakers = mutableSetOf<String>()
        val exactFullText = StringBuilder()
        val recentFullText = StringBuilder()

        if (!activeSubtitleText.isNullOrBlank()) {
            exactFullText.append(activeSubtitleText).append("\n")
            extractSpeakers(activeSubtitleText, exactSpeakers)
        }

        exactCues.forEach { cue ->
            exactFullText.append(cue.text).append("\n")
            extractSpeakers(cue.text, exactSpeakers)
        }

        recentCues.forEach { cue ->
            recentFullText.append(cue.text).append("\n")
            extractSpeakers(cue.text, recentSpeakers)
        }

        val exactTextStr = exactFullText.toString().trim()
        val recentTextStr = recentFullText.toString().trim()
        val normalizedExactText = normalizeText(exactTextStr)
        val normalizedRecentText = normalizeText(recentTextStr)

        // Detecta se há diálogo entre duas ou mais pessoas (ex: traço '-', quebras de linha com falas ou múltiplos falantes)
        val isDialogueCue = exactTextStr.contains("- ") ||
            exactTextStr.contains("– ") ||
            exactTextStr.contains("\n-") ||
            exactTextStr.contains("\n–") ||
            exactSpeakers.size >= 2 ||
            (exactTextStr.contains("\n") && exactTextStr.lines().count { it.trim().isNotBlank() } >= 2)

        val exactMatchedMembers = mutableSetOf<MetaCastMember>()
        val recentMatchedMembers = mutableSetOf<MetaCastMember>()

        // 1. Primeira passada: busca correspondência nominal (por tag de falante, vocativo ou menção no texto)
        castMembers.forEach { member ->
            val characterTokens = extractTokens(member.character)
            val actorTokens = extractTokens(member.name)
            val allTokens = (characterTokens + actorTokens).filter { it.length >= 3 }

            // Checagem exata: Falante identificado explicitamente por tag ou vocativo/menção na fala atual
            val exactSpeakerMatch = exactSpeakers.firstOrNull { spk -> matchesToken(spk, allTokens) }
            val mentionedInExactText = allTokens.any { token -> containsWord(normalizedExactText, token) }

            if (exactSpeakerMatch != null || (isDialogueCue && mentionedInExactText) || (mentionedInExactText && exactCues.isNotEmpty())) {
                exactMatchedMembers.add(member)
            }

            // Checagem recente: Falante ou menção nos últimos 25 segundos
            val recentSpeakerMatch = recentSpeakers.firstOrNull { spk -> matchesToken(spk, allTokens) }
            val mentionedInRecentText = allTokens.any { token -> containsWord(normalizedRecentText, token) }

            if (recentSpeakerMatch != null || mentionedInRecentText) {
                recentMatchedMembers.add(member)
            }
        }

        // 2. Segunda passada: Quando há diálogo na cena, garante que os atores principais da história
        // apareçam como presentes na cena ("Na cena" / isInScene), mas NUNCA marca falsamente
        // como "Falando agora" quem não foi identificado nominalmente.
        val hasExactDialogue = exactTextStr.isNotBlank() || exactCues.isNotEmpty()
        val hasRecentDialogue = recentTextStr.isNotBlank() || recentCues.isNotEmpty()

        if (hasExactDialogue || hasRecentDialogue) {
            val desiredSceneCount = if (hasExactDialogue) 3 else 2
            val currentTotalInScene = exactMatchedMembers.size + recentMatchedMembers.size
            if (currentTotalInScene < desiredSceneCount) {
                castMembers.forEach { member ->
                    if ((exactMatchedMembers.size + recentMatchedMembers.size) < desiredSceneCount &&
                        member !in exactMatchedMembers &&
                        member !in recentMatchedMembers
                    ) {
                        recentMatchedMembers.add(member)
                    }
                }
            }
        }

        val result = castMembers.map { member ->
            val isSpeakingNow = member in exactMatchedMembers
            val isInScene = isSpeakingNow || member in recentMatchedMembers

            val characterTokens = extractTokens(member.character)
            val actorTokens = extractTokens(member.name)
            val allTokens = (characterTokens + actorTokens).filter { it.length >= 3 }
            val exactSpeakerMatch = exactSpeakers.firstOrNull { spk -> matchesToken(spk, allTokens) }
            val recentSpeakerMatch = recentSpeakers.firstOrNull { spk -> matchesToken(spk, allTokens) }
            val detectedTag = exactSpeakerMatch ?: recentSpeakerMatch

            XRayCastMember(
                member = member,
                isInScene = isInScene,
                isSpeakingNow = isSpeakingNow,
                detectedTag = detectedTag
            )
        }

        // Ordena: Quem está falando agora primeiro, depois quem está na cena, depois o resto do elenco
        return result.sortedWith(
            compareByDescending<XRayCastMember> { it.isSpeakingNow }
                .thenByDescending { it.isInScene }
        )
    }

    private fun extractSpeakers(text: String, output: MutableSet<String>) {
        // Extrai tags com colons ou colchetes: [Rick], (Rick), Rick:, - Rick:
        SPEAKER_REGEX.findAll(text).forEach { match ->
            val speaker = match.groups[1]?.value
                ?: match.groups[2]?.value
                ?: match.groups[3]?.value
            if (!speaker.isNullOrBlank()) {
                val clean = speaker.trim().removeSurrounding("\"").removeSurrounding("'")
                val norm = normalizeText(clean)
                if (clean.length in 2..30 && !clean.contains("-->") && norm !in STOPWORDS) {
                    output.add(clean)
                }
            }
        }

        // Para falas de diálogo com traço (ex: "- Rick, você viu aquilo?"), extrai vocativos reais seguidos de vírgula ou dois pontos
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("-") || trimmed.startsWith("–")) {
                val content = trimmed.removePrefix("-").removePrefix("–").trim()
                val separatorIndex = content.indexOfAny(charArrayOf(',', ':'))
                if (separatorIndex in 2..25) {
                    val potentialName = content.substring(0, separatorIndex).trim()
                    val norm = normalizeText(potentialName)
                    if (potentialName.all { it.isLetter() || it == ' ' || it == '\'' || it == '-' } &&
                        norm !in STOPWORDS &&
                        norm.split(' ').size <= 2
                    ) {
                        output.add(potentialName)
                    }
                }
            }
        }
    }

    private fun extractTokens(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        val normalized = normalizeText(name)
        val tokens = mutableListOf<String>()
        // Adiciona a string inteira
        tokens.add(normalized)
        // Divide por "/", "(", ")", "," para casos como "Maya Lopez / Echo"
        name.split('/', '(', ')', ',').forEach { part ->
            val normPart = normalizeText(part.trim())
            if (normPart.isNotBlank() && normPart !in GENERIC_ROLE_TOKENS) {
                tokens.add(normPart)
            }
        }
        // Adiciona palavras individuais com mais de 3 letras que não sejam genéricas nem stopwords
        normalized.split(' ')
            .filter { it.length >= 3 && it !in GENERIC_ROLE_TOKENS && it !in STOPWORDS }
            .forEach { word ->
                tokens.add(word)
            }
        return tokens.distinct()
    }

    private fun matchesToken(speaker: String, candidateTokens: List<String>): Boolean {
        val normSpeaker = normalizeText(speaker)
        return candidateTokens.any { token ->
            normSpeaker == token ||
                normSpeaker.startsWith("$token ") ||
                normSpeaker.endsWith(" $token") ||
                normSpeaker.contains(" $token ")
        }
    }

    private fun containsWord(fullText: String, word: String): Boolean {
        if (word.isBlank() || fullText.isBlank() || word in STOPWORDS || word in GENERIC_ROLE_TOKENS) {
            return false
        }
        val regex = Regex("""\b${Regex.escape(word)}\b""")
        return regex.containsMatchIn(fullText)
    }

    private fun normalizeText(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
