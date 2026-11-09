package com.rpm.player.phone.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.rpm.player.phone.R
import com.rpm.player.core.R as CoreR
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.rpm.player.phone.design.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The background of the app, as on the iPhone (`VinylGround` in RPMUI/VinylGround.swift). It is the app's
 * colour, the backdrop picture over it, and a 20% black layer over that. Nothing else.
 *
 * **Drawn in one pass.** The picture is decoded once at the window's size, cropped to what the window shows, with
 * the shade already on it ([Plates]), and drawn pixel for pixel. Drawn as three layers (the colour, the picture
 * scaled from about 15 MB, the shade) it was three full-screen fills on every frame, and with the window's own
 * background the screen was painted four times over before a single cover: on the Titan's GPU that was most of a
 * frame while a wall scrolled.
 *
 * The picture is the one chosen in the profile ([LocalGround]); the default is RPaMp's ground. Every page draws its
 * own ground, so the choice is read here and not passed through every page, as the iPhone reads `AppStorage`.
 *
 * **Laid out in the window, not in the container that draws it.** The iPhone app works the same way. If each
 * screen cropped the picture to its own box, the background would move between screens of different heights.
 * The picture fills the window. It is centred across the window and hangs from the top, wherever the
 * container is.
 *
 * **Clipped to its container.** The picture is drawn at the window's size, so without the clip a page that is still
 * sliding in from the side paints its ground over the whole screen, and the page it slides over vanishes in one frame;
 * a page sliding out hides the one it uncovers until it has gone, which then pops in.
 */
@Composable
fun VinylGround(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val choice = LocalGround.current
    val window = LocalWindowInfo.current.containerSize
    val plate by produceState(Plates.held(choice, window), choice, window) { value = Plates.ground(context, choice, window) }
    var here by remember { mutableStateOf(Offset.Zero) }

    // One pass: the plate already holds the picture at the window's size with the shade on it. Until it is decoded,
    // the app's colour and the shade.
    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onGloballyPositioned { here = it.positionInRoot() }
            .drawBehind {
                val picture = plate
                if (picture == null) {
                    drawRect(Ink.ground)
                    drawRect(SHADE)
                } else {
                    drawImage(picture, topLeft = Offset(-here.x, -here.y))
                }
            },
    )
}

/** The 20% black over the picture (`VinylGround.swift`). */
private val SHADE = Color.Black.copy(alpha = 0x33 / 255f)

/**
 * The built-in backdrops, by the id that is stored (`Backdrop.Choice` on iPhone, the desktop's `Backdrop.cs`). The
 * phone's pressed vinyl replaces the desktop's table of records, so Gold is the vinyl pressed in gold.
 *
 * @property id what `backdrop.picture` holds. The desktop stores the same ids.
 * @property file the picture. Vinyl and Gold are two colourings of one picture.
 * @property nameKey its name in the picker.
 */
enum class Ground(val id: String, @DrawableRes val file: Int, @StringRes val nameKey: Int) {
    /** The default: RPaMp's ground, bands of cyan light in deep blue. */
    Standard("", R.drawable.background_rpamp, CoreR.string.Settings_BackgroundDefault),

    /** Three records, lit, drawn for a phone, in the blue pressing. */
    Vinyl("vinyl", R.drawable.background_vinyl, CoreR.string.Settings_BackgroundVinyl),

    /** The visualizer's own room, shot against a red record. */
    Pegasus("pegasus", R.drawable.background_pegasus, CoreR.string.Settings_BackgroundPegasus),

    /** A printed score in warm sepia. */
    SheetMusic("sheet-music", R.drawable.background_sheet_music, CoreR.string.Settings_BackgroundSheetMusic),

    /** The same three records pressed in gold. The supporter's. */
    Gold("gold", R.drawable.background_vinyl_gold, CoreR.string.Settings_BackgroundGold);

    /** Whether supporting rpm is what this one is for. The tile is shown to everybody, so people can see it. */
    val isSupporters: Boolean get() = this == Gold

    companion object {
        /**
         * Which backdrop is shown, given what is stored and whether this launch may stand on gold
         * (`Backdrop.standing` on iPhone). Anything unknown is the default. Older settings' "rpamp" is the default and
         * "night" is the vinyl. Gold for an account that is not a supporter is the vinyl in blue. What is stored is
         * left alone, so support that comes back shows the same backdrop again.
         */
        fun standing(stored: String, gold: Boolean): Ground = when (stored) {
            "rpamp" -> Standard
            "night" -> Vinyl
            Gold.id -> if (gold) Gold else Vinyl
            else -> entries.firstOrNull { it.id == stored } ?: Standard
        }
    }
}

/** The backdrop every ground on screen draws. Provided once, over the whole app, from the phone's settings. */
val LocalGround = staticCompositionLocalOf { Ground.Standard }

/**
 * The background picture, decoded once and kept for the life of the process, ready to draw: scaled to cover the
 * window as the iPhone fills it (centred across, hanging from the top), cropped to the window, with the 20% shade
 * painted on. The same picture is behind every screen. Only one is kept, not all five: the person looks at one.
 */
private object Plates {
    @Volatile
    private var kept: Triple<Ground, IntSize, ImageBitmap>? = null

    fun held(choice: Ground, window: IntSize): ImageBitmap? =
        kept?.takeIf { it.first == choice && it.second == window }?.third

    suspend fun ground(context: Context, choice: Ground, window: IntSize): ImageBitmap? {
        held(choice, window)?.let { return it }
        if (window.width <= 0 || window.height <= 0) return null
        return withContext(Dispatchers.IO) { plate(context, choice.file, window) }?.also { kept = Triple(choice, window, it) }
    }

    private fun plate(context: Context, @DrawableRes file: Int, window: IntSize): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, file, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val fill = max(window.width / bounds.outWidth.toFloat(), window.height / bounds.outHeight.toFloat())
        // Decoded no smaller than it is drawn, in the largest power-of-two step that allows.
        var sample = 1
        while (fill * sample * 2 <= 1f) sample *= 2
        val source = BitmapFactory.decodeResource(context.resources, file, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = max(window.width / source.width.toFloat(), window.height / source.height.toFloat())
        val out = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.translate((window.width - source.width * scale) / 2f, 0f)
        canvas.scale(scale, scale)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.setMatrix(null)
        canvas.drawColor(SHADE.toArgb())
        source.recycle()
        out.prepareToDraw()
        return out.asImageBitmap()
    }
}

/**
 * One small backdrop for the row of tiles in the profile (`BackdropTile` on iPhone). It decodes its own small copy,
 * off the main thread, at the size it is drawn: five full pictures for a row of tiles would be about 75 MB.
 */
@Composable
fun GroundTile(choice: Ground, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val edge = with(density) { max(width.toPx(), height.toPx()).roundToInt() }
    val picture by produceState<ImageBitmap?>(null, choice, edge) {
        value = withContext(Dispatchers.IO) { thumbnail(context, choice.file, edge) }
    }
    Box(modifier.size(width, height).clipToBounds()) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** The picture decoded at the smallest power-of-two step whose long side is still at least [edge] pixels. */
private fun thumbnail(context: Context, @DrawableRes file: Int, edge: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeResource(context.resources, file, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
    return BitmapFactory.decodeResource(context.resources, file, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
