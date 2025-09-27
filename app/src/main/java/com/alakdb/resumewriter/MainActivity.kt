package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)

        // Check authentication first
        if (!checkAuthentication()) {
            return  // Exit early if not authenticated
        }

        initializeApp()
    }

    private fun checkAuthentication(): Boolean {
        val currentUser = FirebaseAuth.getInstance().currentUser
        
        if (currentUser == null || !userManager.isUserLoggedIn()) {
            // User not logged in, redirect to login
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return false
        }
        
        return true
    }

    private fun initializeApp() {
        initializeBilling()
        setupUI()
        loadUserData()
        updateAdminIndicator()
    }

    private fun initializeBilling() {
        billingManager.initializeBilling { success, message ->
            isBillingInitialized = success
            if (success) {
                showMessage("Store ready")
                updateProductPrices()
            } else {
                showMessage("Store temporarily unavailable")
                // Disable purchase buttons but keep the app functional
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy8Cv.isEnabled = false
                binding.btnBuy3Cv.text = "Store Unavailable"
                binding.btnBuy8Cv.text = "Store Unavailable"
            }
        }
    }

    private fun setupUI() {
        // CV Generation
        binding.btnGenerateCv.setOnClickListener {
            generateCV()
        }

        // Purchase Buttons with proper error handling
        binding.btnBuy3Cv.setOnClickListener {
            if (isBillingInitialized) {
                purchaseProduct("cv_package_3")
            } else {
                showMessage("Store not ready. Please wait...")
            }
        }

        binding.btnBuy8Cv.setOnClickListener {
            if (isBillingInitialized) {
                purchaseProduct("cv_package_8")
            } else {
                showMessage("Store not ready. Please wait...")
            }
        }

        // Admin Access - Triple tap on version text
        binding.tvVersion.setOnClickListener {
            adminTapCount++
            if (adminTapCount >= 3) {
                adminTapCount = 0
                navigateToAdmin()
            }
        }

        // Admin Access - Direct button
        binding.btnAdminAccess.setOnClickListener {
            navigateToAdmin()
        }

        // Logout button
        binding.btnLogout.setOnClickListener {
            logoutUser()
        }

        // Refresh button
        binding.btnRefresh.setOnClickListener {
            refreshUserData()
        }
    }

    private fun loadUserData() {
        showLoadingState(true)
        
        creditManager.syncWithFirebase { success, credits ->
            showLoadingState(false)
            
            if (success) {
                updateCreditDisplay()
                if (credits != null) {
                    showMessage("Data synchronized successfully")
                }
            } else {
                showMessage("Using local data - sync failed")
                updateCreditDisplay() // Still show local data
            }
        }
    }

    private fun refreshUserData() {
        binding.btnRefresh.isEnabled = false
        creditManager.syncWithFirebase { success, credits ->
            binding.btnRefresh.isEnabled = true
            if (success) {
                updateCreditDisplay()
                showMessage("Data refreshed successfully")
            } else {
                showMessage("Refresh failed - using current data")
            }
        }
    }

    private fun generateCV() {
        if (creditManager.getAvailableCredits() <= 0) {
            showMessage("Not enough credits! Please purchase more.")
            return
        }

        binding.btnGenerateCv.isEnabled = false
        binding.btnGenerateCv.text = "Generating..."

        creditManager.useCredit { success ->
            binding.btnGenerateCv.isEnabled = true
            binding.btnGenerateCv.text = "Generate CV"
            
            if (success) {
                showMessage("CV generated successfully!")
                updateCreditDisplay()
                
                // Here you would typically navigate to CV creation screen
                // startActivity(Intent(this, CvCreationActivity::class.java))
            } else {
                showMessage("Failed to generate CV. Please try again.")
            }
        }
    }

    private fun purchaseProduct(productId: String) {
        if (!isBillingInitialized) {
            showMessage("Billing not initialized. Please try again.")
            return
        }

        // Show loading state on the specific button
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
            // Reset button states
            updatePurchaseButtons()
            
            showMessage(message)
            
            if (success) {
                // Refresh credits after successful purchase
                creditManager.syncWithFirebase { syncSuccess, credits ->
                    updateCreditDisplay()
                    if (syncSuccess) {
                        showMessage("Credits updated successfully!")
                    }
                }
            }
        }
    }

    private fun updatePurchaseButtons() {
        binding.btnBuy3Cv.isEnabled = isBillingInitialized
        binding.btnBuy8Cv.isEnabled = isBillingInitialized
        
        if (isBillingInitialized) {
            updateProductPrices()
        } else {
            binding.btnBuy3Cv.text = "Store Unavailable"
            binding.btnBuy8Cv.text = "Store Unavailable"
        }
    }

    private fun updateProductPrices() {
        val price3 = billingManager.getProductPrice("cv_package_3")
        val price8 = billingManager.getProductPrice("cv_package_8")
        
        binding.btnBuy3Cv.text = "Buy 3 CVs - $price3"
        binding.btnBuy8Cv.text = "Buy 8 CVs - $price8"
    }

    private fun navigateToAdmin() {
        if (creditManager.isAdminMode()) {
            // Already admin, go directly to admin panel
            startActivity(Intent(this, AdminPanelActivity::class.java))
        } else {
            // Need to login as admin
            startActivity(Intent(this, AdminLoginActivity::class.java))
        }
    }

    private fun logoutUser() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Yes") { _, _ ->
                userManager.logout()
                showMessage("Logged out successfully")
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCreditDisplay() {
        val available = creditManager.getAvailableCredits()
        val used = creditManager.getUsedCredits()
        val total = creditManager.getTotalCredits()

        binding.tvAvailableCredits.text = "Available CV Credits: $available"
        binding.tvCreditStats.text = "Used: $used | Total Earned: $total"
        
        // Enable/disable generate button based on credits
        binding.btnGenerateCv.isEnabled = available > 0
        binding.btnGenerateCv.text = if (available > 0) "Generate CV" else "No Credits Available"
        
        // Update user email display
        val userEmail = userManager.getCurrentUserEmail() ?: "Unknown"
        binding.tvUserWelcome.text = "Welcome, ${userEmail.substringBefore("@")}"
    }

    private fun updateAdminIndicator() {
        if (creditManager.isAdminMode()) {
            binding.tvAdminIndicator.visibility = android.view.View.VISIBLE
            binding.btnAdminAccess.visibility = android.view.View.VISIBLE
        } else {
            binding.tvAdminIndicator.visibility = android.view.View.GONE
            binding.btnAdminAccess.visibility = android.view.View.GONE
        }
    }

    private fun showLoadingState(show: Boolean) {
        binding.progressBar.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnRefresh.isEnabled = !show
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to the app
        if (::creditManager.isInitialized) {
            updateCreditDisplay()
        }
    }
}
