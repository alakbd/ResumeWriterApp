package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.facebook.share.widget.LikeView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.alakdb.resumewriter.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var userManager: UserManager
    private lateinit var creditManager: CreditManager
    private lateinit var firebaseAuth: FirebaseAuth

    // Track login state to prevent multiple attempts
    private var isLoginInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize managers and services
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        firebaseAuth = FirebaseAuth.getInstance()

        Log.d("LoginActivity", "=== LOGIN ACTIVITY STARTED ===")
        userManager.debugUserState()
        creditManager.debugCreditState()

        // Check if already logged in with PROPER validation
        checkExistingAuthState()

        setupClickListeners()
        setupFacebookLike()
    }

        private fun setupFacebookLike() {
        val likeView = findViewById<LikeView>(R.id.like_view)
        
        // Replace with your Facebook Page ID or username
        likeView.setObjectIdAndType(
            "61583995713342",  // Your actual Facebook Page ID
            LikeView.ObjectType.PAGE
        )
        
        // Customize the LikeView appearance
        likeView.setLikeViewStyle(LikeView.Style.STANDARD)
        likeView.setAuxiliaryViewPosition(LikeView.AuxiliaryViewPosition.INLINE)
        likeView.setHorizontalAlignment(LikeView.HorizontalAlignment.CENTER)
        
        // Optional: Set foreground color to match your app theme
        likeView.setForegroundColor(-1) // -1 for white foreground
    }
}
    private fun checkExistingAuthState() {
        Log.d("LoginActivity", "🔄 Checking existing auth state...")
        
        val firebaseUser = firebaseAuth.currentUser
        val localUid = userManager.getCurrentUserId()
        
        Log.d("LoginActivity", "Firebase User: ${firebaseUser?.uid ?: "NULL"}")
        Log.d("LoginActivity", "Local UID: $localUid")
        Log.d("LoginActivity", "UserManager isUserLoggedIn(): ${userManager.isUserLoggedIn()}")

        when {
            // Case 1: Both Firebase and local data agree - user is logged in AND VERIFIED
            firebaseUser != null && localUid != null && firebaseUser.uid == localUid -> {
                if (firebaseUser.isEmailVerified) {
                    Log.d("LoginActivity", "✅ Valid verified session found")
                    proceedToMainActivity()
                } else {
                    Log.w("LoginActivity", "🚫 User exists but email not verified - blocking access")
                    // Sign out and clear data to enforce verification
                    firebaseAuth.signOut()
                    userManager.clearUserData()
                    creditManager.clearCreditData()
                    showVerificationRequiredDialog(firebaseUser.email)
                }
            }
            
            // Case 2: Firebase user exists but no local data - recover state ONLY IF VERIFIED
            firebaseUser != null && localUid == null -> {
                if (firebaseUser.isEmailVerified) {
                    Log.w("LoginActivity", "🔄 Recovering local state from verified Firebase user")
                    userManager.saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                    proceedToMainActivity()
                } else {
                    Log.w("LoginActivity", "🚫 Firebase user not verified - blocking access")
                    firebaseAuth.signOut()
                    showVerificationRequiredDialog(firebaseUser.email)
                }
            }
            
            // Case 3: Local data exists but no Firebase user - clear stale data
            firebaseUser == null && localUid != null -> {
                Log.w("LoginActivity", "🗑️ Clearing stale local data")
                userManager.clearUserData()
                creditManager.clearCreditData()
                // Stay on login screen
            }
            
            // Case 4: No auth data at all - stay on login screen
            else -> {
                Log.d("LoginActivity", "❌ No valid session - showing login form")
                // Continue showing login UI
            }
        }
    }
    
    private fun setupClickListeners() {
        binding.btnLogin.setOnClickListener {
            if (isLoginInProgress) {
                Log.d("LoginActivity", "⚠️ Login already in progress - ignoring click")
                return@setOnClickListener
            }
            
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
                    Log.d("LoginActivity", "✅ Password reset email sent to: $email")
                } else {
                    val errorMessage = task.exception?.message ?: "Unknown error occurred"
                    showMessage("Failed to send reset email: $errorMessage")
                    Log.e("LoginActivity", "❌ Password reset failed: $errorMessage")
                }
            }
    }

    private fun attemptLogin(email: String, password: String) {
        if (isLoginInProgress) {
            Log.w("LoginActivity", "⚠️ Login already in progress - ignoring duplicate attempt")
            return
        }
        
        setLoginInProgress(true)
        isLoginInProgress = true

        Log.d("LoginActivity", "🔐 Attempting STRICT login for: ${email.take(5)}...")

        // ⭐⭐⭐ USE USERMANAGER'S STRICT LOGIN (WILL BLOCK UNVERIFIED USERS)
        userManager.loginUser(email, password) { success, message ->
            isLoginInProgress = false
            setLoginInProgress(false)
            
            if (success) {
                // User is verified and properly logged in
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    Log.d("LoginActivity", "✅ STRICT login successful for verified user: ${user.uid}")
                    completeLoginProcess(user)
                } else {
                    Log.e("LoginActivity", "❌ Login succeeded but user is null")
                    showMessage("Login successful but user data missing")
                }
            } else {
                // Login failed or user not verified
                handleLoginFailure(message)
            }
        }
    }

    private fun completeLoginProcess(user: FirebaseUser) {
        Log.d("LoginActivity", "🎯 Completing login process for verified user: ${user.uid}")
        
        // CRITICAL: Save user data (should already be done by UserManager)
        userManager.saveUserDataLocally(user.email ?: "", user.uid)
        
        // Ensure admin mode is disabled
        creditManager.setAdminMode(false)
        creditManager.resetResumeCooldown()
        
        Log.d("LoginActivity", "✅ Local data saved - starting synchronization")
        
        showMessage("Login successful! Syncing data...")
        
        // Use optimized synchronization
        optimizedSynchronizeAndProceed()
    }

    private fun optimizedSynchronizeAndProceed() {
        Log.d("LoginActivity", "🔄 Starting optimized synchronization...")
        
        // Single sync call instead of multiple force syncs
        userManager.syncUserCredits { success, credits ->
            if (success) {
                Log.d("LoginActivity", "✅ Sync successful - credits: $credits")
                userManager.updateLocalCredits(credits ?: 0)
            } else {
                Log.w("LoginActivity", "⚠️ Sync failed but proceeding with cached data")
            }
            
            // Always proceed to main activity
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                proceedToMainActivity()
            }, 500)
        }
    }

    private fun handleLoginFailure(errorMessage: String?) {
        val message = errorMessage ?: "Login failed. Please check your credentials."
        showMessage(message)
        Log.e("LoginActivity", "❌ Login failed: $message")
        
        // Clear password field on failure
        binding.etLoginPassword.text?.clear()
        binding.etLoginPassword.requestFocus()
    }

    private fun showVerificationRequiredDialog(email: String?) {
        AlertDialog.Builder(this)
            .setTitle("Email Verification Required")
            .setMessage("Please verify your email address ($email) before logging in. Check your inbox for the verification link.")
            .setPositiveButton("Resend Verification") { dialog, _ ->
                resendVerificationEmail()
                dialog.dismiss()
            }
            .setNegativeButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Open Email App") { dialog, _ ->
                openEmailApp()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun resendVerificationEmail() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            user.sendEmailVerification()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        showMessage("Verification email sent to ${user.email}")
                        Log.d("LoginActivity", "✅ Verification email resent to: ${user.email}")
                    } else {
                        showMessage("Failed to send verification email")
                        Log.e("LoginActivity", "❌ Failed to resend verification email")
                    }
                }
        } else {
            showMessage("No user found. Please register first.")
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

    private fun setLoginInProgress(inProgress: Boolean) {
        binding.btnLogin.isEnabled = !inProgress
        binding.btnLogin.text = if (inProgress) "Logging in..." else "Login"
        binding.btnGoToRegister.isEnabled = !inProgress
        binding.btnForgotPassword.isEnabled = !inProgress
        
        binding.etLoginEmail.isEnabled = !inProgress
        binding.etLoginPassword.isEnabled = !inProgress
    }

    private fun proceedToMainActivity() {
        Log.d("LoginActivity", "🎯 Navigating to MainActivity...")
        
        // Final verification
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val userManagerUid = userManager.getCurrentUserId()
        
        Log.d("LoginActivity", "Final Check - Firebase: ${firebaseUser?.uid ?: "NULL"}")
        Log.d("LoginActivity", "Final Check - UserManager: $userManagerUid")
        Log.d("LoginActivity", "Final Check - Verified: ${firebaseUser?.isEmailVerified ?: false}")

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        
        Log.d("LoginActivity", "✅ LoginActivity finished")
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        Log.d("LoginActivity", "🔄 LoginActivity resumed")
        
        // Reset login state when activity resumes
        isLoginInProgress = false
        setLoginInProgress(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("LoginActivity", "💀 LoginActivity destroyed")
    }
}
