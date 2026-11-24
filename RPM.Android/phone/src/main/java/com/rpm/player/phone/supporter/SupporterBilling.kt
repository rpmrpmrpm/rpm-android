package com.rpm.player.phone.supporter

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.rpm.player.core.cloud.HeldPurchase
import com.rpm.player.core.cloud.PurchaseOutcome
import com.rpm.player.core.cloud.SupporterAnswer
import com.rpm.player.core.cloud.SupporterPost
import com.rpm.player.core.cloud.SupporterPosting
import com.rpm.player.core.cloud.SupporterPurchases
import com.rpm.player.core.diag.Diag
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One base plan of the supporter subscription, with the price as Google Play formats it for this person. */
class PlanOffer internal constructor(
    /** `week`, `month` or `year`. */
    val basePlanId: String,
    val formattedPrice: String,
    internal val offerToken: String,
    internal val details: ProductDetails,
)

/**
 * The Google Play subscription and the accounts service: the iPhone's `SupporterStore`.
 *
 * The service, not the phone, decides who is a supporter: the phone sends the purchase token, and the service reads
 * the purchase from Google, stores it and acknowledges it. The phone never acknowledges. A purchase that did not reach
 * the service stays unacknowledged in Play, is sent again by [sync] on the next start, and is refunded by Google after
 * three days if it never arrives.
 */
class SupporterBilling(
    context: Context,
    private val poster: SupporterPosting,
    private val scope: CoroutineScope,
    /** Called when the service confirmed a purchase, so the app reads the account again. `bought` is true for a
     * purchase made in the sheet just now. */
    private val onConfirmed: suspend (answer: SupporterAnswer, bought: Boolean) -> Unit,
) : PurchasesUpdatedListener {
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val outcomes = MutableStateFlow<PurchaseOutcome?>(null)

    /** How the last purchase made in the sheet ended. Null while nothing is to be said. */
    val outcome: StateFlow<PurchaseOutcome?> = outcomes.asStateFlow()

    /** Clears [outcome], when the sheet opens. */
    fun forgetOutcome() {
        outcomes.value = null
    }

    private val connecting = Mutex()

    /** Connects to Google Play once. False when Play is not on this device or does not answer. */
    private suspend fun ready(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        suspendCancellableCoroutine<Boolean> { waiting ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (waiting.isActive) waiting.resume(result.responseCode == BillingResponseCode.OK)
                }

                override fun onBillingServiceDisconnected() {
                    if (waiting.isActive) waiting.resume(false)
                }
            })
        }
    }

    /** The week, month and year plans with Play's prices, in that order, or null when Play could not be asked. */
    suspend fun plans(): List<PlanOffer>? {
        if (!ready()) return null
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(SupporterPurchases.PRODUCT)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build(),
            ))
            .build()
        val details = suspendCancellableCoroutine<ProductDetails?> { waiting ->
            client.queryProductDetailsAsync(params) { result, found ->
                if (waiting.isActive) waiting.resume(if (result.responseCode == BillingResponseCode.OK) found.productDetailsList.firstOrNull() else null)
            }
        } ?: return null

        // The base plan itself, not an offer on it: an offer has an id.
        return PLANS.mapNotNull { plan ->
            details.subscriptionOfferDetails?.firstOrNull { it.basePlanId == plan && it.offerId == null }?.let { offer ->
                val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@let null
                PlanOffer(plan, price, offer.offerToken, details)
            }
        }
    }

    /** Opens Play's purchase screen for [plan], with the account's id on the purchase so the service knows whose
     * support it is. */
    fun buy(activity: Activity, plan: PlanOffer, accountId: String) {
        outcomes.value = null
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(plan.details)
                    .setOfferToken(plan.offerToken)
                    .build(),
            ))
            .setObfuscatedAccountId(accountId)
            .build()
        val started = client.launchBillingFlow(activity, params)
        if (started.responseCode != BillingResponseCode.OK) {
            Diag.w(TAG, "purchase screen not opened: ${started.responseCode} ${started.debugMessage}")
            outcomes.value = PurchaseOutcome.FAILED
        }
    }

    /** Sends every bought supporter purchase that this Google account holds: at start, after sign-in and on return to
     * the foreground. */
    fun sync() {
        scope.launch { send(held(), bought = false) }
    }

    /** The supporter purchases this Google account holds, or none when Play could not be asked. */
    private suspend fun held(): List<Purchase> {
        if (!ready()) return emptyList()
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        return suspendCancellableCoroutine { waiting ->
            client.queryPurchasesAsync(params) { result, purchases ->
                if (waiting.isActive) waiting.resume(if (result.responseCode == BillingResponseCode.OK) purchases else emptyList())
            }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> scope.launch { outcomes.value = outcomeOf(purchases.orEmpty()) }
            BillingResponseCode.USER_CANCELED -> Unit
            // Bought before, perhaps on another device or for another rpm account: send what Play holds and say how
            // it ended, so a subscription that belongs to another account is told in the sheet.
            BillingResponseCode.ITEM_ALREADY_OWNED -> scope.launch { outcomes.value = outcomeOf(held()) }
            else -> {
                Diag.w(TAG, "purchase ended: ${result.responseCode} ${result.debugMessage}")
                outcomes.value = PurchaseOutcome.FAILED
            }
        }
    }

    /** Sends the purchases and says how a purchase made in the sheet ended. */
    private suspend fun outcomeOf(purchases: List<Purchase>): PurchaseOutcome =
        SupporterPurchases.outcomeOf(
            send(purchases, bought = true),
            pending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING && SupporterPurchases.PRODUCT in it.products },
        )

    private suspend fun send(purchases: List<Purchase>, bought: Boolean): List<SupporterPost> {
        val held = purchases.map { HeldPurchase(it.purchaseToken, it.purchaseState == Purchase.PurchaseState.PURCHASED, it.products) }
        val said = SupporterPurchases.send(held, poster)
        said.filterIsInstance<SupporterPost.Confirmed>().forEach { onConfirmed(it.answer, bought) }
        return said
    }

    private companion object {
        const val TAG = "rpm.supporter"

        /** The base plans of `rpm_supporter`, in the order the sheet lists them. rpm.Api accepts these and no others. */
        val PLANS = listOf("week", "month", "year")
    }
}
