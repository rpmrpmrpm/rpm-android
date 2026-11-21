package com.rpm.player.phone.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rpm.player.core.Services
import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.library.ArtistCredit
import com.rpm.player.core.library.ArtistWallItem
import com.rpm.player.core.library.Collection
import com.rpm.player.core.library.LibraryState
import com.rpm.player.core.playback.NowPlaying
import com.rpm.player.core.playback.TrackRef
import kotlinx.coroutines.launch

/**
 * What the app gives the player beyond the player itself, the parts the iPhone's `AppModel` hands its `NowPlayingSheet`:
 * the lyrics lookup and the measured words for karaoke, the speakers, the heart on the song playing, the artist's page,
 * and whether the sheet is going down because the listening ended. Made once by the shell ([rememberPlayerWiring]), so
 * the lookup's answer and the speakers' state outlive one showing of the sheet.
 */
@Stable
class PlayerWiring(private val services: Services) {
    val lyrics = LyricsLookup(services.lrcLib, services.measuredLyrics)

    /** The measured words of the playing song, for karaoke. */
    val timing = KaraokeTiming(services.measuredLyrics)
    val speakers = Speakers(services.casting)

    /** Set when the listening ended under the sheet: it goes down by itself. */
    var leaving by mutableStateOf(false)

    /** The last thing played, which the sheet shows while it goes down after the listening ended. */
    var lastNow by mutableStateOf<NowPlaying?>(null)
        internal set

    internal var collection: Collection? = null

    /** A heart pressed and not yet seen in the library: shown at once, and undone if the cloud refuses it. */
    private var held by mutableStateOf<Pair<String, Boolean>?>(null)

    /** Reads the words of [now], once per record, with the catalogue's names and length where the library has them. */
    suspend fun readLyrics(now: NowPlaying) {
        val ref = TrackRef.parse(now.mediaId)
        val track = ref?.let { collection?.tracksById?.get(it.rpmId) }
        val seconds = track?.tags?.durationSeconds?.takeIf { it > 0 } ?: (now.durationMs / 1000.0)
        lyrics.read(
            if (now.station == null) now.mediaId else null,
            track?.artist ?: now.artist,
            track?.title ?: now.title,
            track?.album ?: now.album,
            seconds,
        )
    }

    /**
     * Reads the measured words of [now], and asks for them to be measured where none fit its lyrics. Only once the
     * lyrics of this very record are in: the request names the sheet's lines, and the last record's sheet must not be
     * sent for this one.
     */
    suspend fun readTiming(now: NowPlaying) {
        if (now.station != null || lyrics.mediaId != now.mediaId) return
        timing.read(now.mediaId, lyrics.original, lyrics.offset, known = lyrics.answer?.lines)
    }

    /** Has the words of [next], the song after the one playing, measured ahead of time ([KaraokeTiming.warm]). */
    suspend fun warmNext(next: QueueEntry?) {
        next ?: return
        val track = collection?.tracksById?.get(next.track.rpmId) ?: next.track
        timing.warm(next.mediaId, track.artist, track.title, track.album, track.tags?.durationSeconds ?: 0.0, services.lrcLib)
    }

    /** Whether the song [rpmId] carries the heart, as pressed here or as the library has it. */
    fun isMarked(rpmId: String?): Boolean {
        rpmId ?: return false
        held?.takeIf { it.first == rpmId }?.let { return it.second }
        return collection?.tracksById?.get(rpmId)?.tags?.favorite == true
    }

    /** Whether the song playing can carry this account's heart: a song, on this account's own shelf. */
    fun canMark(now: NowPlaying): Boolean = now.station == null && TrackRef.parse(now.mediaId)?.shelf == Shelf.Own

    /**
     * Marks [rpmId], or clears the mark. The change shows before the cloud answers and is undone if it refuses, as on
     * iPhone (`LibrarySession.mark`). The library lays the write over its rows once the cloud took it.
     */
    fun mark(rpmId: String, on: Boolean) {
        held = rpmId to on
        services.scope.launch {
            // Refused, the library still holds what was there. Taken, the heart stays as pressed until the library
            // lays the write over its rows ([settle]): let go earlier, it would flash back for a moment.
            if (!services.ownWriter.mark(rpmId, on) && held == rpmId to on) held = null
        }
    }

    /** Lets go of a pressed heart once the library shows the same. */
    internal fun settle() {
        val (id, on) = held ?: return
        if ((collection?.tracksById?.get(id)?.tags?.favorite == true) == on) held = null
    }

    /** The act whose page the song [rpmId] belongs to: the record's artist, else the song's, else the first name credited. */
    fun artistOf(rpmId: String, ready: LibraryState.Ready): ArtistWallItem? {
        val track = ready.collection.tracksById[rpmId] ?: return null
        val album = ready.collection.albumOf[rpmId]
        val names = listOfNotNull(album?.artist, track.artist) + ArtistCredit.split(album?.artist) + ArtistCredit.split(track.artist)
        return names.asSequence().filter { it.isNotBlank() }.mapNotNull { ready.walls.artist(it) }.firstOrNull()
    }
}

/** The shell's [PlayerWiring], kept for the life of the shell and told of the library and of what plays. */
@Composable
fun rememberPlayerWiring(services: Services, collection: Collection, now: NowPlaying?): PlayerWiring {
    val wiring = remember(services) { PlayerWiring(services) }
    SideEffect {
        wiring.collection = collection
        wiring.settle()
        if (now != null) wiring.lastNow = now
    }
    FollowCastTrouble(wiring.speakers)
    return wiring
}
