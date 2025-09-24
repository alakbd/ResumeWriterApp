package com.example.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore

class UserManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
    
    companion object {
        const val USER_EMAIL_KEY = "user_email"
        const val USER_ID_KEY = "user_id"
        const val IS_REGISTERED_KEY = "is_registered"
    }
    
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
    
    // Add other methods like getCurrentUserEmail(), syncCreditsWithServer(), etc.
}
