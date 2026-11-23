package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import java.text.Collator

/** One record on the wall: its tracks in play order, and the names it is shown under. */
data class CloudAlbum(
    val key: String,
    val album: String,
    val artist: String,
    val tracks: List<CloudTrack>,
)

/** The tag value for explicit lyrics (`ContentAdvisory.Explicit` on the desktop). It is the only value that shows the E:
 * 2 means the song was stated to be clean, and 0 that nobody has said. */
const val ADVISORY_EXPLICIT = 1

/** Whether the E goes beside the song's title. */
val CloudTrack.isExplicit: Boolean get() = tags?.advisory == ADVISORY_EXPLICIT

/** Whether the E goes beside the record's title: when any of its songs is explicit, as streaming services mark a record. */
val CloudAlbum.isExplicit: Boolean get() = tracks.any { it.isExplicit }

/** Whether the song [rpmId] of this collection is explicit, or the record it is on is: either one shows the E. The
 * lyrics lookup uses it to prefer the uncensored sheet ([com.rpm.player.core.lyrics.ExplicitLyrics]). False for a song
 * the collection does not hold. */
fun Collection.isExplicitRecord(rpmId: String): Boolean =
    tracksById[rpmId]?.isExplicit == true || albumOf[rpmId]?.isExplicit == true

/**
 * Turns the catalogue's tracks into albums, with the same rules as the iOS app's `AlbumWall`,
 * `AlbumGrouping`, `AlbumTracklist` and `AlbumSort`: grouped by folder where there is one and by tags
 * where there is not, tracks in disc and track order, albums by artist and then title.
 */
object AlbumWall {

    fun group(tracks: List<CloudTrack>): List<CloudAlbum> {
        val names = collator()
        val albums = tracks.groupBy(::key).map { (key, rows) ->
            val folder = folder(rows[0].relativePath)
            // Title first, so it is the tie-break: the stable sort below keeps tracks with no number
            // in alphabetical order, not in listing order.
            val byTitle = rows.sortedWith { a, b -> names.compare(a.title.orEmpty(), b.title.orEmpty()) }
            CloudAlbum(
                key = key,
                album = albumName(rows, folder).orEmpty(),
                artist = albumArtist(rows, folder).orEmpty(),
                tracks = inPlayOrder(byTitle),
            )
        }
        return albums.sortedWith { a, b ->
            names.compare(a.artist, b.artist).takeIf { it != 0 } ?: names.compare(a.album, b.album)
        }
    }

    /** The folder wins wherever there is one: it is how the person filed the library. */
    fun key(track: CloudTrack): String {
        folder(track.relativePath)?.let { return "folder:" + it.lowercase() }
        val albumArtist = track.tags?.albumArtist
        // A present but blank tag is not an album artist.
        val owner = if (albumArtist.isNullOrBlank()) track.artist else albumArtist
        val name = if (track.album.isNullOrBlank()) "" else normalizedAlbumName(track.album)
        return "tags:" + norm(owner) + "|" + norm(name)
    }

    /** The folder part of a relative path, with `\` read as `/`. Empty for a file at the root, null
     * when there is no path. */
    fun folder(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        val path = relativePath.trim().replace('\\', '/').trimStart('/')
        val cut = path.lastIndexOf('/')
        return if (cut < 0) "" else path.substring(0, cut)
    }

    /** Disc by disc (an untagged disc is disc 1), and within a disc by track number, an untagged
     * track after every numbered one. Stable, so ties keep the order they were handed in. */
    fun inPlayOrder(tracks: List<CloudTrack>): List<CloudTrack> =
        tracks.sortedWith(
            compareBy<CloudTrack> { position(it.tags?.discNumber).takeIf { d -> d > 0 } ?: 1 }
                .thenBy { position(it.tags?.trackNumber).takeIf { t -> t > 0 } ?: Int.MAX_VALUE },
        )

    /** An album name without a disc suffix ("Album (Disc 2)" files with "Album (Disc 1)"). The
     * desktop's pattern, character for character. */
    fun normalizedAlbumName(album: String): String {
        val trimmed = album.trim()
        val stripped = DISC_SUFFIX.replace(trimmed, "").trim()
        return stripped.ifEmpty { trimmed }
    }

    private val DISC_SUFFIX =
        Regex("""[\s\-–—_:,]*[(\[]?\s*(cd|disc|disk)\s*[-_ ]?\d+\s*[)\]]?$""", RegexOption.IGNORE_CASE)

    private fun albumName(tracks: List<CloudTrack>, folder: String?): String? =
        firstFilled(tracks.map { it.album }) ?: segment(folder, fromEnd = 0)

    private fun albumArtist(tracks: List<CloudTrack>, folder: String?): String? =
        firstFilled(tracks.map { it.tags?.albumArtist })
            ?: firstFilled(tracks.map { it.artist })
            ?: segment(folder, fromEnd = 1)

    private fun segment(folder: String?, fromEnd: Int): String? {
        if (folder.isNullOrBlank()) return null
        val parts = folder.split('/').filter { it.isNotEmpty() }
        return parts.getOrNull(parts.size - 1 - fromEnd)
    }

    private fun firstFilled(values: List<String?>): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun position(value: Int?): Int = if (value != null && value > 0) value else 0

    private fun norm(value: String?) = (value ?: "").trim().lowercase()

    /** Case-insensitive, accent-sensitive, in the device's language, like the iOS app's
     * `compare(options: .caseInsensitive, locale: .current)`. */
    private fun collator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
}
