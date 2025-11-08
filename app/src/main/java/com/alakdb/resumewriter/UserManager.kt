package com.alakdb.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.provider.Settings
import android.os.Build
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class UserManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val lifecycleScope = CoroutineScope(Dispatchers.IO)

    // Synchronization control
    private val isSyncing = AtomicBoolean(false)

    companion object {
        private const val USER_EMAIL_KEY = "user_email"
        private const val USER_ID_KEY = "user_id"
        private const val IS_REGISTERED_KEY = "is_registered"
        private const val AVAILABLE_CREDITS_KEY = "available_credits"
        private const val LAST_SYNC_TIME_KEY = "last_sync_time"
        private const val SYNC_COOLDOWN_MS = 30000L // 30 seconds between syncs
        private const val TAG = "UserManager"
    }

    // -----------------------
    // VERIFICATION ENFORCEMENT METHODS
    // -----------------------

    /**
     * ⭐⭐⭐ STRICT CHECK for resume generation
     * Use this before allowing any CV generation
     */
    fun canGenerateResume(): Boolean {
        val user = auth.currentUser
        val isLoggedIn = isUserLoggedIn()
        
        val canGenerate = user != null && isLoggedIn && user.isEmailVerified
        
        Log.d(TAG, "🔥 Resume Access Check - " +
              "User: ${user?.uid?.take(8)}..., " +
              "LoggedIn: $isLoggedIn, " +
              "Verified: ${user?.isEmailVerified}, " +
              "CanGenerate: $canGenerate")
        
        return canGenerate
    }

    /**
     * ⭐⭐⭐ Get verification status for UI
     */
    fun getVerificationStatus(): VerificationStatus {
        val user = auth.currentUser
        return when {
            user == null -> VerificationStatus.NOT_LOGGED_IN
            !user.isEmailVerified -> VerificationStatus.NOT_VERIFIED  
            else -> VerificationStatus.VERIFIED
        }
    }

    enum class VerificationStatus {
        NOT_LOGGED_IN, NOT_VERIFIED, VERIFIED
    }

    /**
     * ⭐⭐⭐ Check if user needs verification redirect
     */
    fun requiresVerification(): Boolean {
        val user = auth.currentUser
        return user != null && !user.isEmailVerified && isUserLoggedIn()
    }

    // -----------------------
    // SIMPLE AUTH STATE MANAGEMENT
    // -----------------------

    /**
     * Simple and reliable user login check
     */
    fun isUserLoggedIn(): Boolean {
        return try {
            val firebaseUser = auth.currentUser
            val localUid = prefs.getString(USER_ID_KEY, null)
            
            // User is logged in if we have a Firebase user AND local data
            val isLoggedIn = firebaseUser != null && localUid != null && firebaseUser.uid == localUid
            
            Log.d(TAG, "Auth Check - Firebase: ${firebaseUser != null}, Local: ${localUid != null}, LoggedIn: $isLoggedIn")
            
            isLoggedIn
        } catch (e: Exception) {
            Log.e(TAG, "Auth check failed: ${e.message}")
            // Fallback to local data
            !prefs.getString(USER_ID_KEY, null).isNullOrBlank()
        }
    }

    /**
     * Check if user data is properly persisted
     */
    fun isUserDataPersisted(): Boolean {
        val userId = prefs.getString(USER_ID_KEY, null)
        val email = prefs.getString(USER_EMAIL_KEY, null)
        return !userId.isNullOrBlank() && !email.isNullOrBlank()
    }

    /**
     * Check if fresh install
     */
    fun isFreshInstall(): Boolean {
        return auth.currentUser == null && prefs.getString(USER_ID_KEY, null) == null
    }

    // -----------------------
    // REGISTRATION (WITH AUTO-SIGNOUT)
    // -----------------------

    fun registerUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        Log.d(TAG, "Starting registration for $email")

        if (password.length < 6) {
            onComplete(false, "Password must be at least 6 characters")
            return
        }

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = task.result?.user
                    val uid = user?.uid

                    if (uid == null) {
                        onComplete(false, "Registration failed: No user ID")
                        return@addOnCompleteListener
                    }

                    Log.d(TAG, "Firebase registration successful: $uid")

                    // ⭐⭐⭐ COLLECT DEVICE INFO (KEEP THIS - IT'S WORKING!)
                    val deviceId = getDeviceId()
                    val deviceInfo = getDeviceInfo()

                    // Prepare user data
                    val userData = hashMapOf(
                        "email" to email,
                        "uid" to uid,
                        "availableCredits" to 3,
                        "usedCredits" to 0,
                        "totalCreditsEarned" to 3,
                        "createdAt" to System.currentTimeMillis(),
                        "lastActive" to System.currentTimeMillis(),
                        "isActive" to true,
                        "emailVerified" to false,
                        "deviceId" to deviceId, // ⭐⭐⭐ DEVICE ID PRESERVED
                        "deviceInfo" to deviceInfo, // ⭐⭐⭐ DEVICE INFO PRESERVED
                        "registrationIp" to "pending", // ⭐⭐⭐ WILL BE UPDATED
                        "lastLoginIp" to "pending"
                    )

                    // Save to Firestore
                    db.collection("users").document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Log.d(TAG, "Firestore document created for $email")
                            
                            // Save locally
                            saveUserDataLocally(email, uid)
                            
                            // ⭐⭐⭐ UPDATE IP INFO (KEEP THIS - IT'S WORKING!)
                            updateUserIpInfo(uid, email, true)
                            
                            // Send verification email
                            user.sendEmailVerification()
                                .addOnCompleteListener { emailTask ->
                                    if (emailTask.isSuccessful) {
                                        Log.d(TAG, "Verification email sent")
                                        
                                        // ⭐⭐⭐ CRITICAL: AUTO-SIGNOUT AFTER REGISTRATION
                                        lifecycleScope.launch {
                                            kotlinx.coroutines.delay(2000) // Let IP capture complete
                                            auth.signOut()
                                            clearUserData()
                                            Log.d(TAG, "🚪 Auto-signed out after registration")
                                        }
                                        
                                        onComplete(true, "Registration successful! Please verify your email before logging in.")
                                    } else {
                                        Log.w(TAG, "Verification email failed")
                                        
                                        // Still enforce signout even if email failed
                                        auth.signOut()
                                        clearUserData()
                                        onComplete(true, "Account created but verification email failed. Please use 'Resend Verification' from login.")
                                    }
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Firestore save failed: ${e.message}")
                            user?.delete()?.addOnCompleteListener {
                                onComplete(false, "Database error: ${e.message}")
                            }
                        }

                } else {
                    val error = when (task.exception) {
                        is FirebaseAuthUserCollisionException -> "Email already registered"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid email format"
                        is FirebaseAuthWeakPasswordException -> "Password is too weak"
                        else -> "Registration failed: ${task.exception?.message}"
                    }
                    Log.e(TAG, "Registration failed: $error")
                    onComplete(false, error)
                }
            }
    }

    // -----------------------
    // ⭐⭐⭐ STRICT LOGIN (BLOCKS UNVERIFIED USERS)
    // -----------------------

    fun loginUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        Log.d(TAG, "🔐 STRICT LOGIN ATTEMPT for: $email")
        
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        Log.d(TAG, "✅ Firebase auth success - checking verification...")
                        
                        // ⭐⭐⭐ CRITICAL: STRICT VERIFICATION BLOCK
                        if (!user.isEmailVerified) {
                            Log.w(TAG, "🚫 BLOCKED: Email not verified for ${user.email}")
                            
                            // IMMEDIATE SIGN OUT - User cannot proceed
                            auth.signOut()
                            clearUserData()
                            
                            val errorMsg = "Please verify your email address before logging in. Check your inbox for the verification link."
                            onComplete(false, errorMsg)
                            return@addOnCompleteListener
                        }

                        // ✅ ONLY verified users reach here
                        Log.d(TAG, "✅ Email verified - proceeding with login")
                        
                        // Save user data locally
                        saveUserDataLocally(user.email ?: "", user.uid)
                        
                        // ⭐⭐⭐ UPDATE IP INFO ON LOGIN (KEEP THIS!)
                        updateUserIpInfo(user.uid, email, false)
                        
                        // Sync credits
                        syncUserCredits { success, _ ->
                            Log.d(TAG, "🎉 Verified user login completed: $email")
                            onComplete(true, null)
                        }
                    } else {
                        onComplete(false, "Login failed: No user data")
                    }
                } else {
                    val error = when (task.exception) {
                        is FirebaseAuthInvalidUserException -> "No account found with this email"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid password"
                        else -> "Login failed: ${task.exception?.message}"
                    }
                    Log.e(TAG, "Login failed: $error")
                    onComplete(false, error)
                }
            }
    }

    // -----------------------
    // EMAIL VERIFICATION (SIMPLE)
    // -----------------------

    fun sendEmailVerification(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, "No user logged in")
            return
        }
        
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Verification email sent")
                    onComplete(true, null)
                } else {
                    val error = task.exception?.message ?: "Failed to send verification email"
                    Log.e(TAG, "Verification email failed: $error")
                    onComplete(false, error)
                }
            }
    }

    fun resendVerificationEmail(onComplete: (Boolean, String?) -> Unit) {
        sendEmailVerification(onComplete)
    }

    // -----------------------
    // CREDIT SYNC (SIMPLE AND RELIABLE)
    // -----------------------

    fun syncUserCredits(onComplete: (Boolean, Int?) -> Unit) {
        // Prevent multiple simultaneous syncs
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "Sync already in progress")
            onComplete(true, getCachedCredits())
            return
        }

        val userId = getCurrentUserId()
        if (userId == null) {
            isSyncing.set(false)
            onComplete(false, null)
            return
        }

        // Cooldown check
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        if (System.currentTimeMillis() - lastSync < SYNC_COOLDOWN_MS) {
            Log.d(TAG, "Sync skipped - too recent")
            isSyncing.set(false)
            onComplete(true, getCachedCredits())
            return
        }

        Log.d(TAG, "Starting credit sync for user: ${userId.take(8)}...")

        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                isSyncing.set(false)
                
                if (document.exists()) {
                    val credits = document.getLong("availableCredits")?.toInt() ?: 0
                    prefs.edit().apply {
                        putInt(AVAILABLE_CREDITS_KEY, credits)
                        putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis())
                        apply()
                    }
                    Log.d(TAG, "Credits synced: $credits")
                    onComplete(true, credits)
                } else {
                    Log.e(TAG, "User document missing")
                    onComplete(false, null)
                }
            }
            .addOnFailureListener { e ->
                isSyncing.set(false)
                Log.e(TAG, "Credit sync failed: ${e.message}")
                onComplete(false, null)
            }
    }

    // -----------------------
    // EMERGENCY SYNC (SIMPLE)
    // -----------------------

    fun emergencySyncWithFirebase(): Boolean {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                Log.d(TAG, "Emergency sync successful")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Emergency sync failed: ${e.message}")
            false
        }
    }

    fun forceSyncWithFirebase(onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "Forcing user data sync")
        prefs.edit().remove(LAST_SYNC_TIME_KEY).apply()
        syncUserCredits { success, _ ->
            onComplete(success)
        }
    }

    fun autoResyncIfNeeded() {
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        val needsSync = System.currentTimeMillis() - lastSync > SYNC_COOLDOWN_MS
        
        if (needsSync && isUserLoggedIn()) {
            Log.d(TAG, "Auto-resyncing user data")
            syncUserCredits { success, _ ->
                if (success) {
                    Log.d(TAG, "Auto-resync successful")
                } else {
                    Log.w(TAG, "Auto-resync failed")
                }
            }
        }
    }

    // -----------------------
    // ⭐⭐⭐ IP CAPTURE (KEEP THIS - IT'S WORKING!)
    // -----------------------

    private fun updateUserIpInfo(uid: String, email: String, isRegistration: Boolean = false) {
        lifecycleScope.launch {
            try {
                val publicIp = fetchPublicIpWithFallback()
                
                val updateData = hashMapOf<String, Any>(
                    "ipAddress" to publicIp,
                    "lastLoginIp" to publicIp,
                    "lastActive" to System.currentTimeMillis(),
                    "lastUpdated" to System.currentTimeMillis()
                )

                // ⭐⭐⭐ PRESERVE REGISTRATION IP FOR NEW USERS
                if (isRegistration) {
                    updateData["registrationIp"] = publicIp
                    Log.d(TAG, "📍 Registration IP captured: $publicIp")
                } else {
                    Log.d(TAG, "📍 Login IP captured: $publicIp")
                }

                db.collection("users").document(uid)
                    .update(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ IP info updated successfully")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ IP update failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "❌ IP capture failed: ${e.message}")
            }
        }
    }

    private suspend fun fetchPublicIpWithFallback(): String = withContext(Dispatchers.IO) {
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
                    return@withContext publicIp
                }
            } catch (e: Exception) {
                // Try next service
            }
        }
        return@withContext "unknown"
    }

    // -----------------------
    // ⭐⭐⭐ DEVICE INFO (KEEP THIS - IT'S WORKING!)
    // -----------------------

    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } 
                ?: "android_${System.currentTimeMillis()}"
        } catch (e: Exception) {
            "error_device_${System.currentTimeMillis()}"
        }
    }

    private fun getDeviceInfo(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        } catch (e: Exception) {
            "Unknown Device"
        }
    }

    // -----------------------
    // UTILITY METHODS
    // -----------------------

    fun saveUserDataLocally(email: String, uid: String) {
        prefs.edit().apply {
            putString(USER_EMAIL_KEY, email)
            putString(USER_ID_KEY, uid)
            putBoolean(IS_REGISTERED_KEY, true)
            apply()
        }
        Log.d(TAG, "User data saved: ${email.take(5)}...")
    }

    fun getCurrentUserId(): String? {
        return auth.currentUser?.uid ?: prefs.getString(USER_ID_KEY, null)
    }

    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    fun getCurrentFirebaseUser() = auth.currentUser

    fun getCachedCredits(): Int {
        return prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    }

    fun updateLocalCredits(newCredits: Int) {
        prefs.edit().putInt(AVAILABLE_CREDITS_KEY, newCredits).apply()
        Log.d(TAG, "Local credits updated: $newCredits")
    }

    fun clearUserData() {
        prefs.edit().clear().apply()
        Log.d(TAG, "All user data cleared")
    }

    fun logout() {
        Log.d(TAG, "Logging out user")
        auth.signOut()
        clearUserData()
    }

    // -----------------------
    // DEBUG METHODS
    // -----------------------

    fun debugUserState() {
        val firebaseUser = auth.currentUser
        val localUid = getCurrentUserId()
        val localEmail = getCurrentUserEmail()
        
        Log.d(TAG, "=== USER MANAGER STATE ===")
        Log.d(TAG, "Firebase UID: ${firebaseUser?.uid ?: "null"}")
        Log.d(TAG, "Local UID: $localUid")
        Log.d(TAG, "Local Email: $localEmail")
        Log.d(TAG, "Logged In: ${isUserLoggedIn()}")
        Log.d(TAG, "Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
        Log.d(TAG, "Can Generate Resume: ${canGenerateResume()}")
        Log.d(TAG, "=== END USER STATE ===")
    }

    fun debugStoredData() {
        val allEntries = prefs.all
        Log.d(TAG, "=== STORED DATA ===")
        allEntries.forEach { (key, value) ->
            Log.d(TAG, "$key: $value")
        }
        Log.d(TAG, "=== END STORED DATA ===")
    }
}
