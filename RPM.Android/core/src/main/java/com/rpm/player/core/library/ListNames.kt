package com.rpm.player.core.library

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack

/** What a list is called on screen: the app's word for a mood, a decade by its number, or the name the
 * list was given. */
@Composable
fun listName(name: ListName): String = when (name) {
    is ListName.Text -> name.text
    is ListName.Decade -> stringResource(R.string.Genres_Decade, name.decade.toString())
    is ListName.Mood -> moodName(name.kind)?.let { stringResource(it) } ?: name.fallback
}

/** [listName] off the composition, for putting lists in order by the names they are shown by. */
fun listName(name: ListName, resources: Resources): String = when (name) {
    is ListName.Text -> name.text
    is ListName.Decade -> resources.getString(R.string.Genres_Decade, name.decade.toString())
    is ListName.Mood -> moodName(name.kind)?.let(resources::getString) ?: name.fallback
}

/** A mood list's sentence, worded: the desktop's `MoodBlurbText.For`. */
@Composable
fun moodSentence(line: MoodLine): String {
    val named = listed(line.genres)
    return when (line.mood) {
        Mood.Sleep -> stringResource(R.string.Playlists_BlurbSleep)
        Mood.Calm -> stringResource(R.string.Playlists_BlurbCalm, named)
        Mood.Energetic -> stringResource(R.string.Playlists_BlurbEnergetic, named)
        Mood.Danceable -> stringResource(R.string.Playlists_BlurbDanceable, named)
        Mood.Focus -> stringResource(R.string.Playlists_BlurbFocus, named)
        Mood.Melancholy -> stringResource(R.string.Playlists_BlurbMelancholy, named)
        Mood.Happy -> stringResource(R.string.Playlists_BlurbHappy, named)
        Mood.Angry -> stringResource(R.string.Playlists_BlurbAngry, named)
        Mood.Romantic -> stringResource(R.string.Playlists_BlurbRomantic, named)
        Mood.Zen -> stringResource(R.string.Playlists_BlurbZen)
        Mood.NewYearsEve -> stringResource(R.string.Playlists_BlurbNewYearsEve)
        Mood.Christmas -> stringResource(R.string.Playlists_BlurbChristmas)
    }
}

/**
 * What a list is and who is on it, under its name on its page: a mood's sentence, then the artists that most of the
 * songs are by, for example "Featuring Blur, Pulp and 4 more". Null if there is neither. The phone's and the TV's
 * list pages both show it (`PlaylistFeatures.line` on the iPhone and the Apple TV).
 */
@Composable
fun listAbout(tracks: List<CloudTrack>, mood: MoodLine?): String? {
    val blurb = mood?.let { moodSentence(it) }
    val featured = remember(tracks) { PlaylistFeatures.of(tracks.map { it.artist }, PlaylistFeatures.NAMED) }
    if (featured.named.isEmpty()) return blurb
    val names = featured.named.joinToString(", ")
    val featuring = if (featured.more > 0) {
        stringResource(R.string.Playlists_FeaturingMore, names, featured.more)
    } else {
        stringResource(R.string.Playlists_Featuring, names)
    }
    return if (blurb == null) featuring else "$blurb $featuring"
}

/**
 * The line under a list's name on its row or card, where a record shows its artist: what kind of list it is
 * (`PlaylistRow.byline` on the iPhone). The phone's playlists tab and the TV's Playlists screen both show it.
 *
 * - A list somebody made names the person whose collection is shown ([owner]). Only the owner of a collection can
 *   make lists in it. With no name known, it says only that it is a playlist.
 * - A mood list names the genre it is cut to. A list cut across all genres (a whole mood, or a decade) says that it
 *   is a mood list.
 * - The lists about listening ([Lists.OWNER_ONLY]) are neither: null, and the caller says how much they hold.
 *
 * The genre is the genres wall's name for it ([Genres.match]), so a list and the wall call the same music by the same
 * name. Genre names are not translated anywhere.
 */
@Composable
fun listKindLine(list: TrackList, owner: String?): String? {
    val genre = list.genre?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        !list.generated ->
            if (owner.isNullOrBlank()) stringResource(R.string.Home_QuickPlaylist) else stringResource(R.string.Playlists_RowBy, owner)
        list.kind in Lists.OWNER_ONLY -> null
        genre != null -> Genres.match(genre) ?: genre
        else -> stringResource(R.string.Playlists_RowMood)
    }
}

/** "rock", "rock and pop", "rock, pop and jazz". The last join is a localised template because only some
 * languages put a word there. */
@Composable
private fun listed(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> stringResource(R.string.Playlists_GenreAnd, names.dropLast(1).joinToString(", "), names.last())
}

@StringRes
private fun moodName(kind: String): Int? = when (kind) {
    "RightNow" -> R.string.Playlists_RightNow
    "OnRepeat" -> R.string.Playlists_OnRepeat
    "FavoriteTracks" -> R.string.Playlists_FavoriteTracks
    "FirstListens" -> R.string.Playlists_FirstListens
    "Rediscover" -> R.string.Playlists_Rediscover
    "LovedAndForgotten" -> R.string.Playlists_LovedAndForgotten
    "LateNight" -> R.string.Playlists_LateNight
    "MostPlayed" -> R.string.Playlists_MostPlayed
    "Calm" -> R.string.Playlists_Calm
    "Energetic" -> R.string.Playlists_Energetic
    "Danceable" -> R.string.Playlists_Danceable
    "Focus" -> R.string.Playlists_Focus
    "Sleep" -> R.string.Playlists_Sleep
    "Melancholy" -> R.string.Playlists_Melancholy
    "Happy" -> R.string.Playlists_Happy
    "Angry" -> R.string.Playlists_Angry
    "Romantic" -> R.string.Playlists_Romantic
    "Zen" -> R.string.Playlists_Zen
    "NewYearsEve" -> R.string.Playlists_NewYearsEve
    "Christmas" -> R.string.Playlists_Christmas
    "Classical" -> R.string.Playlists_Classical
    else -> null
}
