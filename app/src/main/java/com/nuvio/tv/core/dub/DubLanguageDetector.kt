package com.nuvio.tv.core.dub

import com.nuvio.tv.domain.model.Stream
import java.util.Locale

object DubLanguageDetector {

    /**
     * Normaliza código de idioma para formato padronizado (ex: "pt", "en", "es", etc.).
     */
    fun normalizeLanguageCode(code: String?): String {
        if (code.isNullOrBlank()) return ""
        val cleaned = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        val base = cleaned.substringBefore('-')
        return when (base) {
            "pt", "por", "pob", "portuguese", "português", "portugues" -> "pt"
            "en", "eng", "english", "inglês", "ingles" -> "en"
            "es", "spa", "spanish", "espanhol", "castellano", "latino" -> "es"
            "fr", "fra", "fre", "french", "francês", "frances" -> "fr"
            "de", "ger", "deu", "german", "alemão", "alemao" -> "de"
            "it", "ita", "italian", "italiano" -> "it"
            "ja", "jpn", "japanese", "japonês", "japones" -> "ja"
            "ko", "kor", "korean", "coreano" -> "ko"
            "zh", "chi", "zho", "chinese", "chinês", "chines" -> "zh"
            "ru", "rus", "russian", "russo" -> "ru"
            else -> base
        }
    }

    /**
     * Detecta os idiomas contidos no Stream combinando clientResolve, badges e texto com limites de palavra.
     */
    fun detectLanguages(stream: Stream): Set<String> {
        val languages = mutableSetOf<String>()

        // 1. Languages do clientResolve
        stream.clientResolve?.stream?.raw?.parsed?.languages?.forEach { lang ->
            val norm = normalizeLanguageCode(lang)
            if (norm.isNotBlank()) languages.add(norm)
        }

        // 2. Badges
        stream.badges.forEach { badge ->
            val norm = normalizeLanguageCode(badge.name)
            if (norm.isNotBlank()) languages.add(norm)
        }

        // 3. Analisa texto (nome, título, descrição, filename) usando regex com bordas de palavra
        val textParts = listOfNotNull(
            stream.name,
            stream.title,
            stream.description,
            stream.behaviorHints?.filename
        )
        val text = textParts.joinToString(" ").lowercase(Locale.ROOT)

        if (matchesPattern(text, PORTUGUESE_PATTERNS) || text.contains("🇧🇷") || text.contains("🇵🇹")) {
            languages.add("pt")
        }
        if (matchesPattern(text, ENGLISH_PATTERNS) || text.contains("🇺🇸") || text.contains("🇬🇧")) {
            languages.add("en")
        }
        if (matchesPattern(text, SPANISH_PATTERNS) || text.contains("🇪🇸") || text.contains("🇲🇽")) {
            languages.add("es")
        }
        if (matchesPattern(text, FRENCH_PATTERNS) || text.contains("🇫🇷")) {
            languages.add("fr")
        }
        if (matchesPattern(text, GERMAN_PATTERNS) || text.contains("🇩🇪")) {
            languages.add("de")
        }
        if (matchesPattern(text, ITALIAN_PATTERNS) || text.contains("🇮🇹")) {
            languages.add("it")
        }
        if (matchesPattern(text, JAPANESE_PATTERNS) || text.contains("🇯🇵")) {
            languages.add("ja")
        }
        if (matchesPattern(text, KOREAN_PATTERNS) || text.contains("🇰🇷")) {
            languages.add("ko")
        }
        if (matchesPattern(text, CHINESE_PATTERNS) || text.contains("🇨🇳")) {
            languages.add("zh")
        }
        if (matchesPattern(text, RUSSIAN_PATTERNS) || text.contains("🇷🇺")) {
            languages.add("ru")
        }

        // "dual", "multi" indica múltiplos idiomas; se houver termo nacional/dublado junto, inclui pt
        if (matchesPattern(text, DUAL_MULTI_PATTERNS)) {
            // Em add-ons de streaming brasileiros/latinos, "dual" é quase sempre PT + EN ou ES + EN
            languages.add("en")
            if (matchesPattern(text, DUBLADO_PATTERNS) || text.contains("🇧🇷")) {
                languages.add("pt")
            }
        }

        return languages
    }

    /**
     * Verifica se o Stream corresponde ao idioma alvo desejado.
     */
    fun streamMatchesTargetLanguage(stream: Stream, targetLanguage: String): Boolean {
        val targetNorm = normalizeLanguageCode(targetLanguage)
        if (targetNorm.isBlank()) return false

        val detected = detectLanguages(stream)
        if (detected.contains(targetNorm)) return true

        // Checagem adicional caso seja português e contenha "dublado" explicitamente
        if (targetNorm == "pt") {
            val text = listOfNotNull(stream.name, stream.title, stream.description)
                .joinToString(" ")
                .lowercase(Locale.ROOT)
            if (matchesPattern(text, DUBLADO_PATTERNS) || text.contains("🇧🇷") || text.contains("nacional")) {
                return true
            }
        }

        return false
    }

    /**
     * Verifica se o Stream já possui a faixa original (muito comum em releases dual audio).
     */
    fun hasOriginalTrack(stream: Stream): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return matchesPattern(text, DUAL_MULTI_PATTERNS) ||
            matchesPattern(text, ENGLISH_PATTERNS) ||
            detectLanguages(stream).contains("en")
    }

    private fun matchesPattern(text: String, patterns: List<Regex>): Boolean {
        return patterns.any { it.containsMatchIn(text) }
    }

    private val PORTUGUESE_PATTERNS = listOf(
        Regex("(?i)\\b(dublado|dub|pt-?br|pt_br|pob|por|pt|portugu[eê]s|portuguese|brazilian|nacional)\\b")
    )
    private val DUBLADO_PATTERNS = listOf(
        Regex("(?i)\\b(dublado|dublada|dub|audio dublado)\\b")
    )
    private val ENGLISH_PATTERNS = listOf(
        Regex("(?i)\\b(english|ingl[eê]s|eng|en)\\b")
    )
    private val SPANISH_PATTERNS = listOf(
        Regex("(?i)\\b(espa[nñ]ol|spanish|castellano|latino|spa|es)\\b")
    )
    private val FRENCH_PATTERNS = listOf(
        Regex("(?i)\\b(french|franc[eê]s|fra|fre)\\b")
    )
    private val GERMAN_PATTERNS = listOf(
        Regex("(?i)\\b(german|alem[aã]o|ger|deu)\\b")
    )
    private val ITALIAN_PATTERNS = listOf(
        Regex("(?i)\\b(italian|italiano|ita)\\b")
    )
    private val JAPANESE_PATTERNS = listOf(
        Regex("(?i)\\b(japanese|japon[eê]s|jpn|jap)\\b")
    )
    private val KOREAN_PATTERNS = listOf(
        Regex("(?i)\\b(korean|coreano|kor)\\b")
    )
    private val CHINESE_PATTERNS = listOf(
        Regex("(?i)\\b(chinese|chin[eê]s|chi|zho)\\b")
    )
    private val RUSSIAN_PATTERNS = listOf(
        Regex("(?i)\\b(russian|russo|rus)\\b")
    )
    private val DUAL_MULTI_PATTERNS = listOf(
        Regex("(?i)\\b(dual|multi|dual-?audio|multi-?audio)\\b")
    )
}
