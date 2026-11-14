package com.rpm.player.phone.home

import android.content.Context
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.GeneratedListLook
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.QuickPick
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.radio.StationLogo
import com.rpm.player.phone.radio.stationFaceRequest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** How many cards of each row [warmHome] reads: the cards a row shows before it is swiped, and one more
 * (`HomePage.firstCards` on iPhone). */
private const val FIRST_CARDS = 4

/** How many pictures [warmHome] reads at the same time (`HomePage.readsAtOnce` on iPhone). */
private const val READS_AT_ONCE = 4

/** The sizes home draws its pictures at, which [warmHome] reads them at. */
internal class HomeArt(val quick: Dp, val quickStationInset: Dp, val row: Dp)

/**
 * **The pictures on home are read when the page gets its rows, not when each is scrolled to** (`HomePage.warm` on
 * iPhone). The page itself is built at once, but each row is a lazy row or a pager: it builds a card only when the card
 * comes on screen, and the card asks for its picture then. A picture that is only on disk or on the network would
 * appear under the reader's eyes. Each request here is the one the card makes later ([warmRequests]), so a picture read
 * here is answered from the image loader's memory on the card's first frame.
 *
 * Only what a row shows before it is swiped is read: the quick selection, in the order its lines are drawn, and the
 * first cards of each row and of the lists. The banner reads its own cards. They are read [READS_AT_ONCE] at a time, in
 * order, and the reading stops when the page leaves or its rows change.
 */
internal suspend fun warmHome(images: ImageLoader, requests: List<ImageRequest>) = coroutineScope {
    val next = requests.iterator()
    // Every reader runs on the caller's thread, so they share the iterator without a lock.
    repeat(minOf(READS_AT_ONCE, requests.size)) {
        launch {
            while (next.hasNext()) images.execute(next.next())
        }
    }
}

/**
 * What [warmHome] reads, as the cards will ask for it: the quick selection's sleeves at [HomeArt.quick] and its
 * stations' logos as their face makes them ([stationFaceRequest]), then the first [FIRST_CARDS] sleeves of each of
 * [rows] and of [lists] at [HomeArt.row]. A sleeve is asked for at the pixels it is drawn at, cropped as the card crops
 * it ([Scale.FILL]) and with the precision the card's image asks for, because the loader's memory answers only a request
 * that matches. A list that stands on its room ([GeneratedListLook]) draws no picture and has none read.
 */
internal fun warmRequests(
    context: Context,
    density: Density,
    art: HomeArt,
    quick: List<QuickPick>,
    rows: List<List<CloudAlbum>>,
    lists: List<TrackList>,
    collection: Collection,
    logoStamp: String,
): List<ImageRequest> {
    val quickPx = with(density) { art.quick.roundToPx() }.coerceAtLeast(1)
    val rowPx = with(density) { art.row.roundToPx() }.coerceAtLeast(1)
    fun sleeve(picture: Any?, pixels: Int): ImageRequest? = picture?.let {
        ImageRequest.Builder(context).data(it).size(pixels, pixels).scale(Scale.FILL).precision(Precision.INEXACT).build()
    }
    fun list(list: TrackList, pixels: Int): ImageRequest? =
        if (GeneratedListLook.of(list)?.roomed == true) null else sleeve(collection.cover(list), pixels)
    return buildList {
        for (pick in quick) {
            val request = when (pick) {
                is QuickPick.Song -> sleeve(pick.album?.let { collection.cover(it) }, quickPx)
                is QuickPick.Radio -> sleeve(pick.album?.let { collection.cover(it) }, quickPx)
                is QuickPick.OfAlbum -> sleeve(collection.cover(pick.album), quickPx)
                is QuickPick.OfList -> list(pick.list, quickPx)
                is QuickPick.OfStation -> stationFaceRequest(context, StationLogo.of(pick.station, logoStamp), quickPx, art.quickStationInset / art.quick)
                    .newBuilder().scale(Scale.FIT).precision(Precision.INEXACT).build()
            }
            if (request != null) add(request)
        }
        for (row in rows) {
            for (album in row.take(FIRST_CARDS)) sleeve(collection.cover(album), rowPx)?.let(::add)
        }
        for (each in lists.take(FIRST_CARDS)) list(each, rowPx)?.let(::add)
    }
}
