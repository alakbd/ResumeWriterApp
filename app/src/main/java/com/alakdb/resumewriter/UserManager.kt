package com.alakdb.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth


class UserManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    companion object {
        const val USER_EMAIL_KEY = "user_email"
        const val USER_ID_KEY = "user_id"
        const val IS_REGISTERED_KEY = "is_registered"
    }

    // Register a new user
 fun registerUser(email: String, password: String, onComplete: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = task.result?.user?.uid
                    if (uid == null) {
                        onComplete(false, "Failed to get UID")
                        return@addOnCompleteListener
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
                        .document(uid) // Use UID as document ID
                        .set(userData)
                        .addOnSuccessListener {
                            prefs.edit().apply {
                                putString(USER_EMAIL_KEY, email)
                                putString(USER_ID_KEY, uid)
                                putBoolean(IS_REGISTERED_KEY, true)
                            }.apply()
                            onComplete(true, null)
                        }
                        .addOnFailureListener { e ->
                            onComplete(false, e.message)
                        }

                } else {
                    onComplete(false, task.exception?.message)
                }
            }
    }

    // Check if user is already registered locally
    fun isUserRegistered(): Boolean {
        return prefs.getBoolean(IS_REGISTERED_KEY, false)
    }

    // Get current user email from SharedPreferences
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    // Sync credits with Firebase using UID
    fun syncCreditsWithServer(onComplete: (Boolean) -> Unit) {
        val uid = prefs.getString(USER_ID_KEY, null)
        if (uid == null) {
            onComplete(false)
            return
        }

        db.collection("users")
            .document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    // Optionally update local credits from Firestore
                    onComplete(true)
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener {
                onComplete(false)
            }
    }
}
