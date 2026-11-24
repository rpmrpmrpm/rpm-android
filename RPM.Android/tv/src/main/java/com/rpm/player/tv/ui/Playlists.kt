package com.rpm.player.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import com.rpm.player.core.R
import com.rpm.player.core.frames.FolderFrameOverlay
import com.rpm.player.core.frames.FramedList
import com.rpm.player.core.frames.ListRoom
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.PlaylistBrowse
import com.rpm.player.core.library.PlaylistPhoto
import com.rpm.player.core.library.PlaylistPhotos
import com.rpm.player.core.library.PlaylistRow
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName
import com.rpm.player.core.motion.LocalMotion
import com.rpm.player.core.motion.Motion
import kotlinx.coroutines.launch

/**
 * The playlists screen, browsed as the folders the lists are filed in, as the phone's playlists view does
 * ([PlaylistBrowse]): the folders first, then the lists, each a card laid out like the album wall. The app's own
 * folders come first: the moods, holding the decades and one folder for each mood. The lists about listening stand at
 * the top. A list somebody made with no songs in it is not shown.
 *
 * OK on a folder's card opens the folder in place, and its first card takes the focus. Back goes up one folder, and
 * the card of the folder just left takes the focus ([FolderWalk]); at the top, Back leaves the screen as on every
 * other screen. OK on a list's card opens its page. The screen stays composed under that page ([ScreenStack]), so
 * Back from the page comes back to the same folder and the same card.
 *
 * A folder's card wears the folder frame ([FolderFrameOverlay]), lettered with its name, and says under it that it is
 * a folder. The heading names the folder that is open, after the folders above it.
 */
@Composable
fun PlaylistsScreen(collection: Collection, images: ImageLoader, onOpenList: (TrackList) -> Unit) {
    val resources = LocalContext.current.resources
    val shown = remember(collection) { collection.lists.filter { it.generated || it.tracks.isNotEmpty() } }
    var walk by remember { mutableStateOf(FolderWalk()) }
    val browse = remember(walk.at, shown, resources) { PlaylistBrowse.of(walk.at, shown) { listName(it.name, resources) } }
    BackHandler(enabled = LocalOnTop.current && browse.at.isNotEmpty()) { walk.up(browse.at)?.let { walk = it } }

    Box(Modifier.fillMaxSize()) {
        if (browse.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Message(stringResource(R.string.Viz_BrowseNoPlaylists)) }
        } else {
            // Each folder is a grid of its own, with its own scroll, so a folder opens at its top. The first folder
            // shown comes in with the screen's own fade; every folder after it fades in by itself.
            key(browse.at) {
                FolderGrid(
                    browse = browse,
                    focusAt = remember { walk.focusIndex(browse.rows) },
                    fadeIn = walk != FolderWalk(),
                    collection = collection,
                    images = images,
                    onPress = { row ->
                        when (row) {
                            is PlaylistRow.Folder -> walk = walk.into(row.folder.path)
                            is PlaylistRow.Playlist -> onOpenList(row.list)
                        }
                    },
                )
            }
        }
    }
}

/**
 * One folder's cards under its heading. The card at [focusAt] in [browse]'s rows takes the focus once the grid is
 * laid out, and the grid starts scrolled so that it is in view.
 */
@Composable
private fun FolderGrid(
    browse: PlaylistBrowse,
    focusAt: Int,
    fadeIn: Boolean,
    collection: Collection,
    images: ImageLoader,
    onPress: (PlaylistRow) -> Unit,
) {
    val target = remember { FocusRequester() }
    // Item 0 is the heading. A card in the first line keeps the heading in view; a card further down starts the grid
    // at its own line.
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (focusAt < WALL_COLUMNS) 0 else 1 + focusAt)
    val fades = fadeIn && LocalMotion.current < Motion.Still
    val alpha = remember { Animatable(if (fades) 0f else 1f) }
    LaunchedEffect(Unit) {
        // The cards are composed when the grid is first measured, which can be after this starts.
        for (attempt in 1..FOCUS_TRIES) {
            if (runCatching { target.requestFocus() }.getOrDefault(false)) break
            withFrameNanos { }
        }
    }
    if (fades) LaunchedEffect(Unit) { alpha.animateTo(1f, tween(FOLDER_FADE_MS, easing = EaseOut)) }
    val heading = if (browse.at.isEmpty()) stringResource(R.string.Playlists_Title) else browse.at.joinToString(" / ")
    LazyVerticalGrid(
        columns = GridCells.Fixed(WALL_COLUMNS),
        state = grid,
        contentPadding = PaddingValues(start = 34.px, end = 50.px, top = 64.px, bottom = 28.px),
        horizontalArrangement = Arrangement.spacedBy(22.px),
        verticalArrangement = Arrangement.spacedBy(22.px),
        modifier = Modifier.fillMaxSize().graphicsLayer {
            this.alpha = alpha.value
            // Each draw takes the alpha on its own, so no layer the size of the screen is made for the fade.
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading", contentType = "heading") {
            Box(Modifier.padding(top = 8.px)) { Heading(heading) }
        }
        itemsIndexed(browse.rows, key = { _, row -> row.id }, contentType = { _, row -> row is PlaylistRow.Folder }) { index, row ->
            val focus = if (index == focusAt) Modifier.focusRequester(target) else Modifier
            when (row) {
                is PlaylistRow.Folder -> WallCard(
                    art = { FolderArt(row, images) },
                    title = row.folder.name,
                    line = stringResource(R.string.Playlists_RowFolder),
                    explicit = false,
                    onClick = { onPress(row) },
                    modifier = focus,
                )
                is PlaylistRow.Playlist -> WallCard(
                    art = { ListPicture(row.list, collection, images, drift = false, keepFrame = false, modifier = Modifier.fillMaxSize()) },
                    title = row.name,
                    line = stringResource(R.string.Home_Songs, row.list.tracks.size.toString()),
                    explicit = row.list.tracks.any { it.tags?.advisory == EXPLICIT },
                    onClick = { onPress(row) },
                    modifier = focus,
                )
            }
        }
    }
}

/**
 * A folder's picture, as on the phone's playlists view (`PlaylistSleeve` there): the folder frame lettered with its
 * name ([FramedList.folder]) over what shows through its window. One of the app's own folders shows its own
 * photograph there ([PlaylistPhotos.folder]); a folder somebody made shows a still room in the app's colours
 * ([ListRoom]).
 */
@Composable
private fun FolderArt(row: PlaylistRow.Folder, images: ImageLoader) {
    val framed = remember(row.face, row.folder.name) { FramedList.folder(row.face, row.folder.name) }
    val photo = remember(row.face) { PlaylistPhotos.folder(row.face) }
    when {
        // Only a mood this build has no colours for has no frame: its photograph, if it has one, is shown alone.
        framed == null -> if (photo != null) CoverImage(photo, images, ContentScale.Crop, Modifier.fillMaxSize(), onShown = {})
        photo != null -> FolderCover(photo, framed, images)
        else -> {
            ListRoom(framed.look, framed.name, Modifier.fillMaxSize())
            FolderFrameOverlay(framed, Modifier.fillMaxSize())
        }
    }
}

/**
 * One of the app's own folders: its photograph, washed in the folder's colour by the image loader
 * ([com.rpm.player.core.library.PlaylistPhotoFetcher]), with the folder frame over it. The two come in together once
 * the photograph is ready, fading in over [COVER_FADE_MS] with an ease out, so the frame is never seen over an empty
 * window. A photograph the loader holds in memory is shown at once.
 */
@Composable
private fun FolderCover(photo: PlaylistPhoto, framed: FramedList, images: ImageLoader) {
    val scope = rememberCoroutineScope()
    val shown = remember(photo) { Animatable(0f) }
    var ready by remember(photo) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
        AsyncImage(
            model = photo,
            contentDescription = null,
            imageLoader = images,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                ready = true
                scope.launch {
                    if (state.result.dataSource == DataSource.MEMORY_CACHE) shown.snapTo(1f)
                    else shown.animateTo(1f, tween(COVER_FADE_MS, easing = EaseOut))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (ready) FolderFrameOverlay(framed, Modifier.fillMaxSize())
    }
}

/** How long a folder's photograph and frame take to fade in (0.2 s, as on the phone). */
private const val COVER_FADE_MS = 200

/** How long a folder opened, or gone back to, takes to fade in (0.22 s, as the phone's folders). */
private const val FOLDER_FADE_MS = 220

/** How many frames a folder just shown waits at most for its card to be composed before it stops asking for the
 * focus. */
private const val FOCUS_TRIES = 4
