package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityUserRegistrationBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

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

    userManager.registerUser(email, password) { success, error ->
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
            "availableCredits" to 0,
            "usedCredits" to 0,
            "totalCreditsEarned" to 0,
            "createdAt" to System.currentTimeMillis()
        )

        Firebase.firestore.collection("users").document(user.uid)
            .set(userMap)
            .addOnSuccessListener {
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register"
                showMessage("Registration successful! Please log in.")

                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                binding.btnRegister.isEnabled = true
                binding.btnRegister.text = "Register"
                showMessage("Failed to save user profile: ${e.message}")
            }
    }
}

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

