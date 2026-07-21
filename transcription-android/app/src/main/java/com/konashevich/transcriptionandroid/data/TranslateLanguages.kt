package com.konashevich.pressscribe.data

/**
 * Languages supported by Gemini Live Translation (BCP-47), suitable for text polish+translate.
 * @see <a href="https://ai.google.dev/gemini-api/docs/live-api/live-translate">Gemini Live Translate</a>
 */
data class TranslateLanguage(
    val code: String,
    val label: String,
) {
    /** Short badge for the Raw editor translate button (e.g. EN, ZH, PT). */
    fun buttonCode(): String =
        code.substringBefore('-').uppercase()
}

val TRANSLATE_LANGUAGES: List<TranslateLanguage> = listOf(
    TranslateLanguage("af", "Afrikaans"),
    TranslateLanguage("ak", "Akan"),
    TranslateLanguage("sq", "Albanian"),
    TranslateLanguage("am", "Amharic"),
    TranslateLanguage("ar", "Arabic"),
    TranslateLanguage("hy", "Armenian"),
    TranslateLanguage("az", "Azerbaijani"),
    TranslateLanguage("eu", "Basque"),
    TranslateLanguage("be", "Belarusian"),
    TranslateLanguage("bn", "Bengali"),
    TranslateLanguage("bg", "Bulgarian"),
    TranslateLanguage("my", "Burmese (Myanmar)"),
    TranslateLanguage("ca", "Catalan"),
    TranslateLanguage("zh-Hans", "Chinese (Simplified)"),
    TranslateLanguage("zh-Hant", "Chinese (Traditional)"),
    TranslateLanguage("hr", "Croatian"),
    TranslateLanguage("cs", "Czech"),
    TranslateLanguage("da", "Danish"),
    TranslateLanguage("nl", "Dutch"),
    TranslateLanguage("en", "English"),
    TranslateLanguage("et", "Estonian"),
    TranslateLanguage("fil", "Filipino"),
    TranslateLanguage("fi", "Finnish"),
    TranslateLanguage("fr", "French"),
    TranslateLanguage("gl", "Galician"),
    TranslateLanguage("ka", "Georgian"),
    TranslateLanguage("de", "German"),
    TranslateLanguage("el", "Greek"),
    TranslateLanguage("gu", "Gujarati"),
    TranslateLanguage("ha", "Hausa"),
    TranslateLanguage("he", "Hebrew"),
    TranslateLanguage("hi", "Hindi"),
    TranslateLanguage("hu", "Hungarian"),
    TranslateLanguage("is", "Icelandic"),
    TranslateLanguage("id", "Indonesian"),
    TranslateLanguage("it", "Italian"),
    TranslateLanguage("ja", "Japanese"),
    TranslateLanguage("jv", "Javanese"),
    TranslateLanguage("kn", "Kannada"),
    TranslateLanguage("kk", "Kazakh"),
    TranslateLanguage("km", "Khmer"),
    TranslateLanguage("rw", "Kinyarwanda"),
    TranslateLanguage("ko", "Korean"),
    TranslateLanguage("lo", "Lao"),
    TranslateLanguage("lv", "Latvian"),
    TranslateLanguage("lt", "Lithuanian"),
    TranslateLanguage("mk", "Macedonian"),
    TranslateLanguage("ms", "Malay"),
    TranslateLanguage("ml", "Malayalam"),
    TranslateLanguage("mr", "Marathi"),
    TranslateLanguage("mn", "Mongolian"),
    TranslateLanguage("ne", "Nepali"),
    TranslateLanguage("no", "Norwegian"),
    TranslateLanguage("fa", "Persian"),
    TranslateLanguage("pl", "Polish"),
    TranslateLanguage("pt-BR", "Portuguese (Brazil)"),
    TranslateLanguage("pt-PT", "Portuguese (Portugal)"),
    TranslateLanguage("pa", "Punjabi"),
    TranslateLanguage("ro", "Romanian"),
    TranslateLanguage("ru", "Russian"),
    TranslateLanguage("sr", "Serbian"),
    TranslateLanguage("sd", "Sindhi"),
    TranslateLanguage("si", "Sinhala"),
    TranslateLanguage("sk", "Slovak"),
    TranslateLanguage("sl", "Slovenian"),
    TranslateLanguage("es", "Spanish"),
    TranslateLanguage("su", "Sundanese"),
    TranslateLanguage("sw", "Swahili"),
    TranslateLanguage("sv", "Swedish"),
    TranslateLanguage("ta", "Tamil"),
    TranslateLanguage("te", "Telugu"),
    TranslateLanguage("th", "Thai"),
    TranslateLanguage("tr", "Turkish"),
    TranslateLanguage("uk", "Ukrainian"),
    TranslateLanguage("ur", "Urdu"),
    TranslateLanguage("uz", "Uzbek"),
    TranslateLanguage("vi", "Vietnamese"),
    TranslateLanguage("zu", "Zulu"),
)

const val TRANSLATE_LANGUAGE_PLACEHOLDER = "<<<LANGUAGE>>>"

fun findTranslateLanguage(code: String): TranslateLanguage? {
    val normalized = code.trim()
    if (normalized.isEmpty()) {
        return null
    }
    return TRANSLATE_LANGUAGES.firstOrNull { it.code.equals(normalized, ignoreCase = true) }
}

fun isConfiguredTranslateLanguage(code: String): Boolean =
    findTranslateLanguage(code) != null

fun normalizeTranslateLanguageCode(value: String): String =
    findTranslateLanguage(value)?.code.orEmpty()

fun resolveTranslatePrompt(
    template: String,
    languageCode: String,
): String {
    val language = findTranslateLanguage(languageCode)
        ?: error("Translation language is not configured.")
    val prompt = template.trim().ifBlank { DEFAULT_TRANSLATE_POLISH_PROMPT }
    return if (prompt.contains(TRANSLATE_LANGUAGE_PLACEHOLDER)) {
        prompt.replace(TRANSLATE_LANGUAGE_PLACEHOLDER, language.label)
    } else {
        "$prompt\n\nTranslate the final result into ${language.label}."
    }
}
