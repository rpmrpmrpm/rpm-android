package com.rpm.player.phone.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpm.player.core.playback.StationPlaying
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.SongCovers

/** The covers of the songs stations announce ([SongCovers]). Null: a station shows its logo only. */
val LocalSongCovers = staticCompositionLocalOf<SongCovers?> { null }

/** An announcement this short on either side is not a song worth asking about ("-", "X"). */
private const val SHORTEST = 2

/**
 * The address of the cover of the song [station] announces, or null: while it announces no artist and title, while it
 * reconnects, and for a song with no cover found. The mini player and the player draw it over the station's logo, as
 * the desktop does.
 *
 * A new song keeps the last song's cover until its own is known, so the picture goes from cover to cover and not
 * through the logo; a song with none gives the logo back.
 */
@Composable
fun rememberSongCover(station: StationPlaying?): String? {
    val covers = LocalSongCovers.current ?: return null
    val song = station?.said as? Icy.Said.Song
    val artist = song?.artist?.trim()?.takeIf { it.length >= SHORTEST }
    val title = song?.title?.trim()?.takeIf { it.length >= SHORTEST }
    val asked = if (artist != null && title != null && station.reconnecting != true) artist to title else null
    var cover by remember { mutableStateOf(asked?.let { covers.peek(it.first, it.second) }) }
    LaunchedEffect(asked) {
        cover = asked?.let { (a, t) -> covers.peek(a, t) ?: covers.of(a, t) }
    }
    return cover
}
