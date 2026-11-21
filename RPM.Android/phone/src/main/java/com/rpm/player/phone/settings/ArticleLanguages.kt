package com.rpm.player.phone.settings

import java.util.Locale

/**
 * The languages the artist and record descriptions can be read in, by their Wikipedia codes. These are the larger
 * Wikipedias, where most acts and records have an article. Where one has no article in the chosen language, the English
 * one is read ([com.rpm.player.core.papers.Encyclopedia.languages]).
 */
object ArticleLanguages {
    val OFFERED = listOf("en", "nl", "de", "fr", "es", "it", "pt", "sv", "pl", "ja", "zh")

    /** The language's name in itself, with a capital first letter: "Nederlands", "English", "日本語". */
    fun nameOf(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val name = locale.getDisplayLanguage(locale).ifEmpty { code }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }
}
