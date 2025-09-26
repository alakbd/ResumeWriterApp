package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.alakdb.resumewriter.UserManager


class MainActivity : AppCompatActivity() {

    private lateinit var userManager: UserManager
    private lateinit var billingManager: BillingManager
    private lateinit var creditManager: CreditManager
    private lateinit var binding: ActivityMainBinding

    private var adminTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        userManager = UserManager(this)
        initializeManagers()
        setupUI()
        updateCreditDisplay()

        // Check if user is registered
        if (!userManager.isUserRegistered()) {
        // User not registered → go to registration
        startActivity(Intent(this, UserRegistrationActivity::class.java))
        finish()
        return
    } else {
        // User marked as registered but maybe not logged in → go to Login
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
    }
}

        // Sync credits with Firebase server
        syncWithFirebase()
    }

    // Initialize credit and billing managers
    private fun initializeManagers() {
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
        billingManager.initializeBilling()
    }

    // Setup UI event listeners
    private fun setupUI() {
        binding.btnGenerateCv.setOnClickListener { generateCV() }
        binding.btnBuy3Cv.setOnClickListener { billingManager.purchaseProduct(this, "cv_package_3") }
        binding.btnBuy8Cv.setOnClickListener { billingManager.purchaseProduct(this, "cv_package_8") }

        // Secret admin access (triple-tap on version text)
        binding.tvVersion.setOnClickListener {
            adminTapCount++
            if (adminTapCount >= 3) {
                adminTapCount = 0
                startActivity(Intent(this, AdminLoginActivity::class.java))
            }
        }

        // Admin button
        binding.btnAdminAccess.setOnClickListener {
            startActivity(Intent(this, AdminLoginActivity::class.java))
        }

        // Show admin indicator if in admin mode
        if (creditManager.isAdminMode()) {
            binding.tvAdminIndicator.visibility = View.VISIBLE
        }
    }

    // Generate CV using credits
    private fun generateCV() {
        if (creditManager.useCredit()) {
            showMessage("CV generated successfully!")
            updateCreditDisplay()
        } else {
            showMessage("Not enough credits! Please purchase more.")
        }
    }

    // Update credit display in the UI
    private fun updateCreditDisplay() {
        val available = creditManager.getAvailableCredits()
        val used = creditManager.getUsedCredits()
        val totalEarned = creditManager.getTotalCreditsEarned()

        binding.tvAvailableCredits.text = "Available CV Credits: $available"
        binding.tvCreditStats.text = "Used: $used | Total Earned: $totalEarned"

        binding.btnGenerateCv.isEnabled = available > 0
    }

    // Sync local credits with Firebase server
    private fun syncWithFirebase() {
        userManager.syncCreditsWithServer { success ->
            if (success) updateCreditDisplay()
        }
    }

    // Utility function to show toast messages
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
