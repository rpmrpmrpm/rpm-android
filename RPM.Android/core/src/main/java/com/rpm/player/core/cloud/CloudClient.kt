package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.net.AppKey
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import com.rpm.player.core.net.escapeSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import com.rpm.player.core.radio.StationListenWire
import com.rpm.player.core.radio.StationListensPage
import com.rpm.player.core.radio.StationListensReport
import com.rpm.player.core.radio.StationListensUpdated
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException

/** Whose library a call reads. Every call must state it, so that a borrowed shelf cannot be forgotten at
 * any call. [Borrowed] is a collection that another account shares with this one, identified by that
 * account's id. */
sealed interface Shelf {
    data object Own : Shelf

    data class Borrowed(val ownerId: String) : Shelf {
        init {
            require(ownerId.isNotBlank()) { "a borrowed shelf names its owner" }
        }
    }

    /** A stable name for the shelf in cache keys and media ids: `own`, or `shared:<owner id>`. */
    val key: String
        get() = when (this) {
            Own -> "own"
            is Borrowed -> "shared:$ownerId"
        }
}

/** The result of a conditional playlist write: either written, with the stamp the server gave it, or refused
 * because another device wrote first. A refusal carries the stored copy (null if the refusal did not include it). */
sealed interface PlaylistPut {
    data class Written(val updatedAt: Long) : PlaylistPut
    data class Conflict(val stored: CloudPlaylist?) : PlaylistPut
}

/**
 * Which picture of a stored cover to request: the original that the desktop uploaded, or a copy that the server
 * cuts so its longer side is [Edge.px] pixels (rounded up to 256, 512 or 1024; a larger edge gives the
 * original). Every call must state it: the original has many times more bytes than a copy, and nothing on the
 * TV is drawn large enough to use it.
 */
sealed interface CoverSize {
    data object Original : CoverSize
    data class Edge(val px: Int) : CoverSize
}

/** An API answer that is not a success, grouped by what the caller can do about it. */
sealed class ApiException(message: String) : IOException(message) {
    /** 401 or 403: the session has ended or never existed. */
    class Unauthorized : ApiException("unauthorized")
    class NotFound : ApiException("not found")
    class Status(val code: Int) : ApiException("status $code")
    /** A borrowed read returned someone else's rows. A server that does not know `?owner=` ignores it and
     * returns the caller's own rows. */
    class NotServed(val ownerId: String) : ApiException("shelf of $ownerId not served")
}

/**
 * Everything this app requests from `api.rpmrpmrpm.com`. Every call sends the app key and, when signed in,
 * the bearer token. Nothing is cached here. The callers decide what to keep.
 */
class CloudClient(private val http: OkHttpClient, private val auth: AuthClient) {

    /** Every track on a shelf, following the cursor. [onProgress] receives the row count after each page, on
     * the thread that the page arrived on. A borrowed page that names another owner is refused
     * ([ApiException.NotServed]). */
    suspend fun list(shelf: Shelf, onProgress: (Int) -> Unit): List<CloudTrack> {
        val all = mutableListOf<CloudTrack>()
        var cursor: String? = null
        do {
            var path = "cloud/list?limit=2000" + shelf.query(first = false)
            if (cursor != null) path += "&cursor=" + escapeSegment(cursor)
            val page = get(path, CloudListPage.serializer())
            shelf.check(page.owner)
            all += page.files
            cursor = page.cursor
            onProgress(all.size)
        } while (cursor != null)
        return all
    }

    /** A signed URL for one track at [kilobits]. A free account always gets 128, whatever is requested. */
    suspend fun stream(shelf: Shelf, rpmId: String, kilobits: Int): StreamTicket =
        get("cloud/stream/${escapeSegment(rpmId)}?rate=$kilobits" + shelf.query(first = false), StreamTicket.serializer())

    /**
     * Asks the store to make these tracks at [kilobits], and answers once the ask is on its work list. Nothing is made
     * here: the store starts the transcoder, which makes them in the background. As the iPhone's `makeRenditions`.
     *
     * A stream that finds no rendition is given a live encode and queues nothing. Anything that needs the stored
     * object and not the live one (a download) has to ask for it with this. Sent in pieces of [MOST_TRANSCODE_TRACKS],
     * the server's own cap.
     *
     * @return how many can already be served at that rate, and how many were put on the work list.
     */
    suspend fun makeRenditions(rpmIds: List<String>, kilobits: Int): Pair<Int, Int> {
        var ready = 0
        var queued = 0
        for (batch in rpmIds.chunked(MOST_TRANSCODE_TRACKS)) {
            val body = WireJson.encodeToString(TranscodeAsk.serializer(), TranscodeAsk(batch, kilobits))
            val said = send("POST", "cloud/transcode", body, TranscodeAnswer.serializer())
            ready += said.ready ?: 0
            queued += said.queued ?: 0
        }
        return ready to queued
    }

    /** The album keys that have a stored cover, so a wall of covers does not request every album's cover. Each
     * key comes with the time its cover was last written (milliseconds; 0 from a server that sends only the
     * keys). */
    suspend fun artIndex(shelf: Shelf): Map<String, Long> {
        val index = get("cloud/art" + shelf.query(first = true), AlbumArtIndex.serializer())
        return index.albums?.takeIf { it.isNotEmpty() }?.associate { it.albumKey to it.updatedAt }
            ?: index.albumKeys.orEmpty().associateWith { 0L }
    }

    /** Every device's play totals for this account. */
    suspend fun trackPlays(): List<TrackPlaysRow> =
        get("cloud/track-plays?since=0", TrackPlaysPage.serializer()).tracks

    /** Writes this device's play totals, at most 200 rows. Returns the server's time of the write. */
    suspend fun putTrackPlays(report: TrackPlaysReport): Long =
        send("PUT", "cloud/track-plays", WireJson.encodeToString(TrackPlaysReport.serializer(), report), TrackPlaysWritten.serializer()).updatedAt

    /** The lists that the computer generated, or null if it has pushed none (404). */
    suspend fun moodLists(shelf: Shelf): CloudMoodLists? = try {
        get("cloud/moodlists" + shelf.query(first = true), CloudMoodLists.serializer())
    } catch (e: ApiException.NotFound) {
        null
    }

    /** The playlists that the shelf's owner made. A borrowed answer that names another owner is refused. */
    suspend fun playlists(shelf: Shelf): List<CloudPlaylist> {
        val answer = get("cloud/playlists" + shelf.query(first = true), CloudPlaylists.serializer())
        shelf.check(answer.owner)
        return answer.playlists
    }

    /** The collections that other accounts share with this one: the `received` part of `cloud/shares`. */
    suspend fun sharedWithMe(): List<CloudShare> =
        get("cloud/shares", CloudShares.serializer()).received

    /** An account's public profile, or null if it has none (404). Other failures are thrown, so the caller
     * can try again later. */
    suspend fun profile(userId: String): PublicProfile? = try {
        get("profiles/${escapeSegment(userId)}", PublicProfile.serializer())
    } catch (e: ApiException.NotFound) {
        null
    }

    /** The signed-in account's own profile, which is created on the first request. Needs a bearer token. */
    suspend fun ownProfile(): PublicProfile = get("profile", PublicProfile.serializer())

    /** The signed-in account's storage quota, which shows whether the account is a supporter's. Needs a bearer token. */
    suspend fun quota(): CloudQuota = get("cloud/quota", CloudQuota.serializer())

    /** The records that [shelf]'s owner marked. A borrowed answer that names another owner is refused. */
    suspend fun albumMarks(shelf: Shelf): List<CloudAlbumMark> {
        val answer = get("cloud/album-marks" + shelf.query(first = true), CloudAlbumMarks.serializer())
        shelf.check(answer.owner)
        return answer.albums
    }

    /** Marks one of the person's records as a favourite, or clears the mark, under the key its cover is filed
     * under (`PUT cloud/album-mark/{albumKey}`; the body has the same shape as a track mark). Returns the stamp
     * that the store wrote it with. */
    suspend fun putAlbumMark(albumKey: String, favorite: Boolean, favoritedUtcTicks: Long): Long =
        send(
            "PUT",
            "cloud/album-mark/" + escapeSegment(albumKey),
            WireJson.encodeToString(TrackMark.serializer(), TrackMark(favorite = favorite, favoritedAt = if (favorite) favoritedUtcTicks else 0)),
            TrackMarked.serializer(),
        ).updatedAt

    /** A signed URL for a playlist's picture, or null if it has none. */
    suspend fun playlistArtUrl(shelf: Shelf, id: String): String? = try {
        get("cloud/playlist-art/${escapeSegment(id)}" + shelf.query(first = true), SignedUrl.serializer()).url
    } catch (e: ApiException.NotFound) {
        null
    }

    /** A signed URL for one album's cover, or null if it has none. A 404 is a normal answer. */
    suspend fun coverUrl(shelf: Shelf, albumKey: String, size: CoverSize): String? = try {
        val owner = shelf.query(first = true)
        val sized = when (size) {
            CoverSize.Original -> ""
            is CoverSize.Edge -> (if (owner.isEmpty()) "?" else "&") + "size=${size.px}"
        }
        get("cloud/art/${escapeSegment(albumKey)}" + owner + sized, AlbumArtUrl.serializer()).url
    } catch (e: ApiException.NotFound) {
        null
    }

    /** Every device's station listens that were updated at or after [since]. It is inclusive, so the newest row
     * is returned on every pass. Needs a bearer token. */
    suspend fun stationListens(since: Long): List<StationListenWire> =
        get("cloud/station-listens?since=$since", StationListensPage.serializer()).stations

    /** Writes this device's running totals, at most 200 rows. Returns the server's time of the write. */
    suspend fun putStationListens(report: StationListensReport): Long =
        send("PUT", "cloud/station-listens", WireJson.encodeToString(StationListensReport.serializer(), report), StationListensUpdated.serializer()).updatedAt

    /** Every change to the saved stations that the cloud wrote at or after [since] (inclusive), including
     * removals. Needs a bearer token. */
    suspend fun stationFavorites(since: Long): List<com.rpm.player.core.radio.FavoriteRow> =
        get("cloud/station-favorites?since=$since", com.rpm.player.core.radio.FavoritesPage.serializer()).stations

    /** The word timing that a desktop measured for [track]'s lyrics. Empty if nobody has measured it (404). A
     * borrowed track is read from its owner, after the share check. */
    suspend fun lyricTiming(track: com.rpm.player.core.playback.TrackRef): com.rpm.player.core.lyrics.MeasuredAnswer = try {
        val answer = get("cloud/lyric-timing/" + escapeSegment(track.rpmId) + track.shelf.query(first = true), com.rpm.player.core.lyrics.MeasuredAnswer.serializer())
        track.shelf.check(answer.owner)
        answer
    } catch (e: ApiException.NotFound) {
        com.rpm.player.core.lyrics.MeasuredAnswer()
    }

    /** The lyric sheet the track's owner chose on a desktop ("Try another" or their own file), as LRC; null when
     * they chose none. An answer that names another owner is refused, as for every borrowed read. */
    suspend fun lyricSheet(track: com.rpm.player.core.playback.TrackRef): String? = try {
        val answer = get("cloud/lyric-sheet/" + escapeSegment(track.rpmId) + track.shelf.query(first = true), com.rpm.player.core.lyrics.ChosenSheet.serializer())
        track.shelf.check(answer.owner)
        answer.lrc.takeIf { it.isNotBlank() }
    } catch (e: ApiException.NotFound) {
        null
    }

    /** Requests that [track]'s lyrics are timed from its recording. The server answers at once, and the work
     * continues in the background. */
    suspend fun measureLyrics(track: com.rpm.player.core.playback.TrackRef, ask: com.rpm.player.core.lyrics.MeasureAsk) {
        send(
            "POST",
            "cloud/lyric-timing/" + escapeSegment(track.rpmId) + "/measure" + track.shelf.query(first = true),
            WireJson.encodeToString(com.rpm.player.core.lyrics.MeasureAsk.serializer(), ask),
            kotlinx.serialization.json.JsonObject.serializer(),
        )
    }

    /** Every saved station of the account that shares [shelf] with this one, with the newest change of each. Read
     * only. An answer that names another owner is refused, as for every borrowed read. */
    suspend fun stationFavoritesOf(shelf: Shelf.Borrowed): List<com.rpm.player.core.radio.FavoriteRow> {
        val answer = get("cloud/station-favorites?since=0" + shelf.query(first = false), com.rpm.player.core.radio.FavoritesPage.serializer())
        shelf.check(answer.owner)
        return answer.stations
    }

    /** Sends this device's changes to the saved stations, at most 500. The cloud keeps each change only if it is
     * newer than what it holds. Returns the server's time of the write. */
    suspend fun putStationFavorites(report: com.rpm.player.core.radio.FavoritesReport): Long =
        send(
            "PUT",
            "cloud/station-favorites",
            WireJson.encodeToString(com.rpm.player.core.radio.FavoritesReport.serializer(), report),
            com.rpm.player.core.radio.FavoritesUpdated.serializer(),
        ).updatedAt

    /** Marks one of the person's own tracks as a favourite, through the tags push that every app uses. Returns the
     * stamp that its row now has, or null if the cloud has no row for it (a track only on a computer). */
    suspend fun markFavourite(rpmId: String, favoritedUtcTicks: Long): Long? {
        val body = FavouritePush(listOf(FavouriteItem(rpmId, FavouriteTags(favorite = true, favoritedAt = favoritedUtcTicks))))
        return send("POST", "cloud/catalog/tags", WireJson.encodeToString(FavouritePush.serializer(), body), TagsPushed.serializer())
            .rows.firstOrNull { it.rpmId == rpmId }?.updatedAt
    }

    /** Removes the favourite mark from one of the person's own tracks, through `cloud/track-mark`. The tags push
     * cannot be used, because it ignores a push whose only change is `favorite: false`. Returns the stamp that
     * its row now has, or null if the cloud has no row for it or the API is too old to have the route (both
     * return 404). */
    suspend fun unmarkFavourite(rpmId: String): Long? = try {
        send(
            "PUT",
            "cloud/track-mark/" + escapeSegment(rpmId),
            WireJson.encodeToString(TrackMark.serializer(), TrackMark(favorite = false, favoritedAt = 0)),
            TrackMarked.serializer(),
        ).updatedAt
    } catch (e: ApiException.NotFound) {
        null
    }

    /** Writes one of the person's own playlists as a whole, if the stored copy still has the stamp in
     * [PlaylistWrite.ifUnchangedSince]. A refusal (409) is a normal result and not a failure: it carries the copy
     * that is stored. Any other status is thrown. */
    suspend fun putPlaylist(playlistId: String, write: PlaylistWrite): PlaylistPut {
        val token = auth.accessToken()
        val request = Request.Builder()
            .url(Hosts.API + "cloud/playlist/" + escapeSegment(playlistId))
            .header(AppKey.HEADER, AppKey.VALUE)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .put(WireJson.encodeToString(PlaylistWrite.serializer(), write).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            return when (response.code) {
                in 200..299 -> PlaylistPut.Written(WireJson.decodeFromString(PlaylistWritten.serializer(), response.body.string()).updatedAt)
                409 -> PlaylistPut.Conflict(runCatching { WireJson.decodeFromString(CloudPlaylist.serializer(), response.body.string()) }.getOrNull())
                401, 403 -> throw ApiException.Unauthorized()
                404 -> throw ApiException.NotFound()
                else -> throw ApiException.Status(response.code)
            }
        }
    }

    /**
     * Removes one of the person's own playlists from the cloud (the iPhone's `RPMCloudClient.deletePlaylist`). The
     * songs it named are not touched: they belong to records, and a song can be on other playlists too.
     *
     * **A 404 is the same result.** Asking for a playlist to be gone when it is already gone is not a failure. Any
     * other status is thrown.
     */
    suspend fun deletePlaylist(playlistId: String) {
        val token = auth.accessToken()
        val request = Request.Builder()
            .url(Hosts.API + "cloud/playlist/" + escapeSegment(playlistId))
            .header(AppKey.HEADER, AppKey.VALUE)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .delete()
            .build()
        http.newCall(request).await().use { response ->
            when (response.code) {
                in 200..299, 404 -> return
                401, 403 -> throw ApiException.Unauthorized()
                else -> throw ApiException.Status(response.code)
            }
        }
    }

    /** Bytes from a signed URL. No credentials are sent: an extra header changes the request, and the store
     * refuses it.
     *
     * The body is read on [Dispatchers.IO]. [await] gives the response back on the caller's thread, and a body
     * larger than what came with the headers, such as a sidecar with its cover inside, is read from the network.
     * On the main thread Android stops the app for that (NetworkOnMainThreadException). */
    suspend fun signedBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).await().use { response ->
            if (response.code !in 200..299) throw ApiException.Status(response.code)
            response.body.bytes()
        }
    }

    /**
     * One of the person's own tracks' tags, as the uploader wrote them next to the file ([TagSidecar]), in two hops: the
     * API signs a URL, and the bytes come from the store with no credentials ([signedBytes]). The route takes no shelf,
     * so it answers only about this account's own files.
     *
     * A track with no sidecar gives null and does not throw: any refusal, a 404 or not, means "not there". A sidecar
     * that cannot be fetched or read gives null too. Only a network that cannot be reached throws.
     */
    suspend fun sidecar(rpmId: String): TagSidecar? {
        val signed = try {
            get("cloud/sidecar/" + escapeSegment(rpmId), SignedUrl.serializer())
        } catch (e: ApiException) {
            return null
        }
        val bytes = try {
            signedBytes(signed.url)
        } catch (e: ApiException) {
            return null
        } catch (e: IllegalArgumentException) {
            // Not a URL at all.
            return null
        }
        return runCatching { WireJson.decodeFromString(TagSidecar.serializer(), bytes.decodeToString()) }.getOrNull()
    }

    private suspend fun <T> get(path: String, reader: DeserializationStrategy<T>): T = send("GET", path, null, reader)

    private suspend fun <T> send(method: String, path: String, json: String?, reader: DeserializationStrategy<T>): T {
        val token = auth.accessToken()
        val request = Request.Builder()
            .url(Hosts.API + path)
            .header(AppKey.HEADER, AppKey.VALUE)
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .method(method, json?.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response ->
            when (response.code) {
                in 200..299 -> return WireJson.decodeFromString(reader, response.body.string())
                401, 403 -> throw ApiException.Unauthorized()
                404 -> throw ApiException.NotFound()
                else -> throw ApiException.Status(response.code)
            }
        }
    }

    /** The shelf as a query parameter. [first] says whether it starts the query string. There is no default,
     * because a borrowed shelf written with the wrong separator gives a malformed address. */
    private fun Shelf.query(first: Boolean): String = when (this) {
        Shelf.Own -> ""
        is Shelf.Borrowed -> (if (first) "?owner=" else "&owner=") + escapeSegment(ownerId)
    }

    /** Refuses a borrowed answer whose [owner] is not the shelf's, because an old server ignores `?owner=`. */
    private fun Shelf.check(owner: String?) {
        if (this is Shelf.Borrowed && owner != ownerId) throw ApiException.NotServed(ownerId)
    }

    companion object {
        /** How many tracks one transcode ask may name. The server's own cap. */
        const val MOST_TRANSCODE_TRACKS = 200
    }
}
