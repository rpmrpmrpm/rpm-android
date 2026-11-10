package com.rpm.player.core.home

import com.rpm.player.core.net.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases as the Apple TV's NasHostsTests: the two apps must apply the same rules. */
class NasHostsTest {

    private val nasKey = byteArrayOf(0x30, 0x59, 1, 2, 3)
    private val nasId = PeerWire.idFor(nasKey)
    private val library = "0123456789abcdef0123456789abcdef"
    private val owner = PairedComputer("OWNER", "K", "Mac")

    private fun host(key: ByteArray = nasKey, library: String = this.library) =
        PeerHost(nasId, PeerWire.toBase64Url(key), "DiskStation", library)

    private val seen = mapOf(nasId.uppercase() to FoundComputer(nasId, "Chivan", "192.168.1.20", 4000, nasKey))
    private val ids = mapOf(nasId.uppercase() to library)

    @Test
    fun `hosts decode from a catalogue and are empty when missing`() {
        val with = """{"version":"v","tracks":[],"hosts":[{"deviceId":"N","publicKey":"K","name":"DS","libraryId":"L"}]}"""
        assertEquals("L", WireJson.decodeFromString(PeerCatalog.serializer(), with).hosts.single().libraryId)
        assertTrue(WireJson.decodeFromString(PeerCatalog.serializer(), """{"version":"v","tracks":[]}""").hosts.isEmpty())
    }

    @Test
    fun `hosts from a paired computer are trusted`() {
        val trusted = Computers.trustedNas(mapOf("OWNER" to listOf(host())), listOf(owner), seen, ids)
        assertEquals(4000, trusted[nasId.uppercase()]?.port)
    }

    @Test
    fun `hosts from an unknown source are ignored`() {
        assertTrue(Computers.trustedNas(mapOf("STRANGER" to listOf(host())), listOf(owner), seen, ids).isEmpty())
    }

    @Test
    fun `empty hosts withdraw the NAS`() {
        assertTrue(Computers.trustedNas(mapOf("OWNER" to emptyList()), listOf(owner), seen, ids).isEmpty())
    }

    @Test
    fun `a NAS with another key or library is not trusted`() {
        assertTrue(Computers.trustedNas(mapOf("OWNER" to listOf(host(key = byteArrayOf(9, 9)))), listOf(owner), seen, ids).isEmpty())
        assertTrue(Computers.trustedNas(mapOf("OWNER" to listOf(host(library = "f".repeat(32)))), listOf(owner), seen, ids).isEmpty())
    }

    @Test
    fun `a NAS is preferred for a track on both`() {
        val computer = HeldCatalog("AAA", "v", emptyMap(), fromNas = false)
        val nas = HeldCatalog("ZZZ", "v", emptyMap(), fromNas = true)
        assertEquals(listOf("ZZZ", "AAA"), HeldCatalog.nasFirst(listOf(computer, nas)).map { it.computer })
    }

    @Test
    fun `merge keeps one row per track and takes it from the NAS`() {
        val fromComputer = PeerTrack(rpmId = "1", path = "A/1.flac", title = "From the computer", codec = "Flac Audio")
        val fromNas = PeerTrack(rpmId = "1", path = "A/1.flac", title = "From the NAS", codec = "Flac Audio")
        val merged = HomeMerge.merge(
            emptyList(),
            listOf(HeldCatalog("AAA", "v", mapOf("1" to fromComputer), fromNas = false), HeldCatalog("ZZZ", "v", mapOf("1" to fromNas), fromNas = true)),
        )
        assertEquals(1, merged.size)
        assertEquals("From the NAS", merged[0].title)
    }
}
