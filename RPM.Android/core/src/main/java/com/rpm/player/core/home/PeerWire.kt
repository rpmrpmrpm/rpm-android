package com.rpm.player.core.home

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The encodings of the sharing protocol. They must match `RPM.Core/Sharing` on the desktop
 * (`SharingProtocol`, `PeerIdentity`, `Base32`). Every binary value on the wire is base64url without
 * padding.
 */
object PeerWire {
    const val VERSION = 4

    /** The pairing exchange this TV speaks (the desktop's `SharingProtocol.Pairing`), kept apart from [VERSION]. In
     * version 2 the six digits never cross the network (see [PairingCode]); a request without it is answered 426. */
    const val PAIRING = 2
    const val SERVICE_TYPE = "_rpm._tcp."

    /** The service type a NAS announces, for itself and for each library on it. */
    const val NAS_SERVICE_TYPE = "_rpm-nas._tcp."
    const val SESSION_PURPOSE = "rpm-share-session"

    /** Where a computer serves a track's bytes. A ticket is made over this route and the track's id. */
    const val TRACK_ROUTE = "/rpm/track/"

    fun toBase64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** The bytes, or null for text that is not base64url. */
    fun fromBase64Url(text: String?): ByteArray? = try {
        if (text.isNullOrBlank()) null else Base64.getUrlDecoder().decode(text.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    /** A device id: base32 of the first 16 bytes of SHA-256 of the public key (SubjectPublicKeyInfo
     * DER), 26 characters, upper case. */
    fun idFor(publicKey: ByteArray): String = base32(sha256(publicKey).copyOf(16))

    /** The text a session signature covers: the server's id exactly as it spells it, the challenge, and the
     * account this device is signed into (the user id, or empty). The account is said only here, inside the
     * signature, to a computer whose key this device has pinned. */
    fun sessionText(serverDeviceId: String, challenge: String, account: String): ByteArray =
        "$SESSION_PURPOSE\n$serverDeviceId\n$challenge\n$account".toByteArray(Charsets.UTF_8)

    /** The public name of a session token in a media URL: the first 16 bytes of SHA-256, base64url. The
     * desktop's `PeerSessions.Handle`. */
    fun mediaHandle(token: String): String =
        toBase64Url(sha256("rpm-media-handle\n$token".toByteArray(Charsets.UTF_8)).copyOf(16))

    /** What a media URL carries in place of the token: the first 16 bytes of HMAC-SHA256, keyed with the
     * token, over the route and the unescaped id. It opens that one route and id, and nothing else. The
     * desktop's `PeerSessions.Ticket`. */
    fun mediaTicket(token: String, route: String, id: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return toBase64Url(mac.doFinal("rpm-media\n$route$id".toByteArray(Charsets.UTF_8)).copyOf(16))
    }

    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /** Crockford-style base32 without padding, most significant bit first, the same as `Base32.Encode` on
     * the desktop. */
    fun base32(data: ByteArray): String {
        val text = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                text.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) text.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return text.toString()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * An ECDSA signature as the computer checks it: r and s, 32 bytes each (IEEE P1363), from the DER
     * `SEQUENCE { INTEGER r, INTEGER s }` that Android's Keystore gives. A DER signature is refused.
     */
    fun derToP1363(der: ByteArray, size: Int = 32): ByteArray {
        var at = 0
        require(der[at++].toInt() == 0x30) { "not a DER sequence" }
        at += lengthBytes(der, at)
        val out = ByteArray(size * 2)
        for (part in 0..1) {
            require(der[at++].toInt() == 0x02) { "not a DER integer" }
            val length = readLength(der, at)
            at += lengthBytes(der, at)
            var start = at
            var count = length
            // A leading zero only keeps the integer positive; it is not part of the value.
            while (count > size && der[start].toInt() == 0) {
                start++
                count--
            }
            require(count <= size) { "integer too long" }
            System.arraycopy(der, start, out, part * size + (size - count), count)
            at += length
        }
        return out
    }

    private fun readLength(der: ByteArray, at: Int): Int {
        val first = der[at].toInt() and 0xFF
        if (first < 0x80) return first
        var length = 0
        for (i in 1..(first and 0x7F)) length = (length shl 8) or (der[at + i].toInt() and 0xFF)
        return length
    }

    private fun lengthBytes(der: ByteArray, at: Int): Int {
        val first = der[at].toInt() and 0xFF
        return if (first < 0x80) 1 else 1 + (first and 0x7F)
    }
}

// The wire types, with the server's camelCase names.

@Serializable
data class PairRequest(val version: Int, val publicKey: String, val name: String, val face: String, val nonce: String, val pairing: Int)

@Serializable
data class PairStatus(
    val state: String = "",
    val rev: Long = 0,
    val triesLeft: Int = 0,
    val expiresIn: Int = 0,
    /** The current round, from 1; a wrong code starts the next one. */
    val round: Int = 0,
    /** The computer's commitment to its nonce for the round (base64url); empty from a computer before pairing 2. */
    val commit: String = "",
    /** Whether the computer has the TV's nonce for the round, and so shows the digits. */
    val shown: Boolean = false,
    /** The computer's nonce for the round (base64url), once the TV has committed to a code. */
    val reveal: String = "",
)

@Serializable
data class PairOffer(
    val deviceId: String = "",
    val name: String = "",
    val face: String = "",
    val publicKey: String = "",
    val id: String = "",
    val status: PairStatus = PairStatus(),
)

@Serializable
data class PairNonce(val id: String, val round: Int, val nonce: String)

@Serializable
data class PairCommit(val id: String, val round: Int, val commit: String)

@Serializable
data class PairOpen(val id: String, val round: Int, val opening: String)

@Serializable
data class PairId(val id: String)

@Serializable
data class SessionChallenge(val challenge: String = "", val expiresIn: Int = 0)

@Serializable
data class SessionRedeem(val publicKey: String, val challenge: String, val signature: String, val account: String)

@Serializable
data class SessionGrant(val token: String = "", val expiresIn: Int = 0, val kind: Int = 0)

@Serializable
data class PeerHello(val version: Int = 0, val deviceId: String = "", val name: String = "", val publicKey: String = "", val trackCount: Int = 0)

/** One track in a computer's catalogue (`PeerTrack`); only the fields the TV reads. */
@Serializable
data class PeerTrack(
    val rpmId: String,
    val path: String = "",
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val track: Int = 0,
    val disc: Int = 0,
    val year: Int = 0,
    val seconds: Double = 0.0,
    val size: Long = 0,
    /** .NET UTC ticks. */
    val added: Long = 0,
    val advisory: Int = 0,
    val codec: String? = null,
    val cloudOnly: Boolean = false,
    val inCloud: Boolean = false,
)

/** A NAS that also serves a computer's library, as that computer's catalogue names it. */
@Serializable
data class PeerHost(
    val deviceId: String = "",
    /** SubjectPublicKeyInfo DER, base64url. The NAS is used only with this key. */
    val publicKey: String = "",
    val name: String = "",
    val libraryId: String = "",
)

/** A computer's catalogue. [hosts] is empty from an older build and for a library that is not on a NAS. */
@Serializable
data class PeerCatalog(
    val version: String = "",
    val name: String = "",
    val tracks: List<PeerTrack> = emptyList(),
    val hosts: List<PeerHost> = emptyList(),
)
