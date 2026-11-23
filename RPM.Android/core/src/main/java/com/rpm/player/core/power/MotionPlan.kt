package com.rpm.player.core.power

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * What moves on screen and how much it draws, for one power state ([PowerSaving]): the iPhone's `MotionPlan`, field for
 * field. Screens read the plan ([LocalMotionPlan]) and not the power state, so the rules live in one place.
 *
 * The rules: what moves does so at the display's own rate, never at a lower one, because a low rate looks broken and
 * saves little. While the phone saves power, the screens draw less (fewer pixels, fewer steps, fewer things that move),
 * not fewer frames. What answers the music (the player's spectrum) stays live in both states. The glass is the same in
 * both states, so it is not part of the plan.
 *
 * Make one with `MotionPlan(saving = ...)`.
 */
@Immutable
data class MotionPlan(
    /** How the room behind the player is drawn. */
    val stageRoom: StageRoom,
    /** Whether the rooms on cards (home's banner, a list's or an artist's page) move. A room that does not move keeps
     * its picture. */
    val cardRoomsMove: Boolean,
    /** How many steps each ray takes in the marched visualisers (the lattice, the kaleidoscope, the tunnel). */
    val marchSteps: Int,
    /** The share of their usual pixels, across and down, that the visualisers draw at. */
    val lookScale: Float,
    /** Whether the loops that only decorate run, such as a card that drifts or a shimmer. */
    val decorativeLoops: Boolean,
) {
    /** How the room behind the player is drawn: moving at the display's rate ([Full], [Reduced]) or holding a picture
     * ([Still]). */
    @Immutable
    sealed interface StageRoom {
        /** At its usual pixels. */
        data object Full : StageRoom

        /**
         * At no more than [scale] of its pixels, across and down, and stretched up. This app always draws its rooms at
         * an eighth of their width and height or less ([com.rpm.player.core.room.RoomPainter]), so here this draws as
         * [Full] does. It matters on the iPhone, which draws its room at the screen's pixels.
         */
        data class Reduced(val scale: Float) : StageRoom

        /** The room holds its last picture, and the spectrum carries the music on its own. */
        data object Still : StageRoom
    }

    companion object {
        /** The plan while the phone does not save power: everything moves, at full detail. */
        val FULL = MotionPlan(
            stageRoom = StageRoom.Full,
            cardRoomsMove = true,
            marchSteps = 64,
            lookScale = 1f,
            decorativeLoops = true,
        )

        /**
         * The plan while the phone saves power. The room behind the player keeps moving at the display's rate, as it is
         * the record being played, but at a quarter of its pixels. The rooms on cards hold their pictures, the
         * visualisers march half the steps at half the pixels, and the loops that only decorate stop.
         */
        val SAVING = MotionPlan(
            stageRoom = StageRoom.Reduced(scale = 0.25f),
            cardRoomsMove = false,
            marchSteps = 32,
            lookScale = 0.5f,
            decorativeLoops = false,
        )

        /** The plan for a power state: [SAVING] while the phone saves power, else [FULL]. */
        operator fun invoke(saving: Boolean): MotionPlan = if (saving) SAVING else FULL
    }
}

/**
 * The plan the screens follow. The app's activity provides it from [PowerSaving], so a change of battery saver reaches
 * every screen at once. Without a provider it is [MotionPlan.FULL].
 */
val LocalMotionPlan = compositionLocalOf { MotionPlan.FULL }
