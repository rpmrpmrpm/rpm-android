package com.rpm.player.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import android.os.Bundle
import com.rpm.player.core.cast.CastCovers
import com.rpm.player.core.radio.Icy
import com.rpm.player.core.radio.SongCovers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The session's player with the picture of what is playing in its metadata: the record's cover, or the station's logo.
 *
 * Android TV's now playing card on the launcher draws the session's `ALBUM_ART` bitmap, else its `ART` bitmap, and
 * nothing else (checked in com.google.android.tvrecommendations 7.4); without it the card shows a bare icon. Media3 puts a
 * bitmap there, and on the media notification, only from the metadata's artwork. The queue's items carry no picture,
 * because every controller is sent the whole queue, so the picture of the item playing is read here, through the app's
 * image loader ([CastCovers], the JPEG a cast receiver is sent), and added to [getMediaMetadata] for that item alone.
 *
 * Media3 builds the platform session's metadata again from [getMediaMetadata] on every metadata event, so a picture
 * that arrives is announced as one ([announce]), and a later event from the player underneath (a station's song title)
 * keeps it.
 *
 * **A station reads as the song it plays.** While a station announces a song, the title is the song's title, the artist
 * its artist, and the picture its cover ([SongCovers], the cover the player shows), so the lock screen and the media
 * notification show the song and not the station. The station's name is the title only while it announces nothing
 * that reads as a song, with no artist under it. The announcement as it arrived is kept in the extras
 * ([StationItems.EXTRA_ANNOUNCED]) for the app's own screens, which split it themselves.
 *
 * Main thread, as the player is. Two events can arrive in either order: the item changing and a picture's read
 * finishing. A picture is kept with the media id it was read for and shown only while
 * that id is the current item's, so a read that finishes after the item changed cannot dress the next item in it. A
 * song's cover is kept with the media id and the song, for the same reason.
 */
@OptIn(UnstableApi::class)
class SessionArtwork(
    player: Player,
    private val covers: CastCovers,
    private val scope: CoroutineScope,
    private val songCovers: SongCovers? = null,
    private val marksExplicit: Boolean = false,
) : ForwardingPlayer(player) {
    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    /** The last picture read, by the media id it is of. */
    private var held: Held? = null

    /** The read in flight and the media id it is for. */
    private var reading: Job? = null
    private var readingFor: String? = null

    private class Held(val mediaId: String, val jpeg: ByteArray)

    /** The last song cover read, by the station's media id and the song it is of. */
    private var song: HeldSong? = null
    private var songReading: Job? = null
    private var songReadingFor: String? = null

    private class HeldSong(val mediaId: String, val key: String, val jpeg: ByteArray)

    init {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = follow()

            // A queue handed over replaces the items without a transition when the index stays the same.
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = follow()

            // A station announced a new song.
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = followSong()
        })
        follow()
    }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getMediaMetadata(): MediaMetadata {
        val own = super.getMediaMetadata()
        val id = currentMediaItem?.mediaId
        val built = own.buildUpon()
        val announced = stationSong(own)
        if (announced != null) {
            val (said, name) = announced
            val sung = said as? Icy.Said.Song
            built.setTitle(sung?.title ?: name)
                .setArtist(sung?.artist)
                .setExtras(Bundle(own.extras ?: Bundle()).apply { putString(StationItems.EXTRA_ANNOUNCED, own.title?.toString()) })
            val cover = song?.takeIf { it.mediaId == id && it.key == keyOf(sung) }
            if (cover != null) return built.setArtworkData(cover.jpeg, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
        }
        if (announced == null) markExplicit(built, own)
        val picture = held?.takeIf { it.mediaId == id } ?: return built.build()
        return built.setArtworkData(picture.jpeg, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
    }

    /** What the station playing announces, with the station's name, or null when a record plays. */
    /**
     * Puts [EXPLICIT_MARK] after the title of a song whose item says it is explicit ([EXTRA_EXPLICIT]), and sets the
     * platform's explicit key, which car screens read. Only the session's metadata changes: the app's own screens read
     * the queue's item, whose title stays as it is.
     */
    private fun markExplicit(built: MediaMetadata.Builder, own: MediaMetadata) {
        if (!marksExplicit) return
        if (currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_EXPLICIT) != true) return
        val title = own.title?.toString()?.takeIf { it.isNotBlank() } ?: return
        built.setTitle(title + "\u00A0" + EXPLICIT_MARK)
            .setExtras(Bundle(own.extras ?: Bundle()).apply { putLong(PLATFORM_EXPLICIT, 1L) })
    }

    private fun stationSong(own: MediaMetadata): Pair<Icy.Said?, String>? {
        val id = currentMediaItem?.mediaId ?: return null
        if (StationItems.uuidOf(id) == null) return null
        val name = own.station?.toString() ?: currentMediaItem?.mediaMetadata?.station?.toString() ?: return null
        return Icy.said(own.title?.toString(), name) to name
    }

    /** The key of a song for its cover: the artist and the title, or null when there is not both. */
    private fun keyOf(sung: Icy.Said.Song?): String? {
        val artist = sung?.artist?.trim()?.takeIf { it.length >= SHORTEST } ?: return null
        val title = sung.title.trim().takeIf { it.length >= SHORTEST } ?: return null
        return artist.lowercase() + "\u001F" + title.lowercase()
    }

    /** Reads the cover of the song the station announces now, unless it is held already or being read. */
    private fun followSong() {
        val covers = songCovers ?: return
        val id = currentMediaItem?.mediaId ?: return
        val sung = stationSong(super.getMediaMetadata())?.first as? Icy.Said.Song
        val key = keyOf(sung) ?: return
        val ask = "$id\u001E$key"
        if (song?.let { it.mediaId == id && it.key == key } == true || ask == songReadingFor) return
        songReading?.cancel()
        songReadingFor = ask
        songReading = scope.launch {
            val jpeg = try {
                covers.of(sung!!.artist!!.trim(), sung.title.trim())?.let { this@SessionArtwork.covers.jpegOfPicture(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (songReadingFor == ask) songReadingFor = null
            if (jpeg == null) return@launch
            song = HeldSong(id, key, jpeg)
            if (currentMediaItem?.mediaId == id) announce()
        }
    }

    /** Reads the current item's picture, unless it is held already or being read. */
    private fun follow() {
        val item = currentMediaItem ?: return
        val id = item.mediaId
        if (id.isEmpty() || id == held?.mediaId || id == readingFor) return
        reading?.cancel()
        readingFor = id
        reading = scope.launch {
            val jpeg = try {
                covers.jpegOf(item)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (readingFor == id) readingFor = null
            if (jpeg == null) return@launch
            held = Held(id, jpeg)
            if (currentMediaItem?.mediaId == id) announce()
        }
    }

    companion object {
        /** An announcement this short on either side is not a song worth asking about ("-", "X"). */
        private const val SHORTEST = 2

        /**
         * The explicit mark after a song's title on the lock screen and the media notification: a white E in a filled
         * square (U+1F174), as the iPhone shows it. The system draws that text, not the app, so the mark must be a
         * character its fonts have. Android's symbol fonts do not have it, but its CJK font (NotoSansCJK) does, and the
         * system falls back to that font for it.
         */
        const val EXPLICIT_MARK = "\uD83C\uDD74"

        /** `MediaConstants.METADATA_KEY_IS_EXPLICIT`: 1 for an explicit item. Car screens draw their own mark from it. */
        private const val PLATFORM_EXPLICIT = "android.media.IS_EXPLICIT"
    }

    /** Tells the session the metadata changed: it reads [getMediaMetadata] again, picture included. */
    private fun announce() {
        val metadata = mediaMetadata
        val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
        for (listener in listeners) {
            listener.onMediaMetadataChanged(metadata)
            listener.onEvents(this, events)
        }
    }
}
