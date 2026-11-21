package com.rpm.player.core.papers

import com.rpm.player.core.library.ArtistShelves
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** What a page asks about: a record or an act. The Android side of the iOS `ArticleStore.Subject`. */
sealed interface PaperSubject {
    /** Which record or act this is about. It is the same for every spelling that differs only in case and spaces. */
    val id: String

    /** A subject with no name is not asked about at all. */
    val blank: Boolean
}

/** What a record page asks about: the album artist and title as the files spell them, and the number
 * of songs on the record. */
data class AlbumSubject(val artist: String, val title: String, val tracks: Int) : PaperSubject {
    /** Which record this is about. The track count is not part of it: it helps the server tell an album
     * from a single, but it is a property of the record that the artist and title already name. */
    override val id: String get() = "album\u0001" + norm(artist) + "\u0001" + norm(title)

    override val blank: Boolean get() = artist.isBlank() || title.isBlank()
}

/**
 * What an artist's page asks about: the act's name as the wall shows it, and [records], their own records the act is
 * identified through, in the order they are tried ([PapersClient.artist]).
 */
data class ArtistSubject(val name: String, val records: List<AlbumSubject>) : PaperSubject {
    /** Which act this is about: the name, whatever [records] are named. The id is part of the stored key, so a
     * change to its prefix makes the stored articles unreadable. */
    override val id: String get() = "act\u0001" + norm(name)

    override val blank: Boolean get() = name.isBlank()

    companion object {
        /** The act named [name], identified through [shelves]: their ordinary records, then their live records,
         * soundtracks, compilations and singles, the records with the most songs first on each shelf (an album is in
         * more catalogues than an EP of the same year). Records made with others are left out, whose credit can name
         * the other act first. */
        fun of(name: String, shelves: ArtistShelves): ArtistSubject {
            val records = listOf(shelves.records, shelves.live, shelves.soundtracks, shelves.compilations, shelves.singles)
                .flatMap { shelf -> shelf.sortedByDescending { it.tracks.size } }
                .map { AlbumSubject(it.artist, it.album, it.tracks.size) }
            return ArtistSubject(name, records)
        }
    }
}

/** What a genre's page asks about: the genre as the app spells it ([com.rpm.player.core.library.Genres]). */
data class GenreSubject(val name: String) : PaperSubject {
    override val id: String get() = "genre\u0001" + norm(name)

    override val blank: Boolean get() = name.isBlank()
}

private fun norm(text: String) = text.trim().lowercase()

/**
 * What is said about records and acts, kept in memory and on disk. It matches the desktop's
 * `TrackInfoService` cache and the iOS `ArticleStore`, with the same two clocks. A dossier with
 * something in it is kept for 30 days and an empty one for 5, so a record that gets written up next
 * week is found next week. A failed request is not kept at all: offline is a temporary condition,
 * and saving it would leave the page blank after the network came back. An answer that the API
 * marked partial is not kept either, because the API finishes it in the background.
 *
 * **Two reads, for the two moments a page needs one.** [cached] answers while the page is built,
 * from memory or a small file, and never asks the network, so a record opened before shows its text
 * with the rest of the page. [read] answers from the same caches and goes to the network only where
 * they have no answer.
 *
 * **A request outlives whoever asked.** It runs in [scope], not in the page's coroutine. A page that
 * is left before the answer arrives does not cancel the request, and the answer is kept, so the
 * record is ready the next time it is opened. Two pages asking about one record make one request.
 *
 * Thread-safe: the maps are touched under [lock] only, and nothing waits while holding it.
 */
class Dossiers(
    languages: List<String>,
    private val directory: File?,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val fetch: suspend (PaperSubject, List<String>) -> DossierAnswer,
) {
    constructor(client: PapersClient, languages: List<String>, directory: File?, scope: CoroutineScope, clock: () -> Long) :
        this(languages, directory, scope, clock, { subject, wikis ->
            when (subject) {
                is AlbumSubject -> client.album(subject.artist, subject.title, subject.tracks, wikis)
                is ArtistSubject -> client.artist(subject.name, subject.records, wikis)
                is GenreSubject -> client.genre(subject.name)
            }
        })

    /**
     * The Wikipedias to read, best first. An app may change them while it runs (the phone's description language). Every
     * cache key carries them, so a change is a new question, and an answer kept in the other languages is not shown.
     */
    @Volatile
    var languages: List<String> = languages

    @Serializable
    private data class Kept(val dossier: Dossier)

    private val lock = Any()
    private val memory = HashMap<String, Dossier>()

    /** Keys whose file was looked for in this session and was not found, so a page opened again does
     * not go to the disk for a missing file. */
    private val notOnDisk = HashSet<String>()

    /** Requests in flight, keyed like the caches. Each request removes itself when it ends, whoever is waiting. */
    private val running = HashMap<String, Deferred<DossierAnswer>>()

    init {
        directory?.mkdirs()
    }

    /** The dossier if it is already known, without asking anybody. Null when nobody has asked yet, and
     * for a subject that is not asked about (a blank name). Reads one small file at most. */
    fun cached(subject: PaperSubject): Dossier? {
        val key = key(subject) ?: return null
        return known(key)
    }

    /**
     * The dossier, from the caches where they have an answer and from the network where they do not.
     * Null when the request failed, and for a blank name. [onAsking] is called on the caller's
     * coroutine just before it starts waiting on the network, and not at all when a cache answers, so
     * a page shows its wait only for a real one.
     */
    suspend fun read(subject: PaperSubject, onAsking: () -> Unit): Dossier? {
        val key = key(subject) ?: return null
        withContext(Dispatchers.IO) { known(key) }?.let { return it }

        val asking = synchronized(lock) {
            // Asked again under the lock: a request for the same key can finish while `known` above is
            // reading, and `keep` puts its answer in memory before the request leaves `running`. So here
            // there is always either the answer or the request in flight, never a second request.
            memory[key]?.let { return it }
            running[key] ?: scope.async(Dispatchers.IO) {
                try {
                    fetch(subject, languages).also { answer ->
                        // A partial answer is shown but not kept, not even in memory: the next page that
                        // opens asks again and gets the full answer.
                        if (!answer.partial) keep(key, answer.dossier)
                    }
                } finally {
                    synchronized(lock) { running.remove(key) }
                }
            }.also { running[key] = it }
        }
        onAsking()
        return try {
            asking.await().dossier
        } catch (e: CancellationException) {
            // The caller leaving is passed on. The request being cancelled (the app's scope ending) counts as a
            // failed request, which gives null.
            currentCoroutineContext().ensureActive()
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Null for a subject that is not asked about: a blank name. Every key carries the languages, so
     * a different language is a different answer, and it carries [VERSION]. */
    private fun key(subject: PaperSubject): String? {
        if (subject.blank) return null
        return VERSION + "\u0001" + languages.joinToString(",") + "\u0001" + subject.id
    }

    // ── memory and disk ──

    private fun known(key: String): Dossier? {
        synchronized(lock) {
            memory[key]?.let { return it }
            if (key in notOnDisk) return null
        }
        val read = readDisk(key)
        synchronized(lock) {
            if (read == null) {
                if (key !in memory) notOnDisk += key
                return memory[key]
            }
            memory.putIfAbsent(key, read)
            return memory[key]
        }
    }

    private fun keep(key: String, dossier: Dossier) {
        synchronized(lock) {
            memory[key] = dossier
            notOnDisk -= key
        }
        writeDisk(key, dossier)
    }

    private fun file(key: String): File? {
        val directory = directory ?: return null
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(directory, hash.take(32) + ".json")
    }

    private fun readDisk(key: String): Dossier? = try {
        val file = file(key)?.takeIf { it.isFile }
        file?.let {
            val kept = WireJson.decodeFromString(Kept.serializer(), it.readText())
            val age = clock() - it.lastModified()
            kept.dossier.takeIf { d -> age <= (if (d.isEmpty) BLANK_MS else FOUND_MS) }
        }
    } catch (_: Exception) {
        null
    }

    private fun writeDisk(key: String, dossier: Dossier) {
        val file = file(key) ?: return
        try {
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(WireJson.encodeToString(Kept.serializer(), Kept(dossier)))
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        } catch (_: Exception) {
            // A dossier that could not be written is asked for again next time; nothing else depends on it.
        }
    }

    companion object {
        /** Raise this when a change on the server would give a record a better answer than the one on
         * disk. Old files are never read again. They stay in the cache folder, which the system empties. */
        private const val VERSION = "v1"
        private const val FOUND_MS = 30L * 24 * 3600 * 1000
        private const val BLANK_MS = 5L * 24 * 3600 * 1000
    }
}
