package com.rpm.player.phone.coach

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rpm.player.core.R
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.displayBold
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.system
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The first-run hints: the desktop's coachmarks (`CoachmarkPainter`, `CoachTour`) on the phone. One hint shows at a time,
 * pointing at a part of the screen with a curved arrow, with the rest of the screen dimmed round it. Each hint is
 * answered once, with Next or with the round close key ("Don't show this again"), and is then never shown again
 * ([com.rpm.player.phone.PhoneSettings.coachSeen]). The settings page can put them all back.
 *
 * There are two tours. The app's ([APP_TOUR]) points at the tabs and the mini player, and ends with the card "And that's
 * the tour!". The player's ([PLAYER_TOUR]) points at the parts of the player the first time it is opened, and has no
 * card of its own. A hint whose part is not on screen waits, and is not marked as seen.
 */
enum class CoachMark(val key: String, val target: String, val words: Int) {
    Home("home", CoachTargets.TAB_HOME, R.string.Coach_Phone_Home),
    Collection("collection", CoachTargets.TAB_LIBRARY, R.string.Coach_Phone_Collection),
    Radio("radio", CoachTargets.TAB_RADIO, R.string.Coach_Phone_Radio),
    Feed("feed", CoachTargets.TAB_FEED, R.string.Coach_Phone_Feed),
    Settings("settings", CoachTargets.TAB_SETTINGS, R.string.Coach_Phone_Settings),
    MiniPlayer("mini-player", CoachTargets.MINI_PLAYER, R.string.Coach_Phone_MiniPlayer),

    PlayerCover("player-cover", CoachTargets.PLAYER_COVER, R.string.Coach_Player_Cover),
    PlayerHeart("player-heart", CoachTargets.PLAYER_HEART, R.string.Coach_Player_Heart),
    PlayerLyrics("player-lyrics", CoachTargets.PLAYER_LYRICS, R.string.Coach_Player_Lyrics),
    PlayerQueue("player-queue", CoachTargets.PLAYER_QUEUE, R.string.Coach_Player_Queue),
    PlayerMenu("player-menu", CoachTargets.PLAYER_MENU, R.string.Coach_Player_Menu),
    PlayerSpeakers("player-speakers", CoachTargets.PLAYER_SPEAKERS, R.string.Coach_Player_Speakers),
    PlayerGrip("player-grip", CoachTargets.PLAYER_GRIP, R.string.Coach_Player_Grip);

    companion object {
        /** The app's tour, in order. The mini player's hint waits until something is loaded. */
        val APP_TOUR = listOf(Home, Collection, Radio, Feed, Settings, MiniPlayer)

        /** The player's tour, in order. The speakers' hint is passed over where the player has no speaker key. */
        val PLAYER_TOUR = listOf(PlayerCover, PlayerHeart, PlayerLyrics, PlayerQueue, PlayerMenu, PlayerSpeakers, PlayerGrip)

        /** The key of the closing card, kept with the hints so it shows once. The desktop's `tour-done`. */
        const val DONE = "tour-done"
    }
}

/**
 * Where the parts the hints point at are on screen, in the window's coordinates, by name. A part reports itself with
 * [coachTarget]; a part that leaves the screen is taken out, so a hint never points at nothing.
 */
@Stable
class CoachTargets {
    private val at = mutableStateMapOf<String, Rect>()

    fun of(name: String): Rect? = at[name]

    internal fun put(name: String, rect: Rect) {
        if (at[name] != rect) at[name] = rect
    }

    internal fun remove(name: String) {
        at.remove(name)
    }

    companion object {
        const val TAB_HOME = "tab.home"
        const val TAB_LIBRARY = "tab.library"
        const val TAB_RADIO = "tab.radio"
        const val TAB_FEED = "tab.feed"
        const val TAB_SETTINGS = "tab.settings"
        const val MINI_PLAYER = "mini-player"
        const val PLAYER_COVER = "player.cover"
        const val PLAYER_HEART = "player.heart"
        const val PLAYER_LYRICS = "player.lyrics"
        const val PLAYER_QUEUE = "player.queue"
        const val PLAYER_MENU = "player.menu"
        const val PLAYER_SPEAKERS = "player.speakers"
        const val PLAYER_GRIP = "player.grip"
    }
}

/** The app's [CoachTargets], or null where nothing is pointed at. Set at the root. */
val LocalCoachTargets = staticCompositionLocalOf<CoachTargets?> { null }

/** Reports this part's place to the hints under [name] while it is on screen. Nothing where no targets are set. */
fun Modifier.coachTarget(name: String): Modifier = composed {
    val targets = LocalCoachTargets.current ?: return@composed this
    DisposableEffect(targets, name) { onDispose { targets.remove(name) } }
    onGloballyPositioned { if (it.isAttached) targets.put(name, it.boundsInRoot()) }
}

/** The desktop's `RpmCyanBrush`: the hints' rings, arrows, borders and links. */
private val ACCENT = Color(0xFF04C8FF)

/** The dim over the rest of the screen (`ColorHelper.FromArgb(0xA6, 0x02, 0x05, 0x0A)`). */
private val DIM = Color(0xA602050A)

/** The bubble's and the closing card's fill, the app's ground at 0xF7. */
private val PANEL = Color(0xF70E1520)
private val DISC = Color(0xFF0E1520)

private val BUBBLE_WIDTH = 270.dp
private val REACH = 48.dp
private val EDGE = 14.dp
private val PAD = 6.dp
private const val HEAD = 14f

/**
 * The hint for [mark] over everything, pointing at [target]. [onNext] answers it and moves on; [onClose] answers it and
 * ends the tour. The whole screen takes the touches while it shows, so only the hint's own keys answer, as the desktop's
 * hints with a Next link block the window.
 */
@Composable
fun CoachHint(mark: CoachMark, target: Rect, onNext: () -> Unit, onClose: () -> Unit) {
    val density = LocalDensity.current
    // Each new hint fades in, while the dim stays put (`CoachmarkPainter`'s 300 ms on the marks layer).
    val marks = remember(mark) { Animatable(0f) }
    LaunchedEffect(mark) { marks.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
    BackHandler(onBack = onClose)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        val screenW = with(density) { maxWidth.toPx() }
        val screenH = with(density) { maxHeight.toPx() }
        val pad = with(density) { PAD.toPx() }
        val hole = Rect(target.left - pad, target.top - pad, target.right + pad, target.bottom + pad)
        // The dim with the part cut out, and the ring round the cut.
        Box(
            Modifier.fillMaxSize().drawBehind {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(0f, 0f, size.width, size.height))
                    addRect(hole)
                }
                drawPath(path, DIM)
            },
        )
        var bubble by remember(mark) { mutableStateOf(IntSize.Zero) }
        val width = minOf(BUBBLE_WIDTH, maxWidth - EDGE * 2)
        val widthPx = with(density) { width.toPx() }
        val reach = with(density) { REACH.toPx() }
        val edge = with(density) { EDGE.toPx() }
        // Over the part when it is in the lower half of the screen, under it otherwise.
        val above = target.center.y > screenH / 2
        val left = (target.center.x - widthPx / 2).coerceIn(edge, (screenW - edge - widthPx).coerceAtLeast(edge))
        val top = if (above) target.top - pad - reach - bubble.height else target.bottom + pad + reach
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = marks.value }) {
            // The ring, the arrow and its head, drawn under the bubble.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val ring = 1.5.dp.toPx()
                    drawRoundRect(ACCENT, hole.topLeft, hole.size, CornerRadius(8.dp.toPx()), style = Stroke(ring))
                    if (bubble == IntSize.Zero) return@drawBehind
                    val from = Offset((target.center.x).coerceIn(left + 24.dp.toPx(), left + widthPx - 24.dp.toPx()), if (above) top + bubble.height else top)
                    val to = Offset(target.center.x, if (above) hole.top - 5.dp.toPx() else hole.bottom + 5.dp.toPx())
                    arrow(from, to, flip = above)
                },
            )
            Column(
                Modifier
                    .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                    .width(width)
                    .onSizeChanged { bubble = it },
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    BasicText(
                        stringResource(mark.words),
                        Modifier
                            .fillMaxWidth()
                            .background(PANEL, RoundedCornerShape(12.dp))
                            .border(1.5.dp, ACCENT, RoundedCornerShape(12.dp))
                            .padding(horizontal = 15.dp, vertical = 11.dp),
                        style = system(13.5f).copy(color = Color.White, lineHeight = 20.sp),
                    )
                    // The round close key on the bubble's top left corner.
                    val dismiss = stringResource(R.string.Coach_Dismiss)
                    Box(
                        Modifier
                            .offset((-12).dp, (-12).dp)
                            .size(24.dp)
                            .background(DISC, CircleShape)
                            .border(1.5.dp, ACCENT, CircleShape)
                            .semantics { contentDescription = dismiss }
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) { BasicText(Glyph.close, style = icon(11f).copy(color = ACCENT)) }
                }
                Row(
                    Modifier
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onNext)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(stringResource(R.string.Coach_Next), style = system(14f, FontWeight.SemiBold).copy(color = ACCENT))
                    BasicText(Glyph.into, style = icon(10f).copy(color = ACCENT))
                }
            }
        }
    }
}

/** The arrow: one bowed line from the bubble to the part, and a filled head at the part (`CoachmarkPainter.Arrow`). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.arrow(from: Offset, to: Offset, flip: Boolean) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy)
    if (length < 1f) return
    val head = HEAD.dp.toPx()
    // The bow, across the line, as on the desktop: a sixth of its length, kept between 2 and 40.
    val bow = (length * 0.16f).coerceIn(2f, 40.dp.toPx()) * (if (flip) -1f else 1f)
    val nx = -dy / length
    val ny = dx / length
    val control = Offset((from.x + to.x) / 2 + nx * bow, (from.y + to.y) / 2 + ny * bow)
    // The line stops short of the tip, under the head.
    val tx = to.x - control.x
    val ty = to.y - control.y
    val tl = hypot(tx, ty).coerceAtLeast(1f)
    val ux = tx / tl
    val uy = ty / tl
    val stop = Offset(to.x - ux * head * 0.6f, to.y - uy * head * 0.6f)
    val line = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, stop.x, stop.y)
    }
    drawPath(line, ACCENT, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    val half = head * 0.55f
    val back = Offset(to.x - ux * head, to.y - uy * head)
    val tip = Path().apply {
        moveTo(to.x, to.y)
        lineTo(back.x - uy * half, back.y + ux * half)
        lineTo(back.x + uy * half, back.y - ux * half)
        close()
    }
    drawPath(tip, ACCENT)
}

/**
 * The card that ends the app's tour, centred over the dimmed screen: "And that's the tour!", a line, and the key that
 * hands the app over (`CoachmarkPainter`'s finale card).
 */
@Composable
fun CoachFinale(onDone: () -> Unit) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
    BackHandler(onBack = onDone)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value }
            .background(DIM)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .background(PANEL, RoundedCornerShape(16.dp))
                .border(1.5.dp, ACCENT, RoundedCornerShape(16.dp))
                .padding(start = 28.dp, end = 28.dp, top = 28.dp, bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BasicText(stringResource(R.string.Coach_DoneTitle), style = displayBold(30f).copy(color = Color.White, textAlign = TextAlign.Center))
            BasicText(
                stringResource(R.string.Coach_Phone_DoneBody),
                style = display(14f).copy(color = Color(0xC4FFFFFF), textAlign = TextAlign.Center, lineHeight = 22.sp),
            )
            GlassButton(onClick = onDone, modifier = Modifier.padding(top = 10.dp), stretches = false, inset = 28.dp) { style ->
                BasicText(stringResource(R.string.Coach_DoneButton), style = style)
            }
        }
    }
}
