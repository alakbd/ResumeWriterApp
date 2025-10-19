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

    

/** Check if user is logged in - ENHANCED for UID-based auth */
fun isUserLoggedIn(): Boolean {
    val firebaseUser = auth.currentUser
    val userId = getCurrentUserId()
    val isRegistered = prefs.getBoolean(IS_REGISTERED_KEY, false)
    
    Log.d("UserManager", "Login check - Firebase: ${firebaseUser != null}, UserID: ${!userId.isNullOrBlank()}, Registered: $isRegistered")
    
    // For UID-based auth, we need all three to be true
    val isLoggedIn = firebaseUser != null && !userId.isNullOrBlank() && isRegistered
    
    if (!isLoggedIn) {
        Log.w("UserManager", "❌ User not properly logged in. State inconsistent.")
        
        // Auto-cleanup if Firebase user exists but local data is missing
        if (firebaseUser != null && (userId.isNullOrBlank() || !isRegistered)) {
            Log.w("UserManager", "🔄 Auto-fixing inconsistent state...")
            saveUserDataLocally(firebaseUser.email ?: "", firebaseUser.uid)
        } else if (firebaseUser == null) {
            // Clear everything if no Firebase user
            logout()
        }
    } else {
        Log.d("UserManager", "✅ User properly logged in: ${userId?.take(8)}...")
    }
    
    return isLoggedIn
}

    /** Get current user email */
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    /** Get current user ID - CRITICAL for UID-based auth */
    fun getCurrentUserId(): String? {
        return prefs.getString(USER_ID_KEY, null).also { userId ->
            if (userId == null) {
                Log.d("UserManager", "No user ID found in SharedPreferences")
            } else {
                Log.d("UserManager", "User ID retrieved: ${userId.take(8)}...")
            }
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
    private fun saveUserDataLocally(email: String, uid: String) {
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
