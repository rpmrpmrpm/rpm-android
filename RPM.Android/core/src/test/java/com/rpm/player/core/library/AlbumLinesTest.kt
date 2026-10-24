package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumLinesTest {

    private fun track(id: String, title: String, work: String? = null, movement: String? = null, composer: String? = null, disc: Int = 1, seconds: Double = 60.0) =
        CloudTrack(rpmId = id, title = title, tags = CloudTrackTags(durationSeconds = seconds, discNumber = disc, work = work, movementName = movement, composer = composer))

    private val fifth = "Symphony No. 5 in C minor, Op. 67"

    private val record = listOf(
        track("1", "$fifth: I. Allegro con brio", fifth, composer = "Beethoven"),
        track("2", "$fifth: II. Andante con moto", fifth, composer = "Beethoven"),
        track("3", "Egmont Overture", "Egmont, Op. 84", composer = "Beethoven"),
        track("4", "Für Elise", composer = "Beethoven"),
    )

    @Test
    fun aClassicalRecordIsListedAsWorksWithTheirMovements() {
        val lines = AlbumLines.of(record, classical = true)
        val work = lines[0] as AlbumLine.Work
        assertEquals(fifth, work.title)
        assertEquals("Beethoven", work.composer)
        assertEquals(120.0, work.seconds, 0.0)
        val first = lines[1] as AlbumLine.Song
        assertEquals(SongLevel.Movement, first.level)
        assertEquals("I. Allegro con brio", first.title)
        assertEquals(0, first.index)
        assertEquals("II. Andante con moto", (lines[2] as AlbumLine.Song).title)
    }

    @Test
    fun aOneTrackWorkAndATrackOfNoWorkArePiecesWithTheirComposer() {
        val lines = AlbumLines.of(record, classical = true)
        val overture = lines[3] as AlbumLine.Song
        val elise = lines[4] as AlbumLine.Song
        assertEquals(SongLevel.Piece, overture.level)
        assertEquals("Egmont Overture", overture.title)
        assertEquals("Beethoven", overture.subtitle)
        assertEquals(SongLevel.Piece, elise.level)
        assertEquals(3, elise.index)
        assertEquals(5, lines.size)
    }

    @Test
    fun theMovementTagIsTakenBeforeTheTitle() {
        val t = track("1", "$fifth - 1st movement", fifth, movement = "Allegro con brio")
        assertEquals("Allegro con brio", AlbumLines.movement(t, fifth))
    }

    @Test
    fun aMovementTagThatRepeatsTheWorkIsCutToTheMovement() {
        val work = "Der Rosenkavalier, op. 59: Akt I"
        val t = track("1", "$work. Einleitung", work, movement = "$work. Einleitung")
        assertEquals("Einleitung", AlbumLines.movement(t, work))
    }

    @Test
    fun aRecordFiledUnderClassicalOrOperaIsClassicalByItsGenre() {
        fun genre(g: String?) = CloudTrack(rpmId = "t", tags = CloudTrackTags(genre = g))
        assertTrue(AlbumLines.classicalByGenre(listOf(genre("Classical"), genre("Classical"), genre(null))))
        assertTrue(AlbumLines.classicalByGenre(listOf(genre("Opera"))))
        assertEquals(false, AlbumLines.classicalByGenre(listOf(genre("Soul"), genre("Classical"))))
        assertEquals(false, AlbumLines.classicalByGenre(emptyList()))
    }

    @Test
    fun aTitleThatDoesNotStartWithTheWorkSaysNoMovement() {
        assertNull(AlbumLines.movement(track("1", "Allegro con brio", fifth), fifth))
    }

    @Test
    fun aRecordNotMarkedClassicalIsPlainWhateverItsTags() {
        val lines = AlbumLines.of(record, classical = false)
        assertEquals(4, lines.size)
        assertTrue(lines.all { it is AlbumLine.Song && it.level == SongLevel.Plain })
    }

    @Test
    fun aWorkDoesNotRunOverIntoTheNextDisc() {
        val set = listOf(
            track("1", "$fifth: I.", fifth, disc = 1),
            track("2", "$fifth: II.", fifth, disc = 2),
        )
        val lines = AlbumLines.of(set, classical = true)
        assertEquals(listOf("disc:1", "song:1:0", "disc:2", "song:2:1"), lines.map { it.key })
    }
}
