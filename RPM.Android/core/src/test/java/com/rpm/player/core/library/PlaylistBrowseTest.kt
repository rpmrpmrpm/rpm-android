package com.rpm.player.core.library

import com.rpm.player.core.cloud.CloudTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The iOS app's PlaylistFolderTreeTests and PlaylistBrowseTests, for the rows this app has. */
class PlaylistBrowseTest {

    // ── where a playlist sits ──

    @Test fun aPlaylistNobodyFiledIsAtTheTopOfTheTree() = assertTrue(PlaylistFolderTree.isIn(listOf(), listOf()))
    @Test fun aPlaylistInAFolderIsNotAtTheTopOfTheTree() = assertFalse(PlaylistFolderTree.isIn(listOf("Rock"), listOf()))
    @Test fun aPlaylistDeeperDownIsNotInTheFolderItPassesThrough() = assertFalse(PlaylistFolderTree.isIn(listOf("Rock", "90s"), listOf("Rock")))
    @Test fun aPlaylistDeeperDownIsUnderTheFolderItPassesThrough() = assertTrue(PlaylistFolderTree.isUnder(listOf("Rock", "90s"), listOf("Rock")))
    @Test fun aFolderHoldsWhatIsDirectlyInItAsWell() = assertTrue(PlaylistFolderTree.isUnder(listOf("Rock"), listOf("Rock")))
    @Test fun nothingInOneBranchIsUnderAnother() = assertFalse(PlaylistFolderTree.isUnder(listOf("Pop", "90s"), listOf("Rock")))
    @Test fun everythingIsUnderTheTopOfTheTree() = assertTrue(PlaylistFolderTree.isUnder(listOf("Rock", "90s"), listOf()))
    @Test fun caseIsNotWhatTellsTwoFoldersApart() = assertTrue(PlaylistFolderTree.isIn(listOf("rock"), listOf("Rock")))

    // ── what one level shows ──

    @Test fun theTopOfTheTreeShowsTheFoldersAtTheTopOfTheTree() {
        val rows = PlaylistFolderTree.foldersIn(listOf(listOf("Rock"), listOf("Pop"), listOf("Rock", "90s")), listOf())
        assertEquals(listOf("Pop", "Rock"), rows.map { it.name })
    }

    @Test fun twoFoldersOfOneNameInDifferentBranchesNeverMeet() {
        val chains = listOf(listOf("Rock", "90s"), listOf("Pop", "90s"))
        assertEquals(listOf("90s"), PlaylistFolderTree.foldersIn(chains, listOf("Rock")).map { it.name })
        assertEquals(listOf("90s"), PlaylistFolderTree.foldersIn(chains, listOf("Pop")).map { it.name })
    }

    @Test fun steppingIntoAFolderCarriesTheWholeWayDownToIt() {
        val rows = PlaylistFolderTree.foldersIn(listOf(listOf("Rock", "90s")), listOf("Rock"))
        assertEquals(1, rows.size)
        assertEquals(listOf("Rock", "90s"), rows[0].path)
    }

    @Test fun aFolderAppearsOnceHoweverManyPlaylistsAreInIt() {
        val rows = PlaylistFolderTree.foldersIn(listOf(listOf("Rock"), listOf("Rock"), listOf("Rock")), listOf())
        assertEquals(listOf("Rock"), rows.map { it.name })
    }

    @Test fun aFolderHoldingNothingIsNotAFolderThePhoneKnowsAbout() =
        assertTrue(PlaylistFolderTree.foldersIn(listOf(listOf(), listOf()), listOf()).isEmpty())

    // ── what a folder counts ──

    @Test fun aFolderCountsWhatIsDirectlyInIt() =
        assertEquals(2, PlaylistFolderTree.foldersIn(listOf(listOf("Rock"), listOf("Rock")), listOf())[0].playlists)

    @Test fun aFolderCountsTheRowsOpeningItShows() {
        val rows = PlaylistFolderTree.foldersIn(listOf(listOf("Rock"), listOf("Rock", "90s"), listOf("Rock", "90s", "Indie")), listOf())
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].playlists)
    }

    @Test fun aFolderHoldingOnlyFoldersDoesNotReadAsEmpty() =
        assertEquals(2, PlaylistFolderTree.foldersIn(listOf(listOf("Rock", "90s"), listOf("Rock", "80s")), listOf())[0].playlists)

    // ── where you end up standing ──

    @Test fun aFolderThatStillHoldsSomethingIsWhereYouStay() =
        assertEquals(listOf("Rock"), PlaylistFolderTree.stillOpen(listOf("Rock"), listOf(listOf("Rock", "90s"))))

    @Test fun aFolderWhoseLastPlaylistHasGonePutsYouInTheOneThatHeldIt() =
        assertEquals(listOf("Rock"), PlaylistFolderTree.stillOpen(listOf("Rock", "90s"), listOf(listOf("Rock"))))

    @Test fun aWholeBranchGoingPutsYouBackAtTheTopOfTheTree() =
        assertTrue(PlaylistFolderTree.stillOpen(listOf("Rock", "90s"), listOf(listOf("Pop"))).isEmpty())

    @Test fun anAccountWithNothingLeftInItPutsYouAtTheTopOfTheTree() =
        assertTrue(PlaylistFolderTree.stillOpen(listOf("Rock"), listOf()).isEmpty())

    @Test fun theTopOfTheTreeIsAlwaysSomewhereYouCanBe() = assertTrue(PlaylistFolderTree.stillOpen(listOf(), listOf()).isEmpty())

    // ── the browse ──

    private fun list(
        name: String,
        folder: List<String> = listOf(),
        tracks: List<CloudTrack> = listOf(),
        generated: Boolean = false,
        kind: String? = null,
        decade: Int = 0,
    ) = TrackList(
        id = (if (generated) "mood/" else "own/p-") + name,
        storeId = "p-$name",
        name = ListName.Text(name),
        tracks = tracks,
        missing = 0,
        folder = folder,
        generated = generated,
        kind = kind,
        plainMood = false,
        genre = null,
        decade = decade,
        blurb = null,
    )

    private fun browse(at: List<String>, vararg lists: TrackList) =
        PlaylistBrowse.of(at, lists.toList(), { (it.name as ListName.Text).text })

    private fun names(browse: PlaylistBrowse) = browse.rows.map {
        when (it) {
            is PlaylistRow.Folder -> it.folder.name
            is PlaylistRow.Playlist -> it.name
        }
    }

    @Test fun anAccountWithNoListsShowsNoRowsAtAll() = assertTrue(browse(listOf()).rows.isEmpty())

    @Test fun foldersComeAboveThePlaylistsBesideThem() {
        val made = browse(listOf(), list("Aardvark"), list("Zebra", listOf("Mixes")))
        assertEquals(listOf("Mixes", "Aardvark"), names(made))
        assertTrue(made.rows[0] is PlaylistRow.Folder)
    }

    @Test fun bothAreAlphabeticalWhateverOrderTheyArriveIn() =
        assertEquals(listOf("Alpha", "mid", "zoo"), names(browse(listOf(), list("zoo"), list("Alpha"), list("mid"))))

    @Test fun aFolderRowCountsTheRowsOpeningItShows() {
        val made = browse(listOf(), list("a", listOf("Mixes")), list("b", listOf("Mixes", "Late")), list("c", listOf("Mixes", "Late", "Deeper")))
        assertEquals(listOf("Mixes"), names(made))
        // "a", and the folder "Late": not the two playlists filed away beneath it.
        assertEquals(2, (made.rows[0] as PlaylistRow.Folder).folder.playlists)
    }

    // ── the computer's generated lists ──

    /** The tree the computer sends: its listening lists in Smart playlists, its moods in Moods, its decades in
     * Decades (`SmartPlaylistFace.FolderChain` on the desktop). */
    private val sent = arrayOf(
        list("On repeat", listOf("Smart playlists"), generated = true, kind = "OnRepeat"),
        list("Favourite tracks", listOf("Smart playlists"), generated = true, kind = "FavoriteTracks"),
        list("Calm", listOf("Moods"), generated = true, kind = "Calm"),
        list("Energetic", listOf("Moods"), generated = true, kind = "Energetic"),
        list("Slow Burn", listOf("Moods", "Calm"), generated = true, kind = "Calm"),
        list("1980s", listOf("Decades"), generated = true, kind = "Decade", decade = 1980),
        list("1990s", listOf("Decades"), generated = true, kind = "Decade", decade = 1990),
        list("Aardvark"),
        list("Road trip", listOf("Mixes")),
    )

    @Test fun theListeningListsStandAtTheTopOfTheTree() {
        val top = browse(listOf(), *sent)
        assertTrue("On repeat" in names(top))
        assertTrue("Favourite tracks" in names(top))
        assertTrue("Smart playlists" !in names(top))
    }

    @Test fun theDecadesAreNotAFolderAtTheTopOfTheTree() = assertTrue("Decades" !in names(browse(listOf(), *sent)))

    @Test fun theTopOfTheTreeIsMoodsThenTheReadersFoldersThenTheGeneratedListsThenTheReadersOwn() =
        assertEquals(listOf("Moods", "Mixes", "Favourite tracks", "On repeat", "Aardvark"), names(browse(listOf(), *sent)))

    @Test fun theDecadesAreTheFirstFolderInsideTheMoods() {
        val moods = browse(listOf("Moods"), *sent)
        assertEquals(listOf("Decades", "Calm", "Energetic"), names(moods))
        assertTrue(moods.rows[0] is PlaylistRow.Folder)
        assertEquals(listOf("Moods", "Decades"), (moods.rows[0] as PlaylistRow.Folder).folder.path)
    }

    @Test fun theDecadesFolderHoldsTheDecades() =
        assertEquals(listOf("1980s", "1990s"), names(browse(listOf("Moods", "Decades"), *sent)))

    @Test fun theMoodsFolderIsFoundByWhatIsInItAndNotByItsName() {
        // A computer set to French names its folders in French.
        val french = arrayOf(
            list("Calme", listOf("Ambiances"), generated = true, kind = "Calm"),
            list("1980s", listOf("Décennies"), generated = true, kind = "Decade", decade = 1980),
        )
        assertEquals(listOf("Ambiances"), names(browse(listOf(), *french)))
        assertEquals(listOf("Décennies", "Calme"), names(browse(listOf("Ambiances"), *french)))
    }

    @Test fun decadesWithNoMoodsToGoIntoStayWhereTheyWereSent() =
        assertEquals(listOf("Decades"), names(browse(listOf(), list("1980s", listOf("Decades"), generated = true, kind = "Decade", decade = 1980))))

    @Test fun theMoodsComeAboveAFolderOfTheReadersWhoseNameComesFirst() =
        assertEquals(listOf("Moods", "Aardvarks"), names(browse(listOf(), list("Calm", listOf("Moods"), generated = true, kind = "Calm"), list("a", listOf("Aardvarks")))))

    // ── what a folder's square shows ──

    private fun faces(browse: PlaylistBrowse) = browse.rows.filterIsInstance<PlaylistRow.Folder>().associate { it.folder.name to it.face }

    @Test fun theMoodsFolderWearsTheMoodsMarkAndTheReadersFolderIsPlain() =
        assertEquals(mapOf("Moods" to FolderFace.Moods, "Mixes" to FolderFace.Plain), faces(browse(listOf(), *sent)))

    @Test fun insideTheMoodsTheDecadesAndEachMoodWearTheirOwnMarks() =
        assertEquals(mapOf("Decades" to FolderFace.Decades, "Calm" to FolderFace.Mood("Calm")), faces(browse(listOf("Moods"), *sent)))

    @Test fun aFolderHoldingAListTheReaderMadeIsPlain() {
        // The reader filed a playlist of their own under the computer's Moods, in a folder of the mood's name.
        val mixed = arrayOf(list("Calm", listOf("Moods"), generated = true, kind = "Calm"), list("Mine", listOf("Moods", "Calm")))
        assertEquals(FolderFace.Plain, faces(browse(listOf(), *mixed))["Moods"])
        assertEquals(FolderFace.Plain, faces(browse(listOf("Moods"), *mixed))["Calm"])
    }

    @Test fun aStoredPlaylistInAFolderCalledSmartPlaylistsStaysInIt() =
        assertEquals(listOf("Smart playlists"), names(browse(listOf(), list("Mine", listOf("Smart playlists")))))

    @Test fun steppingIntoAFolderShowsWhatIsDirectlyInIt() {
        val made = browse(listOf("Mixes"), list("a", listOf("Mixes")), list("b", listOf("Mixes", "Late")), list("c"))
        assertEquals(listOf("Mixes"), made.at)
        assertEquals(listOf("Late", "a"), names(made))
    }

    @Test fun aFolderThatHasEmptiedPutsTheReaderBackWhereItWas() {
        val made = browse(listOf("Mixes", "Late"), list("a", listOf("Mixes")))
        assertEquals(listOf("Mixes"), made.at)
        assertEquals(listOf("a"), names(made))
    }

    @Test fun aTreeThatHasGoneEntirelyPutsTheReaderAtTheTop() {
        val made = browse(listOf("Mixes", "Late"))
        assertTrue(made.at.isEmpty())
        assertTrue(made.rows.isEmpty())
    }

    @Test fun whereYouAreComesBackWithWhatIsOnScreen() {
        val made = browse(listOf("MIXES"), list("a", listOf("Mixes")))
        // The reader's own spelling is not overwritten.
        assertEquals(listOf("MIXES"), made.at)
        assertEquals(listOf("a"), names(made))
    }

    @Test fun thereIsNowhereToGoBackToAtTheTopOfTheTree() {
        assertNull(browse(listOf()).back)
        assertTrue(browse(listOf()).path.isEmpty())
    }

    @Test fun oneStepInGoesBackToTheTopAndSaysNoPath() {
        val made = browse(listOf("Mixes"), list("a", listOf("Mixes")))
        assertEquals(listOf<String>(), made.back)
        assertTrue(made.path.isEmpty())
    }

    @Test fun twoStepsInSaysWhereTheFolderSits() {
        val made = browse(listOf("Mixes", "Late"), list("a", listOf("Mixes", "Late")))
        assertEquals(listOf("Mixes"), made.path)
        assertEquals(listOf("Mixes"), made.back)
    }

    @Test fun everyRowIsTellableFromEveryOther() {
        val rows = browse(listOf(), list("a"), list("b"), list("c", listOf("Mixes"))).rows
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
    }

    @Test fun aMoodsOwnListIsTheFirstRowInIt() {
        val at = listOf("Moods", "Chill")
        val made = browse(at, list("Slow Burn", at, generated = true), list("Adagio", at, generated = true), list("Chill", at, generated = true))
        assertEquals(listOf("Chill", "Adagio", "Slow Burn"), names(made))
    }

    @Test fun aMoodWithCutsIsAPlaceAndItsOwnListIsInThere() {
        val lists = arrayOf(list("Chill", listOf("Moods"), generated = true), list("Slow Burn", listOf("Moods", "Chill"), generated = true))
        // The computer put the mood in Moods and its cut under the mood's own row. The one row that cannot be
        // both moves into the place named after it, and is drawn first there.
        assertEquals(listOf("Chill"), names(browse(listOf("Moods"), *lists)))
        assertTrue(browse(listOf("Moods"), *lists).rows[0] is PlaylistRow.Folder)
        assertEquals(listOf("Chill", "Slow Burn"), names(browse(listOf("Moods", "Chill"), *lists)))
    }

    @Test fun aMoodWithNoPlaceNamedAfterItStaysWhereItWasPut() {
        val made = browse(listOf("Moods"), list("Sleep", listOf("Moods"), generated = true))
        assertEquals(listOf("Sleep"), names(made))
        assertTrue(made.rows[0] is PlaylistRow.Playlist)
    }

    @Test fun aStoredPlaylistDoesNotMoveIntoAFolderNamedAfterIt() {
        val made = browse(listOf("Mixes"), list("Late", listOf("Mixes")), list("a", listOf("Mixes", "Late")))
        assertEquals(listOf("Late", "Late"), names(made))
        assertTrue(made.rows[0] is PlaylistRow.Folder)
        assertTrue(made.rows[1] is PlaylistRow.Playlist)
    }

    @Test fun aStoredPlaylistNamedAfterItsFolderIsNotMovedToTheTop() =
        assertEquals(listOf("Ambient", "Mixes"), names(browse(listOf("Mixes"), list("Mixes", listOf("Mixes")), list("Ambient", listOf("Mixes")))))
}
