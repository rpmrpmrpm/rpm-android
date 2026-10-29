package com.rpm.player.tv.ui

import androidx.compose.animation.core.Animatable
import com.rpm.player.core.playback.TrackRef
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.rememberNowPlaying
import com.rpm.player.core.playback.Queued
import com.rpm.player.core.playback.playQueue
import com.rpm.player.core.playback.startRadio
import com.rpm.player.core.playback.tune
import com.rpm.player.core.playback.playNext
import com.rpm.player.core.playback.addToQueue
import com.rpm.player.core.diag.Diag
import com.rpm.player.core.library.LibraryState
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.DisposableEffect
import com.rpm.player.core.library.ListName
import com.rpm.player.core.library.Filed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.stringResource
import com.rpm.player.core.library.shelf
import com.rpm.player.core.library.ShelfNotice
import com.rpm.player.core.cloud.Shelf
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.core.radio.RadioStation
import com.rpm.player.core.room.RoomPalette
import androidx.compose.ui.graphics.asAndroidBitmap
import com.rpm.player.core.room.RoomStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.draw.drawWithContent
import com.rpm.player.core.motion.LocalMotion
import com.rpm.player.core.motion.Motion
import com.rpm.player.core.room.RoomPicture
import com.rpm.player.core.room.RoomTarget
import com.rpm.player.core.room.RoomView
import kotlinx.coroutines.launch

/** A screen of the app below the player. */
sealed interface Route {
    data object Home : Route
    data object Albums : Route
    /** The search over the collection's artists, albums and tracks and the radio stations. */
    data object Search : Route
    data object Playlists : Route
    data object Radio : Route
    data object Computers : Route
    /** An album's page, by its wall key. */
    data class Record(val albumKey: String) : Route
    /** A list's page, by its id. */
    data class OfList(val listId: String) : Route
    /** The artists list. */
    data object Artists : Route
    /** The genres list. */
    data object Genres : Route
    /** An artist's page, by the name the artists list shows. */
    data class OfArtist(val name: String) : Route
    /** A genre's page, by its name. */
    data class OfGenre(val name: String) : Route
}

/**
 * Which screen is up. No session means the sign-in screen, whatever was showing before. Below the
 * player is a stack of screens with home at the bottom: Back pops it, and Back on home leaves the app,
 * as Google's TV checklist requires (TV-DB). The side menu opens over home and the wall. [onRoomCovers]
 * is told whether the room's picture covers the whole window, so the window need not paint a background
 * under it.
 */
@Composable
fun RpmApp(
    services: Services,
    settings: PlayerSettings,
    controller: Player?,
    screensaver: Screensaver,
    /** How many times the activity was asked to show the player (the launcher's now playing card). */
    playerAsks: Int,
    onExit: () -> Unit,
    onRoomCovers: (Boolean) -> Unit,
) {
    val session by services.auth.session.collectAsStateWithLifecycle()
    val nowPlaying = rememberNowPlaying(controller)
    var playerShown by rememberSaveable { mutableStateOf(false) }
    // Each ask opens the player once, as soon as there is something in it: on a cold start the controller connects
    // after the ask arrived, and the order of the two does not matter. Counted per activity, as the asks are.
    var playerAsksSeen by remember { mutableStateOf(0) }
    LaunchedEffect(playerAsks, nowPlaying != null) {
        if (playerAsks > playerAsksSeen && nowPlaying != null) {
            playerAsksSeen = playerAsks
            playerShown = true
        }
    }

    // The screensaver ([Screensaver]): the screens and everything over them fade out on the alpha of the layer
    // they are drawn in, and the room is left. Out slowly, back quickly, as the key that wakes them asks.
    // Not over the player: it is what the screensaver would show anyway, and someone watching the record or the lyrics
    // is not idle. Leaving the player is a key, which starts the wait again.
    // Nor over the set-up, which waits on the person reading it.
    LaunchedEffect(screensaver, settings.screensaverMinutes, playerShown, settings.setupPending) {
        if (!playerShown && !settings.setupPending) screensaver.watch(settings.screensaverMinutes)
    }
    val screensAlpha = animateFloatAsState(
        if (screensaver.shown) 0f else 1f,
        tween(if (screensaver.shown) SCREENSAVER_OUT_MS else SCREENSAVER_IN_MS, easing = QuadInOut),
        label = "screensaver",
    )
    val screensGone by remember { derivedStateOf { screensAlpha.value == 0f } }

    // The drifting cards change on one clock, which stops while the screens are gone: nothing they draw is seen.
    val driftClock = remember { DriftClock() }
    LaunchedEffect(driftClock, screensGone) { if (!screensGone) driftClock.run() }
    CompositionLocalProvider(LocalMotion provides services.motion.level, LocalDriftClock provides driftClock, LocalDrawnAt provides 1f, LocalSongCovers provides services.songCovers) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        // One opening sound for the process: the sign-in screen plays it as it comes up, and the opening screen's
        // hand-over then finds it played, so the app always starts with the opening sound.
        val appContext = LocalContext.current.applicationContext
        // The activity's, which carries the chosen language; the application's has the system's.
        val uiContext = LocalContext.current
        val openingSound = remember { OpeningSound(appContext) }
        DisposableEffect(openingSound) { onDispose { openingSound.stop() } }
        LifecycleEventEffect(Lifecycle.Event.ON_STOP) { openingSound.stop() }
        // Music started: the flourish stops, as the desktop's does.
        LaunchedEffect(nowPlaying?.playing) { if (nowPlaying?.playing == true) openingSound.stop() }
        val signedIn = session ?: run {
            SignInScreen(services.auth, settings, openingSound)
            return@MaterialTheme
        }
        val stack = remember(signedIn.userId) { ScreenStack() }
        // The key legend of the screen just opened ([ControlsLegend]).
        val legend = remember(signedIn.userId) { LegendState() }
        var menuOpen by remember { mutableStateOf(false) }
        var settingsOpen by remember { mutableStateOf(false) }
        // The album wall's search, kept while an album page is open over the wall (WallSearchState).
        val wallSearch = remember(signedIn.userId) { WallSearchState() }
        // Where the artists and genres screens were left, kept while a page is open over them (BrowseState).
        val browse = remember(signedIn.userId) { BrowseState() }
        var swallowLeft by remember { mutableStateOf(false) }
        val route = stack.top.route

        // The room is under every screen, as on the desktop. Its colours are the playing cover's (or
        // station logo's), except on a record or list page, which paints it in that page's picture.
        val room = remember { RoomTarget() }
        val libraryState by services.library.state.collectAsStateWithLifecycle()
        val reloading by services.library.refreshing.collectAsStateWithLifecycle()

        // The opening screen, over everything until the first library is up with home's first pictures in
        // (OpeningSplash), then faded away as the opening sound starts. Once per process: a later load
        // (another collection, a retry) shows its own message instead.
        var opening by rememberSaveable { mutableStateOf(true) }
        var fading by remember { mutableStateOf(false) }
        var picturesIn by remember { mutableStateOf(false) }
        val firstReady = libraryState as? LibraryState.Ready
        LaunchedEffect(opening, firstReady != null) {
            val ready = firstReady ?: return@LaunchedEffect
            if (!opening || picturesIn) return@LaunchedEffect
            // Waited for, but not for ever: a picture that will not come is not worth a blank screen. The
            // wait is on a join, which a timeout ends at once. A timeout around the fetch itself would wait for
            // fetches stuck on the network to give up, and hold the screen up long after its time.
            Diag.d(OPENING_LOG, "library ready at ${sinceStart()} ms")
            val fetching = launch { fetchHomePictures(ready, services.images, appContext) }
            val inTime = withTimeoutOrNull(OPENING_PICTURES_MS) { fetching.join() } != null
            Diag.d(OPENING_LOG, "pictures ${if (inTime) "in" else "not all in"} at ${sinceStart()} ms")
            picturesIn = true
        }
        // A library that failed hands over too, to its message and its Retry key.
        val handOver = picturesIn || libraryState is LibraryState.Failed
        // After the set-up, the albums screen instead of home when the collection is too small for home's rows.
        var afterSetup by rememberSaveable { mutableStateOf(false) }
        val shelfOnScreen = libraryState.shelf
        val shared by services.shared.list.collectAsStateWithLifecycle()
        val ownFace by services.ownFace.face.collectAsStateWithLifecycle()
        // The supporter features (karaoke) follow the quota's standing, which a closed beta tester shares with a
        // paying supporter. ownFace.supporter is the supporter's mark only, which a tester does not wear.
        val ownQuota by services.ownFace.quota.collectAsStateWithLifecycle()
        val notice by services.library.notice.collectAsStateWithLifecycle()
        val withdrawn = stringResource(R.string.Rpm_Error_ShareUnavailable)
        val notServed = stringResource(R.string.Rpm_Share_NotServed)
        var noticeText by remember { mutableStateOf<String?>(null) }
        var noticeCount by remember { mutableStateOf(0) }
        val heartContext = LocalContext.current
        LaunchedEffect(notice) {
            val shown = notice ?: return@LaunchedEffect
            noticeText = if (shown is ShelfNotice.NotServed) notServed else withdrawn
            noticeCount++
            services.library.noticeShown(shown)
        }
        // The room is held still from the Still room level down.
        val motion = services.motion.level
        SideEffect { room.still = motion >= Motion.Still }
        SideEffect { room.light = settings.picture.light }
        SideEffect { room.style = settings.roomStyle }
        var pagePicture by remember { mutableStateOf<Any?>(null) }
        // A genre's page paints the room in the genre's own colours rather than in a picture's.
        var pagePalette by remember { mutableStateOf<RoomPalette?>(null) }
        // A station's room takes the colours of the song it plays, once that song's cover is found, as a record's room
        // takes its sleeve's; the station's logo until then, and between songs.
        val songCover = rememberSongCover(nowPlaying?.station)
        val playingPicture: Any? = songCover ?: nowPlaying?.station?.logo ?: nowPlaying?.cover
        val roomPicture = if (!playerShown && pagePicture != null) pagePicture else playingPicture
        val reading = rememberCoverReading(roomPicture, services.images)
        val roomPalette = (if (!playerShown) pagePalette else null) ?: reading?.room ?: RoomPalette.FALLBACK
        LaunchedEffect(roomPalette) { room.palette = roomPalette }
        val spot = remember { FocusSpot() }
        val mini = remember { MiniPlayerState() }
        val radioFront = remember { RadioFront() }
        val focusManager = LocalFocusManager.current
        // The screens remember which of their items had the focus, so closing the side menu over the
        // same screen gives it back; a screen picked from the menu takes its own first focus instead.
        val screensFocus = remember { FocusRequester() }
        var menuFrom by remember { mutableStateOf<StackEntry?>(null) }
        LaunchedEffect(menuOpen) {
            if (menuOpen) {
                menuFrom = stack.top
            } else if (menuFrom != null) {
                if (menuFrom == stack.top) runCatching { screensFocus.requestFocus() }
                menuFrom = null
            }
        }
        val backdropLayer = rememberGraphicsLayer()
        val backdrop = remember(backdropLayer) { Backdrop(backdropLayer) }
        // The page's picture as last drawn, and the last still of the live room, for what a capture puts under the
        // screens ([Backdrop.under]).
        val underLayer = rememberGraphicsLayer()
        var underStill by remember { mutableStateOf<Pair<Pair<Triple<RoomPalette, RoomStyle, Float>, IntSize>, android.graphics.Bitmap>?>(null) }
        SideEffect { backdrop.ground = roomGround(roomPalette) }

        // A screen picked from the side menu opens over home, whatever was open before.
        fun open(picked: Route) {
            menuOpen = false
            wallSearch.close()
            browse.reset()
            stack.overHome(picked)
        }
        LaunchedEffect(afterSetup, firstReady != null) {
            val ready = firstReady ?: return@LaunchedEffect
            if (!afterSetup) return@LaunchedEffect
            afterSetup = false
            if (ready.albums.size < HOME_FROM_ALBUMS) open(Route.Albums)
        }

        fun play(queue: List<Queued>, start: Int) {
            // Before the controller has connected there is nothing to play on.
            controller?.let {
                it.playQueue(queue, start)
                playerShown = true
            }
        }

        // Back leaves the screen on top; on home it opens the side menu, where Back twice leaves the app.
        fun back() {
            if (stack.size > 1) stack.pop() else menuOpen = true
        }

        BackHandler(enabled = !playerShown && !menuOpen && !settingsOpen, onBack = ::back)

        // The settings panel gives the focus back to the screen it was opened over when it closes.
        var settingsWereOpen by remember { mutableStateOf(false) }
        LaunchedEffect(settingsOpen) {
            if (settingsOpen) {
                settingsWereOpen = true
            } else if (settingsWereOpen) {
                settingsWereOpen = false
                runCatching { screensFocus.requestFocus() }
            }
        }

        // A screen opened gets its key legend while nothing covers it: not under the opening screen, the set-up, the
        // player, the side menu or the settings, nor before the library is up. Something come over it sends it away.
        val legendReady = settings.keyLegend && !opening && !settings.setupPending && libraryState is LibraryState.Ready &&
            (!playerShown || nowPlaying == null) && !menuOpen && !settingsOpen
        LaunchedEffect(stack.top, legendReady) { legend.front(stack.top, legendReady) }

        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    // Any key sends the key legend away; the key itself goes on to do what it does.
                    if (key.action == KeyEvent.ACTION_DOWN) legend.keyDown()
                    // The set-up takes its own keys; nothing under it is reachable, as its keys hold the focus.
                    if (settings.setupPending) return@onPreviewKeyEvent false
                    // Nothing under the opening screen moves while it is up. Back is the activity's, and
                    // still leaves.
                    if (opening) return@onPreviewKeyEvent true
                    // The rest of a press that opened the menu is swallowed, wherever the focus went.
                    if (key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && swallowLeft) {
                        if (key.action == KeyEvent.ACTION_UP) swallowLeft = false
                        return@onPreviewKeyEvent true
                    }
                    if (playerShown || menuOpen || settingsOpen) return@onPreviewKeyEvent false
                    when {
                        // The Menu key opens the side menu on the screens it leads from; on a page of
                        // tracks it is a track's menu, which the row handles.
                        sideMenuFrom(route) && key.keyCode == KeyEvent.KEYCODE_MENU &&
                            key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0 -> {
                            menuOpen = true
                            true
                        }
                        // Left held for 2 seconds on home or radio opens the menu, decided on a repeat of
                        // the same press, on the key's own clock. The first press moves as any Left does; its
                        // repeats move nothing.
                        (route == Route.Home || (route == Route.Radio && radioFront.inFront)) && key.keyCode == KeyEvent.KEYCODE_DPAD_LEFT &&
                            key.action == KeyEvent.ACTION_DOWN && key.repeatCount > 0 -> {
                            if (key.eventTime - key.downTime >= SIDE_HOLD_MS) {
                                menuOpen = true
                                swallowLeft = true
                            }
                            true
                        }
                        else -> false
                    }
                },
        ) {
            val collectionUp = !playerShown || nowPlaying == null
            // The swap between the player and the screens, as one fade: 1 with the screens up, 0 with the player. The
            // screens' backdrop (the picture and its shade) comes and goes with it, over the screen's own fade, and the
            // player fades the other way over the same time, so neither pops in or out over the other. The room's
            // surface behind both stays until the swap has ended (see the hand-over below).
            val homeIn = remember { Animatable(if (collectionUp) 1f else 0f) }
            // Where the swap stands: 1 the screens, 0 the player, -1 on its way.
            val homeAt by remember { derivedStateOf { when { homeIn.value >= 1f -> 1; homeIn.value <= 0f -> 0; else -> -1 } } }
            // A still room under a collection screen is a picture inside the window, with the screens' shade
            // laid in, not the surface behind it: one layer for the display rather than two the GPU blends
            // on every frame. The player keeps
            // the surface, which draws its spectrum. Each stands in for the other until it has drawn.
            // A still backdrop chosen for the pages ([PageBackground]) is such a picture too, except while the
            // screensaver is up: the screens make way for the moving room, whatever the pages are drawn on.
            val pageBackground = settings.background
            val pictured = collectionUp && (motion >= Motion.Still || (pageBackground != PageBackground.Room && !screensaver.shown))
            var surfaceOn by remember { mutableStateOf(!pictured) }
            var pictureOn by remember { mutableStateOf(pictured) }
            var surfaceDrawn by remember { mutableStateOf(false) }
            // Opening the player waits for the room's surface to have drawn: the screens and their picture stay whole
            // over it until then, rather than fading to the window's own grey and black with nothing behind them.
            LaunchedEffect(collectionUp, surfaceDrawn) {
                if (!collectionUp && !surfaceDrawn) return@LaunchedEffect
                val to = if (collectionUp) 1f else 0f
                if (motion >= Motion.Still) homeIn.snapTo(to) else homeIn.animateTo(to, tween(SCREEN_FADE_MS, easing = QuadInOut))
            }
            var pictureShown by remember { mutableStateOf(false) }
            LaunchedEffect(pictured, pictureShown, surfaceDrawn, homeAt) {
                if (pictured) {
                    pictureOn = true
                    // Not while the picture is still fading in over it from the player.
                    if (pictureShown && surfaceOn && homeAt == 1) {
                        // Two frames for the picture to reach the display before the surface under it goes.
                        withFrameNanos { }
                        withFrameNanos { }
                        surfaceOn = false
                        surfaceDrawn = false
                    }
                } else {
                    surfaceOn = true
                    // Not while the picture is still fading out over the opening player.
                    if (surfaceDrawn && pictureOn && homeAt != -1) {
                        withFrameNanos { }
                        withFrameNanos { }
                        pictureOn = false
                        pictureShown = false
                    }
                }
            }
            // The player draws the spectrum and the grain on the room's surface, which need the screen's
            // pixels; under a collection screen the surface is the room's own size, stretched.
            if (surfaceOn) RoomView(services.audioTap, room, sharp = !collectionUp, onDrawn = { surfaceDrawn = true }, Modifier.fillMaxSize())
            val covers = pictureOn && pictureShown && homeAt == 1
            DisposableEffect(covers) {
                onRoomCovers(covers)
                onDispose { onRoomCovers(false) }
            }
            // Which picture is drawn: the one the pages are on, chosen while the picture is where the screens are
            // going. While the surface is coming up instead (the player opening), the picture already showing
            // stands in until the surface has drawn. Switching it to the room's still would put a picture not yet
            // decoded under the opening player, with nothing under it for a third of a second; on the Shield the
            // screens' window shows white there.
            // Read in composition: it changes only with [pictured], which recomposes this.
            val standing = remember { arrayOf(pageBackground) }
            if (pictured) standing[0] = pageBackground
            if (pictureOn) {
                // A change of backdrop shows the new one in the old one's place; the surface is not brought back for it.
                val dim = (1f - Ink.Scrim.alpha) * settings.picture.light
                // Recorded as it is drawn, for what a capture of the screens puts under them ([Backdrop.under]).
                Box(Modifier.fillMaxSize().drawWithContent {
                    underLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(underLayer)
                }) {
                    if (standing[0] == PageBackground.Room) {
                        RoomPicture(services.roomStills, reading?.room ?: RoomPalette.FALLBACK, settings.roomStyle, dim = dim, onShown = { pictureShown = true }, Modifier.fillMaxSize().swapFade { homeIn.value })
                    } else {
                        BackdropPicture(standing[0], dim = dim, onShown = { pictureShown = true }, Modifier.fillMaxSize().swapFade { homeIn.value })
                    }
                }
            }
            // What lies under the screens for a capture of them (a row's menu, the searches): the page's picture as
            // drawn, or over the live room a still of it, made once per look and size. Without it the capture had a
            // flat colour where the screens are clear, so a menu's blur did not have the screen's colours.
            val underPicture = pictureOn && pictureShown
            val underRoom = !pictureOn && surfaceOn && collectionUp
            val stillLook = Triple(roomPalette, settings.roomStyle, settings.picture.light)
            SideEffect {
                backdrop.under = when {
                    underPicture -> { _, _ -> underLayer.toImageBitmap().asAndroidBitmap() }
                    underRoom -> { w, h ->
                        val size = IntSize((w / UNDER_SHRINK).coerceAtLeast(1), (h / UNDER_SHRINK).coerceAtLeast(1))
                        underStill?.takeIf { it.first == stillLook to size }?.second
                            ?: services.roomStills.picture(stillLook.first, stillLook.second, size.width, size.height, dim = stillLook.third)
                                ?.also { underStill = (stillLook to size) to it }
                    }
                    else -> null
                }
            }
            // The room stretched from an eighth of the screen, and the dimmed page pictures, dithered so their steps do
            // not show as rings. Over the room's surface, which is behind the window. The player's sharp room has
            // grain of its own.
            if (pictureOn || (surfaceOn && collectionUp)) Box(Modifier.fillMaxSize().dithered())
            // The screens and what opens over them, drawn at two thirds of the screen's pixels on a box
            // the motion ladder has stepped down that far (Motion.TwoThirds). The room and the opening
            // have surfaces of their own, which the system places from their unscaled position, and stay
            // outside at the screen's own size.
            DrawnAt(if (motion >= Motion.TwoThirds) 2f / 3f else 1f, alpha = { screensAlpha.value }, filter = { settings.picture.filter }) {
                Box(Modifier.fillMaxSize()) {
                    // What the glass panels blur: the screens, not the panels themselves.
                    Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                        // Every collection screen sits on the room under a flat shade (PegasusChrome.xaml:681), which
                        // the room's picture already has in it.
                        if (collectionUp && !(pictureOn && pictureShown)) Box(Modifier.fillMaxSize().swapFade { homeIn.value }.background(Ink.Scrim))
                        // What a row's menu blurs and covers, wherever on the screens it opens.
                        val menuGlass = remember(backdrop, mini) { MenuGlass(backdrop, mini) }
                        CompositionLocalProvider(LocalMenuGlass provides menuGlass) {
                            // Left with nowhere further left to go opens the side menu, as on the desktop (TakeEdgeKey).
                            // It is the one way in that every remote has: the Shield does not pass the Menu key to apps,
                            // and nobody finds a held Left, so the main menu would be out of reach.
                            // Seen here only after the focused item and the rows round it have had the key, so the
                            // banner still steps on Left and a row still scrolls back to its first card. The move is
                            // made here as the remote's own search would make it, and the menu opens only when that
                            // move finds nothing. The rest of the press is swallowed, as for the held Left.
                            val edgeLeft = Modifier.onKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (key.keyCode != KeyEvent.KEYCODE_DPAD_LEFT || key.action != KeyEvent.ACTION_DOWN || key.repeatCount != 0) return@onKeyEvent false
                                if (playerShown || menuOpen || settingsOpen || !edgeOpensSideMenu(route, radioFront.inFront)) return@onKeyEvent false
                                if (focusManager.moveFocus(FocusDirection.Left)) return@onKeyEvent true
                                menuOpen = true
                                swallowLeft = true
                                true
                            }
                            // The focus coming into the screens from outside them (the side menu or the settings closing,
                            // Down from the mini player) goes to the item of the screen in front that last had it
                            // (FocusMemory); with none, to the group's own choice.
                            val backToItem = Modifier.focusProperties { onEnter = { stack.top.focus.giveBack() } }
                            SpotScope(spot, Modifier.fillMaxSize().focusRequester(screensFocus).then(backToItem).focusGroup().then(edgeLeft)) {
                                // The screens stay composed under the player, so closing it shows the screen as it was
                                // left (ScreenStack). The player is composed after them, so its Back handlers are the
                                // later ones and take Back first.
                                Box(Modifier.fillMaxSize().swapFade { homeIn.value }) {
                                    Screens(
                                        services = services,
                                        stack = stack,
                                        // Up until the player has wholly come over them, so they fade out under it
                                        // rather than going at once.
                                        shown = collectionUp || homeAt != 0,
                                        controller = controller,
                                        nowPlaying = nowPlaying,
                                        mini = mini,
                                        radioFront = radioFront,
                                        backdrop = backdrop,
                                        ownFace = ownFace,
                                        wallSearch = wallSearch,
                                        albumCarousel = settings.albumCarousel,
                                        albumOrder = settings.albumOrder,
                                        onAlbumOrder = settings::pickAlbumOrder,
                                        browse = browse,
                                        play = ::play,
                                        back = ::back,
                                        onPicture = { pagePicture = it },
                                        onPalette = { pagePalette = it },
                                        onOpenPlayer = { playerShown = true },
                                        flash = { text ->
                                            noticeText = text
                                            noticeCount++
                                        },
                                    )
                                }
                                // Kept until the screens are wholly up, not only while the fade runs: on the frame the
                                // player is closed, the fade has not started yet.
                                if (!collectionUp || homeAt != 1) Box(Modifier.fillMaxSize().swapFade { 1f - homeIn.value }) {
                                    PlayerScreen(
                                        controller = controller,
                                        nowPlaying = nowPlaying,
                                        images = services.images,
                                        room = room,
                                        roomStills = services.roomStills,
                                        lrcLib = services.lrcLib,
                                        measuredLyrics = services.measuredLyrics,
                                        backdrop = backdrop,
                                        defaultView = settings.defaultView,
                                        onNextView = settings::nextDefaultView,
                                        karaoke = settings.karaoke && ownQuota?.supporter == true,
                                        lyricsWanted = settings.lyricsOn,
                                        onLyricsKey = settings::switchLyrics,
                                        casting = services.casting,
                                        // The heart, for a track of the person's own collection while it is the one
                                        // open: its mark is in that collection, and it is set the way a track's menu
                                        // sets it (OwnEdits.mark), with the same notice. None for a track the open
                                        // collection does not hold (it was left on another shelf, or is gone).
                                        favourite = (libraryState as? LibraryState.Ready)
                                            ?.takeIf { shelfOnScreen == Shelf.Own }
                                            ?.let { ready ->
                                                TrackRef.parse(nowPlaying?.mediaId.orEmpty())
                                                    ?.takeIf { it.shelf == Shelf.Own }
                                                    ?.let { ref -> ready.collection.tracksById[ref.rpmId]?.let { it.tags?.favorite == true } }
                                            },
                                        onFavourite = { favorite ->
                                            val ref = TrackRef.parse(nowPlaying?.mediaId.orEmpty())
                                            val title = nowPlaying?.title.orEmpty()
                                            if (ref != null) services.scope.launch {
                                                val saved = services.ownWriter.mark(ref.rpmId, favorite)
                                                noticeText = when {
                                                    saved && favorite -> heartContext.getString(R.string.Viz_FavoriteAdded, title)
                                                    saved -> heartContext.getString(R.string.Viz_FavoriteRemoved, title)
                                                    favorite -> heartContext.getString(R.string.Tv_FavoriteFailed)
                                                    else -> heartContext.getString(R.string.Tv_UnfavoriteFailed)
                                                }
                                                noticeCount++
                                            }
                                        },
                                        onBack = { playerShown = false },
                                    )
                                }
                            }
                        }
                    }
                    if (menuOpen && collectionUp) {
                        // The header names whose collection is open, as they show themselves: the person, or the
                        // account that shares the one on screen (PegasusChrome.SideMenu.cs, the borrowed header).
                        val borrowed = shelfOnScreen as? Shelf.Borrowed
                        val face = if (borrowed == null) ownFace else borrowed.let { b -> shared.firstOrNull { it.shelf == b }?.face ?: services.shared.faceOf(b) }
                        SideMenu(
                            backdrop = backdrop,
                            header = MenuHeader(
                                name = face?.name ?: when (borrowed) {
                                    null -> signedIn.displayName.ifBlank { signedIn.email }
                                    else -> stringResource(R.string.Viz_MenuShared)
                                },
                                line = when {
                                    borrowed == null -> stringResource(R.string.Share_MyLibrary)
                                    face?.name != null -> stringResource(R.string.Viz_MenuShared)
                                    else -> stringResource(R.string.Rpm_Share_CloudOnly)
                                },
                                picture = face?.picture,
                                supporter = face?.supporter == true,
                            ),
                            images = services.images,
                            entries = menuEntries(playing = nowPlaying != null),
                            current = MenuGo.Screen(route),
                            onCollections = { open(Route.Computers) },
                            reloading = reloading,
                            onReload = { services.library.refresh() },
                            onPick = { go ->
                                when (go) {
                                    is MenuGo.Screen -> open(go.route)
                                    MenuGo.Player -> {
                                        menuOpen = false
                                        playerShown = true
                                    }
                                }
                            },
                            onSettings = {
                                menuOpen = false
                                settingsOpen = true
                            },
                            onExit = onExit,
                            exitOnBack = stack.size == 1,
                            onExitNotice = {
                                noticeText = uiContext.getString(R.string.Tv_ExitDoublePress)
                                noticeCount++
                            },
                            onClose = { menuOpen = false },
                        )
                    }
                    if (settingsOpen && collectionUp) {
                        SettingsPanel(
                            backdrop = backdrop,
                            settings = settings,
                            account = SettingsAccount(
                                name = ownFace?.name ?: signedIn.displayName.ifBlank { signedIn.email },
                                email = signedIn.email,
                                supporter = ownQuota?.supporter,
                                tester = ownQuota?.tester == true,
                                // The music stops with the session: the next person's TV is not playing this one's.
                                signOut = {
                                    settingsOpen = false
                                    controller?.pause()
                                    services.scope.launch { services.auth.signOut() }
                                },
                            ),
                            region = services.region,
                            onClose = { settingsOpen = false },
                        )
                    }
                    // The keys of the screen just opened, in a corner the mini player is not in.
                    if (collectionUp && !menuOpen && !settingsOpen) {
                        val miniHere = settings.miniPlace.takeIf { route.hasMiniPlayer && it != MiniPlace.Off }
                        ControlsLegend(legend, backdrop, settings.albumCarousel, Modifier.align(legendCorner(miniHere)).padding(34.px))
                    }
                    // A shared collection put down while it was open: the person is back on their own, and told why.
                    Flash(noticeText, noticeCount, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.px))
                }
            }
            SideEffect { backdrop.share = if (motion >= Motion.TwoThirds) 2f / 3f else 1f }

            // The mini player, over the screens at the screen's own size rather than in their layer: its glass is
            // drawn again on every frame ([LiveGlass]), and inside the screens' layer that made the layer draw
            // again from the glass down to whatever else had changed, most of the screen on every frame of a
            // drifting card. The side menu and the settings panel, which are in the screens' layer, cover it.
            if (collectionUp && route.hasMiniPlayer && settings.miniPlace != MiniPlace.Off) {
                if (menuOpen || settingsOpen) {
                    DisposableEffect(mini) {
                        mini.cover()
                        onDispose { mini.uncover() }
                    }
                }
                MiniPlayer(
                    state = mini,
                    showsMark = route == Route.Home,
                    nowPlaying = nowPlaying,
                    images = services.images,
                    backdrop = backdrop,
                    place = settings.miniPlace,
                    below = screensFocus,
                    onOpen = { playerShown = true },
                    onLeft = {
                        if (!menuOpen && !settingsOpen && edgeOpensSideMenu(route, radioFront.inFront)) {
                            menuOpen = true
                            swallowLeft = true
                        }
                    },
                    modifier = Modifier.align(settings.miniPlace.alignment).graphicsLayer { alpha = screensAlpha.value },
                )
            }

            if (settings.setupPending) {
                // The set-up after a first sign-in, over the app while the collection loads and home is built under
                // it. Done, the resolution chosen is applied, and the opening screen is skipped when everything is
                // already in; otherwise it stands until it is.
                FirstSetup(
                    name = ownFace?.name ?: signedIn.displayName.ifBlank { signedIn.email },
                    settings = settings,
                    region = services.region,
                    guessedCountry = services.radio.listenerCountry(),
                    supporter = ownFace?.supporter,
                    onDone = {
                        settings.endSetup()
                        settings.applyResolution()
                        afterSetup = true
                        if (handOver) opening = false
                    },
                )
            } else if (opening) {
                val shown by animateFloatAsState(
                    if (fading) 0f else 1f,
                    tween(OPENING_FADE_MS, easing = QuadInOut),
                    label = "opening",
                    finishedListener = { if (fading) opening = false },
                )
                OpeningSplash(
                    ready = handOver,
                    reading = (libraryState as? LibraryState.Loading)?.rows ?: 0,
                    onSwept = {
                        Diag.d(OPENING_LOG, "handed over at ${sinceStart()} ms, library ${libraryState::class.simpleName}")
                        fading = true
                        // Home fading in, as on the desktop when its window is shown. Not over a failure.
                        if (libraryState is LibraryState.Ready) openingSound.play()
                    },
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = shown },
                )
            }
            // The performance stats, over everything and at the screen's own size, while they are switched on
            // in the settings panel.
            if (settings.showStats) {
                StatsOverlay(Modifier.align(Alignment.TopStart).padding(start = 12.px, top = 12.px))
            }
        }
    }
    }
}

/**
 * Every screen on [stack], once the library is ready, each in a [StackedScreen]: the top one placed and drawn
 * while [shown], the ones under it composed and not drawn (see [ScreenStack]). The top one tells the app which
 * picture the room should be painted in ([onPicture]): a record or list page's own, else none, which leaves the
 * playing one.
 */
@Composable
private fun Screens(
    services: Services,
    stack: ScreenStack,
    /** False while the player is up over the screens: then none of them is on top. */
    shown: Boolean,
    controller: Player?,
    nowPlaying: NowPlaying?,
    mini: MiniPlayerState,
    radioFront: RadioFront,
    backdrop: Backdrop,
    ownFace: com.rpm.player.core.library.Face?,
    wallSearch: WallSearchState,
    /** Whether the albums screen is the carousel ([PlayerSettings.albumCarousel]). */
    albumCarousel: Boolean,
    /** The albums screen's order, and where a new pick is kept ([PlayerSettings.albumOrder]). */
    albumOrder: WallOrder,
    onAlbumOrder: (WallOrder) -> Unit,
    browse: BrowseState,
    play: (List<Queued>, Int) -> Unit,
    back: () -> Unit,
    onPicture: (Any?) -> Unit,
    /** Paints the room in these colours instead of a picture's, or null for the picture. */
    onPalette: (RoomPalette?) -> Unit,
    onOpenPlayer: () -> Unit,
    /** Shows a line along the foot of the screen for a moment: what a menu's write came to. */
    flash: (String) -> Unit,
) {
    val heard by services.radio.listens.top.collectAsStateWithLifecycle()
    val rules by services.radio.rules.collectAsStateWithLifecycle()
    // The rules once at start, at most hourly: home's stations are drawn through them (hidden ones out,
    // the admin's edits on), and the radio screen, which reads them too, may never be opened.
    LaunchedEffect(Unit) { services.radio.refreshRules() }
    // Tunes a station and opens the player. Before the controller has connected there is nothing to play
    // on.
    fun tune(station: RadioStation) {
        controller?.let {
            it.tune(station, rules.logoStamp, station.detail(java.util.Locale.getDefault()))
            onOpenPlayer()
            services.scope.launch { services.radio.browser.click(station.uuid) }
        }
    }
    // Start radio: the song plays now, the queue keeps filling with songs like it, and the player opens, as for
    // any other play.
    fun startRadio(song: Queued) {
        controller?.let {
            it.startRadio(song)
            onOpenPlayer()
        }
    }
    // The tuner tunes in place, with no player over it.
    fun tuneHere(station: RadioStation) {
        controller?.let {
            it.tune(station, rules.logoStamp, station.detail(java.util.Locale.getDefault()))
            services.scope.launch { services.radio.browser.click(station.uuid) }
        }
    }
    // Stops the station and lets go of its stream. The station stays the queue's one item, so Play on
    // the remote tunes it again.
    fun stopStation() {
        controller?.let {
            it.pause()
            it.stop()
        }
    }
    LibraryGate(services.library) { ready ->
        val collection = ready.collection
        if (albumCarousel) CarouselWarmUp(collection, albumOrder, services.images)
        // Kept across recompositions, so home is not redrawn each time the playing position moves
        // (twice a second while playing).
        val homeActions = remember(ready, controller) {
            HomeActions(
                openAlbum = { album -> stack.push(Route.Record(album.key)) },
                openList = { list -> stack.push(Route.OfList(list.id)) },
                playSong = { song -> play(listOf(queuedOf(song, collection)), 0) },
                playSongs = { songs -> play(songs.map { queuedOf(it, collection) }, 0) },
                tune = { station -> tune(station) },
                openPlayer = onOpenPlayer,
                startRadio = { pick -> startRadio(queuedOf(pick, collection)) },
            )
        }
        val context = LocalContext.current
        val trackActions = remember(controller) {
            TrackActions(
                play = play,
                playNext = { tracks -> controller?.playNext(tracks) },
                addToQueue = { tracks -> controller?.addToQueue(tracks) },
                startRadio = { q -> startRadio(q) },
                // The writes run in the app's scope, so leaving the page does not cut one off half made.
                mark = { q, favorite ->
                    services.scope.launch {
                        val title = q.track.title.orEmpty()
                        val saved = services.ownWriter.mark(q.track.rpmId, favorite)
                        flash(
                            when {
                                saved && favorite -> context.getString(R.string.Viz_FavoriteAdded, title)
                                saved -> context.getString(R.string.Viz_FavoriteRemoved, title)
                                favorite -> context.getString(R.string.Tv_FavoriteFailed)
                                else -> context.getString(R.string.Tv_UnfavoriteFailed)
                            },
                        )
                    }
                },
                fileIn = { q, list ->
                    val name = (list.name as? ListName.Text)?.text.orEmpty()
                    services.scope.launch {
                        flash(
                            when (services.ownWriter.addToPlaylist(q.track.rpmId, list.storeId)) {
                                Filed.Added -> context.getString(R.string.Viz_PlaylistAdded, q.track.title.orEmpty(), name)
                                Filed.AlreadyIn -> context.getString(R.string.Rpm_PlaylistPick_AlreadyIn, name)
                                Filed.Failed -> context.getString(R.string.Rpm_PlaylistPick_Failed)
                            },
                        )
                    }
                },
                fileNew = { q, name ->
                    services.scope.launch {
                        flash(
                            if (services.ownWriter.createPlaylist(q.track.rpmId, name)) context.getString(R.string.Viz_PlaylistAdded, q.track.title.orEmpty(), name.trim())
                            else context.getString(R.string.Rpm_Playlists_CreateFailed),
                        )
                    }
                },
                markAlbum = { album, favorite ->
                    services.scope.launch {
                        val saved = services.albumFavourites.mark(album, favorite)
                        flash(
                            when {
                                saved && favorite -> context.getString(R.string.Viz_FavoriteAdded, album.album)
                                saved -> context.getString(R.string.Viz_FavoriteRemoved, album.album)
                                favorite -> context.getString(R.string.Tv_AlbumFavoriteFailed)
                                else -> context.getString(R.string.Tv_AlbumUnfavoriteFailed)
                            },
                        )
                    }
                },
                rateAlbum = { album, stars -> services.albumRatings.rate(album, stars) },
            )
        }
        val top = stack.top
        stack.entries.forEach { entry ->
            key(entry.id) {
                val onTop = shown && entry == top
                StackedScreen(onTop, entry.focus) {
                    val route = entry.route
                    // An artist's page hands up its photograph once it has one (ArtistPage's onPicture).
                    var artistPicture by remember { mutableStateOf<Any?>(null) }
                    if (onTop) {
                        val picture: Any? = when (route) {
                            is Route.Record -> collection.album(route.albumKey)?.let(collection::cover)
                            is Route.OfList -> collection.list(route.listId)?.let(collection::cover)
                            is Route.OfArtist -> artistPicture
                            else -> null
                        }
                        val palette: RoomPalette? = (route as? Route.OfGenre)?.let { genreRoom(com.rpm.player.core.library.GenreMarks.of(it.name)) }
                        SideEffect {
                            onPicture(picture)
                            onPalette(palette)
                        }
                    }
                    when (route) {
                        Route.Home -> HomeScreen(
                            collection = collection,
                            home = ready.home,
                            images = services.images,
                            artwork = services.artwork,
                            radio = HomeRadio(
                                stations = rules.shown(heard.map { it.station }).take(HOME_STATIONS),
                                logoStamp = rules.logoStamp,
                                blurbs = services.stationBlurbs,
                                playing = nowPlaying?.station,
                            ),
                            mini = mini,
                            actions = homeActions,
                            tracks = trackActions,
                        )
                        Route.Albums -> AlbumsScreen(
                            collection = collection,
                            images = services.images,
                            carousel = albumCarousel,
                            order = albumOrder,
                            onOrder = onAlbumOrder,
                            searchState = wallSearch,
                            backdrop = backdrop,
                            mini = mini,
                            favouriteAlbums = services.albumFavourites,
                            ratings = services.albumRatings,
                            actions = trackActions,
                            onOpenAlbum = homeActions.openAlbum,
                        )
                        Route.Search -> SearchScreen(
                            collection = collection,
                            walls = ready.walls,
                            radio = services.radio,
                            rules = rules,
                            images = services.images,
                            artwork = services.artwork,
                            actions = trackActions,
                            onOpenArtist = { artist ->
                                browse.openedAlbum = null
                                stack.push(Route.OfArtist(artist.name))
                            },
                            onOpenAlbum = homeActions.openAlbum,
                            onTune = ::tune,
                        )
                        Route.Playlists -> PlaylistsScreen(collection, services.images, homeActions.openList)
                        Route.Computers -> ComputersScreen(
                            computers = services.computers,
                            shared = services.shared,
                            own = ownFace,
                            images = services.images,
                            open = collection.shelf,
                            backdrop = backdrop,
                            onOpen = { shelf ->
                                // Another collection opens on its home, as the desktop's switch does.
                                services.library.open(shelf)
                                stack.newHome()
                            },
                        )
                        Route.Radio -> RadioScreen(
                            radio = services.radio,
                            blurbs = services.stationBlurbs,
                            images = services.images,
                            playing = nowPlaying?.station,
                            tuned = nowPlaying?.station?.takeIf { nowPlaying.playing || it.reconnecting },
                            mini = mini,
                            backdrop = backdrop,
                            front = radioFront,
                            onTune = { station, _ -> tune(station) },
                            onTuneHere = ::tuneHere,
                            onStop = ::stopStation,
                        )
                        // A page whose album or list the collection no longer holds (a new load replaced it) shows
                        // nothing; Back leaves it.
                        is Route.Record -> collection.album(route.albumKey)?.let {
                            TrackPage(
                                PageOf.OfAlbum(it), collection, services.images, services.dossiers, services.albumFavourites, services.albumRatings, trackActions,
                                classical = services.classicalMarks.isClassical(collection.shelf, it),
                            )
                        }
                        is Route.OfList -> collection.list(route.listId)?.let {
                            TrackPage(PageOf.OfList(it), collection, services.images, services.dossiers, services.albumFavourites, services.albumRatings, trackActions)
                        }
                        // The artists and genres screens. A page opened from them starts with no record to come back to.
                        Route.Artists -> ArtistsScreen(
                            walls = ready.walls,
                            collection = collection,
                            images = services.images,
                            artwork = services.artwork,
                            state = browse,
                            mini = mini,
                            onOpenArtist = { artist ->
                                browse.openedAlbum = null
                                stack.push(Route.OfArtist(artist.name))
                            },
                        )
                        Route.Genres -> GenresScreen(
                            walls = ready.walls,
                            images = services.images,
                            state = browse,
                            mini = mini,
                            onOpenGenre = { genre ->
                                browse.openedAlbum = null
                                stack.push(Route.OfGenre(genre.name))
                            },
                        )
                        is Route.OfArtist -> ready.walls.artist(route.name)?.let { artist ->
                            ArtistPage(
                                artist = artist,
                                walls = ready.walls,
                                collection = collection,
                                images = services.images,
                                artwork = services.artwork,
                                dossiers = services.dossiers,
                                edits = services.artistEdits,
                                worldPlays = services.worldPlays,
                                state = browse,
                                actions = trackActions,
                                onOpenAlbum = homeActions.openAlbum,
                                onOpenArtist = { other ->
                                    browse.openedAlbum = null
                                    stack.push(Route.OfArtist(other.name))
                                },
                                onOpenList = homeActions.openList,
                                onPicture = { shown -> artistPicture = shown },
                            )
                        }
                        is Route.OfGenre -> ready.walls.genre(route.name)?.let { genre ->
                            GenrePage(
                                genre = genre,
                                walls = ready.walls,
                                collection = collection,
                                images = services.images,
                                artwork = services.artwork,
                                dossiers = services.dossiers,
                                worldPlays = services.worldPlays,
                                state = browse,
                                actions = trackActions,
                                onOpenAlbum = homeActions.openAlbum,
                                onOpenArtist = { artist ->
                                    browse.openedAlbum = null
                                    stack.push(Route.OfArtist(artist.name))
                                },
                                onOpenList = homeActions.openList,
                                onOpenGenre = { other ->
                                    browse.openedAlbum = null
                                    stack.push(Route.OfGenre(other.name))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** About the colour of the room behind a panel: its ground a third of the way to its tint, at the 0.62
 * the room is drawn at. */
private fun roomGround(room: RoomPalette): androidx.compose.ui.graphics.Color {
    fun mix(g: Float, t: Float) = ((g + (t - g) * 0.35f) * 0.62f).coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(mix(room.ground.r, room.tint.r), mix(room.ground.g, room.tint.g), mix(room.ground.b, room.tint.b))
}

/** The still of the live room under a capture is made at this share of the screens' size: it is shrunk and blurred
 * with them, so more would only cost time in the room's GL context while the menu waits for it. */
private const val UNDER_SHRINK = 4

/** The stations heard most that home shows, as the desktop's quick selection takes them (top seven). */
private const val HOME_STATIONS = 7

/**
 * The side menu's entries, as the Apple TV's sidebar lists them: Now playing while something is loaded, then Home
 * and the screens.
 */
private fun menuEntries(playing: Boolean): List<MenuEntry> = buildList {
    if (playing) add(MenuEntry(Fa.WAVEFORM, R.string.Atv_Tab_NowPlaying, MenuGo.Player))
    add(MenuEntry(Fa.HOUSE, R.string.Atv_Tab_Home, MenuGo.Screen(Route.Home)))
    add(MenuEntry(Fa.SEARCH, R.string.Viz_MenuSearch, MenuGo.Screen(Route.Search)))
    add(MenuEntry(Fa.RECORD, R.string.Viz_MenuAlbums, MenuGo.Screen(Route.Albums)))
    add(MenuEntry(Fa.USER, R.string.Viz_MenuArtists, MenuGo.Screen(Route.Artists)))
    add(MenuEntry(Fa.MUSIC, R.string.Viz_MenuGenres, MenuGo.Screen(Route.Genres)))
    add(MenuEntry(Fa.PLAYLISTS, R.string.Viz_MenuPlaylists, MenuGo.Screen(Route.Playlists)))
    add(MenuEntry(Fa.RADIO, R.string.Viz_MenuRadio, MenuGo.Screen(Route.Radio)))
}

/** The screens the mini player floats over: home, the wall, radio and the artists and genres lists, as on
 * the desktop. Not the search, which covers the screen as the station search does. */
private val Route.hasMiniPlayer: Boolean
    get() = this == Route.Home || this == Route.Albums || this == Route.Radio || this == Route.Artists || this == Route.Genres

/** The screens where the Menu key opens the side menu: home, the search, the album wall, the playlists, the
 * computers and the artists and genres lists. On radio the Menu key opens a station's menu, and Left held for 2
 * seconds opens the side menu. On the search a row's menu is a held OK, as on the album wall. */
private fun sideMenuFrom(route: Route): Boolean =
    route == Route.Home || route == Route.Search || route == Route.Albums || route == Route.Playlists || route == Route.Computers ||
        route == Route.Artists || route == Route.Genres

/** The screens where Left with nowhere further left to go opens the side menu: those where the Menu key opens
 * it, and radio while its stations are in front (the desktop's TakeEdgeKey). */
private fun edgeOpensSideMenu(route: Route, radioInFront: Boolean): Boolean =
    sideMenuFrom(route) || (route == Route.Radio && radioInFront)

/** The fewest albums home is opened on after the set-up; a smaller collection opens on the albums screen. */
private const val HOME_FROM_ALBUMS = 30

/** How long Left is held on home before the side menu opens (the desktop's `SideHoldMs`). */
private const val SIDE_HOLD_MS = 2_000L

/** How long the screens take to fade out for the screensaver, and to come back on a key. */
private const val SCREENSAVER_OUT_MS = 1_500
private const val SCREENSAVER_IN_MS = 400

/** Fades what it is put on by [alpha], read when it is drawn, so the swap between the player and the screens does not
 * compose them again on every frame of it. The alpha is applied to each draw, with no layer of its own: a whole screen
 * through an offscreen layer is more than the Amiko's GPU draws in a frame. */
private fun Modifier.swapFade(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
}
