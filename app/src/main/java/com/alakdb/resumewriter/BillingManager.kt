package com.alakdb.resumewriter

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(
    private val context: Context,
    private val creditManager: CreditManager
) {

    companion object {
        private const val TAG = "BillingManager"

        private const val PRODUCT_3_CV = "cv_package_3"
        private const val PRODUCT_8_CV = "cv_package_8"
    }

    private lateinit var billingClient: BillingClient
    private var productDetails: List<ProductDetails> = emptyList()
    private var isBillingReady = false

    private var purchaseCallback: ((Boolean, String) -> Unit)? = null

    private val purchasesUpdatedListener =
        PurchasesUpdatedListener { billingResult, purchases ->

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases?.forEach { handlePurchase(it) }
                return@PurchasesUpdatedListener
            }

            if (billingResult.responseCode ==
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
            ) {
                Log.w(TAG, "Item already owned – querying existing purchases")
                queryPurchases()
                return@PurchasesUpdatedListener
            }

            if (billingResult.responseCode ==
                BillingClient.BillingResponseCode.USER_CANCELED
            ) {
                purchaseCallback?.invoke(false, "Purchase canceled")
                return@PurchasesUpdatedListener
            }

            purchaseCallback?.invoke(
                false,
                "Billing error: ${billingResult.debugMessage}"
            )
        }

    fun initializeBilling(onComplete: (Boolean, String) -> Unit) {
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    isBillingReady = true
                    loadProducts()
                    queryPurchases()
                    onComplete(true, "Billing ready")
                } else {
                    onComplete(false, result.debugMessage)
                }
            }

            override fun onBillingServiceDisconnected() {
                isBillingReady = false
            }
        })
    }

    private fun loadProducts() {
        val products = listOf(
            PRODUCT_3_CV,
            PRODUCT_8_CV
        ).map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder()
                .setProductList(products)
                .build()
        ) { _, list ->
            productDetails = list
        }
    }

    fun purchaseProduct(
        activity: Activity,
        productId: String,
        callback: (Boolean, String) -> Unit
    ) {
        if (!isBillingReady) {
            callback(false, "Billing not ready")
            return
        }

        val product = productDetails.find { it.productId == productId }
            ?: run {
                callback(false, "Product not found")
                return
            }

        purchaseCallback = callback

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams
                        .newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()

        billingClient.launchBillingFlow(activity, params)
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        val productId = purchase.products.firstOrNull() ?: return

        val credits = when (productId) {
            PRODUCT_3_CV -> 3
            PRODUCT_8_CV -> 8
            else -> return
        }

        creditManager.addCredits(credits) { success ->
            if (!success) return@addCredits

            if (!purchase.isAcknowledged) {
                acknowledgePurchase(purchase)
            }

            consumePurchase(purchase)

            purchaseCallback?.invoke(
                true,
                "$credits credits added successfully"
            )
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        billingClient.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) {}
    }

    private fun consumePurchase(purchase: Purchase) {
        billingClient.consumeAsync(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { result, _ ->
            Log.i(TAG, "Consumed: ${result.responseCode}")
        }
    }

    private fun queryPurchases() {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            purchases.forEach { handlePurchase(it) }
        }
    }

    fun destroy() {
        if (::billingClient.isInitialized) {
            billingClient.endConnection()
        }
    }
}
