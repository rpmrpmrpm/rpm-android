package com.rpm.player.core

import java.util.Locale

/** The language the app's strings are shown in, as the store names it: one of `en`, `fr`, `es`, `de`, `zh-Hans`. */
object AppLanguage {
    /** The tag for [locale]: the five the strings come in, and English for any other, which is what the app shows. */
    fun of(locale: Locale): String = when (locale.language) {
        "fr", "es", "de" -> locale.language
        "zh" -> "zh-Hans"
        else -> "en"
    }
}
