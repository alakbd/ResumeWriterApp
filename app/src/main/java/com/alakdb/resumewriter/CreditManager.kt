package com.alakdb.resumewriter

import android.content.Context
import android.util.Log
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
        private const val LAST_SYNC_TIME_KEY = "last_sync_time" // NEW: Track sync time
        
        // 🔒 Admin emails - keep this centralized
        private val ADMIN_EMAILS = listOf("alakbd2009@gmail.com", "alakbd@yahoo.com")
        
        // Sync intervals
        private const val SYNC_COOLDOWN_MS = 30000L // 30 seconds between syncs
        private const val RESUME_COOLDOWN_MS = 30000L // 30 second cooldown
    }

    // -----------------------
    // STORAGE VALIDATION & DEBUG
    // -----------------------

    /**
     * 🔍 Validate that credit data is properly persisted
     */
    fun isCreditDataPersisted(): Boolean {
        val available = prefs.getInt(AVAILABLE_CREDITS_KEY, -1)
        val used = prefs.getInt(USED_CREDITS_KEY, -1)
        val total = prefs.getInt(TOTAL_CREDITS_KEY, -1)
        
        Log.d("CreditStorage", "Available: $available, Used: $used, Total: $total")
        return available >= 0 && used >= 0 && total >= 0
    }

    /**
     * 📊 Debug current credit state
     */
    fun debugCreditState() {
        val available = getAvailableCredits()
        val used = getUsedCredits()
        val total = getTotalCredits()
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        val lastResume = prefs.getLong(LAST_RESUME_TIME_KEY, 0)
        
        Log.d("CreditDebug", "=== CREDIT MANAGER STATE ===")
        Log.d("CreditDebug", "Available: $available")
        Log.d("CreditDebug", "Used: $used")
        Log.d("CreditDebug", "Total: $total")
        Log.d("CreditDebug", "Last Sync: ${if (lastSync > 0) "${(System.currentTimeMillis() - lastSync)/1000}s ago" else "Never"}")
        Log.d("CreditDebug", "Last Resume: ${if (lastResume > 0) "${(System.currentTimeMillis() - lastResume)/1000}s ago" else "Never"}")
        Log.d("CreditDebug", "Storage Valid: ${isCreditDataPersisted()}")
        Log.d("CreditDebug", "Admin Mode: ${isAdminMode()}")
        Log.d("CreditDebug", "Firebase User: ${auth.currentUser?.uid ?: "null"}")
    }

    /**
     * 🗑️ Clear all credit data (use on logout)
     */
    fun clearCreditData() {
        prefs.edit().apply {
            remove(AVAILABLE_CREDITS_KEY)
            remove(USED_CREDITS_KEY)
            remove(TOTAL_CREDITS_KEY)
            remove(LAST_SYNC_TIME_KEY)
            remove(LAST_RESUME_TIME_KEY)
            remove(ADMIN_MODE_KEY) // Also clear admin mode on logout
            apply()
        }
        Log.d("CreditManager", "All credit data cleared")
    }

    // -----------------------
    // ADMIN SECURITY FUNCTIONS
    // -----------------------

    /**
     * 🔒 SECURE: Only set admin mode through explicit admin login
     */
    fun setAdminMode(isAdmin: Boolean) {
        prefs.edit().putBoolean(ADMIN_MODE_KEY, isAdmin).apply()
        Log.d("AdminSecurity", "Admin mode set to: $isAdmin")
    }

    /**
     * 🔒 SECURE: Check if current user is authorized for admin access
     */
    private fun isUserAuthorizedAdmin(): Boolean {
    val currentUser = auth.currentUser
    return when {
        currentUser == null -> false
        currentUser.email == "alakbd2009@gmail.com" -> true
        currentUser.uid == "your_admin_uid_here" -> true
        // Add other admin checks (from database, etc.)
        else -> false
    }
}

    /**
     * 🔒 SECURE: Comprehensive admin mode check
     */
    fun isAdminMode(): Boolean {
    // First, check if current user is authorized to be admin
    val isUserAdmin = isUserAuthorizedAdmin()
    
    // Get the current admin flag state
    val currentAdminFlag = prefs.getBoolean(ADMIN_MODE_KEY, false)
    
    // If user is authorized admin but flag is false, auto-enable admin mode
    if (isUserAdmin && !currentAdminFlag) {
        Log.d("AdminSecurity", "Auto-enabling admin mode for authorized user")
        setAdminMode(true)
        return true
    }
    
    // If user is NOT authorized admin but flag is true, auto-revoke
    if (!isUserAdmin && currentAdminFlag) {
        Log.w("AdminSecurity", "Auto-revoking admin access for unauthorized user")
        setAdminMode(false)
        return false
    }
    
    // Return the synchronized state
    return currentAdminFlag && isUserAdmin
}

    // -----------------------
    // NEW METHODS FOR RESUME GENERATION CONTROL
    // -----------------------
    
    /**
     * Check if user can generate a resume (has credits and not in cooldown)
     */
    fun canGenerateResume(): Boolean {
        val canGenerate = getAvailableCredits() > 0 && !hasUsedCreditRecently()
        Log.d("ResumeCheck", "Can generate resume: $canGenerate (credits: ${getAvailableCredits()}, recently: ${hasUsedCreditRecently()})")
        return canGenerate
    }

    /**
     * Use credit specifically for resume generation with additional checks
     */
    fun useCreditForResume(onComplete: (Boolean) -> Unit) {
        if (!canGenerateResume()) {
            Log.w("CreditUse", "Cannot use credit for resume - check failed")
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
            
            Log.d("CreditUse", "Resume credit used. New available: $newCredits")
            
            // Sync to Firebase (with offline support)
            syncCreditsToFirebase(newCredits, newUsed, onComplete)
        } else {
            Log.w("CreditUse", "No credits available for resume")
            onComplete(false)
        }
    }

    /**
     * Check if credit was used recently (within cooldown period)
     */
    fun hasUsedCreditRecently(): Boolean {
        val lastTime = prefs.getLong(LAST_RESUME_TIME_KEY, 0)
        val recentlyUsed = System.currentTimeMillis() - lastTime < RESUME_COOLDOWN_MS
        
        if (recentlyUsed) {
            val secondsLeft = (RESUME_COOLDOWN_MS - (System.currentTimeMillis() - lastTime)) / 1000
            Log.d("Cooldown", "Credit used recently. $secondsLeft seconds remaining")
        }
        
        return recentlyUsed
    }

    /**
     * Reset the resume generation cooldown (useful when leaving the WebView)
     */
    fun resetResumeCooldown() {
        prefs.edit().remove(LAST_RESUME_TIME_KEY).apply()
        Log.d("Cooldown", "Resume cooldown reset")
    }

    // -----------------------
    // Credits getters with validation
    // -----------------------
    fun getAvailableCredits(): Int {
        val credits = prefs.getInt(AVAILABLE_CREDITS_KEY, 0)
        // Validate credits aren't negative
        return if (credits < 0) 0 else credits
    }
    
    fun getUsedCredits(): Int {
        val credits = prefs.getInt(USED_CREDITS_KEY, 0)
        return if (credits < 0) 0 else credits
    }
    
    fun getTotalCredits(): Int {
        val credits = prefs.getInt(TOTAL_CREDITS_KEY, 0)
        return if (credits < 0) 0 else credits
    }

    // -----------------------
    // Credit operations with improved error handling
    // -----------------------
    fun useCredit(onComplete: (Boolean) -> Unit = {}) {
        val currentCredits = getAvailableCredits()
        if (currentCredits > 0) {
            val newCredits = currentCredits - 1
            val newUsed = getUsedCredits() + 1
            updateLocalCredits(newCredits, newUsed, getTotalCredits())
            Log.d("CreditUse", "Credit used. New available: $newCredits")
            syncCreditsToFirebase(newCredits, newUsed, onComplete)
        } else {
            Log.w("CreditUse", "Attempted to use credit but none available")
            onComplete(false)
        }
    }

    fun addCredits(amount: Int, onComplete: (Boolean) -> Unit = {}) {
        if (amount <= 0) {
            Log.w("CreditAdd", "Invalid credit amount: $amount")
            onComplete(false)
            return
        }
        
        val newCredits = getAvailableCredits() + amount
        val newTotal = getTotalCredits() + amount
        updateLocalCredits(newCredits, getUsedCredits(), newTotal)
        Log.d("CreditAdd", "Added $amount credits. New total: $newCredits")
        syncCreditsToFirebase(newCredits, getUsedCredits(), onComplete)
    }

    // -----------------------
    // SYNC IMPROVEMENTS - OFFLINE SUPPORT
    // -----------------------

    /**
     * 🔄 Sync local values from Firestore with cooldown protection
     */
    fun syncWithFirebase(onComplete: (Boolean, Int?) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            Log.w("CreditSync", "Cannot sync - no user logged in")
            onComplete(false, null)
            return
        }

        // Check if we synced recently to avoid spam
        val lastSync = prefs.getLong(LAST_SYNC_TIME_KEY, 0)
        if (System.currentTimeMillis() - lastSync < SYNC_COOLDOWN_MS) {
            Log.d("CreditSync", "Sync skipped - too recent")
            onComplete(true, getAvailableCredits()) // Return cached data
            return
        }

        Log.d("CreditSync", "Starting Firebase sync for user: ${user.uid}")

        db.collection("users").document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val available = document.getLong("availableCredits")?.toInt() ?: 0
                    val used = document.getLong("usedCredits")?.toInt() ?: 0
                    val total = document.getLong("totalCreditsEarned")?.toInt() ?: 0

                    // Update local storage
                    updateLocalCredits(available, used, total)
                    
                    // Update sync timestamp
                    prefs.edit().putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis()).apply()

                    Log.d("CreditSync", "Sync successful - Available: $available, Used: $used, Total: $total")
                    onComplete(true, available)
                } else {
                    Log.w("CreditSync", "User document not found in Firestore")
                    onComplete(false, null)
                }
            }
            .addOnFailureListener { exception ->
                Log.e("CreditSync", "Firebase sync failed: ${exception.message}")
                onComplete(false, null)
            }
    }

    /**
     * 🆘 Emergency sync - forces sync regardless of cooldown
     */
    fun forceSyncWithFirebase(onComplete: (Boolean, Int?) -> Unit) {
        Log.d("CreditSync", "Forcing emergency sync")
        prefs.edit().remove(LAST_SYNC_TIME_KEY).apply() // Clear cooldown
        syncWithFirebase(onComplete)
    }

    /**
     * 📱 Get credits with auto-sync if data seems stale
     */
    fun getCreditsWithAutoSync(onComplete: (Int) -> Unit) {
        if (!isCreditDataPersisted() || prefs.getLong(LAST_SYNC_TIME_KEY, 0) == 0L) {
            // First time or no data - sync first
            syncWithFirebase { success, credits ->
                onComplete(credits ?: getAvailableCredits())
            }
        } else {
            // Use cached data
            onComplete(getAvailableCredits())
        }
    }

    // -----------------------
    // Admin Firestore operations with security checks
    // -----------------------
    fun adminAddCreditsToUser(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        if (!isAdminMode()) {
            Log.w("AdminSecurity", "Unauthorized admin credit add attempt")
            onComplete(false)
            return
        }
        
        if (amount <= 0) {
            Log.w("AdminOps", "Invalid credit amount: $amount")
            onComplete(false)
            return
        }
        
        Log.d("AdminOps", "Adding $amount credits to user: $userId")
        
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to FieldValue.increment(amount.toLong()),
                "totalCreditsEarned" to FieldValue.increment(amount.toLong()),
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task -> 
            val success = task.isSuccessful
            Log.d("AdminOps", "Credit add operation: ${if (success) "SUCCESS" else "FAILED"}")
            onComplete(success) 
        }
    }

    fun adminSetUserCredits(userId: String, amount: Int, onComplete: (Boolean) -> Unit) {
        if (!isAdminMode()) {
            onComplete(false)
            return
        }
        
        db.collection("users").document(userId).update(
            mapOf(
                "availableCredits" to amount,
                "totalCreditsEarned" to amount,
                "lastUpdated" to System.currentTimeMillis()
            )
        ).addOnCompleteListener { task -> onComplete(task.isSuccessful) }
    }

    fun adminResetUserCredits(userId: String, onComplete: (Boolean) -> Unit) {
        if (!isAdminMode()) {
            onComplete(false)
            return
        }
        
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
    // Internal helpers with improved persistence
    // -----------------------
    private fun updateLocalCredits(available: Int, used: Int, total: Int) {
        prefs.edit().apply {
            putInt(AVAILABLE_CREDITS_KEY, available)
            putInt(USED_CREDITS_KEY, used)
            putInt(TOTAL_CREDITS_KEY, total)
            apply() // Ensure immediate persistence
        }
        Log.d("CreditUpdate", "Local credits updated - Available: $available, Used: $used, Total: $total")
    }

    private fun syncCreditsToFirebase(available: Int, used: Int, onComplete: (Boolean) -> Unit) {
        val user = auth.currentUser
        if (user == null) {
            Log.w("CreditSync", "Cannot sync to Firebase - no user")
            onComplete(false)
            return
        }

        val updateData = mapOf(
            "availableCredits" to available,
            "usedCredits" to used,
            "totalCreditsEarned" to getTotalCredits(),
            "lastUpdated" to System.currentTimeMillis(),
            "lastActive" to System.currentTimeMillis()
        )

        Log.d("CreditSync", "Syncing credits to Firebase: $updateData")

        db.collection("users").document(user.uid).update(updateData)
            .addOnSuccessListener {
                Log.d("CreditSync", "Firebase sync successful")
                // Update sync timestamp on successful sync
                prefs.edit().putLong(LAST_SYNC_TIME_KEY, System.currentTimeMillis()).apply()
                onComplete(true)
            }
            .addOnFailureListener { exception ->
                Log.e("CreditSync", "Firebase sync failed: ${exception.message}")
                // Even if Firebase fails, we keep local changes
                // They'll sync next time we have connection
                onComplete(false)
            }
    }
}
