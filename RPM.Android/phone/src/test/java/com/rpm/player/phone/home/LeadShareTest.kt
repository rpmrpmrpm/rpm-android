package com.rpm.player.phone.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far home's ground has moved from the banner's picture to the front card's colour: from 40% of the banner off the
 * screen until its foot reaches the title, and the picture until the banner and the title are measured.
 */
class LeadShareTest {
    // A banner whose foot is 600 px down, under a status bar and title 200 px tall: the change runs from 160 to 400.
    private val foot = 600f
    private val top = 200f

    @Test
    fun atRestThePictureIsTheGround() {
        assertEquals(0f, leadShare(scrolled = 0f, bannerFoot = foot, top = top), 0f)
    }

    @Test
    fun theColourStartsComingInWhenTheBannerIsFortyPercentOff() {
        assertEquals(0f, leadShare(scrolled = 160f, bannerFoot = foot, top = top), 0f)
        assertEquals(0.5f, leadShare(scrolled = 280f, bannerFoot = foot, top = top), 0.0001f)
    }

    @Test
    fun theColourLeadsOnceTheBannerFootReachesTheTitle() {
        assertEquals(1f, leadShare(scrolled = 400f, bannerFoot = foot, top = top), 0f)
        assertEquals(1f, leadShare(scrolled = 2_000f, bannerFoot = foot, top = top), 0f)
    }

    @Test
    fun nothingMovesUntilTheBannerAndTheTitleAreMeasured() {
        assertEquals(0f, leadShare(scrolled = 500f, bannerFoot = 0f, top = top), 0f)
        assertEquals(0f, leadShare(scrolled = 500f, bannerFoot = foot, top = 0f), 0f)
    }

    @Test
    fun aBannerNoTallerThanTheTitleStillEnds() {
        assertEquals(1f, leadShare(scrolled = 1f, bannerFoot = 150f, top = top), 0f)
    }
}
