package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudMoodList
import com.rpm.player.core.cloud.CloudMoodLists
import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.cloud.Shelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The desktop's `MoodPickTests`, with the lists as the cloud sends them. */
class MoodPickTest {

    private val tracks = (1..12).map { CloudTrack(rpmId = "t$it", title = "t$it", artist = "A", album = "A", relativePath = "A/A/t$it.flac") }

    /** One generated list per (kind, genre), each of [size] songs, as [MoodPick.offered] passes them. */
    private fun offered(vararg lists: Pair<String, String?>, size: Int = tracks.size): List<TrackList> = MoodPick.offered(
        Lists.generated(
            CloudMoodLists(lists = lists.map { (kind, genre) -> CloudMoodList(kind = kind, genre = genre, trackIds = tracks.take(size).map { it.rpmId }) }),
            tracks.associateBy { it.rpmId },
            Shelf.Own,
        ),
    )

    private fun id(kind: String, genre: String? = null) = "mood/$kind|${genre.orEmpty()}|0"

    /** A day that is not the one day in seven, so the usual answer applies. */
    private val anOrdinaryDay = 3

    @Test fun theCutTheyPlayMostAtThisHourIsWhatIsHeldUp() {
        val pick = MoodPick.pick(listOf(Mood.Calm), listOf("Jazz", "Rock"), offered("Calm" to "Jazz", "Calm" to "Rock", "Calm" to null), anOrdinaryDay)
        assertEquals(id("Calm", "Jazz"), pick?.id)
    }

    @Test fun aCutTheCollectionDoesNotOfferIsNotAList() {
        // They play jazz most, but there is no quiet jazz list: the next genre they play wins.
        val pick = MoodPick.pick(listOf(Mood.Calm), listOf("Jazz", "Rock"), offered("Calm" to "Rock", "Calm" to null), anOrdinaryDay)
        assertEquals(id("Calm", "Rock"), pick?.id)
    }

    @Test fun withNothingListenedToTheWholeCollectionStands() {
        val pick = MoodPick.pick(listOf(Mood.Calm), emptyList(), offered("Calm" to "Jazz", "Calm" to null), anOrdinaryDay)
        assertEquals(id("Calm"), pick?.id)
    }

    @Test fun aMoodThisCollectionCannotFillIsSteppedPast() {
        val pick = MoodPick.pick(listOf(Mood.Sleep, Mood.Calm), listOf("Jazz"), offered("Calm" to "Jazz"), anOrdinaryDay)
        assertEquals(id("Calm", "Jazz"), pick?.id)
    }

    @Test fun aCollectionOfferingNoneOfTheMoodsIsNoPick() {
        assertNull(MoodPick.pick(listOf(Mood.Calm, Mood.Focus), listOf("Jazz"), emptyList(), anOrdinaryDay))
    }

    @Test fun oneDayInSevenHoldsUpAnotherOfTheGroup() {
        val lists = offered("Calm" to "Jazz", "Calm" to "Rock", "Calm" to null)
        val picks = (0 until 70).map { day -> MoodPick.pick(listOf(Mood.Calm), listOf("Jazz", "Rock"), lists, day)?.id }
        assertEquals(60, picks.count { it == id("Calm", "Jazz") })
        assertEquals(10, picks.count { it != id("Calm", "Jazz") })
    }

    @Test fun theDaysThatDifferWalkTheGroupRatherThanRepeatingOneOtherList() {
        assertEquals(0, MoodPick.which(3, 1))
        assertEquals(1, MoodPick.which(3, 0))
        assertEquals(2, MoodPick.which(3, 7))
        assertEquals(1, MoodPick.which(3, 14))
        assertEquals(2, MoodPick.which(3, 21))
    }

    @Test fun aGroupOfOneHasNowhereToStep() {
        for (day in 0 until 21) assertEquals(0, MoodPick.which(1, day))
    }

    @Test fun aDayNumberBelowNoughtStillStepsForwards() {
        assertTrue(MoodPick.which(3, -7) in 0..2)
        assertTrue(MoodPick.which(3, -14) in 0..2)
    }

    @Test fun theGroupIsTheCutsInOrderOfPlaysThenTheWholeCollection() {
        val group = MoodPick.group(Mood.Calm, listOf("Rock", "Pop", "Jazz"), offered("Calm" to null, "Calm" to "Jazz", "Calm" to "Rock", "Focus" to "Pop"))
        assertEquals(listOf(id("Calm", "Rock"), id("Calm", "Jazz"), id("Calm")), group.map { it.id })
    }

    @Test fun aListTooShortToShowIsNotOffered() {
        // Ten songs for a mood over the whole collection, six for a cut, as the desktop's floors.
        assertEquals(emptyList<TrackList>(), offered("Calm" to null, size = 9))
        assertEquals(listOf(id("Calm", "Jazz")), offered("Calm" to null, "Calm" to "Jazz", size = 6).map { it.id })
    }

    @Test fun decadesAndOtherKindsAreNotOffered() {
        val ids = tracks.map { it.rpmId }
        val lists = Lists.generated(
            CloudMoodLists(
                lists = listOf(
                    CloudMoodList(kind = "Calm", decade = 1990, trackIds = ids),
                    CloudMoodList(kind = "RightNow", trackIds = ids),
                    CloudMoodList(kind = "Melancholy", trackIds = ids),
                ),
            ),
            tracks.associateBy { it.rpmId },
            Shelf.Own,
        )
        assertEquals(emptyList<TrackList>(), MoodPick.offered(lists))
    }
}
