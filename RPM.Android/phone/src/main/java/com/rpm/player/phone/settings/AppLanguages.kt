package com.rpm.player.phone.settings

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import com.rpm.player.phone.stage.findActivity
import com.rpm.player.phone.ui.Look
import java.util.Locale

/**
 * The language of the app, chosen by the person: the phone's own, or one of the five the strings come in (`Loc.offered`,
 * `Loc.choose` on iPhone; the desktop's `AppLanguage.Offered`). Chosen on the settings page and in the welcome.
 *
 * **Android 13 and later keep the choice themselves** ([LocaleManager]), as a per-app language: the system's own parts
 * of the app follow too, and the choice is also in the phone's settings for the app. The activity handles the change of
 * locale itself (`configChanges` in the manifest), so it is not created again: Compose reads the new configuration and
 * every `stringResource` gives the new language at once, on the same tab and page. The playback service and the
 * library carry on.
 *
 * **Android 9 to 12 have no per-app language.** The choice is kept in the phone's settings (`app.language`, the
 * iPhone's key), the activity lays it over its configuration as it is made ([overrideFor]), and a new choice makes the
 * activity again. The tab and the pages open are kept, as they are saved state. Text drawn outside the activity (the
 * notification, the media session's titles) stays in the phone's language there.
 */
object AppLanguages {
    /** The app's languages, as the desktop offers them. */
    val OFFERED = listOf("en", "fr", "es", "de", "zh-Hans")

    /** Where the choice is kept: one of [OFFERED], or nothing for the phone's own language. */
    const val KEY = "app.language"

    private var prefs: SharedPreferences? = null

    /** Opens the phone's settings, where the choice is kept. Called once as the app starts. */
    fun open(prefs: SharedPreferences) {
        this.prefs = prefs
    }

    /**
     * The language chosen, one of [OFFERED], or empty for the phone's. Asked again whenever the configuration changes,
     * because on Android 13 the person can also change it in the phone's settings.
     */
    fun chosen(context: Context): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
        offeredOf(if (locales == null || locales.isEmpty) "" else locales[0].toLanguageTag())
    } else {
        offeredOf(stored(context))
    }

    /** Uses [code] for the app at once: one of [OFFERED], or anything else for the phone's own language. */
    fun choose(context: Context, code: String) {
        val kept = code.takeIf { it in OFFERED }.orEmpty()
        if (kept == chosen(context)) return
        val store = prefs ?: settingsOf(context)
        store.edit().apply { if (kept.isEmpty()) remove(KEY) else putString(KEY, kept) }.apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (kept.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(kept)
        } else {
            context.findActivity()?.recreate()
        }
    }

    /**
     * On Android 9 to 12, the configuration the activity lays over its own so its text is in the chosen language, or
     * null for the phone's. It also sets the default locale, which formats dates and numbers. On Android 13 and later,
     * null: the system applies the per-app language itself.
     */
    fun overrideFor(context: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        val code = offeredOf(stored(context))
        val locale = if (code.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        return if (code.isEmpty()) null else Configuration().apply { setLocale(locale) }
    }

    /** A language's name in that language, as a language picker shows it: "Deutsch", "Français". */
    fun name(code: String): String {
        val locale = Locale.forLanguageTag(code)
        val said = locale.getDisplayName(locale).ifEmpty { code }
        return said.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /**
     * The language of [OFFERED] a language tag stands for, or empty for one the app does not offer (the iPhone counts a
     * stored code it does not offer as the phone's language). Chinese is the simplified script the strings are written in.
     */
    internal fun offeredOf(tag: String): String {
        if (tag.isBlank()) return ""
        val locale = Locale.forLanguageTag(tag)
        return when (locale.language) {
            "en", "fr", "es", "de" -> locale.language
            "zh" -> if (locale.script.isEmpty() || locale.script == "Hans") "zh-Hans" else ""
            else -> ""
        }
    }

    private fun stored(context: Context): String = (prefs ?: settingsOf(context)).getString(KEY, null).orEmpty()

    /** The phone's settings file ([com.rpm.player.phone.PhoneSettings]), opened before [open] has been called. */
    private fun settingsOf(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(Look.FILE, Context.MODE_PRIVATE)
}
