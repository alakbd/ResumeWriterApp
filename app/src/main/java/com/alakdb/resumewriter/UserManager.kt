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
import java.net.URL

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
        // NO TOKEN CONSTANTS - WE DON'T NEED THEM ANYMORE
    }

    // -----------------------
    // STORAGE VALIDATION & DEBUG
    // -----------------------
    // Add this method to your existing UserManager class
    fun cacheCredits(credits: Int) {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putInt("cached_credits", credits).apply()
        Log.d("UserManager", "Cached credits: $credits")
    }
    
    /**
     * 🔍 Validate that user data is properly persisted
     */
    fun isUserDataPersisted(): Boolean {
        val userId = prefs.getString(USER_ID_KEY, null)
        val email = prefs.getString(USER_EMAIL_KEY, null)
        val isRegistered = prefs.getBoolean(IS_REGISTERED_KEY, false)
        
        Log.d("UserStorage", "UserID: $userId, Email: $email, Registered: $isRegistered")
        return !userId.isNullOrBlank() && !email.isNullOrBlank()
    }

    /**
     * 📊 Debug current user state
     */
    fun debugUserState() {
        val firebaseUser = auth.currentUser
        val localUid = getCurrentUserId()
        val localEmail = getCurrentUserEmail()
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        
        Log.d("UserDebug", "=== USER MANAGER STATE ===")
        Log.d("UserDebug", "Firebase UID: ${firebaseUser?.uid ?: "null"}")
        Log.d("UserDebug", "Local UID: $localUid")
        Log.d("UserDebug", "Local Email: $localEmail")
        Log.d("UserDebug", "Last Sync: ${if (lastSync > 0) "${(System.currentTimeMillis() - lastSync)/1000}s ago" else "Never"}")
        Log.d("UserDebug", "Storage Valid: ${isUserDataPersisted()}")
        Log.d("UserDebug", "Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
        Log.d("UserDebug", "=== END USER STATE ===")
    }

    /**
     * 🗑️ Clear all user data (use on logout)
     */
    fun clearUserData() {
        prefs.edit().apply {
            remove(USER_EMAIL_KEY)
            remove(USER_ID_KEY)
            remove(IS_REGISTERED_KEY)
            remove(AVAILABLE_CREDITS_KEY)
            remove(LAST_SYNC_TIME_KEY)
            apply()
        }
        Log.d("UserManager", "All user data cleared")
    }

    // -----------------------
    // EMERGENCY SYNC & DATA RECOVERY
    // -----------------------

    /** Emergency sync - ensures UserManager is synchronized with Firebase */
    fun emergencySyncWithFirebase(): Boolean {
        return try {
            val firebaseUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (e: Exception) {
                Log.e("UserManager", "❌ Firebase access failed in emergency sync: ${e.message}")
                null
            }
            
            if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val email = firebaseUser.email ?: ""
                
                try {
                    saveUserDataLocally(email, uid)
                    Log.d("UserManager", "✅ Emergency sync successful: ${uid.take(8)}...")
                    true
                } catch (e: Exception) {
                    Log.e("UserManager", "❌ saveUserDataLocally failed: ${e.message}")
                    false
                }
            } else {
                Log.w("UserManager", "⚠️ No Firebase user for emergency sync")
                false
            }
        } catch (e: Exception) {
            Log.e("UserManager", "💥 Emergency sync completely failed: ${e.message}")
            false
        }
    }

    /**
     * 🆘 Force sync regardless of cooldown
     */
    fun forceSyncWithFirebase(onComplete: (Boolean) -> Unit) {
        Log.d("UserManager", "Forcing user data sync")
        prefs.edit().remove(LAST_SYNC_TIME_KEY).apply() // Clear cooldown
        syncUserCredits { success, _ ->
            onComplete(success)
        }
    }

    /**
     * 🔄 Automatic re-sync when network connects
     */
    fun autoResyncIfNeeded() {
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        val needsSync = System.currentTimeMillis() - lastSync > SYNC_COOLDOWN_MS
        
        if (needsSync && isUserLoggedIn()) {
            Log.d("UserManager", "Auto-resyncing user data")
            syncUserCredits { success, _ ->
                if (success) {
                    Log.d("UserManager", "Auto-resync successful")
                } else {
                    Log.w("UserManager", "Auto-resync failed")
                }
            }
        }
    }

// ⭐⭐⭐ ADD THIS METHOD TO CAPTURE REAL PUBLIC IP
    
    /**
 * Capture real public IP and update user document
 */
private fun fetchPublicIpAndUpdateUser(uid: String, email: String, onIpFetched: (String) -> Unit = {}) {
    Thread {
        try {
            val connection = java.net.URL("https://api.ipify.org").openConnection()
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.getInputStream()))
            val publicIp = reader.readLine()?.takeIf { it.isNotBlank() } ?: "unknown_ip"
            reader.close()

            val ipUpdateData = hashMapOf<String, Any>(
                "ipAddress" to publicIp,
                "registrationIp" to publicIp,
                "lastLoginIp" to publicIp,
                "lastUpdated" to System.currentTimeMillis()
            )

            db.collection("users").document(uid)
                .update(ipUpdateData)
                .addOnSuccessListener {
                    Log.d("UserManager", "✅ IP updated: $publicIp")
                    onIpFetched(publicIp)
                }
                .addOnFailureListener {
                    Log.e("UserManager", "❌ Failed to update IP")
                    onIpFetched(publicIp)
                }

        } catch (e: Exception) {
            val fallbackIp = "dynamic_${System.currentTimeMillis()}"
            db.collection("users").document(uid)
                .update(hashMapOf(
                    "ipAddress" to fallbackIp,
                    "registrationIp" to fallbackIp,
                    "lastLoginIp" to fallbackIp,
                    "lastUpdated" to System.currentTimeMillis()
                ))
            onIpFetched(fallbackIp)
        }
    }.start()
}

    
    // -----------------------
    // AUTHENTICATION METHODS
    // -----------------------
    
    /** Register a new user */
    fun registerUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
        )  {
        Log.d("UserManager", "🔹 Starting registration for $email")

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
                    Log.e("UserManager", "❌ Registration failed: UID null")
                    onComplete(false, "Registration failed: No user ID")
                    return@addOnCompleteListener
                }

                Log.d("UserManager", "✅ Firebase registration successful. UID: $uid")

                // --- Collect Device Info Using Android's Secure Methods ---
                var deviceId = "unknown"
                var deviceInfo = "unknown"
                var userAgent = "unknown"
                var localIp = "unknown"

                try {
                    // PROPER DEVICE ID CAPTURE
                    deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                        ?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" } 
                        ?: "android_${System.currentTimeMillis()}"

                    // DEVICE INFO
                    deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
                    
                    // USER AGENT
                    userAgent = "Android/${Build.VERSION.RELEASE} (${Build.MANUFACTURER} ${Build.MODEL})"
                    
                    // INITIAL IP (will be updated with real IP later)
                    localIp = "pending_real_ip"

                } catch (e: Exception) {
                    Log.w("UserManager", "⚠️ Failed to get device info: ${e.message}")
                    deviceId = "error_device_${System.currentTimeMillis()}"
                    deviceInfo = "Unknown Device"
                    userAgent = "Android App"
                    localIp = "error_ip_${System.currentTimeMillis()}"
                }

                // --- Prepare User Data ---
                val userData: Map<String, Any> = mapOf(
                    "email" to email,
                    "uid" to uid,
                    "availableCredits" to 3,
                    "usedCredits" to 0,
                    "totalCreditsEarned" to 3,
                    "createdAt" to System.currentTimeMillis(),
                    "lastActive" to System.currentTimeMillis(),
                    "isActive" to true,
                    "emailVerified" to false,
                    
                    // NETWORK DATA
                    "ipAddress" to localIp,
                    "registrationIp" to localIp,
                    "lastLoginIp" to localIp,
                    "deviceId" to deviceId,
                    "deviceInfo" to deviceInfo,
                    "userAgent" to userAgent,
                    "lastLogin" to System.currentTimeMillis(),
                    "lastUpdated" to System.currentTimeMillis()
                )

                // --- Save to Firestore ---
                db.collection("users").document(uid)
                    .set(userData, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("UserManager", "✅ Firestore document created for $email")

                        // ⭐⭐⭐ CAPTURE REAL PUBLIC IP AND UPDATE USER
                        fetchPublicIpAndUpdateUser(uid, email)

                        // --- Save user locally ---
                        saveUserDataLocally(email, uid)

                        // --- Send verification email ---
                        user.sendEmailVerification()
                            .addOnCompleteListener { emailTask ->
                                if (emailTask.isSuccessful) {
                                    Log.d("UserManager", "📩 Verification email sent to $email")
                                    onComplete(true, "Registration successful! Please verify your email before logging in.")
                                } else {
                                    Log.w("UserManager", "⚠️ Verification email failed: ${emailTask.exception?.message}")
                                    onComplete(true, "Registration successful, but verification email could not be sent. You can resend it later.")
                                }
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.e("UserManager", "❌ Firestore user save failed: ${e.message}")
                        user.delete().addOnCompleteListener {
                            Log.e("UserManager", "Deleted Firebase user after Firestore failure.")
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
                Log.e("UserManager", "❌ Firebase registration failed: $error")
                onComplete(false, error)
            }
        }
}




    /** Check if current user has verified email */
    fun isEmailVerified(): Boolean {
        return auth.currentUser?.isEmailVerified ?: false
    }

    /** Send email verification to current user */
    fun sendEmailVerification(onComplete: (Boolean, String?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, "No user logged in")
            return
        }
        
        user.sendEmailVerification()
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d("UserManager", "Verification email sent to ${user.email}")
                    onComplete(true, null)
                } else {
                    val error = task.exception?.message ?: "Failed to send verification email"
                    Log.e("UserManager", "Verification email failed: $error")
                    onComplete(false, error)
                }
            }
    }

    /** Enhanced login with email verification check */
    fun loginUserWithVerification(
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
                            Log.w("UserManager", "Login blocked: Email not verified for $email")
                            onComplete(false, "Please verify your email address before logging in. Check your inbox for the verification link.")
                        } else {
                            saveUserDataLocally(user.email ?: "", user.uid)
                            // Sync credits after successful login
                            syncUserCredits { success, _ ->
                                Log.d("UserManager", "User logged in successfully: $email")
                                onComplete(true, null)
                            }
                        }
                    } else {
                        Log.e("UserManager", "Login failed: No user data after successful auth")
                        onComplete(false, "Login failed: No user data")
                    }
                } else {
                    val error = when (task.exception) {
                        is FirebaseAuthInvalidUserException -> "No account found with this email"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid password"
                        else -> "Login failed: ${task.exception?.message}"
                    }
                    Log.e("UserManager", "Login failed: $error")
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
                    Log.d("UserManager", "Verification email resent to ${user.email}")
                    onComplete(true, null)
                } else {
                    val error = task.exception?.message ?: "Failed to send verification email"
                    Log.e("UserManager", "Verification email resend failed: $error")
                    onComplete(false, error)
                }
            }
    }

  /** Login existing user with IP capture */
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
                    saveUserDataLocally(user.email ?: "", user.uid)
                    
                    // ⭐⭐⭐ CAPTURE IP ON LOGIN TOO
                    fetchPublicIpAndUpdateUser { publicIp ->
                        if (publicIp != "unknown_ip") {
                            val updateData = hashMapOf<String, Any>(
                                "lastLoginIp" to publicIp,
                                "lastLogin" to System.currentTimeMillis(),
                                "lastActive" to System.currentTimeMillis(),
                                "lastUpdated" to System.currentTimeMillis()
                            )
                            
                            db.collection("users").document(user.uid)
                                .update(updateData)
                                .addOnSuccessListener {
                                    Log.d("UserManager", "✅ Login IP captured: $publicIp")
                                }
                                .addOnFailureListener { e ->
                                    Log.e("UserManager", "❌ Failed to update login IP", e)
                                }
                        }
                    }
                    
                    // Sync credits after successful login
                    syncUserCredits { success, _ ->
                        Log.d("UserManager", "User logged in successfully: $email")
                        onComplete(true, null)
                    }
                } else {
                    Log.e("UserManager", "Login failed: No user data after successful auth")
                    onComplete(false, "Login failed: No user data")
                }
            } else {
                val error = when (task.exception) {
                    is FirebaseAuthInvalidUserException -> "No account found with this email"
                    is FirebaseAuthInvalidCredentialsException -> "Invalid password"
                    else -> "Login failed: ${task.exception?.message}"
                }
                Log.e("UserManager", "Login failed: $error")
                onComplete(false, error)
            }
        }
}

    fun logCurrentUserState() {
        val firebaseUser = auth.currentUser
        val userId = getCurrentUserId()
        val email = getCurrentUserEmail()
        val registered = prefs.getBoolean(IS_REGISTERED_KEY, false)
        Log.d("UserManager", "DEBUG User State -> Firebase UID: ${firebaseUser?.uid}, Pref UID: $userId, Email: $email, Registered: $registered")
    }

    /** Check if user is logged in - RELAXED for better user experience */
    fun isUserLoggedIn(): Boolean {
        return try {
            val firebaseUser = auth.currentUser
            val localUid = prefs.getString(USER_ID_KEY, null)
            
            Log.d("UserManager", "Auth Check - Firebase: ${firebaseUser != null}, Local: ${!localUid.isNullOrBlank()}")
            
            // Primary: Firebase user exists
            if (firebaseUser != null) {
                Log.d("UserManager", "✅ User logged in (Firebase confirmed)")
                
                // Ensure local data matches Firebase
                if (localUid != firebaseUser.uid) {
                    Log.w("UserManager", "🔄 Syncing local data with Firebase...")
                    saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
                }
                return true
            }
            
            // Fallback: Local data exists (might be offline)
            if (!localUid.isNullOrBlank()) {
                Log.w("UserManager", "⚠️ Using local auth data (Firebase might be offline)")
                return true
            }
            
            // No auth data at all
            Log.w("UserManager", "❌ No auth data found")
            false
            
        } catch (e: Exception) {
            Log.e("UserManager", "💥 Auth check failed: ${e.message}")
            // In case of error, fallback to local data check
            !prefs.getString(USER_ID_KEY, null).isNullOrBlank()
        }
    }

    /** Get current user email */
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    /** Get current user ID - SIMPLIFIED and RELIABLE */
    fun getCurrentUserId(): String? {
        return try {
            // Priority 1: Firebase UID (most reliable)
            val firebaseUid = auth.currentUser?.uid
            if (!firebaseUid.isNullOrBlank()) {
                Log.d("UserManager", "✅ Using Firebase UID: ${firebaseUid.take(8)}...")
                return firebaseUid
            }
            
            // Priority 2: Local UID (fallback)
            val localUid = prefs.getString(USER_ID_KEY, null)
            if (!localUid.isNullOrBlank()) {
                Log.w("UserManager", "⚠️ Using local UID (Firebase missing): ${localUid.take(8)}...")
                return localUid
            }
            
            Log.w("UserManager", "❌ No UID available")
            null
        } catch (e: Exception) {
            Log.e("UserManager", "💥 UID retrieval failed: ${e.message}")
            null
        }
    }

    /** Get current user from Firebase Auth */
    fun getCurrentFirebaseUser() = auth.currentUser

    /** Logout user - clear everything */
    fun logout() {
        Log.d("UserManager", "Logging out user: ${getCurrentUserEmail()}")
        auth.signOut()
        clearUserData() // Use the new clear method
        Log.d("UserManager", "User logged out and all data cleared")
    }

private var hasSyncedOnce = false

fun autoSyncUser() {
    if (hasSyncedOnce) {
        Log.d("UserManager", "🟡 Skipping duplicate auto-sync call.")
        return
    }
    hasSyncedOnce = true

    val firebaseUser = auth.currentUser
    if (firebaseUser != null) {
        Log.d("UserManager", "Auto-resyncing user data")
        syncUserCredits { success, message ->
            if (success) {
                Log.d("UserManager", "✅ Auto-sync completed for ${firebaseUser.uid}")
            } else {
                Log.w("UserManager", "⚠️ Auto-sync failed: $message")
            }
        }
    } else {
        Log.w("UserManager", "⚠️ No Firebase user found for auto-sync.")
    }
}

    
    // -----------------------
    // CREDIT SYNC IMPROVEMENTS
    // -----------------------

    /** Sync user credits from Firestore with cooldown protection */
    fun syncUserCredits(onComplete: (Boolean, Int?) -> Unit) {
        val userId = getCurrentUserId()
        val user = auth.currentUser

        if (userId == null || user == null) {
            Log.e("UserManager", "Cannot sync credits: No user ID or Firebase user")
            onComplete(false, null)
            return
        }

        // Check if we synced recently to avoid spam
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        if (System.currentTimeMillis() - lastSync < SYNC_COOLDOWN_MS) {
            Log.d("UserManager", "Credit sync skipped - too recent")
            onComplete(true, getCachedCredits()) // Return cached data
            return
        }

        Log.d("UserManager", "Starting credit sync for user: ${user.uid}")

        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val credits = document.getLong("availableCredits")?.toInt() ?: 0
                    // Update local preferences
                    prefs.edit().putInt(AVAILABLE_CREDITS_KEY, credits).apply()
                    
                    // Update sync timestamp
                    prefs.edit().putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis()).apply()
                    
                    Log.d("UserManager", "Credits synced successfully: $credits")
                    onComplete(true, credits)
                } else {
                    Log.e("UserManager", "User document does not exist in Firestore")
                    onComplete(false, null)
                }
            }
            .addOnFailureListener { e ->
                Log.e("UserManager", "Failed to sync credits: ${e.message}")
                onComplete(false, null)
            }
    }

    /** Emergency method to ensure headers can be sent */
    fun getUserIdForHeaders(): String? {
        return try {
            // Priority 1: Direct Firebase (most reliable)
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val firebaseUid = firebaseUser?.uid

            if (!firebaseUid.isNullOrBlank()) {
                Log.d("UserManager", "🔥 Using Firebase UID for headers: ${firebaseUid.take(8)}...")

                // Emergency sync to local storage
                try {
                    if (getCurrentUserId() != firebaseUid) {
                        saveUserDataLocally(firebaseUser.email ?: "", firebaseUid)
                    }
                } catch (e: Exception) {
                    Log.w("UserManager", "⚠️ Local sync failed but we have Firebase UID")
                }

                return firebaseUid
            }

            // Priority 2: Local storage
            val localUid = getCurrentUserId()
            if (!localUid.isNullOrBlank()) {
                Log.d("UserManager", "📱 Using local UID for headers: ${localUid.take(8)}...")
                return localUid
            }

            Log.e("UserManager", "❌ NO UID AVAILABLE FOR HEADERS")
            null

        } catch (e: Exception) {
            Log.e("UserManager", "💥 Header UID retrieval failed: ${e.message}")
            null
        }
    }
    
    /** Get locally cached credits (use syncUserCredits for fresh data) */
    fun getCachedCredits(): Int {
        return prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    }

    /** Update local credits cache (called after successful credit deduction) */
    fun updateLocalCredits(newCredits: Int) {
        prefs.edit().putInt(AVAILABLE_CREDITS_KEY, newCredits).apply()
        Log.d("UserManager", "Local credits updated to: $newCredits")
    }

    /** Save user data locally in SharedPreferences */
    fun saveUserDataLocally(email: String, uid: String) {
        prefs.edit().apply {
            putString(USER_EMAIL_KEY, email)
            putString(USER_ID_KEY, uid)
            putBoolean(IS_REGISTERED_KEY, true)
            apply() // Ensure immediate persistence
        }
        Log.d("UserManager", "User data saved locally: $email (UID: ${uid.take(8)}...)")
    }

    /** Debug method to check all stored data */
    fun debugStoredData() {
        val allEntries = prefs.all
        Log.d("UserManager", "=== Stored User Data ===")
        allEntries.forEach { (key, value) ->
            Log.d("UserManager", "$key: $value")
        }
        Log.d("UserManager", "Firebase current user: ${auth.currentUser?.uid ?: "null"}")
        Log.d("UserManager", "User logged in: ${isUserLoggedIn()}")
        Log.d("UserManager", "=== End Stored Data ===")
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

    /** Check if this is a fresh install (no user data at all) */
    fun isFreshInstall(): Boolean {
        val hasUserData = prefs.contains(USER_ID_KEY) || 
                         prefs.contains(USER_EMAIL_KEY) || 
                         prefs.contains(IS_REGISTERED_KEY)
    
        val hasFirebaseUser = auth.currentUser != null
    
        Log.d("UserManager", "Fresh install check - HasUserData: $hasUserData, HasFirebaseUser: $hasFirebaseUser")
    
        return !hasUserData && !hasFirebaseUser
    }

    // ========== NO TOKEN METHODS - COMPLETELY REMOVED ==========
    // We don't need any token methods for UID-based authentication
    // The SecureAuthInterceptor uses getCurrentUserId() directly
}
