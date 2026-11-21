package com.rpm.player.core.playback

import com.rpm.player.core.library.DayPart
import com.rpm.player.core.library.DayParts
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * How many plays this device counted in each part of the day ([DayParts]), by the genre of the record played. It is
 * the part of the desktop's listening profile that the home banner reads (`ListeningProfile.GenresAt`): which kinds of
 * music this listener plays in the morning, in the afternoon, in the evening and at night ([com.rpm.player.core.library.MoodPick]).
 *
 * Like the desktop's, it counts this device's own listening only: the cloud keeps one total per track per device,
 * with no time of day. A play whose record has no genre the app knows is not counted, because it says nothing about
 * which kind of music is played when.
 *
 * The counts belong to the account they were played under. A play for another account starts the counts over, as
 * [TrackPlayLog] does. Kept in one small file (four parts by the app's genres), written through a temporary file on
 * IO. A file that cannot be read is never overwritten.
 */
class DayPartPlays(private val file: File, private val scope: CoroutineScope) {

    @Serializable
    private data class Model(
        val account: String? = null,
        /** Plays by part of the day ([DayPart.name]), then by genre. */
        val parts: Map<String, Map<String, Int>> = emptyMap(),
    )

    private val lock = Any()
    private var read = false
    private var mayWrite = true
    private var account: String? = null
    private val parts = HashMap<DayPart, HashMap<String, Int>>()

    /** Counts one play of a record filed under [genre], counted at [at] (Unix seconds), for [userId]. */
    fun note(userId: String, genre: String, at: Long) {
        if (genre.isBlank()) return
        val part = DayParts.of(hourOf(at))
        scope.launch(Dispatchers.IO) { count(userId, genre, part) }
    }

    /** [note]'s work, on the caller's thread. */
    internal fun count(userId: String, genre: String, part: DayPart) = synchronized(lock) {
        readOnce()
        if (!mayWrite) return@synchronized
        if (account != userId) {
            account = userId
            parts.clear()
        }
        val genres = parts.getOrPut(part) { HashMap() }
        genres[genre] = (genres[genre] ?: 0) + 1
        save()
    }

    /** What [userId] plays in [part] on this device, most played first ([ranked]). Empty for another account. Reads
     * the file the first time, so it is called off the main thread. */
    fun genresAt(userId: String, part: DayPart): List<String> = synchronized(lock) {
        readOnce()
        if (account != userId) emptyList() else ranked(parts[part].orEmpty())
    }

    private fun readOnce() {
        if (read) return
        read = true
        if (!file.exists()) return
        val model = try {
            WireJson.decodeFromString(Model.serializer(), file.readText())
        } catch (_: Exception) {
            mayWrite = false
            return
        }
        account = model.account
        for ((name, genres) in model.parts) {
            val part = DayPart.entries.firstOrNull { it.name == name } ?: continue
            parts[part] = HashMap(genres)
        }
    }

    private fun save() {
        try {
            val model = Model(account, parts.entries.associate { (part, genres) -> part.name to genres.toMap() })
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(WireJson.encodeToString(Model.serializer(), model))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: Exception) {
            // Kept in memory and written with the next play.
        }
    }

    companion object {
        /** The hour of [at] (Unix seconds) on this device's clock. */
        fun hourOf(at: Long, zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochSecond(at).atZone(zone).hour

        /**
         * The genres most played first; the name breaks a tie, so two reads of the same counts give the same order.
         * The desktop's `ListeningProfile` sorts its shares the same way. A genre with no plays is left out.
         */
        fun ranked(plays: Map<String, Int>): List<String> = plays.entries
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
    }
}
