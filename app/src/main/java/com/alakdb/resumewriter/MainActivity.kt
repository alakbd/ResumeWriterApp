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
        if (!checkAuthentication()) {
            return  // Exit early if not authenticated
        }

        initializeApp()
    }

    private fun initializeManagers() {
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
    }

    private fun checkAuthentication(): Boolean {
        if (!userManager.isUserLoggedIn()) {
            // User not logged in, redirect to login
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
        // Generate CV Button
        binding.btnGenerateCv.setOnClickListener {
            generateCV()
        }

        // Purchase Buttons
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

        // Admin Access - Triple tap on version text (hidden feature)
        binding.tvVersion.setOnClickListener {
            handleAdminTap()
        }

        // Admin Access - Direct button
        binding.btnAdminAccess.setOnClickListener {
            navigateToAdmin()
        }
    }

    private fun handleAdminTap() {
        adminTapCount++
        
        // Show tap feedback (optional)
        when (adminTapCount) {
            1 -> binding.tvVersion.text = "v1.0 (1/3)"
            2 -> binding.tvVersion.text = "v1.0 (2/3)"
            3 -> {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
                navigateToAdmin()
            }
        }
        
        // Reset counter after 2 seconds
        binding.tvVersion.postDelayed({
            if (adminTapCount > 0 && adminTapCount < 3) {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
            }
        }, 2000)
    }

    private fun initializeBilling() {
        billingManager.initializeBilling { success, message ->
            isBillingInitialized = success
            if (success) {
                showMessage("Store ready")
                updateProductPrices()
            } else {
                showMessage("Store temporarily unavailable")
                // Update button texts to show unavailable state
                binding.btnBuy3Cv.text = "3 CV Credits - Unavailable"
                binding.btnBuy8Cv.text = "8 CV Credits - Unavailable"
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy8Cv.isEnabled = false
            }
        }
    }

    private fun loadUserData() {
        // Show initial loading state
        binding.btnGenerateCv.isEnabled = false
        binding.btnGenerateCv.text = "Loading..."
        
        creditManager.syncWithFirebase { success, credits ->
            if (success) {
                updateCreditDisplay()
                showMessage("Data synchronized")
            } else {
                // Still show local data even if sync fails
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

        // Show loading state
        binding.btnGenerateCv.isEnabled = false
        binding.btnGenerateCv.text = "Generating CV..."

        creditManager.useCredit { success ->
            // Restore button state
            updateGenerateButton()
            
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
            showMessage("Billing not ready. Please try again later.")
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
        if (isBillingInitialized) {
            binding.btnBuy3Cv.isEnabled = true
            binding.btnBuy8Cv.isEnabled = true
            updateProductPrices()
        }
    }

    private fun updateProductPrices() {
        val price3 = billingManager.getProductPrice("cv_package_3")
        val price8 = billingManager.getProductPrice("cv_package_8")
        
        // Use default prices if billing not available
        val displayPrice3 = if (price3 != "Not available") price3 else "€5"
        val displayPrice8 = if (price8 != "Not available") price8 else "€10"
        
        binding.btnBuy3Cv.text = "3 CV Credits - $displayPrice3"
        binding.btnBuy8Cv.text = "8 CV Credits - $displayPrice8"
    }

    private fun updateCreditDisplay() {
        val available = creditManager.getAvailableCredits()
        val used = creditManager.getUsedCredits()
        val total = creditManager.getTotalCredits()

        // Update credit displays
        binding.tvAvailableCredits.text = "Available CV Credits: $available"
        binding.tvCreditStats.text = "Used: $used | Total Earned: $total"
        
        // Update generate button state
        updateGenerateButton()
    }

    private fun updateGenerateButton() {
        val available = creditManager.getAvailableCredits()
        binding.btnGenerateCv.isEnabled = available > 0
        binding.btnGenerateCv.text = if (available > 0) "Generate CV (1 Credit)" else "No Credits Available"
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

    private fun updateAdminIndicator() {
        val isAdmin = creditManager.isAdminMode()
        
        if (isAdmin) {
            binding.tvAdminIndicator.visibility = android.view.View.VISIBLE
            binding.btnAdminAccess.visibility = android.view.View.VISIBLE
        } else {
            binding.tvAdminIndicator.visibility = android.view.View.GONE
            binding.btnAdminAccess.visibility = android.view.View.GONE
        }
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ ->
                // Properly finish the activity
                finishAffinity()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh data when returning to the app
        if (::creditManager.isInitialized) {
            updateCreditDisplay()
            updateAdminIndicator()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up billing resources
        if (::billingManager.isInitialized) {
            billingManager.destroy()
        }
    }
}
