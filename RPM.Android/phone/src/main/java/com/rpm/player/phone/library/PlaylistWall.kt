package com.rpm.player.phone.library

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.library.PlaylistRow
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import kotlin.math.max
import kotlin.math.min

/**
 * How the playlists view shows a folder: as covers, or as rows. It matches the iPhone's `PlaylistLayout`
 * (RPMUI/PlaylistWall.swift) and the desktop's `PlaylistViewMode`. [stored] is the value saved between launches.
 */
enum class PlaylistLayout(val stored: String, @StringRes val label: Int, val mark: String) {
    Grid("grid", R.string.Playlists_ViewIcons, Glyph.grid),
    List("list", R.string.Playlists_ViewList, Glyph.rows);

    companion object {
        fun of(stored: String?): PlaylistLayout? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * The tile widths a pinch or a saved setting may ask for: the records wall's bounds, so a fast pinch cannot ask for a
 * hundred tiles across and a value saved by another build cannot open the view on one tile (`PlaylistsPage.bounded`).
 */
internal fun boundedStride(art: Double): Double = min(max(art, 60.0), 900.0)

/**
 * The covers of one folder's contents, drawn as the records wall draws a library. It matches the iPhone's
 * `PlaylistWall`. It draws the rows it is given and decides nothing: [PlaylistBrowse][com.rpm.player.core.library.PlaylistBrowse]
 * decides what is in the folder, and the rows layout is built from the same answer, so the two always agree.
 *
 * The header already says where the folder is, so no path is drawn over the tiles.
 *
 * @param targetStride how wide a tile should be. It is fitted to a whole number of columns by the records wall's own
 * rule ([WallLayout.wall]).
 * @param onPinch a pinch as a factor on [targetStride], and null when the fingers lift. The wall does not own its size.
 * @param footer the text under the last tile, which the page decides.
 */
@Composable
fun PlaylistWall(
    rows: List<PlaylistRow>,
    titleOf: @Composable (PlaylistRow) -> String,
    pictureOf: (PlaylistRow) -> Any?,
    framedOf: @Composable (PlaylistRow) -> FramedList?,
    images: ImageLoader,
    targetStride: Double,
    bottomRoom: Dp,
    topRoom: Dp,
    onPress: (PlaylistRow) -> Unit,
    onPinch: (Double?) -> Unit,
    footer: @Composable () -> Unit,
) {
    val pinch by rememberUpdatedState(onPinch)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val (columns, art) = WallLayout.wall(maxWidth.value.toDouble(), targetStride, EDGE, WALL_GUTTER, WALL_COLUMNS)
        val caption = AlbumCaption.of(art)
        val grid = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier
                .fillMaxSize()
                .dissolvesAtTop(topRoom) { grid.scrolled() }
                .pinches { pinch(it) },
            contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = topRoom + 4.dp, bottom = FOOT + bottomRoom),
            horizontalArrangement = Arrangement.spacedBy(WALL_GUTTER.dp),
        ) {
            items(rows, key = { it.id }, contentType = { "list" }) { row ->
                Box(contentAlignment = Alignment.TopCenter) {
                    PlaylistTile(row, titleOf(row), pictureOf(row), framedOf(row), caption, images) { onPress(row) }
                }
            }
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") { footer() }
        }
    }
}

/**
 * One list, folder or generated list on the wall: the square cover and the name under it. It matches the iPhone's
 * `PlaylistTile`, with the records wall's measurements: a list and a record are both pictures in a grid of pictures.
 * A list has only a name, so the name takes the room of both of a record's lines and wraps to two, set at the top of
 * that room so a short name sits right under the picture. The whole tile answers a tap.
 */
@Composable
private fun PlaylistTile(row: PlaylistRow, title: String, picture: Any?, framed: FramedList?, caption: AlbumCaption, images: ImageLoader, onPress: () -> Unit) {
    val art = caption.art.dp
    // The mark on an empty square follows the edge, as on the records wall: a glyph that suits two columns is far
    // too big at one and far too small at four.
    val glyphSize = min(max(caption.art * 0.2, 16.0), 52.0).toFloat()
    Column(
        Modifier
            .width(art)
            .height(caption.card.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onPress),
    ) {
        PlaylistSleeve(picture, row.mark, art, corner = 10.dp, mark = glyphSize, images, framed)
        Spacer(Modifier.height(AlbumCaption.ART_GAP.dp))
        val nameRoom = (caption.albumLine + AlbumCaption.LINE_GAP + caption.artistLine).dp
        Box(Modifier.width(art).height(nameRoom), contentAlignment = Alignment.TopCenter) {
            BasicText(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = system(caption.album, FontWeight.Medium).copy(color = Color.White, textAlign = TextAlign.Center),
            )
        }
    }
}

/** The mark a row shows when it has no picture of its own (`PlaylistRow.mark` on iPhone). */
internal val PlaylistRow.mark: String
    get() = when (this) {
        PlaylistRow.Queue -> Glyph.queue
        PlaylistRow.Marked -> Glyph.heart
        is PlaylistRow.Folder -> Glyph.folder
        is PlaylistRow.Playlist -> Glyph.record
    }

/**
 * Reports a two-finger pinch as a factor on the distance the fingers started at, and null when they lift. One finger
 * is left alone, so the wall still scrolls. While two fingers are down their moves are taken, so the wall does not
 * scroll under the pinch. The iPhone's wall reads its `MagnifyGesture` beside the scroll in the same way.
 */
private fun Modifier.pinches(onPinch: (Double?) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var start = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size >= 2) {
                val apart = (down[0].position - down[1].position).getDistance()
                if (start <= 0f) start = apart else if (apart > 0f) onPinch((apart / start).toDouble())
                event.changes.forEach { it.consume() }
            }
        }
        if (start > 0f) onPinch(null)
    }
}
