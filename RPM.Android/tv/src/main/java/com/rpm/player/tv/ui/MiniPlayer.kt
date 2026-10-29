package com.rpm.player.tv.ui

import androidx.compose.ui.unit.toSize

import androidx.compose.ui.layout.positionInParent

import androidx.compose.ui.layout.onGloballyPositioned

import androidx.compose.ui.graphics.drawscope.clipPath

import androidx.compose.ui.draw.drawWithContent

import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.rememberPosition
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import coil3.ImageLoader
import coil3.compose.AsyncImage
import com.rpm.player.core.R

/**
 * What the screens under the mini player tell it: whether their page is scrolled to the top, and whether a
 * layer of the screen covers it (the radio search, the tuner, the album search, a row's menu). Its glass
 * reads what is under it as it is drawn ([LiveGlassPanel]), so the screens need not say when that changed.
 */
@Stable
class MiniPlayerState {
    /**
     * The page in front's own answer to "is the page scrolled to its top?", read live whenever the mini player is
     * composed. The page hands its answer over when it comes to the front ([follow]); a page kept underneath does
     * not. The page must not write a plain value instead: that would be written in a SideEffect, which does not
     * watch the scroll, so the value would only change when something else recomposed the page.
     */
    private var page by mutableStateOf<State<Boolean>?>(null)
    val atTop: Boolean get() = page?.value ?: true

    fun follow(atTop: State<Boolean>) {
        if (page !== atTop) com.rpm.player.core.diag.Diag.d(MINI_LOG, "follows page ${System.identityHashCode(atTop)}, from ${caller()}")
        page = atTop
    }

    /** How many layers cover it now. This is a count and not a flag, because two can cover it at once (a row's
     * menu over the radio tuner), and the first to leave must not uncover it while the other is still there.
     * Each [cover] is paired with one [uncover], from the same DisposableEffect. */
    private var covers by mutableIntStateOf(0)
    val covered: Boolean get() = covers > 0

    fun cover() {
        covers += 1
        com.rpm.player.core.diag.Diag.d(MINI_LOG, "covered ($covers), from ${caller()}")
    }

    fun uncover() {
        covers = (covers - 1).coerceAtLeast(0)
        com.rpm.player.core.diag.Diag.d(MINI_LOG, "uncovered ($covers left), from ${caller()}")
    }

    /** Where a call came from, three frames deep, for the log: which screen followed, covered or uncovered. It is
     * called only for those calls, which happen a few times per screen opened and never per frame. */
    private fun caller(): String = Throwable().stackTrace.drop(2).take(3).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
}

/**
 * The mini player at the top centre of home, the wall and radio (PegasusChrome.xaml, PlayerCard;
 * PegasusChrome.Library.cs). It is a card of the light glass, `484·ms` by `122·ms`, dimmed ([GLASS_DIM]). It shows
 * the cover, the three lines, the time left and the seek bar. The desktop's row of previous, play and next
 * buttons on the bottom edge is left out, because the remote cannot reach those keys. The remote stands on the
 * whole card, and the player's own keys do the same.
 * With nothing playing it shows the rpm wordmark, on home only ([showsMark]). On the other screens it stays
 * hidden until something plays (PlayerAway.cs, MarkKeptOff).
 *
 * It shows at the top of a page, and while the remote stands on it. It hides as soon as the page scrolls down,
 * and comes back when the page is at its top again. It slides down and fades in over 300 ms (PlayerAway.cs).
 * It is one stop for the remote; OK opens the player.
 *
 * Unlike the desktop, the wordmark does not hide after 5 seconds (MarkKeptOff). On a fresh start the player is
 * empty until Play resumes the saved queue, so a timer would run out under the opening screen and home would
 * have no card to bring back by returning to the top. The wordmark stays at the top of home like the playing card.
 *
 * Down goes back into the screen under it, [below], which gives the focus to the item that had it there. The
 * remote's own search cannot do this: it compares the mini player with the screen's whole area, which starts
 * above it, so nothing on the screen counts as below the mini player. Left asks for the side menu ([onLeft]),
 * as Left does on the screens when there is nothing further left. The mini player is outside the screens, so
 * their edge rule never sees a Left pressed here.
 */
@Composable
fun MiniPlayer(
    state: MiniPlayerState,
    showsMark: Boolean,
    nowPlaying: NowPlaying?,
    images: ImageLoader,
    backdrop: Backdrop,
    /** Where it stands: it slides in from that edge, and the screen is under it or over it. */
    place: MiniPlace,
    below: FocusRequester,
    onOpen: () -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val wanted = !state.covered && (focused || state.atTop && (nowPlaying != null || showsMark))
    // Logs every change of what decides whether it shows, for `adb logcat -s rpm.mini` when it is missing where it
    // should be.
    androidx.compose.runtime.LaunchedEffect(wanted, state.covered, state.atTop, focused, nowPlaying != null) {
        com.rpm.player.core.diag.Diag.d(MINI_LOG, "shows $wanted: covered ${state.covered}, page at top ${state.atTop}, focused $focused, something in the player ${nowPlaying != null}")
    }
    val shown by animateFloatAsState(if (wanted) 1f else 0f, tween(SLIDE_MS, easing = CubicOut), label = "mini")
    // The card's blur ([LiveGlass]), drawn only while the mini player is shown at all.
    val glass = remember(backdrop) { LiveGlass(backdrop) }
    // Two facts about the slide, each changing once per slide. This way the slide's frames are read only in the layer
    // below and do not recompose the mini player.
    val glassLive by remember { androidx.compose.runtime.derivedStateOf { shown > 0f } }
    val reachable by remember { androidx.compose.runtime.derivedStateOf { shown > 0.5f } }

    val card = Size(484f, 122f)
    val edge = 34.px
    Box(
        modifier
            .padding(
                top = if (place.atTop) edge else 0.px,
                bottom = if (place.atTop) 0.px else edge,
                start = if (place == MiniPlace.BottomLeft) edge else 0.px,
                end = if (place == MiniPlace.BottomRight) edge else 0.px,
            )
            .graphicsLayer {
                alpha = shown
                // In from the edge it stands at.
                val away = (1 - shown) * (34.px.toPx() + (122.ms + 2.px).toPx())
                translationY = if (place.atTop) -away else away
            },
    ) {
        val spot = remember { FocusSpot() }
        SpotScope(spot, Modifier) {
            DeskButton(
                onClick = onOpen,
                corner = 7.ms,
                modifier = Modifier
                    .focusProperties {
                        canFocus = nowPlaying != null && reachable
                        // The screen is under a card at the top and over one at the bottom.
                        if (place.atTop) down = below else up = below
                    }
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { event ->
                        val key = event.nativeKeyEvent
                        if (key.keyCode != android.view.KeyEvent.KEYCODE_DPAD_LEFT || key.action != android.view.KeyEvent.ACTION_DOWN || key.repeatCount != 0) return@onKeyEvent false
                        onLeft()
                        true
                    }
                    .liveGlass(glass, glassLive),
            ) {
                LiveGlassPanel(
                    glass,
                    7.ms,
                    Modifier.size(card.width.ms + 2.px, card.height.ms + 2.px),
                ) {
                    Box(Modifier.matchParentSize().background(GLASS_DIM))
                    if (nowPlaying == null) {
                        Image(
                            painter = painterResource(R.drawable.wordmark),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(Color(0x8CFFFFFF)),
                            modifier = Modifier.align(Alignment.Center).size(40.ms * (46.5f / 18f), 40.ms),
                        )
                    } else {
                        // The lines are centred on the cover, both for a track (three lines and the bar) and for a
                        // station (its lines and no bar).
                        Row(Modifier.padding(13.ms + 1.px), verticalAlignment = Alignment.CenterVertically) {
                            val station = nowPlaying.station
                            Box(Modifier.size(96.ms).clip(RoundedCornerShape(6.px)).background(if (station != null) SLEEVE_DARK else Ink.Well), contentAlignment = Alignment.Center) {
                                if (station != null) {
                                    // The player's picture of a station ([StationArt]): its logo dressed by [StationMark] on
                                    // the sleeve's dark, a mark on its made ground, held off the edges by the share the
                                    // player's sleeve holds it by on this screen. The bare logo, fitted on the well, lost the
                                    // ground and showed the record glyph through a mark. A station whose logo does not load
                                    // shows the dark alone, as the player's sleeve does.
                                    val screen = LocalConfiguration.current
                                    val inset = remember(screen.screenWidthDp, screen.screenHeightDp) {
                                        stationInset(sleeveSide(screen.screenWidthDp.dp, screen.screenHeightDp.dp))
                                    }
                                    StationMark(station.logo, images, inset = inset, fadeInMs = HELD_FADE_MS, onState = {}, modifier = Modifier.matchParentSize())
                                    // The song playing, where the station names one and its cover was found: the same
                                    // picture the player's sleeve shows over the station ([StationArt]).
                                    SongCoverOver(rememberSongCover(station), images, Modifier.matchParentSize())
                                } else {
                                    Glyph(Fa.RECORD, 28.mts, Color(0x59FFFFFF))
                                    val picture = nowPlaying.cover
                                    if (picture != null) {
                                        // A new record keeps the last cover until its own cover has loaded, then fades to it.
                                        HeldCover(picture = picture, images = images, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    }
                                }
                            }
                            Spacer(Modifier.width(17.ms))
                            Words(nowPlaying)
                        }
                    }
                }
            }
        }
    }
}

/** Where the mini player stands, as the settings name it: at the top centre (the desktop's place), at the bottom
 * centre, in a bottom corner, or not at all. Chosen in the settings. */
enum class MiniPlace(val label: Int) {
    Top(R.string.Tv_MiniTop),
    Bottom(R.string.Tv_MiniBottom),
    BottomLeft(R.string.Tv_MiniBottomLeft),
    BottomRight(R.string.Tv_MiniBottomRight),
    Off(R.string.Viz_SettingOff);

    /** Where it is aligned in the screen; Off is never drawn. */
    val alignment: Alignment
        get() = when (this) {
            Top, Off -> Alignment.TopCenter
            Bottom -> Alignment.BottomCenter
            BottomLeft -> Alignment.BottomStart
            BottomRight -> Alignment.BottomEnd
        }

    val atTop: Boolean get() = this == Top || this == Off
}

/** The three lines (19 SemiBold, 13.5 at 0.70 and 0.60, in `mts`), the time left at the end of the album
 * line, and the seek bar 10 below them. A station has no time and no bar. */
@Composable
private fun Words(nowPlaying: NowPlaying) {
    val station = nowPlaying.station
    val title = station?.let { (it.said as? com.rpm.player.core.radio.Icy.Said.Song)?.title ?: it.name } ?: nowPlaying.title
    val artist = station?.let { (it.said as? com.rpm.player.core.radio.Icy.Said.Song)?.artist.orEmpty() } ?: nowPlaying.artist
    val album = station?.name ?: nowPlaying.album
    val position by rememberPosition(nowPlaying)
    Column(Modifier.width(345.ms), verticalArrangement = Arrangement.spacedBy(2.px)) {
        Text(title, style = ui(19.mts, FontWeight.SemiBold), color = Ink.Words, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A station leaves out a line it has nothing for (no artist, or its own name again when nothing is said to
        // be playing), so the lines it has stay centred. A track keeps all three.
        if (station == null || artist.isNotEmpty()) {
            Text(artist.ifEmpty { " " }, style = ui(13.5.mts), color = Ink.Artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (station == null || album != title) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(album.ifEmpty { " " }, style = ui(13.5.mts), color = Ink.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (station == null && nowPlaying.durationMs > 0) {
                Text("-" + remainingOf(nowPlaying.durationMs - position), style = ui(12.mts), color = Ink.Dim, modifier = Modifier.padding(start = 8.ms))
            }
        }
        if (station == null) {
            val fraction = if (nowPlaying.durationMs > 0) (position.toFloat() / nowPlaying.durationMs).coerceIn(0f, 1f) else 0f
            Canvas(Modifier.padding(top = 10.px).fillMaxWidth().height(14.ms)) {
                val line = 3.ms.toPx()
                val y = size.height / 2
                val x = size.width * fraction
                drawRoundRect(Color(0x8BFFFFFF), Offset(0f, y - line / 2), Size(size.width, line), CornerRadius(line / 2))
                drawRoundRect(Color.White, Offset(0f, y - line / 2), Size(x, line), CornerRadius(line / 2))
                drawRoundRect(Color.White, Offset((x - line / 2).coerceIn(0f, size.width - line), 0f), Size(line, size.height), CornerRadius(line / 2))
            }
        }
    }
}

/** The mini player is drawn 10 % larger than the desktop's. Every length in it is in `ms` (1.1·s) and every
 * text size is in `mts` (1.1·ts), so the card and its contents grow together. */
private const val MINI = 1.1f
private val Number.ms: androidx.compose.ui.unit.Dp get() = (toFloat() * MINI).s
private val Number.mts: androidx.compose.ui.unit.TextUnit get() = (toFloat() * MINI).ts

private const val SLIDE_MS = 300

/** For `adb logcat -s rpm.mini`: what the mini player follows and what covers it, and each change of whether it shows. */
private const val MINI_LOG = "rpm.mini"
/** `1 - (1 - t)^3`, the desktop's PaneGlide ease. */
private val CubicOut = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)
