package com.rpm.player.core.playback

import com.rpm.player.core.cloud.CloudListen
import com.rpm.player.core.net.WireJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * The listens this device has not sent to the store yet.
 *
 * Every listen worth writing down (a play or a skip, [Listen.worthRecording]) goes to the store's listening log
 * (`cloud/listens`), which cuts the lists that read it: On repeat, Rediscover, Late night, First listens, and the
 * hourly lean of Your mix. This device does not cut those lists, so it keeps nothing it has sent.
 *
 * Each listen gets its id here, when it is noted. The store keys on it, so a batch sent again after a lost answer
 * is stored once. The listens belong to the account that was signed in when they were heard: another account
 * starts with an empty log, as [TrackPlayLog] starts over.
 */
class ListenLog(private val file: File, private val keepAtMost: Int = CEILING) {

    /** One listen, as the player handed it over. */
    data class Heard(
        val rpmId: String,
        val at: Long,
        val heardSeconds: Double,
        val lengthSeconds: Double,
        val counted: Boolean,
        val localHour: Int,
        val localDayOfWeek: Int,
    )

    @Serializable
    private data class Model(val account: String? = null, val unsent: List<CloudListen> = emptyList())

    private val mutex = Mutex()
    private var read = false
    private var mayWrite = true
    private var account: String? = null
    private val unsent = ArrayList<CloudListen>()

    /** Notes one finished listen for [userId]. A track with no cloud id is not kept: the store has nothing to
     * join it to. */
    suspend fun note(userId: String, heard: Heard) {
        if (heard.rpmId.isBlank()) return
        mutex.withLock {
            readOnce()
            if (!mayWrite) return@withLock
            adopt(userId)
            unsent += CloudListen(
                listenId = UUID.randomUUID().toString(),
                rpmId = heard.rpmId,
                at = heard.at,
                heardSeconds = heard.heardSeconds,
                lengthSeconds = heard.lengthSeconds,
                counted = heard.counted,
                localHour = heard.localHour,
                localDayOfWeek = heard.localDayOfWeek,
            )
            if (unsent.size > keepAtMost) unsent.subList(0, unsent.size - keepAtMost).clear()
            save()
        }
    }

    /** The oldest listens [userId] has not sent, at most [limit]. None for an account the log does not hold. */
    suspend fun unsent(userId: String, limit: Int): List<CloudListen> = mutex.withLock {
        readOnce()
        if (account != userId) emptyList() else unsent.take(limit.coerceAtLeast(0))
    }

    /** Records that the store has taken these listens. Listens noted while they were on their way stay. */
    suspend fun noteSent(sent: List<CloudListen>) {
        if (sent.isEmpty()) return
        mutex.withLock {
            readOnce()
            val ids = sent.mapTo(HashSet()) { it.listenId }
            if (unsent.removeAll { it.listenId in ids }) save()
        }
    }

    private fun adopt(userId: String) {
        if (account == userId) return
        account = userId
        unsent.clear()
    }

    private fun readOnce() {
        if (read) return
        read = true
        if (!file.exists()) return
        val model = try {
            WireJson.decodeFromString(Model.serializer(), file.readText())
        } catch (_: Exception) {
            // A file that cannot be read is never written over: the next save would replace its listens with none.
            mayWrite = false
            return
        }
        account = model.account
        unsent.addAll(model.unsent)
    }

    private fun save() {
        if (!mayWrite) return
        try {
            val text = WireJson.encodeToString(Model.serializer(), Model(account, unsent.toList()))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (_: Exception) {
            // Kept in memory and written with the next listen.
        }
    }

    companion object {
        /** The most listens kept. A device that never reaches the store drops the oldest past this. */
        const val CEILING = 20_000
    }
}
