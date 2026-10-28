package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudMoodList
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** What a song radio picks next, as the desktop's SongRadioTests check it, with what the phone has to go on. */
class SongRadioTest {

    /** A song with its own record and its own act unless told otherwise, so spacing stays out of tests not about it. */
    private fun song(
        id: String,
        year: Int = 1975,
        genre: String? = "Rock",
        styles: Set<String> = emptySet(),
        moods: Set<String> = emptySet(),
        artist: String = "act $id",
        record: String = id,
        plays: Int = 0,
        offered: Boolean = true,
        live: Boolean = false,
    ) = RadioSong(id, record, artist, "$artist\n$id", year, genre, styles, moods, offered, plays, live)

    private fun pick(crate: RadioCrate, seed: String, count: Int, history: List<String> = emptyList(), origin: String = seed, random: Int = 7) =
        SongRadio.pick(crate, seed, origin, emptySet(), history, count, Random(random))

    // ── what is close ──

    @Test fun aSongOfTheSameStyleComesBeforeAnUnfiledOneAndThatBeforeAnotherStyle() {
        val seed = song("seed", genre = "Electronic / Dance", styles = setOf("Trip Hop"))
        val same = song("same", genre = "Electronic / Dance", styles = setOf("Trip Hop"))
        val unfiled = song("unfiled", genre = "Electronic / Dance")
        val electro = song("electro", genre = "Electronic / Dance", styles = setOf("Electro"))

        assertTrue(SongRadio.apart(same, seed) < SongRadio.apart(unfiled, seed))
        assertTrue(SongRadio.apart(unfiled, seed) < SongRadio.apart(electro, seed))
    }

    @Test fun kinStylesAreCloserThanStylesWithNothingInCommon() {
        val lofi = song("lofi", genre = "Electronic / Dance", styles = setOf("Lo-fi Beats"))
        assertEquals(SongRadio.KIN_STYLE, SongRadio.genreApart(song("d", genre = "Electronic / Dance", styles = setOf("Downtempo")), lofi), 1e-9)
        assertEquals(SongRadio.OTHER_STYLE, SongRadio.genreApart(song("e", genre = "Electronic / Dance", styles = setOf("Electro")), lofi), 1e-9)
    }

    // ── styles that clash ──

    /** The mixtape after a dance-pop record: a ballad from the same year is closer in era than dance-pop from
     * another decade, and it is still not played. */
    @Test fun aSongWhoseStyleClashesWithTheOnePlayingIsNotPicked() {
        val seed = song("seed", 2003, genre = "Pop", styles = setOf("Dance-Pop"))
        val ballad = song("ballad", 2003, genre = "Pop", styles = setOf("Ballad"))
        val dancing = (0 until SongRadio.CLASH_SPARE + 10).map {
            song("dance$it", 1990, genre = "Pop", styles = setOf(if (it % 2 == 0) "Dance-Pop" else "Europop"))
        }
        val crate = RadioCrate(listOf(seed, ballad) + dancing)

        assertTrue(SongRadio.apart(ballad, seed) < SongRadio.apart(dancing[0], seed))
        for (r in 0 until 20) assertFalse("ballad" in pick(crate, "seed", 5, random = r))
    }

    @Test fun aSongWithNoStyleIsNotLeftOutAsAClash() {
        val seed = song("seed", genre = "Pop", styles = setOf("Dance-Pop"))
        val unfiled = song("unfiled", genre = "Pop")
        assertEquals(listOf(unfiled), SongRadio.withoutClashes(listOf(unfiled), seed, 0))
    }

    @Test fun clashesStayWhenTooFewSongsWouldBeLeft() {
        val crate = RadioCrate(listOf(
            song("seed", genre = "Pop", styles = setOf("Dance-Pop")),
            song("ballad", genre = "Pop", styles = setOf("Ballad")),
            song("dance", genre = "Pop", styles = setOf("Dance-Pop")),
        ))
        assertEquals(2, pick(crate, "seed", 2).size)
    }

    @Test fun stylesAreKinWhereTheyArePlayedTogether() {
        fun apart(a: String, b: String) = SongRadio.styleApart(setOf(a), setOf(b))
        assertEquals(SongRadio.KIN_STYLE, apart("Dance-Pop", "Europop")!!, 1e-9)
        assertEquals(SongRadio.OTHER_STYLE, apart("Dance-Pop", "Ballad")!!, 1e-9)
        assertEquals(SongRadio.KIN_STYLE, apart("Folk Rock", "Hard Rock")!!, 1e-9)
        assertEquals(SongRadio.KIN_STYLE, apart("Celtic Folk", "Acoustic Rock")!!, 1e-9)
        assertEquals(SongRadio.OTHER_STYLE, apart("Celtic Folk", "Hard Rock")!!, 1e-9)
        assertEquals(SongRadio.KIN_STYLE, apart("Contemporary R&B", "Neo-Soul")!!, 1e-9)
        assertEquals(SongRadio.KIN_STYLE, apart("Contemporary R&B", "Conscious Hip Hop")!!, 1e-9)
        assertEquals(SongRadio.OTHER_STYLE, apart("Contemporary R&B", "Pop Rap")!!, 1e-9)
    }

    // ── live recordings ──

    @Test fun aLiveRecordingIsToldByItsRecordItsTitleOrItsAlbum() {
        assertTrue(SongRadio.isLive("Sail Away (Live)", null, null))
        assertTrue(SongRadio.isLive("Babylon [Live at the Point]", null, null))
        assertTrue(SongRadio.isLive("Layla - Unplugged", null, null))
        assertTrue(SongRadio.isLive("Babylon", "Live", null))
        assertTrue(SongRadio.isLive("Babylon", "Live at the BBC", null))
        assertTrue(SongRadio.isLive("Babylon", "MTV Unplugged", null))
        assertTrue(SongRadio.isLive("Babylon", "White Ladder", "Live"))
        assertFalse(SongRadio.isLive("Groovejet (If This Ain't Love) (Live Mix)", "Read My Lips", "Album"))
        assertFalse(SongRadio.isLive("Live Forever", "Definitely Maybe", null))
        assertFalse(SongRadio.isLive("Babylon", "Long Live the King", null))
        assertFalse(SongRadio.isLive("Babylon", "White Ladder", null))
    }

    @Test fun aRadioFromAStudioSongPutsLiveRecordingsBehindStudioOnes() {
        val seed = song("seed", 2000, genre = "Pop")
        val live = song("live", 2000, genre = "Pop", live = true)
        val studio = song("studio", 2004, genre = "Pop")
        assertTrue(SongRadio.score(live, seed, seed) > SongRadio.score(studio, seed, seed))
    }

    @Test fun aRadioFromALiveSongDoesNotHoldLiveRecordingsBack() {
        val seed = song("seed", 2000, genre = "Pop", live = true)
        val live = song("live", 2000, genre = "Pop", live = true)
        val studio = song("studio", 2004, genre = "Pop")
        assertTrue(SongRadio.score(live, seed, seed) < SongRadio.score(studio, seed, seed))
    }

    @Test fun aRelatedShelfIsCloserThanAnUnrelatedOne() {
        val seed = song("seed", genre = "Rock")
        assertTrue(SongRadio.apart(song("metal", genre = "Metal"), seed) < SongRadio.apart(song("classical", genre = "Classical"), seed))
    }

    @Test fun twoSongsInOneMoodAreCloserThanTwoInDifferentMoodsAndUnknownIsBetween() {
        val seed = song("seed", moods = setOf("Calm"))
        val calm = SongRadio.moodApart(song("calm", moods = setOf("Calm")), seed)
        val none = SongRadio.moodApart(song("none"), song("other"))
        val energetic = SongRadio.moodApart(song("energetic", moods = setOf("Energetic")), seed)
        assertTrue(calm < none)
        assertTrue(none < energetic)
    }

    @Test fun betweenTwoClassicalRecordsTheRecordingYearIsNotAnEra() {
        val mozart = song("mozart", 1987, genre = "Classical")
        assertEquals(0.0, SongRadio.eraApart(song("bach", 2000, genre = "Classical"), mozart), 1e-9)
        assertEquals(1.0, SongRadio.eraApart(song("pop", 1960, genre = "Pop"), song("pop2", 2020, genre = "Pop")), 1e-9)
        assertEquals(SongRadio.UNKNOWN, SongRadio.eraApart(song("undated", 0), mozart), 1e-9)
    }

    @Test fun everyStyleInAFamilyIsAStyleRpmKnows() {
        val known = SubgenreTable.styles.map { it.first }.toSet()
        for (style in SongRadio.STYLE_FAMILIES.flatten()) assertTrue(style, style in known)
    }

    // ── picking ──

    @Test fun aRadioOnTripHopDoesNotPickElectroWhileTripHopIsLeft() {
        val chill = (0 until SongRadio.SHORTLIST + 3).map { song("chill$it", genre = "Electronic / Dance", styles = setOf("Trip Hop")) }
        val club = (0 until SongRadio.SHORTLIST).map { song("club$it", genre = "Electronic / Dance", styles = setOf("Electro")) }
        val crate = RadioCrate(listOf(song("seed", genre = "Electronic / Dance", styles = setOf("Trip Hop"))) + chill + club)
        for (r in 0 until 20) pick(crate, "seed", 3, random = r).forEach { assertTrue(it, it.startsWith("chill")) }
    }

    @Test fun aClassicalRadioKeepsToClassicalMusic() {
        val classical = (0 until SongRadio.SHORTLIST + 4).map { song("classical$it", 1990 + it, genre = "Classical") }
        val pop = (0 until SongRadio.SHORTLIST).map { song("pop$it", 1987, genre = "Pop") }
        val crate = RadioCrate(listOf(song("seed", 1987, genre = "Classical")) + classical + pop)
        for (r in 0 until 20) pick(crate, "seed", 4, random = r).forEach { assertTrue(it, it.startsWith("classical")) }
    }

    @Test fun aSongInTheQueueOrAlreadyQueuedIsNotPicked() {
        val crate = RadioCrate(listOf(song("seed"), song("queued"), song("played"), song("fresh")))
        assertEquals(listOf("fresh"), SongRadio.pick(crate, "seed", "seed", setOf("seed", "queued"), listOf("played"), 3, Random(1)))
    }

    @Test fun anInterludeCanStartARadioButIsNeverPicked() {
        val crate = RadioCrate(listOf(song("interlude", offered = false), song("song"), song("other", offered = false)))
        assertEquals(listOf("song"), pick(crate, "interlude", 5))
    }

    @Test fun anActDoesNotComeBackUntilOthersHavePlayed() {
        val same = (0 until 6).map { song("same$it", artist = "Band") }
        val others = (0 until 6).map { song("other$it", 1980) }
        val crate = RadioCrate(listOf(song("seed", artist = "Band")) + same + others)
        pick(crate, "seed", SongRadio.ARTIST_GAP).forEach { assertTrue(it, it.startsWith("other")) }
    }

    @Test fun spacingGivesWayWhenOneActIsAllThereIs() {
        val crate = RadioCrate((0 until 5).map { song("s$it", artist = "Band", record = "album") })
        assertEquals(4, pick(crate, "s0", 4).size)
    }

    @Test fun aRadioWhoseSeedAndOriginAreUnknownPicksNothing() {
        assertTrue(pick(RadioCrate(listOf(song("a"))), "gone", 3, origin = "also gone").isEmpty())
    }

    // ── favourites ──

    /** Ten songs closest, then the favourite, then a crowd further off in era: enough that the favourite is within
     * the fit without being in the closest shortlist, so only its turn can bring it. */
    private fun favouriteCrate() = RadioCrate(
        listOf(song("seed")) + (0 until 10).map { song("near$it") } +
            song("favourite", 1977, plays = 50) + (0 until 300).map { song("crowd$it", 1995) },
    )

    @Test fun everyFifthSongIsAFavouriteThatFits() {
        val history = listOf("seed", "near0", "near1", "near2", "near3")
        for (r in 0 until 10) assertEquals("favourite", pick(favouriteCrate(), "near3", 1, history, origin = "seed", random = r)[0])
    }

    @Test fun aFavouriteWaitsForItsTurn() {
        val picked = pick(favouriteCrate(), "near0", 4, listOf("seed", "near0"), origin = "seed", random = 1)
        assertFalse(picked.take(3).contains("favourite"))
        assertEquals("favourite", picked[3])
    }

    @Test fun aFavouriteThatDoesNotFitIsNotPlayed() {
        val crate = RadioCrate(
            listOf(song("seed")) + (0 until 40).map { song("close$it") } + song("favourite", 2020, genre = "Classical", plays = 50),
        )
        val history = listOf("seed", "close0", "close1", "close2", "close3")
        for (r in 0 until 10) assertFalse(pick(crate, "close3", 6, history, origin = "seed", random = r).contains("favourite"))
    }

    @Test fun aSongPlayedOnceOrTwiceIsNotAFavourite() {
        val crate = RadioCrate(listOf(song("tried", plays = RadioCrate.FAVOURITE_LEAST_PLAYS - 1), song("loved", plays = RadioCrate.FAVOURITE_LEAST_PLAYS)))
        assertFalse(crate.isFavourite(crate.find("tried")!!))
        assertTrue(crate.isFavourite(crate.find("loved")!!))
    }

    // ── what the crate reads ──

    @Test fun stylesAreReadFromEveryEntryOfATag() {
        assertEquals(setOf("Romantic Classical", "Classical Period"), Genres.styles("classical, romantic classical, classical period"))
        assertEquals(setOf("Trip Hop"), Genres.styles("Trip Hop"))
        assertTrue(Genres.styles("Rock").isEmpty())
    }

    @Test fun theMoodsAreReadFromEveryWindowOfThePlainMoods() {
        val cut = CloudMoodLists(
            lists = listOf(
                CloudMoodList(kind = "Calm", trackIds = listOf("a", "b", "c"), shows = 1),
                CloudMoodList(kind = "Calm", genre = "Jazz", trackIds = listOf("d")),
                CloudMoodList(kind = "Energetic", decade = 1980, trackIds = listOf("e")),
                CloudMoodList(kind = "RightNow", trackIds = listOf("f")),
            ),
        )
        val moods = Lists.moods(cut)
        assertEquals(setOf("Calm"), moods["c"])
        assertEquals(setOf("Calm"), moods["d"])
        assertFalse(moods.containsKey("e"))
        assertFalse(moods.containsKey("f"))
    }

    @Test fun anEditionNoteDoesNotMakeAnotherSong() {
        val a = CloudTrack(rpmId = "a", artist = "Band", title = "Song")
        val b = CloudTrack(rpmId = "b", artist = "Band", title = "Song (2011 Remaster)")
        val c = CloudTrack(rpmId = "c", artist = "Band", title = "Song (Part 2)")
        assertEquals(RadioCrate.songOf(a), RadioCrate.songOf(b))
        assertNotEquals(RadioCrate.songOf(a), RadioCrate.songOf(c))
    }

    // ── home ──

    @Test fun theSecondRadioIsOnTheMostPlayedSongByAnotherAct() {
        fun t(id: String, artist: String) = CloudTrack(rpmId = id, artist = artist, tags = CloudTrackTags())
        val seeds = HomeShelf.radioSeeds(listOf(t("s1", "Sting"), t("s2", "sting"), t("s3", "Björk"), t("s4", "Prince")))
        assertEquals(listOf("s1", "s3"), seeds.map { it.rpmId })
        assertEquals(listOf("s1"), HomeShelf.radioSeeds(listOf(t("s1", "Sting"), t("s2", "Sting"))).map { it.rpmId })
    }
}
