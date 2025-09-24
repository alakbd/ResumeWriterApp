package com.example.resumewriter

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import android.view.View
import android.content.Intent
import com.example.resumewriter.databinding.ActivityMainBinding // ✅ Auto-generated from activity_main.xml





class MainActivity : AppCompatActivity() {
    
    private lateinit var userManager: UserManager
    private lateinit var billingManager: BillingManager
    private lateinit var creditManager: CreditManager
    private lateinit var binding: ActivityMainBinding

    private var adminTapCount = 0 // ✅ Declare it

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Initialize ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeManagers()
        setupUI()
        updateCreditDisplay()

        // Initialize Firebase and check user registration
        userManager = UserManager(this)
        if (!userManager.isUserRegistered()) {
            // Show registration screen or auto-register
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
            return
    }
        setContentView(R.layout.activity_main)
        // ... rest of your existing code ...
        
        // Sync with Firebase when app starts
        syncWithFirebase()
    }

    private fun syncWithFirebase() {
        userManager.syncCreditsWithServer { success ->
            if (success) {
                updateCreditDisplay()
            }
        }
    }
}
    private fun initializeManagers() {
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
        billingManager.initializeBilling()
    }

    private fun setupUI() {
        // ✅ Use binding instead of findViewById
        binding.btnGenerateCv.setOnClickListener {
            generateCV()
        }

        binding.btnBuy3Cv.setOnClickListener {
            billingManager.purchaseProduct(this, "cv_package_3")
        }

        binding.btnBuy8Cv.setOnClickListener {
            billingManager.purchaseProduct(this, "cv_package_8")
        }

        // ✅ Secret admin access (triple-tap on version text)
        binding.tvVersion.setOnClickListener {
            adminTapCount++
            if (adminTapCount >= 3) {
                adminTapCount = 0
                startActivity(Intent(this, AdminLoginActivity::class.java))
            }
        }

        // ✅ Admin button
        binding.btnAdminAccess.setOnClickListener {
            startActivity(Intent(this, AdminLoginActivity::class.java))
        }

        // ✅ Show admin indicator if needed
        if (creditManager.isAdminMode()) {
            binding.tvAdminIndicator.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        updateCreditDisplay()
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
