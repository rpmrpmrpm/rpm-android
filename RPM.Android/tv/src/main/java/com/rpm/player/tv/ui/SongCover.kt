package com.rpm.player.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import com.rpm.player.core.playback.StationPlaying
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.SongCovers

/** The song covers ([SongCovers]) for the station pictures that show the song playing: the player's sleeve and the
 * mini player. Null shows the station alone. */
val LocalSongCovers = staticCompositionLocalOf<SongCovers?> { null }

/**
 * The cover of the song [station] says it is playing, or null: while it announces nothing, an ad break or a line
 * that is not an artist and a title, while it reconnects, and for a song nobody found a cover for.
 *
 * A new song keeps the last song's cover until its own is known, so the picture goes from one cover to the next
 * rather than through the station's logo; a song with none then gives the station back.
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

/** An announcement this short on either side is not a song worth asking about ("-", "X"). */
private const val SHORTEST = 2

/**
 * The song's cover ([rememberSongCover]) over a station's picture, filling [modifier]'s box and cropped to it. It
 * fades in over the station when the first cover arrives and out again when the station has none; from one cover to
 * the next it crossfades without showing the station between them ([HeldCover]).
 */
@Composable
fun SongCoverOver(cover: String?, images: ImageLoader, modifier: Modifier = Modifier.fillMaxSize()) {
    // The cover last shown, kept to draw while it fades out. Not state: it is only read with the cover.
    val shown = remember { arrayOfNulls<String>(1) }
    if (cover != null) shown[0] = cover
    val alpha by animateFloatAsState(if (cover != null) 1f else 0f, tween(SONG_COVER_FADE_MS), label = "song cover")
    val picture = shown[0] ?: return
    if (alpha <= 0f) return
    HeldCover(
        picture = picture,
        images = images,
        contentScale = ContentScale.Crop,
        modifier = modifier.graphicsLayer {
            this.alpha = alpha
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    )
}

/** How long the song's cover takes to come over the station, or to leave it. */
private const val SONG_COVER_FADE_MS = 600
