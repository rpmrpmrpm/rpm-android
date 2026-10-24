package com.rpm.player.core.room

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room's animations, picked in the TV's settings. [Original] is the room every app draws
 * (RoomSource.Glsl); the others are the TV's own, drawn by the same renderer in the same eighth-size pass
 * with a program of their own ([RoomShaders]), so they cost what the original costs.
 */
enum class RoomStyle {
    /** The three families of lobes over the plane warped by the music ([RoomOrbits]). */
    Original,

    /** Two big balls on the bass and two small ones on the air, turning about the middle with fading
     * echoes behind them, over one big blurred ball in the third colour. */
    Yang,

    /** Blobs rising and sinking like a lava lamp's, melting into a pool at the floor and one at the top,
     * over one huge blurred ball in the third colour that swells with the middle. */
    Lava,

    /** Alchemy: the RPaMp skin's ground (background-rpamp.jpg, drawn by make_rpamp_ground.py), set moving:
     * broad soft ribbons of light flowing across a dark ground, in the sleeve's colours. */
    Alchemy;

    companion object {
        /** The style saved under [name]; Alchemy was saved as "RPaMp" before it was renamed. */
        fun named(name: String): RoomStyle? =
            if (name == "RPaMp") Alchemy else entries.firstOrNull { it.name == name }
    }
}

/**
 * Where the figures of [RoomStyle.Yang], [RoomStyle.Lava] and [RoomStyle.Alchemy] are this frame, and how
 * large and bright: worked out once per frame on the CPU, as [RoomOrbits] does for the original, so the
 * shaders spend nothing per pixel on numbers that are the same for every pixel. Written as vec4s, ready
 * for `glUniform4fv`. Allocation-free per frame.
 *
 * Each style's main motion runs on a clock of its own ([spin], [flow], [drift]) that [advance] moves on
 * faster while its level is high, so the music speeds the figures up without making them jump: a level
 * that scaled the time itself would throw every figure forward the moment the bass came in. A still room
 * is advanced by nothing and keeps its pose. Slow drifts that the music does not touch read the room's
 * own clock instead.
 */
class RoomFigures {
    /** Yang's two big balls, each followed by [BIG_ECHOES] echoes: (x, y, radius, weight). */
    val big = FloatArray(YANG_BIG * 4)
    /** Yang's two small balls, each followed by [SMALL_ECHOES] echoes, as [big]. */
    val small = FloatArray(YANG_SMALL * 4)
    /** Yang's blurred middle ball: (x, y, radius, unused). */
    val centre = FloatArray(4)

    /** Lava's blobs: (x, y, radius, how far from the tint to the glow its colour is). */
    val blobs = FloatArray(LAVA_BLOBS * 4)
    /** Lava's two pools: (floor's line, floor's thickness, top's line, top's thickness). */
    val pools = FloatArray(4)
    /** Lava's background ball: (x, y, radius, the phase of the pools' ripple). */
    val back = FloatArray(4)

    /** RPaMp's ribbons, each a curve across the screen as make_rpamp_ground.py draws one: (where it
     * crosses the left edge, tilt, how far its sine moves it, 2 pi x its sine's cycles across). */
    val ribbonA = FloatArray(RIBBONS * 4)
    /** RPaMp's ribbons: (phase of the sine, phase of its second sine, half-thickness, swell). */
    val ribbonB = FloatArray(RIBBONS * 4)
    /** RPaMp's ribbons: (phase of the swell, weight, the filament's weight, colour: 0 tint, 1 glow, 2 shade). */
    val ribbonC = FloatArray(RIBBONS * 4)

    /** Yang's turn, in radians. */
    var spin = 0.0
        private set
    /** Lava's clock, in seconds of lava. */
    var flow = 0.0
        private set
    /** RPaMp's clock, in seconds of drifting. */
    var drift = 0.0
        private set

    /** Moves the three clocks on by [dt] seconds at the speed the levels give them; 0 for a still room. */
    fun advance(dt: Float, bass: Float, mid: Float) {
        // With music, Yang turns at [YANG_MUSIC_PACE] of the speed the levels would give it: at full speed the balls
        // raced. The pace eases in with the music's level, so silence keeps the plain drift and nothing jumps.
        val music = ((bass + mid) * 5f).coerceIn(0f, 1f)
        spin += dt * (YANG_SPIN + bass * YANG_SPIN_BASS) * (1.0 - (1.0 - YANG_MUSIC_PACE) * music)
        flow += dt * (1.0 + bass * 0.6)
        drift += dt * (1.0 + mid * 0.8)
    }

    /**
     * Yang at [t] of the room's clock. The big pair stands opposite each other on an orbit 0.24 of the
     * height out, the small pair on one 0.39 out, turning [SMALL_RATE] times as fast a quarter turn
     * apart, so the small ones keep passing the big ones. Both orbits are stretched [WIDE] across, to
     * use the width. The big balls swell with the bass as the original's figures do (1 + bass x 0.62),
     * the small ones with the air (1 + air x 0.85), and the middle ball with the middle.
     *
     * The trail is not a framebuffer kept from frame to frame, which would be a second full pass and a
     * read of last frame's texture on the Amiko: it is more balls per ball, each further back along the
     * orbit, smaller and lighter, summed into the same field, so they melt into one tail. The echoes are
     * spaced by length along the orbit ([trail]), not by time, so a ball that turns faster does not
     * spread its tail into beads. A small ball's [SMALL_ECHOES] echoes are 0.045 of the height apart
     * against its radius of 0.07, close enough to fill in one smooth streak (they were four at 0.09, and
     * read as dots); a big ball's [BIG_ECHOES] are 0.09 apart against 0.15. The louder the ball's level,
     * the faster it turns and the longer its tail: the big ones' spacing x (1 + bass x 0.6), the small
     * ones' x (1 + air x 0.4), held below the radius of the last, smallest echo even at full air.
     */
    fun yang(t: Double, bass: Float, mid: Float, air: Float) {
        val bigOrbit = 0.24 + 0.03 * sin(t * 0.23)
        val smallOrbit = 0.39 + 0.04 * sin(t * 0.31 + 1.0)
        val bigR = 0.15 * (1.0 + bass * 0.62)
        val smallR = 0.07 * (1.0 + air * 0.85)
        for (ball in 0 until 2) {
            trail(big, ball * (BIG_ECHOES + 1), spin + ball * PI, bigOrbit, bigR, 0.09 * (1.0 + bass * 0.6), BIG_ECHOES, 0.12, 0.6)
            trail(small, ball * (SMALL_ECHOES + 1), spin * SMALL_RATE + PI / 2 + ball * PI, smallOrbit, smallR, 0.045 * (1.0 + air * 0.4), SMALL_ECHOES, 0.06, 0.8)
        }
        put(centre, 0, 0.0, 0.0, 0.30 * (1.0 + mid * 0.35), 0.0)
    }

    /** One ball at [angle] on an orbit [distance] out, written from [first], and its [echoes] behind it,
     * each [spacing] further back along the stretched orbit, [shrink] of the radius smaller and [fade]
     * times as heavy as the one before. The step in angle is the spacing over how fast the orbit's length
     * grows with the angle where the echo stands. */
    private fun trail(into: FloatArray, first: Int, angle: Double, distance: Double, radius: Double, spacing: Double, echoes: Int, shrink: Double, fade: Double) {
        var a = angle
        for (k in 0..echoes) {
            put(into, first + k, cos(a) * distance * WIDE, sin(a) * distance, radius * (1.0 - shrink * k), fade.pow(k))
            val s = sin(a) * WIDE
            val c = cos(a)
            a -= spacing / (distance * sqrt(s * s + c * c))
        }
    }

    /**
     * Lava. Seven blobs spread across the width, each rising and sinking on a sine of its own period (24
     * to 66 seconds), bent so it lingers at the floor and the top as a lamp's wax does, and drifting a
     * little sideways. Blobs of different periods pass each other, melt into one and part again; at the
     * ends they melt into the pools. They swell with the bass as the original's figures do. The
     * background ball wanders slowly and swells with the middle (1 + mid x 0.45).
     */
    fun lava(bass: Float, mid: Float) {
        val f = flow
        for (i in 0 until LAVA_BLOBS) {
            val period = 24.0 + 7.0 * ((i * 3) % LAVA_BLOBS)
            val s = sin(f * 2 * PI / period + i * 2.39996)
            val y = sign(s) * abs(s).pow(0.85) * 0.44
            val x = -0.70 + i * (1.40 / (LAVA_BLOBS - 1)) + 0.12 * sin(f * 0.071 + i * 1.7)
            val r = (0.09 + 0.03 * sin(f * 0.13 + i * 2.3)) * (1.0 + bass * 0.62)
            put(blobs, i, x, y, r, (i * 0.41 + 0.15) % 1.0)
        }
        put(pools, 0, -0.565, 0.11 * (1.0 + bass * 0.3), 0.575, 0.07 * (1.0 + bass * 0.2))
        put(back, 0, 0.20 * sin(f * 0.043), -0.04 + 0.07 * sin(f * 0.031 + 1.0), 0.40 * (1.0 + mid * 0.45), f * 0.21)
    }

    /**
     * RPaMp: the five ribbons of make_rpamp_ground.py, with its numbers (the bands that drew
     * background-rpamp.jpg), so the still room is that picture. Here they move: each ribbon's phase swings
     * slowly back and forth along it (1.1 radians, over 70 to 40 seconds of [drift]), so its curve flows
     * and undulates; where it crosses the left edge rises and falls by 0.025 of the height, its sine
     * deepens and flattens by a quarter, and its swell slides along it. Swinging, not running on, so the
     * picture stays the skin's composition and two ribbons never come to lie on each other for long.
     * At [drift] 0 every swing is at its middle: a fresh room is the picture itself.
     *
     * The music, gently. The bright main ribbon (the one that enters from the left at about 40% of the
     * height and rises to the right, the script's second) takes the bass in its weight (1 + bass x 0.25)
     * and thickness (1 + bass x 0.15), and its filament flares with the air, which has the quickest
     * attack of the three, so it catches the transients. The ribbon that crosses it hears half the bass;
     * the two below it the middle; the faint one along the top the air. The middle also hurries [drift].
     * The two bright ribbons wear the tint, the others the glow, the shade and the glow.
     */
    fun ribbons(bass: Float, mid: Float, air: Float) {
        val d = drift
        for (i in 0 until RIBBONS) {
            val level = when (i) {
                0 -> bass * 0.5f
                1 -> bass
                2, 3 -> mid
                else -> air
            }.toDouble()
            val k = i * 6
            val y0 = BANDS[k]
            val tilt = BANDS[k + 1]
            val amp = BANDS[k + 2]
            val freq = BANDS[k + 3]
            val phase0 = BANDS[k + 4]
            val width = BANDS[k + 5]
            val phase = phase0 - 1.1 * sin(d * (0.09 + 0.02 * i))
            put(
                ribbonA, i,
                y0 + 0.025 * sin(d * (0.031 + 0.006 * i)),
                tilt,
                amp * (1.0 + 0.25 * sin(d * (0.043 + 0.007 * i))),
                2 * PI * freq,
            )
            put(ribbonB, i, phase, phase * 1.7, width * (1.0 + level * 0.15), SWELL[i])
            put(
                ribbonC, i,
                phase0 * 0.6 + 0.5 * sin(d * (0.037 + 0.009 * i)),
                WEIGHT[i] * (1.0 + level * 0.25),
                0.22 + if (i == 1) air * 0.5 else 0.0,
                COLOUR[i],
            )
        }
    }

    private fun put(into: FloatArray, i: Int, a: Double, b: Double, c: Double, d: Double) {
        into[i * 4] = a.toFloat()
        into[i * 4 + 1] = b.toFloat()
        into[i * 4 + 2] = c.toFloat()
        into[i * 4 + 3] = d.toFloat()
    }

    companion object {
        /** Echoes behind each of Yang's big balls, and behind each small one. */
        const val BIG_ECHOES = 4
        const val SMALL_ECHOES = 8
        /** Yang's big balls and their echoes: two of 1 + [BIG_ECHOES]. */
        const val YANG_BIG = 2 * (BIG_ECHOES + 1)
        /** Yang's small balls and their echoes: two of 1 + [SMALL_ECHOES]. */
        const val YANG_SMALL = 2 * (SMALL_ECHOES + 1)
        const val LAVA_BLOBS = 7
        const val RIBBONS = 5

        /** Yang's turn with no music, radians a second: a turn in about 20 seconds. */
        const val YANG_SPIN = 0.32
        /** How much faster the bass at full turns it. */
        const val YANG_SPIN_BASS = 0.9
        /** How fast Yang turns while music plays, as a share of the speed above: 60% slower. */
        const val YANG_MUSIC_PACE = 0.4
        const val SMALL_RATE = 1.6
        /** Yang's orbits are this much wider than high. */
        const val WIDE = 1.3

        /** make_rpamp_ground.py's BANDS, six to a ribbon: y0 (from the top, as a share of the height),
         * tilt, amp, freq, phase, width. Its swell and weight follow in [SWELL] and [WEIGHT]. */
        private val BANDS = doubleArrayOf(
            0.18, 0.30, 0.10, 0.9, 0.0, 0.20,
            0.44, -0.22, 0.13, 0.6, 2.1, 0.16,
            0.72, 0.18, 0.09, 1.2, 4.0, 0.22,
            1.02, -0.26, 0.11, 0.8, 1.2, 0.18,
            -0.06, 0.16, 0.08, 1.5, 3.3, 0.14,
        )
        private val SWELL = doubleArrayOf(0.55, 0.70, 0.45, 0.60, 0.50)
        private val WEIGHT = doubleArrayOf(1.00, 0.90, 0.85, 0.70, 0.55)
        /** Each ribbon's colour: 0 tint, 1 glow, 2 shade. */
        private val COLOUR = doubleArrayOf(0.0, 0.0, 1.0, 2.0, 1.0)
    }
}
