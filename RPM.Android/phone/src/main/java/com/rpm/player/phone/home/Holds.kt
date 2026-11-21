package com.rpm.player.phone.home

import android.content.res.Resources
import androidx.compose.runtime.staticCompositionLocalOf
import com.rpm.player.core.R
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.TrackList
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.MenuItem

/**
 * Something the person put on home's Quick selection themselves, as [PhoneSettings.quickAdded] stores it: a song by its
 * id, a record by its key, or a list by its id, each with its kind in front ("song:…", "album:…", "list:…").
 */
object QuickRef {
    private const val SONG = "song:"
    private const val ALBUM = "album:"
    private const val LIST = "list:"

    fun of(track: CloudTrack) = SONG + track.rpmId
    fun of(album: CloudAlbum) = ALBUM + album.key
    fun of(list: TrackList) = LIST + list.id

    /** The pick [ref] names in [collection], or null where the collection does not hold it (any more). */
    fun pick(ref: String, collection: Collection): QuickPick? = when {
        ref.startsWith(SONG) -> ref.removePrefix(SONG).let { id -> collection.tracksById[id]?.let { QuickPick.Song(it, collection.albumOf[id]) } }
        ref.startsWith(ALBUM) -> collection.album(ref.removePrefix(ALBUM))?.let { QuickPick.OfAlbum(it) }
        ref.startsWith(LIST) -> collection.list(ref.removePrefix(LIST))?.let { QuickPick.OfList(it) }
        else -> null
    }

    /** The ref of a pick on Quick selection; null for a station or a mixtape, which the person cannot add. */
    fun of(pick: QuickPick): String? = when (pick) {
        is QuickPick.Song -> of(pick.track)
        is QuickPick.OfAlbum -> of(pick.album)
        is QuickPick.OfList -> of(pick.list)
        else -> null
    }

    /**
     * Quick selection as home shows it: the suggested mixtapes first, then what the person added, newest first, then the
     * rest of the suggestions, at most [most] lines. A suggestion the person also added is shown once, where they put it.
     */
    fun merged(suggested: List<QuickPick>, added: List<String>, collection: Collection, most: Int): List<QuickPick> {
        val theirs = added.mapNotNull { pick(it, collection) }
        if (theirs.isEmpty()) return suggested.take(most)
        val refs = added.toHashSet()
        val mixtapes = suggested.takeWhile { it is QuickPick.Radio }
        val rest = suggested.drop(mixtapes.size).filter { pick -> of(pick)?.let { it !in refs } ?: true }
        return (mixtapes + theirs + rest).take(most)
    }
}

/**
 * What a long press offers on a record or a playlist anywhere in the app, and the Quick selection item of the song menu.
 * The words are read from [resources] at the moment of the press, so the rows of a list build nothing while they scroll.
 *
 * @param playNext puts a record's songs after the song playing, as the song menu's play next does for one song.
 */
class Holds(
    private val resources: Resources,
    private val settings: PhoneSettings,
    private val playNext: (CloudAlbum) -> Unit,
) {
    /** A record: play next, and Quick selection. */
    fun album(album: CloudAlbum): List<MenuItem> = listOf(
        MenuItem(resources.getString(R.string.AlbumMenu_PlayNext), Glyph.next) { playNext(album) },
        quick(QuickRef.of(album)),
    )

    /** A playlist: pinned to the top of the playlists view, and Quick selection. */
    fun list(list: TrackList): List<MenuItem> = listOf(pin(list.id), quick(QuickRef.of(list)))

    /** Adds to Quick selection, or takes out what the person put there. */
    fun quick(ref: String): MenuItem =
        if (ref in settings.quickAdded) MenuItem(resources.getString(R.string.Home_RemoveFromQuick), Glyph.close) { settings.removeFromQuick(ref) }
        else MenuItem(resources.getString(R.string.Home_AddToQuick), Glyph.quick) { settings.addToQuick(ref) }

    /** Pins a list to the top of the playlists view, or lets it go back to its own place. */
    fun pin(id: String): MenuItem =
        if (id in settings.pinnedLists) MenuItem(resources.getString(R.string.Playlists_Unpin), Glyph.pin) { settings.pinList(id, false) }
        else MenuItem(resources.getString(R.string.Playlists_Pin), Glyph.pin) { settings.pinList(id, true) }
}

/** The app's [Holds], or null where things have no long-press menu. Set at the root. */
val LocalHolds = staticCompositionLocalOf<Holds?> { null }
