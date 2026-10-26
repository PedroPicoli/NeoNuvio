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

    /**
     * Verifica se o texto do stream já indica áudio único no idioma alvo (Nível A).
     * Suporta todos os idiomas suportados no app (pt, en, es, fr, de, it, ja, ko, zh, ru, etc.).
     */
    fun isExplicitSingleTargetLanguage(
        rawText: String?,
        addonName: String?,
        targetLanguage: String
    ): Boolean {
        val targetNorm = normalizeLanguageCode(targetLanguage)
        if (targetNorm.isBlank()) return false
        val text = (rawText.orEmpty() + " " + addonName.orEmpty()).lowercase(Locale.ROOT)

        val hasSubTag = matchesPattern(text, SUBTITLE_ONLY_PATTERNS)
        if (hasSubTag) return false

        val isDual = matchesPattern(text, DUAL_MULTI_PATTERNS)
        if (isDual) return false

        val explicitPatterns = EXPLICIT_DUB_BY_LANG[targetNorm] ?: getGenericPatternsForLang(targetNorm)
        val hasExplicitTag = matchesPattern(text, explicitPatterns) || hasFlagForLanguage(text, targetNorm)

        val nationalAddons = NATIONAL_ADDONS_BY_LANG[targetNorm] ?: emptyList()
        val isNationalAddon = matchesPattern(text, nationalAddons)

        return hasExplicitTag || isNationalAddon
    }

    /**
     * Verifica se o stream contém indicação de múltiplos áudios (Dual Audio / Multi).
     */
    fun isDualOrMultiAudio(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        return matchesPattern(rawText.lowercase(Locale.ROOT), DUAL_MULTI_PATTERNS)
    }

    /**
     * Nível B: Verifica se o label ou language da faixa de áudio corresponde ao idioma alvo.
     * Suporta qualquer idioma alvo globalmente.
     */
    fun isTrackMatchingTargetLanguage(
        formatLanguage: String?,
        formatLabel: String?,
        targetLanguage: String
    ): Boolean {
        val targetNorm = normalizeLanguageCode(targetLanguage)
        if (targetNorm.isBlank()) return false

        val langNorm = normalizeLanguageCode(formatLanguage)
        if (langNorm.isNotBlank() && langNorm == targetNorm) return true

        val label = formatLabel.orEmpty().lowercase(Locale.ROOT)
        if (label.isBlank()) return false

        val labelTerms = LABEL_TERMS_BY_LANG[targetNorm]
        if (labelTerms != null && labelTerms.any { label.contains(it) }) {
            return true
        }

        // Checagem genérica por display language em diferentes locales
        val targetLocale = Locale.forLanguageTag(targetNorm)
        val displayNames = listOf(
            targetLocale.getDisplayLanguage(Locale.ROOT).lowercase(Locale.ROOT),
            targetLocale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT),
            targetLocale.getDisplayLanguage(Locale("pt")).lowercase(Locale.ROOT),
            targetLocale.getDisplayLanguage(Locale("es")).lowercase(Locale.ROOT)
        ).filter { it.length >= 3 }

        return displayNames.any { label.contains(it) } || (label.contains(targetNorm) && targetNorm.length >= 3)
    }

    /**
     * Nível C: Se houver apenas 1 faixa de áudio und/null, e o stream/addon indicar origem do idioma alvo
     * sem nenhuma tag de áudio estrangeiro ou legenda, assume que já é o idioma nativo.
     */
    fun isSingleTrackPresumedTarget(
        singleTrackLanguage: String?,
        rawText: String?,
        addonName: String?,
        targetLanguage: String
    ): Boolean {
        val targetNorm = normalizeLanguageCode(targetLanguage)
        if (targetNorm.isBlank()) return false
        val lang = singleTrackLanguage?.trim()?.lowercase(Locale.ROOT)
        val isUndOrNull = lang.isNullOrBlank() || lang == "und" || lang == "null"
        if (!isUndOrNull) return false

        val text = (rawText.orEmpty() + " " + addonName.orEmpty()).lowercase(Locale.ROOT)
        if (matchesPattern(text, SUBTITLE_ONLY_PATTERNS)) return false

        // Se tiver tag explícita de idioma estrangeiro conflitante, não presume
        val conflictingPatterns = CONFLICTING_FOREIGN_PATTERNS[targetNorm]
        if (conflictingPatterns != null && matchesPattern(text, conflictingPatterns)) {
            return false
        }

        val originPatterns = ORIGIN_PATTERNS_BY_LANG[targetNorm] ?: (EXPLICIT_DUB_BY_LANG[targetNorm] ?: emptyList())
        val nationalAddons = NATIONAL_ADDONS_BY_LANG[targetNorm] ?: emptyList()
        val hasFlag = hasFlagForLanguage(text, targetNorm)

        return matchesPattern(text, originPatterns) || matchesPattern(text, nationalAddons) || hasFlag
    }

    private fun hasFlagForLanguage(text: String, langNorm: String): Boolean {
        return when (langNorm) {
            "pt" -> text.contains("🇧🇷") || text.contains("🇵🇹")
            "en" -> text.contains("🇺🇸") || text.contains("🇬🇧")
            "es" -> text.contains("🇪🇸") || text.contains("🇲🇽") || text.contains("🇦🇷")
            "fr" -> text.contains("🇫🇷")
            "de" -> text.contains("🇩🇪") || text.contains("🇦🇹")
            "it" -> text.contains("🇮🇹")
            "ja" -> text.contains("🇯🇵")
            "ko" -> text.contains("🇰🇷")
            "zh" -> text.contains("🇨🇳") || text.contains("🇹🇼")
            "ru" -> text.contains("🇷🇺")
            else -> false
        }
    }

    private fun getGenericPatternsForLang(langNorm: String): List<Regex> {
        val loc = Locale.forLanguageTag(langNorm)
        val names = listOfNotNull(
            loc.getDisplayLanguage(Locale.ROOT),
            loc.getDisplayLanguage(Locale.ENGLISH),
            loc.getDisplayLanguage(Locale("pt"))
        ).map { it.lowercase(Locale.ROOT) }.filter { it.length >= 3 }.distinct()

        return names.map { Regex("(?i)\\b$it\\b") }
    }

    private fun matchesPattern(text: String, patterns: List<Regex>): Boolean {
        return patterns.any { it.containsMatchIn(text) }
    }

    private val SUBTITLE_ONLY_PATTERNS = listOf(
        Regex("(?i)\\b(legendado|leg\\b|\\[leg\\]|subbed|sub\\b|softsub|vostfr|vost|subtitulado)\\b")
    )

    private val DUAL_MULTI_PATTERNS = listOf(
        Regex("(?i)\\b(dual|multi|dual-?audio|multi-?audio|dublado e legendado)\\b")
    )

    private val EXPLICIT_DUB_BY_LANG = mapOf(
        "pt" to listOf(
            Regex("(?i)\\b(dublado|dublada|\\[dub\\]|pt-?br|ptbr|portugu[eê]s|nacional|brazilian|audio dublado)\\b")
        ),
        "en" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng|\\[eng\\]|\\[en\\]|original audio)\\b")
        ),
        "es" to listOf(
            Regex("(?i)\\b(espa[nñ]ol|spanish|castellano|latino|audio latino|\\[esp\\]|\\[lat\\]|doblado|spa)\\b")
        ),
        "fr" to listOf(
            Regex("(?i)\\b(french|franc[eê]s|truefrench|vf|vff|vfq|audio fran[cç]ais|fra|fre|\\[vf\\]|\\[vff\\])\\b")
        ),
        "de" to listOf(
            Regex("(?i)\\b(german|deutsch|alem[aã]o|ger|deu|\\[ger\\]|\\[de\\]|synchro)\\b")
        ),
        "it" to listOf(
            Regex("(?i)\\b(italian|italiano|ita|\\[ita\\]|\\[it\\]|doppiato)\\b")
        ),
        "ja" to listOf(
            Regex("(?i)\\b(japanese|japon[eê]s|nihongo|jpn|jap|\\[jap\\]|\\[jpn\\])\\b")
        ),
        "ko" to listOf(
            Regex("(?i)\\b(korean|coreano|kor|\\[kor\\])\\b")
        ),
        "zh" to listOf(
            Regex("(?i)\\b(chinese|chin[eê]s|mandarin|cantonese|chi|zho|\\[chi\\])\\b")
        ),
        "ru" to listOf(
            Regex("(?i)\\b(russian|russo|rus|\\[rus\\]|dubl|dubljazh|rus\\.dub)\\b")
        )
    )

    private val NATIONAL_ADDONS_BY_LANG = mapOf(
        "pt" to listOf(
            Regex("(?i)\\b(bestcine|brazuca|redecanais|comando|megaflix|cinetorrent|baixarfilmes|cinevision)\\b")
        ),
        "es" to listOf(
            Regex("(?i)\\b(alfa|palantir|magis|pelisplus|cuevana|cinecalidad)\\b")
        ),
        "fr" to listOf(
            Regex("(?i)\\b(vstream|zone-telechargement|wawacity|darkiworld)\\b")
        ),
        "ru" to listOf(
            Regex("(?i)\\b(filmix|rezka|hdrezka|lostfilm|kinogo)\\b")
        )
    )

    private val ORIGIN_PATTERNS_BY_LANG = mapOf(
        "pt" to listOf(
            Regex("(?i)\\b(dublado|dub|pt-?br|ptbr|portugu[eê]s|nacional|brazilian|brasil)\\b")
        ),
        "es" to listOf(
            Regex("(?i)\\b(espa[nñ]ol|spanish|castellano|latino|doblado|spa)\\b")
        ),
        "fr" to listOf(
            Regex("(?i)\\b(french|franc[eê]s|truefrench|vf|vff|vfq|fra|fre)\\b")
        ),
        "de" to listOf(
            Regex("(?i)\\b(german|deutsch|alem[aã]o|ger|deu)\\b")
        ),
        "it" to listOf(
            Regex("(?i)\\b(italian|italiano|ita)\\b")
        ),
        "ja" to listOf(
            Regex("(?i)\\b(japanese|japon[eê]s|jpn|jap|nihongo)\\b")
        ),
        "ko" to listOf(
            Regex("(?i)\\b(korean|coreano|kor)\\b")
        ),
        "zh" to listOf(
            Regex("(?i)\\b(chinese|chin[eê]s|mandarin|cantonese|chi|zho)\\b")
        ),
        "ru" to listOf(
            Regex("(?i)\\b(russian|russo|rus|dubl)\\b")
        )
    )

    private val LABEL_TERMS_BY_LANG = mapOf(
        "pt" to listOf("dublado", "portugu", "pt-br", "ptbr", "brazilian", "brasil", "pob", "por"),
        "en" to listOf("english", "ingl", "eng", "original"),
        "es" to listOf("español", "espanol", "spanish", "castellano", "latino", "doblado", "spa"),
        "fr" to listOf("français", "francais", "french", "fra", "vff", "vf", "vfq"),
        "de" to listOf("deutsch", "german", "ger", "deu"),
        "it" to listOf("italiano", "italian", "ita"),
        "ja" to listOf("japanese", "japon", "jpn", "jap", "nihongo"),
        "ko" to listOf("korean", "coreano", "kor"),
        "zh" to listOf("chinese", "chin", "mandarin", "cantonese", "chi", "zho"),
        "ru" to listOf("russian", "russo", "rus", "dubl")
    )

    private val CONFLICTING_FOREIGN_PATTERNS = mapOf(
        "pt" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng\\b|espa[nñ]ol|french|german)\\b")
        ),
        "es" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng\\b|portugu[eê]s|pt-?br|french|german)\\b")
        ),
        "fr" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng\\b|portugu[eê]s|espa[nñ]ol|german)\\b")
        ),
        "de" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng\\b|portugu[eê]s|espa[nñ]ol|french)\\b")
        ),
        "it" to listOf(
            Regex("(?i)\\b(english|ingl[eê]s|eng\\b|portugu[eê]s|espa[nñ]ol|french)\\b")
        ),
        "en" to listOf(
            Regex("(?i)\\b(portugu[eê]s|pt-?br|espa[nñ]ol|french|german|russian)\\b")
        )
    )

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
}

