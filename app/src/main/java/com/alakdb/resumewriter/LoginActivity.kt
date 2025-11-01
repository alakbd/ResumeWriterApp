package com.alakdb.resumewriter

import android.content.Context
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

        // NEW: Debug initial state
        Log.d("LoginActivity", "=== LOGIN ACTIVITY STARTED ===")
        userManager.debugUserState()
        creditManager.debugCreditState()

        // Clean up stale data
        cleanupStaleData()

        // Check if already logged in
        if (userManager.isUserLoggedIn()) {
            Log.d("LoginActivity", "User already logged in - proceeding to main")
            proceedToMainActivity()
            return
        }

        setupClickListeners()
    }
    
    private fun cleanupStaleData() {
        val firebaseUser = firebaseAuth.currentUser
        val localUid = userManager.getCurrentUserId()
        
        Log.d("LoginActivity", "🔄 Cleanup check - Firebase: ${firebaseUser?.uid ?: "NULL"}, Local: ${localUid ?: "NULL"}")
        
        // Only clear if we have local data but no Firebase user
        if (firebaseUser == null && localUid != null) {
            Log.w("LoginActivity", "⚠️ Clearing stale local data - no matching Firebase user")
            userManager.clearUserData()
            creditManager.clearCreditData()
        } else if (firebaseUser != null && localUid != null) {
            Log.d("LoginActivity", "✅ Data consistent - Firebase and local UID match")
        }
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
                            showUnverifiedEmailDialog(user)
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
                resendVerificationEmail(user)
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



    // NEW: Enhanced login success handling with synchronization
    private fun onLoginSuccess(user: FirebaseUser) {
        Log.d("LOGIN_DEBUG", "=== START onLoginSuccess ===")
        Log.d("LOGIN_DEBUG", "Firebase User UID: ${user.uid}")
        Log.d("LOGIN_DEBUG", "Firebase User Email: ${user.email}")
        
        // ⚠️ CRITICAL: Save user data to UserManager
        Log.d("LOGIN_DEBUG", "Calling saveUserDataLocally...")
        userManager.saveUserDataLocally(user.email ?: "", user.uid)
        
        // Verify it was saved
        val savedUid = userManager.getCurrentUserId()
        val savedEmail = userManager.getCurrentUserEmail()
        Log.d("LOGIN_DEBUG", "After save - Local UID: $savedUid")
        Log.d("LOGIN_DEBUG", "After save - Local Email: $savedEmail")
        
        // Double-check Firebase state
        val currentFirebaseUser = FirebaseAuth.getInstance().currentUser
        Log.d("LOGIN_DEBUG", "Current Firebase User: ${currentFirebaseUser?.uid ?: "NULL"}")
        
        // Ensure admin mode is disabled for regular login
        creditManager.setAdminMode(false)
        
        // NEW: Initialize credit data
        creditManager.resetResumeCooldown()
        
        Log.d("LOGIN_DEBUG", "✅ Login successful - starting synchronization")
        Log.d("LOGIN_DEBUG", "=== END onLoginSuccess ===")
        
        showMessage("Login successful! Syncing data...")
        
        // NEW: Use the enhanced synchronization flow
        synchronizeAndProceed()
    }

    // NEW: Enhanced synchronization method
    private fun synchronizeAndProceed() {
        Log.d("LoginActivity", "🔄 Starting data synchronization...")
        
        // Force sync user data first
        userManager.forceSyncWithFirebase { userSuccess ->
            if (userSuccess) {
                Log.d("LoginActivity", "✅ User data synced successfully")
                
                // Then force sync credits
                creditManager.forceSyncWithFirebase { creditSuccess, credits ->
                    if (creditSuccess) {
                        Log.d("LoginActivity", "✅ Credits synced successfully: $credits")
                        showMessage("Data synchronized successfully!")
                    } else {
                        Log.w("LoginActivity", "⚠️ Credit sync failed but proceeding")
                        showMessage("Login successful - using cached data")
                    }
                    
                    // Proceed to main activity regardless of credit sync result
                    proceedToMainActivity()
                }
            } else {
                Log.e("LoginActivity", "❌ User data sync failed")
                showMessage("Login successful but sync incomplete")
                
                // Still proceed to main activity
                proceedToMainActivity()
            }
        }
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

    // UPDATED: Enhanced navigation with better logging
    private fun proceedToMainActivity() {
        Log.d("LOGIN_NAVIGATION", "=== NAVIGATING TO MAIN ACTIVITY ===")
        
        // Final state verification
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val userManagerUid = userManager.getCurrentUserId()
        
        Log.d("LOGIN_NAVIGATION", "Final State - Firebase: ${firebaseUser?.uid ?: "NULL"}")
        Log.d("LOGIN_NAVIGATION", "Final State - UserManager: $userManagerUid")
        Log.d("LOGIN_NAVIGATION", "Final State - User Data Persisted: ${userManager.isUserDataPersisted()}")
        Log.d("LOGIN_NAVIGATION", "Final State - Credit Data Persisted: ${creditManager.isCreditDataPersisted()}")
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        
        Log.d("LOGIN_NAVIGATION", "LoginActivity finished")
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // NEW: Handle network connectivity changes
    override fun onResume() {
        super.onResume()
        
        // Auto-resync if user is logged in but data might be stale
        if (userManager.isUserLoggedIn()) {
            Log.d("LoginActivity", "Resuming - auto-resyncing if needed")
            userManager.autoResyncIfNeeded()
        }
    }

    // NEW: Emergency recovery method
    private fun attemptEmergencyRecovery() {
        Log.w("LoginActivity", "🆘 Attempting emergency recovery...")
        
        val success = userManager.emergencySyncWithFirebase()
        if (success) {
            showMessage("Recovery successful - proceeding to main")
            proceedToMainActivity()
        } else {
            showMessage("Recovery failed - please login again")
            userManager.clearUserData()
            creditManager.clearCreditData()
        }
    }
}
