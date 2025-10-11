package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var userManager: UserManager
    private lateinit var creditManager: CreditManager
    private lateinit var billingManager: BillingManager

    private var adminTapCount = 0
    private var isBillingInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        initializeManagers()

        // Check authentication first
        if (!checkAuthentication()) return

        initializeApp()
    }

    private fun initializeManagers() {
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
    }

    private fun checkAuthentication(): Boolean {
        if (!userManager.isUserLoggedIn()) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return false
        }
        return true
    }

    private fun initializeApp() {
        setupClickListeners()
        initializeBilling()
        loadUserData()
        updateAdminIndicator()
    }

    private fun setupClickListeners() {
        // Generate CV Button - Opens WebView without deducting credit
        binding.btnGenerateCv.setOnClickListener { generateCV() }

        // Purchase Buttons
        binding.btnBuy3Cv.setOnClickListener {
            if (isBillingInitialized) purchaseProduct("cv_package_3")
            else showMessage("Store not ready. Please wait...")
        }

        binding.btnBuy8Cv.setOnClickListener {
            if (isBillingInitialized) purchaseProduct("cv_package_8")
            else showMessage("Store not ready. Please wait...")
        }

        // Admin Access
        binding.tvVersion.setOnClickListener { handleAdminTap() }
        binding.btnAdminAccess.setOnClickListener { navigateToAdmin() }

        // Logout Button
        binding.btnLogout.setOnClickListener {
            userManager.logout()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        // Refresh Button
        binding.btnRefresh.setOnClickListener {
            binding.btnRefresh.isEnabled = false
            binding.btnRefresh.text = "Refreshing..."
            creditManager.syncWithFirebase { success, _ ->
                binding.btnRefresh.isEnabled = true
                binding.btnRefresh.text = "Refresh"
                if (success) {
                    updateCreditDisplay()
                    showMessage("Data refreshed successfully!")
                } else {
                    updateCreditDisplay()
                    showMessage("Failed to refresh. Using local data.")
                }
            }
        }
    }

    private fun handleAdminTap() {
        adminTapCount++
        when (adminTapCount) {
            1 -> binding.tvVersion.text = "v1.0 (1/3)"
            2 -> binding.tvVersion.text = "v1.0 (2/3)"
            3 -> {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
                navigateToAdmin()
            }
        }
        binding.tvVersion.postDelayed({
            if (adminTapCount in 1..2) {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
            }
        }, 2000)
    }

    private fun initializeBilling() {
        billingManager.initializeBilling { success, _ ->
            isBillingInitialized = success
            if (success) {
                showMessage("Store ready")
                updateProductPrices()
            } else {
                showMessage("Store temporarily unavailable")
                binding.btnBuy3Cv.text = "3 CV Credits - Unavailable"
                binding.btnBuy8Cv.text = "8 CV Credits - Unavailable"
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy8Cv.isEnabled = false
            }
        }
    }

    private fun loadUserData() {
        binding.btnGenerateCv.isEnabled = false
        binding.btnGenerateCv.text = "Loading..."

        creditManager.syncWithFirebase { success, _ ->
            binding.btnGenerateCv.isEnabled = true
            updateGenerateButton()

            if (success) {
                updateCreditDisplay()
                showMessage("Data synchronized")
            } else {
                updateCreditDisplay()
                showMessage("Using local data")
            }
        }
    }

    private fun generateCV() {
        val availableCredits = creditManager.getAvailableCredits()
        if (availableCredits <= 0) {
            showMessage("Not enough credits! Please purchase more.")
            return
    }

    // Open the new Resume Generation Activity
    val intent = Intent(this, ResumeGenerationActivity::class.java)
    startActivity(intent)
}

    private fun purchaseProduct(productId: String) {
        if (!isBillingInitialized) {
            showMessage("Billing not ready. Please try again later.")
            return
        }

        when (productId) {
            "cv_package_3" -> {
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy3Cv.text = "Processing..."
            }
            "cv_package_8" -> {
                binding.btnBuy8Cv.isEnabled = false
                binding.btnBuy8Cv.text = "Processing..."
            }
        }

        billingManager.purchaseProduct(this, productId) { success, message ->
            updatePurchaseButtons()
            showMessage(message)
            if (success) {
                creditManager.syncWithFirebase { syncSuccess, _ ->
                    updateCreditDisplay()
                    if (syncSuccess) showMessage("Credits updated successfully!")
                }
            }
        }
    }

    private fun updatePurchaseButtons() {
        if (isBillingInitialized) {
            binding.btnBuy3Cv.isEnabled = true
            binding.btnBuy8Cv.isEnabled = true
            updateProductPrices()
        }
    }

    private fun updateProductPrices() {
        val price3 = billingManager.getProductPrice("cv_package_3")
        val price8 = billingManager.getProductPrice("cv_package_8")

        binding.btnBuy3Cv.text = "3 CV Credits - ${if (price3 != "Not available") price3 else "€5"}"
        binding.btnBuy8Cv.text = "8 CV Credits - ${if (price8 != "Not available") price8 else "€10"}"
    }

    private fun updateCreditDisplay() {
        binding.tvAvailableCredits.text = "Available CV Credits: ${creditManager.getAvailableCredits()}"
        binding.tvCreditStats.text = "Used: ${creditManager.getUsedCredits()} | Total Earned: ${creditManager.getTotalCredits()}"
        updateGenerateButton()
    }

    private fun updateGenerateButton() {
        val available = creditManager.getAvailableCredits()
        binding.btnGenerateCv.isEnabled = available > 0
        binding.btnGenerateCv.text = if (available > 0) "Generate CV (1 Credit)" else "No Credits Available"
    }

    private fun navigateToAdmin() {
        val intent = if (creditManager.isAdminMode()) {
            Intent(this, AdminPanelActivity::class.java)
        } else {
            Intent(this, AdminLoginActivity::class.java)
        }
        startActivity(intent)
    }

    private fun updateAdminIndicator() {
        val isAdmin = creditManager.isAdminMode()
        binding.tvAdminIndicator.visibility = if (isAdmin) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnAdminAccess.visibility = if (isAdmin) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::creditManager.isInitialized) {
            // Refresh credit display when returning from WebView
            creditManager.syncWithFirebase { success, _ ->
                updateCreditDisplay()
                updateAdminIndicator()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) billingManager.destroy()
    }
}
