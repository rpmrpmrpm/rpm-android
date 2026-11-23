package com.rpm.player.phone.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

// ── the frost ──

/**
 * What the glass on a screen blurs: the screen's background and everything drawn on it, recorded into a
 * layer as it is drawn ([backdropSource]). The iPhone's glass blurs the live scene behind it (`RPMFrost`).
 * An Android view cannot read what is behind it, so the screen gives its own drawing to the glass instead.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Where the source sits in the window. A glass surface reads its own position against this. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** How big the source is. The glass blurs nothing outside it (see [frost]). */
    internal var size by mutableStateOf(IntSize.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/**
 * Records what this draws into [backdrop] for the glass over it, and draws it. Apply it to content that is
 * BEHIND the glass, never to a container that holds the glass. A surface that blurs a picture of itself
 * keeps drawing forever.
 */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned {
        backdrop.origin = it.positionInRoot()
        backdrop.size = it.size
    }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * The blur of the chrome: the tab capsule and the mini player. A standard deviation of 24, the default
 * of `GlassBrush` in the Uno phone head. The iPhone's frost (`RPMFrost`) is matched to this value.
 */
val CHROME_BLUR: Dp = 24.dp

/** The blur of a card. The Uno phone head's cards also use 30. */
val CARD_BLUR: Dp = 30.dp

/**
 * The frost in [shape]: [backdrop] blurred at [blur], clipped to the shape, with the wash on top. The
 * frost is a blur and a `#22000000` veil and nothing else. This matches the Uno phone head's glass, which
 * the iPhone's frost is adjusted to match. The iPhone's saturation and extra veil correct the grey of its
 * system material. Android has no such material, so nothing needs correcting here.
 *
 * Only the part of the backdrop under the surface is blurred. Three standard deviations of margin are
 * added around it, so the edges blur from real content. The margin stops at the backdrop's own edges, and the
 * blur clamps there: past the screen's edge it repeats the screen's last pixels, as the Uno phone head's
 * `GlassBrush.Clamp` does. A margin that ran past the screen would hold nothing, the blur would mix that
 * emptiness into the frost, and the sharp page under the glass would show through it near the edge (it did:
 * home's headings showed through the left end of the mini player and the tab bar).
 *
 * Android 9 to 11 cannot blur a drawing, because `RenderEffect` starts at [Build.VERSION_CODES.S]. Below
 * that the surface is [Ink.frostless] instead of a see-through window onto the page.
 */
@Composable
fun Modifier.frost(backdrop: Backdrop, shape: Shape, blur: Dp): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return background(Ink.frostless, shape).background(Ink.glassWash, shape)

    val blurred = rememberGraphicsLayer()
    var here by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { here = it.positionInRoot() }
        .drawBehind {
            val sigma = blur.toPx()
            val margin = ceil(sigma * 3)
            // The page under the glass is drawn into the layer at a quarter of its size and blurred there, then
            // scaled back up. A blur this wide keeps no detail the smaller copy loses, and the layer and its blur
            // cost a sixteenth of the pixels. At full size the two chrome surfaces took about 40 ms of every
            // frame on the Titan while the page under them scrolled.
            val radius = radiusFor(sigma * FROST_SCALE)
            // The part of the backdrop that is blurred, in the window: the surface and its margin, cut to the backdrop.
            val left = maxOf(here.x - margin, backdrop.origin.x)
            val top = maxOf(here.y - margin, backdrop.origin.y)
            val right = minOf(here.x + size.width + margin, backdrop.origin.x + backdrop.size.width)
            val bottom = minOf(here.y + size.height + margin, backdrop.origin.y + backdrop.size.height)
            if (right <= left || bottom <= top) return@drawBehind
            blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            blurred.record(
                size = IntSize(
                    ceil((right - left) * FROST_SCALE).toInt().coerceAtLeast(1),
                    ceil((bottom - top) * FROST_SCALE).toInt().coerceAtLeast(1),
                ),
            ) {
                scale(FROST_SCALE, FROST_SCALE, pivot = Offset.Zero) {
                    translate(backdrop.origin.x - left, backdrop.origin.y - top) {
                        drawLayer(backdrop.layer)
                    }
                }
            }
            clipPath(outlinePath(shape.createOutline(size, layoutDirection, this))) {
                translate(left - here.x, top - here.y) {
                    scale(1 / FROST_SCALE, 1 / FROST_SCALE, pivot = Offset.Zero) { drawLayer(blurred) }
                }
            }
            drawOutline(shape.createOutline(size, layoutDirection, this), Ink.glassWash)
        }
}

/** The scale the frost blurs at: a quarter of the screen's pixels on each side. */
private const val FROST_SCALE = 0.25f

/**
 * Skia turns a blur radius into a standard deviation as `0.57735 r + 0.5`. This function does the reverse,
 * so the radius it returns gives the wanted standard deviation. The Uno phone head and the desktop state
 * their blurs as standard deviations. SwiftUI's `blur(radius:)` is read the same way.
 */
fun radiusFor(sigma: Float): Float = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0f)

private fun outlinePath(outline: Outline): Path = when (outline) {
    is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
    is Outline.Generic -> outline.path
}

// ── glass on glass ──

/**
 * Whether what is being drawn sits on a glass surface. A key or a field on glass draws a flat fill, never a
 * second frost, as in the iPhone app (`OnGlassKey`). The phone follows the iPhone look from before iOS 26,
 * where keys and fields are flat fills everywhere. So only the cards and the chrome read this value today.
 * It stays so that a frosted key cannot be placed on a card by mistake.
 */
val LocalOnGlass = staticCompositionLocalOf { false }

// ── the card ──

/**
 * A panel that floats over the page: a picker, a sheet's body, the sign-in form. It matches the iPhone's
 * `GlassCard` before iOS 26: the frost, the wash and the edge, in an 18 dp rounded rectangle. Everything on
 * it is on glass ([LocalOnGlass]). The glass colour ([AppColour]) lies over the frost as Liquid Glass's tint does.
 */
@Composable
fun GlassCard(backdrop: Backdrop, modifier: Modifier = Modifier, radius: Dp = 18.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .frost(backdrop, shape, CARD_BLUR)
            .glassTint(shape, AppColour.ON_GLASS)
            .border(1.dp, Ink.cardBorder, shape)
            .clip(shape),
    ) {
        CompositionLocalProvider(LocalOnGlass provides true) { content() }
    }
}

/**
 * The chrome's glass in [shape]: the frost with a thin edge, as `rpmGlass` on iPhone before iOS 26, and the glass colour
 * ([AppColour]) over the frost as Liquid Glass's tint is on iOS 26.
 */
@Composable
fun Modifier.chromeGlass(backdrop: Backdrop, shape: Shape): Modifier =
    frost(backdrop, shape, CHROME_BLUR).glassTint(shape, AppColour.ON_GLASS).border(1.dp, Ink.chromeEdge, shape)

// ── keys ──

/** How heavy a key is: the one action of a screen, or any other. */
enum class Weight { Primary, Ghost }

/**
 * A key drawn flat, as the iPhone's `RPMGlassButton` is drawn before iOS 26. It is a translucent fill with
 * the glass edge, the glass colour as a wash over the fill ([AppColour]), and a white veil while pressed. It is 44 dp high, with a 10 dp corner. The label uses the
 * system font, size 15, semi-bold.
 *
 * When disabled, the face stays lit and only the label fades. A key that goes dark would look like a hole
 * in the card.
 *
 * @param stretches whether it takes the full width offered to it. Use true for the one key a screen is about,
 * and false for a key beside another key.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weight: Weight = Weight.Ghost,
    enabled: Boolean = true,
    stretches: Boolean = true,
    height: Dp = 44.dp,
    radius: Dp = 10.dp,
    inset: Dp = 20.dp,
    size: Float = 15f,
    label: @Composable RowScope.(TextStyle) -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    val ink = if (weight == Weight.Primary) Ink.white(0xF5) else Ink.white(0xF0)
    val style = system(size, FontWeight.SemiBold).copy(color = ink)
    Row(
        modifier
            .then(if (stretches) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(if (weight == Weight.Primary) Ink.primaryFace else Ink.ghostFace)
            .glassTint(shape, AppColour.ON_FILL)
            .then(if (pressed) Modifier.background(Ink.pressedVeil) else Modifier)
            .border(1.dp, Ink.glassEdge, shape)
            .clickable(interactionSource = presses, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = inset),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.alpha(if (enabled) 1f else 0.35f), verticalAlignment = Alignment.CenterVertically) { label(style) }
    }
}

/**
 * A round glass key: the iPhone's `RPMGlassKey`, the chrome's frost in a circle. The back key and the
 * player's close key are drawn with it.
 */
@Composable
fun GlassKey(backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier, edge: Dp = 40.dp, content: @Composable () -> Unit) {
    val circle = androidx.compose.foundation.shape.CircleShape
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Box(
        modifier
            .defaultMinSize(edge, edge)
            .chromeGlass(backdrop, circle)
            .clip(circle)
            .clickable(interactionSource = presses, indication = null, role = Role.Button, onClick = onClick)
            .alpha(if (pressed) 0.8f else 1f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The back key: a chevron on a glass circle, as the iPhone's `RPMBackKey`. Every page that steps back
 * uses this one, so they stay the same.
 */
@Composable
fun BackKey(backdrop: Backdrop, onBack: () -> Unit, modifier: Modifier = Modifier) {
    GlassKey(backdrop, onBack, modifier) {
        androidx.compose.foundation.text.BasicText(Glyph.back, style = icon(15f).copy(color = Ink.white(0xB8)))
    }
}

// ── fields ──

/**
 * A text field on the face of a ghost key, as the iPhone's `rpmField()`. It is always 44 dp high, whether
 * empty or typed in. A field never grows taller. The text uses the system font at size 15, in white.
 */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val shape = RoundedCornerShape(10.dp)
    val style = system(15f).copy(color = Color.White)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Color.White),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(Ink.ghostFace)
            .glassTint(shape, AppColour.ON_FILL)
            .border(1.dp, Ink.glassEdge, shape),
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) androidx.compose.foundation.text.BasicText(placeholder, style = style.copy(color = Ink.white(0x77)))
                field()
            }
        },
    )
}
