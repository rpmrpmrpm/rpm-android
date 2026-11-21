package com.rpm.player.phone.player

import com.rpm.player.phone.coach.CoachTargets
import com.rpm.player.phone.coach.coachTarget
import androidx.compose.ui.unit.Dp
import com.rpm.player.phone.PhoneSettings
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.rpm.player.core.R
import com.rpm.player.core.cast.CastState
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.lyrics.LyricSheet
import com.rpm.player.core.lyrics.MeasuredLine
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.SleepClock
import com.rpm.player.core.playback.rememberPosition
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.StationSign
import com.rpm.player.phone.design.Glyph
import com.rpm.player.phone.design.GlassKey
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.rememberHaptics
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.chromeGlass
import com.rpm.player.phone.design.display
import com.rpm.player.phone.design.icon
import com.rpm.player.phone.design.iconOutline
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.library.SleeveSquare
import com.rpm.player.phone.library.SongMenu
import com.rpm.player.phone.library.SongMenuLayer
import com.rpm.player.phone.library.SongMenuShown
import com.rpm.player.phone.radio.StationFace
import com.rpm.player.phone.ui.LocalDiscStandIn
import com.rpm.player.phone.ui.RecordDisc
import com.rpm.player.phone.ui.ScannedDisc
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The stiffness of a spring with no bounce and a response of 0.35 s: SwiftUI's `spring(duration: 0.35)`. */
private const val SHEET_STIFFNESS = 322f

/** `spring(duration: 0.3)`: the sheet going home when it was not pulled far enough, and the flicked sleeve coming back. */
private const val HOME_STIFFNESS = 439f

/** The size of the cover, and the scale it shrinks to while the record is paused. */
private val SLEEVE = 260.dp
private const val PAUSED_SCALE = 0.9f

/** The room one spectrum bar needs, with its gap; at less the run looks like a texture, not bars. */
private val SPECTRUM_PITCH = 7.dp

/** How far a flick across the sleeve has to go to change the track, and how far a finger moves before it is one. */
private val FLICK = 80.dp

/** How long the play key must be held before letting go stops instead of pausing: the system's long press. */
private const val HOLD_FOR_STOP_MS = 500L

/** How far a finger may wander and still be on the play key. */
private val HOLD_SLIP = 40.dp

/** How far down the sheet has to come to be let go of, and how far a throw has to be heading. `ScreenPull.far`, `thrown`. */
private val FAR = 120.dp
private val THROWN = 260.dp

/** The first stretch of a pull, over which everything that shows "on its way down" grows. `ScreenPull.ramp`. */
private val RAMP = 24.dp

/** The glyphs of the row under the transport: the lyrics (a speech bubble) and the queue (a list). */
private const val LYRICS_GLYPH = ""
private const val QUEUE_GLYPH = ""

/**
 * The player, raised over everything, the tabs and the mini player included: the iPhone's `NowPlayingSheet`
 * (RPMUI/NowPlayingSheet.swift). From the top: the grip with the speaker key on it, the record (or the lyrics in its
 * place), the title and artist with the heart beside them, the seek bar, the transport with play in the centre, and a
 * glass row of lyrics, the queue and a menu.
 *
 * The sheet moves with the finger on the grip and goes down when let go far enough, or when the grip is tapped. One
 * value, `offset`, moves it both ways, as the iPhone's `ScreenPull`. Its top corners are square against the top of the
 * screen and round as soon as it moves away, and stay round while it leaves.
 *
 * **Behind the cover is the room** ([StageGround]), lit by the record that is playing, in the cover's colours, and the
 * spectrum runs out of both sides of the cover ([spectrumRun]), as on iPhone.
 *
 * Fast-changing state is read low: the seek bar and the clocks read the position in their own composable, and the
 * lyrics recompose once a line.
 *
 * @param lyrics the words of what is playing, or [LyricSheet.EMPTY], which greys the lyrics key out.
 * @param lookingUpLyrics whether the lookup still runs: the one state where the key is neither lit nor greyed.
 * @param onNeedLyrics asks for the lyrics, when the sheet is shown and when the record changes. **Nothing is asked
 *   while the sheet is hidden**, so ordinary listening sends no record name to any service.
 * @param karaoke whether the sung line lights letter by letter: the setting, for a supporter ([karaokeOn]).
 * @param measured the words of the playing song as the cloud measured them, or none.
 * @param onNeedTiming reads the measured words of what is playing, and asks for them to be measured where none fit. It
 *   is called while the sheet is shown and karaoke is on, and again when the record or its lyrics change.
 * @param songMenu the long-press menu on the up next drawer's rows, where play next moves the row up instead
 *   ([QueueOverlay]), and add to playlist puts the player away first; null leaves the rows without one.
 * @param onMark marks the song playing or clears the mark. Null hides the heart: a station is not a song.
 * @param onFile opens the add-to-playlist sheet for the song playing; null leaves it out of the menu.
 * @param onAlbum opens the playing record's page; null where the library has no page for it.
 * @param onArtist opens the playing artist's page; null where the library has no page for them.
 * @param speakers the speaker picker's model; null hides the speaker key.
 * @param leaving set when the listening ended under the sheet: it goes down by itself.
 * @param onClosed called once the sheet is down, so the caller can remove it.
 * @param bars whether the spectrum bars beside the cover move with the music: the setting ([PhoneSettings.playerBars]).
 */
@Composable
fun NowPlayingSheet(
    now: NowPlaying,
    player: Player,
    images: ImageLoader,
    queue: QueueRead,
    sittingFrom: Int,
    lyrics: LyricSheet,
    lookingUpLyrics: Boolean,
    onNeedLyrics: suspend () -> Unit,
    karaoke: Boolean,
    measured: List<MeasuredLine>,
    onNeedTiming: suspend () -> Unit,
    songMenu: SongMenu?,
    marked: Boolean,
    onMark: ((Boolean) -> Unit)?,
    onFile: (() -> Unit)?,
    onAlbum: (() -> Unit)?,
    onArtist: (() -> Unit)?,
    speakers: Speakers?,
    sleep: SleepClock,
    leaving: Boolean,
    onClosed: () -> Unit,
    bars: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val backdrop = rememberBackdrop()
    // Whether the person asked for the lyrics in place of the cover. What is shown is [lyricsUp], which also asks
    // whether this record has lyrics.
    var showingLyrics by remember { mutableStateOf(false) }
    val lyricsUp = LyricsLookup.shows(showingLyrics, lyrics, lookingUpLyrics)
    val haptics = rememberHaptics()
    var showingSpeakers by remember { mutableStateOf(false) }
    var showingSleepTimer by remember { mutableStateOf(false) }
    var showingQueue by remember { mutableStateOf(false) }
    var menuAt by remember { mutableStateOf<Rect?>(null) }
    val timer by sleep.timer.collectAsStateWithLifecycle()
    val castState = speakers?.casting?.state?.collectAsStateWithLifecycle()?.value
    val playingOn = (castState as? CastState.On)?.device?.name
    val onStation = now.station != null

    // Asked when the sheet is shown and again when the record changes, and only while it is shown.
    LaunchedEffect(now.mediaId) { onNeedLyrics() }
    // The measured words, once the lyrics are in. Keyed on the sheet too, because the sheet arrives after the record
    // changes, and the request names its lines.
    LaunchedEffect(now.mediaId, karaoke, lyrics) { if (karaoke) onNeedTiming() }
    // The sleep timer's drawer is shown only while a timer runs, so it closes when the timer ends.
    LaunchedEffect(timer.isOn) { if (!timer.isOn) showingSleepTimer = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = with(density) { maxHeight.toPx() }
        val offset = remember { Animatable(height) }
        // The room behind the sheet and the spectrum beside the cover. While the sheet is all the way up it covers the
        // screen, and the rooms of the page under it stop.
        val stage = remember { Stage() }
        stage.top = { offset.value }
        stage.height = height
        val fullyUp by remember { derivedStateOf { offset.value == 0f } }
        var up by remember { mutableStateOf(true) }
        val sheetSpring = spring<Float>(dampingRatio = 1f, stiffness = SHEET_STIFFNESS)
        LaunchedEffect(Unit) { offset.animateTo(0f, sheetSpring) }

        fun putAway(then: () -> Unit = {}) {
            if (!up) return
            up = false
            scope.launch {
                offset.animateTo(height, sheetSpring)
                onClosed()
                then()
            }
        }
        LaunchedEffect(leaving) { if (leaving) putAway() }
        BackHandler { putAway() }

        // The song menu on the queue's rows. It is drawn over the sheet, in its own layer, and its add to playlist puts
        // the player away first: the picker is drawn under it, as for the playlist item in the player's own menu.
        val queueMenus = remember { SongMenuShown() }
        val filing = rememberUpdatedState<(CloudTrack) -> Unit> { track -> putAway { songMenu?.onFile?.invoke(track) } }
        val queueMenu = remember(songMenu) { songMenu?.drawnIn(queueMenus) { filing.value(it) } }

        val ramp = with(density) { RAMP.toPx() }
        // How far into the putting-down the sheet is: nothing at home, all of it a ramp's depth in and while it leaves.
        fun putDown(): Float = if (up) (offset.value / ramp).coerceIn(0f, 1f) else 1f
        val corner = with(density) { 44.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .graphicsLayer {
                    clip = true
                    val rounding = putDown() * corner
                    shape = RoundedCornerShape(topStart = rounding, topEnd = rounding)
                }
                // Every touch on the player stops at the player: one between the controls must not reach the page under it.
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
        ) {
            // The ground is what the glass on the sheet blurs: the speaker picker, the menu, the row of keys.
            Box(Modifier.fillMaxSize().backdropSource(backdrop)) { StageGround(now, images, stage, covers = fullyUp, Modifier.fillMaxSize()) }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Grip(
                    onDrag = { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) } },
                    onLetGo = { velocity ->
                        val far = with(density) { FAR.toPx() }
                        val thrown = with(density) { THROWN.toPx() }
                        // Where the hand was heading: the iPhone projects a throw half a second on.
                        if (offset.value > far || offset.value + velocity * 0.5f > thrown) putAway()
                        else scope.launch { offset.animateTo(0f, spring(dampingRatio = 1f, stiffness = HOME_STIFFNESS)) }
                    },
                    onTap = { putAway() },
                ) {
                    // Over the grip, not in a row of its own. Hidden while the sheet is being put away.
                    if (speakers != null) {
                        val label = playingOn?.let { stringResource(R.string.Rpm_Cast_Playing, it) } ?: stringResource(R.string.Rpm_Cast_PlayOn)
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 12.dp)
                                .coachTarget(CoachTargets.PLAYER_SPEAKERS)
                                .graphicsLayer { alpha = 1f - putDown() },
                        ) {
                            GlassKey(backdrop, onClick = { if (putDown() == 0f) showingSpeakers = true }, Modifier.semantics { contentDescription = label }) {
                                CastMark(live = playingOn != null, color = if (playingOn == null) Ink.white(0xB8) else Color.White, edge = 17.dp)
                            }
                        }
                    }
                }
                // The lyrics take the place of the cover. The ground stays: its colours come from the record, and the
                // lyrics are read against it.
                Crossfade(lyricsUp, Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(250, easing = FastOutSlowInEasing), label = "lyrics") { up ->
                    if (up) {
                        LyricView(lyrics, now, karaoke, measured)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Sleeve(now, player, images, stage, SLEEVE, bars) }
                    }
                }
                Column(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The title, the artist and the heart are about the record. While the lyrics show, the lyrics are the
                    // text, and two sets of words over one ground would make the reader choose.
                    val wordsAlpha by animateFloatAsState(if (lyricsUp) 0f else 1f, tween(250), label = "words")
                    Box(Modifier.graphicsLayer { alpha = wordsAlpha }) {
                        Words(
                            now,
                            enabled = !lyricsUp,
                            onAlbum = onAlbum?.let { open -> { putAway(open) } },
                            marked = marked,
                            onMark = onMark,
                        )
                    }
                    Needle(now, player, playingOn)
                    Transport(now, player)
                    Below(
                        backdrop,
                        lyricsLit = lyricsUp,
                        lyricsShown = lyrics.isSynced || lookingUpLyrics,
                        lyricsEnabled = lyrics.isSynced,
                        onLyrics = { haptics.pick(); showingLyrics = !showingLyrics },
                        queueEnabled = player.mediaItemCount > 0 && !onStation,
                        onQueue = { haptics.pick(); showingQueue = true },
                        onMenu = { menuAt = it },
                    )
                }
            }
            if (showingQueue) {
                QueueOverlay(queue, sittingFrom, player, backdrop, queueMenu, onClose = { showingQueue = false })
            }
            SongMenuLayer(queueMenus, backdrop)
            menuAt?.let { anchor ->
                val close = { menuAt = null }
                val items = buildList {
                    onFile?.let { add(MenuItem(stringResource(R.string.Playlists_AddToPlaylist), MenuGlyph.file) { close(); putAway(it) }) }
                    onAlbum?.let { add(MenuItem(stringResource(R.string.AlbumMenu_AlbumPage), MenuGlyph.album) { close(); putAway(it) }) }
                    onArtist?.let { add(MenuItem(stringResource(R.string.Rpm_NowPlaying_ArtistPage), MenuGlyph.artist) { close(); putAway(it) }) }
                    // While a timer runs, the item opens its drawer instead of the options.
                    if (timer.isOn) add(MenuItem(stringResource(R.string.Rpm_Sleep_Title), MenuGlyph.sleep) { close(); showingSleepTimer = true })
                }
                PlayerMenu(
                    backdrop,
                    anchor,
                    items,
                    sleepOptions = if (timer.isOn) null else sleepChoices(onStation) { choice ->
                        close()
                        // A drawer asked for an earlier timer that has since ended is not shown for this one.
                        showingSleepTimer = false
                        sleep.set(choice)
                    },
                    onClose = close,
                )
            }
            if (showingSpeakers && speakers != null) {
                SpeakerSheet(speakers, backdrop, onClose = { showingSpeakers = false })
            }
            if (showingSleepTimer && timer.isOn) {
                SleepTimerSheet(sleep, onStation = onStation, hasTrack = !onStation, backdrop, onClose = { showingSleepTimer = false })
            }
        }
    }
}

/**
 * The strip at the top that carries the sheet. Pull it down to put the player away, or tap it. The whole strip takes
 * the touch, not only the bar drawn in it. [over] is drawn over it: the speaker key.
 */
@Composable
private fun Grip(onDrag: (Float) -> Unit, onLetGo: (Float) -> Unit, onTap: () -> Unit, over: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .draggable(rememberDraggableState(onDrag), Orientation.Vertical, onDragStopped = { onLetGo(it) })
            .then(Modifier.coachTarget(CoachTargets.PLAYER_GRIP))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // The grabber, 36 by 5 dp, in iOS's tertiary label grey. Drawn here because this is not a system sheet.
        Box(Modifier.size(36.dp, 5.dp).clip(RoundedCornerShape(percent = 50)).background(Color(0x4DEBEBF5)))
        over()
    }
}

/**
 * The cover: square and centred, full size while the record plays and a tenth smaller while it is paused, so a stopped
 * record is a smaller thing and the ground round it shows more. Flick it across for the next or the previous track. With
 * no cover, the record itself, not turning: the plain CD, or the vinyl when that is chosen in the profile
 * ([ScannedDisc.standIn]); a station shows its logo on a card.
 */
@Composable
private fun Sleeve(now: NowPlaying, player: Player, images: ImageLoader, stage: Stage, edge: Dp, bars: Boolean) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scale by animateFloatAsState(if (now.playing) 1f else PAUSED_SCALE, spring(dampingRatio = 1f, stiffness = SHEET_STIFFNESS), label = "sleeve")
    val flick = remember { Animatable(0f) }
    val onStation = now.station != null
    val context = LocalContext.current
    val standIn = LocalDiscStandIn.current

    // Where the cover's box sits across the window, for the spectrum, which runs out to the window's edges.
    val across = remember { FloatArray(2) }
    Box(
        Modifier
            .size(edge)
            .coachTarget(CoachTargets.PLAYER_COVER)
            .onGloballyPositioned {
                val at = it.positionInRoot()
                across[0] = at.x
                across[1] = it.findRootCoordinates().size.width.toFloat()
                stage.footAt(at.y + it.size.height)
            }
            // Under the cover's own layer, so it is measured against the cover as drawn (paused smaller, pushed aside by a
            // flick) without being scaled or moved with it (`SpectrumRunView` on iPhone).
            .drawBehind {
                if (!bars) return@drawBehind
                val room = stage.room ?: return@drawBehind
                val side = size.width * scale
                val middle = across[0] + size.width / 2 + flick.value
                translate(left = -across[0]) {
                    spectrumRun(room, across[1], middle - side / 2, middle + side / 2, size.height / 2, side, SPECTRUM_PITCH.toPx())
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = flick.value
            }
            .pointerInput(onStation) {
                // A station has no next or previous track: it has no queue.
                if (onStation) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val far = abs(flick.value) > with(density) { FLICK.toPx() }
                        val left = flick.value < 0
                        scope.launch { flick.animateTo(0f, spring(dampingRatio = 1f, stiffness = HOME_STIFFNESS)) }
                        if (far) if (left) player.seekToNext() else player.seekToPrevious()
                    },
                    onDragCancel = { scope.launch { flick.animateTo(0f) } },
                ) { _, dx -> scope.launch { flick.snapTo(flick.value + dx) } }
            },
        contentAlignment = Alignment.Center,
    ) {
        val station = now.station
        when {
            station != null -> StationCard(station, images, edge)
            now.cover == null -> RecordDisc(edge, spinning = false, disc = remember(standIn) { ScannedDisc.standIn(context, standIn) })
            else -> SleeveSquare(now.cover, edge, images, radius = 14.dp, glyphSize = 52f)
        }
    }
}

/**
 * A station's own logo on a card, inset so it sits on the card and does not fill it, as the desktop draws it. Where the
 * logo's address no longer works, the station's call sign, the letters the radio dial is printed with: an empty field of
 * 260 points would look like a hole.
 */
@Composable
private fun StationCard(station: com.rpm.player.core.playback.StationPlaying, images: ImageLoader, edge: Dp) {
    val shape = RoundedCornerShape(14.dp)
    // The cover of the song the station plays, over its logo, as on the desktop.
    val cover = com.rpm.player.phone.design.rememberSongCover(station)
    Box(Modifier.size(edge).clip(shape)) {
    StationFace(
        station.logo,
        images,
        edge = edge,
        inset = edge * 0.16f,
        modifier = Modifier.size(edge).clip(shape).background(Color.Black.copy(alpha = 0x22 / 255f)).border(1.dp, Ink.cardBorder, shape),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicText(Glyph.radio, style = icon(48f).copy(color = Ink.white(0x3A)))
            BasicText(
                StationSign.of(station.name),
                style = com.rpm.player.phone.design.system(34f, FontWeight.SemiBold).copy(color = Ink.cyan.copy(alpha = 0.85f), letterSpacing = 4.sp),
            )
        }
    }
    if (cover != null) {
        coil3.compose.AsyncImage(
            model = com.rpm.player.phone.design.rememberPicture(cover, edge),
            contentDescription = null,
            imageLoader = images,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
    }
}

/** The title a station's announcement gives, else the station's name; the track's title for a track. */
internal fun NowPlaying.shownTitle(nothing: String): String {
    val station = station ?: return title.ifEmpty { nothing }
    return (station.said as? Icy.Said.Song)?.title ?: station.name
}

/**
 * The artist a station announced; with a song but no artist, nothing. Only while it announces no song, its country
 * and quality, never its name: that is the title then. The track's artist for a track.
 */
internal fun NowPlaying.shownArtist(): String {
    val station = station ?: return artist
    val sung = station.said as? Icy.Said.Song ?: return station.detail.orEmpty()
    return sung.artist.orEmpty()
}

/**
 * The title over the artist, with the heart beside them. A tap on either line opens the record's page. Both lines walk
 * sideways when they do not fit ([Marquee]). The heart is about the record, not about how it plays, and it is filled or
 * hollow and never changes colour.
 */
@Composable
private fun Words(now: NowPlaying, enabled: Boolean, onAlbum: (() -> Unit)?, marked: Boolean, onMark: ((Boolean) -> Unit)?) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val hint = if (onAlbum != null) stringResource(R.string.AlbumMenu_AlbumPage) else ""
        Column(
            Modifier
                .weight(1f)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled && onAlbum != null) { onAlbum?.invoke() }
                .semantics { if (hint.isNotEmpty()) contentDescription = hint },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Marquee(now.shownTitle(stringResource(R.string.Rpm_NowPlaying_Nothing)), com.rpm.player.phone.design.displayBold(22f).copy(color = Color.White), 28.dp)
            Marquee(now.shownArtist(), display(15f).copy(color = Ink.white(0xB3)), 20.dp)
        }
        // A station is not a song, so it cannot be marked. The key is hidden, not greyed: a heart that cannot be
        // pressed raises the question of what it would do.
        Box(
            Modifier
                .offset(x = 12.dp)
                .size(40.dp)
                .then(if (onMark != null) Modifier.coachTarget(CoachTargets.PLAYER_HEART) else Modifier)
                .alpha(if (onMark == null) 0f else 1f)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled && onMark != null, role = Role.Button) { onMark?.invoke(!marked) },
            contentAlignment = Alignment.Center,
        ) {
            BasicText(Glyph.heart, style = (if (marked) icon(15f) else iconOutline(15f)).copy(color = Ink.white(0xE6)))
        }
    }
}

/**
 * The seek bar, its clocks, and the status over its right end: the iPhone's `PlayerNeedle`. **It reads the position here
 * and nowhere else**, so a tick redraws this strip and not the sheet. While the bar is dragged it follows the finger,
 * and the player seeks once, where the finger lifts. A station has no bar, and says "On air".
 *
 * The status is what went wrong, else what is being waited for, else where the sound is going. It sits outside the
 * layout, over the gap above the bar, so buffering never moves the keys under a thumb.
 */
@Composable
private fun Needle(now: NowPlaying, player: Player, playingOn: String?) {
    val position by rememberPosition(now)
    var scrubbing by remember { mutableStateOf<Long?>(null) }
    val clocks = display(11f).copy(color = Ink.white(0x88))
    // What went wrong comes first, and the player's own reason before its error: offline is the one the person can fix.
    val trouble = now.failure.said
    val status = when {
        trouble != null -> stringResource(trouble)
        now.failed -> stringResource(R.string.Rpm_Playback_Failed)
        now.station?.reconnecting == true -> stringResource(R.string.Radio_Reconnecting)
        now.isBusy -> stringResource(R.string.Rpm_Playback_Buffering)
        playingOn != null -> stringResource(R.string.Rpm_Cast_Playing, playingOn)
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            if (now.station != null) {
                Spacer(Modifier.padding(top = 6.dp).height(20.dp))
            } else {
                BoxWithConstraints(
                    Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .height(20.dp)
                        .pointerInput(now.durationMs) {
                            if (now.durationMs <= 0) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun at(x: Float) = ((x / size.width).coerceIn(0f, 1f) * now.durationMs).toLong()
                                scrubbing = at(down.position.x)
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    scrubbing = at(change.position.x)
                                    change.consume()
                                } while (true)
                                scrubbing?.let(player::seekTo)
                                scrubbing = null
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val shown = scrubbing ?: position
                    val through = if (now.durationMs > 0) (shown.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
                    val played = maxWidth * through
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(percent = 50)).background(Ink.white(0x2E)))
                    Box(Modifier.width(played).height(3.dp).clip(RoundedCornerShape(percent = 50)).background(Color.White))
                    Box(Modifier.offset(x = (played - 1.dp).coerceAtLeast(0.dp)).size(2.dp, 14.dp).clip(RoundedCornerShape(percent = 50)).background(Color.White))
                }
            }
            if (status != null) {
                BasicText(
                    status,
                    Modifier.align(Alignment.TopEnd).offset(y = (-8).dp).widthIn(max = 240.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = clocks.copy(textAlign = TextAlign.End),
                )
            }
        }
        if (now.station != null) {
            BasicText(stringResource(R.string.Radio_OnAir), Modifier.fillMaxWidth(), style = clocks.copy(textAlign = TextAlign.Center, letterSpacing = 1.sp))
        } else {
            val shown = scrubbing ?: position
            Row(Modifier.fillMaxWidth()) {
                BasicText(clock(shown), style = clocks)
                Spacer(Modifier.weight(1f))
                BasicText(if (now.durationMs > 0) "-" + clock(now.durationMs - shown) else "", style = clocks)
            }
        }
    }
}

private fun clock(ms: Long): String {
    val whole = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(whole / 60, whole % 60)
}

/**
 * Play in the exact centre, where a thumb finds it without looking, previous and next on either side. Nothing else is
 * in the row: stop is a hold of the play key. **The glyph shows what was asked for**, not what is coming out, so a pause
 * just pressed does not turn back into play while the track opens; a ring shows once a wait is long ([BusyKey]).
 */
@Composable
private fun Transport(now: NowPlaying, player: Player) {
    val onStation = now.station != null
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key(Glyph.backward, 22f, stringResource(R.string.Transport_Previous), enabled = !onStation) { player.seekToPrevious() }
        BusyKey(now.isBusy, 24.dp) { PlayKey(now, player) }
        Key(Glyph.forward, 22f, stringResource(R.string.Transport_Next), enabled = !onStation && player.hasNextMediaItem()) { player.seekToNext() }
    }
}

@Composable
private fun Key(glyph: String, size: Float, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(size).copy(color = Ink.white(0xB8)))
    }
}

/** A press on the play key, from the touch down to the lift. The lift reads this and nothing else. */
private enum class Press { Brief, Held, Away }

/**
 * Tap to pause or play, hold to stop. One gesture, not a click with a separate long press: a click also acts on the
 * lift, so a hold that turned the key into stop would toggle play as well.
 */
@Composable
private fun PlayKey(now: NowPlaying, player: Player) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var press by remember { mutableStateOf<Press?>(null) }
    val haptics = rememberHaptics()
    val glyph = when {
        press == Press.Held -> Glyph.stop
        now.playing -> Glyph.pause
        else -> Glyph.play
    }
    val label = stringResource(R.string.Transport_PlayPause)
    Box(
        Modifier
            .size(68.dp)
            .semantics { contentDescription = label }
            .pointerInput(player) {
                val slip = with(density) { HOLD_SLIP.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    press = Press.Brief
                    val watch: Job = scope.launch {
                        delay(HOLD_FOR_STOP_MS)
                        // The hand feels the key become stop: the finger is over the glyph that changed.
                        if (press == Press.Brief) {
                            press = Press.Held
                            haptics.commit()
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val moved = change.position - down.position
                        if (hypot(moved.x, moved.y) > slip) {
                            watch.cancel()
                            press = Press.Away
                        }
                    }
                    watch.cancel()
                    when (press) {
                        Press.Brief -> toggle(player)
                        Press.Held -> stop(player)
                        Press.Away, null -> Unit
                    }
                    press = null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(26f).copy(color = Color.White))
    }
}

/**
 * The row under the transport: the lyrics, the queue and the menu, on one glass capsule, the same group as the
 * library's search and sort keys. The speaker key is on the grip and stop is a hold of play, so these three are all.
 *
 * The lyrics key is greyed when the record has no lyrics, and lit while the lookup runs: a key that greys as a record
 * starts and lights a second later would look unreliable.
 */
@Composable
private fun Below(
    backdrop: com.rpm.player.phone.design.Backdrop,
    lyricsLit: Boolean,
    lyricsShown: Boolean,
    lyricsEnabled: Boolean,
    onLyrics: () -> Unit,
    queueEnabled: Boolean,
    onQueue: () -> Unit,
    onMenu: (Rect) -> Unit,
) {
    var menuKey by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.chromeGlass(backdrop, RoundedCornerShape(percent = 50))) {
            Box(Modifier.coachTarget(CoachTargets.PLAYER_LYRICS)) {
                Small(LYRICS_GLYPH, stringResource(R.string.Details_Lyrics), if (lyricsLit) Color.White else Ink.white(0x99), shown = lyricsShown, enabled = lyricsEnabled, onClick = onLyrics)
            }
            Box(Modifier.coachTarget(CoachTargets.PLAYER_QUEUE)) {
                Small(QUEUE_GLYPH, stringResource(R.string.Viz_Queue), Ink.white(0x99), shown = queueEnabled, enabled = queueEnabled, onClick = onQueue)
            }
            Box(Modifier.coachTarget(CoachTargets.PLAYER_MENU).onGloballyPositioned { menuKey = it.boundsInRoot() }) {
                Small(Glyph.more, "", Ink.white(0x99), shown = true, enabled = true) { onMenu(menuKey) }
            }
        }
    }
}

@Composable
private fun Small(glyph: String, label: String, ink: Color, shown: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (shown) 1f else 0.35f)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (label.isNotEmpty()) contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = icon(16f).copy(color = ink))
    }
}

/** Play or pause. From a stopped player, or at the end of a queue, playback starts from the start, as a media key does. */
@OptIn(UnstableApi::class)
private fun toggle(player: Player) {
    Util.handlePlayPauseButtonAction(player)
}

/**
 * Puts the record away: the queue is emptied, so nothing is left playing, the mini player goes, and the player goes with
 * it. The playback service then forgets the saved queue, as the iPhone's stop does. The sleep timer ends with it.
 */
private fun stop(player: Player) {
    player.pause()
    player.clearMediaItems()
}
