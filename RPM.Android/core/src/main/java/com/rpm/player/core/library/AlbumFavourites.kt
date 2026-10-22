package com.rpm.player.core.library

import android.util.Log
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudAlbumMark
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.ConsistentCopyVisibility
import kotlin.ExperimentalStdlibApi
import kotlinx.coroutines.withContext

/**
 * The person's favourite records, as `cloud/album-marks` lists them, with the marks this TV wrote laid over
 * them. A record's mark is filed under its cover key, in any of its spellings ([AlbumArtKey.spellings]): it
 * is set under the first spelling and cleared under every spelling it is set under.
 *
 * Read once per account, when the session starts. A mark written here is shown at once and kept while the read
 * row for its key is older than the stamp the store gave the write. So a read that started before the write
 * and ends after it cannot take the mark off the screen. A write not yet answered counts as newer than any
 * read; a write that is refused is taken off again.
 */
class AlbumFavourites(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    /** One mark written here: on or off, the stamp the store gave it ([UNANSWERED] until it answers), and which
     * write it was, so an answer to an older write for the same key does not stamp a newer one. */
    internal data class Written(val on: Boolean, val stamp: Long, val write: Long)

    /** What is held for one account: the rows read, by key, and the marks written here. Replaced whole, on the
     * main thread. */
    @OptIn(ExperimentalStdlibApi::class)
    @ConsistentCopyVisibility
    data class Marks internal constructor(
        val user: String?,
        private val read: Map<String, CloudAlbumMark>,
        private val written: Map<String, Written>,
    ) {
        /** Whether the record with these tracks is a favourite. */
        fun isFavourite(album: CloudAlbum): Boolean = AlbumArtKey.spellings(RecordPicture.COVER, album.tracks).any(::isOn)

        internal fun isOn(key: String): Boolean {
            val row = read[key]
            val mine = written[key]
            return if (mine != null && (row == null || row.updatedAt < mine.stamp)) mine.on else row?.favorite == true
        }

        internal fun withRead(rows: List<CloudAlbumMark>) = Marks(user, rows.associateBy { it.albumKey }, written)
        internal fun withWritten(key: String, mark: Written?) = Marks(user, read, if (mark == null) written - key else written + (key to mark))
        internal fun writtenAt(key: String) = written[key]
    }

    private val held = MutableStateFlow(Marks(null, emptyMap(), emptyMap()))
    val marks: StateFlow<Marks> = held.asStateFlow()

    private var writes = 0L

    init {
        scope.launch {
            auth.session.map { it?.userId }.distinctUntilChanged().collectLatest { user ->
                // Another account's marks are never kept for this one.
                held.value = Marks(user, emptyMap(), emptyMap())
                if (user != null) read(user)
            }
        }
    }

    private suspend fun read(user: String) {
        val rows = try {
            withContext(Dispatchers.IO) { cloud.albumMarks(Shelf.Own) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "favourite records not read: $e")
            return
        }
        if (held.value.user != user) return
        held.value = held.value.withRead(rows)
        Log.i(TAG, "${rows.count { it.favorite }} favourite records")
    }

    /** Marks [album] a favourite ([on]) or clears the mark. True when the store took every write. Main thread. */
    suspend fun mark(album: CloudAlbum, on: Boolean): Boolean {
        val user = held.value.user ?: return false
        val spellings = AlbumArtKey.spellings(RecordPicture.COVER, album.tracks)
        val keys = if (on) spellings.take(1) else spellings.filter(held.value::isOn)
        var all = true
        for (key in keys) {
            val write = ++writes
            held.value = held.value.withWritten(key, Written(on, UNANSWERED, write))
            val stamp = try {
                withContext(Dispatchers.IO) { cloud.putAlbumMark(key, on, OwnWriter.utcTicks(clock())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "favourite record not written: $e")
                null
            }
            // Only this write's own entry is changed, and only for the account it was made for.
            if (held.value.user != user || held.value.writtenAt(key)?.write != write) continue
            held.value = held.value.withWritten(key, stamp?.let { Written(on, it, write) })
            if (stamp == null) all = false
        }
        return all && keys.isNotEmpty()
    }

    private companion object {
        /** The stamp of a write the store has not answered: newer than any row a read can bring. */
        const val UNANSWERED = Long.MAX_VALUE

        /** For `adb logcat -s rpm.marks`. */
        const val TAG = "rpm.marks"
    }
}
