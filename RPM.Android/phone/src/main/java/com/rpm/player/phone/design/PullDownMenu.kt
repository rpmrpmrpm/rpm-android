package com.rpm.player.phone.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One choice of a [PullDownMenu]. [ticked] marks the choice in force, as a SwiftUI `Picker` inside a `Menu` does; a
 * plain `Button` in a menu has no mark. [dividerAfter] draws iOS's wide menu divider under it, which separates "All
 * genres" from the genres. [destructive] draws the label in [Ink.red], as iOS draws a `Button(role: .destructive)`.
 */
class PullItem(val label: String, val ticked: Boolean = false, val dividerAfter: Boolean = false, val destructive: Boolean = false, val onPress: () -> Unit)

/**
 * A menu that drops from the key that opened it: what SwiftUI's `Menu` draws on iPhone, which the radio's filters use.
 * Unlike the long-press [ContextMenu] it does not dim the page or lift anything out of it; it is the system's menu
 * panel alone, frosted, 250 dp wide, with rows at least 44 dp high in the system face at 17.
 *
 * - It stands under the key, its leading edge at the key's, when it fits there; otherwise over the key; and when it
 *   fits on neither side, as the country list does not on a short screen, it takes the taller side and scrolls.
 * - A tick in front of a label marks the choice in force, in the leading gutter as iOS draws it.
 * - It grows out of the key with a spring, and fades away when it is put down. A tap outside it, or Back, puts it down
 *   without choosing.
 *
 * @param source where the key is, in the coordinates of the screen this overlay fills.
 * @param onDismiss called once the menu has faded, whether a choice was made or not.
 */
@Composable
fun PullDownMenu(backdrop: Backdrop, source: Rect, items: List<PullItem>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember { Animatable(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
    fun close() {
        if (leaving) return
        leaving = true
        scope.launch {
            shown.animateTo(0f, tween(140))
            onDismiss()
        }
    }
    BackHandler(onBack = ::close)

    val density = LocalDensity.current
    val insets = WindowInsets.safeDrawing.asPaddingValues(density)
    val top = with(density) { (insets.calculateTopPadding() + EDGE).toPx() }
    val bottom = with(density) { (insets.calculateBottomPadding() + EDGE).toPx() }
    val gap = with(density) { GAP.toPx() }
    val margin = with(density) { SIDE.toPx() }
    val ticks = items.any { it.ticked }
    // The menu's natural height, from its rows, so the side it opens on is known on the first frame.
    val tall = with(density) {
        (ROW * items.size + DIVIDER * items.count { it.dividerAfter } + HAIRLINE * (items.size - 1).coerceAtLeast(0)).toPx()
    }
    var screen by remember { mutableStateOf(0f) }
    val below = screen - bottom - (source.bottom + gap)
    val above = source.top - gap - top
    val under = tall <= below || (tall > above && below >= above)
    val room = if (under) below else above

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screen = it.height.toFloat() }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = ::close),
    ) {
        if (screen <= 0f) return@Box
        Layout(
            content = {
                val shape = RoundedCornerShape(CORNER)
                Box(
                    Modifier
                        .width(WIDTH)
                        .heightIn(max = with(density) { room.coerceAtLeast(ROW.toPx()).toDp() })
                        .graphicsLayer {
                            val grow = 0.7f + 0.3f * shown.value
                            scaleX = grow
                            scaleY = grow
                            alpha = shown.value.coerceIn(0f, 1f)
                            transformOrigin = TransformOrigin(0.1f, if (under) 0f else 1f)
                        }
                        .frost(backdrop, shape, CARD_BLUR)
                        .clip(shape)
                        .background(FILL)
                        // A tap on the menu's own edge must not reach the layer under it, which would close it.
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    val rows = rememberLazyListState(initialFirstVisibleItemIndex = items.indexOfFirst { it.ticked }.coerceAtLeast(0).let { if (it > 2) it - 2 else 0 })
                    LazyColumn(state = rows) {
                        itemsIndexed(items) { at, item ->
                            if (at > 0 && !items[at - 1].dividerAfter) Box(Modifier.fillMaxWidth().height(HAIRLINE).background(SEPARATOR))
                            Entry(item, ticks) {
                                item.onPress()
                                close()
                            }
                            if (item.dividerAfter) Box(Modifier.fillMaxWidth().height(DIVIDER).background(DIVIDER_FILL))
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val menu = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
            val width = constraints.maxWidth
            val y = if (under) source.bottom + gap else source.top - gap - menu.height
            val x = source.left.coerceIn(margin, (width - margin - menu.width).coerceAtLeast(margin))
            layout(width, constraints.maxHeight) { menu.place(x.roundToInt(), y.roundToInt()) }
        }
    }
}

/** One row: the tick's gutter where the menu has ticks, the label, lit while a finger is on it. */
@Composable
private fun Entry(item: PullItem, ticks: Boolean, onPress: () -> Unit) {
    val presses = remember { MutableInteractionSource() }
    val pressed by presses.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = ROW)
            .background(if (pressed) PRESSED else Color.Transparent)
            .clickable(presses, indication = null, role = if (ticks) Role.RadioButton else Role.Button, onClick = onPress)
            .padding(start = if (ticks) 10.dp else 16.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ticks) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                if (item.ticked) BasicText(Glyph.check, Modifier.padding(start = 2.dp), style = icon(14f).copy(color = Color.White))
            }
        }
        BasicText(item.label, maxLines = 2, overflow = TextOverflow.Ellipsis, style = system(17f).copy(color = if (item.destructive) Ink.red else Color.White))
    }
}

/** iOS's menu sizes, in points, which are dp here. */
private val WIDTH = 250.dp
private val ROW = 44.dp
private val CORNER = 13.dp
private val GAP = 6.dp
private val SIDE = 16.dp
private val EDGE = 8.dp
private val HAIRLINE = 0.5.dp
private val DIVIDER = 8.dp

/** The system's dark menu colours, the same as [ContextMenu]'s: its fill over the frost, separators, a pressed row. */
private val FILL = Color(0xB8252528)
private val SEPARATOR = Color.White.copy(alpha = 0x26 / 255f)
private val DIVIDER_FILL = Color.Black.copy(alpha = 0x33 / 255f)
private val PRESSED = Color.White.copy(alpha = 0x1F / 255f)
