package com.rpm.player.core.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two plans, and that the power state picks between them. */
class MotionPlanTest {

    @Test fun withoutSavingEverythingMovesAtFullDetail() {
        val plan = MotionPlan(saving = false)
        assertEquals(MotionPlan.StageRoom.Full, plan.stageRoom)
        assertTrue(plan.cardRoomsMove)
        assertEquals(64, plan.marchSteps)
        assertEquals(1f, plan.lookScale)
        assertTrue(plan.decorativeLoops)
    }

    @Test fun whileSavingTheStageRoomMovesSmallerAndTheRestDrawsLess() {
        val plan = MotionPlan(saving = true)
        assertEquals(MotionPlan.StageRoom.Reduced(0.25f), plan.stageRoom)
        assertFalse(plan.cardRoomsMove)
        assertEquals(32, plan.marchSteps)
        assertEquals(0.5f, plan.lookScale)
        assertFalse(plan.decorativeLoops)
    }

    @Test fun whileSavingTheStageRoomIsNotStill() {
        // The room behind the player is the record being played: it keeps moving, at the display's rate.
        assertTrue(MotionPlan(saving = true).stageRoom != MotionPlan.StageRoom.Still)
    }

    @Test fun eachStateGivesTheSamePlanEveryTime() {
        assertSame(MotionPlan.FULL, MotionPlan(saving = false))
        assertSame(MotionPlan.SAVING, MotionPlan(saving = true))
    }
}
