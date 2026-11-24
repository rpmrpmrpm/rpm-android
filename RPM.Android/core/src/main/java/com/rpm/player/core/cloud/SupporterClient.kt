package com.rpm.player.core.cloud

import com.rpm.player.core.auth.AuthClient
import com.rpm.player.core.net.Hosts
import com.rpm.player.core.net.WireJson
import com.rpm.player.core.net.await
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the accounts service says about the account after it took a store purchase: the supporter fields of
 * `/auth/me`. The server type is `SupporterStanding` in rpm.Api. */
@Serializable
data class SupporterAnswer(
    val grSupporter: Boolean = false,
    val grSupporterUntil: Long = 0,
    /** `apple` or `google` while that store pays the longest, else null. */
    val grSupporterStore: String? = null,
    val grUnlimitedStorage: Boolean = false,
    val grTester: Boolean = false,
)

/** How the accounts service answered a store purchase. */
sealed interface SupporterPost {
    /** The service read the purchase from Google, stored it and acknowledged it. */
    data class Confirmed(val answer: SupporterAnswer) : SupporterPost

    /** The subscription belongs to another rpm account. Nothing changed. */
    data object OtherAccount : SupporterPost

    /** Not a purchase of an rpm supporter subscription. Sending it again changes nothing. */
    data object Refused : SupporterPost

    /** The service could not be reached, could not store it, or the phone is not signed in. The purchase is sent again
     * on the next start: Google keeps it in the phone's purchases, and refunds it after three days if nobody
     * acknowledges it. */
    data object Unavailable : SupporterPost
}

/** Where the phone sends a Google Play purchase. [SupporterClient] is the only implementation; tests replace it. */
fun interface SupporterPosting {
    suspend fun postGoogle(purchaseToken: String): SupporterPost
}

/** `POST supporter/google` on the accounts service, with the account's token. */
class SupporterClient(private val http: OkHttpClient, private val auth: AuthClient) : SupporterPosting {
    override suspend fun postGoogle(purchaseToken: String): SupporterPost = try {
        val token = auth.accessToken()
        val request = Request.Builder()
            .url(Hosts.ACCOUNTS + "supporter/google")
            .apply { if (!token.isNullOrEmpty()) header("Authorization", "Bearer $token") }
            .post(WireJson.encodeToString(GoogleBody.serializer(), GoogleBody(purchaseToken)).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).await().use { response -> read(response.code, response.body.string()) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        SupporterPost.Unavailable
    }

    @Serializable
    private data class GoogleBody(val purchaseToken: String)

    @Serializable
    private data class Refusal(val error: String? = null)

    private companion object {
        /** The service's answer. A 409 for a deleted account (`noAccount`) is not the other-account case: it is left to
         * be sent again, like any answer the phone cannot act on. */
        fun read(code: Int, body: String): SupporterPost = try {
            when (code) {
                in 200..299 -> SupporterPost.Confirmed(WireJson.decodeFromString(SupporterAnswer.serializer(), body))
                409 -> if (WireJson.decodeFromString(Refusal.serializer(), body).error == "otherAccount") SupporterPost.OtherAccount else SupporterPost.Unavailable
                400 -> SupporterPost.Refused
                else -> SupporterPost.Unavailable
            }
        } catch (e: SerializationException) {
            SupporterPost.Unavailable
        } catch (e: IllegalArgumentException) {
            SupporterPost.Unavailable
        }
    }
}

/** One purchase from Play Billing, as much of it as the phone needs. */
data class HeldPurchase(val purchaseToken: String, val purchased: Boolean, val products: List<String>)

/** How a purchase made in the subscription sheet ended, for the line the sheet shows. */
enum class PurchaseOutcome {
    /** The accounts service checked the purchase with Google and stored it. The sheet closes. */
    CONFIRMED,

    /** The payment is not complete yet. */
    PENDING,

    /** The subscription on this Google account belongs to another rpm account. */
    OTHER_ACCOUNT,

    /** Paid, but the service has not confirmed it yet. It is sent again on the next start. */
    FAILED,
}

/** Which purchases are sent to the accounts service. */
object SupporterPurchases {
    /** The Google Play subscription that makes an account a supporter. rpm.Api accepts this and no other. */
    const val PRODUCT = "rpm_supporter"

    /**
     * Sends each bought supporter purchase and says how each was answered. A pending purchase is not paid yet and is
     * sent when Play reports it bought.
     */
    suspend fun send(purchases: List<HeldPurchase>, poster: SupporterPosting): List<SupporterPost> =
        purchases.filter { it.purchased && PRODUCT in it.products }.map { poster.postGoogle(it.purchaseToken) }

    /**
     * How the purchases sent from the sheet ended: confirmed if the service confirmed any, then another account's,
     * then pending when nothing could be sent because the payment is not complete.
     *
     * @param pending whether Play reported a supporter purchase whose payment is not complete.
     */
    fun outcomeOf(said: List<SupporterPost>, pending: Boolean): PurchaseOutcome = when {
        said.any { it is SupporterPost.Confirmed } -> PurchaseOutcome.CONFIRMED
        said.any { it is SupporterPost.OtherAccount } -> PurchaseOutcome.OTHER_ACCOUNT
        said.isEmpty() && pending -> PurchaseOutcome.PENDING
        else -> PurchaseOutcome.FAILED
    }
}
