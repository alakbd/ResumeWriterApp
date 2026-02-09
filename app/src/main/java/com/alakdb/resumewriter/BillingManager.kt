package com.alakdb.resumewriter

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(private val context: Context, private val creditManager: CreditManager) {
    private lateinit var billingClient: BillingClient
    private var productDetails: List<ProductDetails> = emptyList()
    private var isBillingReady = false
    
    // Callback for purchase events
    private var purchaseCallback: ((Boolean, String) -> Unit)? = null
    
    companion object {
        private const val TAG = "BillingManager"
        
        // Product IDs - make sure these match your Google Play Console
        private const val PRODUCT_3_CV = "cv_package_3"
        private const val PRODUCT_8_CV = "cv_package_8"
    }
    
    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    handlePurchase(purchase)
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "User canceled the purchase")
                purchaseCallback?.invoke(false, "Purchase canceled")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.w(TAG, "Item already owned - checking for pending purchase")
                // Query purchases to see if there's a pending one that needs processing
                queryPurchases()
                purchaseCallback?.invoke(false, "Please wait while we check your purchase status")
            }
            else -> {
                Log.e(TAG, "Billing error: ${billingResult.responseCode}")
                purchaseCallback?.invoke(false, "Purchase failed: ${billingResult.debugMessage}")
            }
        }
    }
    
    fun initializeBilling(onComplete: (Boolean, String) -> Unit) {
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()
            
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.OK -> {
                        Log.i(TAG, "Billing service connected")
                        isBillingReady = true
                        loadProducts()
                        // Query existing purchases on initialization to handle any pending ones
                        queryPurchases()
                        onComplete(true, "Billing initialized successfully")
                    }
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                        Log.e(TAG, "Billing unavailable")
                        onComplete(false, "Billing not available on this device")
                    }
                    else -> {
                        Log.e(TAG, "Billing setup failed: ${billingResult.responseCode}")
                        onComplete(false, "Billing setup failed: ${billingResult.debugMessage}")
                    }
                }
            }
            
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                isBillingReady = false
                // Attempt to restart the connection
                // You might want to implement a retry mechanism here
            }
        })
    }
    
    private fun queryPurchases() {
        if (!isBillingReady) return
        
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchases ->
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Log.i(TAG, "Found ${purchases.size} existing purchases")
                    purchases.forEach { purchase ->
                        // Handle any purchases that haven't been processed yet
                        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                            Log.i(TAG, "Found unprocessed purchase: ${purchase.products.firstOrNull()}")
                            handlePurchase(purchase)
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "Failed to query purchases: ${billingResult.responseCode}")
                }
            }
        }
    }
    
    private fun loadProducts() {
        if (!isBillingReady) return
        
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_3_CV)
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_8_CV)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
            
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    productDetails = productDetailsList
                    Log.i(TAG, "Loaded ${productDetails.size} products")
                    
                    if (productDetails.isEmpty()) {
                        Log.w(TAG, "No products found - check product IDs in Google Play Console")
                    }
                }
                else -> {
                    Log.e(TAG, "Failed to load products: ${billingResult.responseCode}")
                }
            }
        }
    }
    
    fun purchaseProduct(activity: Activity, productId: String, callback: (Boolean, String) -> Unit) {
        if (!isBillingReady) {
            callback(false, "Billing not ready. Please try again.")
            return
        }
        
        if (productDetails.isEmpty()) {
            callback(false, "Products not loaded yet. Please wait.")
            return
        }
        
        val product = productDetails.find { it.productId == productId }
        if (product == null) {
            callback(false, "Product not available: $productId")
            return
        }
        
        purchaseCallback = callback
        
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
            
        val responseCode = billingClient.launchBillingFlow(activity, flowParams).responseCode
        
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            callback(false, "Failed to launch billing flow: $responseCode")
            purchaseCallback = null
        }
    }
    
    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                val productId = purchase.products.firstOrNull()
                if (productId == null) {
                    Log.e(TAG, "Purchase has no product ID")
                    return
                }

                val creditsToAdd = when (productId) {
                    PRODUCT_3_CV -> 3
                    PRODUCT_8_CV -> 8
                    else -> {
                        Log.e(TAG, "Unknown product purchased: $productId")
                        0
                    }
                }

                if (creditsToAdd <= 0) return

                // CRITICAL: For a top-up system, we need to acknowledge the purchase
                // but NOT consume it. Consumption is only for one-time use items.
                // Acknowledgment tells Google Play the purchase was delivered.
                
                // Step 1: Grant credits first
                creditManager.addCredits(creditsToAdd) { success ->
                    if (!success) {
                        Log.e(TAG, "Failed to add credits after purchase")
                        purchaseCallback?.invoke(
                            false,
                            "Purchase completed but credits could not be added."
                        )
                        return@addCredits
                    }

                    Log.i(TAG, "Successfully added $creditsToAdd credits")

                    // Step 2: Acknowledge purchase (REQUIRED for all purchases)
                    if (!purchase.isAcknowledged) {
                        acknowledgePurchase(purchase)
                    }

                    purchaseCallback?.invoke(
                        true,
                        "Purchase successful! $creditsToAdd credits added to your account."
                    )
                }
            }

            Purchase.PurchaseState.PENDING -> {
                Log.i(TAG, "Purchase is pending")
                purchaseCallback?.invoke(
                    true,
                    "Purchase is pending. Credits will be added once payment is completed."
                )
            }

            else -> {
                Log.w(TAG, "Unhandled purchase state: ${purchase.purchaseState}")
            }
        }
    }
    
    private fun acknowledgePurchase(purchase: Purchase) {
        val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
            
        billingClient.acknowledgePurchase(acknowledgeParams) { billingResult ->
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    Log.i(TAG, "Purchase acknowledged successfully")
                }
                else -> {
                    Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.responseCode}")
                }
            }
        }
    }
    
    fun getProductPrice(productId: String): String {
        return productDetails.find { it.productId == productId }
            ?.let { product ->
                product.oneTimePurchaseOfferDetails?.formattedPrice ?: "Price not available"
            } ?: "Not available"
    }
    
    fun isBillingReady(): Boolean = isBillingReady
    
    fun getAvailableProducts(): List<String> {
        return productDetails.map { it.productId }
    }
    
    fun destroy() {
        purchaseCallback = null
        if (::billingClient.isInitialized) {
            billingClient.endConnection()
        }
    }
}
