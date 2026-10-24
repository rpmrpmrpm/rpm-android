package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack

/** Which level of a record's listing a song is on (`AlbumSongLevel` on iPhone). */
enum class SongLevel {
    /** An ordinary record's song. */
    Plain,

    /** A whole piece on a classical record: a track that is part of no work, or the only track of one. It is drawn at
     * the level and weight of a work's heading, because that is what it is. */
    Piece,

    /** One movement of the work above it, set in, and named by the movement alone. */
    Movement,
}

/** One line of a record's listing. */
sealed interface AlbumLine {
    val key: String

    data class Disc(val number: Int) : AlbumLine {
        override val key get() = "disc:$number"
    }

    /**
     * The line that opens a work of more than one track: its name, who wrote it (null when the tracks disagree or say
     * nothing), and how long the whole piece runs. Nothing plays from it.
     */
    data class Work(val title: String, val composer: String?, val seconds: Double, val first: String) : AlbumLine {
        override val key get() = "work:$first"
    }

    /**
     * A song. [index] is its place in the record's own order; playing from this row starts there. [title] is the
     * movement alone under a work, the file's title everywhere else. [subtitle] is the composer under a classical
     * piece, null under a movement, and left to the row on a plain record.
     */
    data class Song(val track: CloudTrack, val index: Int, val title: String, val subtitle: String?, val level: SongLevel) : AlbumLine {
        override val key get() = "song:${track.rpmId}:$index"
    }
}

/**
 * A record's lines in reading order, as on iPhone (`AlbumLines.build`): disc by disc, with a heading in front of each
 * disc of a set. A [classical] record is listed as works with their movements under them, and the pieces that are a
 * work on their own at the level of the headings. **The work tags alone never decide**: MusicBrainz names a work for
 * pop songs too (8 pop records of 1,691 in one collection had runs of a work, Back to Black and Off the Wall among
 * them). What decides is the owner's mark, or a genre that files the record as classical music ([classicalByGenre]):
 * in the same collection one record was marked, and 44 filed under Classical or Opera had works on them.
 */
object AlbumLines {
    fun of(tracks: List<CloudTrack>, classical: Boolean): List<AlbumLine> {
        // The tracks are already in disc and track order (AlbumWall.inPlayOrder); a disc is a run of one number.
        val discs = ArrayList<Pair<Int, MutableList<IndexedValue<CloudTrack>>>>()
        tracks.forEachIndexed { index, track ->
            val number = (track.tags?.discNumber ?: 0).takeIf { it > 0 } ?: 1
            if (discs.isEmpty() || discs.last().first != number) discs += number to ArrayList()
            discs.last().second += IndexedValue(index, track)
        }
        val set = discs.map { it.first }.toSet().size > 1
        val lines = ArrayList<AlbumLine>(tracks.size + discs.size)
        for ((number, rows) in discs) {
            if (set) lines += AlbumLine.Disc(number)
            if (classical) {
                lines += works(rows)
            } else {
                rows.mapTo(lines) { (index, track) -> AlbumLine.Song(track, index, track.title.orEmpty(), null, SongLevel.Plain) }
            }
        }
        return lines
    }

    /** One disc of a classical record. A work is a run of consecutive tracks naming it, never every track that does. */
    private fun works(rows: List<IndexedValue<CloudTrack>>): List<AlbumLine> {
        val groups = ArrayList<Pair<String?, MutableList<IndexedValue<CloudTrack>>>>()
        for (row in rows) {
            val work = row.value.tags?.work?.takeIf { it.isNotBlank() }
            if (groups.isEmpty() || groups.last().first != work) groups += work to ArrayList()
            groups.last().second += row
        }
        val lines = ArrayList<AlbumLine>()
        for ((work, group) in groups) {
            // A one-track work has no heading: its own line already says the whole name.
            if (work == null || group.size < 2) {
                group.mapTo(lines) { (index, track) -> AlbumLine.Song(track, index, track.title.orEmpty(), composer(track), SongLevel.Piece) }
                continue
            }
            lines += AlbumLine.Work(
                work,
                agreed(group.map { composer(it.value) }),
                group.sumOf { it.value.tags?.durationSeconds ?: 0.0 },
                group[0].value.rpmId,
            )
            group.mapTo(lines) { (index, track) ->
                AlbumLine.Song(track, index, movement(track, work) ?: track.title.orEmpty(), null, SongLevel.Movement)
            }
        }
        return lines
    }

    /**
     * What a movement's own line says under a work that has said the rest: the movement's tag where the file has one,
     * and otherwise what the title has left once the work in front of it is taken off. A tag that repeats the work in
     * front of the movement is cut the same way: MusicBrainz Picard writes "Der Rosenkavalier, op. 59: Akt I.
     * Einleitung" under the work "Der Rosenkavalier, op. 59: Akt I" (403 of 948 such tags in one collection), which
     * left every movement saying the whole title again. Null when neither says.
     */
    fun movement(track: CloudTrack, work: String): String? {
        track.tags?.movementName?.takeIf { it.isNotBlank() }?.let { return afterWork(it, work) ?: it }
        val title = track.title ?: return null
        return afterWork(title, work)
    }

    /** What [text] has left once [work] in front of it and the marks that part them are taken off; null when it
     * does not start with the work or nothing is left. */
    private fun afterWork(text: String, work: String): String? {
        if (!text.startsWith(work, ignoreCase = true)) return null
        val rest = text.substring(work.length).dropWhile { it in " .,:;-–—" }
        return rest.ifEmpty { null }
    }

    /** The genres that file a record as classical music, by the shelf [Genres.match] puts the tag on. */
    private val CLASSICAL_SHELVES = setOf("Classical", "Opera")

    /** Whether most of [tracks] are filed under classical music or opera by their genre. */
    fun classicalByGenre(tracks: List<CloudTrack>): Boolean {
        if (tracks.isEmpty()) return false
        val filed = tracks.count { Genres.match(it.tags?.genre) in CLASSICAL_SHELVES }
        return filed * 2 > tracks.size
    }

    private fun composer(track: CloudTrack): String? = track.tags?.composer?.takeIf { it.isNotBlank() }

    /** The one answer every track gives, ignoring case, or null. */
    private fun agreed(answers: List<String?>): String? {
        val first = answers.firstOrNull() ?: return null
        return if (answers.all { it != null && it.equals(first, ignoreCase = true) }) first else null
    }
}
