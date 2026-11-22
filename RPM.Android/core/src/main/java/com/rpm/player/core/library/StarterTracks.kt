package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.cloud.Shelf

/**
 * The three records that ship inside the phone app, the desktop's starter tracks (`StarterMusic` there): Connect by
 * Northern Palaces, Satie's first Gymnopédie, and the first movement of the Moonlight Sonata played by Paul Pitman for
 * Musopen. A person whose own collection holds no music sees them in its place ([shown]), so a new account has
 * something to play, and the first-launch welcome has a song to spin.
 *
 * **The cloud never sees them.** They are not in the cloud's catalogue, so every write that names a track or a record
 * would fail or store an id nobody can resolve. Their ids all start with [PREFIX], and each write that could reach them
 * asks [isStarter] first: the favourite marks, the playlists, the downloads, Quick selection, the play counts, the lyric
 * timing, and the counts a profile shows. They play from the app's assets ([assetOf]), and their pictures are read from
 * there too ([pictureAsset]).
 *
 * The files are in the phone's `assets/starter` folder. The TV app does not ship them and never offers them: only the
 * phone hands [tracks] to the library ([LibraryRepository]).
 */
object StarterTracks {
    /** The start of every starter track's id, and of their picture keys. A cloud id is a GUID, so it never starts so. */
    const val PREFIX = "rpm-starter-"

    /** The folder under the app's assets that holds the files. */
    private const val FOLDER = "starter"

    /** The folder every starter track's path is in. The wall groups a record by its folder ([AlbumWall.key]), so each
     * record's key starts with this. */
    private const val PATH = "rpm-starter/"

    /** One record: the name its files and ids are made from, and its one track. */
    private class Record(val name: String, val disc: Boolean, val track: CloudTrack)

    private fun record(
        name: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        genre: String,
        seconds: Double,
        lufs: Double,
        truePeak: Double,
        composer: String? = null,
        releaseType: String? = null,
        disc: Boolean = false,
    ) = Record(
        name,
        disc,
        CloudTrack(
            rpmId = PREFIX + name,
            relativePath = "$PATH$name/$name.ogg",
            title = title,
            artist = artist,
            album = album,
            tags = CloudTrackTags(
                durationSeconds = seconds,
                trackNumber = 1,
                discNumber = 1,
                year = year,
                genre = genre,
                albumArtist = artist,
                extension = "ogg",
                sampleRate = 48_000,
                bitrateKbps = 96,
                codecDescription = "Opus",
                channels = 2,
                composer = composer,
                loudnessLufs = lufs,
                truePeak = truePeak,
                releaseType = releaseType,
            ),
        ),
    )

    // The tags are the desktop's files' own. The loudness and the true peak were measured on these Opus files with
    // ffmpeg's ebur128 filter; the peak is linear, where 1.0 is full scale.
    private val RECORDS = listOf(
        record(
            "connect", "Connect (2017 Version)", "Northern Palaces", "Connect - Single", 2017, "Electronic / Dance",
            seconds = 246.676, lufs = -11.3, truePeak = 1.334, releaseType = "Single",
        ),
        record(
            "gymnopedie", "Gymnopedie no. 1 - lent et douloureux", "Erik Satie", "Gymnopedie no. 1", 1998, "Classical",
            seconds = 183.595, lufs = -25.9, truePeak = 0.447, composer = "Erik Satie", releaseType = "Single",
        ),
        record(
            "moonlight", "Sonata No.14 in C-sharp minor, Op.27, No.2 (\"Moonlight\"): I. Adagio sostenuto", "Ludwig van Beethoven",
            "Moonlight Sonata", 1801, "Classical",
            seconds = 335.776, lufs = -32.4, truePeak = 0.204, composer = "Ludwig van Beethoven", disc = true,
        ),
    )

    private val byId = RECORDS.associateBy { it.track.rpmId }

    /** The three tracks, as the library is built from them. */
    val tracks: List<CloudTrack> = RECORDS.map { it.track }

    /**
     * The welcome's song, in the order it is chosen: Connect first, because it has a beat to show the player off with,
     * then the Gymnopédie. The Moonlight Sonata is long and quiet, so it comes last.
     */
    val welcomeOrder: List<String> = listOf("connect", "gymnopedie", "moonlight").map { PREFIX + it }

    /** Whether [rpmId] is a starter track. */
    fun isStarter(rpmId: String): Boolean = rpmId.startsWith(PREFIX)

    /** Whether [album] is a starter record: every one of its tracks is a starter track. */
    fun isStarter(album: CloudAlbum): Boolean = album.tracks.isNotEmpty() && album.tracks.all { isStarter(it.rpmId) }

    /** Whether [albumKey] is a starter record's key ([AlbumWall.key] of a track in [PATH]). */
    fun isStarterKey(albumKey: String): Boolean = albumKey.startsWith("folder:$PATH")

    /** Whether [collection] is the starter shelf, shown in place of an empty collection, rather than the person's music. */
    fun isStarterShelf(collection: Collection): Boolean = collection.albums.isNotEmpty() && collection.albums.all(::isStarter)

    /**
     * The tracks the library is built from: [tracks] as read, or the starter tracks in their place when [shelf] is the
     * person's own and holds nothing. [starters] is what the app ships, empty on the TV. [force] shows them over a
     * collection that has music, which only a debuggable build can ask for, to look at a new account's screens.
     */
    fun shown(shelf: Shelf, tracks: List<CloudTrack>, starters: List<CloudTrack>, force: Boolean = false): List<CloudTrack> =
        if (shelf == Shelf.Own && starters.isNotEmpty() && (tracks.isEmpty() || force)) starters else tracks

    /** The asset path of the starter track [rpmId], as `asset:///` addresses name it, or null for any other track. */
    fun assetOf(rpmId: String): String? = byId[rpmId]?.let { "$FOLDER/${it.name}.ogg" }

    /** The cover of a starter record, or null for any other record. The key is read back by [pictureAsset]. */
    fun coverOf(album: CloudAlbum): Cover? =
        recordOf(album)?.let { Cover(Shelf.Own, listOf(COVER + it.name), stamp = 0) }

    /** The disc picture of a starter record that has one, or null. */
    fun discOf(album: CloudAlbum): DiscArt? =
        recordOf(album)?.takeIf { it.disc }?.let { DiscArt(Shelf.Own, listOf(DISC + it.name), stamp = 0) }

    /** The asset path of the picture a cover or disc key names, or null for a key the cloud keeps. */
    fun pictureAsset(key: String?): String? {
        key ?: return null
        val cover = key.removePrefix(COVER).takeIf { it != key }
        val disc = key.removePrefix(DISC).takeIf { it != key }
        return when {
            cover != null && RECORDS.any { it.name == cover } -> "$FOLDER/$cover.jpg"
            disc != null && RECORDS.any { it.name == disc && it.disc } -> "$FOLDER/$disc-disc.webp"
            else -> null
        }
    }

    private fun recordOf(album: CloudAlbum): Record? =
        if (isStarter(album)) byId[album.tracks.first().rpmId] else null

    private const val COVER = PREFIX + "cover-"
    private const val DISC = PREFIX + "disc-"
}
