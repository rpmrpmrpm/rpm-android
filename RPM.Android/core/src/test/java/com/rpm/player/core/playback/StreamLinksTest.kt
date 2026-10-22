package com.rpm.player.core.playback

import com.rpm.player.core.cloud.Shelf
import com.rpm.player.core.cloud.StreamTicket
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamLinksTest {
    private val ref = TrackRef(Shelf.Own, "t1")
    private var now = 0L

    /** Answers each sign with the next ticket, and counts the signs. */
    private class Signer(vararg tickets: StreamTicket) {
        val left = ArrayDeque(tickets.toList())
        var asked = 0

        fun sign(ref: TrackRef, kilobits: Int): StreamTicket {
            asked++
            return left.removeFirst()
        }
    }

    private val sixHours = 6 * 3600L

    /** A stored object at the rate asked for is kept for four hours, as on the iPhone: the same file answers every
     * open, and after that it is signed again even though the signature would last longer. */
    @Test fun aStoredAddressAtTheAskedRateIsKeptForFourHours() {
        val signer = Signer(
            StreamTicket("https://s/a", kilobits = 320, live = false, expiresInSeconds = sixHours),
            StreamTicket("https://s/b", kilobits = 320, live = false, expiresInSeconds = sixHours),
        )
        val links = StreamLinks(signer::sign) { now }
        links.resolveBlocking(ref, 320)
        now += 3 * 3600_000L
        links.resolveBlocking(ref, 320)
        assertEquals(1, signer.asked)
        now += 3600_000L + 1
        assertEquals(StreamLink("https://s/b"), links.resolveBlocking(ref, 320))
    }

    /** A live encode is asked for again after a couple of minutes, so a replay gets the stored object once the store
     * has kept the rendition, instead of a second live encode. Within those minutes the same address answers. */
    @Test fun aLiveAddressIsAskedForAgainSoon() {
        val signer = Signer(
            StreamTicket("https://s/live", kilobits = 320, live = true, expiresInSeconds = sixHours),
            StreamTicket("https://s/stored", kilobits = 320, live = false, expiresInSeconds = sixHours),
        )
        val links = StreamLinks(signer::sign) { now }
        assertEquals(StreamLink("https://s/live", live = true), links.resolveBlocking(ref, 320))
        now += 60_000L
        assertEquals(StreamLink("https://s/live", live = true), links.resolveBlocking(ref, 320))
        now += 2 * 60_000L
        assertEquals(StreamLink("https://s/stored"), links.resolveBlocking(ref, 320))
        assertEquals(2, signer.asked)
    }

    /** A lower rate than the one asked for is a stand-in (the rate is not stored yet): asked for again soon too. */
    @Test fun aLowerRateFallbackIsAskedForAgainSoon() {
        val signer = Signer(
            StreamTicket("https://s/128", kilobits = 128, live = false, expiresInSeconds = sixHours),
            StreamTicket("https://s/320", kilobits = 320, live = false, expiresInSeconds = sixHours),
        )
        val links = StreamLinks(signer::sign) { now }
        links.resolveBlocking(ref, 320)
        now += 3 * 60_000L
        assertEquals(StreamLink("https://s/320"), links.resolveBlocking(ref, 320))
        assertEquals(2, signer.asked)
    }

    /** However long it is kept, an address is never opened with less than MIN_LEFT_MS of its signature left. */
    @Test fun anAddressNearItsExpiryIsSignedAgain() {
        val signer = Signer(
            StreamTicket("https://s/a", kilobits = 320, live = false, expiresInSeconds = 10 * 60L),
            StreamTicket("https://s/b", kilobits = 320, live = false, expiresInSeconds = sixHours),
        )
        val links = StreamLinks(signer::sign) { now }
        links.resolveBlocking(ref, 320)
        now += 6 * 60_000L
        assertEquals(StreamLink("https://s/b"), links.resolveBlocking(ref, 320))
    }
}
