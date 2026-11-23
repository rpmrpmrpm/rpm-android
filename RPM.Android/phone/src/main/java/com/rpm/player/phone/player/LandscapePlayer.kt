package com.rpm.player.phone.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
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
 * sides of it, is centred in the left half; the words, the seek bar, the transport and the row of keys are in one column
 * in the right half, centred from top to bottom; the visualiser key and the speaker key are in the top right corner. The
 * sheet ([NowPlayingSheet]) gives the pieces, so they are the same ones, and behave the same, as upright.
 *
 * The notch is kept clear on both sides: the cover's half stops at a notch on the left, the column and the keys at a
 * notch on the right.
 *
 * @param record the cover, or the lyrics in its place, filling the modifier it is given, with the cover's side.
 * @param panel the column's contents: the words, the seek bar, the transport and the row of keys.
 * @param keys the keys in the top right corner, from left to right.
 */
@Composable
internal fun SidewaysPlayer(
    record: @Composable (Modifier, Dp) -> Unit,
    panel: @Composable () -> Unit,
    keys: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
            ) {
                // Room is left on both sides of the cover for the spectrum to run out into.
                val edge = minOf(SLEEVE, maxHeight * COVER_OF_HEIGHT, maxWidth * COVER_OF_WIDTH)
                record(Modifier.fillMaxSize(), edge)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                    // Under the row of keys at the top, so the column never runs into them.
                    .padding(start = 12.dp, end = COLUMN_END, top = KEYS_ROW, bottom = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { panel() }
            }
        }
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End + WindowInsetsSides.Top))
                .padding(top = 14.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = keys,
        )
    }
}

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
internal fun VisualKey(backdrop: Backdrop, showing: StageLook?, onClick: () -> Unit) {
    val label = stringResource(R.string.Rpm_Stage_Visualiser)
    GlassKey(backdrop, onClick, Modifier.semantics { contentDescription = label }) {
        BasicText(StageLook.Ribbon.glyph, style = icon(16f).copy(color = if (showing == null) Ink.white(0xB8) else Color.White))
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

/** The cover's side as a share of the left half's height and width, as in the drawing the layout follows. */
private const val COVER_OF_HEIGHT = 0.66f
private const val COVER_OF_WIDTH = 0.62f

/** The room the keys in the top right corner take, which the column stays under. */
private val KEYS_ROW = 54.dp

/** The column's distance from the right edge. The heart reaches 12 dp into it. */
private val COLUMN_END = 32.dp

/** The room's glyph in the picker: a drop, for the slow blobs of a lava lamp. */
private const val LAVA_GLYPH = ""
