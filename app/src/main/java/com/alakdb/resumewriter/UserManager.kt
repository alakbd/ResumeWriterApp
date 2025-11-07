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

class UserManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val lifecycleScope = CoroutineScope(Dispatchers.IO)

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
    // IP CAPTURE METHODS
    // -----------------------

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

    /**
     * Enhanced IP capture with multiple fallbacks
     */
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
     * Update user IP information in Firestore
     */
    private fun updateUserIpInfo(uid: String, email: String, isRegistration: Boolean = false) {
        lifecycleScope.launch {
            try {
                val publicIp = fetchPublicIpWithFallback()
                
                val updateData = mutableMapOf<String, Any>(
                    "ipAddress" to publicIp,
                    "lastLoginIp" to publicIp,
                    "lastActive" to System.currentTimeMillis(),
                    "lastUpdated" to System.currentTimeMillis(),
                    "deviceInfo" to getDeviceInfo(),
                    "userAgent" to getUserAgent()
                )

                if (isRegistration) {
                    updateData["registrationIp"] = publicIp
                    updateData["deviceId"] = getDeviceId()
                    updateData["createdAt"] = System.currentTimeMillis()
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

                            // Save user locally
                            saveUserDataLocally(email, uid)
                            
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
                            saveUserDataLocally(user.email ?: "", user.uid)
                            
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
    // SYNC & DATA MANAGEMENT (IMPROVED)
    // -----------------------

    fun syncUserCredits(onComplete: (Boolean, Int?) -> Unit) {
        val userId = getCurrentUserId()
        val user = auth.currentUser

        if (userId == null || user == null) {
            Log.e(TAG, "Cannot sync credits: No user ID or Firebase user")
            onComplete(false, null)
            return
        }

        // Check if we synced recently to avoid spam
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        if (System.currentTimeMillis() - lastSync < SYNC_COOLDOWN_MS) {
            Log.d(TAG, "Credit sync skipped - too recent")
            onComplete(true, getCachedCredits())
            return
        }

        Log.d(TAG, "Starting credit sync for user: ${user.uid}")

        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val credits = document.getLong("availableCredits")?.toInt() ?: 0
                    // Update local preferences
                    prefs.edit().putInt(AVAILABLE_CREDITS_KEY, credits).apply()
                    
                    // Update sync timestamp
                    prefs.edit().putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis()).apply()
                    
                    Log.d(TAG, "Credits synced successfully: $credits")
                    onComplete(true, credits)
                } else {
                    Log.e(TAG, "User document does not exist in Firestore")
                    onComplete(false, null)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync credits: ${e.message}")
                onComplete(false, null)
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
        Log.d(TAG, "User data saved locally: $email (UID: ${uid.take(8)}...)")
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

    fun isUserLoggedIn(): Boolean {
        return try {
            auth.currentUser != null || !prefs.getString(USER_ID_KEY, null).isNullOrBlank()
        } catch (e: Exception) {
            Log.e(TAG, "Auth check failed: ${e.message}")
            !prefs.getString(USER_ID_KEY, null).isNullOrBlank()
        }
    }

    fun getCachedCredits(): Int {
        return prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    }

    fun logout() {
        Log.d(TAG, "Logging out user: ${getCurrentUserEmail()}")
        auth.signOut()
        prefs.edit().clear().apply()
        Log.d(TAG, "User logged out and all data cleared")
    }

    // -----------------------
    // DEBUG METHODS
    // -----------------------

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
        Log.d(TAG, "Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
        Log.d(TAG, "=== END USER STATE ===")
    }
}
