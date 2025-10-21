package com.alakdb.resumewriter

import android.content.Intent
import android.util.Log
import android.os.Bundle
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
                // Hide keyboard
                hideKeyboard()
                attemptLogin(email, password)
            }
        }

        binding.btnForgotPassword.setOnClickListener {
        val email = binding.etLoginEmail.text.toString().trim()
        when {
            email.isEmpty() -> {
                binding.etLoginEmail.error = getString(R.string.enter_email_first)
                binding.etLoginEmail.requestFocus()
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                binding.etLoginEmail.error = getString(R.string.enter_valid_email)
                binding.etLoginEmail.requestFocus()
            }
            else -> {
                hideKeyboard()
                sendPasswordResetEmail(email)
            }
        }
    }
    
    binding.btnGoToRegister.setOnClickListener {
        startActivity(Intent(this, UserRegistrationActivity::class.java))
        finish()
    }
}

    private fun sendPasswordResetEmail(email: String) {
    binding.btnForgotPassword.isEnabled = false
    binding.btnForgotPassword.text = getString(R.string.sending_email)
    
    FirebaseAuth.getInstance().sendPasswordResetEmail(email)
        .addOnCompleteListener { task ->
            binding.btnForgotPassword.isEnabled = true
            binding.btnForgotPassword.text = getString(R.string.forgot_password)
            
            if (task.isSuccessful) {
                showMessage(getString(R.string.reset_link_sent, email))
            } else {
                val errorMessage = task.exception?.message ?: getString(R.string.unknown_error)
                showMessage(getString(R.string.reset_email_failed, errorMessage))
            }
        }
}
    // Add helper to hide keyboard
private fun hideKeyboard() {
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
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

    

    private fun attemptLogin(email: String, password: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "Logging in..."

        userManager.loginUser(email, password) { success, error ->
            binding.btnLogin.isEnabled = true
            binding.btnLogin.text = "Login"

            if (success) {
                // ✅ UID-based auth: No token management needed
                // Ensure admin mode is explicitly disabled for regular login
                creditManager.setAdminMode(false)
                
                Log.d("LoginActivity", "✅ Login successful - UID: ${userManager.getCurrentUserId()}")
                showMessage("Login successful!")
                creditManager.resetResumeCooldown()
                
                // Navigate to MainActivity
                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                finish()
            } else {
                showMessage(error ?: "Login failed")
            }
        }
    }

    // After successful Firebase login
FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
    .addOnCompleteListener { task ->
        if (task.isSuccessful) {
            val user = task.result?.user
            if (user != null) {
                // ⭐️ CRITICAL: Save user data to UserManager
                userManager.saveUserLogin(user)
                
                // Then proceed to main activity
                startActivity(Intent(this, ResumeGenerationActivity::class.java))
                finish()
            }
        } else {
            // Handle login failure
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
