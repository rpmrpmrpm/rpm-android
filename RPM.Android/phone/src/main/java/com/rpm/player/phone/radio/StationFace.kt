package com.rpm.player.phone.radio

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.radio.StationBadge
import com.rpm.player.core.radio.StationLogo

/**
 * A station's own logo, wherever a sleeve would go: the iPhone's `StationFace` (RPMSharedUI/StationFace.swift).
 *
 * **A logo published with nothing behind it gets a made ground**, a soft figure in the logo's own hues pushed away
 * from its tone, which fills the square while [inset] keeps the logo off the edges. The dressing is `:core`'s
 * [StationBadge], the same one the cast and the lock screen use, so a station's square looks the same everywhere.
 * The logo is fetched by `:core`'s image loader through [StationLogo]: rpm's own copy first, then each address the
 * directory lists.
 *
 * **Every caller gives what to draw instead** ([fallback]), because some logo addresses no longer work, and the right
 * stand-in differs between a 40 dp row and a large tile. It shows until the logo has loaded, and stays when none does.
 * The logo fades in over 0.25 s, as on iPhone; one already in memory is drawn at once.
 *
 * The request is keyed on the station and the logo stamp, so a recycled row never shows the previous station's mark,
 * and a logo changed in the admin is fetched again. It asks for the size drawn, not bigger.
 *
 * @param edge the side of the square, which is also the size the logo is kept at.
 */
@Composable
fun StationFace(
    station: RadioStation,
    logoStamp: String,
    images: ImageLoader,
    edge: Dp,
    inset: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) = StationFace(StationLogo.of(station, logoStamp), images, edge, inset, modifier, fallback)

/** The same face from the logo a playing station carries ([com.rpm.player.core.playback.StationPlaying.logo]): the
 * mini player and the player have the station only as the queue item names it. */
@Composable
fun StationFace(
    logo: StationLogo,
    images: ImageLoader,
    edge: Dp,
    inset: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { edge.roundToPx() }.coerceAtLeast(1)
    val request = remember(logo, pixels) { stationFaceRequest(context, logo, pixels, inset / edge) }
    var worn by remember(logo) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (!worn) Box(Modifier.fillMaxSize().padding(inset), contentAlignment = Alignment.Center) { fallback() }
        AsyncImage(
            model = request,
            contentDescription = null,
            imageLoader = images,
            contentScale = ContentScale.Fit,
            onSuccess = { worn = true },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The request a [StationFace] makes for [logo], [pixels] on a side, with the logo [inset] by that share of the side.
 * Home reads its stations' logos early through it ([warmHome][com.rpm.player.phone.home.warmHome]), so the early read is
 * the same request the face makes later, and the face is answered from the loader's memory.
 */
fun stationFaceRequest(context: Context, logo: StationLogo, pixels: Int, inset: Float): ImageRequest =
    ImageRequest.Builder(context)
        .data(logo)
        .size(pixels)
        .transformations(StationBadge(logo.uuid, inset, pixels))
        .crossfade(FADE_MS)
        .build()

/** How long a logo takes to fade in, as the iPhone's 0.25 s ease-out. */
private const val FADE_MS = 250
