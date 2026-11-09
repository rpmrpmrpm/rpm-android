package com.rpm.player.phone.library

import com.rpm.player.phone.design.rememberPicture
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Cover
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.system
import com.rpm.player.phone.downloads.LocalPlayable
import com.rpm.player.phone.downloads.greyedOut
import kotlin.math.max
import kotlin.math.min

/** The gap between two covers. */
internal const val WALL_GUTTER = 24.0

/** The inset at either edge of the wall: the app's margin, so the first column lines up with the title. */
internal const val EDGE = 20.0

/**
 * The most columns the wall can show. One column is a cover as wide as the phone. Four is as small as a name can still
 * be read at arm's length.
 */
internal const val WALL_COLUMNS = 4

/** The gap between the last row of a page and the chrome below it (`RPMLayout.foot` on iPhone). */
val FOOT = 16.dp

/**
 * The wall of records. It matches the iPhone's `AlbumWall` (RPMUI/AlbumWall.swift). It gets a width and works
 * out its own columns with one rule (`WallLayout.wall`). So all rows have the same height. The covers fade out
 * at the top edge as they scroll off it.
 *
 * @param bottomRoom how much of the bottom of the page the chrome covers. The last row can then scroll clear of it.
 * @param topRoom how much of the top the page's header covers: the first row starts under it, and the covers fade
 * out over it as they scroll up behind it ([com.rpm.player.phone.design.UnderHeader]).
 */
@Composable
fun AlbumWall(
    albums: List<CloudAlbum>,
    coverOf: (CloudAlbum) -> Cover?,
    images: ImageLoader,
    targetStride: Double,
    bottomRoom: Dp,
    onOpen: (CloudAlbum) -> Unit,
    modifier: Modifier = Modifier,
    topRoom: Dp = 0.dp,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val (columns, art) = WallLayout.wall(maxWidth.value.toDouble(), targetStride, EDGE, WALL_GUTTER, WALL_COLUMNS)
        val caption = AlbumCaption.of(art)
        val grid = rememberLazyGridState()
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = grid,
            modifier = Modifier.fillMaxSize().dissolvesAtTop(if (topRoom > 0.dp) topRoom else 30.dp) { grid.scrolled() },
            contentPadding = PaddingValues(start = EDGE.dp, end = EDGE.dp, top = topRoom + 4.dp, bottom = FOOT + bottomRoom),
            horizontalArrangement = Arrangement.spacedBy(WALL_GUTTER.dp),
        ) {
            items(albums, key = { it.key }, contentType = { "album" }) { album ->
                Box(contentAlignment = Alignment.TopCenter) {
                    AlbumTile(album, coverOf(album), caption, images) { onOpen(album) }
                }
            }
        }
    }
}

/**
 * One record on the wall: the cover, its name, and its artist (as on the iPhone's `AlbumTile`). Every size
 * comes from [caption], so every card in a row has the same height. The whole card responds to a tap, not
 * only the cover.
 */
@Composable
fun AlbumTile(album: CloudAlbum, cover: Cover?, caption: AlbumCaption, images: ImageLoader, onOpen: () -> Unit) {
    val art = caption.art.dp
    // Offline, a record with nothing on the phone is greyed: still there to look at, not there to play.
    val playable = LocalPlayable.current.canPlay(album.key)
    Column(
        Modifier
            .width(art)
            .height(caption.card.dp)
            .greyedOut(!playable)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        SleeveSquare(cover, art, images, radius = 10.dp, glyphSize = min(max(caption.art * 0.2, 16.0), 52.0).toFloat())
        Spacer(Modifier.height(AlbumCaption.ART_GAP.dp))
        Box(Modifier.height(caption.albumLine.dp), contentAlignment = Alignment.CenterStart) {
            BasicText(album.album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(caption.album, FontWeight.Medium).copy(color = Color.White))
        }
        Spacer(Modifier.height(AlbumCaption.LINE_GAP.dp))
        Box(Modifier.height(caption.artistLine.dp), contentAlignment = Alignment.CenterStart) {
            BasicText(album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = system(caption.artist).copy(color = Ink.secondary))
        }
    }
}

/**
 * A square cover. It has a dark background with a faint record icon, and the cover image is drawn over that
 * once it has loaded. The image fills the square and is **cropped from the bottom**, as the iPhone's `Sleeve`
 * does. The top of a cover holds the record's name or a face. So the top is kept, and the part that does not
 * fit is cut off at the bottom.
 */
@Composable
fun SleeveSquare(cover: Cover?, edge: Dp, images: ImageLoader, radius: Dp, glyphSize: Float, bordered: Boolean = true) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .size(edge)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0x22 / 255f))
            .then(if (bordered) Modifier.border(1.dp, Ink.cardBorder, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(Glyph.record, style = icon(glyphSize).copy(color = Ink.white(0x3A)))
        if (cover != null) {
            AsyncImage(
                model = rememberPicture(cover, edge),
                contentDescription = null,
                imageLoader = images,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
