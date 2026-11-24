package com.rpm.player.core.library

/**
 * Where a folder's name goes on the folder frame (`folder_frame.png`): centred in the black bar under the folder, on
 * one line. The
 * iPhone's `FolderFrameMetrics` (RPMDesign/FolderFrameMetrics.swift) has the same numbers; change both together.
 *
 * The drawing is 1000 pixels square: a folder-shaped window with round corners and a soft glow round its edge, on
 * black. The window runs from 77 to 920 across and from 82 to 714 down. Its tab is at the top left, from 77 to about
 * 420 across, and slopes down to the folder's top edge at 186. The black bar under the folder runs from 715 to the
 * bottom edge. Every number here is a share of the square, so the same rule sets the name at any size. y counts down, as in the drawing.
 */
object FolderFrameMetrics {
    /** The middle of the bar under the folder, down the square: halfway between 715 and 1000. */
    const val BAR_CENTRE = (715f + 1000f) / 2f / 1000f

    /** How tall the gauge's ink is, as a share of the square. This sets the type size, as on a list's frame
     * ([ListFrameMetrics.GAUGE]), so every folder's name is set at one size. */
    const val NAME_CROSS = 0.095f

    /** The widest a name may run, as a share of the square: as wide as the folder. A longer name is set smaller
     * rather than cut. */
    const val NAME_RUN = 0.84f

    /** How the name is set: its size, and where the pen and the baseline go, in the square's units. */
    data class Lettering(val size: Float, val x: Float, val baseline: Float)

    /**
     * The size and place of [name] on a square of [edge], from a font that measures a string at a size.
     *
     * The name is centred across the square by its own ink. Down the square, the body of the letters is centred on
     * [BAR_CENTRE]: the part from the top of the gauge's ink (the top of the tall letters, as high as the capitals)
     * to the baseline. Hanging tails such as the "g"'s are left out, so a name looks centred in the bar, and every
     * folder's name sits on the same line whatever letters it has. Null where the font measures nothing; the frame is
     * then drawn without a name.
     */
    fun lettering(name: String, edge: Float, measuring: (String, Float) -> ListFrameMetrics.Ink): Lettering? {
        if (edge <= 0f || name.isEmpty()) return null
        val cross = edge * NAME_CROSS
        var size = cross
        // A font with no Latin letters measures the gauge as nothing; the name then sets the size.
        var gauged = measuring(ListFrameMetrics.GAUGE, size)
        if (gauged.height <= 0f) gauged = measuring(name, size)
        if (gauged.height <= 0f) return null
        size *= cross / gauged.height
        var ink = measuring(name, size)
        if (ink.width <= 0f) return null
        if (ink.width > edge * NAME_RUN) {
            size *= (edge * NAME_RUN) / ink.width
            ink = measuring(name, size)
        }
        // Measured again at the size set, so the top of the letters is the one this font draws at.
        var line = measuring(ListFrameMetrics.GAUGE, size)
        if (line.height <= 0f) line = ink
        return Lettering(size, x = (edge - ink.width) / 2f - ink.left, baseline = edge * BAR_CENTRE - line.top / 2f)
    }
}
