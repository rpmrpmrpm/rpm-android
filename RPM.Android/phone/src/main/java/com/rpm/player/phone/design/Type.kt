package com.rpm.player.phone.design

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shadow
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.rpm.player.phone.R
import com.rpm.player.core.R as CoreR

/**
 * The app's fonts, the same as the iPhone app's `RPMFont` (RPMUI/RPMTheme.swift). Cabin Bold and the two
 * Font Awesome fonts are the files that `:core` already ships for the TV, and they are the same bytes as on
 * iPhone. Cabin Medium, the brand icons and Thiamine RPM are files that only the phone app has.
 */
object RpmFont {
    /** The body and display face, `Cabin`. */
    val cabin = FontFamily(Font(R.font.cabin_medium, FontWeight.Medium), Font(CoreR.font.cabin_bold, FontWeight.Bold))

    /**
     * The tab and page titles, in `Thiamine RPM`. Its capitals are 0.247 of the em, against 0.70 for Cabin.
     * So it is never used at body-text size.
     */
    val thiamine = FontFamily(Font(R.font.thiamine_rpm))

    /** Font Awesome solid: the app's glyphs. */
    val solid = FontFamily(Font(CoreR.font.fa_solid))

    /** Font Awesome regular: outline glyphs, such as an empty heart. */
    val regular = FontFamily(Font(CoreR.font.fa_regular))

    /** The brand marks: Apple, Google. */
    val brands = FontFamily(Font(R.font.fa_brands))
}

/**
 * A size in points, as the iPhone app states every size, converted to text units.
 *
 * **Fixed, not scaled with the system's font size.** The iPhone app does not follow Dynamic Type. Its keys
 * and fields are a fixed 44 points high, and its text is sized to fit them. If the text scaled, a large font
 * size would overflow them. All sizes go through this one function, so following the system setting would
 * be a single change.
 */
@Composable
fun pt(size: Float): TextUnit = with(LocalDensity.current) { size.dp.toSp() }

/**
 * A soft outline drawn round every piece of text on a page that stands on a photograph: a shadow with no offset,
 * feathered, so text stays readable where the picture under it is bright. The artist page provides it for everything
 * on it; elsewhere it is none. Every style below takes it, so a row or a tile drawn on that page has it too.
 */
val LocalTextFeather = staticCompositionLocalOf<Shadow?> { null }

/** `.system(size:weight:)`: the platform's own font, Roboto here, where the iPhone uses SF Pro. */
@Composable
fun system(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontSize = pt(size), fontWeight = weight, shadow = LocalTextFeather.current)

/** `RPMFont.display`: Cabin Medium. */
@Composable
fun display(size: Float): TextStyle =
    TextStyle(fontFamily = RpmFont.cabin, fontWeight = FontWeight.Medium, fontSize = pt(size), shadow = LocalTextFeather.current)

/** `RPMFont.displayBold`: Cabin Bold. */
@Composable
fun displayBold(size: Float): TextStyle =
    TextStyle(fontFamily = RpmFont.cabin, fontWeight = FontWeight.Bold, fontSize = pt(size), shadow = LocalTextFeather.current)

/** `RPMFont.header`: Thiamine RPM, with no added letter spacing, as on iPhone. */
@Composable
fun header(size: Float = 96f): TextStyle = TextStyle(fontFamily = RpmFont.thiamine, fontSize = pt(size))

/** `RPMFont.icon`: a Font Awesome solid glyph. */
@Composable
fun icon(size: Float): TextStyle = TextStyle(fontFamily = RpmFont.solid, fontSize = pt(size), shadow = LocalTextFeather.current)

/** `RPMFont.iconOutline`: a Font Awesome regular glyph. */
@Composable
fun iconOutline(size: Float): TextStyle = TextStyle(fontFamily = RpmFont.regular, fontSize = pt(size), shadow = LocalTextFeather.current)

/** `RPMFont.brand`: a Font Awesome brand mark. */
@Composable
fun brand(size: Float): TextStyle = TextStyle(fontFamily = RpmFont.brands, fontSize = pt(size))

/** `rpmKicker()` on iPhone: the small line above a group. It is quiet and semi-bold. */
@Composable
fun kicker(): TextStyle = system(11f, FontWeight.SemiBold).copy(color = Ink.white(0x88))
