package com.example.resumewriter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

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
        initializeManagers()
        setupUI()
        updateCreditDisplay()

        // Initialize Firebase and check user registration
        userManager = UserManager(this)
        if (!userManager.isUserRegistered()) {
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
            return
        }

        // Sync credits with Firebase server
        syncWithFirebase()
    }

    private fun initializeManagers() {
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
        billingManager.initializeBilling()
    }

    private fun setupUI() {
        binding.btnGenerateCv.setOnClickListener { generateCV() }
        binding.btnBuy3Cv.setOnClickListener { billingManager.purchaseProduct(this, "cv_package_3") }
        binding.btnBuy8Cv.setOnClickListener { billingManager.purchaseProduct(this, "cv_package_8") }

        // Secret admin access (triple-tap on version)
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

        // Admin indicator
        if (creditManager.isAdminMode()) {
            binding.tvAdminIndicator.visibility = View.VISIBLE
        }
    }

    private fun syncWithFirebase() {
        userManager.syncCreditsWithServer { success ->
            if (success) updateCreditDisplay()
        }
    }

    private fun generateCV() {
        if (creditManager.useCredit()) {
            showMessage("CV generated successfully!")
            updateCreditDisplay()
        } else {
            showMessage("Not enough credits! Please purchase more.")
        }
    }

    private fun updateCreditDisplay() {
        val available = creditManager.getAvailableCredits()
        val used = creditManager.getUsedCredits()
        val totalEarned = creditManager.getTotalCreditsEarned()

        binding.tvAvailableCredits.text = "Available CV Credits: $available"
        binding.tvCreditStats.text = "Used: $used | Total Earned: $totalEarned"

        binding.btnGenerateCv.isEnabled = available > 0
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
