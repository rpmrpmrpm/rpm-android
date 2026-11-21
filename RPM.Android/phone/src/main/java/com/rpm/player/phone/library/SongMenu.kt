package com.rpm.player.phone.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.ContextMenu
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.SwipeKey
import com.rpm.player.phone.design.SwipeRow
import com.rpm.player.phone.design.MenuItem

/**
 * What "play next" means for the rows of one list: the iPhone's `TrackActions.UpNext`. **It differs between an ordinary
 * song list and the queue itself.** A row on a record or in a list is not in the queue, so play next adds it. A row of
 * the queue is already queued: adding it again would make a second copy where the person means *move this song up*,
 * which is [MoveUp], given by the player's up next drawer to each row it can move.
 */
sealed interface UpNext {
    /** Put the song in the queue after the one playing. */
    class Send(val put: (CloudTrack) -> Unit) : UpNext

    /** No action: this row is already in the queue, and it is the song playing, the song already next, or a row of a
     * list that moves its rows with a grip. */
    data object ItIsTheQueue : UpNext

    /** A row of the queue that can still move up: [move] moves this row to just after the song playing. It is an action
     * for this one row and not a function of the song, because the same song can be in the queue twice. */
    class MoveUp(val move: () -> Unit) : UpNext
}

/** What play next does on [track] under [upNext], or null where it would do nothing. */
private fun playNextDeed(upNext: UpNext, track: CloudTrack): (() -> Unit)? = when (upNext) {
    is UpNext.Send -> { { upNext.put(track) } }
    is UpNext.MoveUp -> upNext.move
    UpNext.ItIsTheQueue -> null
}

/**
 * The long-press menu on a song, wherever a song is listed: the iPhone's `TrackMenu` and `TrackActions`. The items are
 * the iPhone's, in its order: play next, start radio, the favourite mark, and add to playlist.
 *
 * **Play next and start radio are on every row; the other two only on a song of this account's own shelf**
 * ([isMine]). A favourite mark is stored against an id, and a playlist names songs by id. A song on a shelf borrowed
 * from another account is not this account's, so the store would drop the write. The two items are left out there,
 * not dimmed: a dimmed item suggests "not now", but they never work from there.
 *
 * The app sets one menu at the root and hands it down the tree through [LocalSongMenu], as the iPhone hands down
 * `startRadio` through the environment. So every page that lists songs offers it without carrying five more
 * parameters, and the queue page is given the same menu with [UpNext.ItIsTheQueue].
 *
 * @param startRadio "Start radio" on the song: it plays now, and the queue keeps filling with songs like it. Null
 * where the app does not offer it.
 */
@Stable
class SongMenu(
    val isMarked: (CloudTrack) -> Boolean,
    val isMine: (CloudTrack) -> Boolean,
    val upNext: UpNext,
    val onMark: (CloudTrack, Boolean) -> Unit,
    val onFile: (CloudTrack) -> Unit,
    val startRadio: ((CloudTrack) -> Unit)?,
    internal val shown: SongMenuShown,
    /** The Quick selection item for a song: add it to home's Quick selection, or take it out. Null leaves it out. */
    val quick: ((CloudTrack) -> MenuItem)? = null,
) {
    /** The same menu, with play next meaning [upNext] instead. */
    fun with(upNext: UpNext) = SongMenu(isMarked, isMine, upNext, onMark, onFile, startRadio, shown, quick)

    /** The same menu, opened in [shown] and with add to playlist doing [onFile] instead: for a layer drawn over the
     * rest, such as the player, which shows its own open menu over itself. */
    fun drawnIn(shown: SongMenuShown, onFile: (CloudTrack) -> Unit) = SongMenu(isMarked, isMine, upNext, onMark, onFile, startRadio, shown, quick)
}

/**
 * The song menu that is open, if any, and the row it was opened on. There is one for the app: the menu is drawn over
 * everything at the root ([SongMenuLayer]), not inside the list, so it can reach past the page and over the tab bar.
 */
@Stable
class SongMenuShown {
    var open by mutableStateOf<Open?>(null)
        internal set

    /** The menu [menu] opened on [track], whose row is at [source] in the window. */
    class Open(val track: CloudTrack, val source: Rect, val menu: SongMenu)
}

/** The song menu of the page being drawn, or null where songs have none. Set at the root; see [SongMenu]. */
val LocalSongMenu = compositionLocalOf<SongMenu?> { null }

/**
 * A song row's press: a tap runs [onClick], and a long press opens the song menu ([LocalSongMenu]) on [track]. Use it in
 * place of the row's `clickable`. Where no menu is set, it is that plain `clickable`.
 *
 * The row's place is taken at the moment of the long press, from the coordinates its last layout left, so nothing is
 * read or written while the list scrolls.
 */
@Composable
fun Modifier.songPress(track: CloudTrack, presses: MutableInteractionSource, onClick: () -> Unit): Modifier {
    val menu = LocalSongMenu.current ?: return clickable(presses, indication = null, role = Role.Button, onClick = onClick)
    val haptics = LocalHapticFeedback.current
    val placed = remember { Placed() }
    return this
        .onPlaced { placed.at = it }
        .combinedClickable(
            interactionSource = presses,
            indication = null,
            role = Role.Button,
            onLongClick = {
                val at = placed.at?.takeIf { it.isAttached } ?: return@combinedClickable
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                menu.shown.open = SongMenuShown.Open(track, at.boundsInRoot(), menu)
            },
            onClick = onClick,
        )
}

/** Where a row was last placed. Held outside the state system on purpose: nothing draws from it. */
private class Placed {
    var at: LayoutCoordinates? = null
}

/**
 * The swipe keys of a song row in a list, as the iPhone's `TrackActions` gives a row of a SwiftUI `List`: pull it right
 * to play the song next, and left for add to playlist and the favourite mark. The left side is only on this account's
 * own songs, for the reason [SongMenu] gives, and it does not run a key on a full pull, as on iPhone. A row of the
 * queue has no play next here: the queue page has its own keys.
 *
 * **Only on a song that is a row of a list of its own** (the iPhone's `TrackActions.Swipes.onItsRow`). Song lines drawn
 * side by side in one sideways row, such as home's quick selection, take the long-press menu alone ([songPress]): a
 * sideways swipe through the panels must not open them. A list that sets its own swipe keys, such as the up next drawer,
 * does not take these as well. Each key is its icon alone, as SwiftUI draws a
 * `Label` in a swipe action; the long-press menu names the same actions in words.
 *
 * Where no menu is set ([LocalSongMenu]), the row is drawn as it is.
 */
@Composable
fun SongSwipe(track: CloudTrack, content: @Composable () -> Unit) {
    val menu = LocalSongMenu.current ?: return content()
    val haptics = LocalHapticFeedback.current
    val leading = playNextDeed(menu.upNext, track)?.let { deed ->
        SwipeKey(Glyph.next, "", Ink.white(0x33)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            deed()
        }
    }
    val trailing = if (menu.isMine(track)) {
        val marked = menu.isMarked(track)
        listOf(
            // A quiet grey. The red is the favourite's, so the other key must not compete with it.
            SwipeKey(Glyph.playlists, "", Ink.white(0x26)) { menu.onFile(track) },
            SwipeKey(if (marked) Glyph.unheart else Glyph.heart, "", Ink.red) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                menu.onMark(track, !marked)
            },
        )
    } else {
        emptyList()
    }
    if (leading == null && trailing.isEmpty()) return content()
    SwipeRow(leading, trailing, fullTrailing = false, content)
}

/**
 * The open song menu, over everything. The app's root draws this once, last, with the backdrop its pages draw into,
 * which the menu frosts.
 */
@Composable
fun SongMenuLayer(shown: SongMenuShown, backdrop: Backdrop) {
    val open = shown.open ?: return
    val track = open.track
    val menu = open.menu
    val haptics = LocalHapticFeedback.current
    val items = buildList {
        playNextDeed(menu.upNext, track)?.let { deed ->
            add(MenuItem(stringResource(R.string.AlbumMenu_PlayNext), Glyph.next) {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                deed()
            })
        }
        // On a borrowed row too: a radio only plays, it writes nothing.
        menu.startRadio?.let { radio ->
            add(MenuItem(stringResource(R.string.Track_StartMixtape), Glyph.mixtape) { radio(track) })
        }
        if (menu.isMine(track)) {
            val marked = menu.isMarked(track)
            // The label names what pressing it will do, not the state the song is in, as the desktop's drawer does.
            add(
                MenuItem(stringResource(if (marked) R.string.Track_UnsetFavorite else R.string.Track_SetFavorite), if (marked) Glyph.unheart else Glyph.heart) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    menu.onMark(track, !marked)
                },
            )
            add(MenuItem(stringResource(R.string.Rpm_Track_AddToPlaylist), Glyph.playlists) { menu.onFile(track) })
        }
        menu.quick?.let { add(it(track)) }
    }
    // Keyed by the opening, so a second long press while the first menu fades starts a fresh menu.
    androidx.compose.runtime.key(open) {
        ContextMenu(backdrop, open.source, items, onDismiss = { if (shown.open === open) shown.open = null })
    }
}
