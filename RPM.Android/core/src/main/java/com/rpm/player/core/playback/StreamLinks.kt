package com.rpm.player.core.playback

import android.net.Uri
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.cloud.StreamTicket
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
class StreamLinks(
    /** Signs [TrackRef] at a rate: `cloud/stream`. Blocking. */
    private val sign: (TrackRef, Int) -> StreamTicket,
    private val clock: () -> Long,
) {
    constructor(cloud: CloudClient, clock: () -> Long) : this({ ref, kilobits -> runBlocking { cloud.stream(ref.shelf, ref.rpmId, kilobits) } }, clock)

    /** A signed address, until when its signature holds, and until when it is used before the cloud is asked again. */
    private data class Entry(val link: StreamLink, val expiresAtMillis: Long, val keptUntilMillis: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * Blocking: called on an ExoPlayer loader thread, which may block. [kilobits] has no default,
     * because the rate is the caller's choice and the cache is keyed by it.
     *
     * As the iPhone's `StreamUrlCache`, an address is kept for one of two lengths of time. A stored object at the rate
     * asked for is kept for [STORED_LIFE_MS]. A live encode, or a lower rate standing in for one the store has not
     * made, is kept for [FALLBACK_LIFE_MS] only: long enough for the opens of one play, and short enough that a replay
     * asks again and gets the stored object once the store has kept it, which opens much faster than another encode.
     */
    fun resolveBlocking(ref: TrackRef, kilobits: Int): StreamLink {
        val cacheKey = "${ref.mediaId}@$kilobits"
        val now = clock()
        entries[cacheKey]?.takeIf { now < it.keptUntilMillis && usable(it.expiresAtMillis, now) }?.let { return it.link }

        val ticket = sign(ref, kilobits)
        val lifetime = (ticket.expiresInSeconds ?: DEFAULT_LIFETIME_S) * 1000L
        val link = StreamLink(ticket.url, live = ticket.live == true)
        // A ticket that does not say its rate is taken to be at the rate asked for.
        val stored = !link.live && (ticket.kilobits ?: kilobits) >= kilobits
        entries[cacheKey] = Entry(link, now + lifetime, now + if (stored) STORED_LIFE_MS else FALLBACK_LIFE_MS)
        return link
    }

    companion object {
        /** A signed URL with less time than this left is replaced before it is opened. */
        const val MIN_LEFT_MS = 5 * 60_000L

        /** What the API signs for when it does not say: six hours. */
        const val DEFAULT_LIFETIME_S = 6 * 3600L

        /** How long a stored object at the rate asked for is used before the cloud is asked again: the iPhone's. */
        const val STORED_LIFE_MS = 4 * 3600_000L

        /** How long a live encode or a lower-rate stand-in is used: the iPhone's `fallbackLife`. */
        const val FALLBACK_LIFE_MS = 2 * 60_000L

        fun usable(expiresAtMillis: Long, now: Long): Boolean = expiresAtMillis - now > MIN_LEFT_MS
    }
}
