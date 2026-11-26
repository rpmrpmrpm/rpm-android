package com.rpm.player.phone.supporter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's SupporterNudgeTests that the phone's [SupporterNudge] answers too: when the supporter pop-up shows. */
class SupporterNudgeTest {
    @Test fun everyFiftiethLaunchOfAnAccountThatDoesNotPay() {
        assertFalse(SupporterNudge.due(49, supporter = false))
        assertTrue(SupporterNudge.due(50, supporter = false))
        assertFalse(SupporterNudge.due(51, supporter = false))
        assertTrue(SupporterNudge.due(100, supporter = false))
        assertTrue(SupporterNudge.due(150, supporter = false))
    }

    @Test fun neverForASupporter() {
        for (launch in listOf(49, 50, 51, 100)) assertFalse(SupporterNudge.due(launch, supporter = true))
    }

    @Test fun neverWhileThePlanIsNotKnown() {
        for (launch in listOf(49, 50, 51, 100)) assertFalse(SupporterNudge.due(launch, supporter = null))
    }

    @Test fun noLaunchCountedIsNotALaunchThatShows() {
        assertFalse(SupporterNudge.onLaunch(0))
        assertFalse(SupporterNudge.due(0, supporter = false))
    }
}
