package com.rpm.player.tv.ui

import com.rpm.player.core.library.ListName
import com.rpm.player.core.library.PlaylistBrowse
import com.rpm.player.core.library.PlaylistRow
import com.rpm.player.core.library.TrackList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderWalkTest {

    private fun list(name: String, folder: List<String> = listOf()) = TrackList(
        id = "own/p-$name",
        storeId = "p-$name",
        name = ListName.Text(name),
        tracks = listOf(),
        missing = 0,
        folder = folder,
        generated = false,
        kind = null,
        plainMood = false,
        genre = null,
        decade = 0,
        blurb = null,
    )

    private val lists = listOf(
        list("Loose"),
        list("Alpha", listOf("A")),
        list("Bravo", listOf("B")),
        list("Charlie", listOf("C")),
        list("Deep", listOf("C", "Inner")),
    )

    private fun browse(walk: FolderWalk) = PlaylistBrowse.of(walk.at, lists) { (it.name as ListName.Text).text }

    private fun title(row: PlaylistRow) = when (row) {
        is PlaylistRow.Folder -> row.folder.name
        is PlaylistRow.Playlist -> row.name
    }

    @Test
    fun `the screen opens at the top with the first card in focus`() {
        val walk = FolderWalk()
        val rows = browse(walk).rows
        assertEquals(listOf("A", "B", "C", "Loose"), rows.map(::title))
        assertEquals(0, walk.focusIndex(rows))
    }

    @Test
    fun `OK on a folder opens it with its first card in focus`() {
        val walk = FolderWalk().into(listOf("C"))
        val rows = browse(walk).rows
        assertEquals(listOf("Inner", "Charlie"), rows.map(::title))
        assertEquals(0, walk.focusIndex(rows))
    }

    @Test
    fun `Back goes up one folder and the folder just left takes the focus`() {
        val inner = FolderWalk().into(listOf("C")).into(listOf("C", "Inner"))
        val c = inner.up(browse(inner).at)!!
        assertEquals(listOf("C"), c.at)
        assertEquals(0, c.focusIndex(browse(c).rows))

        val top = c.up(browse(c).at)!!
        assertEquals(listOf<String>(), top.at)
        assertEquals(2, top.focusIndex(browse(top).rows))
    }

    @Test
    fun `Back at the top is not the screen's, so it leaves the screen`() {
        assertNull(FolderWalk().up(listOf()))
    }

    @Test
    fun `the folder come back from is found whatever its case`() {
        val walk = FolderWalk(at = listOf(), cameFrom = listOf("b"))
        assertEquals(1, walk.focusIndex(browse(walk).rows))
    }

    @Test
    fun `a folder come back from that has gone gives the focus to the first card`() {
        val walk = FolderWalk(at = listOf(), cameFrom = listOf("Gone"))
        assertEquals(0, walk.focusIndex(browse(walk).rows))
    }

    @Test
    fun `Back from a folder that emptied goes up from where the reader was put`() {
        // Asked for a folder that holds nothing now: the browse stands at the top, and Back has nowhere to go.
        val walk = FolderWalk().into(listOf("Empty"))
        val shown = browse(walk)
        assertEquals(listOf<String>(), shown.at)
        assertNull(walk.up(shown.at))
    }
}
