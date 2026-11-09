package com.rpm.player.core.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.RadioCrate
import com.rpm.player.core.library.SongRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * The song radios, run where playback lives so a radio carries on with the app gone.
 *
 * A radio's queue items carry its name ([EXTRA_RADIO]), and that is the only thing that says the radio is on, as
 * the queue rows carry it on the computer: an item of a radio becoming the current one asks that radio for the songs
 * after it, and a queue replaced by anything else holds none of its items. There is no flag to forget to clear.
 * A song added with Play next plays in its turn, and the radio carries on from its own item after it.
 *
 * Runs on the player's thread. Only the picking goes to a worker, and what it returns is checked again against the
 * queue as it is when the answer lands, so an answer that arrives after the queue changed adds nothing it should not.
 */
class SongRadioRuns(
    private val player: Player,
    private val scope: CoroutineScope,
    /** The collection on screen, or null while none is loaded. */
    private val collection: () -> Collection?,
    /** Whether a list that ends goes on as a mixtape ([carryOn]): the person's setting, read when a last song starts. */
    private val mixtapeAfter: () -> Boolean = { false },
) {
    /** One radio: the song it started from, and every song it has queued, oldest first. */
    private class Run(val origin: String) {
        val history = ArrayList<String>()
        val queued = HashSet<String>()

        fun note(id: String) {
            if (queued.add(id)) history.add(id)
        }
    }

    private val runs = HashMap<String, Run>()
    private var crate: Pair<Collection, RadioCrate>? = null
    private var asking: Job? = null

    fun attach() {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                topUp()
                carryOn()
            }
        })
    }

    /** The current item belongs to a radio: picks songs to follow it, on a worker, and queues them when they come. */
    private fun topUp() {
        val item = player.currentMediaItem ?: return
        val name = item.mediaMetadata.extras?.getString(EXTRA_RADIO) ?: return
        val ref = TrackRef.parse(item.mediaId) ?: return

        // Radios with no item left in the queue are over.
        val live = (0 until player.mediaItemCount).mapNotNullTo(HashSet()) { radioOf(player.getMediaItemAt(it)) }
        runs.keys.retainAll(live)
        val run = runs.getOrPut(name) { Run(originOf(name)).also { it.note(originOf(name)) } }

        if (wanted() <= 0) return
        // What is queued, and what was heard lately, which a radio does not play again so soon.
        val inQueue = (0 until player.mediaItemCount).mapNotNullTo(HashSet()) { TrackRef.parse(player.getMediaItemAt(it).mediaId)?.rpmId } +
            ListeningHistory.recent(ref.shelf)
        val history = run.history.toList()
        val held = collection() ?: return
        // A radio picks from the collection its song is in; one started on another shelf than the one now on
        // screen has nothing to pick from.
        if (held.shelf != ref.shelf) return

        asking?.cancel()
        asking = scope.launch {
            val picks = withContext(Dispatchers.Default) {
                SongRadio.pick(crateOf(held), ref.rpmId, run.origin, inQueue, history, SongRadio.AHEAD, Random.Default)
            }
            take(name, run, held, picks)
        }
    }

    /**
     * The last song of an album, a playlist or a single has started: with [mixtapeAfter] on, a mixtape of songs like
     * the list is queued after it, so the music carries on instead of stopping. It is a song radio like any other,
     * named after the list's first song and with the whole list in its history, so it does not play the list again,
     * and from its first item on it tops itself up as every radio does ([topUp]). Not after a radio's own song, a
     * station, or a queue set to repeat.
     */
    private fun carryOn() {
        val at = player.currentMediaItemIndex
        if (at != player.mediaItemCount - 1 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        val item = player.currentMediaItem ?: return
        if (radioOf(item) != null) return
        val ref = TrackRef.parse(item.mediaId) ?: return
        if (!mixtapeAfter()) return
        val held = collection() ?: return
        if (held.shelf != ref.shelf) return
        val list = (0 until player.mediaItemCount).mapNotNull { TrackRef.parse(player.getMediaItemAt(it).mediaId)?.rpmId }
        val first = list.firstOrNull() ?: return
        val name = newRun(first)
        val run = Run(first).also { r -> list.forEach(r::note) }
        val mediaId = item.mediaId
        asking?.cancel()
        asking = scope.launch {
            val picks = withContext(Dispatchers.Default) {
                SongRadio.pick(crateOf(held), ref.rpmId, first, list.toHashSet() + ListeningHistory.recent(ref.shelf), run.history.toList(), SongRadio.AHEAD, Random.Default)
            }
            // Only if the list's last song is still the one playing and still the last in the queue.
            if (player.currentMediaItem?.mediaId != mediaId || player.currentMediaItemIndex != player.mediaItemCount - 1) return@launch
            runs[name] = run
            append(name, run, held, picks, SongRadio.AHEAD)
        }
    }

    /** Appends picks until [SongRadio.AHEAD] songs follow the current one, if the radio is still the one playing. */
    private fun take(name: String, run: Run, held: Collection, picks: List<String>) {
        val current = player.currentMediaItem ?: return
        if (radioOf(current) != name) return
        append(name, run, held, picks, wanted())
    }

    /** Appends up to [wanted] of [picks] to the queue as songs of the radio [name]. */
    private fun append(name: String, run: Run, held: Collection, picks: List<String>, wanted: Int) {
        if (wanted <= 0) return
        val inQueue = (0 until player.mediaItemCount).mapNotNullTo(HashSet()) { TrackRef.parse(player.getMediaItemAt(it).mediaId)?.rpmId }
        val items = ArrayList<MediaItem>()
        for (id in picks) {
            if (items.size == wanted) break
            // Queued by this radio once and since taken out by the listener: not put back.
            if (id in run.queued || id in inQueue) continue
            val track = held.tracksById[id] ?: continue
            val queued = queuedOf(held, track).copy(radio = name)
            val ref = TrackRef(queued.shelf, id)
            // Straight into the player, so the address the session puts on a controller's items is put on here.
            items.add(mediaItemOf(queued).buildUpon().setUri(CloudTrackUri.of(ref)).build())
            run.note(id)
        }
        if (items.isNotEmpty()) player.addMediaItems(items)
    }

    private fun wanted(): Int = SongRadio.AHEAD - (player.mediaItemCount - 1 - player.currentMediaItemIndex)

    /** The crate of [held], built once per collection. A collection is equal only to itself, so a reload builds
     * a new one. */
    private fun crateOf(held: Collection): RadioCrate = synchronized(this) {
        crate?.takeIf { it.first === held }?.second ?: RadioCrate.of(held).also { crate = held to it }
    }

    companion object {
        /** A new radio's name: the song it starts from, and a stamp, so a second radio on the same song is a new
         * radio with a history of its own. */
        fun newRun(origin: String): String = "$origin#${System.nanoTime()}"

        internal fun originOf(name: String): String = name.substringBefore('#')

        /** Where the TV and the phone keep the mixtape-after-a-record setting ([carryOn]): its settings file and key.
         * On the TV it is on unless the person turns it off ([MIXTAPE_AFTER_DEFAULT]); on the phone it is off unless
         * they turn it on in the up next drawer ([MIXTAPE_AFTER_PHONE]). */
        const val SETTINGS_FILE = "rpm.settings"
        const val MIXTAPE_AFTER = "mixtape.after"
        const val MIXTAPE_AFTER_DEFAULT = true
        const val MIXTAPE_AFTER_PHONE = false

        private fun radioOf(item: MediaItem): String? = item.mediaMetadata.extras?.getString(EXTRA_RADIO)
    }
}
