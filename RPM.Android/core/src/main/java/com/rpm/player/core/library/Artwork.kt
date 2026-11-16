package com.rpm.player.core.library

import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** What the rpm API's `artwork` answers for an artist and album: photos of the artist (fanart.tv and
 * TheAudioDB), square pictures of the artist ([thumbs], which the desktop's ArtistPortraits.SquareAsync and
 * the phone read first), and the artist's logo. It matches the desktop's `Artwork` record. */
@Serializable
data class ArtistArtwork(
    val backgrounds: List<String> = emptyList(),
    val thumbs: List<String> = emptyList(),
    val logo: String? = null,
)

/**
 * The artist photos and logos home's banner is dressed in (the desktop's ArtworkService), asked once per
 * artist. Two asks for the same artist while the first is out share its answer. A failed ask is not kept, so the
 * next card for that artist asks again.
 *
 * **The answers are kept on disk ([store])**, not only for the life of the app. The pictures themselves are in the
 * image loader's disk cache, but a picture can only be asked for once its address is known. With the answers in
 * memory only, every artist box and artist page after a launch waited for the API before its photo could start, and
 * the photos came in long after the page. Now [peek] knows the address from the first frame. An answer older than
 * [STALE_MS] is still used, and asked again in the background so a new photo is found.
 *
 * [peek] reads an answer already kept without asking or waiting, so a card composed again can start from it.
 *
 * @param store the file the answers are kept in; null keeps them in memory only, as the tests do.
 * @param scope where the file is written, a few seconds after the last answer.
 */
class Artwork(private val http: OkHttpClient, private val store: File? = null, private val scope: CoroutineScope? = null) {
    /** The write that waits for answers to stop arriving. A new answer starts the wait again. */
    private var pending: Job? = null

    private val lock = Mutex()
    private val known = HashMap<String, CompletableDeferred<ArtistArtwork?>>()

    /**
     * The answers kept, by the same key as [known], for [peek]: read on the main thread, written by the ask. Read from
     * [store] the first time it is needed: one small file, read once.
     */
    private val answered by lazy {
        java.util.concurrent.ConcurrentHashMap<String, Kept>().also { map ->
            runCatching { store?.takeIf { it.exists() }?.readText() }.getOrNull()?.let { text ->
                runCatching { WireJson.decodeFromString(Stored.serializer(), text) }.getOrNull()?.artists?.let(map::putAll)
            }
        }
    }

    /** How many answers arrived since the file was last written. */
    private val unsaved = java.util.concurrent.atomic.AtomicInteger()
    private val saving = Mutex()

    /** The answer kept for [artist], or null when there is none yet (not asked, still out, or failed). Never asks. */
    fun peek(artist: String): ArtistArtwork? {
        val key = artist.trim().lowercase()
        return if (key.isEmpty()) null else answered[key]?.art
    }

    suspend fun of(artist: String, album: String): ArtistArtwork? {
        val key = artist.trim().lowercase()
        if (key.isEmpty()) return null
        // A fresh answer from an earlier run is the answer: no ask.
        answered[key]?.takeIf { System.currentTimeMillis() - it.at < STALE_MS }?.let { return it.art }
        val (asked, mine) = lock.withLock {
            known[key]?.let { it to false } ?: CompletableDeferred<ArtistArtwork?>().also { known[key] = it }.let { it to true }
        }
        if (!mine) return asked.await()
        val answer = try {
            fetch(artist, album)
        } catch (e: CancellationException) {
            lock.withLock { known.remove(key) }
            asked.complete(null)
            throw e
        }
        if (answer == null) {
            lock.withLock { known.remove(key) }
        } else {
            answered[key] = Kept(answer, System.currentTimeMillis())
            if (unsaved.incrementAndGet() >= SAVE_EVERY) {
                save()
            } else if (scope != null) {
                pending?.cancel()
                pending = scope.launch {
                    delay(SAVE_AFTER_MS)
                    save()
                }
            }
        }
        asked.complete(answer ?: answered[key]?.art)
        return answer ?: answered[key]?.art
    }

    /** Writes the answers to [store], whole, off the main thread. A write already running is not doubled. */
    suspend fun save() {
        val file = store ?: return
        if (!saving.tryLock()) return
        try {
            unsaved.set(0)
            withContext(Dispatchers.IO) {
                val text = WireJson.encodeToString(Stored.serializer(), Stored(HashMap(answered)))
                val next = File(file.path + ".new")
                next.writeText(text)
                next.renameTo(file)
            }
        } catch (_: Exception) {
            // Kept in memory; written with the next batch.
        } finally {
            saving.unlock()
        }
    }

    @Serializable
    private data class Kept(val art: ArtistArtwork, val at: Long)

    @Serializable
    private data class Stored(val artists: Map<String, Kept> = emptyMap())

    private companion object {
        /** An answer this old is used and asked again: an artist's photos change rarely. */
        const val STALE_MS = 14L * 24 * 60 * 60 * 1000

        /** The file is written after this many new answers, or [SAVE_AFTER_MS] after the last one. */
        const val SAVE_EVERY = 20
        const val SAVE_AFTER_MS = 5_000L
    }

    private suspend fun fetch(artist: String, album: String): ArtistArtwork? {
        val url = (Hosts.API + "artwork").toHttpUrl().newBuilder()
            .addQueryParameter("artist", artist)
            .addQueryParameter("album", album)
            .build()
        val request = Request.Builder().url(url).header(AppKey.HEADER, AppKey.VALUE).build()
        return try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return null
                WireJson.decodeFromString(ArtistArtwork.serializer(), response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
