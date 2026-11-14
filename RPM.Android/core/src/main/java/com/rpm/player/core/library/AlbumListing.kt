package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import java.text.Normalizer

/**
 * The orders of the records' list view: the iPhone's `ListOrdering` (RPMLibrary/AlbumListing.swift).
 *
 * Stored by name, like [AlbumOrdering], so a new order added later does not move anybody's saved choice onto its
 * neighbour.
 */
enum class ListOrdering(val stored: String) {
    /** Each record with its songs, by whose record it is, then by its name. */
    Artist("Artist"),

    /** Each record with its songs, by the record's own name. */
    Album("Album"),

    /** Every song as its own row, by its title. */
    Track("Track");

    companion object {
        fun of(stored: String?): ListOrdering? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * The records as one list, for the phone's list view of the library: the iPhone's `AlbumListing`. It joins the
 * desktop's last two views: the album list, where each record stands with its songs under it, and the data table,
 * which can be put in another order. Ordered by artist or by record, each record is one block with its songs. Ordered
 * by track, every song is a row of its own, by title.
 *
 * **The letter comes first in the order, then the name**, as on the artists wall ([ArtistWall.initial]). The letter
 * skips a name's leading punctuation, and a plain sort by name does not. So "(hed) p.e." is filed under H, and a sort by
 * name alone would put it ahead of every A: the rail's letters would then run H, A, B down the list, and a press on A
 * would land under H. Names with no letter of the rail (digits, symbols, other scripts) are filed under '#', which is
 * last, as on the rail.
 */
class AlbumListing private constructor(
    val rows: List<Row>,
    /** The first row filed under each letter of the rail, by row id. A letter that is not here has nothing under it. */
    val firstUnder: Map<Char, String>,
) {
    /** One row of the list. Its [id] is unique in the list, so a lazy list can key its items by it. */
    sealed interface Row {
        val id: String

        /** A record, drawn with its songs under it. */
        data class Record(val album: CloudAlbum) : Row {
            override val id: String get() = "record:${album.key}"
        }

        /** One song, in track order, and the record it is on. */
        data class Song(val track: CloudTrack, val on: CloudAlbum) : Row {
            override val id: String get() = "song:${on.key}/${track.rpmId}"
        }
    }

    companion object {
        /** The letters of the rail, top to bottom: A to Z, then '#' for everything else (the iPhone's `ArtistWall.alphabet`). */
        val ALPHABET: List<Char> = ('A'..'Z').toList() + '#'

        val EMPTY = AlbumListing(emptyList(), emptyMap())

        /**
         * Builds the list. It reads every song in track order, so a caller with a large library runs it away from the
         * main thread.
         */
        fun of(albums: List<CloudAlbum>, order: ListOrdering): AlbumListing {
            val filed: List<Pair<Place, Row>> = when (order) {
                ListOrdering.Artist -> albums.map { Place(it.artist, listOf(it.album)) to Row.Record(it) }
                ListOrdering.Album -> albums.map { Place(it.album, listOf(it.artist)) to Row.Record(it) }
                ListOrdering.Track -> {
                    val songs = ArrayList<Pair<Place, Row>>(albums.sumOf { it.tracks.size })
                    for (album in albums) {
                        for (track in album.tracks) {
                            val place = Place(
                                track.title.orEmpty(),
                                listOf(track.artist ?: album.artist, album.album),
                                disc = track.tags?.discNumber ?: 0,
                                number = track.tags?.trackNumber ?: 0,
                            )
                            songs += place to Row.Song(track, album)
                        }
                    }
                    songs
                }
            }

            val sorted = filed.sortedWith { a, b -> a.first.compareTo(b.first) }

            val firstUnder = HashMap<Char, String>()
            for ((place, row) in sorted) firstUnder.putIfAbsent(place.letter, row.id)

            return AlbumListing(sorted.map { it.second }, firstUnder)
        }
    }

    /**
     * Where a row goes: its letter on the rail, then its name, then the names that break a tie, read once per row and
     * not once per comparison.
     */
    private class Place(name: String, ties: List<String>, val disc: Int = 0, val number: Int = 0) : Comparable<Place> {
        val letter: Char = ArtistWall.initial(name)
        val names: List<String> = (listOf(name) + ties).map(::folded)

        override fun compareTo(other: Place): Int {
            // '#' after Z, whatever its character sorts as.
            val last = letter == '#'
            val otherLast = other.letter == '#'
            if (last != otherLast) return if (otherLast) -1 else 1
            if (letter != other.letter) return letter.compareTo(other.letter)
            for (i in 0 until minOf(names.size, other.names.size)) {
                val by = names[i].compareTo(other.names[i])
                if (by != 0) return by
            }
            if (names.size != other.names.size) return names.size.compareTo(other.names.size)
            if (disc != other.disc) return disc.compareTo(other.disc)
            return number.compareTo(other.number)
        }

        private companion object {
            private val MARKS = Regex("\\p{Mn}+")

            /** Case, accents and width do not count: "Émilie" sits with "Emilie", as the rail files it. */
            fun folded(text: String): String =
                MARKS.replace(Normalizer.normalize(text.trim(), Normalizer.Form.NFKD), "").lowercase()
        }
    }
}
