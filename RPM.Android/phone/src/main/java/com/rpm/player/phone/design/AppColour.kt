package com.rpm.player.phone.design

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.rpm.player.core.R
import com.rpm.player.phone.ui.Ground
import com.rpm.player.phone.ui.LocalGround
import com.rpm.player.core.cloud.ProfileBadges
import com.rpm.player.core.room.CoverColor
import com.rpm.player.core.room.Rgb
import com.rpm.player.phone.ui.GlassInk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour the person gives the app's glass: the cards, the chrome, the keys, the switches and the faders. It is the
 * iPhone's `AppColour`, chosen on the Appearance settings and in the welcome's look step.
 *
 * **No colour is the default**: the app's white frost on dark glass. The person can pick the background's own colour
 * ([theme]), so the glass and the background look like one theme, or one of the colours offered. A colour is laid over
 * that frost as a light wash ([ON_GLASS] and the others), so white text on it stays readable whichever colour is chosen.
 *
 * The colours offered are the profile badge's colours ([ProfileBadges.colours]), shown as the same round bubbles as on
 * the profile page, so the app and the badge can match. The choice is stored under [KEY], the iPhone's key, with the
 * iPhone's values (its `GlassColour`): empty for no colour, [MATCHES_BACKGROUND] for the background's colour, or
 * `#RRGGBB` (the badge's own form). Anything else is no colour, so a value this version does not know shows the frost.
 *
 * **An album, a playlist or the player on screen colours the glass with its cover** while it is shown ([PageColour]).
 * That colour comes before the person's. What the glass is drawn in now is [GlassTint], which fades between the two.
 */
object AppColour {
    const val KEY = "look.colour"

    /** What is stored for the background's colour. No colour is stored as empty. */
    const val MATCHES_BACKGROUND = "background"

    /**
     * The colour each background gives the glass, as 0xRRGGBB, so the two look like one theme (the iPhone's
     * `AppColour.theme`). The badge's own blue, red, orange and yellow, and a grey for the grey background.
     */
    fun theme(ground: Ground): Long = when (ground) {
        Ground.Standard, Ground.Vinyl -> 0x2563EB
        Ground.Pegasus -> 0xD12A4E
        Ground.SheetMusic, Ground.Cocoa -> 0xE0701B
        Ground.Aero, Ground.Gold -> 0xB8901F
        Ground.Graphite -> 0x8E939B
    }

    /** The colours offered, as 0xRRGGBB. */
    val offered: List<Long> get() = ProfileBadges.colours

    /** How strongly each kind of face takes the colour. Light washes, because white text and marks stand on every one. */
    const val ON_GLASS = 0.35f
    const val ON_FILL = 0.22f
    const val ON_SECTION = 0.16f
    const val ON_PILL = 0.55f

    /** The choice as stored: empty for no colour, [MATCHES_BACKGROUND], or `#RRGGBB`. */
    var stored by mutableStateOf("")
        private set

    private var loaded = false

    /** The colour the choice gives on [ground], or null for no colour. */
    fun chosen(ground: Ground): Color? = glass(stored, ground)?.let { Color(0xFF000000 or it) }

    /** The colour [stored] gives the glass on [ground], as 0xRRGGBB, or null for no colour. */
    fun glass(stored: String, ground: Ground): Long? = if (stored == MATCHES_BACKGROUND) theme(ground) else value(stored)

    /** Reads the stored choice once. The settings file is the one [com.rpm.player.phone.PhoneSettings] keeps. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        stored = prefs(context).getString(KEY, null).orEmpty()
    }

    /** Keeps [wire] (empty for no colour, [MATCHES_BACKGROUND], or `#RRGGBB`) and shows it on every face at once. */
    fun choose(context: Context, wire: String) {
        loaded = true
        stored = wire
        prefs(context).edit().putString(KEY, wire).apply()
    }

    /** The stored colour as 0xRRGGBB, or null for none. Anything that cannot be read is none. */
    fun value(stored: String): Long? {
        val digits = stored.removePrefix("#")
        if (stored.length != 7 || digits.length != 6 || !digits.all { Character.digit(it, 16) >= 0 }) return null
        return digits.toLong(16)
    }

    /** The colour stored, or null for none. */
    fun colour(stored: String): Color? = value(stored)?.let { Color(0xFF000000 or it) }

    /** 0xRRGGBB as the badge's wire form, `#RRGGBB`. */
    fun wire(value: Long): String = "#%06X".format(value)

    private fun prefs(context: Context) = context.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    /** The file [com.rpm.player.phone.PhoneSettings] keeps the settings in. */
    private const val SETTINGS_FILE = "rpm.phone"
}

/**
 * The colours of the pages on screen that have one, in the order they were shown: an album, a playlist, the player
 * (the iPhone's `PageColour`). The last one shown wins, so a record opened from a playlist colours the glass with the
 * record, and going back gives the playlist's colour again.
 *
 * A page holds its colour while it is in the composition ([HoldsPageColour]). **A page lets go when its back key is
 * pressed**, and not only when it leaves: a page going back stays composed for its slide out, and its colour would stay
 * on the page under it for the whole slide.
 */
open class PageColours {
    private val held = mutableStateListOf<Pair<Any, Color>>()

    /** The colour of the page shown last that has one, or null. */
    val current: Color? get() = held.lastOrNull()?.second

    /** Holds [colour] for [page], last. Null lets it go. */
    fun hold(colour: Color?, page: Any) {
        held.removeAll { it.first == page }
        if (colour != null) held.add(page to colour)
    }

    fun release(page: Any) = hold(null, page)
}

/** The pages' colours for the whole app. See [PageColours]. */
object PageColour : PageColours()

/**
 * Holds [colour] for [page] while this is in the composition. See [PageColours].
 *
 * @param page the page's own identity ([rememberColourPage]). Its back key passes the same one to
 *   [PageColours.release] before it goes back.
 */
@Composable
fun HoldsPageColour(colour: Color?, page: Any) {
    // Held as the composition is applied, not a frame later, so a page pushed over another wins from its first frame.
    DisposableEffect(page, colour) {
        PageColour.hold(colour, page)
        onDispose {}
    }
    DisposableEffect(page) { onDispose { PageColour.release(page) } }
}

/** A page's identity among the pages that colour the glass. */
@Composable
fun rememberColourPage(): Any = remember { Any() }

/**
 * The colour the glass is drawn in now: a page's ([PageColour]), else the person's ([AppColour]), else none, with the
 * change faded over 0.2 seconds as on iPhone. None is a transparent colour, so a face draws its plain look.
 *
 * Faces read [now] while drawing, never while composing, so a fade redraws the faces and recomposes nothing.
 */
object GlassTint {
    private var fade: androidx.compose.animation.core.Animatable<Color, AnimationVector4D>? = null

    /** The tint now. Its alpha is how far in it is: 0 is none. */
    val now: Color get() = fade?.value ?: Color.Transparent

    /** Starts at [colour], once, so the app opens in the person's colour and does not fade into it. */
    internal fun start(colour: Color?) {
        if (fade == null) fade = androidx.compose.animation.Animatable(colour ?: Color.Transparent)
    }

    /** Fades to [target]. From none, the colour itself fades in, rather than turning from the last colour shown. */
    internal suspend fun fadeTo(target: Color?) {
        val fade = fade ?: return
        val from = fade.value
        if (target != null && from.alpha == 0f) fade.snapTo(target.copy(alpha = 0f))
        fade.animateTo(target ?: from.copy(alpha = 0f), tween(FADE_MS, easing = EASE_OUT))
    }

    private const val FADE_MS = 200

    /** SwiftUI's `easeOut`. */
    private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
}

/** Follows the colour the glass should be in ([GlassTint]). The root draws it once, for the whole app. */
@Composable
fun GlassColourFade() {
    AppColour.load(LocalContext.current)
    val chosen = AppColour.chosen(LocalGround.current)
    GlassTint.start(chosen)
    val target = PageColour.current ?: chosen
    LaunchedEffect(target) { GlassTint.fadeTo(target) }
}

/** The glass colour as a wash over a face in [shape], at [strength] of the colour ([AppColour.ON_GLASS] and the others). */
fun Modifier.glassTint(shape: Shape, strength: Float): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    onDrawBehind {
        val tint = GlassTint.now
        if (tint.alpha > 0f) drawOutline(outline, tint.copy(alpha = tint.alpha * strength))
    }
}

/**
 * A fill that the glass colour takes over: [rest] without a colour, and the colour at [strength] with one. The page
 * tabs' pill is drawn so: its white is replaced, not washed, or a colour would read as grey on it.
 */
fun Modifier.tintedFill(rest: Color, strength: Float): Modifier = drawBehind {
    val tint = GlassTint.now
    if (tint.alpha < 1f) drawRect(rest.copy(alpha = rest.alpha * (1f - tint.alpha)))
    if (tint.alpha > 0f) drawRect(tint.copy(alpha = tint.alpha * strength))
}

/**
 * The colour of a lit switch or fader: the glass colour, whole, or [rest] without one (the iPhone's `rpmControlTint`).
 * Read it while drawing.
 */
fun controlColour(rest: Color): Color {
    val tint = GlassTint.now
    return if (tint.alpha <= 0f) rest else lerp(rest, tint.copy(alpha = 1f), tint.alpha)
}

// ── a cover's colour ──

/** The colour a cover gives the glass: its main colour, else its most vivid one, lifted for dark glass ([GlassInk]). */
fun glassOf(dominant: Rgb?, highlight: Rgb?): Color? = (dominant ?: highlight)?.let(GlassInk::of)

/**
 * The colour [picture] gives the glass ([glassOf]), read off the main thread from the 48 pixel square the colours are
 * taken from, once per picture. While a new picture is read the last one's colour stays, so the glass goes from colour
 * to colour (a station's songs, a record's cover arriving) and not through none. Null for no picture or no colour.
 */
@Composable
fun rememberGlassColour(picture: Any?, images: ImageLoader): Color? {
    val context = LocalContext.current
    var read by remember { mutableStateOf(picture?.let { GLASS.get(it) }) }
    LaunchedEffect(picture) {
        if (picture == null) {
            read = null
            return@LaunchedEffect
        }
        read = GLASS.get(picture) ?: withContext(Dispatchers.Default) {
            val request = ImageRequest.Builder(context).data(picture).size(48).allowHardware(false).build()
            val bitmap = (images.execute(request) as? SuccessResult)?.image?.toBitmap()
            val reading = bitmap?.let { CoverColor.read(it) }
            Tinted(reading?.let { glassOf(it.dominant, it.glow) })
        }.also { GLASS.put(picture, it) }
    }
    return read?.colour
}

/** A glass colour as read, which may be none: a cache cannot hold a null. */
private class Tinted(val colour: Color?)

/** The glass colours read so far, by picture, for the life of the app. */
private val GLASS = LruCache<Any, Tinted>(64)

// ── the bubbles ──

/**
 * The round colour bubbles, as the profile page draws them for the badge: no colour first, then the background's own
 * colour, then each colour offered. The one chosen has a thick white ring (the iPhone's `AppColourBubbles`).
 */
@Composable
fun AppColourBubbles() {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    AppColour.load(context)
    val stored = AppColour.stored
    val matchesBackground = stored == AppColour.MATCHES_BACKGROUND
    val chosen = AppColour.value(stored)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Bubble(null, picked = !matchesBackground && chosen == null) {
            haptics.pick()
            AppColour.choose(context, "")
        }
        ThemeBubble(Color(0xFF000000 or AppColour.theme(LocalGround.current)), picked = matchesBackground) {
            haptics.pick()
            AppColour.choose(context, AppColour.MATCHES_BACKGROUND)
        }
        for (value in AppColour.offered) {
            Bubble(value, picked = chosen == value) {
                haptics.pick()
                AppColour.choose(context, AppColour.wire(value))
            }
        }
    }
}

/** The background's own colour, with the palette mark on it: the glass follows the background. */
@Composable
private fun ThemeBubble(colour: Color, picked: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.Settings_ColourTheme)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(colour)
            .border(if (picked) 3.dp else 1.dp, Ink.white(if (picked) 0xF2 else 0x44), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = picked
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(Glyph.palette, style = icon(13f).copy(color = Ink.white(0xF2)))
    }
}

/** One bubble. Null is no colour: the app's own frost, with a line across it. */
@Composable
private fun Bubble(value: Long?, picked: Boolean, onClick: () -> Unit) {
    val label = if (value == null) stringResource(R.string.Settings_ColourNone) else AppColour.wire(value)
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (value == null) Ink.white(0x24) else Color(0xFF000000 or value))
            .border(if (picked) 3.dp else 1.dp, Ink.white(if (picked) 0xF2 else 0x44), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                selected = picked
            },
        contentAlignment = Alignment.Center,
    ) {
        if (value == null) Box(Modifier.rotate(45f).size(2.dp, 22.dp).clip(RoundedCornerShape(1.dp)).background(Ink.white(0x99)))
    }
}

/** The colour's kicker and its bubbles, for the welcome's look step, under the backdrops. */
@Composable
fun AppColourChoice() {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText(stringResource(R.string.Settings_Colour), style = kicker())
        AppColourBubbles()
    }
}
