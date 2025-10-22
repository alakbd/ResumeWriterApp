package com.alakdb.resumewriter

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.alakdb.resumewriter.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    private lateinit var creditManager: CreditManager
    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers and services
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        firebaseAuth = FirebaseAuth.getInstance()

        // Check if already logged in
        if (userManager.isUserLoggedIn()) {
            proceedToMainActivity()
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
                email.isEmpty() -> {
                    showMessage("Please enter your email first")
                    binding.etLoginEmail.requestFocus()
                }
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                    binding.etLoginEmail.error = "Enter a valid email address"
                    binding.etLoginEmail.requestFocus()
                }
                else -> sendPasswordResetEmail(email)
            }
        }
    
        binding.btnGoToRegister.setOnClickListener {
            startActivity(Intent(this, UserRegistrationActivity::class.java))
            finish()
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        var isValid = true

        if (email.isEmpty()) {
            binding.etLoginEmail.error = "Email is required"
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.etLoginEmail.error = "Enter a valid email address"
            isValid = false
        } else {
            binding.etLoginEmail.error = null
        }

        if (password.isEmpty()) {
            binding.etLoginPassword.error = "Password is required"
            isValid = false
        } else if (password.length < 6) {
            binding.etLoginPassword.error = "Password must be at least 6 characters"
            isValid = false
        } else {
            binding.etLoginPassword.error = null
        }

        return isValid
    }

    private fun sendPasswordResetEmail(email: String) {
        binding.btnForgotPassword.isEnabled = false
        val originalText = binding.btnForgotPassword.text.toString()
        binding.btnForgotPassword.text = "Sending..."

        firebaseAuth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                binding.btnForgotPassword.isEnabled = true
                binding.btnForgotPassword.text = originalText

                if (task.isSuccessful) {
                    showMessage("Password reset link sent to $email")
                    Log.d("LoginActivity", "Password reset email sent to: $email")
                } else {
                    val errorMessage = task.exception?.message ?: "Unknown error occurred"
                    showMessage("Failed to send reset email: $errorMessage")
                    Log.e("LoginActivity", "Password reset failed: $errorMessage")
                }
            }
    }

    private fun attemptLogin(email: String, password: String) {
    setLoginInProgress(true)

    FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val user = task.result?.user
                if (user != null) {
                    if (user.isEmailVerified) {
                        // ✅ Email verified - proceed with login
                        onLoginSuccess(user)
                    } else {
                        // ❌ Email not verified - show dialog
                        setLoginInProgress(false)
                        showUnverifiedEmailDialog(user) // Pass the user object
                    }
                } else {
                    setLoginInProgress(false)
                    showMessage("Login failed: User not found")
                }
            } else {
                setLoginInProgress(false)
                val errorMessage = task.exception?.message ?: "Login failed"
                onLoginFailure(errorMessage)
            }
        }
}

private fun showUnverifiedEmailDialog(user: FirebaseUser) {
    AlertDialog.Builder(this)
        .setTitle("Email Not Verified")
        .setMessage("Please verify your email address (${user.email}) before logging in. Check your inbox for the verification link.")
        .setPositiveButton("Resend Verification") { dialog, _ ->
            resendVerificationEmail(user) // Use the simpler function
            dialog.dismiss()
        }
        .setNegativeButton("OK") { dialog, _ ->
            dialog.dismiss()
        }
        .setNeutralButton("Open Email App") { dialog, _ ->
            openEmailApp()
            dialog.dismiss()
        }
        .show()
}

    private fun resendVerificationEmail(user: FirebaseUser) {
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    showMessage("Verification email sent to ${user.email}")
                    Log.d("LoginActivity", "Verification email resent to: ${user.email}")
                } else {
                    showMessage("Failed to send verification email")
                    Log.e("LoginActivity", "Failed to resend verification email")
                }
            }
    }
    
private fun openEmailApp() {
    try {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_APP_EMAIL)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    } catch (e: Exception) {
        showMessage("No email app found")
    }
}

    private fun onLoginSuccess(user: FirebaseUser) {
    // Ensure admin mode is disabled for regular login
    creditManager.setAdminMode(false)
    
    // Initialize user session and credits
    creditManager.resetResumeCooldown()
    
    // Sync user credits from Firestore
    userManager.syncUserCredits { success, credits ->
        if (success) {
            Log.d("LoginActivity", "User credits synced: $credits")
        } else {
            Log.w("LoginActivity", "Failed to sync user credits")
        }
    }
    
    Log.d("LoginActivity", "✅ Login successful - UID: ${user.uid}, Email: ${user.email}")
    showMessage("Login successful!")
    
    proceedToMainActivity()
}

    private fun onLoginFailure(error: String?) {
        val errorMessage = error ?: "Login failed. Please check your credentials."
        showMessage(errorMessage)
        
        Log.e("LoginActivity", "Login failed: $errorMessage")
        
        // Clear password field on failure
        binding.etLoginPassword.text?.clear()
        binding.etLoginPassword.requestFocus()
    }

    

    private fun setLoginInProgress(inProgress: Boolean) {
        binding.btnLogin.isEnabled = !inProgress
        binding.btnLogin.text = if (inProgress) "Logging in..." else "Login"
        binding.btnGoToRegister.isEnabled = !inProgress
        binding.btnForgotPassword.isEnabled = !inProgress
        
        // Disable input fields during login process
        binding.etLoginEmail.isEnabled = !inProgress
        binding.etLoginPassword.isEnabled = !inProgress
    }

    private fun proceedToMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
