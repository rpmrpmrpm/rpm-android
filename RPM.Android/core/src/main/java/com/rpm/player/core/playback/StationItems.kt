package com.rpm.player.core.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.radio.RadioStation

/**
 * A radio station as a queue item. Its media id is `radio:<uuid>`. A controller's items reach the
 * session without their address (Media3 strips it), so the stream's address travels in the request
 * metadata, which Media3 keeps. [restore] puts it back in `onAddMediaItems`.
 *
 * The item has no title. Media3 builds the player's metadata from what the stream announces (ICY) with
 * the item's metadata laid over it, so a title on the item would always hide the stream's. The
 * station's name is the item's `station`, and also its `artist`, for other controllers (the notification).
 */
object StationItems {
    private const val PREFIX = "radio:"
    private const val EXTRA_HLS = "rpm.hls"
    const val EXTRA_LOGOS = "rpm.stationLogos"
    const val EXTRA_LOGO_STAMP = "rpm.logoStamp"
    const val EXTRA_DETAIL = "rpm.stationDetail"
    /** The whole station as JSON, so the service's listen counter knows what it is counting. */
    private const val EXTRA_STATION = "rpm.station"

    /**
     * What the station's stream announced, as it arrived ("Artist - Title"), in the session's metadata extras. The
     * session's title and artist are the song's own (see [SessionArtwork]), so a screen that splits the announcement
     * itself reads it here.
     */
    const val EXTRA_ANNOUNCED = "rpm.announced"

    fun uuidOf(mediaId: String): String? = mediaId.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    fun itemOf(station: RadioStation, logoStamp: String, detail: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(PREFIX + station.uuid)
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(station.streamUrl))
                    .setExtras(Bundle().apply { putBoolean(EXTRA_HLS, station.isHls) })
                    .build(),
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setStation(station.display)
                    .setArtist(station.display)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(
                        Bundle().apply {
                            putStringArray(EXTRA_LOGOS, (listOfNotNull(station.favicon) + station.otherLogos).toTypedArray())
                            putString(EXTRA_LOGO_STAMP, logoStamp)
                            putString(EXTRA_DETAIL, detail)
                            putString(EXTRA_STATION, WireJson.encodeToString(RadioStation.serializer(), station))
                        },
                    )
                    .build(),
            )
            .build()

    /** The station an item plays, or null for a track or an item that does not carry one. */
    fun stationOf(item: MediaItem): RadioStation? {
        if (uuidOf(item.mediaId) == null) return null
        val json = item.mediaMetadata.extras?.getString(EXTRA_STATION) ?: return null
        return try {
            WireJson.decodeFromString(RadioStation.serializer(), json)
        } catch (_: Exception) {
            null
        }
    }

    /** The item with its stream address put back, or unchanged when it carries none. */
    fun restore(item: MediaItem): MediaItem {
        val uri = item.requestMetadata.mediaUri ?: return item
        val hls = item.requestMetadata.extras?.getBoolean(EXTRA_HLS) == true
        return item.buildUpon().setUri(uri).setMimeType(if (hls) MimeTypes.APPLICATION_M3U8 else null).build()
    }
}
