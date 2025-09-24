package com.example.resumewriter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.example.resumewriter.UserManager


class MainActivity : AppCompatActivity() {

    private lateinit var userManager: UserManager
    private lateinit var billingManager: BillingManager
    private lateinit var creditManager: CreditManager
    private lateinit var binding: ActivityMainBinding

    private var adminTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers
        initializeManagers()
        setupUI()
        updateCreditDisplay()

        userManager = UserManager(this)
        if (!userManager.isUserRegistered()) {
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
            return
        }

        syncWithFirebase()
    }

    // ... rest of MainActivity code ...
}
