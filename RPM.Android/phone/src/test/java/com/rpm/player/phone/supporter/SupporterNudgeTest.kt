package com.rpm.player.phone.supporter

import com.rpm.player.phone.supporter.SupporterNudge.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iPhone's SupporterNudgeTests that the phone's [SupporterNudge] answers too: when the supporter pop-up shows. */
class SupporterNudgeTest {
    @Test fun everyFiftiethLaunchOfAnAccountThatDoesNotPay() {
        assertFalse(SupporterNudge.due(49, Plan.Free))
        assertTrue(SupporterNudge.due(50, Plan.Free))
        assertFalse(SupporterNudge.due(51, Plan.Free))
        assertTrue(SupporterNudge.due(100, Plan.Free))
        assertTrue(SupporterNudge.due(150, Plan.Free))
    }

    @Test fun everyHundredthLaunchOfAClosedBetaTester() {
        assertFalse(SupporterNudge.due(50, Plan.Tester))
        assertFalse(SupporterNudge.due(99, Plan.Tester))
        assertTrue(SupporterNudge.due(100, Plan.Tester))
        assertFalse(SupporterNudge.due(150, Plan.Tester))
        assertTrue(SupporterNudge.due(200, Plan.Tester))
    }

    @Test fun neverForAPayingSupporter() {
        for (launch in listOf(49, 50, 51, 100)) assertFalse(SupporterNudge.due(launch, Plan.Pays))
    }

    @Test fun neverWhileThePlanIsNotKnown() {
        for (launch in listOf(49, 50, 51, 100)) assertFalse(SupporterNudge.due(launch, null))
    }

    @Test fun aTesterIsATesterWhateverTheSupporterFlagSays() {
        assertEquals(Plan.Tester, Plan.of(supporter = true, tester = true))
        assertEquals(Plan.Tester, Plan.of(supporter = false, tester = true))
        assertEquals(Plan.Pays, Plan.of(supporter = true, tester = false))
        assertEquals(Plan.Free, Plan.of(supporter = false, tester = false))
    }

    @Test fun noLaunchCountedIsNotALaunchThatShows() {
        assertFalse(SupporterNudge.onLaunch(0))
        assertFalse(SupporterNudge.due(0, Plan.Free))
        assertFalse(SupporterNudge.due(0, Plan.Tester))
    }
}
