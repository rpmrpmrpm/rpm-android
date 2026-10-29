package com.rpm.player.core.library

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.ApiException
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudPlaylist
import com.rpm.player.core.cloud.Shelf
import android.os.SystemClock
import com.rpm.player.core.diag.Diag
import com.rpm.player.core.home.HomeCatalogs
import com.rpm.player.core.home.HeldCatalog
import com.rpm.player.core.home.HomeMerge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Which albums have a stored cover. [Unknown] when the index could not be read, in which case each
 * album is asked about directly. */
sealed interface CoverIndex {
    /** Each album key with a stored cover, and when that cover was written (0 where the server did not say). */
    data class Known(val stamps: Map<String, Long>) : CoverIndex {
        val keys: Set<String> get() = stamps.keys
    }
    data object Unknown : CoverIndex
}

sealed interface LibraryState {
    data object SignedOut : LibraryState
    data class Loading(val shelf: Shelf, val rows: Int) : LibraryState
    /** [walls] is the artists and genres lists of this load, built beside home. */
    data class Ready(val collection: Collection, val home: Home, val walls: Walls) : LibraryState {
        val albums: List<CloudAlbum> get() = collection.albums
    }
    data class Failed(val shelf: Shelf) : LibraryState
}

/** The shelf a state is about: the one loading, shown or failed; the person's own when signed out. */
val LibraryState.shelf: Shelf
    get() = when (this) {
        LibraryState.SignedOut -> Shelf.Own
        is LibraryState.Loading -> shelf
        is LibraryState.Ready -> collection.shelf
        is LibraryState.Failed -> shelf
    }

/** Why a shelf another account shares was put down and the person returned to their own collection. */
sealed interface ShelfNotice {
    val shelf: Shelf.Borrowed

    /** The cloud refused it (404): the share was withdrawn, or never existed. */
    data class Withdrawn(override val shelf: Shelf.Borrowed) : ShelfNotice

    /** The server answered with someone else's rows: it does not know shared collections yet. */
    data class NotServed(override val shelf: Shelf.Borrowed) : ShelfNotice
}

/**
 * The collection on screen, loaded once per session and shelf: the copy cached on the TV first, when
 * there is one, then the cloud's. The person's own collection has the paired computers' tracks merged in;
 * a collection another account shares ([open]) is that account's cloud alone, with no plays.
 *
 * The load runs under `collectLatest` on (user, shelf, attempt), so a new session or another collection
 * cancels the load before it, and every result is published only if both the user and the shelf it was
 * loaded for are still the ones asked for. The shelf asked for belongs to the user who asked, so a new
 * session starts on its own collection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val cloud: CloudClient,
    private val auth: AuthClient,
    private val homeCatalogs: HomeCatalogs,
    private val cache: LibraryCache,
    scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val current = MutableStateFlow<LibraryState>(LibraryState.SignedOut)
    val state: StateFlow<LibraryState> = current.asStateFlow()

    /** Advanced by [retry], so the same user can be loaded again after a failure. */
    private val attempt = MutableStateFlow(0)

    /** Advanced by [refresh]: the same collection read again from the cloud while the one on screen stays. */
    private val refreshes = MutableStateFlow(0)

    private val reading = MutableStateFlow(false)

    /** Whether a [refresh] is reading the cloud now, for the side menu's reload key to show. */
    val refreshing: StateFlow<Boolean> = reading.asStateFlow()

    /** Which collection the screen shows ([LibraryState.Ready]); null before the first. Written where a Ready is
     * published. */
    @Volatile private var shown: Shown? = null

    private data class Shown(val user: String, val shelf: Shelf, val seed: Long)

    /** Set by [exited], taken by [refreshIfExited]. */
    @Volatile private var exitedSince = false

    /** The shelf a user asked for, with that user, so it means nothing to another session. */
    private data class Asked(val user: String, val shelf: Shelf)

    private val asked = MutableStateFlow<Asked?>(null)

    private val notices = MutableStateFlow<ShelfNotice?>(null)

    /** The last shelf put down, until the screen has said so ([noticeShown]). */
    val notice: StateFlow<ShelfNotice?> = notices.asStateFlow()

    /** What this TV wrote to the person's own collection this session, laid over each library that a load of
     * their own shelf holds. */
    private val edits = MutableStateFlow(OwnEdits.NONE)

    /** Records a write the cloud took for [user]'s own collection; the collection on screen is built again
     * with it. Edits for an earlier account are dropped. */
    fun edited(user: String, change: (OwnEdits) -> OwnEdits) {
        edits.update { change(it.forUser(user)) }
    }

    /**
     * Which selection of each generated list this phone shows, by the list's store id: how many times "another
     * selection" was pressed on it (the iPhone's `GeneratedListReadings`). Kept for the session, whatever reloads.
     */
    private val readings = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The playlists and the generated lists read again on their own ([rereadLists]), for the shelf they were
     * read for. Laid over each library of that shelf read before them. */
    private val listsRead = MutableStateFlow<ListsRead?>(null)

    private data class ListsRead(val user: String, val shelf: Shelf, val playlists: List<CloudPlaylist>, val moods: CloudMoodLists?, val readAt: Long)

    /** The lists the collection on screen was last built from, so a read again that finds nothing new builds nothing. */
    @Volatile private var builtLists: Pair<List<CloudPlaylist>, CloudMoodLists?>? = null

    /**
     * Shows the next selection of the generated [list], when the computer sent more than one ([TrackList.slices]).
     * Nothing is fetched: the whole run is already held, and the step is arithmetic ([Lists.window]).
     */
    fun newList(list: TrackList) {
        if (!list.generated || list.slices <= 1) return
        readings.update { it + (list.storeId to ((it[list.storeId] ?: 0) + 1)) }
    }

    /**
     * Reads the playlists and the generated lists again, without the rest of the library, and without taking the
     * collection off the screen: the iPhone's `rereadPlaylists`, run when the app comes back to the front, because a
     * list can be changed on a computer while the phone is away. A read that fails, or finds the lists as they
     * were, changes nothing.
     */
    suspend fun rereadLists() {
        val user = auth.session.value?.userId ?: return
        val on = shown?.takeIf { it.user == user } ?: return
        val shelf = on.shelf
        val readAt = clock()
        val read = coroutineScope {
            val own = async(Dispatchers.IO) { orNull { cloud.playlists(shelf) } }
            val moods = async(Dispatchers.IO) { orNull { cloud.moodLists(shelf) } }
            own.await()?.let { it to moods.await() }
        } ?: return
        if (shown?.let { it.user == user && it.shelf == shelf } != true) return
        val before = builtLists
        if (before != null && before.first == read.first && (read.second == null || before.second == read.second)) return
        listsRead.value = ListsRead(user, shelf, read.first, read.second, readAt)
    }

    private data class Key(val user: String, val shelf: Shelf, val attempt: Int, val refresh: Int)

    init {
        scope.launch {
            combine(auth.session.map { it?.userId }.distinctUntilChanged(), asked, attempt, refreshes) { user, ask, n, r ->
                user?.let { Key(it, shelfFor(it, ask), n, r) }
            }
                .distinctUntilChanged()
                .collectLatest { key ->
                    if (key == null) current.value = LibraryState.SignedOut else load(key.user, key.shelf)
                }
        }
    }

    fun retry() {
        attempt.value += 1
    }

    /**
     * Reads the collection on screen from the cloud again: the side menu's reload key, and the start after Exit rpm
     * ([refreshIfExited]). The collection on screen stays while the read runs and is replaced by the answer; a read
     * that fails leaves it (see [load], `quiet`). Home keeps its shuffle.
     */
    fun refresh() {
        refreshes.value += 1
    }

    /**
     * Notes that the person left with Exit rpm. Android keeps the process after it, and the library loads once per
     * process, so the next start would show the collection read before. [refreshIfExited] reads it again then, so
     * that closing the app and opening it again reloads the collection. Leaving any other way (Home, another app) is
     * not an exit and reads nothing.
     */
    fun exited() {
        exitedSince = true
    }

    /** [refresh], once, if the person left with Exit rpm since the last start. */
    fun refreshIfExited() {
        if (!exitedSince) return
        exitedSince = false
        refresh()
    }

    /** Shows [shelf] instead of the collection on screen. Nothing happens signed out. */
    fun open(shelf: Shelf) {
        val user = auth.session.value?.userId ?: return
        asked.value = Asked(user, shelf)
    }

    /** The shelf on screen for the signed-in user: the one they asked for, else their own. */
    val viewing: Shelf get() = auth.session.value?.userId?.let { shelfFor(it, asked.value) } ?: Shelf.Own

    fun noticeShown(shown: ShelfNotice) {
        notices.compareAndSet(shown, null)
    }

    private fun shelfFor(user: String, ask: Asked?): Shelf = ask?.takeIf { it.user == user }?.shelf ?: Shelf.Own

    /** The library being built from: the cache's or the cloud's, and which. */
    private data class Held(val library: CloudLibrary, val fromCloud: Boolean)

    private suspend fun load(user: String, shelf: Shelf) = coroutineScope {
        // Quiet: this collection is on screen already ([refresh], or a retry over it). It stays until the cloud's
        // answer replaces it: no loading screen, no cache read in between, and a failed read leaves it. Read off
        // [shown], which names the user and shelf it was published for, so another account's or another shelf's
        // collection still on screen never makes a load quiet.
        val before = shown?.takeIf { it.user == user && it.shelf == shelf && current.value is LibraryState.Ready }
        val quiet = before != null
        if (!quiet) publishFor(user, shelf, LibraryState.Loading(shelf, 0)) else reading.value = true
        val started = SystemClock.elapsedRealtime()
        fun took(what: String) = Diag.d(TAG, "$what at ${SystemClock.elapsedRealtime() - started} ms${if (quiet) ", refresh" else ""}")
        val held = MutableStateFlow<Held?>(null)
        // One shuffle per load, as the desktop draws its banner's Most played card afresh each launch; the
        // same seed for every build of this load, so the cloud's copy replacing the cache's does not
        // reshuffle home under the person when nothing changed. A quiet load keeps the shuffle on screen.
        val seed = before?.seed ?: System.nanoTime()

        // The cache: shown at once, but never in place of the cloud's answer (the `held.update` keeps a
        // cloud library that is already there). Not read by a quiet load: what is on screen is at least as new.
        if (!quiet) launch {
            val cached = cache.read(user, shelf) ?: return@launch
            took("${cached.tracks.size} tracks from the cache")
            held.update { it ?: Held(cached, fromCloud = false) }
        }

        // The cloud: replaces the cache's library, and is cached for the next launch (below, with this
        // TV's edits laid over it). A failure with the cache on screen leaves it there.
        launch {
            try {
                val library = readCloud(user, shelf, held, progress = !quiet, ::took)
                held.value = Held(library, fromCloud = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException.NotFound) {
                if (shelf is Shelf.Borrowed) putDown(user, ShelfNotice.Withdrawn(shelf)) else if (held.value == null && !quiet) publishFor(user, shelf, LibraryState.Failed(shelf))
            } catch (e: ApiException.NotServed) {
                if (shelf is Shelf.Borrowed) putDown(user, ShelfNotice.NotServed(shelf))
            } catch (e: Exception) {
                if (held.value == null && !quiet) publishFor(user, shelf, LibraryState.Failed(shelf))
            } finally {
                // Cleared however the read ends, cancelled included: a newer load that is quiet sets it again.
                if (quiet) reading.value = false
            }
        }

        // Built again whenever the library held changes, or a computer's catalogue, or a computer comes or
        // goes; a build still running when the next change arrives is dropped for the newer one.
        // The computers hold the person's own music, so a borrowed shelf is built without them.
        val catalogs = if (shelf == Shelf.Own) homeCatalogs.here else flowOf(emptyMap())
        // The person's own edits too: one recorded rebuilds the collection with it, on the same seed. A
        // borrowed shelf is never written to from here, so it is built without them.
        val own = if (shelf == Shelf.Own) edits else flowOf(OwnEdits.NONE)
        val loading = this
        // What was cached last, so a rebuild for a computer coming or going does not write the same
        // library again.
        var cached: Triple<Held, OwnEdits, ListsRead?>? = null
        // The part of the last build that depends on the tracks alone, kept while the tracks and the computers stay
        // the same: a list read again, written or stepped to its next selection builds only the lists and home.
        var base: Base? = null
        combine(held.filterNotNull(), catalogs, own, readings, listsRead) { library, here, edited, chosen, lists -> Built(library, here, edited, chosen, lists) }
            .collectLatest { (library, here, edited, chosen, lists) ->
                val mine = if (edited.user == user) edited else OwnEdits.NONE
                // Lists read again after this library was read replace its lists; an older read is left out.
                val read = lists?.takeIf { it.user == user && it.shelf == shelf && it.readAt > library.library.readAt }
                val built = try {
                    withContext(Dispatchers.Default) {
                        val fresh = read?.let { library.library.copy(playlists = it.playlists, moods = it.moods ?: library.library.moods, readAt = it.readAt) } ?: library.library
                        val laid = mine.over(fresh)
                        // Keyed on the tracks as read and the marks laid over them: laying marks makes a new list of
                        // tracks on every build, though nothing in it changed.
                        val reused = base?.takeIf { it.tracks === fresh.tracks && it.marks == mine.marks && it.here === here }
                        val parts = reused ?: base(laid, here, ::took).copy(tracks = fresh.tracks, marks = mine.marks).also { base = it }
                        builtLists = laid.playlists to laid.moods
                        laid to build(laid, parts, seed, shelf, chosen, ::took)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val ready = built?.second
                // Cached as shown: the cloud's library with this TV's edits laid over it, so a write made
                // here survives a restart that comes before the next cloud read. Written
                // whenever either changes, from the cloud's library or with an edit on the cache's; never
                // the cache's own library back unchanged. In the load's scope, so the next rebuild does
                // not cut a write off, and through the cache's lock, which is first come first served, so
                // the last written is the latest.
                // The library by identity: a new read is a new object, and comparing twenty thousand tracks
                // to find that out is not worth it.
                val changed = cached?.let { (was, edits, lists) -> was !== library || edits != mine || lists != read } ?: true
                if (built != null && changed && (library.fromCloud || mine != OwnEdits.NONE || read != null)) {
                    cached = Triple(library, mine, read)
                    loading.launch { cache.write(user, shelf, built.first).also { took("cache written") } }
                }
                took("collection and home, from the ${if (library.fromCloud) "cloud" else "cache"}, ${edited.marks.size + edited.playlists.size} edits")
                // A quiet load whose build failed leaves the collection on screen, as its failed read does.
                if (ready == null && quiet) return@collectLatest
                publishFor(user, shelf, ready ?: LibraryState.Failed(shelf))
                if (ready != null && current.value === ready) shown = Shown(user, shelf, seed)
            }
    }

    /** A borrowed shelf refused: its cache is forgotten, and if it is still the one asked for, the
     * person is returned to their own collection and told why. */
    private suspend fun putDown(user: String, notice: ShelfNotice) {
        Diag.i(TAG, "shared collection put down: $notice")
        cache.delete(user, notice.shelf)
        if (asked.value == Asked(user, notice.shelf)) {
            notices.value = notice
            asked.value = Asked(user, Shelf.Own)
        }
    }

    /** The library's reads from the cloud: the tracks, then home's other reads side by side. Any one of
     * those failing is an empty answer for that one, not a failed library. Progress is shown
     * only while nothing else is on screen. */
    /** [progress]: whether the rows read so far may be shown as a loading screen; never for a quiet load, which has
     * a collection on screen. */
    private suspend fun readCloud(user: String, shelf: Shelf, held: StateFlow<Held?>, progress: Boolean, took: (String) -> Unit): CloudLibrary {
        val readAt = clock()
        val tracks = withContext(Dispatchers.IO) {
            cloud.list(shelf) { rows -> if (progress && held.value == null) publishFor(user, shelf, LibraryState.Loading(shelf, rows)) }
        }
        took("${tracks.size} tracks")
        return coroutineScope {
            val covers = async(Dispatchers.IO) { orNull { cloud.artIndex(shelf) }.also { took("cover index") } }
            // The plays are the person's own listening, by their own track ids, which a borrowed shelf's do
            // not match: a borrowed shelf has none.
            val plays = async(Dispatchers.IO) {
                if (shelf == Shelf.Own) orNull { cloud.trackPlays() }.orEmpty().also { took("${it.size} play rows") } else emptyList()
            }
            val moods = async(Dispatchers.IO) { orNull { cloud.moodLists(shelf) }.also { took("mood lists") } }
            val own = async(Dispatchers.IO) { orNull { cloud.playlists(shelf) }.orEmpty().also { took("${it.size} playlists") } }
            CloudLibrary(tracks, coverKeys = null, coverStamps = covers.await(), plays = plays.await(), moods = moods.await(), playlists = own.await(), readAt = readAt)
        }
    }

    /** One load's combined flows, as one value. */
    private data class Built(
        val library: Held,
        val here: Map<String, HeldCatalog>,
        val edited: OwnEdits,
        val chosen: Map<String, Int>,
        val lists: ListsRead?,
    )

    /** What a build makes from the tracks and the computers alone, kept by [load] while neither changes. [tracks] (as
     * read, before this phone's marks), [marks] and [here] are the inputs it was made from. */
    private data class Base(
        val tracks: List<com.rpm.player.core.cloud.CloudTrack>,
        val marks: Map<String, OwnEdits.Mark>,
        val here: Map<String, HeldCatalog>,
        val byId: Map<String, com.rpm.player.core.cloud.CloudTrack>,
        val albums: List<CloudAlbum>,
        val walls: Walls,
    )

    private fun base(library: CloudLibrary, here: Map<String, HeldCatalog>, took: (String) -> Unit): Base {
        val all = HomeMerge.merge(library.tracks, here.values)
        val byId = all.associateBy { it.rpmId }
        val albums = AlbumWall.group(all)
        took("${albums.size} albums grouped from ${all.size} tracks, ${here.size} computers")
        val walls = Walls.of(albums)
        took("${walls.artists.size} artists, ${walls.genres.size} genres")
        return Base(library.tracks, emptyMap(), here, byId, albums, walls)
    }

    private fun build(library: CloudLibrary, base: Base, seed: Long, shelf: Shelf, readings: Map<String, Int>, took: (String) -> Unit): LibraryState.Ready {
        val collection = Collection.of(
            shelf = shelf,
            albums = base.albums,
            tracksById = base.byId,
            plays = Lists.plays(library.plays),
            lists = Lists.generated(library.moods, base.byId, shelf, readings) + Lists.own(library.playlists, base.byId),
            index = library.coverIndex,
            moods = Lists.moods(library.moods),
        )
        took("collection")
        val home = HomeShelf.build(collection, Random(seed))
        took("home")
        return LibraryState.Ready(collection, home, base.walls)
    }

    /** The answer, or null for any failure but a cancellation, which is passed on. */
    private suspend fun <T> orNull(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun publishFor(user: String, shelf: Shelf, next: LibraryState) {
        if (auth.session.value?.userId == user && shelfFor(user, asked.value) == shelf) current.value = next
    }

    private companion object {
        /** Where the load writes how long each part took, for `adb logcat -s rpm.library`. */
        const val TAG = "rpm.library"
    }
}
