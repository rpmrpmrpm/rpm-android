package com.rpm.player.phone.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.stage.LookLine
import com.rpm.player.phone.stage.StageLook
import com.rpm.player.phone.stage.VisualPicker

/**
 * How the player looks when the phone is turned on its side, chosen on the Appearance settings (`player.landscape`).
 * [Classic] is the stage ([com.rpm.player.phone.stage.StageView]): a record on a deck, and the visualisers. [Modern] is
 * the now playing sheet laid out for a phone on its side ([SidewaysPlayer]).
 */
enum class LandscapeStyle(val stored: String) {
    Classic("classic"),
    Modern("modern");

    companion object {
        /** The style kept under [stored], and [Classic] when nothing is kept. */
        fun read(stored: String?): LandscapeStyle = entries.firstOrNull { it.stored == stored } ?: Classic
    }
}

/**
 * What is drawn behind the modern player on its side (`player.landscape.visual`): the room, which is called Lava in the
 * picker, or one of the stage's visualisers. The room is null. A visualiser is kept by the stage's own number for it
 * ([StageLook.stored]), so a look keeps its place when the list changes.
 */
internal object LandscapeVisual {
    /** What is kept for the room. */
    const val ROOM = "room"

    /** The stage's visualisers that can be drawn behind the sheet. The stage's artwork is left out: the cover is already
     * on the screen. */
    val offered: List<StageLook> get() = StageLook.offered.filter { it != StageLook.Artwork }

    fun stored(look: StageLook?): String = look?.stored?.toString() ?: ROOM

    /** The visualiser kept under [stored]; null, the room, for the room, for nothing kept and for a look this build does
     * not draw. */
    fun read(stored: String?): StageLook? = stored?.toIntOrNull()?.let(StageLook::of)?.takeIf { it in offered }
}

/**
 * The now playing sheet on its side: the modern style ([LandscapeStyle.Modern]). The cover, with the spectrum on both
 * sides of it, is on the left; the words, the seek bar, the transport and the capsule of keys are in one column on the
 * right; the visualiser key and the speaker key are in the top right corner. Each is put where the drawing has it, from
 * the screen's size ([SidewaysLayout]). The sheet ([NowPlayingSheet]) gives the pieces, so they are the same ones, and
 * behave the same, as upright; [SidewaysLayout.scale] sizes them.
 *
 * The notch is kept clear: an element that would reach into it is moved in by the difference.
 *
 * @param record the cover, or the lyrics in its place, in a modifier that fills the screen, at the layout's places.
 * @param words the title, the artist and the heart.
 * @param needle the seek bar and its clocks.
 * @param transport previous, play and next.
 * @param below the capsule with the lyrics, queue and menu keys.
 * @param cast whether the speaker key is shown. Without it, the visualiser key takes the corner.
 * @param keys the visualiser key and the speaker key, in the modifiers that place them, with the scale.
 */
@Composable
internal fun SidewaysPlayer(
    record: @Composable (Modifier, SidewaysLayout) -> Unit,
    words: @Composable (Modifier, Float) -> Unit,
    needle: @Composable (Modifier, Float) -> Unit,
    transport: @Composable (Modifier, Float) -> Unit,
    below: @Composable (Modifier, Float) -> Unit,
    cast: Boolean,
    keys: @Composable (visualAt: Modifier, castAt: Modifier, scale: Float) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        // The places are measured from the start edge, as the offsets that put them there are.
        val left = cutout.getLeft(density, direction)
        val right = cutout.getRight(density, direction)
        val ltr = direction == LayoutDirection.Ltr
        val start = if (ltr) left else right
        val end = if (ltr) right else left
        val laid = with(density) {
            SidewaysLayout.of(
                maxWidth.value,
                maxHeight.value,
                start.toDp().value,
                cutout.getTop(density).toDp().value,
                end.toDp().value,
                cutout.getBottom(density).toDp().value,
            )
        }
        record(Modifier.fillMaxSize(), laid)
        words(Modifier.at(laid.words), laid.scale)
        needle(Modifier.at(laid.needle), laid.scale)
        transport(Modifier.at(laid.transport), laid.scale)
        below(Modifier.at(laid.capsule), laid.scale)
        keys(Modifier.at(if (cast) laid.visual else laid.cast), Modifier.at(laid.cast), laid.scale)
    }
}

/** Puts an element at [frame], measured from the screen's top start corner. */
internal fun Modifier.at(frame: Frame): Modifier = offset(frame.x.dp, frame.y.dp).size(frame.width.dp, frame.height.dp)

/**
 * The dark that runs in from the right over the room or the visualiser, so the column of words reads on any cover. The
 * left half, with the cover, stays clear.
 */
@Composable
internal fun SidewaysShade(modifier: Modifier) {
    Box(
        modifier.background(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.42f to Color.Transparent,
                0.62f to Ink.ground.copy(alpha = 0.45f),
                1f to Ink.ground.copy(alpha = 0.78f),
            ),
        ),
    )
}

/**
 * The visualiser key: a glass circle, as the speaker key beside it, that opens the list of what can be drawn behind the
 * sheet ([SidewaysVisualPicker]). Its glyph is the stage's visualiser glyph, lit while a visualiser is drawn and dim over
 * the room, as on the stage.
 */
@Composable
internal fun VisualKey(backdrop: Backdrop, showing: StageLook?, modifier: Modifier, scale: Float, onClick: () -> Unit) {
    val label = stringResource(R.string.Rpm_Stage_Visualiser)
    GlassKey(backdrop, onClick, modifier.semantics { contentDescription = label }, edge = 40.dp * scale) {
        BasicText(StageLook.Ribbon.glyph, style = icon(16f * scale).copy(color = if (showing == null) Ink.white(0xB8) else Color.White))
    }
}

/**
 * The list of what can be drawn behind the sheet on its side, in the stage's own panel ([VisualPicker]): the room first,
 * then the stage's visualisers with their names and lines of description. [offersLooks] false lists the room alone.
 */
@Composable
internal fun SidewaysVisualPicker(
    backdrop: Backdrop,
    showing: StageLook?,
    offersLooks: Boolean,
    onPick: (StageLook?) -> Unit,
    onClose: () -> Unit,
) {
    VisualPicker(backdrop, stringResource(showing?.title ?: R.string.Rpm_Stage_Room), onClose) {
        LookLine(LAVA_GLYPH, R.string.Rpm_Stage_Room, null, on = showing == null) { onPick(null) }
        if (offersLooks) {
            for (look in LandscapeVisual.offered) LookLine(look.glyph, look.title, look.detail, on = look == showing) { onPick(look) }
        }
    }
}

/** The room's glyph in the picker: a drop, for the slow blobs of a lava lamp. */
private const val LAVA_GLYPH = ""
