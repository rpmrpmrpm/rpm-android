package com.rpm.player.core.radio

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

/**
 * How the admin changed the way one station is shown (`StationEdit` on the desktop, in RPM.Core): its
 * title, its tags, the line on its banner card, and whether it carries advertising. Each value is null where the
 * station's own is used. [tags] replace the directory's for genres only; the ads rule reads the directory's own,
 * and [ads] overrides that rule ([RadioStation.carriesAds]).
 */
data class StationEdit(val name: String?, val tags: List<String>?, val description: String?, val ads: Boolean? = null) {
    companion object {
        /** The edit with blank values as null and tags read as the directory's are, or null when
         * nothing is left of it. */
        fun of(name: String?, tags: List<String>?, description: String?, ads: Boolean? = null): StationEdit? {
            val title = name?.trim()?.takeIf { it.isNotEmpty() }
            val tagged = tags?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.distinct()?.takeIf { it.isNotEmpty() }
            val line = description?.trim()?.takeIf { it.isNotEmpty() }
            return if (title == null && tagged == null && line == null && ads == null) null else StationEdit(title, tagged, line, ads)
        }
    }
}

/**
 * One internet radio station, as Radio Browser lists it. The saved fields match the iOS app's
 * `RadioStation.swift`, in camelCase, so a favourite is the whole station and still plays after
 * the directory drops it. The transient fields come from folding a directory answer
 * ([RadioResults.onePerStation]) and from the rpm API's rules ([StationRules.edited]). They are never saved.
 */
@Serializable
data class RadioStation(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val favicon: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val codec: String? = null,
    val bitrateKbps: Int = 0,
    val votes: Int = 0,
    val clickCount: Int = 0,
    val isHls: Boolean = false,
    val reachable: Boolean = true,
    /** Set by [RadioNames.distinguish] when two listed stations would print the same. */
    @Transient val shownName: String? = null,
    /** Logos of the other entries folded into this one, best first. */
    @Transient val otherLogos: List<String> = emptyList(),
    /** The uuids of the other entries folded into this one. */
    @Transient val alsoFiledAs: List<String> = emptyList(),
    /** The admin's edit, applied when the station is drawn ([StationRules.shown]). [name] and [tags]
     * stay the directory's, so a favourite is saved and folded as the directory files it. */
    @Transient val edit: StationEdit? = null,
) {
    /** The name to print: the admin's title, else the name with the codec and bitrate words and any
     * strapline taken off. */
    val display: String get() = edit?.name ?: shownName ?: RadioNames.clean(name)

    /** The tags the station is filed under for genres: the admin's where they set some, else the
     * directory's. */
    val shownTags: List<String> get() = edit?.tags ?: tags

    /** Whether the station carries advertising: what the admin said where they said it, else the ads rule on the
     * directory's own tags, name and website ([RadioAds]). Every AD mark and the ads filter read this. */
    val carriesAds: Boolean get() = edit?.ads ?: RadioAds.carriesAds(tags, name, homepage)

    /** "MP3 128k", "AAC", or null when the directory names neither. */
    val quality: String?
        get() {
            val format = codec?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "UNKNOWN" }
            val rate = bitrateKbps.takeIf { it > 0 }?.let { "${it}k" }
            return listOfNotNull(format, rate).joinToString(" ").ifEmpty { null }
        }

    /** The country in the TV's language, then the quality: "Netherlands · MP3 128k". */
    fun detail(locale: Locale): String? {
        val place = countryCode?.let { countryName(it, locale) } ?: country?.takeIf { it.isNotBlank() }
        return listOfNotNull(place, quality).joinToString(" · ").ifEmpty { null }
    }

    /** Whether [id] names this station, or one of the entries folded into it. */
    fun answersTo(id: String): Boolean =
        uuid.equals(id, ignoreCase = true) || alsoFiledAs.any { it.equals(id, ignoreCase = true) }
}

/** A country's name in [locale]'s language from its two-letter code, or null for a code that is not one. */
fun countryName(code: String, locale: Locale): String? = try {
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale).takeIf { it.isNotBlank() }
} catch (_: Exception) {
    null
}
