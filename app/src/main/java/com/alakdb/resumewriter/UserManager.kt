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
import kotlinx.coroutines.tasks.await
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class UserManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val lifecycleScope = CoroutineScope(Dispatchers.IO)

    // Synchronization control
    private val isSyncing = AtomicBoolean(false)
    private val lastAuthCheck = AtomicBoolean(false)

    companion object {
        private const val USER_EMAIL_KEY = "user_email"
        private const val USER_ID_KEY = "user_id"
        private const val IS_REGISTERED_KEY = "is_registered"
        private const val AVAILABLE_CREDITS_KEY = "available_credits"
        private const val LAST_SYNC_TIME_KEY = "last_sync_time"
        private const val AUTH_STATE_KEY = "auth_state_consistent"
        private const val SYNC_COOLDOWN_MS = 60000L // 60 seconds between syncs
        private const val TAG = "UserManager"
    }

    // -----------------------
    // STORAGE VALIDATION & DEBUG (FOR MAINACTIVITY)
    // -----------------------

    fun cacheCredits(credits: Int) {
        prefs.edit().putInt("cached_credits", credits).apply()
        Log.d(TAG, "Cached credits: $credits")
    }
    
    /**
     * 🔍 Validate that user data is properly persisted (Used by MainActivity)
     */
    fun isUserDataPersisted(): Boolean {
        val userId = prefs.getString(USER_ID_KEY, null)
        val email = prefs.getString(USER_EMAIL_KEY, null)
        val isRegistered = prefs.getBoolean(IS_REGISTERED_KEY, false)
        
        Log.d(TAG, "UserDataPersisted Check - UserID: ${userId?.take(8)}..., Email: ${email?.take(5)}..., Registered: $isRegistered")
        return !userId.isNullOrBlank() && !email.isNullOrBlank()
    }

    /**
     * 📊 Debug current user state (Used by MainActivity)
     */
    fun debugUserState() {
        val firebaseUser = auth.currentUser
        val localUid = getCurrentUserId()
        val localEmail = getCurrentUserEmail()
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        
        Log.d(TAG, "=== USER MANAGER STATE ===")
        Log.d(TAG, "Firebase UID: ${firebaseUser?.uid ?: "null"}")
        Log.d(TAG, "Local UID: $localUid")
        Log.d(TAG, "Local Email: $localEmail")
        Log.d(TAG, "Last Sync: ${if (lastSync > 0) "${(System.currentTimeMillis() - lastSync)/1000}s ago" else "Never"}")
        Log.d(TAG, "Storage Valid: ${isUserDataPersisted()}")
        Log.d(TAG, "Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
        Log.d(TAG, "Auth Consistent: ${prefs.getBoolean(AUTH_STATE_KEY, false)}")
        Log.d(TAG, "=== END USER STATE ===")
    }

    /**
     * 🗑️ Clear all user data (use on logout) - Used by MainActivity
     */
    fun clearUserData() {
        prefs.edit().apply {
            remove(USER_EMAIL_KEY)
            remove(USER_ID_KEY)
            remove(IS_REGISTERED_KEY)
            remove(AVAILABLE_CREDITS_KEY)
            remove(LAST_SYNC_TIME_KEY)
            remove(AUTH_STATE_KEY)
            apply()
        }
        Log.d(TAG, "🗑️ All user data cleared")
    }

    // -----------------------
    // AUTH STATE MANAGEMENT (FIXED)
    // -----------------------

    /**
     * RELIABLE user login check with proper state synchronization
     */
    fun isUserLoggedIn(): Boolean {
        return try {
            val firebaseUser = auth.currentUser
            val localUid = prefs.getString(USER_ID_KEY, null)
            val isConsistent = prefs.getBoolean(AUTH_STATE_KEY, false)

            // If we recently validated auth state, trust it
            if (lastAuthCheck.get() && isConsistent) {
                Log.d(TAG, "✅ Using cached auth state")
                return true
            }

            val firebaseExists = firebaseUser != null
            val localExists = !localUid.isNullOrBlank()

            Log.d(TAG, "Auth Check - Firebase: $firebaseExists, Local: $localExists, UID: ${localUid?.take(8)}")

            when {
                // Ideal case: Both Firebase and local data agree
                firebaseExists && localExists && firebaseUser.uid == localUid -> {
                    markAuthStateConsistent(true)
                    true
                }
                // Firebase exists but local doesn't - recover local state
                firebaseExists && !localExists -> {
                    Log.w(TAG, "🔄 Recovering local state from Firebase")
                    saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                    markAuthStateConsistent(true)
                    true
                }
                // Local exists but Firebase doesn't - could be offline or signed out
                localExists && !firebaseExists -> {
                    Log.w(TAG, "⚠️ Firebase user missing but local data exists")
                    // Try to restore Firebase state
                    attemptFirebaseStateRecovery()
                }
                // Neither exists - definitely logged out
                else -> {
                    markAuthStateConsistent(false)
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Auth check failed, using local fallback: ${e.message}")
            // Fallback to local data in case of errors
            !prefs.getString(USER_ID_KEY, null).isNullOrBlank()
        }
    }

    /**
     * Check if this is a fresh install (no user data at all) - Used by MainActivity
     */
    fun isFreshInstall(): Boolean {
        val hasUserData = prefs.contains(USER_ID_KEY) || 
                         prefs.contains(USER_EMAIL_KEY) || 
                         prefs.contains(IS_REGISTERED_KEY)
    
        val hasFirebaseUser = auth.currentUser != null
    
        Log.d(TAG, "Fresh install check - HasUserData: $hasUserData, HasFirebaseUser: $hasFirebaseUser")
    
        return !hasUserData && !hasFirebaseUser
    }

    /**
     * Attempt to recover Firebase auth state when local data exists
     */
    private fun attemptFirebaseStateRecovery(): Boolean {
        return try {
            // Check if Firebase might be initializing slowly
            val currentUser = auth.currentUser
            if (currentUser != null) {
                Log.d(TAG, "✅ Firebase state recovered")
                saveUserDataLocally(currentUser.email ?: "", currentUser.uid)
                markAuthStateConsistent(true)
                true
            } else {
                Log.w(TAG, "❌ Firebase recovery failed - user truly logged out")
                clearUserData() // Clean up inconsistent state
                markAuthStateConsistent(false)
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Firebase recovery error: ${e.message}")
            false
        }
    }

    /**
     * Mark auth state as consistent to prevent repeated checks
     */
    private fun markAuthStateConsistent(consistent: Boolean) {
        prefs.edit().putBoolean(AUTH_STATE_KEY, consistent).apply()
        lastAuthCheck.set(consistent)
        Log.d(TAG, "Auth state marked as consistent: $consistent")
    }

    // -----------------------
    // EMERGENCY SYNC & DATA RECOVERY (FOR MAINACTIVITY)
    // -----------------------

    /** Emergency sync - ensures UserManager is synchronized with Firebase */
    fun emergencySyncWithFirebase(): Boolean {
        return try {
            val firebaseUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                Log.e(TAG, "❌ Firebase access failed in emergency sync: ${e.message}")
                null
            }
            
            if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val email = firebaseUser.email ?: ""
                
                try {
                    saveUserDataLocally(email, uid)
                    markAuthStateConsistent(true)
                    Log.d(TAG, "✅ Emergency sync successful: ${uid.take(8)}...")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ saveUserDataLocally failed: ${e.message}")
                    false
                }
            } else {
                Log.w(TAG, "⚠️ No Firebase user for emergency sync")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Emergency sync completely failed: ${e.message}")
            false
        }
    }

    /**
     * 🆘 Force sync regardless of cooldown - Used by MainActivity
     */
    fun forceSyncWithFirebase(onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "🔄 Forcing user data sync")
        prefs.edit().remove(LAST_SYNC_TIME_KEY).apply() // Clear cooldown
        syncUserCredits { success, _ ->
            onComplete(success)
        }
    }

    /**
     * 🔄 Automatic re-sync when network connects - Used by MainActivity
     */
    fun autoResyncIfNeeded() {
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        val needsSync = System.currentTimeMillis() - lastSync > SYNC_COOLDOWN_MS
        
        if (needsSync && isUserLoggedIn()) {
            Log.d(TAG, "🔄 Auto-resyncing user data")
            syncUserCredits { success, _ ->
                if (success) {
                    Log.d(TAG, "✅ Auto-resync successful")
                } else {
                    Log.w(TAG, "⚠️ Auto-resync failed")
                }
            }
        }
    }

    // -----------------------
    // REGISTRATION METHOD (FIXED)
    // -----------------------

    fun registerUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        Log.d(TAG, "🔹 Starting registration for $email")

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
                        Log.e(TAG, "❌ Registration failed: UID null")
                        onComplete(false, "Registration failed: No user ID")
                        return@addOnCompleteListener
                    }

                    Log.d(TAG, "✅ Firebase registration successful. UID: $uid")

                    // Prepare comprehensive user data
                    val userData = mutableMapOf<String, Any>(
                        "email" to email,
                        "uid" to uid,
                        "availableCredits" to 3,
                        "usedCredits" to 0,
                        "totalCreditsEarned" to 3,
                        "createdAt" to System.currentTimeMillis(),
                        "lastActive" to System.currentTimeMillis(),
                        "isActive" to true,
                        "emailVerified" to false,
                        
                        // Device information
                        "deviceId" to getDeviceId(),
                        "deviceInfo" to getDeviceInfo(),
                        "userAgent" to getUserAgent(),
                        
                        // Initial IP (will be updated with real IP)
                        "ipAddress" to "pending",
                        "registrationIp" to "pending",
                        "lastLoginIp" to "pending",
                        "lastLogin" to System.currentTimeMillis(),
                        "lastUpdated" to System.currentTimeMillis()
                    )

                    // Save to Firestore
                    db.collection("users").document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Firestore document created for $email")

                            // Save user locally and mark consistent
                            saveUserDataLocally(email, uid)
                            markAuthStateConsistent(true)

                            // Update with real IP information
                            updateUserIpInfo(uid, email, true)

                            // Send verification email
                            user.sendEmailVerification()
                                .addOnCompleteListener { emailTask ->
                                    if (emailTask.isSuccessful) {
                                        Log.d(TAG, "📩 Verification email sent to $email")
                                        onComplete(true, "Registration successful! Please verify your email before logging in.")
                                    } else {
                                        Log.w(TAG, "⚠️ Verification email failed: ${emailTask.exception?.message}")
                                        onComplete(true, "Registration successful, but verification email could not be sent. You can resend it later.")
                                    }
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Firestore user save failed: ${e.message}")
                            // Clean up Firebase auth user if Firestore fails
                            user.delete().addOnCompleteListener {
                                Log.e(TAG, "Deleted Firebase user after Firestore failure.")
                                onComplete(false, "Database error during registration: ${e.message}")
                            }
                        }

                } else {
                    val error = when (task.exception) {
                        is FirebaseAuthUserCollisionException -> "This email is already registered"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid email format"
                        is FirebaseAuthWeakPasswordException -> "Password is too weak"
                        else -> "Registration failed: ${task.exception?.message}"
                    }
                    Log.e(TAG, "❌ Firebase registration failed: $error")
                    onComplete(false, error)
                }
            }
    }

    // -----------------------
    // LOGIN METHOD (FIXED)
    // -----------------------

    fun loginUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = auth.currentUser
                    if (user != null) {
                        if (!user.isEmailVerified) {
                            // Sign out if email not verified
                            auth.signOut()
                            Log.w(TAG, "Login blocked: Email not verified for $email")
                            onComplete(false, "Please verify your email address before logging in. Check your inbox for the verification link.")
                        } else {
                            // Save data and mark consistent
                            saveUserDataLocally(user.email ?: "", user.uid)
                            markAuthStateConsistent(true)
                            
                            // Update IP information on login
                            updateUserIpInfo(user.uid, email, false)
                            
                            // Sync credits after successful login
                            syncUserCredits { success, _ ->
                                Log.d(TAG, "User logged in successfully: $email")
                                onComplete(true, null)
                            }
                        }
                    } else {
                        Log.e(TAG, "Login failed: No user data after successful auth")
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
    // EMAIL VERIFICATION METHODS (FOR MAINACTIVITY)
    // -----------------------

    /** Check if current user has verified email */
    fun isEmailVerified(): Boolean {
        return auth.currentUser?.isEmailVerified ?: false
    }

    /** Send email verification to current user - Used by MainActivity */
    fun sendEmailVerification(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, "No user logged in")
            return
        }
        
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Verification email sent to ${user.email}")
                    onComplete(true, null)
                } else {
                    val error = task.exception?.message ?: "Failed to send verification email"
                    Log.e(TAG, "Verification email failed: $error")
                    onComplete(false, error)
                }
            }
    }

    /** Resend verification email to a specific email (for registration flow) */
    fun resendVerificationEmail(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, "No user logged in")
            return
        }
        
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Verification email resent to ${user.email}")
                    onComplete(true, null)
                } else {
                    val error = task.exception?.message ?: "Failed to send verification email"
                    Log.e(TAG, "Verification email resend failed: $error")
                    onComplete(false, error)
                }
            }
    }

    // -----------------------
    // SYNC SYSTEM (PREVENT DUPLICATE CALLS)
    // -----------------------

    /** Sync user credits from Firestore with cooldown protection */
    fun syncUserCredits(onComplete: (Boolean, Int?) -> Unit) {
        // Prevent multiple simultaneous syncs
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d(TAG, "🔄 Sync already in progress, skipping duplicate call")
            onComplete(true, getCachedCredits())
            return
        }

        val userId = getCurrentUserId()
        val user = auth.currentUser

        if (userId == null || user == null) {
            Log.e(TAG, "❌ Cannot sync: No valid user")
            isSyncing.set(false)
            onComplete(false, null)
            return
        }

        // Cooldown check
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        if (System.currentTimeMillis() - lastSync < SYNC_COOLDOWN_MS) {
            Log.d(TAG, "⏰ Sync skipped - too recent")
            isSyncing.set(false)
            onComplete(true, getCachedCredits())
            return
        }

        Log.d(TAG, "🔄 Starting credit sync for user: ${user.uid.take(8)}...")

        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                isSyncing.set(false) // Release sync lock
                
                if (document.exists()) {
                    val credits = document.getLong("availableCredits")?.toInt() ?: 0
                    prefs.edit().apply {
                        putInt(AVAILABLE_CREDITS_KEY, credits)
                        putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis())
                        apply()
                    }
                    Log.d(TAG, "✅ Credits synced: $credits")
                    onComplete(true, credits)
                } else {
                    Log.e(TAG, "❌ User document missing in Firestore")
                    onComplete(false, null)
                }
            }
            .addOnFailureListener { e ->
                isSyncing.set(false) // Release sync lock
                Log.e(TAG, "❌ Credit sync failed: ${e.message}")
                onComplete(false, null)
            }
    }

    // -----------------------
    // IP CAPTURE (BACKGROUND)
    // -----------------------

    private fun updateUserIpInfo(uid: String, email: String, isRegistration: Boolean = false) {
        lifecycleScope.launch {
            try {
                val publicIp = fetchPublicIpWithFallback()
                
                val updateData = mutableMapOf<String, Any>(
                    "ipAddress" to publicIp,
                    "lastLoginIp" to publicIp,
                    "lastActive" to System.currentTimeMillis(),
                    "lastUpdated" to System.currentTimeMillis()
                )

                if (isRegistration) {
                    updateData["registrationIp"] to publicIp
                }

                db.collection("users").document(uid)
                    .update(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ IP info updated for $email: $publicIp")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ Failed to update IP info: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "💥 IP update failed: ${e.message}")
            }
        }
    }

    private suspend fun fetchPublicIpWithFallback(): String = withContext(Dispatchers.IO) {
        val ipServices = listOf(
            "https://api.ipify.org",
            "https://api64.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com"
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
                    Log.d(TAG, "✅ Public IP captured from $service: $publicIp")
                    return@withContext publicIp
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ IP service $service failed: ${e.message}")
            }
        }

        // Fallback to local IP
        val localIp = getLocalIpAddress()
        Log.w(TAG, "⚠️ Using local IP as fallback: $localIp")
        return@withContext localIp
    }

    /**
     * Get local IP address (fallback when public IP fails)
     */
    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        return addr.hostAddress
                    }
                }
            }
            "unknown_local"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
            "error_local"
        }
    }

    // -----------------------
    // DEVICE INFO METHODS
    // -----------------------

    /**
     * Get secure device ID
     */
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } 
                ?: "android_${System.currentTimeMillis()}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device ID: ${e.message}")
            "error_device_${System.currentTimeMillis()}"
        }
    }

    /**
     * Get detailed device information
     */
    private fun getDeviceInfo(): String {
        return try {
            "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get device info: ${e.message}")
            "Unknown Device"
        }
    }

    /**
     * Get user agent string
     */
    private fun getUserAgent(): String {
        return try {
            "Android/${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL}; ${Build.DEVICE})"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get user agent: ${e.message}")
            "Android App"
        }
    }

    // -----------------------
    // UTILITY METHODS (FOR MAINACTIVITY)
    // -----------------------

    fun saveUserDataLocally(email: String, uid: String) {
        prefs.edit().apply {
            putString(USER_EMAIL_KEY, email)
            putString(USER_ID_KEY, uid)
            putBoolean(IS_REGISTERED_KEY, true)
            apply()
        }
        Log.d(TAG, "💾 Local data saved: ${email.take(5)}... (${uid.take(8)}...)")
    }

    fun getCurrentUserId(): String? {
        return try {
            auth.currentUser?.uid ?: prefs.getString(USER_ID_KEY, null)
        } catch (e: Exception) {
            Log.e(TAG, "UID retrieval failed: ${e.message}")
            prefs.getString(USER_ID_KEY, null)
        }
    }

    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    /** Get current user from Firebase Auth - Used by MainActivity */
    fun getCurrentFirebaseUser() = auth.currentUser

    fun getCachedCredits(): Int {
        return prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    }

    fun updateLocalCredits(newCredits: Int) {
        prefs.edit().putInt(AVAILABLE_CREDITS_KEY, newCredits).apply()
        Log.d(TAG, "Local credits updated to: $newCredits")
    }

    fun logout() {
        Log.d(TAG, "🚪 Logging out user: ${getCurrentUserEmail()}")
        auth.signOut()
        clearUserData()
        Log.d(TAG, "✅ User logged out completely")
    }

    // -----------------------
    // DEBUG METHODS
    // -----------------------

    /** Debug method to check all stored data */
    fun debugStoredData() {
        val allEntries = prefs.all
        Log.d(TAG, "=== Stored User Data ===")
        allEntries.forEach { (key, value) ->
            Log.d(TAG, "$key: $value")
        }
        Log.d(TAG, "Firebase current user: ${auth.currentUser?.uid ?: "null"}")
        Log.d(TAG, "User logged in: ${isUserLoggedIn()}")
        Log.d(TAG, "=== End Stored Data ===")
    }

    /** Check if user exists in Firestore */
    fun checkUserExists(userId: String, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                onComplete(document.exists())
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }
}
