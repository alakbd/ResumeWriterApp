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

class UserManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        private const val USER_EMAIL_KEY = "user_email"
        private const val USER_ID_KEY = "user_id"
        private const val IS_REGISTERED_KEY = "is_registered"
        private const val AVAILABLE_CREDITS_KEY = "available_credits"
        // NO TOKEN CONSTANTS - WE DON'T NEED THEM ANYMORE
    }

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

    
    /** Register a new user */
    fun registerUser(
        email: String,
        password: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
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

                    // Create user document in Firestore
                    val userData = hashMapOf(
                        "email" to email,
                        "uid" to uid,
                        "availableCredits" to 3, // Start with 3 free credits
                        "usedCredits" to 0,
                        "totalCreditsEarned" to 3,
                        "createdAt" to System.currentTimeMillis(),
                        "lastActive" to System.currentTimeMillis(),
                        "isActive" to true
                    )

                    db.collection("users").document(uid)
                        .set(userData)
                        .addOnSuccessListener {
                            // Save registration state locally
                            saveUserDataLocally(email, uid)
                            Log.d("UserManager", "User registered successfully: $email")
                            onComplete(true, null)
                        }
                        .addOnFailureListener { e ->
                            // Delete the auth user if Firestore fails
                            user.delete()
                            Log.e("UserManager", "Firestore registration failed: ${e.message}")
                            onComplete(false, "Database error: ${e.message}")
                        }

                } else {
                    val error = when (task.exception) {
                        is FirebaseAuthUserCollisionException -> "This email is already registered"
                        is FirebaseAuthInvalidCredentialsException -> "Invalid email format"
                        is FirebaseAuthWeakPasswordException -> "Password is too weak"
                        else -> "Registration failed: ${task.exception?.message}"
                    }
                    Log.e("UserManager", "Registration failed: $error")
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

    /** Login existing user */
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
                        Log.d("UserManager", "User logged in successfully: $email")
                        onComplete(true, null)
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

/** Check if user is logged in - ENHANCED for UID-based auth */
fun isUserLoggedIn(): Boolean {
    val firebaseUser = auth.currentUser
    val userId = getCurrentUserId()
    val isRegistered = prefs.getBoolean(IS_REGISTERED_KEY, false)
    
    Log.d("UserManager", "Login check - Firebase: ${firebaseUser != null}, UserID: ${!userId.isNullOrBlank()}, Registered: $isRegistered")
    
    // For proper authentication, we need BOTH Firebase user AND local registration data
    val isLoggedIn = firebaseUser != null && !userId.isNullOrBlank() && isRegistered
    
    if (isLoggedIn) {
        Log.d("UserManager", "✅ User properly logged in: ${userId?.take(8)}...")
    } else {
        Log.w("UserManager", "❌ User not properly logged in. State inconsistent.")
        
        // Clear everything if inconsistent state
        if (firebaseUser == null && (!userId.isNullOrBlank() || isRegistered)) {
            Log.w("UserManager", "🔄 Clearing stale local data - no Firebase user")
            logout()
        }
    }
    
    return isLoggedIn
}

    /** Get current user email */
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    /** Get current user ID - CRITICAL for UID-based auth */
    /** Get current user ID - ENHANCED with proper synchronization */
fun getCurrentUserId(): String? {
    return try {
        // First, check if we have a valid Firebase user
        val firebaseUser = auth.currentUser
        val firebaseUid = firebaseUser?.uid
        
        // Check local preferences
        val prefId = prefs.getString(USER_ID_KEY, null)
        
        Log.d("UserManager", "🔍 UID Check - Firebase: ${firebaseUid?.take(8)}, Prefs: ${prefId?.take(8)}")
        
        when {
            // Case 1: Both exist and match - ideal scenario
            !prefId.isNullOrBlank() && !firebaseUid.isNullOrBlank() && prefId == firebaseUid -> {
                Log.d("UserManager", "✅ UID from prefs (matches Firebase): ${prefId.take(8)}...")
                prefId
            }
            
            // Case 2: Firebase has UID but prefs don't match or are missing - SYNC REQUIRED
            !firebaseUid.isNullOrBlank() -> {
                Log.w("UserManager", "🔄 Syncing UID from Firebase to prefs: ${firebaseUid.take(8)}...")
                val email = firebaseUser.email ?: ""
                saveUserDataLocally(email, firebaseUid)
                firebaseUid
            }
            
            // Case 3: Prefs have UID but no Firebase user - CLEANUP REQUIRED
            !prefId.isNullOrBlank() && firebaseUser == null -> {
                Log.e("UserManager", "⚠️ Stale UID in prefs with no Firebase user - clearing")
                logout() // Clear stale data
                null
            }
            
            // Case 4: No UID available
            else -> {
                Log.w("UserManager", "❌ No UID available (Firebase: ${firebaseUser != null}, Prefs: ${!prefId.isNullOrBlank()})")
                null
            }
        }
    } catch (e: Exception) {
        Log.e("UserManager", "💥 Exception in getCurrentUserId: ${e.message}", e)
        // Fallback to Firebase UID
        auth.currentUser?.uid
    }
}

    /** Get current user from Firebase Auth */
    fun getCurrentFirebaseUser() = auth.currentUser

    /** Logout user - clear everything */
    fun logout() {
        Log.d("UserManager", "Logging out user: ${getCurrentUserEmail()}")
        auth.signOut()
        prefs.edit().clear().apply()
        Log.d("UserManager", "User logged out and all data cleared")
    }

    /** Sync user credits from Firestore */
    fun syncUserCredits(onComplete: (Boolean, Int?) -> Unit) {
        val userId = getCurrentUserId()
        val user = auth.currentUser

        if (userId == null || user == null) {
            Log.e("UserManager", "Cannot sync credits: No user ID or Firebase user")
            onComplete(false, null)
            return
        }

        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val credits = document.getLong("availableCredits")?.toInt() ?: 0
                    // Update local preferences
                    prefs.edit().putInt(AVAILABLE_CREDITS_KEY, credits).apply()
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
            apply()
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

    // ========== NO TOKEN METHODS - COMPLETELY REMOVED ==========
    // We don't need any token methods for UID-based authentication
    // The SecureAuthInterceptor uses getCurrentUserId() directly
}
