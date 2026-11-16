package com.rpm.player.phone.shell

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.playback.StationPlaying
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.StationSign
import com.rpm.player.phone.design.system
import com.rpm.player.phone.player.BusyKey
import com.rpm.player.phone.radio.StationFace
import com.rpm.player.phone.player.OnceTicker
import com.rpm.player.phone.player.isBusy
import com.rpm.player.phone.ui.GlassInk
import com.rpm.player.phone.ui.rememberAccent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.PlaybackFailure
import com.rpm.player.core.playback.rememberPosition
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.chromeGlass
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.library.SleeveSquare

/**
 * The height of the strip. The row of controls is 40 dp high and starts 9 dp from the top. The rest is the gap above
 * the progress bar.
 */
private val STRIP = 62.dp
private val ROW = 40.dp
private val ABOVE_ROW = 9.dp
private val BAR = 2.dp

/**
 * What is playing, on a glass capsule above the tabs: the iPhone's `MiniPlayer` (RPMUI/MiniPlayer.swift). It shows the
 * cover (a station's logo for a station), the title above the artist, and the previous, play and next keys. A line
 * along the bottom grows as the track plays, in the cover's accent. A tap anywhere that is not a key opens the player.
 *
 * Stop is not shown here: on a strip this size it would be hit by accident, and stopping is never urgent. On a station
 * the previous and next keys are disabled, because a station has no queue. A station's lines are the song it announced
 * and its artist, else its name over its country and quality, never its name twice.
 */
@Composable
fun MiniPlayer(now: NowPlaying, player: Player, images: ImageLoader, backdrop: Backdrop, onOpen: () -> Unit) {
    val capsule = RoundedCornerShape(percent = 50)
    val station = now.station
    val onStation = station != null
    val accent = rememberAccent(now.cover.takeIf { !onStation }, images)
    Column(
        Modifier
            .fillMaxWidth()
            .height(STRIP)
            .chromeGlass(backdrop, capsule)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(top = ABOVE_ROW),
    ) {
        Row(
            // 15 dp on the left, so the cover's corners sit inside the curve of the capsule. The keys' icons are
            // narrower than their frames, so 9 dp on the right is enough.
            Modifier.height(ROW).padding(start = 15.dp, end = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (station != null) StationSleeve(station, images) else SleeveSquare(now.cover, ROW, images, radius = 8.dp, glyphSize = 16f)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                // Each line scrolls through once if it does not fit, as the iPhone's `OnceTicker` does.
                OnceTicker(title(now), display(14f).copy(color = Color.White))
                // When a record stops because there is no network, the reason replaces the artist, because this strip
                // is what the person sees at that moment.
                val offline = if (now.failure == PlaybackFailure.Offline) stringResource(R.string.Rpm_Playback_Offline) else null
                OnceTicker(offline ?: said(now), display(11f).copy(color = Ink.white(0x88)))
            }
            Row {
                Key(Glyph.backward, stringResource(R.string.Transport_Previous), enabled = !onStation) { player.seekToPrevious() }
                // The key shows what was asked for, not what is coming out, and a ring if the wait is long.
                BusyKey(now.isBusy, 16.dp) {
                    Key(
                        if (now.playing) Glyph.pause else Glyph.play,
                        stringResource(if (now.playing) R.string.CloudChip_Pause else R.string.AlbumMenu_Play),
                        enabled = true,
                    ) { toggle(player) }
                }
                Key(Glyph.forward, stringResource(R.string.Transport_Next), enabled = !onStation && player.hasNextMediaItem()) { player.seekToNext() }
            }
        }
        Spacer(Modifier.height(STRIP - ABOVE_ROW - ROW - BAR))
        // The band keeps its height for a station, with nothing in it, so the row does not move.
        if (!onStation) Progress(now, GlassInk.of(accent))
    }
}

/** The song a station announced, else its name; the track's title for a track. */
private fun title(now: NowPlaying): String {
    val station = now.station ?: return now.title
    return (station.said as? Icy.Said.Song)?.title ?: station.name
}

/**
 * The artist a station announced; with a song but no artist, nothing. Only while it announces no song, its country
 * and quality, never its name: that is the title then. The track's artist for a track.
 */
private fun said(now: NowPlaying): String {
    val station = now.station ?: return now.artist
    val sung = station.said as? Icy.Said.Song ?: return station.detail.orEmpty()
    return sung.artist.orEmpty()
}

/**
 * A station's own logo where the sleeve would be, on its made ground where it needs one ([StationFace]), and its call
 * sign where the logo's address no longer works. The ground fills the sleeve, so it is clipped and its edge drawn again.
 */
@Composable
private fun StationSleeve(station: StationPlaying, images: ImageLoader) {
    val shape = RoundedCornerShape(8.dp)
    // The cover of the song the station plays, over its logo, as on the desktop.
    val cover = com.rpm.player.phone.design.rememberSongCover(station)
    Box(Modifier.size(ROW).clip(shape)) {
        StationFace(
            station.logo,
            images,
            edge = ROW,
            inset = 3.dp,
            modifier = Modifier.size(ROW).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, shape),
        ) {
            BasicText(StationSign.of(station.name), maxLines = 1, style = system(11f, FontWeight.SemiBold).copy(color = Ink.white(0xAA), letterSpacing = 0.5.sp))
        }
        if (cover != null) {
            coil3.compose.AsyncImage(
                model = com.rpm.player.phone.design.rememberPicture(cover, ROW),
                contentDescription = null,
                imageLoader = images,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().border(1.dp, Ink.cardBorder, shape),
            )
        }
    }
}

/**
 * Play or pause. At the end of a queue, or on a stopped player, playback starts from the start, as a media key does.
 */
@OptIn(UnstableApi::class)
private fun toggle(player: Player) {
    Util.handlePlayPauseButtonAction(player)
}

@Composable
private fun Key(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(15f).copy(color = Ink.white(0xB8)))
    }
}

/**
 * The progress bar on the bottom border of the strip, and only the part played: it sits on the border it grows along.
 * It fades in from nothing over its first 16 dp, so its end joins the border, and is a little short of solid, so the
 * border shows through. Its colour spills into the glass round it, so it reads as light inside the glass. It is drawn in
 * the cover's accent through [GlassInk], white where the record has no colour.
 *
 * **The position is read in the draw**, so a tick redraws the line and recomposes nothing.
 */
@Composable
private fun Progress(now: NowPlaying, ink: Color) {
    val position = rememberPosition(now)
    Canvas(Modifier.fillMaxWidth().height(BAR).padding(horizontal = STRIP / 2)) {
        val through = if (now.durationMs > 0) (position.value.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
        val played = size.width * through
        if (played <= 0f) return@Canvas
        val fade = 16.dp.toPx()
        val brush = { alpha: Float ->
            Brush.horizontalGradient(
                0f to ink.copy(alpha = 0f),
                (fade / played).coerceAtMost(0.5f) to ink.copy(alpha = alpha),
                1f to ink.copy(alpha = alpha),
                startX = 0f,
                endX = played,
            )
        }
        // The spill: a wider, faint band under the line, where the iPhone draws a three point shadow.
        val spill = 3.dp.toPx()
        drawRoundRect(brush(0.55f * 0.35f), topLeft = Offset(0f, -spill), size = Size(played, size.height + 2 * spill), cornerRadius = CornerRadius(size.height / 2 + spill))
        drawRoundRect(brush(0.92f), size = Size(played, size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}
