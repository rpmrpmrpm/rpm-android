package com.rpm.player.phone.library

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.AlbumListing
import com.rpm.player.core.library.AlbumSort
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.ListOrdering
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.chromeGlass
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.dissolvesAtTop
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.scrolled
import com.rpm.player.phone.design.system
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rpm.player.phone.design.ScrollsToTop

/**
 * How the records tab shows the records: as a grid of covers, or as a list of records with their songs. The iPhone's
 * `LibraryLayout` (RPMUI/AlbumList.swift). Stored by name (`library.layout`), so the tab opens the way it was left.
 */
enum class LibraryLayout(val stored: String, @StringRes val label: Int, val mark: String) {
    Grid("grid", R.string.Library_ViewGrid, Glyph.grid),
    List("list", R.string.Library_ViewAlbums, Glyph.rows);

    companion object {
        fun of(stored: String?): LibraryLayout? = entries.firstOrNull { it.stored == stored }
    }
}

/** The orders the list's menu offers, as on iPhone: the list can be ordered by song title, and only the grid by date
 * added or by year. */
internal val LIST_OFFERED = listOf(
    ListOrdering.Artist to R.string.AlbumSort_Artist,
    ListOrdering.Album to R.string.AlbumSort_Album,
    ListOrdering.Track to R.string.AlbumSort_Track,
)

/**
 * The letter under the finger on the rail, while the finger is down; null otherwise. It is held outside the list, so the
 * large letter can be drawn over the page as glass ([HeldLetter]) without the list blurring a picture of itself, and it
 * is read only by the rail and the large letter, so a drag along the rail recomposes nothing else.
 */
@Stable
internal class RailHold {
    var letter by mutableStateOf<Char?>(null)
}

/** The space between the rail and the screen's right edge (606f390). */
private val RAIL_EDGE = 10.dp

/** The width of the rail, which is also the width a finger has to land on. */
private val RAIL_WIDTH = 22.dp

/** The width kept free on the right for the rail and the space on both sides of it, so no row runs under it and the
 * letters do not touch a song's length. */
private val RAIL_ROOM = RAIL_EDGE + RAIL_WIDTH + 14.dp

/** The tallest a letter's row on the rail may be. On a short phone the rows are shorter, so all twenty-seven fit. */
private val RAIL_TALLEST = 17.dp

/** The page's side margin (`RPMLayout.margin`). */
private val MARGIN = 20.dp

/**
 * The records as a list, built away from the main thread: the iPhone's `LibraryPage.list`. Ordering by song title reads
 * every song in the library, which is too long for a frame. The list is built again when the collection or the order
 * changes; a list built for another order stays up until the new one is ready, so the page does not go blank between
 * orders. [waiting] shows while the first list is built.
 */
@Composable
internal fun AlbumListHost(
    collection: Collection,
    albums: List<CloudAlbum>,
    order: ListOrdering,
    images: ImageLoader,
    playing: String?,
    hold: RailHold,
    topRoom: Dp,
    bottomRoom: Dp,
    onOpen: (CloudAlbum) -> Unit,
    onPlay: (CloudTrack, CloudAlbum) -> Unit,
    waiting: @Composable () -> Unit,
) {
    val listing by produceState<AlbumListing?>(null, collection, order) {
        value = withContext(Dispatchers.Default) { AlbumListing.of(albums, order) }
    }
    val built = listing
    if (built == null) {
        Box(Modifier.fillMaxSize().padding(top = topRoom)) { waiting() }
    } else {
        AlbumList(built, collection, images, playing, hold, topRoom, bottomRoom, onOpen, onPlay)
    }
}

/**
 * The records as a list, the phone's version of the desktop's album list and data table together: each record with its
 * songs under it, or, ordered by song title, every song as a row of its own. The iPhone's `AlbumList`. The order is
 * [AlbumListing]'s. The letters down the right edge jump to the first row filed under a letter ([LetterRail]).
 *
 * A record's line opens the record, as its cover does on the grid. A tap on a song plays its record from it, and a long
 * press gives the song menu; the rows have no swipe keys (`swipes: .menuOnly` on iPhone).
 *
 * **A record is one header item and one item per song**, not one tall item, so a record of a hundred songs is laid out
 * a screen at a time like everything else. The rail jumps by the lazy list's index, worked out once per listing.
 */
@Composable
private fun AlbumList(
    listing: AlbumListing,
    collection: Collection,
    images: ImageLoader,
    playing: String?,
    hold: RailHold,
    topRoom: Dp,
    bottomRoom: Dp,
    onOpen: (CloudAlbum) -> Unit,
    onPlay: (CloudTrack, CloudAlbum) -> Unit,
) {
    val list = rememberLazyListState()
    ScrollsToTop(list)
    val jumps = rememberCoroutineScope()
    // Where each row starts in the lazy list: a record takes one item for itself and one for each of its songs.
    val indexOf = remember(listing) {
        val at = HashMap<String, Int>(listing.rows.size)
        var index = 0
        for (row in listing.rows) {
            at[row.id] = index
            index += when (row) {
                is AlbumListing.Row.Record -> 1 + row.album.tracks.size
                is AlbumListing.Row.Song -> 1
            }
        }
        at
    }
    val present = remember(listing) { listing.firstUnder.keys }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().dissolvesAtTop(topRoom) { list.scrolled() },
            contentPadding = PaddingValues(top = topRoom + 4.dp, bottom = FOOT + bottomRoom),
        ) {
            for (row in listing.rows) {
                when (row) {
                    is AlbumListing.Row.Record -> {
                        val album = row.album
                        item(key = row.id, contentType = "record") {
                            RecordLine(album, collection, images) { onOpen(album) }
                        }
                        album.tracks.forEachIndexed { at, track ->
                            item(key = "${row.id}/$at", contentType = "line") {
                                SongLine(track, at, album, track.rpmId == playing) { onPlay(track, album) }
                            }
                        }
                    }
                    is AlbumListing.Row.Song -> item(key = row.id, contentType = "song") {
                        SongAlone(row.track, row.on, collection, images, row.track.rpmId == playing) { onPlay(row.track, row.on) }
                    }
                }
            }
        }
        LetterRail(
            present,
            hold,
            onPick = { letter ->
                val id = listing.firstUnder[letter] ?: return@LetterRail
                val index = indexOf[id] ?: return@LetterRail
                jumps.launch { list.scrollToItem(index) }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = topRoom, bottom = bottomRoom, end = RAIL_EDGE)
                .fillMaxHeight(),
        )
    }
}

// ── a record and its songs ──

/** The record's line: its sleeve, its name, and its artist and year, as on the desktop's album list. */
@Composable
private fun RecordLine(album: CloudAlbum, collection: Collection, images: ImageLoader, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = MARGIN, end = RAIL_ROOM, top = 22.dp, bottom = 6.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistSleeve(collection.cover(album), Glyph.record, edge = 64.dp, corner = 6.dp, mark = 22f, images)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(album.album, maxLines = 2, overflow = TextOverflow.Ellipsis, style = displayBold(17f).copy(color = Color.White))
            BasicText(byLine(album), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(13f).copy(color = Ink.white(0x99)))
        }
    }
}

/** Artist and year under the record's name, as on the desktop's album list. */
private fun byLine(album: CloudAlbum): String {
    val year = AlbumSort.yearOf(album.tracks)
    return if (year > 0) "${album.artist} · $year" else album.artist
}

/**
 * One song of a record: its number, its title, the artist where it is not the record's, and its length. The number is
 * the song's number on its record, or its place in the list where it has none.
 */
@Composable
private fun SongLine(track: CloudTrack, at: Int, album: CloudAlbum, playing: Boolean, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = MARGIN, end = RAIL_ROOM, top = 4.dp)
            .songPress(track, presses, onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tagged = track.tags?.trackNumber ?: 0
            BasicText(
                (if (tagged > 0) tagged else at + 1).toString(),
                Modifier.width(24.dp),
                style = display(13f).copy(color = if (playing) Color.White else Ink.white(0x77), textAlign = TextAlign.End),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(track.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(15f).copy(color = Color.White))
                val artist = track.artist
                if (!artist.isNullOrEmpty() && !artist.equals(album.artist, ignoreCase = true)) {
                    BasicText(artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(12f).copy(color = Ink.white(0x77)))
                }
            }
            BasicText(length(track.tags?.durationSeconds ?: 0.0), style = display(13f).copy(color = Ink.white(0x66)))
        }
        if (playing) PlayingMark(Modifier.align(Alignment.CenterStart))
    }
}

// ── one song, in song-title order ──

/** A song on its own row: its record's cover, its title, its artist and record, and its length. */
@Composable
private fun SongAlone(track: CloudTrack, on: CloudAlbum, collection: Collection, images: ImageLoader, playing: Boolean, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = MARGIN, end = RAIL_ROOM)
            .songPress(track, presses, onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 58.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaylistSleeve(collection.cover(on), Glyph.record, edge = 44.dp, corner = 6.dp, mark = 16f, images)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                BasicText(track.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = display(16f).copy(color = Color.White))
                BasicText(
                    listOfNotNull(track.artist, track.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = display(13f).copy(color = Ink.white(0x77)),
                )
            }
            BasicText(length(track.tags?.durationSeconds ?: 0.0), style = display(13f).copy(color = Ink.white(0x66)))
        }
        if (playing) PlayingMark(Modifier.align(Alignment.CenterStart))
    }
}

/** The playing mark in the margin, 12 dp left of the row: three points of white, which is what playing looks like
 * everywhere in this app. */
@Composable
private fun PlayingMark(modifier: Modifier) {
    Box(modifier.offset(x = (-12).dp).size(width = 3.dp, height = 24.dp).clip(RoundedCornerShape(percent = 50)).background(Color.White))
}

// ── the rail ──

/**
 * The index down the right edge of a list: A to Z, then '#' for everything else. The iPhone's `LetterRail`, the phone's
 * version of the desktop's `AlphabetRail`.
 *
 * A press, or a finger moving along the rail, goes to the first row under that letter. A letter with nothing under it
 * is faint and does nothing, as on the desktop: an index that jumps to a gap would be wrong. The rail takes every touch
 * on its strip, so a drag never falls between two letters, and it reports a letter only when it changes, so a drag
 * scrolls once per letter.
 */
@Composable
private fun LetterRail(present: Set<Char>, hold: RailHold, onPick: (Char) -> Unit, modifier: Modifier = Modifier) {
    val letters = AlbumListing.ALPHABET
    val haptics = rememberHaptics()
    val has by rememberUpdatedState(present)
    val pick by rememberUpdatedState(onPick)
    val density = LocalDensity.current
    BoxWithConstraints(modifier.width(RAIL_WIDTH)) {
        val step = minOf(RAIL_TALLEST, maxHeight / letters.size)
        val stepPx = with(density) { step.toPx() }
        val topPx = with(density) { (maxHeight - step * letters.size).toPx() } / 2
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(stepPx, topPx) {
                    fun touch(y: Float) {
                        val at = ((y - topPx) / stepPx).toInt().coerceIn(0, letters.size - 1)
                        val letter = letters[at]
                        if (letter == hold.letter) return
                        hold.letter = letter
                        if (letter !in has) return
                        haptics.tick()
                        pick(letter)
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        touch(down.position.y)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val finger = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!finger.pressed) break
                                finger.consume()
                                touch(finger.position.y)
                            }
                        } finally {
                            hold.letter = null
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column {
                val held = hold.letter
                for (letter in letters) {
                    val ink = when {
                        letter !in present -> Ink.white(0x40)
                        letter == held -> Color.White
                        else -> Ink.white(0xB8)
                    }
                    Box(Modifier.width(RAIL_WIDTH).height(step), contentAlignment = Alignment.Center) {
                        BasicText(letter.toString(), style = system(10f, FontWeight.SemiBold).copy(color = ink))
                    }
                }
            }
        }
    }
}

/**
 * The letter under the finger on the rail, shown large in the middle of the page while the finger is down, on glass. It
 * is drawn by the page, outside what [backdrop] records, because glass inside its own backdrop would blur a picture of
 * itself.
 */
@Composable
internal fun HeldLetter(hold: RailHold, backdrop: Backdrop, modifier: Modifier = Modifier) {
    val held = hold.letter
    // The last letter held, kept while the large letter fades out after the finger lifts. Not state: nothing should
    // draw again because it changed, only because the held letter did.
    val last = remember { CharArray(1) { ' ' } }
    if (held != null) last[0] = held
    val letter = held ?: last[0]
    AnimatedVisibility(
        held != null,
        modifier = modifier,
        enter = fadeIn(tween(120, easing = EaseOut)),
        exit = fadeOut(tween(120, easing = EaseOut)),
    ) {
        Box(
            Modifier.size(84.dp).chromeGlass(backdrop, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(letter.toString(), style = displayBold(40f).copy(color = Color.White))
        }
    }
}
