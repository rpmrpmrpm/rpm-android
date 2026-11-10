package com.rpm.player.core.home

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags

/**
 * The library that the screens show: every track in the cloud, and every track that a paired computer on the
 * network holds and the cloud does not, as one list. A track that is in both is one track, matched by `rpmId`,
 * and the cloud's row is kept (the rest of the app uses its tags). Where the track plays from is decided when
 * it is opened ([com.rpm.player.core.playback.TrackSources]). A track on two computers is taken from the
 * first one.
 */
object HomeMerge {

    fun merge(cloud: List<CloudTrack>, catalogs: Collection<HeldCatalog>): List<CloudTrack> {
        if (catalogs.isEmpty()) return cloud
        val seen = HashSet<String>(cloud.size * 2)
        cloud.mapTo(seen) { it.rpmId }
        val merged = ArrayList<CloudTrack>(cloud.size)
        merged += cloud
        // A NAS first, so a track on a NAS and a computer is shown from the NAS's catalogue.
        for (catalog in HeldCatalog.nasFirst(catalogs)) {
            for (track in catalog.tracks.values) {
                if (seen.add(track.rpmId)) merged += asCloudTrack(track)
            }
        }
        return merged
    }

    /** A computer's track converted to the shape that the library is built from. */
    fun asCloudTrack(track: PeerTrack): CloudTrack = CloudTrack(
        rpmId = track.rpmId,
        relativePath = track.path,
        originalSize = track.size,
        title = track.title,
        artist = track.artist,
        album = track.album,
        addedAt = unixSeconds(track.added),
        tags = CloudTrackTags(
            durationSeconds = track.seconds,
            trackNumber = track.track,
            discNumber = track.disc,
            year = track.year,
            genre = track.genre,
            albumArtist = track.albumArtist,
            extension = track.path.substringAfterLast('.', "").lowercase().ifEmpty { null },
            codecDescription = track.codec,
            advisory = track.advisory,
        ),
    )

    /** Converts .NET UTC ticks (100 ns since 0001-01-01) to Unix seconds, which is the cloud's unit. 0 stays 0. */
    fun unixSeconds(ticks: Long): Long = if (ticks <= UNIX_EPOCH_TICKS) 0 else (ticks - UNIX_EPOCH_TICKS) / 10_000_000

    private const val UNIX_EPOCH_TICKS = 621_355_968_000_000_000L
}
