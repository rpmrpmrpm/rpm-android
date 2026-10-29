package com.rpm.player.phone.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import com.rpm.player.core.R
import com.rpm.player.core.Services
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.MarkedSongs
import com.rpm.player.core.library.CloudAlbum
import com.rpm.player.core.library.GenreWallItem
import com.rpm.player.core.library.TrackList
import com.rpm.player.core.library.listName
import com.rpm.player.core.playback.Queued
import com.rpm.player.core.playback.TrackRef
import com.rpm.player.core.playback.tune
import com.rpm.player.core.playback.startRadio
import com.rpm.player.core.playback.queueOf
import com.rpm.player.core.playback.QueueQuestion
import com.rpm.player.core.playback.QueueStart
import com.rpm.player.core.playback.Replacing
import com.rpm.player.core.playback.Tapped
import com.rpm.player.core.playback.insertNext
import com.rpm.player.core.playback.replacing
import com.rpm.player.core.playback.start
import com.rpm.player.core.library.Filed
import com.rpm.player.phone.design.ActionSheet
import com.rpm.player.phone.design.Backdrop
import com.rpm.player.phone.design.LocalOverChrome
import com.rpm.player.phone.design.OverChromeHost
import com.rpm.player.phone.design.OverChromeLayer
import com.rpm.player.phone.design.DialogKey
import com.rpm.player.phone.design.KeyRole
import com.rpm.player.phone.library.LocalSongMenu
import com.rpm.player.phone.library.PlaylistPickSheet
import com.rpm.player.phone.library.SongMenu
import com.rpm.player.phone.library.SongMenuLayer
import com.rpm.player.phone.library.SongMenuShown
import com.rpm.player.phone.library.UpNext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import com.rpm.player.core.playback.rememberNowPlaying
import com.rpm.player.phone.PhoneSettings
import com.rpm.player.phone.design.GlassButton
import com.rpm.player.phone.design.Ink
import com.rpm.player.phone.design.backdropSource
import com.rpm.player.phone.design.rememberBackdrop
import com.rpm.player.phone.design.system
import com.rpm.player.phone.home.HomeActions
import com.rpm.player.phone.home.HomePage
import com.rpm.player.phone.home.LibraryPicker
import com.rpm.player.phone.home.ShelfRow
import com.rpm.player.phone.library.LibraryMode
import com.rpm.player.phone.library.WallReading
import com.rpm.player.phone.library.AlbumPage
import com.rpm.player.phone.library.ArtistPage
import com.rpm.player.phone.library.GenrePage
import com.rpm.player.phone.radio.RadioPage
import com.rpm.player.phone.profile.ProfileTab
import com.rpm.player.phone.player.NowPlayingSheet
import com.rpm.player.phone.player.OnListeningEnded
import com.rpm.player.phone.player.PutAwayWhenPlayedOut
import com.rpm.player.phone.player.rememberPlayerWiring
import com.rpm.player.phone.library.LibraryPage
import com.rpm.player.phone.library.FramedList
import com.rpm.player.phone.library.WallStanding
import com.rpm.player.core.library.CoverIndex
import com.rpm.player.core.library.Home
import com.rpm.player.core.library.Walls
import com.rpm.player.core.library.shelf
import com.rpm.player.phone.library.PlaylistPage
import com.rpm.player.phone.library.RereadListsOnReturn
import com.rpm.player.phone.library.rememberPlaylistWrites
import com.rpm.player.phone.library.QueuePage
import com.rpm.player.phone.library.RecordArtists
import com.rpm.player.phone.library.ReviewWalls
import com.rpm.player.phone.player.rememberQueue
import com.rpm.player.phone.signin.SignIn
import com.rpm.player.phone.signin.SignInScreen
import com.rpm.player.phone.ui.RecordSpinner
import com.rpm.player.phone.ui.VinylGround
import com.rpm.player.phone.downloads.DownloadsPage
import com.rpm.player.phone.downloads.LocalPlayable
import com.rpm.player.phone.downloads.OfflineBar
import com.rpm.player.phone.downloads.rememberDownloadsHere
import com.rpm.player.phone.stage.StageOrientation
import com.rpm.player.phone.stage.StageOrientationFollows
import com.rpm.player.phone.stage.StageView
import com.rpm.player.phone.player.Speakers
import com.rpm.player.phone.stage.findActivity
import com.rpm.player.phone.stage.rememberTurned
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.diag.FeedbackShot
import com.rpm.player.core.diag.WindowShot
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.platform.LocalContext

/**
 * The first screen of the app, as the iPhone's `RootView`. When signed out, the sign-in gate is shown. When
 * signed in, a wait screen is shown while the library is read. Then the app is shown.
 *
 * The session and the library are read each time either one changes. The state is never stored. So a sign-out
 * from anywhere shows the gate, and a library that finishes loading shows the app. Whichever happens first,
 * the screen follows it.
 */
@Composable
fun Root(services: Services, settings: PhoneSettings, signIn: SignIn, controller: Player?, sittingFrom: Int) {
    val session by services.auth.session.collectAsStateWithLifecycle()
    val library by services.library.state.collectAsStateWithLifecycle()

    val shown = library
    // Whether the read being waited for is a retry of one that failed. The app stays up for it, with the wall waiting,
    // as the iPhone's does: a retry from the library tab does not throw the person back to the launch screen.
    var afterFailure by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        afterFailure = when (shown) {
            is LibraryState.Failed -> true
            is LibraryState.Loading -> afterFailure
            else -> false
        }
    }
    val reach by (LocalContext.current.applicationContext as com.rpm.player.phone.PhoneApplication).line.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        when {
            session == null -> SignInScreen(signIn)
            // A library that could not be read still opens the app, as on iPhone (`AccountStanding.showing` with the wall's
            // `ReadStanding.trouble`): the radio works without it, and the library tab says what went wrong and offers a
            // retry. One call for all three states, so the tab and the stacks stay as they were across the retry.
            shown is LibraryState.Ready || shown is LibraryState.Failed || (shown is LibraryState.Loading && afterFailure) -> {
                val ready = (shown as? LibraryState.Ready) ?: remember(shown.shelf) { nothingRead(shown.shelf) }
                val wall = when (shown) {
                    is LibraryState.Ready -> WallStanding.Showing
                    is LibraryState.Failed -> WallStanding.Trouble
                    else -> WallStanding.Reading
                }
                Shell(services, settings, ready, controller, sittingFrom, wall)
            }
            // The words say "your library", which is wrong over somebody else's: the record alone says the app is waiting.
            shown is LibraryState.Loading -> Waiting(if (shown.shelf != Shelf.Own) null else stringResource(R.string.Rpm_Library_Reading))
            // Signed in, before the library has started: the iPhone's `starting`, the record alone.
            else -> Waiting(null)
        }
        // Over every screen, the sign-in form and the wait included: the missing connection is why any of them can fail,
        // and a notice only on the library would never be seen by somebody signing in. Once the app shows, it says what
        // still plays.
        val showing = session != null && (shown is LibraryState.Ready || shown is LibraryState.Failed || afterFailure)
        OfflineBar(
            showing = !reach.isOnline,
            detail = if (showing) stringResource(R.string.Rpm_Net_OfflineDownloads) else null,
        )
    }
}

/** A collection with nothing in it, which the app is drawn around while the library could not be read. */
private fun nothingRead(shelf: Shelf) = LibraryState.Ready(
    com.rpm.player.core.library.Collection.of(shelf, emptyList(), emptyMap(), emptyMap(), emptyList(), CoverIndex.Unknown),
    Home(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
    Walls(emptyList(), emptyList()),
)

/**
 * The app, once there is something to show: a page, with the chrome floating over its bottom edge. The
 * chrome's glass blurs the page under it, so the page is the backdrop. Each page is told how tall the chrome
 * is, so its last row can scroll clear of it.
 *
 * The library and home each keep their own stack of opened records and lists ([Step]). A step stores a name,
 * not the data, so the page is drawn from the collection on every pass. When the collection is read again
 * under an open page, the page redraws with the new rows. This is the iPhone's `LibraryStep`. A page that is
 * opened slides in from the right, as a push does on iPhone. Each page keeps its own saved state. So the wall
 * stays where the user left it when the record is put away.
 *
 * The app opens on home, as on iPhone.
 */
@Composable
private fun Shell(services: Services, settings: PhoneSettings, ready: LibraryState.Ready, controller: Player?, sittingFrom: Int, wall: WallStanding) {
    val backdrop = rememberBackdrop()
    val session by services.auth.session.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var opened by rememberSaveable(stateSaver = Step.stack) { mutableStateOf(listOf<Step>()) }
    var homeOpened by rememberSaveable(stateSaver = Step.stack) { mutableStateOf(listOf<Step>()) }
    var playerUp by rememberSaveable { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(0.dp) }
    val pages = rememberSaveableStateHolder()
    val now = rememberNowPlaying(controller)
    val playing = now?.mediaId?.let { TrackRef.parse(it)?.rpmId }
    val collection = ready.collection
    val images = services.images
    val queue = rememberQueue(controller, collection)
    // The marked songs exist only on the person's own shelf. Another account's songs cannot be marked by this one.
    val marked = remember(collection) { if (collection.shelf == Shelf.Own) MarkedSongs.of(collection.albums) else null }
    val heard by services.radio.listens.top.collectAsStateWithLifecycle()
    // The launch's wait cover (`covering` and `homeReady` on iPhone). The shell is new for each collection shown, so a
    // switch to another collection is covered the same way.
    var homeReady by remember { mutableStateOf(false) }
    var covering by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(COVER_FIRST_MS)
        withTimeoutOrNull(HOME_WAITS_AT_MOST_MS) { snapshotFlow { homeReady }.first { it } }
        covering = false
    }
    // Whether a pull on a wall is reading the collection again.
    val refreshing by services.library.refreshing.collectAsStateWithLifecycle()
    // The collections this account can show: its own, and those shared with it (`shelfRows` on iPhone).
    var picking by rememberSaveable { mutableStateOf(false) }
    val shared by services.shared.list.collectAsStateWithLifecycle()
    val ownFace by services.ownFace.face.collectAsStateWithLifecycle()
    val ownName = stringResource(R.string.Rpm_Share_OwnLibrary)
    val unknownName = stringResource(R.string.Rpm_Share_Unknown)
    val shelfRows = remember(shared, ownFace, collection.shelf, ownName, unknownName) {
        ShelfRow.of(services.shared.rows(collection.shelf), collection.shelf, ownFace, ownName, unknownName)
    }
    val rules by services.radio.rules.collectAsStateWithLifecycle()
    // The station rules are read once at start, and again at most once an hour. Home's stations are filtered through
    // them.
    LaunchedEffect(Unit) { services.radio.refreshRules() }
    val stations = remember(heard, rules) { rules.shown(heard.map { it.station }).take(HOME_STATIONS) }
    val playlistWrites = rememberPlaylistWrites(services)
    RereadListsOnReturn(services)
    val downloads = rememberDownloadsHere()
    var showingDownloads by rememberSaveable { mutableStateOf(false) }

    // ── Send feedback ──

    // Only a beta build offers it (ReportScope). The screenshot is taken first, while the screen being reported is still
    // showing, and the page opens over it: from the profile's key, or from a shake on any screen.
    val shotActivity = LocalContext.current.findActivity()
    val shotScope = rememberCoroutineScope()
    var feedbackShot by remember { mutableStateOf<FeedbackShot?>(null) }
    val openFeedback: (() -> Unit)? = if (!services.diagnostics.offersFeedback || shotActivity == null) null else {
        { if (feedbackShot == null) shotScope.launch { feedbackShot = FeedbackShot(WindowShot.capture(shotActivity)) } }
    }
    openFeedback?.let { ShakeWatch(onShake = it) }

    // ── what can be done to a song or a record from anywhere ──

    // The question asked before a tap replaces a queue the person changed, while it is on screen (the iPhone's
    // `queueQuestion`). It is here and not on each page, because a tap on every page reaches [play].
    var queueQuestion by remember { mutableStateOf<QueueQuestion?>(null) }
    // The song the playlist picker is open for, or null while it is closed (the iPhone's `filing`). The song and not a
    // flag, because the picker needs to know only which song it is filing.
    var filing by remember { mutableStateOf<CloudTrack?>(null) }
    // Opens the playlist picker for any song, from anywhere: the song menu, and the now playing sheet's own key.
    val file: (CloudTrack) -> Unit = { filing = it }
    val favouriteRecords by services.albumFavourites.marks.collectAsStateWithLifecycle()
    val songMenus = remember { SongMenuShown() }
    // The long-press menu on a song outside the queue, handed down to every page that lists songs. Made again only when
    // the collection or the controller changes, because a new menu redraws every row that reads it.
    val songMenu = remember(collection, controller) {
        SongMenu(
            isMarked = { collection.tracksById[it.rpmId]?.tags?.favorite == true },
            // A song is this account's when its own collection holds it. Another account's songs cannot be marked or
            // filed by this one.
            isMine = { collection.shelf == Shelf.Own && collection.tracksById.containsKey(it.rpmId) },
            // Nothing starts, as on iPhone (`AppModel.playNext`): into an empty queue the song waits, paused.
            upNext = UpNext.Send { track -> controller?.insertNext(listOf(queued(collection, track))) },
            // The writes run in the app's scope, so leaving the page does not cut one off half made.
            onMark = { track, on -> services.scope.launch { services.ownWriter.mark(track.rpmId, on) } },
            onFile = file,
            // A radio replaces the queue without asking, as on iPhone: it is a new thing to listen to, not a list
            // to keep the old one beside.
            startRadio = { track -> controller?.startRadio(queued(collection, track)) },
            shown = songMenus,
        )
    }
    val queueMenu = remember(songMenu) { songMenu.with(UpNext.ItIsTheQueue) }

    // Any list of songs from outside the queue: a record, a list, the marked songs. It replaces the queue, or asks
    // first if the person changed the songs still to come (the iPhone's `AppModel.play`).
    fun play(queue: List<Queued>, from: Int, tapped: Tapped) {
        val player = controller ?: return
        when (val replacing = player.replacing(queue, from, tapped)) {
            is Replacing.Clear -> player.start(replacing.start)
            is Replacing.Asks -> queueQuestion = replacing.question
        }
    }

    // The record pages' reviews, one wall per record for the life of this collection, and the acts each record's
    // artist line opens.
    val reviewWalls = remember(collection.shelf) { ReviewWalls(services.community) }
    val recordArtists = remember(ready.walls) { RecordArtists(ready.walls) }

    // One record page. It is used for whichever tab's stack the record was opened on, and what it opens (a similar
    // record, the artist) goes on the same stack.
    @Composable
    fun record(album: CloudAlbum, push: (Step) -> Unit, onBack: () -> Unit) {
        val queue = { queueOf(collection.shelf, album, collection.cover(album), collection.disc(album)) }
        AlbumPage(
            album,
            collection.cover(album),
            images,
            playing,
            bottomRoom = chrome,
            onPlay = { at -> play(queue(), at, Tapped.Song) },
            onShuffle = { play(queue().shuffled(), 0, Tapped.List) },
            onBack = onBack,
            collection = collection,
            dossiers = services.dossiers,
            artists = remember(album, recordArtists) { recordArtists.of(album) },
            onArtist = { push(Step.Artist(it.name)) },
            onOpenAlbum = { push(Step.Record(it.key)) },
            classical = services.classicalMarks.isClassical(collection.shelf, album),
            wall = reviewWalls.of(album),
            me = session?.userId,
            onPlayAll = { play(queue(), 0, Tapped.List) },
            marked = favouriteRecords.isFavourite(album),
            onMark = { on -> services.scope.launch { services.albumFavourites.mark(album, on) } },
            download = downloads.key(album, collection.shelf),
            canPlay = downloads.canPlay,
        )
    }

    // The marked songs' page. This list has no picture, and its order is the order of the marks.
    @Composable
    fun songs(name: String, tracks: List<CloudTrack>, onBack: () -> Unit) {
        val queue = { tracks.map { queued(collection, it) } }
        PlaylistPage(
            name,
            tracks,
            missing = 0,
            blurb = null,
            picture = null,
            coverOf = { track -> collection.albumOf[track.rpmId]?.let { collection.cover(it) } },
            images = images,
            playing = playing,
            bottomRoom = chrome,
            onPlay = { at -> play(queue(), at, Tapped.Song) },
            onShuffle = { play(queue().shuffled(), 0, Tapped.List) },
            onBack = onBack,
            onPlayAll = { play(queue(), 0, Tapped.List) },
        )
    }

    // One list's page. It is the same for either stack.
    @Composable
    fun playlist(list: TrackList, onBack: () -> Unit) {
        val queue = { list.tracks.map { queued(collection, it) } }
        val shownName = listName(list.name)
        PlaylistPage(
            shownName,
            list.tracks,
            list.missing,
            list.blurb,
            collection.cover(list),
            coverOf = { track -> collection.albumOf[track.rpmId]?.let { collection.cover(it) } },
            images,
            playing,
            bottomRoom = chrome,
            onPlay = { at -> play(queue(), at, Tapped.Song) },
            onShuffle = { play(queue().shuffled(), 0, Tapped.List) },
            onBack = onBack,
            onPlayAll = { play(queue(), 0, Tapped.List) },
            framed = FramedList.of(list, shownName),
            // Another selection, only where the computer sent enough songs for one.
            onNewList = if (list.slices > 1) ({ services.library.newList(list) }) else null,
            adding = playlistWrites.adding(collection, list),
            canReorder = playlistWrites.canReorder(collection, list),
            onMove = { order -> playlistWrites.move(collection, list, order) },
        )
    }

    // One artist's page. It is the same for either stack. A record opened from it goes on the same stack.
    @Composable
    fun artist(artist: ArtistWallItem, push: (Step) -> Unit, onBack: () -> Unit) {
        ArtistPage(
            artist,
            collection,
            images,
            services.dossiers,
            services.worldPlays,
            ready.walls,
            services.artwork,
            bottomRoom = chrome,
            onPlay = { tracks, at -> play(tracks.map { queued(collection, it) }, at, Tapped.Song) },
            onOpen = { push(Step.Record(it.key)) },
            onOpenArtist = { push(Step.Artist(it.name)) },
            onBack = onBack,
            onPlayAll = { tracks -> play(tracks.map { queued(collection, it) }, 0, Tapped.List) },
        )
    }

    // One genre's page; what is opened from it goes on the same stack.
    @Composable
    fun genre(genre: GenreWallItem, push: (Step) -> Unit, onBack: () -> Unit) {
        GenrePage(
            genre,
            ready,
            images,
            services.artwork,
            bottomRoom = chrome,
            onOpen = { push(Step.Record(it.key)) },
            onPlay = { tracks -> play(tracks.map { queued(collection, it) }, 0, Tapped.List) },
            onArtist = { push(Step.Artist(it.name)) },
            onList = { push(Step.Playlist(it.id)) },
            onGenre = { push(Step.Genre(it)) },
            onBack = onBack,
        )
    }

    // The page on top of a stack, or [root] when nothing is pushed. If a step's record, list or artist is no
    // longer in the collection after a re-read, [root] is shown instead of an empty page.
    @Composable
    fun page(step: Step?, push: (Step) -> Unit, onBack: () -> Unit, root: @Composable () -> Unit) {
        val album = (step as? Step.Record)?.let { collection.album(it.key) }
        val list = (step as? Step.Playlist)?.let { collection.list(it.id) }
        val act = (step as? Step.Artist)?.let { ready.walls.artist(it.name) }
        val kind = (step as? Step.Genre)?.let { ready.walls.genre(it.name) }
        when {
            album != null -> record(album, push, onBack)
            list != null -> playlist(list, onBack)
            act != null -> artist(act, push, onBack)
            kind != null -> genre(kind, push, onBack)
            step == Step.Queue -> CompositionLocalProvider(LocalSongMenu provides queueMenu) {
                QueuePage(queue, sittingFrom, controller, images, bottomRoom = chrome, onBack = onBack)
            }
            step == Step.Marked && marked != null -> songs(stringResource(R.string.Playlists_FavoriteTracks), marked, onBack)
            else -> root()
        }
    }

    val homeActions = HomeActions(
        onOpen = { homeOpened = homeOpened + Step.Record(it.key) },
        onOpenList = { homeOpened = homeOpened + Step.Playlist(it.id) },
        onPlay = { tracks -> play(tracks.map { queued(collection, it) }, 0, Tapped.List) },
        onRadio = { track -> controller?.startRadio(queued(collection, track)) },
        onTune = { station ->
            controller?.let {
                it.tune(station, rules.logoStamp, station.detail(java.util.Locale.getDefault()))
                services.scope.launch { services.radio.browser.click(station.uuid) }
            }
        },
    )

    // The player's wiring: its lyrics, the speakers, the heart, and the way out when the listening ends.
    val wiring = rememberPlayerWiring(services, collection, now)
    PutAwayWhenPlayedOut(controller)
    // The listening ended, because the queue was stopped or played out: the player goes down, and the tab is the one the
    // music came from, the library for a record and the radio for a station. Home is never the answer.
    OnListeningEnded(controller, now) { wasTheRadio ->
        wiring.leaving = true
        tab = if (wasTheRadio) Tab.Radio else Tab.Library
    }

    // The stage: the phone turned while something is loaded. The app turns only while there is something to turn to,
    // and the player is put away when the stage opens, so turning back shows the tab it was opened from.
    StageOrientationFollows(loaded = now != null)
    val onStage = rememberTurned() && now != null && controller != null
    LaunchedEffect(onStage) { if (onStage) playerUp = false }

    // When the connection goes, the person's own records are shown, with the ones not on the phone greyed: a borrowed
    // collection cannot be read without a network. An open page of the own shelf stays open; its record may be on the
    // phone. The settled reading is used, the one the offline bar draws, so a short handover does not move the screen.
    val online = downloads.online
    var wasOnline by remember { mutableStateOf(online) }
    LaunchedEffect(online) {
        if (wasOnline && !online) {
            if (collection.shelf != Shelf.Own) {
                opened = emptyList()
                homeOpened = emptyList()
                services.library.open(Shelf.Own)
            }
            tab = Tab.Library
            settings.changeLibraryMode(LibraryMode.Albums)
        }
        wasOnline = online
    }

    // The pages' sheets are drawn over the chrome (`OverChrome`), as the iPhone's cover the tab bar and the mini player.
    val overChrome = remember { OverChromeHost() }
    CompositionLocalProvider(LocalPlayable provides downloads.playable, LocalOverChrome provides overChrome) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
          CompositionLocalProvider(LocalSongMenu provides songMenu) {
            when (tab) {
                Tab.Library -> AnimatedContent(
                    targetState = opened,
                    transitionSpec = { pushOrPop() },
                    label = "library stack",
                ) { stack ->
                    val step = stack.lastOrNull()
                    pages.SaveableStateProvider(step?.saved ?: "library") {
                        page(step, push = { opened = opened + it }, onBack = { opened = opened.dropLast(1) }) {
                            LibraryPage(
                                ready,
                                images,
                                settings,
                                bottomRoom = chrome,
                                artwork = services.artwork,
                                onOpen = { opened = opened + Step.Record(it.key) },
                                onOpenList = { opened = opened + Step.Playlist(it.id) },
                                onOpenArtist = { opened = opened + Step.Artist(it.name) },
                                onOpenGenre = { opened = opened + Step.Genre(it.name) },
                                queue = queue,
                                onOpenQueue = { opened = opened + Step.Queue },
                                marked = marked,
                                onOpenMarked = { opened = opened + Step.Marked },
                                playing = playing,
                                // A song found in search plays its record from that song. A single song would be a
                                // queue of one, and playback would stop after it. Nobody who searches for a song
                                // wants that. The iPhone app's `play(track)` does the same.
                                onPlaySong = { hit ->
                                    val album = hit.album
                                    val at = album.tracks.indexOfFirst { it.rpmId == hit.track.rpmId }
                                    if (at >= 0) play(queueOf(collection.shelf, album, collection.cover(album), collection.disc(album)), at, Tapped.Song)
                                },
                                onMakePlaylist = playlistWrites::make,
                                standing = wall,
                                onRetry = services.library::retry,
                                reading = WallReading(refreshing, services.library::refresh, shelfRows.firstOrNull { it.isOpen && !it.isOwn }?.name, services.library::rereadLists),
                            )
                        }
                    }
                }
                // Home has its own stack. A record opened here slides in over home and back out to it. The library's
                // stack is not changed by a trip that began on home.
                Tab.Home -> AnimatedContent(targetState = homeOpened, transitionSpec = { pushOrPop() }, label = "home stack") { stack ->
                    val step = stack.lastOrNull()
                    pages.SaveableStateProvider("home:" + (step?.saved ?: "")) {
                        page(step, push = { homeOpened = homeOpened + it }, onBack = { homeOpened = homeOpened.dropLast(1) }) {
                            HomePage(
                                ready,
                                stations,
                                rules.logoStamp,
                                images,
                                bottomRoom = chrome,
                                actions = homeActions,
                                onScreen = tab == Tab.Home && homeOpened.isEmpty(),
                                onSwitch = if (shelfRows.size > 1) ({ picking = true }) else null,
                                // The lock's key: the library, on its records.
                                onAddMusic = { tab = Tab.Library; settings.changeLibraryMode(LibraryMode.Albums) },
                                onReady = { homeReady = true },
                            )
                        }
                    }
                }
                Tab.Radio -> RadioPage(
                    services.radio,
                    images,
                    tuned = now?.station?.logo?.uuid,
                    said = now?.station?.said,
                    bottomRoom = chrome,
                    onTune = homeActions.onTune,
                    // The saved stations of a shared collection are its owner's, and are never changed from here.
                    onSave = { station -> if (services.radio.saved.value.mine) services.radio.favorites.toggle(station) },
                )
                Tab.Profile -> ProfileTab(
                    services,
                    settings,
                    controller,
                    bottomRoom = chrome,
                    downloaded = downloads.downloaded,
                    onDownloads = { showingDownloads = true },
                    onFeedback = openFeedback,
                )
            }
          }
        }
        Chrome(
            backdrop,
            showing = tab,
            onShow = { tab = it },
            miniPlayer = if (now != null && controller != null) {
                { MiniPlayer(now, controller, images, backdrop, onOpen = { wiring.leaving = false; playerUp = true }) }
            } else {
                null
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { chrome = with(density) { it.height.toDp() } },
        )
        OverChromeLayer(overChrome)
        // The wait cover stays over the first frames, then until home has drawn what it opens on, so the app shows a
        // finished page and not one filling in. Taps are held back while it is up.
        WaitCover(covering, words = collection.shelf == Shelf.Own)
        // Whose collection the app shows, over everything as on the iPhone. Opening one clears every stack: their
        // pages belong to the collection being put away.
        if (picking) {
            LibraryPicker(
                shelfRows,
                images,
                backdrop,
                onOpen = { shelf ->
                    picking = false
                    opened = emptyList()
                    homeOpened = emptyList()
                    services.library.open(shelf)
                },
                onClose = { picking = false },
            )
        }
        // Over everything, the tabs and the mini player included. It stays on the last thing played while it goes down
        // after the listening ended.
        val sheetNow = now ?: wiring.lastNow
        if (playerUp && sheetNow != null && controller != null) {
            val album = playing?.let { collection.albumOf[it] }
            val artist = playing?.let { wiring.artistOf(it, ready) }
            NowPlayingSheet(
                sheetNow,
                controller,
                images,
                queue,
                sittingFrom,
                lyrics = wiring.lyrics.sheet,
                lookingUpLyrics = wiring.lyrics.looking,
                onNeedLyrics = { wiring.readLyrics(sheetNow) },
                marked = wiring.isMarked(playing),
                onMark = playing?.let { id -> wiring.canMark(sheetNow).takeIf { it }?.let { { on: Boolean -> wiring.mark(id, on) } } },
                // The playlist picker, over everything, for the song playing. Not for a song on a borrowed shelf: a
                // playlist names this account's songs.
                onFile = playing?.let { collection.tracksById[it] }?.takeIf { wiring.canMark(sheetNow) }?.let { track -> { file(track) } },
                // The records are on the library's albums stack, so the page is pushed there.
                onAlbum = album?.let { { tab = Tab.Library; settings.changeLibraryMode(LibraryMode.Albums); opened = listOf(Step.Record(it.key)) } },
                // The artists view of the library, with the artist's page pushed on its stack.
                onArtist = artist?.let { { tab = Tab.Library; settings.changeLibraryMode(LibraryMode.Artists); opened = listOf(Step.Artist(it.name)) } },
                speakers = wiring.speakers,
                sleep = services.sleep,
                leaving = wiring.leaving,
                onClosed = {
                    playerUp = false
                    wiring.leaving = false
                },
            )
        }
        // The downloaded albums, risen over the profile tab as the iPhone's sheet.
        AnimatedVisibility(
            showingDownloads,
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) {
            DownloadsPage(
                downloads.rows,
                coverOf = { album -> collection.album(album.key)?.let(collection::cover) },
                images = images,
                onRemove = downloads.engine::remove,
                onClose = { showingDownloads = false },
            )
        }
        SongOverlays(
            services,
            collection,
            backdrop,
            songMenus,
            filing,
            onFiled = { filing = null },
            queueQuestion,
            onAnswered = { start ->
                queueQuestion = null
                start?.let { controller?.start(it) }
            },
        )
        // Over everything, the player included: the stage is the whole screen while the phone is turned.
        Stage(onStage, now, controller, services, wiring.speakers)
        Reports(services, feedbackShot, onFeedbackClosed = { feedbackShot = null })
    }
    }
}

/**
 * What is drawn over everything for a song or a queue: the open song menu, the playlist picker while a song is being
 * filed (the iPhone's `filing` overlay), and the question asked before a changed queue is replaced (its
 * `confirmationDialog`). Kept apart from [Shell], which only holds their state.
 *
 * @param onAnswered the start the answer to the queue question chose, or null for Cancel.
 */
@Composable
private fun SongOverlays(
    services: Services,
    collection: com.rpm.player.core.library.Collection,
    backdrop: Backdrop,
    songMenus: SongMenuShown,
    filing: CloudTrack?,
    onFiled: () -> Unit,
    queueQuestion: QueueQuestion?,
    onAnswered: (QueueStart?) -> Unit,
) {
    val context = LocalContext.current
    // The picker fades in and out over 0.18 s, as the iPhone's overlay does. The song is kept while it fades out, so the
    // panel does not go blank as it leaves.
    // Not state: it only follows [filing], which is.
    val filed = remember { object { var track: CloudTrack? = null } }
    if (filing != null) filed.track = filing
    AnimatedVisibility(filing != null, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        val track = filed.track ?: return@AnimatedVisibility
        key(track.rpmId) {
            PlaylistPickSheet(
                track,
                collection,
                backdrop,
                onAdd = { list, song, again ->
                    when (services.ownWriter.addToPlaylist(song.rpmId, list.storeId, again)) {
                        Filed.Added -> null
                        Filed.AlreadyIn -> context.getString(R.string.Rpm_PlaylistPick_AlreadyIn, listName(list.name, context.resources))
                        Filed.Failed -> context.getString(R.string.Rpm_PlaylistPick_Failed)
                    }
                },
                onClose = onFiled,
            )
        }
    }
    SongMenuLayer(songMenus, backdrop)
    queueQuestion?.let { question ->
        key(question) {
            ActionSheet(
                backdrop,
                stringResource(R.string.Rpm_Queue_DiscardTitle),
                stringResource(R.string.Rpm_Queue_DiscardMessage),
                listOf(
                    DialogKey(stringResource(R.string.Rpm_Queue_Discard), KeyRole.Destructive) { onAnswered(question.discard()) },
                    DialogKey(stringResource(R.string.Rpm_Queue_PlayNowKeep)) { onAnswered(question.keepQueue()) },
                ),
                cancel = DialogKey(stringResource(R.string.Common_Cancel), KeyRole.Cancel) { onAnswered(null) },
            )
        }
    }
}

/**
 * The stage over the app ([StageView]), faded in and out over a quarter of a second. What it shows is held through the
 * fade out, so a stage that closes because the music stopped does not go blank before it has faded.
 */
@Composable
private fun Stage(shown: Boolean, now: NowPlaying?, controller: Player?, services: Services, speakers: Speakers?) {
    val activity = LocalContext.current.findActivity()
    var held by remember { mutableStateOf(now) }
    if (now != null) held = now
    AnimatedVisibility(shown, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
        val playing = held
        if (playing != null && controller != null) {
            StageView(playing, controller, services.images, services.audioTap, speakers, onPortrait = { activity?.let(StageOrientation::requestPortrait) })
        }
    }
}

/**
 * The number of stations that home shows: the radio card and the quick selection. This is the same as the TV app's
 * seven.
 */
private const val HOME_STATIONS = 7

/**
 * A page that is opened slides in from the right, over the page it was opened from. That page moves back by a
 * third. When the page is put away, it slides back out over that page. This matches a push and a pop on the
 * iPhone's stacks.
 */
private fun AnimatedContentTransitionScope<List<Step>>.pushOrPop(): ContentTransform =
    if (targetState.size >= initialState.size) {
        slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
    } else {
        (slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }).apply { targetContentZIndex = -1f }
    }

/** One song to queue, with the names, cover and disc of the record it is on. */
private fun queued(collection: com.rpm.player.core.library.Collection, track: CloudTrack): Queued {
    val album = collection.albumOf[track.rpmId]
    return Queued(
        collection.shelf,
        track,
        album?.album ?: track.album.orEmpty(),
        album?.artist ?: track.artist.orEmpty(),
        album?.let { collection.cover(it) },
        album?.let { collection.disc(it) },
    )
}

/** How long the wait cover stays over the shell at least, and how much longer it waits at most for home's front card:
 * a photograph is a network request, and a slow one must not hold the app (`homeWaitsAtMost` on iPhone). */
private const val COVER_FIRST_MS = 400L
private const val HOME_WAITS_AT_MOST_MS = 2_100L

/**
 * The wait screen laid over the shell while it is [covering], fading out over 0.35 s when it stops. Its own composable,
 * so the fade redraws only the cover. The words are shown only over the person's own collection, as on iPhone.
 */
@Composable
private fun WaitCover(covering: Boolean, words: Boolean) {
    val shown by animateFloatAsState(if (covering) 1f else 0f, tween(if (covering) 150 else 350), label = "wait cover")
    if (shown <= 0f) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
    ) {
        Waiting(if (words) stringResource(R.string.Rpm_Library_Reading) else null)
    }
}

/** The wait screen: a spinning glass record, and what is being waited for. The iPhone's `waiting` view is the same. */
@Composable
private fun Waiting(what: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VinylGround()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            RecordSpinner(120.dp)
            // The footnote size in iOS's secondary label colour, as the iPhone draws it.
            if (what != null) BasicText(what, style = system(13f).copy(color = Ink.secondary))
            LaunchReadout()
        }
    }
}
