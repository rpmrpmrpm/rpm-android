package com.rpm.player.phone.library

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.SongsAdded
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * The playlist writes the pages make, worded for the screen: the iPhone's `makePlaylist`, `addToPlaylist`,
 * `reorderPlaylist`, `renamePlaylist` and `deletePlaylist` (rpm/AppModel.swift, RPMSession/LibrarySession.swift), over `:core`'s
 * [PlaylistWriter][com.rpm.player.core.library.PlaylistWriter].
 *
 * **Each write runs in the app's scope, not the page's**, so leaving the page while the store is answering does not
 * cut the write off half way. The page only waits for the answer.
 */
class PlaylistWrites(private val services: Services, private val resources: Resources) {
    /** Makes a playlist; a sentence when it failed, null when it is done. */
    suspend fun make(name: String, folder: List<String>, tracks: List<CloudTrack>): String? = services.scope.async {
        services.playlistWriter.make(name, folder, tracks.map { it.rpmId })?.let { refusalText(resources, it, R.string.Rpm_Playlists_CreateFailed) }
    }.await()

    /**
     * What the plus key on [list]'s page needs, or null where songs cannot be added: a list the computer generated,
     * which it replaces on its next run, and a list on a borrowed shelf, which belongs to someone else.
     */
    fun adding(collection: Collection, list: TrackList): ListAdding? {
        if (!isWritable(collection, list)) return null
        return ListAdding(collection, list.folder) { songs ->
            services.scope.async {
                when (val added = services.playlistWriter.add(list, collection.shelf, songs.map { it.rpmId })) {
                    is SongsAdded.Added -> null
                    SongsAdded.AlreadyIn -> resources.getString(R.string.Rpm_PlaylistPick_AlreadyIn, listName(list.name, resources))
                    is SongsAdded.Refused -> refusalText(resources, added.why, R.string.Rpm_PlaylistPick_Failed)
                }
            }.await()
        }
    }

    /**
     * Whether [list]'s rows can be rearranged here. A write sends the whole order, so a list with songs this phone
     * cannot see is not rearranged: each drag would drop them.
     */
    fun canReorder(collection: Collection, list: TrackList): Boolean = isWritable(collection, list) && list.missing == 0

    /** Writes [order] as [list]'s running order; whether the store took it. */
    suspend fun move(collection: Collection, list: TrackList, order: List<CloudTrack>): Boolean =
        services.scope.async { services.playlistWriter.reorder(list, collection.shelf, order.map { it.rpmId }) == null }.await()

    /**
     * What the menu on [list]'s page offers (the iPhone's `RootView.menu(for:)`). A playlist the person made has the
     * four items that change it; a list rpm made has its new selection, [onNewList] where the computer sent another
     * one; another account's playlist has no menu, because every item would write to their account.
     */
    fun menu(collection: Collection, list: TrackList, onNewList: (() -> Unit)?): PlaylistMenu = when {
        isWritable(collection, list) -> PlaylistMenu.Own(
            onRename = { name -> rename(collection, list, name) },
            onDelete = { delete(collection, list) },
        )
        list.generated -> PlaylistMenu.Generated(onNewList)
        else -> PlaylistMenu.None
    }

    /** Gives [list] another name; a sentence when it failed, null when it is done. */
    suspend fun rename(collection: Collection, list: TrackList, name: String): String? = services.scope.async {
        services.playlistWriter.rename(list, collection.shelf, name)?.let { refusalText(resources, it, R.string.Rpm_Playlists_RenameFailed) }
    }.await()

    /** Removes [list] from the cloud; a sentence when it failed, null when it is done. The songs stay in the collection. */
    suspend fun delete(collection: Collection, list: TrackList): String? = services.scope.async {
        services.playlistWriter.delete(list, collection.shelf)?.let { refusalText(resources, it, R.string.Rpm_Playlists_DeleteFailed) }
    }.await()

    /** A playlist the person made, on their own shelf (the iPhone's `PlaylistSet.isWritable`). */
    private fun isWritable(collection: Collection, list: TrackList): Boolean = collection.shelf == Shelf.Own && !list.generated
}

@Composable
fun rememberPlaylistWrites(services: Services): PlaylistWrites {
    val resources = LocalContext.current.resources
    return remember(services, resources) { PlaylistWrites(services, resources) }
}

/**
 * Reads the playlists again each time the app comes back to the front (the iPhone's `.onChange(of: phase)` in
 * `RootView`): a list can be changed on a computer while the phone is away. Only the lists are read, which is one
 * request, not the whole library, and what is on screen stays while it runs and if it fails. The first start is not
 * a return: the lists were read with the library a moment ago.
 */
@Composable
fun RereadListsOnReturn(services: Services) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, services) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            if (first) first = false else services.scope.launch { services.library.rereadLists() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
