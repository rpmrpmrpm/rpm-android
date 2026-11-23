package com.rpm.player.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.rpm.player.core.RpmApplication
import com.rpm.player.core.Services
import com.rpm.player.core.SessionOpening
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.rpm.player.core.playback.AudioLadder
import androidx.compose.runtime.setValue
import com.rpm.player.core.library.AlbumOrdering
import com.rpm.player.core.library.BlankDisc
import com.rpm.player.core.library.DiscStandIn
import com.rpm.player.core.library.StarterTracks
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.radio.RadioStation
import kotlinx.serialization.builtins.ListSerializer
import com.rpm.player.core.library.ArtistOrdering
import com.rpm.player.core.library.ArtistTrackGrouping
import com.rpm.player.core.playback.EqCurve
import com.rpm.player.core.playback.Equalizing
import com.rpm.player.core.playback.LevelMode
import com.rpm.player.core.playback.Levelling
import com.rpm.player.phone.art.Logos
import com.rpm.player.phone.art.Portraits
import com.rpm.player.phone.downloads.Downloads
import com.rpm.player.core.library.ListOrdering
import com.rpm.player.phone.library.LibraryLayout
import com.rpm.player.phone.library.LibraryMode
import com.rpm.player.phone.settings.EqOutput
import com.rpm.player.phone.settings.EqOutputs
import com.rpm.player.phone.settings.SettingsSection
import com.rpm.player.phone.library.PlaylistLayout
import com.rpm.player.phone.signin.SignIn
import com.rpm.player.phone.signin.Said
import com.rpm.player.phone.settings.Account

class PhoneApplication : RpmApplication() {
    /**
     * The phone's settings. Made in [makeServices] before the services, because the services need its levelling
     * setting.
     */
    lateinit var settings: PhoneSettings
        private set

    /**
     * The sign-in flow. It is held here, not in the activity, because the activity can be destroyed
     * while the browser is open.
     */
    val signIn: SignIn by lazy {
        SignIn(services.auth, services.scope, bringBack = ::bringToFront, online = ::online)
    }

    /**
     * The account and what follows from it: the plan, the network, the rate the player asks for. Made as the app
     * starts ([onCreate]), before any screen, because the playback service can start without one.
     */
    lateinit var account: Account
        private set

    override fun onCreate() {
        super.onCreate()
        // Somebody already signed in when this build first runs had the app before the welcome and the hints existed.
        settings.settleFirstRun(signedIn = services.auth.session.value != null)
        com.rpm.player.phone.ui.Look.open(this)
        account = Account(this, services, settings, onDeleted = { signIn.notice = Said(com.rpm.player.core.R.string.Account_DeletedBody) })
        account.begin()
        // The player follows the same reading as the offline bar: offline, only what is on the phone plays. Set here,
        // before the playback service can start.
        services.line = line
        // The equaliser follows the speakers even while no screen is open, as the playback service plays on without one.
        EqOutputs(this, services.casting.state, services.scope, settings::useEqOutput).start()
    }

    /** Artists' photographs, cropped around their faces. Kept for the life of the app. */
    val portraits: Portraits by lazy { Portraits(this, services.images, services.artwork) }

    /**
     * The moving rooms inside pages: one thread and one GL context for all of them ([com.rpm.player.phone.ui.LiveRoom]).
     * They follow the phone's battery saver ([com.rpm.player.core.room.RoomPainter.saving]), told by the system's
     * broadcast whenever it is turned on or off.
     */
    val rooms: com.rpm.player.core.room.RoomPainter by lazy {
        com.rpm.player.core.room.RoomPainter(services.audioTap).also { painter ->
            val power = getSystemService(PowerManager::class.java)
            painter.saving = power?.isPowerSaveMode == true
            ContextCompat.registerReceiver(
                this,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        painter.saving = power?.isPowerSaveMode == true
                    }
                },
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Artists' logos, trimmed and kept for the life of the app. */
    val logos: Logos by lazy { Logos(this, services.images, services.artwork) }

    /** The albums downloaded to this phone ([Downloads]). Made before the services, whose player plays from it. */
    private lateinit var downloadBook: com.rpm.player.core.downloads.DownloadBook

    /** The settled network reading ([Downloads.settle]): the offline bar, and what plays offline. */
    val line: kotlinx.coroutines.flow.StateFlow<com.rpm.player.core.net.Reach> by lazy {
        Downloads.settle(account.reach.now, services.scope)
    }

    /** The downloader, kept going for the life of the process ([Downloads.keepGoing]). */
    val downloads: com.rpm.player.core.downloads.AlbumDownloads by lazy {
        Downloads.make(services, settings, account.reach, downloadBook).also { Downloads.keepGoing(this, services, settings, line, it) }
    }

    override fun makeServices(): Services {
        settings = PhoneSettings(this)
        downloadBook = Downloads.book(this)
        return Services(
            this,
            "android",
            settings.levelling,
            com.rpm.player.core.radio.TunerOpensOn.EveryCountry,
            copies = downloadBook,
            equalizing = settings.equalizing,
            starters = StarterTracks.tracks,
            forceStarters = previewsStarters(),
        )
            .also { it.dossiers.languages = settings.articleLanguages() }
    }

    /**
     * Whether the starter tracks are shown over the person's music, as a new account sees them, to look at those screens
     * without signing out. Only a debuggable build reads it, so a listener's build never does. Turned on with
     * `adb shell run-as com.rpm.player touch files/starter-preview` and off by removing that file; read when the app
     * starts.
     */
    private fun previewsStarters(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            java.io.File(filesDir, STARTER_PREVIEW).exists()

    /** A pause key only pauses: headphones and cars send it to mean pause, never to start the music. */
    override val pauseKeyResumes: Boolean get() = false

    override val marksExplicitOnSession: Boolean get() = true

    override fun sessionOpening(): SessionOpening = SessionOpening.Player(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_SHOW_PLAYER, true),
    )

    /** Whether the phone has a network that reaches the internet. With none, signing in says so without asking. */
    private fun online(): Boolean {
        val network = getSystemService(ConnectivityManager::class.java) ?: return true
        // A phone that will not say is taken to be online: the request then fails, or does not, by itself.
        val caps = try {
            network.getNetworkCapabilities(network.activeNetwork)
        } catch (_: SecurityException) {
            return true
        } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Brings the app back over the browser it opened. The Custom Tab runs in the app's task, above the
     * activity, and `FLAG_ACTIVITY_CLEAR_TOP` closes it. Starting an activity from the background is
     * allowed because the app has an activity in the task that is in front.
     */
    private fun bringToFront() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }
}

/**
 * The settings the phone keeps for the person. The keys match the ones the iPhone app stores (`Prefs`).
 *
 * Volume levelling: the defaults match albums and leave classical records alone. [levelling] is the one object the
 * playback service's sink reads for every buffer, so a change in the profile reaches the record already playing, as
 * on iPhone.
 *
 * The rates (`rate.wifi`, `rate.cellular`, `rate.download`) default to the original, as on iPhone: the store limits a
 * free account whatever is asked for. When the download rate is unset it is the Wi-Fi rate, as on iPhone.
 *
 * The library's order and view (`library.order`, `library.mode`) are kept across launches. They are
 * read on the main thread as the app starts. They are written with `apply()`, which writes in the
 * background, so a process killed before the write finishes keeps the older value.
 */
class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val app = context.applicationContext

    var libraryOrder by mutableStateOf(AlbumOrdering.of(prefs.getString(KEY_ORDER, null)) ?: AlbumOrdering.Artist)
        private set

    /**
     * What the collection's search was used for, newest first, at most [SEARCH_HISTORY] entries. Shown under the search
     * box while it is empty. See [rememberSearch].
     */
    var searchHistory by mutableStateOf(prefs.getString(KEY_SEARCH_HISTORY, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
        private set

    /**
     * Keeps [asked] at the top of the history. A search that only adds to or takes from the newest entry ("radi", then
     * "radiohead") replaces it, so the half-typed steps of one search are not kept as separate entries.
     */
    fun rememberSearch(asked: String) {
        val clean = asked.trim().replace('\n', ' ')
        if (clean.isEmpty()) return
        val newest = searchHistory.firstOrNull()
        val rest = if (newest != null && (clean.startsWith(newest, ignoreCase = true) || newest.startsWith(clean, ignoreCase = true))) {
            searchHistory.drop(1)
        } else {
            searchHistory
        }
        searchHistory = (listOf(clean) + rest.filterNot { it.equals(clean, ignoreCase = true) }).take(SEARCH_HISTORY)
        prefs.edit().putString(KEY_SEARCH_HISTORY, searchHistory.joinToString("\n")).apply()
    }

    fun forgetSearch(asked: String) {
        searchHistory = searchHistory.filterNot { it == asked }
        prefs.edit().putString(KEY_SEARCH_HISTORY, searchHistory.joinToString("\n")).apply()
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    var libraryMode by mutableStateOf(LibraryMode.of(prefs.getString(KEY_LIBRARY_MODE, null)) ?: LibraryMode.Albums)
        private set

    fun changeLibraryOrder(order: AlbumOrdering) {
        libraryOrder = order
        prefs.edit().putString(KEY_ORDER, order.stored).apply()
    }

    /** Whether the records are a grid of covers or a list with their songs (`library.layout`), and the list's own
     * order (`library.listOrder`), kept apart from the grid's because the list can be ordered by song title. */
    var libraryLayout by mutableStateOf(LibraryLayout.of(prefs.getString(KEY_LIBRARY_LAYOUT, null)) ?: LibraryLayout.Grid)
        private set

    var listOrder by mutableStateOf(ListOrdering.of(prefs.getString(KEY_LIST_ORDER, null)) ?: ListOrdering.Artist)
        private set

    fun changeLibraryLayout(layout: LibraryLayout) {
        libraryLayout = layout
        prefs.edit().putString(KEY_LIBRARY_LAYOUT, layout.stored).apply()
    }

    fun changeListOrder(order: ListOrdering) {
        listOrder = order
        prefs.edit().putString(KEY_LIST_ORDER, order.stored).apply()
    }

    var artistOrder by mutableStateOf(ArtistOrdering.entries.firstOrNull { it.name == prefs.getString(KEY_ARTIST_ORDER, null) } ?: ArtistOrdering.Name)
        private set

    var trackGrouping by mutableStateOf(ArtistTrackGrouping.of(prefs.getString(KEY_TRACK_GROUPING, null)) ?: ArtistTrackGrouping.Letter)
        private set

    fun changeArtistOrder(order: ArtistOrdering) {
        artistOrder = order
        prefs.edit().putString(KEY_ARTIST_ORDER, order.name).apply()
    }

    fun changeTrackGrouping(grouping: ArtistTrackGrouping) {
        trackGrouping = grouping
        prefs.edit().putString(KEY_TRACK_GROUPING, grouping.stored).apply()
    }

    fun changeLibraryMode(mode: LibraryMode) {
        libraryMode = mode
        prefs.edit().putString(KEY_LIBRARY_MODE, mode.stored).apply()
    }

    /** The section of the settings page that was open last, so the page opens on it again. */
    var settingsSection by mutableStateOf(SettingsSection.of(prefs.getString(KEY_SETTINGS_SECTION, null)) ?: SettingsSection.General)
        private set

    fun changeSettingsSection(section: SettingsSection) {
        settingsSection = section
        prefs.edit().putString(KEY_SETTINGS_SECTION, section.stored).apply()
    }

    /** The size the person pinched the album wall's covers to, and the artists' boxes to, in points: the iPhone's
     * `library.tileArt.v2` (194 at first) and `library.artistBox.v1` (190). Stored when a pinch ends, not during it. */
    var wallStride by mutableStateOf(prefs.getFloat(KEY_WALL_STRIDE, 194f).toDouble())
        private set

    var artistBox by mutableStateOf(prefs.getFloat(KEY_ARTIST_BOX, 190f).toDouble())
        private set

    fun changeWallStride(stride: Double) {
        wallStride = stride
        prefs.edit().putFloat(KEY_WALL_STRIDE, stride.toFloat()).apply()
    }

    fun changeArtistBox(stride: Double) {
        artistBox = stride
        prefs.edit().putFloat(KEY_ARTIST_BOX, stride.toFloat()).apply()
    }

    /** How the playlists view shows a folder: covers or rows (`playlists.layout`). It opens in the layout last chosen. */
    var playlistLayout by mutableStateOf(PlaylistLayout.of(prefs.getString(KEY_PLAYLIST_LAYOUT, null)) ?: PlaylistLayout.List)
        private set

    fun changePlaylistLayout(layout: PlaylistLayout) {
        playlistLayout = layout
        prefs.edit().putString(KEY_PLAYLIST_LAYOUT, layout.stored).apply()
    }

    /** How the tuner lists its stations: rows, or a wall of their squares (`radio.layout`). It opens in the layout last
     * chosen, as the playlists view does. */
    var radioLayout by mutableStateOf(PlaylistLayout.of(prefs.getString(KEY_RADIO_LAYOUT, null)) ?: PlaylistLayout.List)
        private set

    fun changeRadioLayout(layout: PlaylistLayout) {
        radioLayout = layout
        prefs.edit().putString(KEY_RADIO_LAYOUT, layout.stored).apply()
    }

    /** How wide a tile on the playlists' covers is asked to be (`playlists.tileArt.v1`), apart from the records wall's,
     * because the two walls are pinched apart. Saved when the fingers lift. */
    var playlistStride by mutableStateOf(prefs.getFloat(KEY_PLAYLIST_STRIDE, 194f).toDouble())
        private set

    fun changePlaylistStride(stride: Double) {
        playlistStride = stride
        prefs.edit().putFloat(KEY_PLAYLIST_STRIDE, stride.toFloat()).apply()
    }

    /**
     * Stored by the iPhone's names (`off`, `album`, `track`). A name this build does not know gives the default, not
     * off: levelling is on unless the person turned it off. The comparison ignores case, because earlier builds wrote
     * the Kotlin names.
     */
    val levelling = Levelling(
        prefs.getString(KEY_MODE, null)?.let { name -> LevelMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } } ?: LevelMode.Album,
        prefs.getBoolean(KEY_CLASSICAL, false),
    )

    /** The screen's copy of [levelling], so the profile redraws when it changes. */
    var levelMode by mutableStateOf(levelling.mode)
        private set

    var levelsClassical by mutableStateOf(levelling.includeClassical)
        private set

    /** The setting is changed only here, so what is stored and what the sink levels by cannot disagree. */
    fun changeLevelMode(mode: LevelMode) {
        levelMode = mode
        levelling.mode = mode
        prefs.edit().putString(KEY_MODE, mode.name.lowercase()).apply()
    }

    fun changeLevelsClassical(on: Boolean) {
        levelsClassical = on
        levelling.includeClassical = on
        prefs.edit().putBoolean(KEY_CLASSICAL, on).apply()
    }

    /**
     * The equaliser, as on the desktop (EqService): one curve for each set of speakers ([EqOutput]), each stored under
     * `eq.curve.` and the speakers' key. A set of speakers seen for the first time starts flat and off. [equalizing] is
     * the one object the playback service's sink reads for every buffer, so a change is heard on the music already
     * playing.
     */
    val equalizing = Equalizing()

    /** The speakers the music goes to now, whose curve is on the faders. Null until [EqOutputs] first says. */
    var eqOutput by mutableStateOf<EqOutput?>(null)
        private set

    /** The curve of [eqOutput], as on the faders. */
    var eqCurve by mutableStateOf(EqCurve.OFF)
        private set

    /** The music goes to [output] now: its own curve is shown and heard. */
    fun useEqOutput(output: EqOutput) {
        val before = eqOutput
        eqOutput = output
        if (before?.key == output.key) return
        eqCurve = EqCurve.parse(prefs.getString(KEY_EQ + output.key, null))
        equalizing.curve = eqCurve
    }

    /** The curve is changed only here, so what is stored, what is shown and what is heard cannot disagree. */
    fun changeEq(curve: EqCurve) {
        val output = eqOutput ?: return
        if (curve == eqCurve) return
        eqCurve = curve
        equalizing.curve = curve
        prefs.edit().putString(KEY_EQ + output.key, curve.stored()).apply()
    }

    /** The rate asked for on a network that costs nothing. [AudioLadder.ORIGINAL] is the untranscoded file. */
    var rateOnWifi by mutableIntStateOf(prefs.getInt(KEY_RATE_WIFI, AudioLadder.ORIGINAL))
        private set

    /** The rate asked for on a metered network. */
    var rateOnCellular by mutableIntStateOf(prefs.getInt(KEY_RATE_CELLULAR, AudioLadder.ORIGINAL))
        private set

    /**
     * The rate downloads ask for, on any network. A separate setting, because keeping a record and streaming it are
     * different choices. It applies to tracks signed after the change; a file already on the phone keeps its rate.
     */
    var rateForDownloads by mutableIntStateOf(prefs.getInt(KEY_RATE_DOWNLOAD, rateOnWifi))
        private set

    fun changeRateOnWifi(kilobits: Int) {
        rateOnWifi = kilobits
        prefs.edit().putInt(KEY_RATE_WIFI, kilobits).apply()
    }

    fun changeRateOnCellular(kilobits: Int) {
        rateOnCellular = kilobits
        prefs.edit().putInt(KEY_RATE_CELLULAR, kilobits).apply()
    }

    fun changeRateForDownloads(kilobits: Int) {
        rateForDownloads = kilobits
        prefs.edit().putInt(KEY_RATE_DOWNLOAD, kilobits).apply()
    }

    /** The id of the chosen backdrop (`backdrop.picture`). Every page's ground reads it, so a change shows at once. */
    var backdrop by mutableStateOf(prefs.getString(KEY_BACKDROP, null).orEmpty())
        private set

    /** Keeps the backdrop, and sets the glass colour back to the backdrop's own, so the two stay one theme. */
    fun changeBackdrop(id: String) {
        backdrop = id
        prefs.edit().putString(KEY_BACKDROP, id).apply()
        com.rpm.player.phone.design.AppColour.choose(app, "")
    }

    /**
     * Night mode (`look.night`, as on iPhone): pages that stand on a photograph are drawn darker. Off at first. Every
     * ground reads it through [com.rpm.player.phone.ui.LocalNightMode], so a change shows at once.
     */
    var nightMode by mutableStateOf(prefs.getBoolean(KEY_NIGHT, false))
        private set

    fun changeNightMode(on: Boolean) {
        nightMode = on
        prefs.edit().putBoolean(KEY_NIGHT, on).apply()
    }

    /**
     * What the record looks like for a record with no disc picture of its own (`look.discStandIn`, as on iPhone): the
     * plain CD by default, or the vinyl. Read through [com.rpm.player.phone.ui.LocalDiscStandIn]; a change applies to
     * the next record that is put on or opened.
     */
    var discStandIn by mutableStateOf(BlankDisc.read(prefs.getString(BlankDisc.KEY, null)))
        private set

    fun changeDiscStandIn(choice: DiscStandIn) {
        discStandIn = choice
        prefs.edit().putString(BlankDisc.KEY, choice.stored).apply()
    }

    /**
     * Karaoke lyrics (`lyrics.karaoke`, the iPhone's `KaraokeSetting`): the sung line lights letter by letter as it is
     * sung. On when it was never set, so a supporter sees the feature without looking for it. It is a supporter feature:
     * the switch is kept for everyone, and the player combines it with the plan ([com.rpm.player.phone.player.karaokeOn]).
     */
    var karaoke by mutableStateOf(prefs.getBoolean(KEY_KARAOKE, true))
        private set

    fun changeKaraoke(on: Boolean) {
        karaoke = on
        prefs.edit().putBoolean(KEY_KARAOKE, on).apply()
    }

    /**
     * Whether this launch may stand on the gold pressing (the iPhone's `SupporterGround.isGold`). Read once, before the
     * first frame. A quota read later only leaves a note for the next launch ([rememberSupporter]), so the picture
     * never turns gold while somebody is looking at it. Only a sign-in, where nothing of the account is on screen yet,
     * may change it now ([adoptSupporter]).
     */
    var isGold by mutableStateOf(prefs.getBoolean(KEY_GOLD, false))
        private set

    /** What the cloud just said about the account, kept for the next launch. */
    fun rememberSupporter(isSupporter: Boolean) {
        prefs.edit().putBoolean(KEY_GOLD, isSupporter).apply()
    }

    /** The plan of the account, read at a sign-in: used now, and kept for the next launch. */
    fun adoptSupporter(isSupporter: Boolean) {
        rememberSupporter(isSupporter)
        isGold = isSupporter
    }

    /** Whether the opening sound plays when the app opens (`sound.opening`). On until it is turned off. */
    var openingSound by mutableStateOf(prefs.getBoolean(KEY_OPENING_SOUND, true))
        private set

    fun changeOpeningSound(on: Boolean) {
        openingSound = on
        prefs.edit().putBoolean(KEY_OPENING_SOUND, on).apply()
    }

    /**
     * The language of the Wikipedia texts on the artist and record pages (`papers.language`): a language code such as
     * "en" or "nl", or empty for the phone's own language.
     */
    var articleLanguage by mutableStateOf(prefs.getString(KEY_ARTICLE_LANGUAGE, null).orEmpty())
        private set

    fun changeArticleLanguage(code: String) {
        articleLanguage = code
        prefs.edit().putString(KEY_ARTICLE_LANGUAGE, code).apply()
    }

    /** The Wikipedias the record and artist pages read, best first, from [articleLanguage]: that language, then English. */
    fun articleLanguages(): List<String> =
        com.rpm.player.core.papers.Encyclopedia.languages(articleLanguage.ifEmpty { java.util.Locale.getDefault().toLanguageTag() })

    /** Whether the spectrum bars beside the cover in the player move with the music (`player.bars`). On at first. */
    var playerBars by mutableStateOf(prefs.getBoolean(KEY_PLAYER_BARS, true))
        private set

    fun changePlayerBars(on: Boolean) {
        playerBars = on
        prefs.edit().putBoolean(KEY_PLAYER_BARS, on).apply()
    }

    /**
     * How the player looks on its side (`player.landscape`): the stage, or the now playing sheet laid out for it
     * ([com.rpm.player.phone.player.LandscapeStyle]). The stage at first.
     */
    var landscapePlayer by mutableStateOf(com.rpm.player.phone.player.LandscapeStyle.read(prefs.getString(KEY_LANDSCAPE, null)))
        private set

    fun changeLandscapePlayer(style: com.rpm.player.phone.player.LandscapeStyle) {
        landscapePlayer = style
        prefs.edit().putString(KEY_LANDSCAPE, style.stored).apply()
    }

    /**
     * What is drawn behind the modern player on its side (`player.landscape.visual`): one of the stage's visualisers,
     * or null for the room, which is the first choice ([com.rpm.player.phone.player.LandscapeVisual]).
     */
    var landscapeVisual by mutableStateOf(com.rpm.player.phone.player.LandscapeVisual.read(prefs.getString(KEY_LANDSCAPE_VISUAL, null)))
        private set

    fun changeLandscapeVisual(look: com.rpm.player.phone.stage.StageLook?) {
        landscapeVisual = look
        prefs.edit().putString(KEY_LANDSCAPE_VISUAL, com.rpm.player.phone.player.LandscapeVisual.stored(look)).apply()
    }

    /**
     * What the person added to home's Quick selection themselves, newest first (`home.quick`): each one a song, a record
     * or a list, by [QuickRef]. Shown after the suggested mixtapes. Something the collection no longer holds is skipped
     * when home is drawn, and kept here, so it comes back when the collection has it again.
     */
    var quickAdded by mutableStateOf(readRefs(KEY_QUICK))
        private set

    fun addToQuick(ref: String) {
        quickAdded = (listOf(ref) + quickAdded.filterNot { it == ref }).take(QUICK_ADDED)
        prefs.edit().putString(KEY_QUICK, quickAdded.joinToString("\n")).apply()
    }

    fun removeFromQuick(ref: String) {
        quickAdded = quickAdded.filterNot { it == ref }
        prefs.edit().putString(KEY_QUICK, quickAdded.joinToString("\n")).apply()
    }

    /**
     * The stations the person pinned to the radio home's Quick selection, in the order they were pinned (`radio.pinned`).
     * Each is kept whole, as a saved station is, so it is shown and plays even when the directory does not answer.
     */
    var pinnedStations by mutableStateOf(readStations(KEY_RADIO_PINNED))
        private set

    /** Whether [station] is pinned, by its uuid or one of the entries folded into it. */
    fun isPinned(station: RadioStation): Boolean = pinnedStations.any { station.answersTo(it.uuid) }

    /** Pins [station] after the others, or unpins it. At most [PINNED_STATIONS]; the oldest pin goes first. */
    fun pinStation(station: RadioStation, on: Boolean) {
        val others = pinnedStations.filterNot { station.answersTo(it.uuid) }
        // Kept as the directory files it: the folded entries and the admin's edit are worked out again when it is shown.
        val kept = station.copy(shownName = null, otherLogos = emptyList(), alsoFiledAs = emptyList(), edit = null)
        pinnedStations = if (on) (others + kept).takeLast(PINNED_STATIONS) else others
        prefs.edit().putString(KEY_RADIO_PINNED, WireJson.encodeToString(ListSerializer(RadioStation.serializer()), pinnedStations)).apply()
    }

    private fun readStations(key: String): List<RadioStation> = try {
        prefs.getString(key, null)?.let { WireJson.decodeFromString(ListSerializer(RadioStation.serializer()), it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /** The ids of the playlists the person pinned to the top of the playlists view, in the order they were pinned
     * (`playlists.pinned`). */
    var pinnedLists by mutableStateOf(readRefs(KEY_PINNED))
        private set

    fun pinList(id: String, on: Boolean) {
        pinnedLists = if (on) pinnedLists.filterNot { it == id } + id else pinnedLists.filterNot { it == id }
        prefs.edit().putString(KEY_PINNED, pinnedLists.joinToString("\n")).apply()
    }

    /** The first-run hints already shown and answered (`coach.seen`), by their keys ([com.rpm.player.phone.coach.CoachMark]). */
    var coachSeen by mutableStateOf(readRefs(KEY_COACH).toSet())
        private set

    /** Whether the hint [key] was shown and answered. [ALL_SEEN] stands for every hint. */
    fun hasSeen(key: String): Boolean = ALL_SEEN in coachSeen || key in coachSeen

    fun seeCoach(key: String) {
        if (hasSeen(key)) return
        coachSeen = coachSeen + key
        prefs.edit().putString(KEY_COACH, coachSeen.joinToString("\n")).apply()
    }

    /** Puts every first-run hint back, from the settings page. */
    fun resetCoach() {
        coachSeen = emptySet()
        prefs.edit().remove(KEY_COACH).apply()
    }

    /** Whether the welcome has been shown on this phone (`welcome.done`). The very first launch shows it once. */
    var welcomed by mutableStateOf(prefs.getBoolean(KEY_WELCOMED, false))
        private set

    fun finishWelcome() {
        welcomed = true
        prefs.edit().putBoolean(KEY_WELCOMED, true).apply()
    }

    /** Puts the welcome back, with the hints: the settings page's key, which shows them all again. */
    fun replayWelcome() {
        resetCoach()
        welcomed = false
        prefs.edit().putBoolean(KEY_WELCOMED, false).apply()
    }

    /**
     * Decides once, on the first run of a build that has the welcome, whether this phone is new to rpm. A person who is
     * already signed in then had the app before: they are not walked through the welcome or the hints. Somebody who
     * still has to sign in is new, and sees both after signing in.
     */
    fun settleFirstRun(signedIn: Boolean) {
        if (prefs.contains(KEY_WELCOMED)) return
        welcomed = signedIn
        if (signedIn) coachSeen = setOf(ALL_SEEN)
        prefs.edit().putBoolean(KEY_WELCOMED, signedIn).putString(KEY_COACH, coachSeen.joinToString("\n")).apply()
    }

    private fun readRefs(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val FILE = "rpm.phone"
        const val KEY_OPENING_SOUND = "sound.opening"
        const val KEY_ARTICLE_LANGUAGE = "papers.language"
        const val KEY_PLAYER_BARS = "player.bars"
        const val KEY_LANDSCAPE = "player.landscape"
        const val KEY_LANDSCAPE_VISUAL = "player.landscape.visual"
        const val KEY_QUICK = "home.quick"
        /** The most the person can add to Quick selection; home shows sixteen lines in all. */
        const val QUICK_ADDED = 16
        const val KEY_PINNED = "playlists.pinned"
        const val KEY_RADIO_PINNED = "radio.pinned"
        /** The most stations the person can pin: the radio home's Quick selection shows sixteen lines in all. */
        const val PINNED_STATIONS = 16
        const val KEY_COACH = "coach.seen"
        const val KEY_WELCOMED = "welcome.done"
        /** In [coachSeen], every hint at once. */
        const val ALL_SEEN = "*"
        const val KEY_MODE = "levelling.mode"
        const val KEY_CLASSICAL = "levelling.classical"
        const val KEY_EQ = "eq.curve."
        const val KEY_RATE_WIFI = "rate.wifi"
        const val KEY_RATE_CELLULAR = "rate.cellular"
        const val KEY_RATE_DOWNLOAD = "rate.download"
        const val KEY_BACKDROP = "backdrop.picture"
        const val KEY_NIGHT = "look.night"
        const val KEY_KARAOKE = "lyrics.karaoke"
        const val KEY_GOLD = "supporter.gold"
        const val KEY_ORDER = "library.order"
        const val KEY_LIBRARY_MODE = "library.mode"
        const val KEY_SETTINGS_SECTION = "settings.section"
        const val KEY_SEARCH_HISTORY = "search.history"
        const val SEARCH_HISTORY = 10
        const val KEY_LIBRARY_LAYOUT = "library.layout"
        const val KEY_LIST_ORDER = "library.listOrder"
        const val KEY_ARTIST_ORDER = "library.artistOrder"
        const val KEY_TRACK_GROUPING = "artist.trackGrouping"
        const val KEY_WALL_STRIDE = "library.tileArt.v2"
        const val KEY_ARTIST_BOX = "library.artistBox.v1"
        const val KEY_PLAYLIST_LAYOUT = "playlists.layout"
        const val KEY_PLAYLIST_STRIDE = "playlists.tileArt.v1"
        const val KEY_RADIO_LAYOUT = "radio.layout"
    }
}

/** The file in the app's files folder that turns the starter preview on ([PhoneApplication], debuggable builds only). */
private const val STARTER_PREVIEW = "starter-preview"
