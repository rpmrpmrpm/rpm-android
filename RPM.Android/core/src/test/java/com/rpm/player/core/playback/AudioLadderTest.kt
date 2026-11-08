package com.rpm.player.core.playback

import com.rpm.player.core.net.Reach
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rate offer, pinned to the iPhone's `AudioLadderTests` cases: which setting a network uses, what a free account
 * settles on, and that Data Saver never lets the original through. */
class AudioLadderTest {
    private val wifi = Reach(isOnline = true, costsData = false, isConstrained = false)
    private val cellular = Reach(isOnline = true, costsData = true, isConstrained = false)
    private val saver = Reach(isOnline = true, costsData = true, isConstrained = true)
    private val whole = AudioLadder.RateOffer.WHOLE
    private val free = AudioLadder.RateOffer.FREE

    @Test
    fun theNetworkPicksTheSetting() {
        assertEquals(0, whole.settles(onWifi = 0, onCellular = 192, reach = wifi))
        assertEquals(192, whole.settles(onWifi = 0, onCellular = 192, reach = cellular))
    }

    @Test
    fun aPlayerWithNoSettingAsksForTheOriginalForASupporterOrTester() {
        assertEquals(AudioLadder.ORIGINAL, AudioLadder.RateOffer.forPlan(isSupporter = true))
        assertEquals(AudioLadder.ORIGINAL, AudioLadder.RateOffer.forPlan(isSupporter = null))
        assertEquals(128, AudioLadder.RateOffer.forPlan(isSupporter = false))
    }

    @Test
    fun aFreeAccountSettlesOnItsOneRung() {
        assertEquals(128, free.settles(onWifi = 0, onCellular = 320, reach = wifi))
        assertEquals(128, free.settles(onWifi = 0, onCellular = 320, reach = cellular))
    }

    @Test
    fun dataSaverCapsTheOriginalToo() {
        assertEquals(128, whole.settles(onWifi = 0, onCellular = 0, reach = saver))
        assertEquals(96, whole.settles(onWifi = 0, onCellular = 96, reach = saver))
        assertEquals(128, whole.settles(onWifi = 0, onCellular = 320, reach = saver))
    }

    @Test
    fun aRateOffTheOfferIsTheFirstRung() {
        assertEquals(0, whole.settles(on = 160))
        assertEquals(128, free.settles(on = 320))
    }

    @Test
    fun anUnreadPlanOffersTheWholeLadder() {
        assertEquals(whole, AudioLadder.RateOffer.UNREAD)
        assertEquals(false, AudioLadder.RateOffer.UNREAD.capped)
    }
}
