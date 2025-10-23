package com.alakdb.resumewriter

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DebugApiService(private val context: Context) {
    
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)
    
    // Simple client without interceptors for testing
    private val simpleClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

  private fun testHeaderSending() {
    lifecycleScope.launch {
        try {
            binding.tvGeneratedResume.text = "Testing headers..."
            
            val result = apiService.getUserCredits()
            when (result) {
                is ApiService.ApiResult.Success -> {
                    val credits = result.data.optInt("available_credits", 0)
                    showMessage("✅ Headers working! Credits: $credits")
                    binding.tvGeneratedResume.text = "Headers OK - Credits: $credits"
                }
                is ApiService.ApiResult.Error -> {
                    showMessage("❌ Header issue: ${result.message}")
                    binding.tvGeneratedResume.text = "Header failed: ${result.message}"
                }
            }
        } catch (e: Exception) {
            showMessage("Test failed: ${e.message}")
        }
    }
}
}
