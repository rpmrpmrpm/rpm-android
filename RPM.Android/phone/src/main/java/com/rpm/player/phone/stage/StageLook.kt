package com.rpm.player.phone.stage

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.rpm.player.core.R
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.GlassCard
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon

/**
 * What the stage can draw behind the words. The iPhone's `StageLook` (RPMUI/StageLook.swift), value for value: the
 * same numbers are stored, under the same key, so a look keeps its place in the list it is saved by.
 *
 * The names and the lines under them are the desktop gallery's own strings. The line of description is what helps a
 * person choose between a plate of sand and a sea of ribbons, not the name.
 */
enum class StageLook(val stored: Int, @StringRes val title: Int, @StringRes val detail: Int, val glyph: String) {
    /** The artist's photograph. */
    Artwork(0, R.string.Details_Art, R.string.Rpm_Stage_ArtworkDesc, ""),

    /** The ribbon sea: contour lines over an open swell. */
    Ribbon(1, R.string.Viz_RibbonSeaName, R.string.Viz_RibbonSeaDesc, ""),

    /** The flight through the block field. */
    Blocks(2, R.string.Viz_LowFlowName, R.string.Viz_LowFlowDesc, ""),

    /** Chladni's plate of sand. */
    Plate(3, R.string.Rpm_Stage_Plate, R.string.Viz_ChladniDesc, ""),

    /** The slice through a four-dimensional lattice, marched. */
    Lattice(4, R.string.Viz_LatticeName, R.string.Viz_LatticeDesc, ""),

    /** Two mirrors and one strut, marched. */
    Kaleidoscope(5, R.string.Viz_KaleidoscopeName, R.string.Viz_KaleidoscopeDesc, ""),

    /** The folded tunnel, marched. */
    Tunnel(6, R.string.Viz_TunnelName, R.string.Viz_TunnelDesc, ""),

    /** The spectrum, mirrored into four. */
    Butterfly(7, R.string.Viz_ButterflyName, R.string.Viz_ButterflyDesc, ""),

    /** A flower of light on a golden spiral. */
    Bloom(8, R.string.Viz_BloomName, R.string.Viz_BloomDesc, "");

    /**
     * Whether this app can draw it yet. A look that is listed and does nothing is worse than one that is not
     * listed: the picker is where somebody decides, and a row that shows the artwork instead looks like a fault.
     * The iPhone keeps the same property for the same reason.
     */
    val isDrawn: Boolean get() = this == Artwork || StageLooks.drawn(this)

    companion object {
        /** The looks the picker offers, which are the ones that draw. */
        val offered: List<StageLook> get() = entries.filter { it.isDrawn }

        fun of(stored: Int): StageLook? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * The stage's two settings, kept for the person across launches, under the iPhone's keys: which look it opens on
 * (`stage.look`) and whether the chrome is pinned visible (`stage.pinned`). They are kept here and not in
 * `PhoneSettings` because only the stage reads them.
 *
 * Read once, on the main thread, as the stage is first shown; written with `apply()`, which writes in the
 * background.
 */
class StageSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * The look chosen. A stored look that this build cannot draw opens as the artwork, as the iPhone's `look`
     * property checks when it reads.
     */
    var look by mutableStateOf(StageLook.of(prefs.getInt(KEY_LOOK, 0))?.takeIf { it.isDrawn } ?: StageLook.Artwork)
        private set

    /**
     * Whether the chrome stays up. A user watching from across a room wants the text to stay, and tapping the screen
     * every six seconds to keep it would be worse than a chrome that never hid.
     */
    var pinned by mutableStateOf(prefs.getBoolean(KEY_PINNED, false))
        private set

    fun choose(look: StageLook) {
        this.look = look
        prefs.edit().putInt(KEY_LOOK, look.stored).apply()
    }

    fun pin(on: Boolean) {
        pinned = on
        prefs.edit().putBoolean(KEY_PINNED, on).apply()
    }

    companion object {
        private const val FILE = "rpm.phone"
        private const val KEY_LOOK = "stage.look"
        private const val KEY_PINNED = "stage.pinned"

        @Volatile private var held: StageSettings? = null

        /** One for the app, so a look chosen on one visit to the stage is the look of the next. */
        fun of(context: Context): StageSettings =
            held ?: synchronized(this) { held ?: StageSettings(context.applicationContext).also { held = it } }
    }
}

/**
 * Which look the stage is set to, over everything else on the screen: the iPhone's `LookPicker`. It is the app's own
 * panel and not a menu, because a menu cannot carry the line of description under each name. A tap outside the
 * panel closes it.
 */
@Composable
fun LookPicker(backdrop: Backdrop, showing: StageLook, onPick: (StageLook) -> Unit, onClose: () -> Unit) {
    VisualPicker(backdrop, stringResource(showing.title), onClose) {
        for (look in StageLook.offered) LookLine(look.glyph, look.title, look.detail, on = look == showing) { onPick(look) }
    }
}

/**
 * The panel the visualisers are picked from: its title and the name of the one shown, over the [rows]. The stage's
 * [LookPicker] and the modern player on its side both use it, so the two lists look the same. A tap outside the panel
 * calls [onClose].
 */
@Composable
fun VisualPicker(backdrop: Backdrop, shown: String, onClose: () -> Unit, rows: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0x99 / 255f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        // The panel takes the height the turned phone leaves, and no more than 420.
        GlassCard(
            backdrop,
            Modifier
                .padding(vertical = 24.dp)
                .width(340.dp)
                .heightIn(max = 420.dp)
                // A tap on the panel stays on the panel and does not close it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(stringResource(R.string.Rpm_Stage_Visualiser), Modifier.weight(1f), style = displayBold(15f).copy(color = Color.White))
                    BasicText(shown, style = display(12f).copy(color = Ink.white(0x88)))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.white(0x1A)))
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) { rows() }
            }
        }
    }
}

/**
 * One row of the picker: the glyph, the name over its line of description, and a tick on the one shown. A [detail]
 * that is empty leaves the name alone on its row.
 */
@Composable
fun LookLine(glyph: String, @StringRes title: Int, @StringRes detail: Int?, on: Boolean, onClick: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (pressed) Modifier.background(Ink.white(0x14)) else Modifier)
            .clickable(presses, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(20.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
            BasicText(glyph, style = icon(15f).copy(color = if (on) Ink.cyan else Ink.white(0xB3)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            BasicText(stringResource(title), style = display(15f).copy(color = Color.White))
            val said = detail?.let { stringResource(it) }.orEmpty()
            if (said.isNotEmpty()) BasicText(said, style = display(12f).copy(color = Ink.white(0x88)))
        }
        if (on) BasicText(Glyph.check, Modifier.padding(top = 1.dp), style = icon(14f).copy(color = Color.White))
    }
}
