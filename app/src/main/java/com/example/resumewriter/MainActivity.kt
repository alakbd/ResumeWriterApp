package com.example.resumewriter

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore

class UserManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        const val USER_EMAIL_KEY = "user_email"
        const val USER_ID_KEY = "user_id"
        const val IS_REGISTERED_KEY = "is_registered"
    }

    // Check if user is registered locally
    fun isUserRegistered(): Boolean {
        return prefs.getBoolean(IS_REGISTERED_KEY, false)
    }

    // Get current user's email
    fun getCurrentUserEmail(): String? {
        return prefs.getString(USER_EMAIL_KEY, null)
    }

    // Register a new user
    fun registerUser(email: String, onComplete: (Boolean, String?) -> Unit) {
        val userId = generateUserId(email)

        val userData = hashMapOf(
            "email" to email,
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
            .document(email)
            .set(userData)
            .addOnSuccessListener {
                prefs.edit().apply {
                    putString(USER_EMAIL_KEY, email)
                    putString(USER_ID_KEY, userId)
                    putBoolean(IS_REGISTERED_KEY, true)
                }.apply()
                onComplete(true, null)
            }
            .addOnFailureListener { e ->
                onComplete(false, e.message)
            }
    }

    // Sync local credits with Firebase
    fun syncCreditsWithServer(onComplete: (Boolean) -> Unit) {
        val email = getCurrentUserEmail()
        if (email == null) {
            onComplete(false)
            return
        }

        val creditManager = CreditManager(context)
        val dbRef = db.collection("users").document(email)

        // Update Firebase with local credit values
        val updates = hashMapOf(
            "availableCredits" to creditManager.getAvailableCredits(),
            "usedCredits" to creditManager.getUsedCredits(),
            "totalCreditsEarned" to creditManager.getTotalCreditsEarned(),
            "lastUpdated" to System.currentTimeMillis()
        )

        dbRef.update(updates as Map<String, Any>)
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    // Generate a unique user ID (simple hash of email + timestamp)
    private fun generateUserId(email: String): String {
        return "${email.hashCode()}_${System.currentTimeMillis()}"
    }
}
