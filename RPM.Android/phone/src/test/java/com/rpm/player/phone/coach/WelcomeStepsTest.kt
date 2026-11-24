package com.rpm.player.phone.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The welcome's steps ([WelcomeSteps]): the support step only while the account is asked, the song step only with a
 * song, and the page shown when the one asked for has dropped out. */
class WelcomeStepsTest {
    @Test fun anAccountThatIsAskedWithASongHasFiveStepsTheSupportStepFourth() {
        val steps = WelcomeSteps(asks = true, hasSong = true)
        assertEquals(listOf(WelcomePage.LANGUAGE, WelcomePage.LOOK, WelcomePage.LAYOUT, WelcomePage.SUPPORT, WelcomePage.SPIN), steps.steps)
        assertEquals(4, steps.number(WelcomePage.SUPPORT))
        assertEquals(5, steps.number(WelcomePage.SPIN))
    }

    @Test fun aSupporterIsNotAskedAndTheLayoutStepLeadsToTheSong() {
        val steps = WelcomeSteps(asks = false, hasSong = true)
        assertEquals(listOf(WelcomePage.LANGUAGE, WelcomePage.LOOK, WelcomePage.LAYOUT, WelcomePage.SPIN), steps.steps)
        assertEquals(WelcomePage.SPIN, steps.after(WelcomePage.LAYOUT))
    }

    @Test fun withNoSongTheSupportStepIsTheLast() {
        val steps = WelcomeSteps(asks = true, hasSong = false)
        assertEquals(WelcomePage.SUPPORT, steps.after(WelcomePage.LAYOUT))
        assertNull(steps.after(WelcomePage.SUPPORT))
    }

    @Test fun afterAPurchaseOnTheSupportStepTheSongShows() {
        assertEquals(WelcomePage.SPIN, WelcomeSteps(asks = false, hasSong = true).shown(WelcomePage.SUPPORT))
    }

    @Test fun afterAPurchaseOnTheSupportStepWithNoSongNothingIsLeft() {
        assertNull(WelcomeSteps(asks = false, hasSong = false).shown(WelcomePage.SUPPORT))
    }

    @Test fun backFromTheSongGoesToTheSupportStepWhileItIsThere() {
        assertEquals(WelcomePage.SUPPORT, WelcomeSteps(asks = true, hasSong = true).before(WelcomePage.SPIN))
        assertEquals(WelcomePage.LAYOUT, WelcomeSteps(asks = false, hasSong = true).before(WelcomePage.SPIN))
        assertEquals(WelcomePage.HELLO, WelcomeSteps(asks = true, hasSong = true).before(WelcomePage.LANGUAGE))
    }
}
