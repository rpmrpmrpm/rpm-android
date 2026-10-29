package com.rpm.player.core.library

import com.rpm.player.core.diag.Diag
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which records each shelf's owner marked classical in the desktop's album editor (`cloud/album-marks`), so
 * volume levelling can leave them as they are unless the person asked for classical to be levelled too. Each
 * shelf is read once per session, when it is first opened: the person's own as the session starts, a borrowed
 * one when it is opened.
 *
 * Filed by the shelf the read was for, so a read that lands after another collection was opened is filed where
 * it belongs and nothing depends on the order reads arrive in. A shelf with no entry has not been read, or could
 * not be, and every record on it reads as not classical. [isClassical] is called from the playback thread, so the
 * marks are one immutable map that is swapped whole.
 */
class ClassicalMarks(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    shelfOnScreen: StateFlow<LibraryState>,
    scope: CoroutineScope,
) {
    /** The marks read for one account, by shelf. Written on the main thread only; read from any. */
    private data class Held(val user: String?, val byShelf: Map<Shelf, Set<String>>)

    @Volatile private var held = Held(null, emptyMap())

    init {
        scope.launch {
            combine(auth.session.map { it?.userId }.distinctUntilChanged(), shelfOnScreen.map { it.shelf }.distinctUntilChanged()) { user, shelf -> user to shelf }
                .collectLatest { (user, shelf) ->
                    // Another account's marks are never kept for this one.
                    if (held.user != user) held = Held(user, emptyMap())
                    if (user == null) return@collectLatest
                    if (Shelf.Own !in held.byShelf) read(user, Shelf.Own)
                    if (shelf !in held.byShelf) read(user, shelf)
                }
        }
    }

    /** Whether the record with these names, on [shelf], is marked classical. Matched under every spelling of the
     * record's cover key, as covers are: the desktop hashed whichever album artist its tags held. */
    fun isClassical(shelf: Shelf, albumArtist: String?, artist: String?, album: String?): Boolean {
        val keys = held.byShelf[shelf] ?: return false
        if (keys.isEmpty()) return false
        return AlbumArtKey.spellings(RecordPicture.COVER, albumArtist, artist, album).any { it in keys }
    }

    /** Whether [album], on [shelf], is marked classical, under every spelling its tracks give its cover key, or is
     * filed as classical music by its genre ([AlbumLines.classicalByGenre]). The record page asks this to list a
     * classical record as works; levelling asks the other overload, which is the mark alone. */
    fun isClassical(shelf: Shelf, album: CloudAlbum): Boolean {
        if (AlbumLines.classicalByGenre(album.tracks)) return true
        val keys = held.byShelf[shelf] ?: return false
        if (keys.isEmpty()) return false
        return AlbumArtKey.spellings(RecordPicture.COVER, album.tracks).any { it in keys }
    }

    private suspend fun read(user: String, shelf: Shelf) {
        val marks = try {
            withContext(Dispatchers.IO) { cloud.albumMarks(shelf) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.w(TAG, "classical marks of ${shelf.key} not read: $e")
            return
        }
        if (auth.session.value?.userId != user || held.user != user) return
        val classical = marks.filter { it.classical == true }.map { it.albumKey }.toSet()
        held = Held(user, held.byShelf + (shelf to classical))
        Diag.i(TAG, "${classical.size} classical records on ${shelf.key}")
    }

    private companion object {
        /** For `adb logcat -s rpm.level`. */
        const val TAG = "rpm.level"
    }
}
