package com.rpm.player.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The language sent with the lists read must be one of the five the store has tables for. "zh-CN" is not one. */
class AppLanguageTest {
    @Test fun theFiveComeOutAsTheStoreNamesThem() {
        assertEquals("en", AppLanguage.of(Locale.forLanguageTag("en-GB")))
        assertEquals("fr", AppLanguage.of(Locale.forLanguageTag("fr-BE")))
        assertEquals("es", AppLanguage.of(Locale.forLanguageTag("es-MX")))
        assertEquals("de", AppLanguage.of(Locale.forLanguageTag("de-AT")))
        assertEquals("zh-Hans", AppLanguage.of(Locale.forLanguageTag("zh-CN")))
    }

    @Test fun anyOtherIsEnglishAsTheAppShowsIt() {
        assertEquals("en", AppLanguage.of(Locale.forLanguageTag("nl-NL")))
    }
}
