package com.rpm.player.core.library

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.rpm.player.core.R

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
    "Classical" -> R.string.Playlists_Classical
    else -> null
}
