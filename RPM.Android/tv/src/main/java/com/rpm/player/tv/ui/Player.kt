package com.rpm.player.tv.ui

import com.rpm.player.core.playback.NowPlaying
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.max
import androidx.media3.common.Player
import coil3.ImageLoader
import com.rpm.player.core.R
import com.rpm.player.core.cast.CastNotice
import com.rpm.player.core.cast.CastState
import com.rpm.player.core.cast.Casting
import com.rpm.player.core.lyrics.LrcLib
import com.rpm.player.core.lyrics.LyricSheet
import com.rpm.player.core.playback.KeyRules
import com.rpm.player.core.playback.PlayerAction
import com.rpm.player.core.playback.perform
import com.rpm.player.core.room.PLAYER_BRIGHTNESS
import com.rpm.player.core.room.RoomPalette
import com.rpm.player.core.room.RoomStills
import com.rpm.player.core.room.RoomTarget
import com.rpm.player.core.room.VinylHue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The player screen, laid out as the desktop's Pegasus player (PegasusChrome.xaml). The remote rules are:
 *
 * - Controls hidden: OK, Up or Down shows the controls with the play key focused ([KeyRules.raisesControls]), and
 *   nothing else: OK does not pause, so a press to see the keys never stops the music. The rest of that press (its
 *   repeats and its release) is kept from the play key that now has the focus, or the release would press it. Left
 *   and Right seek 10 seconds ([KeyRules.forHiddenControls]). The remote's play/pause key works on every screen.
 * - Controls shown: the arrows move the focus along the row and OK presses the focused key. Up from the row
 *   reaches the seek bar of a track whose length is known. There, Left and Right move the playhead
 *   ([SeekKeys]) and Down goes back to the play key.
 * - The controls hide after [HIDE_AFTER_MS] without a key. Each key press restarts that wait, so a wait that
 *   began before the latest key can never hide the controls. They fade out over 420 ms and come back over
 *   140 ms. A track that pauses brings them up with the play key focused, and they stay up while it is paused.
 * - Back hides the controls first, then leaves the player.
 * - The queue key opens the queue drawer ([QueueDrawer]). It takes every key except the media keys until
 *   it closes, and the controls stay up while it is open.
 *
 * The view is the one in the settings ([PlayerSettings.defaultView]). The layout key in the row steps through
 * the two layouts (Full, Compact) and keeps the choice, and the new layout's name shows at the top for a moment.
 * A station has no seek bar and only the play key.
 *
 * The lyric key's choice is a setting ([PlayerSettings.lyricsOn]), so the lyrics are still on after Back and a
 * return. Between tracks the layout holds still: it follows the last sheet that was read, not the empty one of a
 * track still being looked up, and it moves only once the next track's sheet is known and says otherwise.
 *
 * The cast key, at the right of the row, opens the cast menu ([castLines]) over the player. The menu lists This
 * TV and the Cast devices on the network, with the one in use ticked. While a device is in use the key is lit
 * and the line at the top says where the music is playing. A cast that could not start, or that ended by itself,
 * is shown in the same place for a few seconds. Devices are looked for while the player is on screen.
 */
@Composable
fun PlayerScreen(
    controller: Player?,
    nowPlaying: NowPlaying?,
    images: ImageLoader,
    room: RoomTarget,
    /** Draws the still of the room that the queue drawer's glass frosts ([DrawerRoom]). */
    roomStills: RoomStills,
    lrcLib: LrcLib,
    /** The word timing desktops measured, read per track for karaoke. */
    measuredLyrics: com.rpm.player.core.lyrics.MeasuredLyrics,
    backdrop: Backdrop,
    defaultView: PegasusView,
    /** Moves the setting to the next layout; [defaultView] then comes back as the new one. */
    onNextView: () -> Unit,
    /** Whether the sung line lights word by word ([PlayerSettings.karaoke]). */
    karaoke: Boolean,
    /** Whether the person wants the lyrics shown, where the track has synced ones ([PlayerSettings.lyricsOn]). It is
     * kept outside the player, which forgets its own state when Back leaves it. */
    lyricsWanted: Boolean,
    /** The lyric key: switches [lyricsWanted]. */
    onLyricsKey: () -> Unit,
    casting: Casting,
    /** Whether the track playing is one of the person's favourites, or null where they cannot set it (a station,
     * a track of a collection someone shares). */
    favourite: Boolean?,
    /** Sets the favourite mark on the track playing (true) or takes it off (false). */
    onFavourite: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var controlsShown by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val castState by casting.state.collectAsState()
    val castFound by casting.discovery.found.collectAsState()
    val castMenu = rememberRowMenu()
    val castKey = remember { FocusRequester() }
    // Devices are looked for while the player is on screen, so the menu already has them when it opens.
    DisposableEffect(casting) {
        casting.discovery.look()
        onDispose { casting.discovery.stopLooking() }
    }
    // An open cast menu follows what is found and what is in use.
    LaunchedEffect(castState, castFound, castMenu.isOpen) {
        if (castMenu.isOpen) castMenu.refresh(castLines(context, casting, castState, castFound))
    }
    val castNotice by casting.notice.collectAsState()
    var noticeShown by remember { mutableStateOf<CastNotice?>(null) }
    LaunchedEffect(castNotice) {
        val notice = castNotice ?: return@LaunchedEffect
        // A notice from before this screen was opened is too old to show.
        if (android.os.SystemClock.uptimeMillis() - notice.serial > CAST_NOTICE_MS) return@LaunchedEffect
        noticeShown = notice
        delay(CAST_NOTICE_MS)
        if (noticeShown == notice) noticeShown = null
    }
    var sheet by remember { mutableStateOf(LyricSheet.EMPTY) }
    // Whether the last sheet read had synced lyrics, which is what the layout follows. Unlike [sheet] it is not
    // cleared as the track changes. It was, through [sheet], and every change of track slid the lyrics away (the
    // compact view's record glided back to the middle, the full view swapped back to the record) and slid them back half
    // a second later when the lookup came back. Now the layout holds while the next sheet is read, the lyric side
    // showing only the dots, and moves once that sheet is known and only if it has no synced lyrics. On opening, the
    // player assumes the lyrics are there if they are wanted, so a return to the player does not slide them in.
    var synced by remember { mutableStateOf(lyricsWanted) }
    // The layout in the settings (PlayerSettings), which the layout key steps through.
    val view = defaultView
    // The layout's name at the top for a moment after the layout key changes it; null otherwise.
    var viewNamed by remember { mutableStateOf<PegasusView?>(null) }
    // The row is built again inside the new layout, and so is the key that was pressed. The focus is handed to
    // the new layout key once it is there, or the focus would be left on nothing.
    val layoutKey = remember { FocusRequester() }
    LaunchedEffect(view) {
        if (viewNamed == null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { layoutKey.requestFocus() }
    }
    LaunchedEffect(viewNamed) {
        if (viewNamed != null) {
            delay(VIEW_NAME_MS)
            viewNamed = null
        }
    }
    val station = nowPlaying?.station != null

    // Looked up per track, keyed on its media id, so a change of track cancels the lookup for the
    // previous track.
    // What a desktop measured of this track's words; empty until read, and for a track nobody has measured.
    var measured by remember { mutableStateOf(emptyList<com.rpm.player.core.lyrics.MeasuredLine>()) }
    // How far the sheet is off the record, where the cloud worked it out for this sheet: the lines are shown that much
    // later (or earlier), and the measured lines are moved with them so they still match ([MeasuredLyrics.shifted]).
    var offset by remember { mutableStateOf(0.0) }
    // A track's sheet: the one its owner chose on a desktop, where they chose one, else LRCLIB's.
    suspend fun readSheet(mediaId: String, artist: String, title: String, album: String, seconds: Double): LyricSheet =
        com.rpm.player.core.playback.TrackRef.parse(mediaId)?.let { measuredLyrics.chosenSheet(it) }
            ?: lrcLib.sheet(artist, title, album, seconds)
    // The next track's media id and sheet, read ahead while this one plays.
    var ahead by remember { mutableStateOf<Pair<String, LyricSheet>?>(null) }
    // Where no measurement covers this sheet, the cloud is asked to time it off the record (the transcoder takes
    // about half a minute for a song). The answer is read at [MEASURE_WAIT_MS] and then every [MEASURE_EVERY_MS],
    // at most [MEASURE_READS] times, while the track is still playing.
    LaunchedEffect(nowPlaying?.mediaId, karaoke, sheet) {
        measured = emptyList()
        offset = 0.0
        if (!sheet.isSynced) return@LaunchedEffect
        val track = nowPlaying?.mediaId?.let(com.rpm.player.core.playback.TrackRef::parse) ?: return@LaunchedEffect
        // Read with karaoke off too: the offset moves the plain lines as well, and a listener who is not a supporter
        // is given the offset alone.
        // The server places the lines first and works out the offset after them, so the reads go on until both are in.
        var offsetKnown = false
        suspend fun take() {
            val answer = withContext(Dispatchers.IO) { measuredLyrics.read(track) }
            offset = com.rpm.player.core.lyrics.MeasuredLyrics.offsetFor(answer, sheet)
            offsetKnown = answer.offset != null
            if (karaoke) measured = answer.lines
        }
        fun done() = offsetKnown && com.rpm.player.core.lyrics.MeasuredLyrics.covers(measured, sheet)
        take()
        // Lines already measured are kept as they are: one measured before offsets were worked out has none, and the
        // server does not measure it again, so the reads below wait for an offset only after a measurement is asked for.
        if (!karaoke || com.rpm.player.core.lyrics.MeasuredLyrics.covers(measured, sheet)) return@LaunchedEffect
        if (!withContext(Dispatchers.IO) { measuredLyrics.ask(track, sheet) }) return@LaunchedEffect
        repeat(MEASURE_READS) { read ->
            delay(if (read == 0) MEASURE_WAIT_MS else MEASURE_EVERY_MS)
            take()
            if (done()) return@LaunchedEffect
        }
    }
    LaunchedEffect(nowPlaying?.mediaId) {
        sheet = LyricSheet.EMPTY
        // A station has no lyrics to look up.
        val playing = nowPlaying?.takeIf { it.station == null } ?: run {
            synced = false
            return@LaunchedEffect
        }
        sheet = ahead?.takeIf { it.first == playing.mediaId }?.second ?: withContext(Dispatchers.IO) {
            readSheet(playing.mediaId, playing.artist, playing.title, playing.album, playing.durationMs / 1000.0)
        }
        synced = sheet.isSynced
    }
    // The next track's sheet, read while this one plays, and with karaoke on its timing asked for now: the server
    // takes half a minute or more for a song, which used to be the first half minute of the song itself, sung as
    // plain lines. The sheet is kept for when the track comes up, which saves looking it up again.
    LaunchedEffect(nowPlaying?.mediaId, karaoke) {
        if (station) return@LaunchedEffect
        delay(AHEAD_AFTER_MS)
        val player = controller ?: return@LaunchedEffect
        val at = player.nextMediaItemIndex
        if (at == androidx.media3.common.C.INDEX_UNSET) return@LaunchedEffect
        val item = player.getMediaItemAt(at)
        val track = com.rpm.player.core.playback.TrackRef.parse(item.mediaId) ?: return@LaunchedEffect
        val about = item.mediaMetadata
        // The sheet is picked by the track's length; without one it could be another sheet than the one looked up
        // when the track plays.
        val seconds = (about.durationMs ?: 0L) / 1000.0
        if (seconds <= 0) return@LaunchedEffect
        val next = withContext(Dispatchers.IO) {
            readSheet(item.mediaId, about.artist?.toString().orEmpty(), about.title?.toString().orEmpty(), about.albumTitle?.toString().orEmpty(), seconds)
        }
        ahead = item.mediaId to next
        if (!karaoke || !next.isSynced) return@LaunchedEffect
        val known = withContext(Dispatchers.IO) { measuredLyrics.of(track) }
        if (com.rpm.player.core.lyrics.MeasuredLyrics.covers(known, next)) return@LaunchedEffect
        withContext(Dispatchers.IO) { measuredLyrics.ask(track, next) }
    }
    val lyricsKnown = synced && !station
    // The sheet as the record sings it, and the measured lines moved with it ([offset]).
    val shownSheet = remember(sheet, offset) { com.rpm.player.core.lyrics.MeasuredLyrics.shifted(sheet, offset) }
    val shownMeasured = remember(measured, offset) { com.rpm.player.core.lyrics.MeasuredLyrics.shifted(measured, offset) }
    var queueOpen by remember { mutableStateOf(false) }
    // The lyrics as the lyric key has them, and as they are drawn: the queue drawer puts them away while it is
    // open, since the drawer and the lyrics cannot both have the room beside the record, and they come back as
    // they were once it closes. The lyric key keeps showing the choice, not the moment.
    val lyricsOn = lyricsWanted && lyricsKnown
    val lyricsShown = lyricsOn && !queueOpen

    // The record's pressing and the sleeve's frame, from the cover or the station's logo. The cover reading
    // follows the current picture, so a new picture replaces the old reading. The room's colours are the
    // app's, from the same picture.
    val reading = rememberCoverReading(nowPlaying?.station?.logo ?: nowPlaying?.cover, images)
    val vinyl = remember(reading) { VinylHue.matrix(reading?.vinyl) }
    val frame = reading?.dominant?.let { Color(it.r, it.g, it.b) } ?: SLEEVE_FRAME

    // Only the spread view dims the left of the room and draws the spectrum. The room changes smoothly between the two states.
    val spread = view == PegasusView.Spread && !lyricsShown
    SideEffect {
        room.bare = if (spread) 0f else 1f
        room.brightness = PLAYER_BRIGHTNESS
    }
    DisposableEffect(room) {
        onDispose {
            room.bare = 1f
            room.push = 0f
            room.brightness = 1f
        }
    }

    var keyPresses by remember { mutableIntStateOf(0) }
    val surface = remember { FocusRequester() }
    // True from an OK that brought the controls up until that OK is released: its repeats and release are swallowed.
    var okRaised by remember { mutableStateOf(false) }
    val playKey = remember { FocusRequester() }
    val queueKey = remember { FocusRequester() }
    val seekKey = remember { FocusRequester() }
    val seekKeys = remember(controller) { SeekKeys(controller) }
    // True while the drawer slides out after a close, so that it is still drawn while it leaves.
    var drawerLeaving by remember { mutableStateOf(false) }
    // Counts closings of the drawer, so each one hands the focus back to the key that opened it.
    var queueClosed by remember { mutableIntStateOf(0) }
    // Every view has keys; a station has two at most, the play key and the cast key while casting.
    val shown = controlsShown
    // Up from the row reaches the seek bar only where it can move (the desktop's tab stop: shown and able to
    // seek). Staying on it needs less: a bar that already has the focus keeps it while the next track's length
    // is still being read, and its keys do nothing until then.
    val seekReachable = !station && (nowPlaying?.durationMs ?: 0) > 0

    // The drawer takes Back while it is open. It is composed later than this screen, so its handler is asked first.
    BackHandler(enabled = shown && !queueOpen) {
        controlsShown = false
    }
    BackHandler(enabled = !shown && !queueOpen, onBack = onBack)

    // A track or a station that pauses, by the play key or by the remote's play/pause key, brings the controls up
    // with the play key focused. They stay up while it is paused, so the screen shows that it is paused and OK resumes.
    val pausedTrack = nowPlaying != null && !nowPlaying.playing
    LaunchedEffect(pausedTrack) {
        if (pausedTrack) controlsShown = true
    }

    // Restarted by every key press, so a wait started before the latest key can never hide the controls.
    // The controls stay up while the drawer or the cast menu is open: hiding them would move the focus to the
    // surface, out of the drawer or menu.
    val castMenuOpen = castMenu.isOpen
    LaunchedEffect(controlsShown, keyPresses, queueOpen, pausedTrack, castMenuOpen) {
        if (controlsShown && !queueOpen && !pausedTrack && !castMenuOpen) {
            delay(HIDE_AFTER_MS)
            controlsShown = false
        }
    }
    // Keyed on [station] too: a track becoming a station removes the other keys from the row, including the one
    // that had the focus.
    LaunchedEffect(shown, station) {
        runCatching { if (shown) playKey.requestFocus() else surface.requestFocus() }
    }
    LaunchedEffect(queueClosed) {
        if (queueClosed > 0) runCatching { queueKey.requestFocus() }
    }

    // The heart as last pressed, until the collection says the same. A mark is written at once, but the collection
    // it shows in is rebuilt from it, which takes up to half a minute on a TV box: without this the heart showed the
    // old state that long, and a second press repeated the first. Forgotten when the track changes, or after
    // [HEART_HELD_MS] if the collection never agrees (a write that failed, which the notice says).
    var pendingHeart by remember(nowPlaying?.mediaId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(favourite, pendingHeart) {
        if (pendingHeart == null) return@LaunchedEffect
        if (favourite == pendingHeart) pendingHeart = null else {
            delay(HEART_HELD_MS)
            pendingHeart = null
        }
    }
    val heart = favourite?.let { pendingHeart ?: it }

    // The drawer pushes the whole picture to the right: by half its own width (0.36 of the screen), which centres
    // the picture in the room the drawer leaves. Pushed by the whole width, the record, the lines and the keys ran
    // off the right edge of the screen. The room stays put behind the drawer's smoked glass, which shows it moving.
    // While the drawer is up, the pushed picture is recorded for its glass to blur ([rememberDrawerPicture]).
    val drawerPicture = rememberDrawerPicture()
    // The room under the drawer, as a blurred still of it ([DrawerRoom]): the room's style is read from the room as the
    // drawer opens, which recomposes this; it changes only in the settings, which the open drawer is not over.
    val drawerRoom = remember(roomStills) { DrawerRoom(roomStills) }
    val push by animateFloatAsState(if (queueOpen) PICTURE_PUSH else 0f, tween(DRAWER_GLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "push")

    // Passed down as a function and read in the keys' layer, so the 140 and 420 ms fades redraw the keys
    // without recomposing the player's views on every frame.
    val keysFade = animateFloatAsState(
        if (shown) 1f else 0f,
        tween(if (shown) FADE_IN_MS else FADE_OUT_MS, easing = QuadInOut),
        label = "keys",
    )
    val keysAlpha = remember(keysFade) { { keysFade.value } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(surface)
            .onPreviewKeyEvent { event ->
                if (queueOpen) return@onPreviewKeyEvent false
                val native = event.nativeKeyEvent
                // The OK that brought the controls up is used up by it. Without this its release reached the play key,
                // focused by then, and a clickable clicks on the release after a press it saw (a held OK's repeats
                // are such a press), so the OK paused after all. A fresh press ends it, in case the release was lost.
                if (okRaised && KeyRules.isOk(native.keyCode)) {
                    if (event.type == KeyEventType.KeyUp) {
                        okRaised = false
                        return@onPreviewKeyEvent true
                    }
                    if (native.repeatCount > 0) return@onPreviewKeyEvent true
                    okRaised = false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keyPresses += 1
                if (shown) return@onPreviewKeyEvent false
                val action = KeyRules.forHiddenControls(event.key.nativeKeyCode)
                    // A live station has nowhere to seek to.
                    ?.takeUnless { station }
                when {
                    action != null -> {
                        if ((action == PlayerAction.BACK || action == PlayerAction.FORWARD) && controller?.isCurrentMediaItemSeekable != true) {
                            com.rpm.player.core.diag.Diag.d("rpm.seek", "hidden ${action.name}: seekable=${controller?.isCurrentMediaItemSeekable} duration=${controller?.duration}")
                        }
                        controller?.perform(action)
                        true
                    }
                    KeyRules.raisesControls(native.keyCode) -> {
                        controlsShown = true
                        if (KeyRules.isOk(native.keyCode)) okRaised = true
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        val seek: @Composable () -> Unit = {
            SeekBar(
                nowPlaying = nowPlaying,
                keys = seekKeys,
                modifier = Modifier
                    .focusRequester(seekKey)
                    .focusProperties {
                        canFocus = shown && !queueOpen
                        up = FocusRequester.Cancel
                        down = playKey
                    },
            )
        }
        val row: @Composable (Dp) -> Unit = { column ->
            TransportRow(
                column = column,
                nowPlaying = nowPlaying,
                controller = controller,
                station = station,
                lyricKey = if (lyricsKnown) lyricsOn else null,
                onLyrics = onLyricsKey,
                onQueue = { queueOpen = true },
                layoutKey = layoutKey,
                onLayout = {
                    onNextView()
                    viewNamed = PegasusView.entries[(view.ordinal + 1) % PegasusView.entries.size]
                },
                canFocus = shown && !queueOpen,
                favourite = heart,
                onFavourite = { to ->
                    pendingHeart = to
                    onFavourite(to)
                },
                above = if (seekReachable) seekKey else FocusRequester.Cancel,
                playKey = playKey,
                queueKey = queueKey,
                casting = castState is CastState.On,
                castKey = castKey,
                onCast = {
                    castMenu.open(context.getString(R.string.Rpm_Cast_Title), castLines(context, casting, castState, castFound), returnTo = castKey)
                },
            )
        }

        Box(Modifier.fillMaxSize().then(if (queueOpen || drawerLeaving) Modifier.backdropSource(drawerPicture) else Modifier).graphicsLayer { translationX = push * size.width }) {
            when {
                // The compact view keeps its record and puts the lyrics beside it. The full view gives the lyrics the screen.
                view == PegasusView.Centred -> PegasusCentred(
                    nowPlaying, images, vinyl, frame, keysAlpha, seek, row,
                    lyrics = if (lyricsShown) ({ LyricColumn(shownSheet, nowPlaying, karaoke, shownMeasured, Modifier.fillMaxSize()) }) else null,
                )
                lyricsShown -> LyricsView(shownSheet, nowPlaying, karaoke, shownMeasured, keysAlpha, seek, row)
                else -> PegasusSpread(nowPlaying, images, vinyl, frame, keysAlpha, seek, row, drawerOpen = queueOpen)
            }
        }

        // A failure is shown where the keys would be, while they are hidden. A station that is reconnecting
        // says so in its lines instead.
        if (nowPlaying?.failed == true && nowPlaying.station?.reconnecting != true && !shown) {
            Text(
                stringResource(R.string.Rpm_Playback_Failed),
                style = ui(14.ts),
                color = Color(0xFFFF8A80),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 44.px),
            )
        }

        // Across the top: where the music is playing while it is on a Cast device, and what happened to a cast
        // that could not start or ended by itself. For a moment after the layout key, it shows the layout's name.
        val castLine = viewNamed?.let { stringResource(it.label) }
            ?: noticeShown?.text ?: castState.device?.takeIf { castState is CastState.On }?.let { stringResource(R.string.Rpm_Cast_Playing, it.name) }
        if (castLine != null) {
            Text(
                castLine,
                style = ui(14.ts),
                color = if (noticeShown != null) Ink.Artist else Ink.Dim,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.px),
            )
        }

        // Composed while open and while it slides out after a close. The drawer says when the slide has ended.
        if ((queueOpen || drawerLeaving) && controller != null) {
            QueueDrawer(
                controller,
                images,
                drawerPicture,
                drawerRoom,
                DrawerRoom.Look(reading?.room ?: RoomPalette.FALLBACK, room.style, if (spread) 0f else 1f),
                open = queueOpen,
                onClose = {
                    // Closes only once: a Back that reaches the drawer while it slides out does nothing more.
                    if (queueOpen) {
                        queueOpen = false
                        drawerLeaving = true
                        queueClosed += 1
                    }
                },
                onGone = { drawerLeaving = false },
            )
        }

        RowMenu(castMenu)
    }
}

/** How long a cast notice stays at the top of the player. */
private const val CAST_NOTICE_MS = 6_000L

/** How long the layout's name stays at the top after the layout key. */
private const val VIEW_NAME_MS = 2_000L

/** The name the settings and the layout key give a layout. */
val PegasusView.label: Int
    get() = when (this) {
        PegasusView.Spread -> R.string.Viz_LayoutFull
        PegasusView.Centred -> R.string.Viz_LayoutCompact
    }

/** The queue drawer's share of the screen's width. Its slide time ([DRAWER_GLIDE_MS]) is about the same as the
 * desktop's drawer takes. */
private const val DRAWER_SHARE = 0.36f

/** How far the picture moves aside while the drawer is open, as a share of the screen: half the drawer, so it is
 * centred in what is left of the screen. */
private const val PICTURE_PUSH = DRAWER_SHARE / 2

/** How long a pressed heart is shown before the collection must have caught up with it. */
private const val HEART_HELD_MS = 90_000L

/** How long the controls stay up without a key press (the desktop's TransportIdle). */
const val HIDE_AFTER_MS = 5_000L
private const val FADE_IN_MS = 140
private const val FADE_OUT_MS = 420

/** The desktop's fade curve: quadratic in and out. */
val QuadInOut = Easing { t -> if (t < 0.5f) 2 * t * t else 1 - 2 * (1 - t) * (1 - t) }

/**
 * The desktop's lyric view: the lyrics centred over the room, and at the foot the seek bar ([seek]) and the
 * keys, as wide as the lyric column and `max(32, 0.045 h)` from the bottom.
 */
@Composable
private fun LyricsView(sheet: LyricSheet, nowPlaying: NowPlaying?, karaoke: Boolean, measured: List<com.rpm.player.core.lyrics.MeasuredLine>, keysAlpha: () -> Float, seek: @Composable () -> Unit, row: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val column = minOf(maxWidth * 0.78f, 1400.px)
        LyricColumn(sheet, nowPlaying, karaoke, measured, Modifier.align(Alignment.Center).width(column).fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = max(32.px, maxHeight * 0.045f))
                .width(column)
                .graphicsLayer { alpha = keysAlpha() },
        ) {
            seek()
            Box(Modifier.padding(top = 14.s)) { row(column) }
        }
    }
}

/**
 * The keys under the seek bar, as wide as the words column (PegasusChrome.xaml, TransportRow). At the
 * left are the queue, the way back to the collection and the heart. In the middle are previous, play or pause,
 * and next. At the right are the cast key, the lyric key (when there are synced lyrics) and the layout key.
 * Sizes are the desktop's at the block's scale (`s`): keys 36 square, the play key 42, glyphs 15, 14, 16 and 20.
 *
 * The desktop has the lyric and layout keys at the left and a volume bar at the right. The TV has no volume
 * bar, because the remote's own keys set the television's volume. The lyric and layout keys stand at the
 * right, where the volume bar is on the desktop. This leaves room on both sides of the middle keys: with the
 * lyric key on the left in the spread view, the left keys would run into previous.
 *
 * The heart sets the track playing as a favourite, or takes it off: outlined until it is one, filled after. On a
 * track of a collection someone shares there is none.
 *
 * A [station] shows the play key and nothing else: a live stream has no queue, no previous or next and
 * nothing to go back to in the row. The one exception is the cast key while the music is on a Cast device,
 * which is the way back to the TV.
 *
 * Up from any key goes to [above]: the seek bar, or [FocusRequester.Cancel] where there is none to reach.
 */
@Composable
private fun TransportRow(
    column: Dp,
    nowPlaying: NowPlaying?,
    controller: Player?,
    station: Boolean,
    lyricKey: Boolean?,
    onLyrics: () -> Unit,
    onQueue: () -> Unit,
    layoutKey: FocusRequester,
    onLayout: () -> Unit,
    canFocus: Boolean,
    favourite: Boolean?,
    onFavourite: (Boolean) -> Unit,
    above: FocusRequester,
    playKey: FocusRequester,
    queueKey: FocusRequester,
    casting: Boolean,
    castKey: FocusRequester,
    onCast: () -> Unit,
) {
    val playing = nowPlaying?.playing == true
    val keys = Modifier.focusProperties {
        this.canFocus = canFocus
        up = above
    }
    Box(Modifier.width(column).height(42.s)) {
        if (!station) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.s), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterStart)) {
                if (controller != null) {
                    TransportKey(Fa.BARS, 15.ts, 36.s, stringResource(R.string.Viz_Queue), onQueue, keys.focusRequester(queueKey))
                }
                if (favourite != null) {
                    // Outlined until the track is a favourite, then filled; OK sets it or takes it off.
                    val heartLabel = stringResource(if (favourite) R.string.Track_UnsetFavorite else R.string.Track_SetFavorite)
                    DeskButton(
                        onClick = { onFavourite(!favourite) },
                        corner = 18.s,
                        modifier = keys.size(36.s).semantics { contentDescription = heartLabel },
                    ) {
                        Glyph(Fa.HEART, 14.ts, Ink.Key, regular = !favourite)
                    }
                }
                // A track of a collection someone shares has no heart: the marks there are the owner's.
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.s), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.Center)) {
            if (!station) {
                TransportKey(Fa.BACKWARD, 16.ts, 36.s, stringResource(R.string.Transport_Previous), { controller?.perform(PlayerAction.PREVIOUS) }, keys)
            }
            TransportKey(
                if (playing) Fa.PAUSE else Fa.PLAY,
                20.ts,
                42.s,
                stringResource(R.string.Transport_PlayPause),
                { controller?.perform(if (playing) PlayerAction.PAUSE else PlayerAction.PLAY) },
                keys.focusRequester(playKey),
            )
            if (!station) {
                TransportKey(Fa.FORWARD, 16.ts, 36.s, stringResource(R.string.Transport_Next), { controller?.perform(PlayerAction.NEXT) }, keys)
            }
        }
        // The cast key, then the lyric key and the layout key. A station shows the cast key only while a cast is on,
        // so the music can be brought back from the radio as well. A station has no lyrics and no layouts.
        Row(horizontalArrangement = Arrangement.spacedBy(2.s), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.align(Alignment.CenterEnd)) {
            if (!station || casting) {
                CastKey(connected = casting, onClick = onCast, modifier = keys.focusRequester(castKey))
            }
            if (lyricKey != null) {
                LyricKey(filled = lyricKey, onClick = onLyrics, modifier = keys)
            }
            if (!station) {
                TransportKey(Fa.LAYOUT, 15.ts, 36.s, stringResource(R.string.Viz_SettingsLayout), onLayout, keys.focusRequester(layoutKey))
            }
        }
    }
}

@Composable
private fun TransportKey(glyph: Char, size: TextUnit, box: Dp, label: String, onClick: () -> Unit, modifier: Modifier) {
    DeskButton(
        onClick = onClick,
        corner = box / 2,
        modifier = modifier.size(box).semantics { contentDescription = label },
    ) {
        Glyph(glyph, size, Ink.Key)
    }
}

/** The lyric key: a speech box with two quotes, hollow while the lyrics are off and filled while they
 * are on. It uses the desktop's path, at its own size, on a key with corner 8. */
@Composable
private fun LyricKey(filled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val label = stringResource(if (filled) R.string.Viz_LyricsBack else R.string.Viz_Lyrics)
    val path = remember(filled) { PathParser().parsePathString(if (filled) LYRIC_FILLED else LYRIC_HOLLOW).toPath().apply { fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd } }
    DeskButton(onClick = onClick, corner = 8.s, modifier = modifier.size(36.s).semantics { contentDescription = label }) {
        Canvas(Modifier.size(width = 30.s * 0.5f, height = 29.73f.s * 0.5f)) {
            val k = size.width / 30f
            scale(k, k, pivot = Offset.Zero) { drawPath(path, Ink.Key, style = Fill) }
        }
    }
}

/** How long the player waits before reading a measurement it asked for, and how many times it reads. */
// The server finishes most songs in 14 to 42 s. A read is one small GET, so the first comes at 8 s and then one
// every 5 s until about two minutes have passed, so an answer shows within seconds of being there.
private const val MEASURE_WAIT_MS = 8_000L
private const val MEASURE_EVERY_MS = 5_000L
private const val MEASURE_READS = 23

/** How long into a track the player reads the next one's sheet and asks for its timing: after this track's own
 * lookups, so they do not wait on it. */
private const val AHEAD_AFTER_MS = 6_000L

private const val LYRIC_BOX = "M2.95,0 H27.05 A2.95,2.95 0 0 1 30,2.95 V26.79 A2.95,2.95 0 0 1 27.05,29.73 H2.95 A2.95,2.95 0 0 1 0,26.79 V2.95 A2.95,2.95 0 0 1 2.95,0 Z "
private const val LYRIC_QUOTES = "M12.58,14.82 A3.35,3.35 0 1 0 9.78,16.34 C9.78,17.95 8.97,19.42 7.9,20.3 C11.12,19.02 12.19,16.88 12.58,14.82 Z M23.03,14.82 A3.35,3.35 0 1 0 20.22,16.34 C20.22,17.95 19.42,19.42 18.35,20.3 C21.56,19.02 22.63,16.88 23.03,14.82 Z"
private const val LYRIC_HOLLOW = LYRIC_BOX + "M3.21,2.14 A1.07,1.07 0 0 0 2.14,3.21 V26.52 A1.07,1.07 0 0 0 3.21,27.59 H26.79 A1.07,1.07 0 0 0 27.86,26.52 V3.21 A1.07,1.07 0 0 0 26.79,2.14 Z " + LYRIC_QUOTES
private const val LYRIC_FILLED = LYRIC_BOX + LYRIC_QUOTES

