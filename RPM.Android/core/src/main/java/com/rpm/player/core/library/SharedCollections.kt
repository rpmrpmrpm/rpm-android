package com.rpm.player.core.library

import com.rpm.player.core.diag.Diag
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A collection another account shares with this one: its shelf, how its owner shows itself (null until
 * read, and when the owner has no profile), and when it was shared, in Unix seconds. */
data class SharedCollection(val shelf: Shelf.Borrowed, val face: Face?, val since: Long) {
    /** The name the owner goes by, when it has been read and there is one. */
    val name: String? get() = face?.name
}

/**
 * The collections other accounts share with the signed-in one (`cloud/shares`, the `received` half),
 * oldest share first, each with its owner's face (name, picture, supporter) from `profiles/{id}`. Read when the
 * session starts and again on [refresh]; a read that fails keeps the list already shown.
 *
 * One profile read per owner per session. An owner with no profile is remembered as having none; any other
 * failure is not remembered, so the next read asks again. Every answer is published only
 * if the user who asked is still signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedCollections(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    scope: CoroutineScope,
) {
    private val current = MutableStateFlow<List<SharedCollection>>(emptyList())
    val list: StateFlow<List<SharedCollection>> = current.asStateFlow()

    /** An owner's face as read: [face] null when the owner has no profile. Absent until read. */
    private data class Read(val face: Face?)

    private val faces = ConcurrentHashMap<String, Read>()
    private val asks = MutableStateFlow(0)

    init {
        scope.launch {
            combine(auth.session.map { it?.userId }.distinctUntilChanged(), asks) { user, _ -> user }
                .collectLatest { user ->
                    if (user == null) {
                        faces.clear()
                        current.value = emptyList()
                    } else {
                        read(user)
                    }
                }
        }
    }

    /** Reads the shares again, for a panel about to show them. */
    fun refresh() {
        asks.value += 1
    }

    /**
     * The rows a panel shows: the shared collections, and [open] too when it is a borrowed shelf no
     * longer in the list, so the row of the collection on screen never disappears from under it.
     */
    fun rows(open: Shelf): List<SharedCollection> {
        val listed = current.value
        if (open !is Shelf.Borrowed || listed.any { it.shelf == open }) return listed
        return listed + SharedCollection(open, faces[open.ownerId]?.face, since = 0)
    }

    /** How [shelf]'s owner shows itself, when it has been read and there is a profile. */
    fun faceOf(shelf: Shelf.Borrowed): Face? = faces[shelf.ownerId]?.face

    private suspend fun read(user: String) {
        val shares = try {
            withContext(Dispatchers.IO) { cloud.sharedWithMe() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        // One row per owner; never the person's own account. The server gives no order, so the oldest
        // share comes first, and the owner id breaks a tie.
        val owners = shares.filter { it.ownerId.isNotBlank() && it.ownerId != user }
            .sortedWith(compareBy({ it.createdAt }, { it.ownerId }))
            .distinctBy { it.ownerId }
        fun rows() = owners.map { SharedCollection(Shelf.Borrowed(it.ownerId), faces[it.ownerId]?.face, it.createdAt) }
        Diag.i(TAG, "${owners.size} collections shared with this account")
        publish(user, rows())
        for (owner in owners) {
            if (faces.containsKey(owner.ownerId)) continue
            try {
                faces[owner.ownerId] = Read(withContext(Dispatchers.IO) { cloud.profile(owner.ownerId) }?.let { Face.of(it, it.supporter) })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not remembered: the next read asks again.
            }
        }
        publish(user, rows())
    }

    private fun publish(user: String, rows: List<SharedCollection>) {
        if (auth.session.value?.userId == user) current.value = rows
    }

    private companion object {
        /** For `adb logcat -s rpm.share`. */
        const val TAG = "rpm.share"
    }
}
