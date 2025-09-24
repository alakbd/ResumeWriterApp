package com.example.resumewriter

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : AppCompatActivity() {
    private lateinit var billingManager: BillingManager
    private lateinit var creditManager: CreditManager
    
    private lateinit var tvCreditStats: TextView
    private lateinit var tvAvailableCredits: TextView
    private lateinit var btnGenerateCV: Button
    private lateinit var btnBuy3CV: Button
    private lateinit var btnBuy8CV: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initializeManagers()
        setupUI()
        updateCreditDisplay()
    }
    
    private fun initializeManagers() {
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
        billingManager.initializeBilling()
    }
    
    private fun setupUI() {
        tvCreditStats = findViewById(R.id.tv_credit_stats)
        tvAvailableCredits = findViewById(R.id.tv_available_credits)
        btnGenerateCV = findViewById(R.id.btn_generate_cv)
        btnBuy3CV = findViewById(R.id.btn_buy_3_cv)
        btnBuy8CV = findViewById(R.id.btn_buy_8_cv)
        
        btnGenerateCV.setOnClickListener {
            generateCV()
        }
        
        btnBuy3CV.setOnClickListener {
            billingManager.purchaseProduct(this, "cv_package_3")
        }
        
        btnBuy8CV.setOnClickListener {
            billingManager.purchaseProduct(this, "cv_package_8")
        }
        
        // Add secret admin access (triple-tap on version text)
        val tvVersion = findViewById<TextView>(R.id.tv_version) // Add this to your layout
        tvVersion.setOnClickListener {
            adminTapCount++
            if (adminTapCount >= 3) {
                adminTapCount = 0
                startActivity(Intent(this, AdminLoginActivity::class.java))
        }
    }
    
        // Or add admin button to your main screen (hidden or visible)
        val btnAdmin = findViewById<Button>(R.id.btn_admin_access) // Add this button
        btnAdmin.setOnClickListener {
            startActivity(Intent(this, AdminLoginActivity::class.java))
    }
    
    // Check if we're in admin mode and show indicator
        if (creditManager.isAdminMode()) {
            findViewById<TextView>(R.id.tv_admin_indicator).visibility = View.VISIBLE
        }
    }
    
    override fun onResume() {
        super.onResume()
        updateCreditDisplay()
    }
    
    private fun generateCV() {
        if (creditManager.useCredit()) {
            // Your existing CV generation code here
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
        
        tvAvailableCredits.text = "Available CV Credits: $available"
        tvCreditStats.text = "Used: $used | Total Earned: $totalEarned"
        
        btnGenerateCV.isEnabled = available > 0
    }
    
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
