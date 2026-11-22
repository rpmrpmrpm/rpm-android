package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudMoodList
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.CloudTrackTags
import com.rpm.player.core.cloud.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which kinds of music a mood list says it holds: the phone's MoodBlurbTests, which are the desktop's
 * `MoodBlurbTests`, so every app describes one list the same way. The wording belongs to the screen and
 * is not tested here. */
class MoodBlurbTest {
    private fun tags(vararg rows: Pair<String, Int>): List<String?> = rows.flatMap { (tag, count) -> List(count) { tag } }

    @Test fun theBiggestKindsAreNamedFirst() {
        assertEquals(listOf("rock", "pop", "jazz"), MoodBlurb.spoken(tags("Jazz" to 5, "Rock" to 20, "Pop" to 10)))
    }

    @Test fun noMoreThanThreeAreNamed() {
        assertEquals(listOf("rock", "pop", "jazz"), MoodBlurb.spoken(tags("Rock" to 20, "Pop" to 18, "Jazz" to 16, "Blues" to 14, "Folk" to 12)))
    }

    /** One song in fifty is the exception, not the list. */
    @Test fun aKindBarelyPresentIsNotNamed() {
        assertEquals(listOf("rock"), MoodBlurb.spoken(tags("Rock" to 49, "Reggae" to 1)))
    }

    @Test fun theSpellingsOfOneKindAreCountedTogether() {
        assertEquals(listOf("house", "rock"), MoodBlurb.spoken(tags("Deep House" to 8, "Acid House" to 7, "Rock" to 5)))
    }

    @Test fun anUntaggedListNamesNothing() {
        assertTrue(MoodBlurb.spoken(emptyList()).isEmpty())
        assertTrue(MoodBlurb.spoken(listOf(null, null, null)).isEmpty())
        assertTrue(MoodBlurb.spoken(tags("Norwegian Sea Shanties" to 10)).isEmpty())
    }

    @Test fun aMoodListIsDescribedByWhatIsOnIt() {
        assertEquals(MoodLine(Mood.Energetic, listOf("rock", "pop")), MoodBlurb.of("Energetic", tags("Rock" to 10, "Pop" to 5)))
    }

    /** Sleep is cut across every genre, so it names none. */
    @Test fun sleepNamesNoGenres() {
        assertEquals(MoodLine(Mood.Sleep, emptyList()), MoodBlurb.of("Sleep", emptyList()))
    }

    /** Zen, New Year's Eve and Merry Christmas are cut across every genre too, so they name none either. */
    @Test fun zenAndNewYearsEveNameNoGenres() {
        assertEquals(MoodLine(Mood.Zen, emptyList()), MoodBlurb.of("Zen", tags("Ambient" to 10)))
        assertEquals(MoodLine(Mood.NewYearsEve, emptyList()), MoodBlurb.of("NewYearsEve", tags("Pop" to 10)))
        assertEquals(MoodLine(Mood.Christmas, emptyList()), MoodBlurb.of("Christmas", tags("Pop" to 10)))
    }

    /** The four feelings the sound model hears are cut by genre, like Calm, so they say which. */
    @Test fun theFeelingListsNameTheirGenres() {
        for (kind in listOf("Melancholy", "Happy", "Angry", "Romantic")) {
            assertEquals(MoodLine(Mood.valueOf(kind), listOf("jazz")), MoodBlurb.of(kind, tags("Jazz" to 10)))
        }
    }

    /** A list about listening says what it is in its title. */
    @Test fun onlyTheMoodsAreDescribed() {
        assertNull(MoodBlurb.of("RightNow", tags("Rock" to 10)))
        assertNull(MoodBlurb.of("Classical", tags("Classical" to 10)))
        assertNull(MoodBlurb.of(null, tags("Rock" to 10)))
    }

    /** A mood list nothing on which is tagged says nothing rather than a sentence with a hole in it. */
    @Test fun aMoodListWithNoTagsHasNoSentence() {
        assertNull(MoodBlurb.of("Calm", listOf(null, null)))
    }

    /** The sentence is counted from the songs the list shows, not from the whole run the computer sent. */
    @Test fun aGeneratedListCarriesTheSentenceForTheSongsItShows() {
        fun track(id: String, genre: String) = CloudTrack(rpmId = id, tags = CloudTrackTags(genre = genre))
        val tracks = listOf(track("a", "Jazz"), track("b", "Jazz"), track("c", "Rock"), track("d", "Rock"), track("e", "Rock"))
        val cut = CloudMoodLists(lists = listOf(CloudMoodList(kind = "Calm", trackIds = listOf("a", "b", "c", "d", "e"), shows = 2)))
        val lists = Lists.generated(cut, tracks.associateBy { it.rpmId }, Shelf.Own)
        assertEquals(MoodLine(Mood.Calm, listOf("jazz")), lists.single().blurb)
    }

    /** A press answered by the store shows the store's new list, not a slice of the run the cut came with. */
    @Test
    fun aNewReadingFromTheStoreReplacesTheSlice() {
        fun track(id: String) = CloudTrack(rpmId = id, tags = CloudTrackTags(genre = "Jazz"))
        val tracks = listOf("a", "b", "c", "d", "n1", "n2").map(::track)
        val cut = CloudMoodLists(lists = listOf(CloudMoodList(kind = "Calm", id = "L", trackIds = listOf("a", "b", "c", "d"), shows = 2)))
        val lists = Lists.generated(cut, tracks.associateBy { it.rpmId }, Shelf.Own, mapOf("L" to 7), mapOf("L" to listOf("n1", "n2")))
        assertEquals(listOf("n1", "n2"), lists.single().tracks.map { it.rpmId })
    }
}
