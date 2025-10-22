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

    suspend fun testHeaderSending(): String {
        return try {
            val debugInfo = StringBuilder()
            debugInfo.appendLine("=== HEADER DEBUG TEST ===")
            
            // Step 1: Check Firebase Auth
            val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("1. FIREBASE AUTH:")
            debugInfo.appendLine("   • UID: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            
            // Step 2: Check UserManager
            debugInfo.appendLine("2. USERMANAGER:")
            debugInfo.appendLine("   • getCurrentUserId(): ${userManager.getCurrentUserId() ?: "NULL"}")
            debugInfo.appendLine("   • isUserLoggedIn(): ${userManager.isUserLoggedIn()}")
            
            // Step 3: Test direct request with manual headers
            debugInfo.appendLine("3. MANUAL HEADER TEST:")
            val testUid = userManager.getCurrentUserId() ?: firebaseUser?.uid
            if (testUid != null) {
                debugInfo.appendLine("   • Using UID: ${testUid.take(8)}...")
                
                val request = Request.Builder()
                    .url("$baseUrl/user/credits")
                    .addHeader("X-User-ID", testUid)
                    .addHeader("User-Agent", "ResumeWriter-Debug")
                    .get()
                    .build()
                
                try {
                    val response = simpleClient.newCall(request).execute()
                    val body = response.body?.string() ?: "{}"
                    
                    debugInfo.appendLine("   • Response Code: ${response.code}")
                    debugInfo.appendLine("   • Response Body: $body")
                    
                    if (response.code == 401) {
                        debugInfo.appendLine("   ❌ SERVER SAYS: UNAUTHORIZED")
                        debugInfo.appendLine("   💡 The UID might not exist in Firestore")
                    } else if (response.isSuccessful) {
                        debugInfo.appendLine("   ✅ SUCCESS: Headers are working!")
                    }
                    
                } catch (e: Exception) {
                    debugInfo.appendLine("   ❌ Request failed: ${e.message}")
                }
            } else {
                debugInfo.appendLine("   ❌ No UID available - cannot test headers")
            }
            
            // Step 4: Test without headers (should fail)
            debugInfo.appendLine("4. NO HEADER TEST:")
            val noHeaderRequest = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()
            
            try {
                val response = simpleClient.newCall(noHeaderRequest).execute()
                val body = response.body?.string() ?: "{}"
                debugInfo.appendLine("   • No-Header Response: ${response.code}")
                debugInfo.appendLine("   • Body: $body")
            } catch (e: Exception) {
                debugInfo.appendLine("   ❌ No-header request failed: ${e.message}")
            }
            
            debugInfo.appendLine("=== END DEBUG ===")
            debugInfo.toString()
            
        } catch (e: Exception) {
            "Debug failed: ${e.message}"
        }
    }
}
