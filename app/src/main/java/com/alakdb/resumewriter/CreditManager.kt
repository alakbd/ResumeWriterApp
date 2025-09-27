package com.alakdb.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class CreditManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("credit_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        private const val AVAILABLE_CREDITS_KEY = "available_credits"
        private const val USED_CREDITS_KEY = "used_credits"
        private const val TOTAL_CREDITS_KEY = "total_credits"
    }

    fun getAvailableCredits(): Int = prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    fun getUsedCredits(): Int = prefs.getInt(USED_CREDITS_KEY, 0)
    fun getTotalCredits(): Int = prefs.getInt(TOTAL_CREDITS_KEY, 0)

    fun useCredit(onComplete: (Boolean) -> Unit = {}) {
        val currentCredits = getAvailableCredits()
        if (currentCredits > 0) {
            val newCredits = currentCredits - 1
            val newUsed = getUsedCredits() + 1
            
            // Update locally first for immediate feedback
            updateLocalCredits(newCredits, newUsed, getTotalCredits())
            
            // Sync with Firebase
            syncCreditsToFirebase(newCredits, newUsed, onComplete)
        } else {
            onComplete(false)
        }
    }

    fun addCredits(amount: Int, onComplete: (Boolean) -> Unit = {}) {
        val newCredits = getAvailableCredits() + amount
        val newTotal = getTotalCredits() + amount
        
        updateLocalCredits(newCredits, getUsedCredits(), newTotal)
        syncCreditsToFirebase(newCredits, getUsedCredits(), onComplete)
    }

    fun syncWithFirebase(onComplete: (Boolean, Int?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false, null)
            return
        }

        db.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val available = document.getLong("availableCredits")?.toInt() ?: 0
                    val used = document.getLong("usedCredits")?.toInt() ?: 0
                    val total = document.getLong("totalCreditsEarned")?.toInt() ?: 0
                    
                    updateLocalCredits(available, used, total)
                    onComplete(true, available)
                } else {
                    onComplete(false, null)
                }
            }
            .addOnFailureListener {
                onComplete(false, null)
            }
    }

    fun isAdminMode(): Boolean {
        val user = auth.currentUser
        val adminEmails = listOf("alakbd2009@gmail.com", "admin@resumewriter.com")
        return user?.email?.let { adminEmails.contains(it) } ?: false
    }

    // Admin functions
    fun adminAddCreditsToUser(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to FieldValue.increment(amount.toLong()),
                "totalCreditsEarned" to FieldValue.increment(amount.toLong()),
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task ->
            onComplete(task.isSuccessful)
        }
    }

    fun adminSetUserCredits(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to amount,
                "totalCreditsEarned" to amount,
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task ->
            onComplete(task.isSuccessful)
        }
    }

    fun adminResetUserCredits(userId: String, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to 0,
                "usedCredits" to 0,
                "totalCreditsEarned" to 0,
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task ->
            onComplete(task.isSuccessful)
        }
    }

    private fun updateLocalCredits(available: Int, used: Int, total: Int) {
        prefs.edit().apply {
            putInt(AVAILABLE_CREDITS_KEY, available)
            putInt(USED_CREDITS_KEY, used)
            putInt(TOTAL_CREDITS_KEY, total)
            apply()
        }
    }

    private fun syncCreditsToFirebase(available: Int, used: Int, onComplete: (Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            onComplete(false)
            return
        }

        db.collection("users").document(user.uid).update(
            mapOf(
                "availableCredits" to available,
                "usedCredits" to used,
                "totalCreditsEarned" to getTotalCredits(),
                "lastUpdated" to System.currentTimeMillis(),
                "lastActive" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task ->
            onComplete(task.isSuccessful)
        }
    }
}
