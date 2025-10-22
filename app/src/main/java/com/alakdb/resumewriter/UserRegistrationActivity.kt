package com.alakdb.resumewriter

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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

        userManager.registerUser(email, password) { success: Boolean, error: String? ->
            if (!success) {
                // Restore button state on error
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register"
                showMessage(error ?: "Registration failed")
                return@registerUser
            }

            // At this point, registration succeeded
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register"
                showMessage("Registration succeeded but user data not ready. Try login.")
                return@registerUser
            }

            val userMap = hashMapOf(
                "email" to email,
                "availableCredits" to 3,   // ✅ give 3 free credits
                "usedCredits" to 0,
                "totalCreditsEarned" to 3, // ✅ track that 3 were earned
                "createdAt" to System.currentTimeMillis()
            )

            Firebase.firestore.collection("users").document(user.uid)
                .set(userMap)
                .addOnSuccessListener {
                    // Send verification email after successful Firestore save
                    sendVerificationEmail(user)
                }
                .addOnFailureListener { e ->
                    binding.btnRegister.isEnabled = true
                    binding.btnRegister.text = "Register"
                    showMessage("Failed to save user profile: ${e.message}")
                }
        }
    }

    private fun sendVerificationEmail(user: FirebaseUser) {
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("Registration", "Verification email sent to ${user.email}")
                    showVerificationDialog(user.email)
                } else {
                    Log.e("Registration", "Failed to send verification email", task.exception)
                    // Still proceed but show message
                    showMessage("Registration successful but verification email failed to send")
                    proceedToLoginActivity()
                }
            }
    }

    private fun showVerificationDialog(email: String?) {
        AlertDialog.Builder(this)
            .setTitle("Verify Your Email")
            .setMessage("We've sent a verification link to $email. Please check your inbox and verify your email address before logging in.")
            .setPositiveButton("Open Email") { _: DialogInterface, _: Int ->
                openEmailApp()
                proceedToLoginActivity()
            }
            .setNegativeButton("Continue") { _: DialogInterface, _: Int ->
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
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
