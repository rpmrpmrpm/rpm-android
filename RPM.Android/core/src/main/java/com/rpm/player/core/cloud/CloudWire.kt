package com.rpm.player.core.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One track in the cloud catalogue, as `cloud/list` returns it. Every field except the id has a default,
 * because rows uploaded by older desktops lack the newer fields. It has the same shape as the iPhone app's
 * `CloudTrack`.
 */
@Serializable
data class CloudTrack(
    val rpmId: String,
    val sha256: String = "",
    val relativePath: String? = null,
    val originalSize: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val addedAt: Long = 0,
    val tags: CloudTrackTags? = null,
    val updatedAt: Long = 0,
)

/** The tags of a track. Only the fields this app reads are listed; the rest are ignored on read. */
@Serializable
data class CloudTrackTags(
    val durationSeconds: Double = 0.0,
    val trackNumber: Int = 0,
    val trackCount: Int = 0,
    val discNumber: Int = 0,
    val discCount: Int = 0,
    val year: Int = 0,
    val genre: String? = null,
    val albumArtist: String? = null,
    val extension: String? = null,
    /** The file's sample rate in Hz, its bit depth and its bitrate in kbit/s, as the desktop read them. 0 if
     * not known (a lossy file has no bit depth). */
    val sampleRate: Int = 0,
    val bitsPerSample: Int = 0,
    val bitrateKbps: Int = 0,
    val codecDescription: String? = null,
    /** How many channels the file has, 0 if not known. The record page names a layout other than stereo ("5.1"). */
    val channels: Int = 0,
    /** The classical work the track is part of, the movement's own name, and the composer, where the file says.
     * A record its owner marked classical is listed as works with their movements under them. */
    val work: String? = null,
    val movementName: String? = null,
    val composer: String? = null,
    val favorite: Boolean = false,
    /** When the favourite mark was set, in .NET ticks (UTC), as the desktop writes it. 0 if not known. */
    val favoritedAt: Long = 0,
    val advisory: Int = 0,
    /** The MusicBrainz release that the file is tagged with, if any. It identifies which edition of a record
     * the world plays refer to. */
    @SerialName("mbReleaseId") val musicBrainzReleaseId: String? = null,
    /** Programme loudness in LUFS as the desktop measured it. 0 if it was not measured. */
    val loudnessLufs: Double = 0.0,
    /** True peak as a linear amplitude, where 1.0 is full scale. 0 if unknown. */
    val truePeak: Double = 0.0,
    /** What kind of release the record is, as the listener or the file set it ("Album", "Ep", "Live"...), or null
     * where nobody has said. See [com.rpm.player.core.library.ReleaseType]. */
    val releaseType: String? = null,
)

@Serializable
data class CloudListPage(
    val files: List<CloudTrack> = emptyList(),
    val cursor: String? = null,
    val owner: String? = null,
)

/** The answer of `cloud/stream/{rpmId}`: a signed URL, the rate it serves, and whether it is a live
 * transcode (a track with no stored rendition at that rate). */
@Serializable
data class StreamTicket(
    val url: String,
    val kilobits: Int? = null,
    val live: Boolean? = null,
    val expiresInSeconds: Long? = null,
)

/** The body of `cloud/transcode`: tracks to make at one rate. */
@Serializable
data class TranscodeAsk(val rpmIds: List<String>, val kilobits: Int)

/** The answer of `cloud/transcode`: how many can already be served at the rate, and how many were put on the work
 * list. Tracks that are not the account's own are counted in neither. */
@Serializable
data class TranscodeAnswer(val ready: Int? = null, val queued: Int? = null)

/** The answer of `cloud/art`: the albums with a stored cover. Older servers send only [albumKeys]. */
@Serializable
data class AlbumArtIndex(
    val albumKeys: List<String>? = null,
    val albums: List<AlbumArtIndexEntry>? = null,
) {
    val keys: Set<String>
        get() = albums?.takeIf { it.isNotEmpty() }?.map { it.albumKey }?.toSet() ?: albumKeys.orEmpty().toSet()
}

@Serializable
data class AlbumArtIndexEntry(
    @SerialName("albumKey") val albumKey: String,
    val updatedAt: Long = 0,
)

@Serializable
data class AlbumArtUrl(val url: String, val updatedAt: Long = 0)

/** One device's running total for one track, as `cloud/track-plays` returns it. */
@Serializable
data class TrackPlaysRow(
    val rpmId: String,
    val deviceId: String = "",
    val plays: Int = 0,
    /** Unix seconds on the device that played it. */
    val lastPlayedAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class TrackPlaysPage(val tracks: List<TrackPlaysRow> = emptyList())

/** This device's total for one track, as `PUT cloud/track-plays` accepts it. */
@Serializable
data class TrackPlaysEntry(val rpmId: String, val plays: Long, val lastPlayedAt: Long)

/** One report of this device's totals, with at most 200 rows (the API's `TrackPlayStore.MaxRowsPerReport`). */
@Serializable
data class TrackPlaysReport(val deviceId: String, val tracks: List<TrackPlaysEntry>)

@Serializable
data class TrackPlaysWritten(val updatedAt: Long = 0)

/** A list that the computer generated (a mood, a genre cut, a decade), as `cloud/moodlists` returns it.
 * [trackIds] holds several windows of [shows] tracks each, one after the other. */
@Serializable
data class CloudMoodList(
    val kind: String = "",
    val genre: String? = null,
    val decade: Int = 0,
    val title: String? = null,
    val folder: List<String> = emptyList(),
    val id: String? = null,
    val trackIds: List<String> = emptyList(),
    val shows: Int = 0,
)

@Serializable
data class CloudMoodLists(
    val recipe: Int = 0,
    val computedAt: Long = 0,
    val lists: List<CloudMoodList> = emptyList(),
)

/** A playlist that the person made, as `cloud/playlists` returns it. Nested lists arrive already flattened.
 * [folder] is the chain of folder names, outermost first. */
@Serializable
data class CloudPlaylist(
    val playlistId: String,
    val name: String = "",
    val trackIds: List<String> = emptyList(),
    val folder: List<String> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class CloudPlaylists(val playlists: List<CloudPlaylist> = emptyList(), val owner: String? = null)

/** A whole playlist sent to `PUT cloud/playlist/{id}`. It is written only if the stored copy still has the
 * stamp [ifUnchangedSince] (milliseconds, the server's clock). Null if the store never gave a stamp. */
@Serializable
data class PlaylistWrite(
    val name: String,
    val trackIds: List<String>,
    val folder: List<String>,
    val ifUnchangedSince: Long? = null,
)

@Serializable
data class PlaylistWritten(val updatedAt: Long = 0)

/** A `cloud/catalog/tags` push that carries only the listener's mark. The push is a merge: a field it does
 * not carry is not changed. [favoritedAt] is in .NET ticks (UTC), as the desktop writes it. */
@Serializable
data class FavouritePush(val items: List<FavouriteItem>)

@Serializable
data class FavouriteItem(val rpmId: String, val tags: FavouriteTags)

@Serializable
data class FavouriteTags(val favorite: Boolean, val favoritedAt: Long)

/** One track's favourite mark, as `PUT cloud/track-mark/{rpmId}` accepts it. [favoritedAt] is in .NET ticks,
 * and 0 when the mark is off. */
@Serializable
data class TrackMark(val favorite: Boolean, val favoritedAt: Long)

/** The stamp that the track's row was written with. */
@Serializable
data class TrackMarked(val updatedAt: Long = 0)

/** The result of a tags push: each row it changed, with the stamp that the row now has. A track that the
 * cloud has no row for is not included. */
@Serializable
data class TagsPushed(val updated: Int = 0, val rows: List<TagsPushedRow> = emptyList())

@Serializable
data class TagsPushedRow(val rpmId: String, val updatedAt: Long = 0)

@Serializable
data class SignedUrl(val url: String)

/** One share: [ownerId]'s collection that [granteeId] can read, since [createdAt] (Unix seconds). For a
 * share that was received, the grantee name is this account's own name, so the owner's name is read from its
 * profile. */
@Serializable
data class CloudShare(val ownerId: String, val granteeId: String = "", val createdAt: Long = 0)

@Serializable
data class CloudShares(val granted: List<CloudShare> = emptyList(), val received: List<CloudShare> = emptyList())

/**
 * The part of a profile that the TV reads: `profiles/{id}` for any account, `profile` for the person's own. The
 * picture is at `profiles/{id}/avatar` when [hasAvatar] is true, and it is stamped with [avatarUpdatedAt].
 * [supporter] is only in the public answer. For the person's own account it comes from `cloud/quota`.
 */
@Serializable
data class PublicProfile(
    val userId: String = "",
    val displayName: String? = null,
    val hasAvatar: Boolean = false,
    val avatarUpdatedAt: Long = 0,
    val supporter: Boolean = false,
)

/** `cloud/quota`: what the account's storage holds and allows, and the account's standing. A field the server does
 * not send reads as 0, and then the quota is the free account's.
 *
 * [unlimited] is about storage only: no ceiling, which only a paying supporter has. Read the supporter features from
 * [supporter], never from [unlimited]: a closed beta tester has the features and a ceiling. */
@Serializable
data class CloudQuota(
    val usedBytes: Long = 0,
    val quotaBytes: Long = 0,
    val fileCount: Int = 0,
    val unlimited: Boolean = false,
    /** What the server sent as `supporter`. Null from a server that does not send it; read [supporter]. */
    @SerialName("supporter") private val supporterSent: Boolean? = null,
    /** A closed beta tester who does not pay: the supporter features, a ceiling and no supporter's mark. */
    val tester: Boolean = false,
) {
    /** Whether the account has the supporter features (higher bitrates, karaoke, the gold ground and the rest). Paying
     * supporters and closed beta testers have them. A server that does not send the field has no testers, so it
     * falls back to [unlimited]. */
    val supporter: Boolean get() = supporterSent ?: unlimited

    /** Whether the supporter's mark is shown for this account. The mark says "Supporter", so a tester, who does not
     * pay, does not wear it. */
    val wearsMark: Boolean get() = supporter && !tester
}

/** One record that its owner marked, as `cloud/album-marks` returns it, identified by the key its cover is filed under. */
@Serializable
data class CloudAlbumMark(val albumKey: String, val favorite: Boolean = false, val classical: Boolean? = null, val updatedAt: Long = 0)

/** Every record that a shelf's owner marked. [owner] names whose marks they are. A borrowed read checks it. */
@Serializable
data class CloudAlbumMarks(val albums: List<CloudAlbumMark> = emptyList(), val owner: String? = null)
