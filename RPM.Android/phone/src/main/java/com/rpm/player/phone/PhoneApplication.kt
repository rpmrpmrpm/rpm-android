package com.rpm.player.phone

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.rpm.player.core.RpmApplication
import com.rpm.player.core.Services
import com.rpm.player.core.SessionOpening
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import com.rpm.player.core.playback.AudioLadder
import androidx.compose.runtime.setValue
import com.rpm.player.core.library.AlbumOrdering
import com.rpm.player.core.library.ArtistOrdering
import com.rpm.player.core.library.ArtistTrackGrouping
import com.rpm.player.core.playback.LevelMode
import com.rpm.player.core.playback.Levelling
import com.rpm.player.phone.art.Logos
import com.rpm.player.phone.art.Portraits
import com.rpm.player.phone.downloads.Downloads
import com.rpm.player.phone.library.LibraryMode
import com.rpm.player.phone.library.PlaylistLayout
import com.rpm.player.phone.signin.SignIn
import com.rpm.player.phone.signin.Said
import com.rpm.player.phone.profile.Account

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
        account = Account(this, services, settings, onDeleted = { signIn.notice = Said(com.rpm.player.core.R.string.Account_DeletedBody) })
        account.begin()
        // The player follows the same reading as the offline bar: offline, only what is on the phone plays. Set here,
        // before the playback service can start.
        services.line = line
    }

    /** Artists' photographs, cropped around their faces. Kept for the life of the app. */
    val portraits: Portraits by lazy { Portraits(this, services.images, services.artwork) }

    /** The moving rooms inside pages: one thread and one GL context for all of them ([com.rpm.player.phone.ui.LiveRoom]). */
    val rooms: com.rpm.player.core.room.RoomPainter by lazy { com.rpm.player.core.room.RoomPainter(services.audioTap) }

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
        return Services(this, "android", settings.levelling, com.rpm.player.core.radio.TunerOpensOn.EveryCountry, copies = downloadBook)
    }

    /** A pause key only pauses: headphones and cars send it to mean pause, never to start the music. */
    override val pauseKeyResumes: Boolean get() = false

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

    var libraryOrder by mutableStateOf(AlbumOrdering.of(prefs.getString(KEY_ORDER, null)) ?: AlbumOrdering.Artist)
        private set

    var libraryMode by mutableStateOf(LibraryMode.of(prefs.getString(KEY_LIBRARY_MODE, null)) ?: LibraryMode.Albums)
        private set

    fun changeLibraryOrder(order: AlbumOrdering) {
        libraryOrder = order
        prefs.edit().putString(KEY_ORDER, order.stored).apply()
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

    fun changeBackdrop(id: String) {
        backdrop = id
        prefs.edit().putString(KEY_BACKDROP, id).apply()
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

    private companion object {
        const val FILE = "rpm.phone"
        const val KEY_MODE = "levelling.mode"
        const val KEY_CLASSICAL = "levelling.classical"
        const val KEY_RATE_WIFI = "rate.wifi"
        const val KEY_RATE_CELLULAR = "rate.cellular"
        const val KEY_RATE_DOWNLOAD = "rate.download"
        const val KEY_BACKDROP = "backdrop.picture"
        const val KEY_GOLD = "supporter.gold"
        const val KEY_ORDER = "library.order"
        const val KEY_LIBRARY_MODE = "library.mode"
        const val KEY_ARTIST_ORDER = "library.artistOrder"
        const val KEY_TRACK_GROUPING = "artist.trackGrouping"
        const val KEY_WALL_STRIDE = "library.tileArt.v2"
        const val KEY_ARTIST_BOX = "library.artistBox.v1"
        const val KEY_PLAYLIST_LAYOUT = "playlists.layout"
        const val KEY_PLAYLIST_STRIDE = "playlists.tileArt.v1"
    }
}
