package com.example.resumewriter

import android.content.Context
import android.content.SharedPreferences

class CreditManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cv_credits_prefs", Context.MODE_PRIVATE)
    private val CREDITS_KEY = "available_cv_credits"
    private val USED_CREDITS_KEY = "used_cv_credits"
    private val TOTAL_CREDITS_KEY = "total_cv_credits_earned"
    
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
    
    fun getCreditStats(): String {
        val available = getAvailableCredits()
        val used = getUsedCredits()
        val totalEarned = getTotalCreditsEarned()
        return "Used: $used | Total Earned: $totalEarned"
    }
}
