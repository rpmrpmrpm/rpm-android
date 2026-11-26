package com.rpm.player.core.radio

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The admin's edits to how a station is shown, as the rpm API's rules carry them (`StationEditsTests` in
 * RPM.Core.Tests has the same cases for the desktop). */
class StationEditsTest {

    private fun station(
        uuid: String,
        name: String,
        tags: List<String> = emptyList(),
        country: String = "NL",
        clicks: Int = 0,
    ) = RadioStation(uuid = uuid, name = name, streamUrl = "https://$uuid.example/stream", tags = tags, countryCode = country, clickCount = clicks)

    private fun rules(vararg edits: Pair<String, StationEdit>) = StationRules(emptySet(), "", edits.toMap(), StationCards.NONE)

    @Test
    fun `a folded station finds the edit of any of its entries, its own first`() {
        val folded = station("a", "NPO Radio 2").copy(alsoFiledAs = listOf("b"))
        assertEquals("From b", rules("b" to StationEdit("From b", null, null)).editOf(folded)?.name)
        assertEquals(
            "From a",
            rules("b" to StationEdit("From b", null, null), "a" to StationEdit("From a", null, null)).editOf(folded)?.name,
        )
    }

    @Test
    fun `shown lays the edit on and keeps the directory's name and tags`() {
        val drawn = rules("a" to StationEdit("Radio 2", listOf("news"), "A line.")).shown(listOf(station("a", "NPO Radio 2 - Hits", tags = listOf("pop")))).single()
        assertEquals("Radio 2", drawn.display)
        assertEquals("NPO Radio 2 - Hits", drawn.name)
        assertEquals(listOf("pop"), drawn.tags)
        assertEquals(listOf("news"), drawn.shownTags)
    }

    @Test
    fun `the admin's ads mark wins over the rule`() {
        // Tagged commercial, so the rule marks it; the admin says it carries none.
        val marked = station("a", "Radio X", tags = listOf("commercial"))
        assertTrue(marked.carriesAds)
        assertTrue(!marked.copy(edit = StationEdit(null, null, null, ads = false)).carriesAds)

        // Says nothing, so the rule does not mark it; the admin says it carries ads.
        val quiet = station("b", "Radio Y")
        assertTrue(!quiet.carriesAds)
        val told = quiet.copy(edit = StationEdit(null, null, null, ads = true))
        assertTrue(told.carriesAds)
        assertTrue(StationSieve(null, null, AdsWanted.With).keeps(told))
    }

    @Test
    fun `an edit of only the ads is kept`() {
        assertEquals(true, StationEdit.of(null, null, null, ads = true)?.ads)
    }

    @Test
    fun `a station with no edit is handed back as it was`() {
        val plain = station("a", "X")
        assertSame(plain, StationRules.NONE.edited(plain))
    }

    @Test
    fun `blank values are the station's own and an empty edit is none`() {
        val edit = StationEdit.of(" ", listOf(" News", "news", "Talk"), "")
        assertNull(edit?.name)
        assertEquals(listOf("news", "talk"), edit?.tags)
        assertNull(edit?.description)
        assertNull(StationEdit.of("", listOf(" "), null))
    }

    @Test
    fun `the genre filter reads the admin's tags`() {
        val drawn = rules("a" to StationEdit(null, listOf("news"), null)).edited(station("a", "Pop Station", tags = listOf("pop")))
        assertTrue(StationSieve.OFF.copy(genre = "News").keeps(drawn))
        assertEquals(false, StationSieve.OFF.copy(genre = "Pop").keeps(drawn))
    }

    @Test
    fun `a genre takes in the stations retagged into it`() {
        val r = rules("a" to StationEdit(null, listOf("news"), null), "b" to StationEdit("Only a title", null, null))
        assertEquals(listOf("a"), r.retaggedInto("News"))
        assertEquals(emptyList<String>(), r.retaggedInto("Jazz"))
    }

    @Test
    fun `an edited title counts as that title when two stations would print the same`() {
        val r = rules("a" to StationEdit("Concertzender", null, null))
        val drawn = r.shown(listOf(station("a", "Something Else"), station("b", "Concertzender - De Gehoorde Stilte")))
        assertEquals(listOf("Concertzender", "Concertzender - De Gehoorde Stilte"), drawn.map { it.display })
    }

    @Test
    fun `the tuner's genre list reads the admin's tags`() {
        val settings = TunerSettings(text = "", country = "NL", sieve = StationSieve.OFF.copy(genre = "News"), order = StationOrder.Listed, favoritesOnly = false)
        val list = Tuner.listOf(
            settings,
            TunerAnswer.Listed(listOf(station("a", "Was Pop", tags = listOf("pop")), station("b", "Real News", tags = listOf("news")))),
            emptyList(),
            rules("a" to StationEdit(null, listOf("news"), null), "b" to StationEdit(null, listOf("pop"), null)),
        )
        assertEquals(listOf("a"), list.stations.map { it.uuid })
    }

    @Test
    fun `a retagged station joins a genre answer in play order, where the question lets it through`() {
        val answer = listOf(station("x", "X", clicks = 900), station("y", "Y", clicks = 100))
        val retagged = listOf(station("k", "Kiss FM", clicks = 500), station("d", "Kiss FM", country = "DE", clicks = 800))
        val merged = RadioResults.withRetagged(answer, retagged, RadioQuery(name = "kiss", tag = "news", countryCode = "nl", limit = 120))
        assertEquals(listOf("x", "k", "y"), merged.map { it.uuid })
        assertSame(answer, RadioResults.withRetagged(answer, listOf(station("x", "X")), RadioQuery(null, null, null, 120)))
    }

    @Test
    fun `the banner line is the admin's description, before any site is read`() = runTest {
        val edited = station("a", "X").copy(edit = StationEdit(null, null, "Dutch pop radio"))
        assertEquals("Dutch pop radio", StationBlurbs(OkHttpClient()).of(edited))
    }
}
