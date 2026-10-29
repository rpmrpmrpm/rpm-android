package com.rpm.player.core

import okio.Path.Companion.toPath
import coil3.disk.DiskCache
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import com.rpm.player.core.library.ArtistEdits
import com.rpm.player.core.library.OwnWriter
import com.rpm.player.core.papers.PapersClient
import com.rpm.player.core.papers.Encyclopedia
import com.rpm.player.core.papers.Dossiers
import com.rpm.player.core.papers.WorldPlays
import com.rpm.player.core.playback.TrackPlaysSync
import com.rpm.player.core.playback.TrackPlayLog
import com.rpm.player.core.library.SharedCollections
import android.content.Context
import coil3.ImageLoader
import com.rpm.player.core.art.SharpDecoder
import com.rpm.player.core.art.OutputPixels
import com.rpm.player.core.library.coverEdgeFor
import com.rpm.player.core.library.CoverEdge
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cast.Casting
import com.rpm.player.core.cast.CoilCastCovers
import com.rpm.player.core.auth.HttpAuthWire
import com.rpm.player.core.auth.KeystoreSessionStore
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.home.Computers
import com.rpm.player.core.home.Discovery
import com.rpm.player.core.home.HomeCatalogs
import com.rpm.player.core.home.PeerTrust
import com.rpm.player.core.library.Artwork
import com.rpm.player.core.library.CoverFetcher
import com.rpm.player.core.library.CoverKeyer
import com.rpm.player.core.library.DiscFetcher
import com.rpm.player.core.library.DiscKeyer
import com.rpm.player.core.library.LibraryCache
import com.rpm.player.core.library.LibraryRepository
import com.rpm.player.core.library.ListCoverFetcher
import com.rpm.player.core.library.ListCoverKeyer
import com.rpm.player.core.library.PlaylistPhotoFetcher
import com.rpm.player.core.library.PlaylistPhotoKeyer
import com.rpm.player.core.lyrics.LrcLib
import com.rpm.player.core.motion.MotionLadder
import com.rpm.player.core.net.httpClient
import com.rpm.player.core.playback.AudioTap
import com.rpm.player.core.playback.Levelling
import com.rpm.player.core.playback.StreamLinks
import com.rpm.player.core.playback.TrackSources
import com.rpm.player.core.radio.RadioBrowser
import com.rpm.player.core.radio.RadioFavorites
import com.rpm.player.core.radio.RadioRepository
import com.rpm.player.core.radio.StationLogoFetcher
import com.rpm.player.core.radio.StationListens
import com.rpm.player.core.radio.StationBlurbs
import com.rpm.player.core.radio.SongCovers
import com.rpm.player.core.radio.StationListensSync
import com.rpm.player.core.radio.StationLogoKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** The app-wide objects, made once by the app's [RpmApplication]. The activity and the playback service
 * both reach them through [services].
 *
 * [levelling] is the app's volume levelling setting, which the playback service's sink reads for every
 * buffer. Each app keeps it with its own settings and hands that same object over here, so a change in
 * the settings reaches the record that is playing. */
class Services(
    context: Context,
    /** Which app reports: "android" for the phone, "androidtv" for the TV. Feedback and crash reports carry it. */
    head: String,
    val levelling: Levelling,
    tunerOpensOn: com.rpm.player.core.radio.TunerOpensOn,
    /** The tracks downloaded to this device, which play from their files. The phone's downloads; none on the TV. */
    val copies: com.rpm.player.core.playback.LocalCopies = com.rpm.player.core.playback.LocalCopies.None,
) {
    /**
     * The settled network reading of an app that keeps downloads, which the player follows offline
     * ([com.rpm.player.core.playback.OfflinePlayback]): only what is on the device plays, and a streamed record stops when
     * the network goes. Set by the phone as it starts, before the playback service can be made; null on the TV, whose
     * player streams everything and never stands still for want of a network.
     */
    @Volatile var line: kotlinx.coroutines.flow.StateFlow<com.rpm.player.core.net.Reach>? = null

    private val clock: () -> Long = System::currentTimeMillis

    /** Lives as long as the process: the library load and the session watch run here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http = httpClient()

    /** Pins each computer's address to the key it advertises; every other host is checked as usual. */
    private val trust = PeerTrust()

    /** The cloud and the computers on the home network, through one client. Made on a background thread
     * as the app starts ([warmUp]): a client with its own trust manager indexes every root certificate the
     * system holds as it is built, which takes about a second on a slow TV box. Built on the main thread,
     * that would delay the first frame. Whoever needs it before it is ready waits for it. */
    private val peerClient = lazy { trust.client(http) }
    val peerHttp: OkHttpClient get() = peerClient.value
    val auth = AuthClient(HttpAuthWire(http), KeystoreSessionStore(context), clock).apply { load() }
    val cloud = CloudClient(http, auth)

    /** rpm's shared album wall: the reviews and ratings on a record page. */
    val community = com.rpm.player.core.cloud.CommunityClient(http, auth)

    /** Send feedback and the crash reports: the scope, the outbox and the consent prompt. */
    val diagnostics = com.rpm.player.core.diag.Diagnostics.make(context, head, http, auth, scope)
    val streamLinks = StreamLinks(cloud, clock)

    /** The audio being heard, filled by the playback service's sink and read by the room. */
    val audioTap = AudioTap(System::nanoTime)

    /** How much moves on screen on this device, lowered while the window's frames are late. */
    val motion = MotionLadder(context)

    /** Pictures of the still room, drawn on a thread of their own when its colours change. */
    val roomStills = com.rpm.player.core.room.RoomStills(audioTap)

    /** What this TV has played, as totals per track, and the passes that send them to the cloud for the
     * desktop to build the generated lists from, as the phone sends its own. */
    val trackPlays = TrackPlayLog(File(context.filesDir, "track-plays.json"), scope)
    val trackPlaysSync = TrackPlaysSync(cloud, auth, trackPlays, scope, clock)

    val lrcLib = LrcLib(http)

    /** The word timing desktops measured for lyrics, for karaoke. */
    val measuredLyrics = com.rpm.player.core.lyrics.MeasuredLyrics(cloud)

    val computers = Computers(
        context = context,
        http = peerClient,
        discovery = Discovery(context, trust, scope).apply { start() },
        auth = auth,
        scope = scope,
        clock = clock,
    )

    /** The paired computers' catalogues, while they are on the network. */
    val homeCatalogs = HomeCatalogs(computers, scope)

    /** The rate cloud tracks are asked for. The TV leaves it at 320; the phone sets it from its settings and the
     * network. */
    val streamRate = com.rpm.player.core.playback.StreamRate(com.rpm.player.core.playback.PlaybackService.STREAM_KILOBITS)

    /** Which source each track plays from: a computer or the cloud. */
    val trackSources = TrackSources(computers, homeCatalogs, streamLinks, extension = TrackSources.extensionDecodes(), rate = streamRate, copies = copies)

    /** The cloud library with the paired computers' tracks merged in by rpmId. */
    val library = LibraryRepository(cloud, auth, homeCatalogs, LibraryCache(context.cacheDir), scope, clock)

    /** A track menu's writes to the person's own collection: a favourite, a place in a playlist. */
    val ownWriter = OwnWriter(cloud, auth, library, clock)

    /** Making a playlist, adding songs to one and rearranging one, as the phone's playlist pages do. */
    val playlistWriter = com.rpm.player.core.library.PlaylistWriter(cloud, auth, library, clock)

    /** The collections other accounts share with this one, for the collections panel. */
    val shared = SharedCollections(cloud, auth, scope)

    /** The signed-in account's name, picture and supporter mark, for the side menu. */
    val ownFace = com.rpm.player.core.library.OwnFace(cloud, auth, scope)

    /** The person's favourite records, read from and written to the cloud. */
    val albumFavourites = com.rpm.player.core.library.AlbumFavourites(cloud, auth, scope, clock)

    /** The stars the person gave records on this TV. */
    val albumRatings = com.rpm.player.core.library.AlbumRatings(context.getSharedPreferences("album-ratings", Context.MODE_PRIVATE), auth)

    /** Which records each shelf's owner marked classical, which volume levelling leaves alone. */
    val classicalMarks = com.rpm.player.core.library.ClassicalMarks(cloud, auth, library.state, scope)

    private val stationListens = StationListens(File(context.filesDir, "radio-listens.json"), scope, clock).apply { load() }

    private val radioFavorites =
        RadioFavorites(File(context.filesDir, "radio-favorites.json"), File(context.filesDir, "radio-favorites-sync.json"), scope, clock).apply { load() }

    /** The country and interface language chosen on this device. */
    val region = Region(context)

    val radio = RadioRepository(
        browser = RadioBrowser(http),
        favorites = radioFavorites,
        favoritesSync = com.rpm.player.core.radio.FavoritesSync(cloud, auth, radioFavorites, scope),
        shared = com.rpm.player.core.radio.SharedStations(cloud, auth, library.state, radioFavorites.stations, scope),
        listens = stationListens,
        listensSync = StationListensSync(cloud, auth, stationListens, scope, clock),
        http = http,
        scope = scope,
        clock = clock,
        tunerOpensOn = tunerOpensOn,
        chosenCountry = { region.country },
    )

    /** What each station is, in a line, for the radio banner. */
    val stationBlurbs = StationBlurbs(http)

    /** The cover of the song a station says it is playing, for the player and the mini player. */
    val songCovers = SongCovers(http)

    /** Artist photos and logos for home's banner, from the rpm API. */
    val artwork = Artwork(http)

    /** The admin's edits to each act: a description in place of Wikipedia's, and related acts to add or leave out. */
    val artistEdits = ArtistEdits(http)

    /** What the encyclopedias say about each record, for the record page: the catalogue's genres and the
     * Wikipedia article, in the device's language and then English. Kept under the cache folder. */
    val dossiers = Dossiers(
        client = PapersClient(http),
        languages = Encyclopedia.languages(java.util.Locale.getDefault().toLanguageTag()),
        directory = File(context.cacheDir, "papers"),
        scope = scope,
        clock = clock,
    )

    /** How often each song of a record has been listened to across everybody's listening, for an artist's
     * popular songs. Kept in memory for the life of the app. */
    val worldPlays = WorldPlays(http, scope)

    /** The cover copies this device asks for. The size starts from the output's own pixels; the TV can change
     * it when it knows the height of its screens. */
    val coverEdge = CoverEdge(coverEdgeFor(Math.round(minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) * OutputPixels.share(context))))

    val images: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(CoverKeyer())
            add(CoverFetcher.Factory(cloud, coverEdge))
            add(DiscKeyer())
            add(DiscFetcher.Factory(cloud))
            add(ListCoverKeyer())
            add(ListCoverFetcher.Factory(cloud, coverEdge))
            add(PlaylistPhotoKeyer())
            add(PlaylistPhotoFetcher.Factory())
            add(StationLogoKeyer())
            add(StationLogoFetcher.Factory(http))
            add(SvgDecoder.Factory())
            // After the SVG decoder: the app's own pictures, shrunk with a sharper filter than the loader's own.
            add(SharpDecoder.Factory())
            add(OkHttpNetworkFetcherFactory(callFactory = { http }))
        }
        // Album covers and discs are kept here by their stamp (StoredPictures), so a launch does not fetch again what
        // the last one drew. In the cache folder, which the system empties when it needs the space.
        .diskCache {
            DiskCache.Builder()
                .directory(File(context.cacheDir, "images").absolutePath.toPath())
                .maxSizeBytes(IMAGE_CACHE_BYTES)
                .build()
        }
        .build()

    /** The playing item's picture as a JPEG, read through [images]: what a cast receiver is sent, and what the media
     * session shows on the launcher's now playing card (`SessionArtwork`). */
    val nowPlayingPictures: com.rpm.player.core.cast.CastCovers = CoilCastCovers(context, images)

    /** Sending to Cast devices: discovery, the sitting, and the route the playback service's sink reads. */
    val casting = Casting(context, scope, nowPlayingPictures)

    /** When the player pauses by itself, kept here so it counts on while no screen is shown. The playback service
     * attaches its player to it. */
    val sleep = com.rpm.player.core.playback.SleepClock()

    init {
        warmUp()
    }

    private companion object {
        /** The most the picture cache keeps on disk: about four thousand covers. */
        const val IMAGE_CACHE_BYTES = 200L * 1024 * 1024
    }

    /** Builds what is costly to build and not needed for the first frame on a background thread, while the
     * main thread makes that frame: the computers' client (see [peerHttp]). */
    private fun warmUp() {
        scope.launch(Dispatchers.Default) { peerClient.value }
    }
}
