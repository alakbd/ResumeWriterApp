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
    private var adminTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        creditManager = CreditManager(this)

        // Check authentication
        if (!userManager.isUserLoggedIn()) {
            redirectToLogin()
            return
        }

        initializeUI()
        loadUserData()
    }

    private fun initializeUI() {
        binding.btnGenerateCv.setOnClickListener { generateCV() }
        binding.btnBuy3Cv.setOnClickListener { showMessage("Purchase feature coming soon") }
        binding.btnBuy8Cv.setOnClickListener { showMessage("Purchase feature coming soon") }

        // Admin access
        binding.tvVersion.setOnClickListener {
            adminTapCount++
            if (adminTapCount >= 3) {
                adminTapCount = 0
                if (creditManager.isAdminMode()) {
                    startActivity(Intent(this, AdminPanelActivity::class.java))
                } else {
                    startActivity(Intent(this, AdminLoginActivity::class.java))
                }
            }
        }

        binding.btnAdminAccess.setOnClickListener {
            if (creditManager.isAdminMode()) {
                startActivity(Intent(this, AdminPanelActivity::class.java))
            } else {
                startActivity(Intent(this, AdminLoginActivity::class.java))
            }
        }

        // Show admin indicator
        binding.tvAdminIndicator.visibility = if (creditManager.isAdminMode()) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
    }

    private fun loadUserData() {
        creditManager.syncWithFirebase { success, credits ->
            if (success) {
                updateCreditDisplay()
                showMessage("Data synchronized successfully")
            } else {
                showMessage("Sync failed, using local data")
                updateCreditDisplay()
            }
        }
    }

    private fun generateCV() {
        creditManager.useCredit { success ->
            if (success) {
                showMessage("CV generated successfully!")
                updateCreditDisplay()
            } else {
                showMessage("Not enough credits! Please purchase more.")
            }
        }
    }

    private fun updateCreditDisplay() {
        val available = creditManager.getAvailableCredits()
        val used = creditManager.getUsedCredits()
        val total = creditManager.getTotalCredits()

        binding.tvAvailableCredits.text = "Available CV Credits: $available"
        binding.tvCreditStats.text = "Used: $used | Total Earned: $total"
        binding.btnGenerateCv.isEnabled = available > 0
    }

    private fun redirectToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
