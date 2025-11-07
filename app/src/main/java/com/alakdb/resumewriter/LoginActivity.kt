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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase

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
    }
    
    private fun checkExistingAuthState() {
        Log.d("LoginActivity", "🔄 Checking existing auth state...")
        
        val firebaseUser = firebaseAuth.currentUser
        val localUid = userManager.getCurrentUserId()
        
        Log.d("LoginActivity", "Firebase User: ${firebaseUser?.uid ?: "NULL"}")
        Log.d("LoginActivity", "Local UID: $localUid")
        Log.d("LoginActivity", "UserManager isUserLoggedIn(): ${userManager.isUserLoggedIn()}")

        when {
            // Case 1: Both Firebase and local data agree - user is logged in
            firebaseUser != null && localUid != null && firebaseUser.uid == localUid -> {
                Log.d("LoginActivity", "✅ Valid existing session found")
                if (firebaseUser.isEmailVerified) {
                    proceedToMainActivity()
                } else {
                    Log.w("LoginActivity", "⚠️ User exists but email not verified")
                    showUnverifiedEmailDialog(firebaseUser)
                }
            }
            
            // Case 2: Firebase user exists but no local data - recover state
            firebaseUser != null && localUid == null -> {
                Log.w("LoginActivity", "🔄 Recovering local state from Firebase")
                userManager.saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                if (firebaseUser.isEmailVerified) {
                    proceedToMainActivity()
                } else {
                    showUnverifiedEmailDialog(firebaseUser)
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

    private fun checkAndUpdateEmailVerification(user: FirebaseUser) {
        val userId = user.uid
        
        user.reload().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val isVerified = user.isEmailVerified
                
                Firebase.firestore.collection("users").document(userId)
                    .update(
                        "emailVerified", isVerified, 
                        "lastUpdated", System.currentTimeMillis()
                    )
                    .addOnSuccessListener {
                        Log.d("Login", "✅ Updated verification status: $isVerified")
                    }
                    .addOnFailureListener { e ->
                        Log.e("Login", "❌ Failed to update verification status", e)
                    }
            } else {
                Log.e("Login", "❌ Failed to reload user for verification check", task.exception)
            }
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

        Log.d("LoginActivity", "🔐 Attempting login for: ${email.take(5)}...")

        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                isLoginInProgress = false
                
                if (task.isSuccessful) {
                    val user = task.result?.user
                    if (user != null) {
                        Log.d("LoginActivity", "✅ Firebase authentication successful: ${user.uid}")
                        handleSuccessfulAuth(user, email)
                    } else {
                        setLoginInProgress(false)
                        Log.e("LoginActivity", "❌ Firebase auth succeeded but user is null")
                        showMessage("Login failed: User data missing")
                    }
                } else {
                    setLoginInProgress(false)
                    handleLoginFailure(task.exception)
                }
            }
    }

    private fun handleSuccessfulAuth(user: FirebaseUser, email: String) {
        // Capture login network info
        captureLoginNetworkInfo(user.uid)
        
        if (user.isEmailVerified) {
            Log.d("LoginActivity", "✅ Email verified - proceeding with login")
            completeLoginProcess(user)
        } else {
            Log.w("LoginActivity", "⚠️ Email not verified - showing dialog")
            setLoginInProgress(false)
            showUnverifiedEmailDialog(user)
        }
    }

    private fun captureLoginNetworkInfo(userId: String) {
        try {
            Thread {
                try {
                    val publicIp = fetchPublicIpWithFallback()
                    val currentTime = System.currentTimeMillis()
                    
                    val updates = hashMapOf<String, Any>(
                        "lastLoginIp" to publicIp,
                        "lastLogin" to currentTime,
                        "lastActive" to currentTime,
                        "lastUpdated" to currentTime
                    )
                    
                    Firebase.firestore.collection("users").document(userId)
                        .update(updates)
                        .addOnSuccessListener {
                            Log.d("Login", "✅ Login network info updated - IP: $publicIp")
                        }
                        .addOnFailureListener { e ->
                            Log.e("Login", "❌ Failed to update login network info", e)
                        }
                } catch (e: Exception) {
                    Log.e("Login", "❌ Error capturing network info", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e("Login", "❌ Error starting network info thread", e)
        }
    }

    private fun fetchPublicIpWithFallback(): String {
        return try {
            val ipServices = listOf(
                "https://api.ipify.org",
                "https://api64.ipify.org", 
                "https://checkip.amazonaws.com"
            )
            
            for (service in ipServices) {
                try {
                    val connection = java.net.URL(service).openConnection()
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.getInputStream()))
                    val publicIp = reader.readLine()?.trim()
                    reader.close()
                    
                    if (!publicIp.isNullOrBlank() && publicIp != "0.0.0.0") {
                        return publicIp
                    }
                } catch (e: Exception) {
                    // Try next service
                }
            }
            "unknown"
        } catch (e: Exception) {
            "error"
        }
    }

    private fun completeLoginProcess(user: FirebaseUser) {
        Log.d("LoginActivity", "🎯 Completing login process for: ${user.uid}")
        
        // CRITICAL: Save user data FIRST
        userManager.saveUserDataLocally(user.email ?: "", user.uid)
        
        // Update verification status
        checkAndUpdateEmailVerification(user)
        
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
                creditManager.updateLocalCredits(credits ?: 0)
            } else {
                Log.w("LoginActivity", "⚠️ Sync failed but proceeding with cached data")
            }
            
            // Always proceed to main activity
            Handler().postDelayed({
                proceedToMainActivity()
            }, 500) // Small delay to ensure data persistence
        }
    }

    private fun handleLoginFailure(exception: Exception?) {
        val errorMessage = when (exception) {
            is FirebaseAuthInvalidUserException -> "No account found with this email"
            is FirebaseAuthInvalidCredentialsException -> "Invalid password"
            else -> exception?.message ?: "Login failed. Please check your credentials."
        }
        
        showMessage(errorMessage)
        Log.e("LoginActivity", "❌ Login failed: $errorMessage")
        
        // Clear password field on failure
        binding.etLoginPassword.text?.clear()
        binding.etLoginPassword.requestFocus()
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
                // Sign out since email isn't verified
                FirebaseAuth.getInstance().signOut()
                userManager.clearUserData()
            }
            .setNeutralButton("Open Email App") { dialog, _ ->
                openEmailApp()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun resendVerificationEmail(user: FirebaseUser) {
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
        Log.d("LoginActivity", "Final Check - Consistent: ${firebaseUser?.uid == userManagerUid}")

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
        // Clean up any pending operations if needed
    }
}
