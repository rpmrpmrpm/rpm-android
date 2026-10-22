package com.rpm.player.core.playback

import android.net.Uri
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * The address the queue holds for a cloud track: `rpm-cloud://track/<rpmId>` for the person's own, or
 * `rpm-cloud://shared/<owner id>/<rpmId>` for a track on a shelf another account shares. It is turned
 * into a signed URL only when the player opens it (see [StreamLinks]), so a URL never expires while it
 * waits in the queue.
 */
object CloudTrackUri {
    private const val SCHEME = "rpm-cloud"
    private const val OWN = "track"
    private const val SHARED = "shared"

    fun of(ref: TrackRef): Uri = when (val shelf = ref.shelf) {
        Shelf.Own -> Uri.Builder().scheme(SCHEME).authority(OWN).appendPath(ref.rpmId).build()
        is Shelf.Borrowed -> Uri.Builder().scheme(SCHEME).authority(SHARED).appendPath(shelf.ownerId).appendPath(ref.rpmId).build()
    }

    fun ref(uri: Uri): TrackRef? {
        if (uri.scheme != SCHEME) return null
        val path = uri.pathSegments
        return when (uri.host) {
            OWN -> path.singleOrNull()?.let { TrackRef(Shelf.Own, it) }
            SHARED -> if (path.size == 2 && path[0].isNotBlank()) TrackRef(Shelf.Borrowed(path[0]), path[1]) else null
            else -> null
        }
    }
}

/**
 * Where to open a track, and whether what answers there is a live encode: the cloud making the rate while it sends,
 * which has no length and is read through [LiveEncodeSource]. Only a cloud address is ever live.
 */
data class StreamLink(val url: String, val live: Boolean = false)

/**
 * Signed stream URLs, kept per track (with its shelf) and rate, each with its own expiry.
 *
 * Asked at every open: at the start of a track, at a seek and at a retry. An entry with less than
 * [MIN_LEFT_MS] left is not used.
 */
class StreamLinks(private val cloud: CloudClient, private val clock: () -> Long) {

    private data class Entry(val link: StreamLink, val expiresAtMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Blocking: called on an ExoPlayer loader thread, which may block. [kilobits] has no default,
     * because the rate is the caller's choice and the cache is keyed by it. */
    fun resolveBlocking(ref: TrackRef, kilobits: Int): StreamLink {
        val cacheKey = "${ref.mediaId}@$kilobits"
        entries[cacheKey]?.takeIf { usable(it.expiresAtMillis, clock()) }?.let { return it.link }

        val ticket = runBlocking { cloud.stream(ref.shelf, ref.rpmId, kilobits) }
        val lifetime = (ticket.expiresInSeconds ?: DEFAULT_LIFETIME_S) * 1000L
        val link = StreamLink(ticket.url, live = ticket.live == true)
        entries[cacheKey] = Entry(link, clock() + lifetime)
        return link
    }

    companion object {
        /** A signed URL with less time than this left is replaced before it is opened. */
        const val MIN_LEFT_MS = 5 * 60_000L

        /** What the API signs for when it does not say: six hours. */
        const val DEFAULT_LIFETIME_S = 6 * 3600L

        fun usable(expiresAtMillis: Long, now: Long): Boolean = expiresAtMillis - now > MIN_LEFT_MS
    }
}
