package com.rpm.player.phone.design

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import coil3.ImageLoader
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Loads the pictures of the items just outside a grid's view into the image loader's memory, so they are ready when
 * those items scroll in.
 *
 * A tile asks for its picture only when it is composed, and a picture from the disk cache still has to be read and
 * decoded, so the tiles that scrolled in were blank for a moment, even for covers seen before. Here the [ahead] items
 * after the last one in view, and before the first, are asked for while the grid stands or scrolls.
 *
 * **[request] must build exactly the request the tile builds** (the same data and the same pixel size, as
 * [rememberPicture] does), or the tile's request misses the memory cache and the preload is wasted.
 *
 * @param count how many items the grid holds.
 * @param request the picture of the item at an index, or null for an item with none.
 */
@Composable
fun PreloadAhead(grid: LazyGridState, count: Int, ahead: Int, images: ImageLoader, request: (Int) -> ImageRequest?) {
    LaunchedEffect(grid, count, ahead, request) {
        val asked = HashSet<Int>()
        snapshotFlow {
            val shown = grid.layoutInfo.visibleItemsInfo
            (shown.firstOrNull()?.index ?: 0) to (shown.lastOrNull()?.index ?: -1)
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                if (last < 0) return@collect
                val from = (first - ahead).coerceAtLeast(0)
                val to = (last + ahead).coerceAtMost(count - 1)
                // Only the window is remembered, so an item scrolled far away and back is asked for again: the
                // memory cache may have let it go.
                asked.retainAll { it in from..to }
                for (index in (last + 1..to) + (first - 1 downTo from)) {
                    if (asked.add(index)) request(index)?.let(images::enqueue)
                }
            }
    }
}
