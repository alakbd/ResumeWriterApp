package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.alakdb.resumewriter.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    private lateinit var creditManager: CreditManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        creditManager = CreditManager(this)

        // Check if already logged in
        if (userManager.isUserLoggedIn()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            val email = binding.etLoginEmail.text.toString().trim()
            val password = binding.etLoginPassword.text.toString().trim()

            if (validateInput(email, password)) {
                attemptLogin(email, password)
            }
        }

        binding.btnForgotPassword.setOnClickListener {
            val email = binding.etLoginEmail.text.toString().trim()
            when {
                email.isEmpty() -> showMessage("Please enter your email first")
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                    binding.etLoginEmail.error = "Enter a valid email address"
                else -> sendPasswordResetEmail(email)
            }
        }
    
        // Go to registration page
        binding.btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        if (email.isEmpty()) {
            binding.etLoginEmail.error = "Email is required"
            return false
        }

        if (password.isEmpty()) {
            binding.etLoginPassword.error = "Password is required"
            return false
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etLoginEmail.error = "Enter a valid email address"
            return false
        }

        return true
    }

    private fun sendPasswordResetEmail(email: String) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(this, "Password reset link sent to $email", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Error: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun attemptLogin(email: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Logging in..."

        userManager.loginUser(email, password) { success, error ->
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "Login"

            if (success) {
                showMessage("Login successful!")
                
                // RESET COOLDOWN ON SUCCESSFUL LOGIN
                creditManager.resetResumeCooldown()
                
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } else {
                showMessage(error ?: "Login failed")
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
