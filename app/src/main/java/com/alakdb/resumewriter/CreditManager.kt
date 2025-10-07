package com.alakdb.resumewriter

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue

class CreditManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("credit_prefs", Context.MODE_PRIVATE)
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    companion object {
        private const val AVAILABLE_CREDITS_KEY = "available_credits"
        private const val USED_CREDITS_KEY = "used_credits"
        private const val TOTAL_CREDITS_KEY = "total_credits"
        private const val ADMIN_MODE_KEY = "admin_mode"
        private const val LAST_RESUME_TIME_KEY = "last_resume_time"
    }

    // -----------------------
    // NEW METHODS FOR RESUME GENERATION CONTROL
    // -----------------------
    
    /**
     * Check if user can generate a resume (has credits and not in cooldown)
     */
    fun canGenerateResume(): Boolean {
        return getAvailableCredits() > 0 && !hasUsedCreditRecently()
    }

    /**
     * Use credit specifically for resume generation with additional checks
     */
    fun useCreditForResume(onComplete: (Boolean) -> Unit) {
        if (!canGenerateResume()) {
            onComplete(false)
            return
        }
        
        val currentCredits = getAvailableCredits()
        if (currentCredits > 0) {
            val newCredits = currentCredits - 1
            val newUsed = getUsedCredits() + 1
            
            // Update local storage first
            updateLocalCredits(newCredits, newUsed, getTotalCredits())
            
            // Mark the time of resume generation
            prefs.edit()
                .putLong(LAST_RESUME_TIME_KEY, System.currentTimeMillis())
                .apply()
            
            // Sync to Firebase
            syncCreditsToFirebase(newCredits, newUsed, onComplete)
        } else {
            onComplete(false)
        }
    }

    /**
     * Check if credit was used recently (within 30 seconds)
     */
    fun hasUsedCreditRecently(): Boolean {
        val lastTime = prefs.getLong(LAST_RESUME_TIME_KEY, 0)
        return System.currentTimeMillis() - lastTime < 30000 // 30 second cooldown
    }

    /**
     * Reset the resume generation cooldown (useful when leaving the WebView)
     */
    fun resetResumeCooldown() {
        prefs.edit().remove(LAST_RESUME_TIME_KEY).apply()
    }

    // -----------------------
    // Credits getters
    // -----------------------
    fun getAvailableCredits(): Int = prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
    fun getUsedCredits(): Int = prefs.getInt(USED_CREDITS_KEY, 0)
    fun getTotalCredits(): Int = prefs.getInt(TOTAL_CREDITS_KEY, 0)

    // -----------------------
    // Credit operations
    // -----------------------
    fun useCredit(onComplete: (Boolean) -> Unit = {}) {
        val currentCredits = getAvailableCredits()
        if (currentCredits > 0) {
            val newCredits = currentCredits - 1
            val newUsed = getUsedCredits() + 1
            updateLocalCredits(newCredits, newUsed, getTotalCredits())
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

    /**
     * Sync local values from Firestore (users collection).
     * onComplete(success, availableCredits)
     */
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

                    val editor = prefs.edit()
                    editor.putInt(AVAILABLE_CREDITS_KEY, available)
                    editor.putInt(USED_CREDITS_KEY, used)
                    editor.putInt(TOTAL_CREDITS_KEY, total)
                    editor.apply()

                    onComplete(true, available)
                } else {
                    onComplete(false, null)
                }
            }
            .addOnFailureListener {
                onComplete(false, null)
            }
    }

    // -----------------------
    // Admin mode handling
    // -----------------------
    /**
     * Persist admin mode flag. Use this from AdminLoginActivity on successful admin auth,
     * and call setAdminMode(false) on logout.
     */
    fun setAdminMode(isAdmin: Boolean) {
        prefs.edit().putBoolean(ADMIN_MODE_KEY, isAdmin).apply()
    }

    /**
     * Returns true only when the explicit admin flag is set.
     * (No implicit Firebase-email auto-grant here — keep admin sessions explicit.)
     */
    fun isAdminMode(): Boolean {
        return prefs.getBoolean(ADMIN_MODE_KEY, false)
    }

    /**
     * Convenience helper: mark admin if the email is in your hardcoded admin list.
     * Call this from your login flow if you prefer a single-line check.
     */
    fun loginAsAdmin(email: String): Boolean {
        val adminEmails = listOf("alakbd2009@gmail.com", "admin@resumewriter.com")
        return if (adminEmails.contains(email)) {
            setAdminMode(true)
            true
        } else false
    }

    // -----------------------
    // Admin Firestore operations
    // -----------------------
    fun adminAddCreditsToUser(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to FieldValue.increment(amount.toLong()),
                "totalCreditsEarned" to FieldValue.increment(amount.toLong()),
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    fun adminSetUserCredits(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to amount,
                "totalCreditsEarned" to amount,
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    fun adminResetUserCredits(userId: String, onComplete: (Boolean) -> Unit) {
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to 0,
                "usedCredits" to 0,
                "totalCreditsEarned" to 0,
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    // -----------------------
    // Internal helpers
    // -----------------------
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
        ).addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }
}
