package com.rpm.player.core.downloads

import com.rpm.player.core.downloads.AlbumDownloads.Standing

/**
 * Where one track stands on this phone, whatever album or playlist asked for it. The iPhone's `TrackDownload`
 * (RPMLibrary/TrackDownloadMark.swift).
 *
 * A track has one file, and an album and a playlist can share it, so this is a fact about the track and not about a
 * page. See [AlbumDownloads.track].
 */
sealed interface TrackDownload {
    /** Not on the phone, and nothing that is still asked for holds it. */
    data object Absent : TrackDownload

    /** Wanted, and not started yet. */
    data object Waiting : TrackDownload

    /** Wanted, and the store is still making it at the chosen rate. See [RenditionWait]. */
    data object Preparing : TrackDownload

    /** On its way. [fraction] is how much of the track has arrived, from 0 to 1. */
    data class Arriving(val fraction: Double) : TrackDownload

    /** It failed [AlbumDownloads.MOST_GOES] times and nothing is being tried for it. */
    data object Stuck : TrackDownload

    /** It has a file on the phone. */
    data object OnPhone : TrackDownload
}

/**
 * The small mark at the end of a song's row on a record's page or a playlist's page. The iPhone's `TrackDownloadMark`.
 *
 * The page's download key already says where the page's own album or playlist stands. The rows add what the key cannot
 * say: which songs are still to come while it downloads, and which songs are on the phone through another album or
 * playlist when it was not asked for.
 */
sealed interface TrackDownloadMark {
    /** A faint outline circle: the song waits for its turn. */
    data object Waiting : TrackDownloadMark

    /** A turning arc: the store is still making the song at the chosen rate. */
    data object Preparing : TrackDownloadMark

    /** A small ring that fills as the song arrives. */
    data class Arriving(val fraction: Double) : TrackDownloadMark

    /** The downloaded glyph: the song is on the phone. */
    data object Done : TrackDownloadMark

    /** The stuck glyph: the song could not be downloaded. */
    data object Stuck : TrackDownloadMark

    companion object {
        /**
         * Which mark a row shows, or null for none.
         *
         * @param page where the page's own album or playlist stands, or null when nobody asked for it.
         * @param track where the row's song stands. Read only when the answer needs it, so a page that shows no marks
         * does not read the songs' states, and is not drawn again when they change.
         */
        fun of(page: Standing?, track: () -> TrackDownload): TrackDownloadMark? = when (page) {
            // The key says it all. A tick on every row would repeat it.
            Standing.OnPhone -> null
            // Not asked for: only the songs that another album or playlist brought are marked.
            null -> if (track() == TrackDownload.OnPhone) Done else null
            is Standing.Downloading, is Standing.Stuck -> when (val state = track()) {
                TrackDownload.Absent -> null
                TrackDownload.Waiting -> Waiting
                TrackDownload.Preparing -> Preparing
                is TrackDownload.Arriving -> Arriving(state.fraction)
                TrackDownload.Stuck -> Stuck
                TrackDownload.OnPhone -> Done
            }
        }
    }
}
