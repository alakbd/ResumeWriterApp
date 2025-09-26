package com.alakdb.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth


class UserManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        const val USER_EMAIL_KEY = "user_email"
        const val USER_ID_KEY = "user_id"
        const val IS_REGISTERED_KEY = "is_registered"
    }

    // Register a new user
    fun registerUser(email: String, password: String, onComplete: (Boolean, String?) -> Unit) {
    FirebaseAuth.getInstance()
        .createUserWithEmailAndPassword(email, password)
        .addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val uid = task.result?.user?.uid ?: return@addOnCompleteListener
                 // your Firestore saving code here
                onComplete(true, null)
            } else {
                onComplete(false, task.exception?.message)
            }
        }

            val userData = hashMapOf(
                "email" to email,
                "uid" to uid,
                "availableCredits" to 0,
                "usedCredits" to 0,
                "totalCreditsEarned" to 0,
                "deviceId" to android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ),
                "createdAt" to System.currentTimeMillis(),
                "lastActive" to System.currentTimeMillis()
            )

            db.collection("users")
                .document(uid) // Use UID instead of email
                .set(userData)
                .addOnSuccessListener {
                    prefs.edit().apply {
                        putString(USER_EMAIL_KEY, email)
                        putString(USER_ID_KEY, uid)
                        putBoolean(IS_REGISTERED_KEY, true)
                    }.apply()
                    onComplete(true, null)
                }
                .addOnFailureListener { e -> onComplete(false, e.message) }
        }
        .addOnFailureListener { e ->
            onComplete(false, e.message)
        }
}

    // Check if user is already registered
    fun isUserRegistered(): Boolean {
        return prefs.getBoolean(IS_REGISTERED_KEY, false)
    }

    // Get current user email
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    // Sync credits with Firebase (example)
    fun syncCreditsWithServer(onComplete: (Boolean) -> Unit) {
        val email = getCurrentUserEmail()
        if (email == null) {
            onComplete(false)
            return
        }

        db.collection("users")
            .document(email)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    // You could sync credits here with CreditManager
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }

    // Generate a unique user ID
    fun generateUserId(email: String): String {
        return email.replace("@", "_at_").replace(".", "_dot_")
    }
}
