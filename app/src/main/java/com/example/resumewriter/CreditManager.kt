package com.example.resumewriter

import com.example.resumewriter.BuildConfig
import android.content.Context
import android.content.SharedPreferences

class CreditManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cv_credits_prefs", Context.MODE_PRIVATE)
    private val CREDITS_KEY = "available_cv_credits"
    private val USED_CREDITS_KEY = "used_cv_credits"
    private val TOTAL_CREDITS_KEY = "total_cv_credits_earned"
    private val IS_ADMIN_KEY = "is_admin_user"
    
    // Admin password (change this to something secure)
    private val ADMIN_PASSWORD = "admin123" // TODO: Change this!
    
    // Check if current user is admin
    fun authenticateAdmin(password: String): Boolean {
        val isAdmin = password == ADMIN_PASSWORD
        if (isAdmin) {
            prefs.edit().putBoolean(IS_ADMIN_KEY, true).apply()
        }
        return isAdmin
    }
    
    fun isAdminMode(): Boolean {
        return prefs.getBoolean(IS_ADMIN_KEY, false) || BuildConfig.DEBUG
    }
    
    // Admin function: Add credits to user account
    fun adminAddCredits(credits: Int) {
        if (isAdminMode()) {
            addCredits(credits)
        }
    }
    
    // Admin function: Set specific credit amount
    fun adminSetCredits(credits: Int) {
        if (isAdminMode()) {
            val currentUsed = getUsedCredits()
            val currentTotal = getTotalCreditsEarned()
            val newTotal = currentTotal + (credits - getAvailableCredits())
            
            prefs.edit().apply {
                putInt(CREDITS_KEY, credits)
                putInt(TOTAL_CREDITS_KEY, newTotal)
            }.apply()
        }
    }
    
    // Admin function: Generate CV without using credits
    fun adminGenerateCV(): Boolean {
        return if (isAdminMode()) {
            // Log admin usage but don't deduct credits
            val used = getUsedCredits()
            prefs.edit().putInt(USED_CREDITS_KEY, used + 1).apply()
            true
        } else {
            useCredit() // Normal user flow
        }
    }
    
    // Admin function: Reset user credits
    fun adminResetCredits() {
        if (isAdminMode()) {
            prefs.edit().apply {
                putInt(CREDITS_KEY, 0)
                putInt(USED_CREDITS_KEY, 0)
                putInt(TOTAL_CREDITS_KEY, 0)
            }.apply()
        }
    }
    
    // Admin function: Get user statistics
    fun adminGetUserStats(): String {
        return if (isAdminMode()) {
            "User Stats:\n" +
            "Available Credits: ${getAvailableCredits()}\n" +
            "Used Credits: ${getUsedCredits()}\n" +
            "Total Earned: ${getTotalCreditsEarned()}\n" +
            "Admin Mode: Active"
        } else {
            "Admin access required"
        }
    }
    
    // Existing functions remain the same...
    fun getAvailableCredits(): Int {
        return prefs.getInt(CREDITS_KEY, 0)
    }
    
    fun getUsedCredits(): Int {
        return prefs.getInt(USED_CREDITS_KEY, 0)
    }
    
    fun getTotalCreditsEarned(): Int {
        return prefs.getInt(TOTAL_CREDITS_KEY, 0)
    }
    
    fun addCredits(credits: Int) {
        val current = getAvailableCredits()
        val totalEarned = getTotalCreditsEarned()
        prefs.edit().apply {
            putInt(CREDITS_KEY, current + credits)
            putInt(TOTAL_CREDITS_KEY, totalEarned + credits)
        }.apply()
    }
    
    fun useCredit(): Boolean {
        val current = getAvailableCredits()
        if (current > 0) {
            val used = getUsedCredits()
            prefs.edit().apply {
                putInt(CREDITS_KEY, current - 1)
                putInt(USED_CREDITS_KEY, used + 1)
            }.apply()
            return true
        }
        return false
    }
    
    fun logoutAdmin() {
        prefs.edit().putBoolean(IS_ADMIN_KEY, false).apply()
    }
}
