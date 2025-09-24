package com.example.resumewriter

import com.example.resumewriter.BuildConfig
import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth

class CreditManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cv_credits_prefs", Context.MODE_PRIVATE)
    private val CREDITS_KEY = "available_cv_credits"
    private val USED_CREDITS_KEY = "used_cv_credits"
    private val TOTAL_CREDITS_KEY = "total_cv_credits_earned"

    // -------------------------
    // Basic credit operations
    // -------------------------
    fun getAvailableCredits(): Int = prefs.getInt(CREDITS_KEY, 0)
    fun getUsedCredits(): Int = prefs.getInt(USED_CREDITS_KEY, 0)
    fun getTotalCreditsEarned(): Int = prefs.getInt(TOTAL_CREDITS_KEY, 0)

    fun addCredits(credits: Int, userEmail: String? = null, syncToFirebase: Boolean = true) {
        val current = getAvailableCredits()
        val totalEarned = getTotalCreditsEarned()

        prefs.edit().apply {
            putInt(CREDITS_KEY, current + credits)
            putInt(TOTAL_CREDITS_KEY, totalEarned + credits)
        }.apply()

        if (syncToFirebase && userEmail != null) {
            syncToFirebase(userEmail)
        }
    }

    fun useCredit(userEmail: String? = null, syncToFirebase: Boolean = true): Boolean {
        val current = getAvailableCredits()
        if (current > 0) {
            val used = getUsedCredits()
            prefs.edit().apply {
                putInt(CREDITS_KEY, current - 1)
                putInt(USED_CREDITS_KEY, used + 1)
            }.apply()

            if (syncToFirebase && userEmail != null) {
                syncToFirebase(userEmail)
            }
            return true
        }
        return false
    }

    // -------------------------
    // Firebase synchronization
    // -------------------------
    fun syncToFirebase(userEmail: String, onComplete: (Boolean) -> Unit = {}) {
        val db = FirebaseFirestore.getInstance()
        db.collection("users")
            .document(userEmail)
            .update(
                "availableCredits", getAvailableCredits(),
                "usedCredits", getUsedCredits(),
                "totalCreditsEarned", getTotalCreditsEarned(),
                "lastUpdated", System.currentTimeMillis()
            )
            .addOnSuccessListener { onComplete(true) }
            .addOnFailureListener { onComplete(false) }
    }

    fun syncFromFirebase(userEmail: String, onComplete: (Boolean) -> Unit) {
        val db = FirebaseFirestore.getInstance()
        db.collection("users")
            .document(userEmail)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val available = document.getLong("availableCredits")?.toInt() ?: 0
                    val used = document.getLong("usedCredits")?.toInt() ?: 0
                    val total = document.getLong("totalCreditsEarned")?.toInt() ?: 0

                    prefs.edit().apply {
                        putInt(CREDITS_KEY, available)
                        putInt(USED_CREDITS_KEY, used)
                        putInt(TOTAL_CREDITS_KEY, total)
                    }.apply()

                    onComplete(true)
                } else {
                    onComplete(false)
                }
            }
            .addOnFailureListener { onComplete(false) }
    }

    // -------------------------
    // Admin Functions
    // -------------------------
    fun isAdminMode(): Boolean = prefs.getBoolean("is_admin", false)

    fun authenticateAdmin(password: String): Boolean {
        val isAdmin = password == "admin123" // Change this for production!
        if (isAdmin) prefs.edit().putBoolean("is_admin", true).apply()
        return isAdmin
    }

    fun logoutAdmin() {
        prefs.edit().putBoolean("is_admin", false).apply()
    }

    fun adminAddCredits(amount: Int) {
        val current = getAvailableCredits()
        prefs.edit().putInt(CREDITS_KEY, current + amount).apply()
    }

    fun adminSetCredits(amount: Int) {
        prefs.edit().putInt(CREDITS_KEY, amount).apply()
    }

    fun adminResetCredits() {
        prefs.edit().putInt(CREDITS_KEY, 0).apply()
        prefs.edit().putInt(USED_CREDITS_KEY, 0).apply()
        prefs.edit().putInt(TOTAL_CREDITS_KEY, 0).apply()
    }

    fun adminGenerateCV(): Boolean {
        // Admin generates a CV without using credits
        // You can put any additional logic here
        return true
    }

    fun adminGetUserStats(): String {
        val available = getAvailableCredits()
        val used = getUsedCredits()
        val total = getTotalCreditsEarned()
        return "Available: $available | Used: $used | Total Earned: $total"
    }
}
