package com.alakdb.resumewriter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MultipartBody
import okhttp3.RequestBody

fun String.sha256(): String {
    return try {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(this.toByteArray(Charsets.UTF_8))
        hashBytes.joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        Log.e("SHA256", "Error hashing string", e)
        ""
    }
}

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)
    
    // SAFE: OkHttp Client with minimal interceptors
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(SafeAuthInterceptor(userManager))
        .build()

    // FIXED: Enhanced SafeAuthInterceptor with proper user ID handling
    class SafeAuthInterceptor(private val userManager: UserManager) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            
            // Always proceed with original request if anything fails
            return try {
                val userId = userManager.getCurrentUserId()
                
                Log.d("AuthInterceptor", "🔐 Interceptor - User ID: ${userId ?: "NULL"}")
                Log.d("AuthInterceptor", "🔐 Request URL: ${originalRequest.url}")
                
                if (!userId.isNullOrBlank()) {
                    val newRequest = originalRequest.newBuilder()
                        .addHeader("X-User-ID", userId)
                        .addHeader("User-Agent", "ResumeWriter-Android")
                        .build()
                    
                    Log.d("AuthInterceptor", "✅ Added X-User-ID: ${userId.take(8)}...")
                    Log.d("AuthInterceptor", "✅ Headers: ${newRequest.headers}")
                    
                    chain.proceed(newRequest)
                } else {
                    Log.w("AuthInterceptor", "⚠️ No user ID available - proceeding without X-User-ID")
                    chain.proceed(originalRequest)
                }
            } catch (e: Exception) {
                Log.e("AuthInterceptor", "❌ Interceptor crashed: ${e.message}")
                // Fallback: proceed with original request
                chain.proceed(originalRequest)
            }
        }
    }

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // API Result wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

    private fun uriToFile(uri: Uri): File {
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Failed to open URI: $uri")
            val file = File.createTempFile("upload_", "_tmp", context.cacheDir)
            input.use { inputStream -> 
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            file
        } catch (e: Exception) {
            Log.e("ApiService", "Error converting URI to file: ${e.message}")
            throw e
        }
    }

    private fun File.asRequestBody(mediaType: MediaType): RequestBody {
        return this.inputStream().readBytes().toRequestBody(mediaType)
    }

    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "Generating resume from files")
            
            val resumeFile = uriToFile(resumeUri)
            val jobDescFile = uriToFile(jobDescUri)

            Log.d("ApiService", "Files: resume=${resumeFile.name}, jobDesc=${jobDescFile.name}")

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart("resume_file", resumeFile.name, 
                    resumeFile.asRequestBody("application/pdf".toMediaType()))
                .addFormDataPart("job_description_file", jobDescFile.name, 
                    jobDescFile.asRequestBody("application/pdf".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Generate resume from files response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "❌ File resume generation crashed: ${e.message}")
            ApiResult.Error("File resume generation failed: ${e.message}")
        }
    }
    
    // SAFE: Test Connection
    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            val simpleClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = simpleClient.newCall(request).execute()
            val body = response.body?.string()
            
            if (response.isSuccessful && body != null) {
                ApiResult.Success(JSONObject(body))
            } else {
                ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Connection failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun waitForServerWakeUp(maxAttempts: Int = 12, delayBetweenAttempts: Long = 10000L): Boolean {
        return try {
            Log.d("ServerWakeUp", "🔄 Waiting for server to wake up...")
            
            repeat(maxAttempts) { attempt ->
                try {
                    Log.d("ServerWakeUp", "Attempt ${attempt + 1}/$maxAttempts")
                    val result = testConnection()
                
                    when (result) {
                        is ApiResult.Success -> {
                            Log.d("ServerWakeUp", "✅ Server is awake and responding!")
                            return true
                        }
                        is ApiResult.Error -> {
                            Log.w("ServerWakeUp", "⏳ Server not ready: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ServerWakeUp", "🚨 Connection attempt ${attempt + 1} failed: ${e.message}")
                }
            
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(delayBetweenAttempts)
                }
            }
            
            Log.e("ServerWakeUp", "❌ Server failed to wake up after $maxAttempts attempts")
            false
        } catch (e: Exception) {
            Log.e("ServerWakeUp", "❌ Server wakeup crashed: ${e.message}")
            false
        }
    }
    
    // SAFE: Deduct Credit
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val requestBody = DeductCreditRequest(userId)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    // SAFE: Generate Resume
    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    // Make sure these utility methods exist:
    fun decodeBase64File(base64Data: String): ByteArray = 
        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    // FIXED: Enhanced Get User Credits with better debugging
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "🔄 Getting user credits from: $baseUrl/user/credits")
            
            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "💰 Credits response: ${response.code} - Body: $respBody")
                
                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(respBody)
                        Log.d("ApiService", "✅ Credits success: ${jsonResponse.toString()}")
                        ApiResult.Success(jsonResponse)
                    } catch (e: Exception) {
                        Log.e("ApiService", "❌ JSON parsing error for credits", e)
                        ApiResult.Error("Invalid server response format", response.code)
                    }
                } else {
                    Log.e("ApiService", "❌ Server error: HTTP ${response.code}")
                    ApiResult.Error("Server error: ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            Log.e("ApiService", "💥 Network exception while fetching credits: ${e.message}", e)
            ApiResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }
    
    // SAFE: Test Secure Auth
    suspend fun testSecureAuth(): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/security-test")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Test failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ENHANCED: Debug Authentication Flow
    suspend fun debugAuthenticationFlow(): String {
        return try {
            val debugInfo = StringBuilder()
            debugInfo.appendLine("=== AUTHENTICATION DEBUG ===")
            
            // 1. Firebase Auth State
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("1. FIREBASE AUTH STATE:")
            debugInfo.appendLine("   • UID: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            debugInfo.appendLine("   • Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
            
            // 2. UserManager State
            debugInfo.appendLine("2. USERMANAGER STATE:")
            debugInfo.appendLine("   • Is Logged In: ${userManager.isUserLoggedIn()}")
            debugInfo.appendLine("   • User ID: ${userManager.getCurrentUserId() ?: "NULL"}")
            debugInfo.appendLine("   • User Email: ${userManager.getCurrentUserEmail() ?: "NULL"}")
            
            // 3. Test if UserManager and Firebase are in sync
            debugInfo.appendLine("3. SYNC CHECK:")
            val firebaseUid = firebaseUser?.uid
            val managerUid = userManager.getCurrentUserId()
            if (firebaseUid != null && managerUid != null) {
                debugInfo.appendLine("   • UID Match: ${firebaseUid == managerUid}")
                if (firebaseUid != managerUid) {
                    debugInfo.appendLine("   ⚠️ UID MISMATCH! Firebase: $firebaseUid, Manager: $managerUid")
                }
            } else {
                debugInfo.appendLine("   • One or both UIDs are NULL")
            }
            
            // 4. Test authentication with credits endpoint (the one that's failing)
            debugInfo.appendLine("4. CREDITS ENDPOINT TEST:")
            val creditsRequest = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            try {
                val response = client.newCall(creditsRequest).execute()
                val respBody = response.body?.string() ?: "{}"
                debugInfo.appendLine("   • HTTP Status: ${response.code}")
                debugInfo.appendLine("   • Response: $respBody")
                
                if (response.code == 401 || respBody.contains("Missing X-User-ID")) {
                    debugInfo.appendLine("   ❌ AUTH FAILED: X-User-ID header missing or invalid")
                    debugInfo.appendLine("   💡 Check if UserManager.getCurrentUserId() is returning the correct UID")
                } else if (response.isSuccessful) {
                    debugInfo.appendLine("   ✅ AUTH SUCCESS")
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   ❌ Request failed: ${e.message}")
            }
            
            // 5. Test secure auth endpoint
            debugInfo.appendLine("5. SECURE AUTH TEST:")
            val authTest = testSecureAuth()
            val authMessage = when (authTest) {
                is ApiResult.Success -> "SUCCESS - ${authTest.data}"
                is ApiResult.Error -> "FAILED: ${authTest.message}"
            }
            debugInfo.appendLine("   • Result: $authMessage")
            
            debugInfo.appendLine("=== END DEBUG ===")
            debugInfo.toString()
        } catch (e: Exception) {
            "Debug failed: ${e.message}"
        }
    }

    // Add this method to force sync UserManager with Firebase
    fun forceSyncUserManager() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            val uid = firebaseUser.uid
            val email = firebaseUser.email ?: ""
            
            userManager.saveUserDataLocally(email, uid)
            Log.d("ApiService", "🔄 Force synced UserManager with Firebase: $uid")
        } else {
            Log.w("ApiService", "⚠️ Cannot sync: No Firebase user")
        }
    }

    // Compatibility method
    fun initializeUserSession(userId: String?) {
        // No-op for compatibility
    }
}
