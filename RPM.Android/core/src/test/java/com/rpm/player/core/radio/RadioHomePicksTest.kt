package com.rpm.player.core.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioHomePicksTest {

    private fun station(uuid: String, tags: List<String> = emptyList(), folded: List<String> = emptyList()) =
        RadioStation(uuid = uuid, name = uuid.uppercase(), streamUrl = "https://$uuid.example/stream", tags = tags, alsoFiledAs = folded)

    @Test
    fun `the banner takes heard and local stations in turn, heard first`() {
        val heard = listOf(station("h1"), station("h2"), station("h3"), station("h4"))
        val local = listOf(station("l1"), station("l2"), station("l3"), station("l4"))
        val banner = RadioHomePicks.banner(heard, local)
        assertEquals(listOf("h1", "l1", "h2", "l2", "h3", "l3"), banner.map { it.station.uuid })
        assertEquals(StationWhy.Heard, banner[0].why)
        assertEquals(StationWhy.Local, banner[1].why)
    }

    @Test
    fun `the banner fills from one list when the other runs out, and lists a station once`() {
        val banner = RadioHomePicks.banner(listOf(station("a")), listOf(station("A"), station("b"), station("c")))
        assertEquals(listOf("a", "b", "c"), banner.map { it.station.uuid })
        assertEquals(StationWhy.Heard, banner[0].why)
    }

    @Test
    fun `a folded entry counts as the station it is folded into`() {
        val quick = RadioHomePicks.quick(
            pinned = listOf(station("main", folded = listOf("other"))),
            saved = listOf(station("other"), station("saved")),
            suggested = emptyList(),
        )
        assertEquals(listOf("main", "saved"), quick.map { it.station.uuid })
    }

    @Test
    fun `the quick selection puts pinned, then saved, then suggested, at most sixteen`() {
        val pinned = listOf(station("p1"), station("p2"))
        val saved = listOf(station("s1"), station("p1"))
        val suggested = (1..20).map { station("f$it") }
        val quick = RadioHomePicks.quick(pinned, saved, suggested)
        assertEquals(16, quick.size)
        assertEquals(listOf("p1", "p2", "s1", "f1"), quick.take(4).map { it.station.uuid })
        assertEquals(listOf(StationWhy.Pinned, StationWhy.Pinned, StationWhy.Saved, StationWhy.ForYou), quick.take(4).map { it.why })
    }

    @Test
    fun `taste counts genres, and the first stations break a tie`() {
        val stations = listOf(
            station("a", listOf("jazz")),
            station("b", listOf("rock")),
            station("c", listOf("rock", "classic rock")),
            station("d", listOf("jazz")),
            station("e", listOf("pop")),
        )
        assertEquals(listOf("Jazz", "Rock"), RadioHomePicks.taste(stations))
    }

    @Test
    fun `suggestions take one from each genre in turn and skip known stations`() {
        val jazz = listOf(station("known"), station("j1"), station("j2"))
        val rock = listOf(station("r1"), station("r2"), station("j1"))
        val suggested = RadioHomePicks.suggested(listOf(jazz, rock), known = listOf(station("known")), most = 4)
        assertEquals(listOf("r1", "j1", "r2", "j2"), suggested.map { it.uuid })
    }
}
