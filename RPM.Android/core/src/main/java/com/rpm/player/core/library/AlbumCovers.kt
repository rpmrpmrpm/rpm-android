package com.rpm.player.core.library

import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The cover of a record the reader does not have, by its artist and title: for a review on a profile page. Asked of the
 * rpm API's cover search (`artwork/search`), the first picture it finds; a record in the reader's own collection shows
 * its own cover and never asks here.
 *
 * One ask per record for the life of the app, and two asks while the first is out share its answer. A record with no
 * cover found is kept as having none; an ask that failed is forgotten, so it is asked again the next time.
 */
class AlbumCovers(
    private val http: OkHttpClient,
    /** The reader's sign-in: the cover search answers a signed-in caller only. */
    private val token: suspend () -> String?,
) {
    private val lock = Mutex()
    private val known = HashMap<String, CompletableDeferred<String?>>()
    private val answered = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The cover already found for this record, without asking. */
    fun peek(artist: String, title: String): String? = answered[keyOf(artist, title)]

    suspend fun of(artist: String, title: String): String? {
        val key = keyOf(artist, title)
        if (key.isBlank()) return null
        val (asked, mine) = lock.withLock {
            known[key]?.let { it to false } ?: CompletableDeferred<String?>().also { known[key] = it }.let { it to true }
        }
        if (!mine) return asked.await()
        val answer = try {
            fetch(artist, title)
        } catch (e: CancellationException) {
            lock.withLock { known.remove(key) }
            asked.complete(null)
            throw e
        }
        when (answer) {
            is Answer.Found -> answered[key] = answer.cover
            Answer.Failed -> lock.withLock { known.remove(key) }
            Answer.None -> {}
        }
        val cover = (answer as? Answer.Found)?.cover
        asked.complete(cover)
        return cover
    }

    private sealed interface Answer {
        data class Found(val cover: String) : Answer
        data object None : Answer
        data object Failed : Answer
    }

    @Serializable
    private data class Found(val results: List<Candidate> = emptyList())

    @Serializable
    private data class Candidate(val thumbUrl: String = "", val fullUrl: String = "")

    private suspend fun fetch(artist: String, title: String): Answer {
        val url = (Hosts.API + "artwork/search").toHttpUrl().newBuilder()
            .addQueryParameter("artist", artist.trim())
            .addQueryParameter("album", title.trim())
            .addQueryParameter("limit", "1")
            .build()
        val bearer = token()
        val request = Request.Builder().url(url).header(AppKey.HEADER, AppKey.VALUE)
            .apply { if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer") }
            .build()
        return try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return Answer.Failed
                val first = WireJson.decodeFromString(Found.serializer(), response.body.string()).results.firstOrNull()
                val cover = first?.thumbUrl?.ifEmpty { first.fullUrl }.orEmpty()
                if (cover.isEmpty()) Answer.None else Answer.Found(cover)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Answer.Failed
        }
    }

    private fun keyOf(artist: String, title: String): String = artist.trim().lowercase() + "\u001F" + title.trim().lowercase()
}
