package com.rpm.player.core.home

import com.rpm.player.core.cloud.CloudTrack
import com.rpm.player.core.playback.TrackSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMergeTest {

    private fun peer(id: String, path: String = "A/B/$id.flac", codec: String? = "Flac Audio") =
        PeerTrack(rpmId = id, path = path, title = "T$id", codec = codec, added = 638_640_000_000_000_000L)

    @Test
    fun `a track in the cloud and on a computer is one track, the cloud's row`() {
        val cloud = listOf(CloudTrack(rpmId = "1", title = "Cloud title"))
        val merged = HomeMerge.merge(cloud, listOf(HeldCatalog("PC", "v", mapOf("1" to peer("1"), "2" to peer("2")), fromNas = false)))
        assertEquals(listOf("1", "2"), merged.map { it.rpmId })
        assertSame(cloud[0], merged[0])
        assertEquals("T2", merged[1].title)
    }

    @Test
    fun `a track on two computers is taken once`() {
        val merged = HomeMerge.merge(
            emptyList(),
            listOf(HeldCatalog("A", "v", mapOf("9" to peer("9")), fromNas = false), HeldCatalog("B", "v", mapOf("9" to peer("9")), fromNas = false)),
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun `no catalogs leaves the cloud list as it is`() {
        val cloud = listOf(CloudTrack(rpmId = "1"))
        assertSame(cloud, HomeMerge.merge(cloud, emptyList()))
    }

    @Test
    fun `ticks become unix seconds`() {
        // 1 January 2025, 00:00 UTC
        assertEquals(1_735_689_600L, HomeMerge.unixSeconds(638_712_864_000_000_000L))
        assertEquals(0L, HomeMerge.unixSeconds(0))
    }

    @Test
    fun `ALAC, AC-3 and E-AC-3 play from the computer only with the extension, unknown containers never`() {
        assertTrue(TrackSources.playsHere(peer("1"), extension = false))
        assertTrue(TrackSources.playsHere(peer("2", "x.m4a", "MPEG-4 Audio (mp4a)"), extension = false))
        assertFalse(TrackSources.playsHere(peer("3", "x.m4a", "MPEG-4 Audio (alac)"), extension = false))
        assertFalse(TrackSources.playsHere(peer("4", "x.m4a", "MPEG-4 Audio (ec-3)"), extension = false))
        assertTrue(TrackSources.playsHere(peer("3", "x.m4a", "MPEG-4 Audio (alac)"), extension = true))
        assertTrue(TrackSources.playsHere(peer("4", "x.m4a", "MPEG-4 Audio (ec-3)"), extension = true))
        assertFalse(TrackSources.playsHere(peer("5", "x.wma", "Windows Media Audio"), extension = true))
        assertFalse(TrackSources.playsHere(peer("6", "x.ape", null), extension = true))
    }
}
