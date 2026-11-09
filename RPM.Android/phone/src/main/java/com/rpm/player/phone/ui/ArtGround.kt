package com.rpm.player.phone.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import com.rpm.player.phone.design.BlurredPicture
import com.rpm.player.core.room.Rgb

/**
 * [picture] is anything the image loader can draw: a record's cover, or an artist's photograph on the home page.
 * [accent] is the cover's accent ([rememberAccent]), laid over the blurred picture; none for a photograph.
 *
 * The background of a page that uses a record as its look. The iPhone app has the same `ArtGround`
 * (RPMUI/ArtGround.swift), and the album page and the player both use it. It is the app's own background,
 * with the picture behind it blurred until it is not recognisable. A black gradient sits on top. It is
 * darkest at the foot, because a long list of songs must stay readable there, and darker all over in night mode
 * ([LocalNightMode]). The picture is blurred once as
 * it loads ([BlurredPicture]), not on every frame.
 */
@Composable
fun ArtGround(picture: Any?, images: ImageLoader, accent: Rgb? = null, lead: Rgb? = null) {
    val night = LocalNightMode.current
    Box(Modifier.fillMaxSize()) {
        VinylGround()
        // A new picture fades in over the old one, with its colour. The iPhone app does the same when the record changes.
        Crossfade(picture to lead, animationSpec = tween(350), label = "ground") { (shown, colour) ->
            val led = if (night) null else colour
            // A page with a colour and no picture (an artist's room) stands on the colour alone.
            if (shown == null) {
                if (led != null) Box(Modifier.fillMaxSize().background(led.color()))
                return@Crossfade
            }
            Box(Modifier.fillMaxSize()) {
                if (led != null) Box(Modifier.fillMaxSize().background(led.color()))
                BlurredPicture(
                    shown,
                    images,
                    sigma = 60.dp,
                    modifier = Modifier.fillMaxSize().alpha(if (led != null) UNDER_LEAD else 1f),
                    colorFilter = if (night) null else COLOURFUL,
                )
            }
        }
        // The colour read off the picture, laid over it at about a fifth, so a ground made from a grey sleeve still has
        // the record's own cast. A photograph of a person gets none: the colour there is the photograph's.
        if (picture != null && accent != null) {
            Box(Modifier.fillMaxSize().background(accent.color().copy(alpha = 0.18f)))
        }
        // Heavier at the foot, where the rows of text are.
        Box(Modifier.fillMaxSize().background(if (night) NIGHT_VEIL else VEIL))
    }
}

/**
 * Whether pages that stand on a photograph are drawn darker: night mode, the switch in the profile's Background card. The
 * iPhone keeps the same switch under the same key (`NightMode`, `look.night`). Provided once, over the whole app, from
 * the phone's settings.
 *
 * Off, the ground is the picture a little more colourful ([COLOURFUL]) under a light veil ([VEIL]), and an artist's
 * photograph has no darkening over it. On, the ground has the heavy veil ([NIGHT_VEIL]) over the picture's own colours,
 * and an artist's photograph is dimmed and darker toward its foot, as home's banner is.
 */
val LocalNightMode = staticCompositionLocalOf { false }

/**
 * How strongly the blurred picture is drawn over a leading colour. The artist page passes its photograph's main colour
 * as [ArtGround]'s `lead`: the page then reads as that colour, with the photograph a faint texture in it. Outside night
 * mode only, as on iPhone (`ArtGround.lead`).
 */
private const val UNDER_LEAD = 0.2f

/** The picture made a little more colourful outside night mode, so the page keeps its colour under the veil. */
private val COLOURFUL = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.4f) })

/** The veil outside night mode: light over the top half of the page, heavier toward the foot. */
private val VEIL = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0x38 / 255f),
    0.42f to Color.Black.copy(alpha = 0x3D / 255f),
    1f to Color.Black.copy(alpha = 0x80 / 255f),
)

/** The veil in night mode: heavy over the whole page. */
private val NIGHT_VEIL = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0x59 / 255f),
    0.42f to Color.Black.copy(alpha = 0x8C / 255f),
    1f to Color.Black.copy(alpha = 0xBF / 255f),
)
