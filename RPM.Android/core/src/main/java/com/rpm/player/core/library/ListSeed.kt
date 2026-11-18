package com.rpm.player.core.library

import com.rpm.player.core.room.Rgb
import com.rpm.player.core.room.RpmPalette

/**
 * The number a generated list's colours are chosen from. It must match the desktop's `SmartPlaylistFace.Cover`
 * and the iOS app's `GeneratedListLook.seed`. The low digit is the kind's hue on the palette ring. Above it
 * are the genre or decade the list is cut to, scattered through a hash so neighbouring decades do not get
 * neighbouring colours. The frame's inks and the room are chosen from it (the TV's `ListPicture`), and so
 * is the colour a shipped photograph is tinted with ([tint]).
 */
object ListSeed {
    /** The seed of the list [kind] cut to [genre] or [decade], or null for a kind with no hue: a kind
     * that this build does not know. Its list still shows, with a plain picture. */
    fun of(kind: String, genre: String?, decade: Int): Long? {
        val hue = HUE[kind] ?: return null
        val variation: Long = when {
            decade > 0 -> 977L + scatter("${decade}s")
            genre == null -> 977L
            else -> 977L + scatter(genre)
        }
        return (variation * RpmPalette.RING.size + hue) and 0xFFFFFFFFL
    }

    /** The palette entry the seed lands on, before the three are sorted by light: the colour a photograph
     * behind the list's frame is tinted with (SmartPlaylistFace.Tint, `GeneratedListLook.tint`). */
    fun tint(seed: Long): Rgb = RpmPalette.RING[(seed % RpmPalette.RING.size).toInt()]

    /** The ring index of each kind's hue (SmartPlaylistFace.Look). */
    private val HUE = mapOf(
        "Calm" to 5, "Energetic" to 9, "Danceable" to 7, "Focus" to 4, "Sleep" to 6, "Decade" to 1, "Classical" to 3,
        "FavoriteTracks" to 8, "MostPlayed" to 0, "OnRepeat" to 1, "RecentlyPlayed" to 2, "RecentlyAdded" to 3,
        "FirstListens" to 4, "LateNight" to 6, "Rediscover" to 7, "LovedAndForgotten" to 8, "PlayedNotLoved" to 9,
        "NeverPlayed" to 5, "KeepSkipping" to 1, "RightNow" to 5,
        "Melancholy" to 6, "Happy" to 2, "Angry" to 0, "Romantic" to 8, "Zen" to 4, "NewYearsEve" to 2,
    )

    /** FNV-1a over the UTF-16 units, kept to 4096 (SmartPlaylistFace.Scatter). */
    private fun scatter(text: String): Long {
        var hash = 2166136261L
        for (c in text) hash = ((hash xor c.code.toLong()) * 16777619L) and 0xFFFFFFFFL
        return hash % 4096L
    }
}
