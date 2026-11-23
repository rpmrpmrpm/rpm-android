package com.rpm.player.phone.ui

import androidx.compose.ui.unit.dp
import com.rpm.player.phone.settings.AppLanguages
import com.rpm.player.phone.settings.valueAt
import org.junit.Assert.assertEquals
import org.junit.Test

/** The arithmetic of the look settings: the background's veil, the covers' corners, the app's language and the slider. */
class LookTest {
    @Test fun anUntouchedVeilLeavesThePictureAlone() {
        assertEquals(0f, BackdropVeil.radius(0f))
        assertEquals(0x33 / 255f, BackdropVeil.shade(0x33 / 255f, 0f), 1e-6f)
    }

    @Test fun theVeilAtItsMostIsThirtyAndThreeQuartersOfWhatIsLeft() {
        assertEquals(30f, BackdropVeil.radius(1f))
        assertEquals(0.75f, BackdropVeil.shade(0f, 1f), 1e-6f)
        // Aero's own 45 per cent, then three quarters of the 55 left.
        assertEquals(0.45f + 0.55f * 0.75f, BackdropVeil.shade(0.45f, 1f), 1e-6f)
    }

    @Test fun theVeilIsKeptBetweenOffAndTheMost() {
        assertEquals(30f, BackdropVeil.radius(2f))
        assertEquals(0f, BackdropVeil.radius(-1f))
        assertEquals(0.75f, BackdropVeil.shade(0f, 5f), 1e-6f)
    }

    /** Ten dp on home's 132 dp covers, until the person chooses. */
    @Test fun theUsualCornerIsHomesTenOn132() {
        assertEquals(10f / 132f, CoverCorner.stored(null))
        assertEquals(10f / 132f, CoverCorner.share)
        assertEquals(10f, CoverCorner.radius(132.dp).value, 1e-4f)
    }

    @Test fun aStoredCornerIsKeptWithinTheRangeOffered() {
        assertEquals(0f, CoverCorner.stored(-0.5f))
        assertEquals(CoverCorner.ROUNDEST, CoverCorner.stored(1f))
        assertEquals(0.1f, CoverCorner.stored(0.1f))
        assertEquals(CoverCorner.USUAL, CoverCorner.stored(Float.NaN))
    }

    @Test fun theOfferedLanguagesAreTheDesktops() {
        assertEquals(listOf("en", "fr", "es", "de", "zh-Hans"), AppLanguages.OFFERED)
    }

    @Test fun aTagIsOneOfTheOfferedLanguagesOrThePhones() {
        assertEquals("fr", AppLanguages.offeredOf("fr"))
        assertEquals("de", AppLanguages.offeredOf("de-AT"))
        assertEquals("zh-Hans", AppLanguages.offeredOf("zh-Hans"))
        assertEquals("zh-Hans", AppLanguages.offeredOf("zh-CN"))
        assertEquals("", AppLanguages.offeredOf("zh-Hant"))
        assertEquals("", AppLanguages.offeredOf("nl"))
        assertEquals("", AppLanguages.offeredOf(""))
    }

    /** Each language is named in itself, with a capital first letter, as a language picker names them. */
    @Test fun eachLanguageIsNamedInItself() {
        assertEquals("English", AppLanguages.name("en"))
        assertEquals("Français", AppLanguages.name("fr"))
        assertEquals("Español", AppLanguages.name("es"))
        assertEquals("Deutsch", AppLanguages.name("de"))
    }

    /** The knob's centre travels between the ends of the track, half a knob in from each side. */
    @Test fun theSliderReadsTheKnobsCentre() {
        assertEquals(0f, valueAt(13f, 226f, 26f, 0f..1f), 1e-6f)
        assertEquals(1f, valueAt(213f, 226f, 26f, 0f..1f), 1e-6f)
        assertEquals(0.5f, valueAt(113f, 226f, 26f, 0f..1f), 1e-6f)
        assertEquals(0.11f, valueAt(113f, 226f, 26f, 0f..0.22f), 1e-6f)
        assertEquals(0f, valueAt(-40f, 226f, 26f, 0f..1f), 1e-6f)
    }
}
