package com.alakdb.resumewriter

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.alakdb.resumewriter.databinding.ActivityUserRegistrationBinding

class UserRegistrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityUserRegistrationBinding
    private lateinit var userManager: UserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserRegistrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirmPassword = binding.etConfirmPassword.text.toString().trim()

            if (validateInput(email, password, confirmPassword)) {
                attemptRegistration(email, password)
            }
        }

        binding.btnGoToLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun validateInput(email: String, password: String, confirmPassword: String): Boolean {
        if (email.isEmpty()) {
            binding.etEmail.error = "Email is required"
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etEmail.error = "Enter a valid email address"
            return false
        }

        if (password.isEmpty()) {
            binding.etPassword.error = "Password is required"
            return false
        }

        if (password.length < 6) {
            binding.etPassword.error = "Password must be at least 6 characters"
            return false
        }

        if (password != confirmPassword) {
            binding.etConfirmPassword.error = "Passwords do not match"
            return false
        }

        return true
    }

    private fun attemptRegistration(email: String, password: String) {
        binding.btnRegister.isEnabled = false
        binding.btnRegister.text = "Registering..."

        Log.d("Registration", "🔄 Starting registration for: $email")

        // Use UserManager's registerUser method - it handles everything including Firestore, IP capture, and device ID
        userManager.registerUser(email, password) { success: Boolean, error: String? ->
            if (success) {
                Log.d("Registration", "✅ Registration successful for: $email")
                
                // ⭐⭐⭐ UserManager now handles:
                // - Firebase Auth registration
                // - Firestore user document creation  
                // - Device ID capture
                // - IP address capture (registrationIp)
                // - Sending verification email
                // - Auto-signout after registration
                
                // Show verification dialog
                showVerificationDialog(email)
            } else {
                // Registration failed
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register"
                showMessage(error ?: "Registration failed")
                Log.e("Registration", "❌ Registration failed: $error")
            }
        }
    }

    private fun showVerificationDialog(email: String?) {
        AlertDialog.Builder(this)
            .setTitle("Verify Your Email")
            .setMessage("We've sent a verification link to $email. Please check your inbox (and spam folder) and verify your email address before logging in.\n\nYou will be automatically signed out after registration - this is for security.")
            .setPositiveButton("Open Email") { _: DialogInterface, _: Int ->
                openEmailApp()
                // ⭐⭐⭐ UserManager will auto-signout, so just proceed to login
                proceedToLoginActivity()
            }
            .setNegativeButton("Continue to Login") { _: DialogInterface, _: Int ->
                // ⭐⭐⭐ UserManager auto-signout ensures user must verify before logging in
                proceedToLoginActivity()
            }
            .setCancelable(false)
            .show()
    }

    private fun openEmailApp() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_EMAIL)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: Exception) {
            showMessage("No email app found. Please check your email manually.")
        }
    }
    
    private fun proceedToLoginActivity() {
        binding.btnRegister.isEnabled = true
        binding.btnRegister.text = "Register"
        
        Log.d("Registration", "➡️ Navigating to LoginActivity")
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        // Allow back navigation to login
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
