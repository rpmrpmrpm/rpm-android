package com.rpm.player.core.library

import android.util.Log
import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.cloud.CloudClient
import com.rpm.player.core.cloud.CloudQuota
import com.rpm.player.core.cloud.PublicProfile
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.escapeSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How an account shows itself (matches the iOS app's `PeerFace`): the name it goes by (null when it has none), the
 * address of its picture (null when it has none), and whether it is a supporter.
 */
data class Face(val name: String?, val picture: String?, val supporter: Boolean) {
    companion object {
        /** [profile] as a face; [supporter] is the profile's own for anybody else, `cloud/quota`'s for the person. */
        fun of(profile: PublicProfile, supporter: Boolean) = Face(
            name = profile.displayName?.trim()?.takeIf { it.isNotEmpty() },
            picture = if (profile.hasAvatar && profile.userId.isNotBlank()) avatarUrl(profile.userId, profile.avatarUpdatedAt) else null,
            supporter = supporter,
        )

        /** `profiles/{id}/avatar`, stamped with when it was last changed, so a new picture is a new address and an
         * old one can be kept for good (ProfileController.Avatar). Open to any caller with the app key. */
        fun avatarUrl(userId: String, stamp: Long): String =
            Hosts.API + "profiles/" + escapeSegment(userId) + "/avatar" + (if (stamp > 0) "?v=$stamp" else "")
    }
}

/**
 * The signed-in account's own face: its profile (`profile`, which makes one on the first ask) and whether it wears
 * the supporter's mark ([CloudQuota.wearsMark]: a paying supporter, not a closed beta tester), read when the session
 * starts. Null until both are read, and
 * when signed out. A read that fails leaves it null; the next session asks again. An answer is published only
 * if the account that asked is still the one signed in.
 */
class OwnFace(private val cloud: CloudClient, private val auth: AuthClient, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<Face?>(null)
    val face: StateFlow<Face?> = current.asStateFlow()

    private val held = MutableStateFlow<CloudQuota?>(null)

    /**
     * The account's whole `cloud/quota` answer, read with the face: the phone's profile shows the storage it uses, and
     * the supporter features (rates, karaoke, the gold ground) follow [CloudQuota.supporter]. Null until read, after a
     * failed read, and when signed out.
     * Null is "not known", never "free".
     */
    val quota: StateFlow<CloudQuota?> = held.asStateFlow()

    init {
        scope.launch {
            auth.session.map { it?.userId }.distinctUntilChanged().collectLatest { user ->
                current.value = null
                held.value = null
                if (user != null) read(user)
            }
        }
    }

    /** Asks again if the last read failed, as the iPhone does when the network comes back or the profile is shown. */
    fun readAgainIfMissing() {
        val user = auth.session.value?.userId ?: return
        if (current.value == null || held.value == null) scope.launch { read(user) }
    }

    private suspend fun read(user: String) {
        val (face, quota) = try {
            withContext(Dispatchers.IO) {
                val profile = async { cloud.ownProfile() }
                val quota = async { cloud.quota() }
                val read = quota.await()
                Face.of(profile.await(), read.wearsMark) to read
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "own profile not read: $e")
            return
        }
        if (auth.session.value?.userId == user) {
            current.value = face
            held.value = quota
        }
    }

    private companion object {
        const val TAG = "rpm.share"
    }
}
