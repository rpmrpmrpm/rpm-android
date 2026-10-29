package com.rpm.player.core.radio

import com.rpm.player.core.diag.Diag
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.library.shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The saved stations the radio shows, and whether they are the person's own to change (the iPhone's `SavedStations`).
 * On a shared collection, [reading] is set until its owner's stations have been read once, and [unreadable] when that
 * read failed and none were read before. The list is empty in both, so a screen can tell "none saved" from "not known".
 */
data class SavedStations(
    val stations: List<RadioStation>,
    val mine: Boolean,
    val reading: Boolean = false,
    val unreadable: Boolean = false,
)

/**
 * Whose saved stations the radio shows: the person's own ([RadioFavorites]), or, while a collection
 * that another account shares is open, that account's. The owner's stations are read from
 * `cloud/station-favorites?owner=`, once each time their collection is opened or the radio asks again
 * ([read]). They are never changed from here: nothing writes another account's stations.
 *
 * Each read is filed under the account that made it and the owner it is for. A read that lands after
 * the person signed out or opened another collection is filed where it belongs and is not shown for
 * another one. Main thread.
 */
class SharedStations(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    shelfOnScreen: StateFlow<LibraryState>,
    own: StateFlow<List<RadioStation>>,
    private val scope: CoroutineScope,
) {
    /** [failed] holds the owners whose read failed with nothing read for them before, so the radio can say so. */
    private data class Read(val user: String?, val byOwner: Map<String, List<RadioStation>>, val failed: Set<String> = emptySet())

    private val held = MutableStateFlow(Read(null, emptyMap()))
    private val shelf = shelfOnScreen.map { it.shelf }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, Shelf.Own)

    val saved: StateFlow<SavedStations> = combine(shelf, own, held) { shelf, mine, read ->
        when (shelf) {
            Shelf.Own -> SavedStations(mine, mine = true)
            is Shelf.Borrowed -> {
                val known = read.byOwner[shelf.ownerId]
                // The account is checked too: a list read for the account signed in before is not this one's.
                val signedIn = auth.session.value?.userId
                val mineToShow = known?.takeIf { read.user == signedIn }
                SavedStations(
                    mineToShow.orEmpty(),
                    mine = false,
                    reading = mineToShow == null && !(read.user == signedIn && shelf.ownerId in read.failed),
                    unreadable = mineToShow == null && read.user == signedIn && shelf.ownerId in read.failed,
                )
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, SavedStations(emptyList(), mine = true))

    init {
        scope.launch {
            shelf.collectLatest { if (it is Shelf.Borrowed) readOwner(it) }
        }
    }

    /** Reads the open collection's owner's stations again, where a shared collection is open. */
    fun read() {
        val now = shelf.value
        if (now is Shelf.Borrowed) scope.launch { readOwner(now) }
    }

    private suspend fun readOwner(owner: Shelf.Borrowed) {
        val user = auth.session.value?.userId ?: return
        val rows = try {
            withContext(Dispatchers.IO) { cloud.stationFavoritesOf(owner) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.w(TAG, "saved stations of ${owner.key} not read: $e")
            if (auth.session.value?.userId != user) return
            val was = held.value.takeIf { it.user == user } ?: Read(user, emptyMap())
            // A failure does not replace a list an earlier read found.
            if (owner.ownerId !in was.byOwner) held.value = was.copy(failed = was.failed + owner.ownerId)
            return
        }
        if (auth.session.value?.userId != user) return
        val stations = rows.filter { it.saved }.sortedBy { it.changedAt }.mapNotNull { it.station }
        val was = held.value.takeIf { it.user == user } ?: Read(user, emptyMap())
        held.value = was.copy(byOwner = was.byOwner + (owner.ownerId to stations), failed = was.failed - owner.ownerId)
        Diag.i(TAG, "${stations.size} saved stations of ${owner.key}")
    }

    private companion object {
        /** For `adb logcat -s rpm.radio`. */
        const val TAG = "rpm.radio"
    }
}
