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
    // REGISTRATION (IMPROVED ERROR HANDLING)
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

                    // Prepare user data with device info
                    val userData = mapOf(
                        "email" to email,
                        "uid" to uid,
                        "availableCredits" to 3,
                        "usedCredits" to 0,
                        "totalCreditsEarned" to 3,
                        "createdAt" to System.currentTimeMillis(),
                        "lastActive" to System.currentTimeMillis(),
                        "isActive" to true,
                        "emailVerified" to false,
                        "deviceId" to getDeviceId(),
                        "deviceInfo" to getDeviceInfo(),
                        "userAgent" to getUserAgent(),
                        "ipAddress" to "pending",
                        "registrationIp" to "pending",
                        "lastLoginIp" to "pending"
                    )

                    // Save to Firestore
                    db.collection("users").document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Firestore document created for $email")

                            // Save locally and mark consistent
                            saveUserDataLocally(email, uid)
                            markAuthStateConsistent(true)

                            // Capture IP in background
                            updateUserIpInfo(uid, email, true)

                            // Send verification email
                            user.sendEmailVerification()
                                .addOnCompleteListener { emailTask ->
                                    if (emailTask.isSuccessful) {
                                        Log.d(TAG, "📩 Verification email sent to $email")
                                        onComplete(true, "Registration successful! Please verify your email.")
                                    } else {
                                        Log.w(TAG, "⚠️ Verification email failed")
                                        onComplete(true, "Registration successful, but verification email failed.")
                                    }
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Firestore save failed: ${e.message}")
                            // Clean up auth user
                            user.delete().addOnCompleteListener {
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
                    Log.e(TAG, "❌ Registration failed: $error")
                    onComplete(false, error)
                }
            }
    }

    // -----------------------
    // LOGIN (WITH BETTER STATE MANAGEMENT)
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
                            auth.signOut()
                            Log.w(TAG, "Login blocked: Email not verified")
                            onComplete(false, "Please verify your email before logging in.")
                        } else {
                            // Save data and mark consistent
                            saveUserDataLocally(user.email ?: "", user.uid)
                            markAuthStateConsistent(true)

                            // Update IP and sync credits
                            updateUserIpInfo(user.uid, email, false)
                            syncUserCredits { success, credits ->
                                if (success) {
                                    Log.d(TAG, "✅ Login successful: $email")
                                    onComplete(true, null)
                                } else {
                                    Log.w(TAG, "⚠️ Login succeeded but sync failed")
                                    onComplete(true, null) // Still success, sync will retry
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "Login failed: No user data after auth")
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
    // SYNC SYSTEM (PREVENT DUPLICATE CALLS)
    // -----------------------

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
    // EMERGENCY SYNC (LIMITED USE)
    // -----------------------

    fun emergencySyncWithFirebase(): Boolean {
        return try {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {
                saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                markAuthStateConsistent(true)
                Log.d(TAG, "✅ Emergency sync successful")
                true
            } else {
                Log.w(TAG, "⚠️ No Firebase user for emergency sync")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Emergency sync failed: ${e.message}")
            false
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
                    updateData["registrationIp"] = publicIp
                }

                db.collection("users").document(uid)
                    .update(updateData)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ IP updated: $publicIp")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ IP update failed: ${e.message}")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "💥 IP capture failed: ${e.message}")
            }
        }
    }

    // -----------------------
    // DEVICE INFO METHODS
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

    private fun getUserAgent(): String {
        return try {
            "Android/${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})"
        } catch (e: Exception) {
            "Android App"
        }
    }

    // -----------------------
    // UTILITY METHODS
    // -----------------------

    fun getCurrentUserId(): String? {
        return try {
            auth.currentUser?.uid ?: prefs.getString(USER_ID_KEY, null)
        } catch (e: Exception) {
            prefs.getString(USER_ID_KEY, null)
        }
    }

    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    fun getCachedCredits(): Int {
        return prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    }

    fun saveUserDataLocally(email: String, uid: String) {
        prefs.edit().apply {
            putString(USER_EMAIL_KEY, email)
            putString(USER_ID_KEY, uid)
            putBoolean(IS_REGISTERED_KEY, true)
            apply()
        }
        Log.d(TAG, "💾 Local data saved: ${email.take(5)}... (${uid.take(8)}...)")
    }

    fun clearUserData() {
        prefs.edit().clear().apply()
        markAuthStateConsistent(false)
        Log.d(TAG, "🗑️ All user data cleared")
    }

    fun logout() {
        Log.d(TAG, "🚪 Logging out user: ${getCurrentUserEmail()}")
        auth.signOut()
        clearUserData()
        Log.d(TAG, "✅ User logged out completely")
    }

    // -----------------------
    // IP CAPTURE IMPLEMENTATION
    // -----------------------

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
    // DEBUG METHODS
    // -----------------------

    fun debugUserState() {
        val firebaseUser = auth.currentUser
        val localUid = getCurrentUserId()
        val localEmail = getCurrentUserEmail()
        
        Log.d(TAG, "=== USER STATE DEBUG ===")
        Log.d(TAG, "Firebase UID: ${firebaseUser?.uid ?: "null"}")
        Log.d(TAG, "Local UID: $localUid")
        Log.d(TAG, "Local Email: $localEmail")
        Log.d(TAG, "Auth Consistent: ${prefs.getBoolean(AUTH_STATE_KEY, false)}")
        Log.d(TAG, "Is Logged In: ${isUserLoggedIn()}")
        Log.d(TAG, "Cached Credits: ${getCachedCredits()}")
        Log.d(TAG, "========================")
    }
}
