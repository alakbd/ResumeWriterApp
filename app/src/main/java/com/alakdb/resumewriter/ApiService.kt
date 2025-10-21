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

fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(this.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)
    
    // FIXED: Simplified OkHttp Client without crashing interceptors
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { 
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor(SecureAuthInterceptor(userManager))
        .build()

    // FIXED: Secure Auth Interceptor with crash protection
    class SecureAuthInterceptor(
        private val userManager: UserManager
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return try {
                val originalRequest = chain.request()
                val url = originalRequest.url.toString()

                // Skip auth for public endpoints
                val publicEndpoints = listOf("/health", "/test", "/", "/api")
                if (publicEndpoints.any { url.contains(it) }) {
                    Log.d("SecureAuth", "🔓 Skipping auth for public endpoint: $url")
                    return chain.proceed(originalRequest)
                }

                // Get user ID - if null/empty, proceed without auth
                val userId = userManager.getCurrentUserId()
                
                if (userId.isNullOrBlank()) {
                    Log.w("SecureAuth", "⚠️ No user ID available for: ${originalRequest.method} $url")
                    return chain.proceed(originalRequest)
                }

                // Add auth headers and proceed
                val newRequest = originalRequest.newBuilder()
                    .addHeader("X-User-ID", userId)
                    .addHeader("User-Agent", "ResumeWriter-Android")
                    .build()

                Log.d("SecureAuth", "✅ Added X-User-ID: ${userId.take(8)}... for ${originalRequest.method} $url")
                chain.proceed(newRequest)
            } catch (e: Exception) {
                Log.e("SecureAuth", "❌ Interceptor crashed: ${e.message}")
                // Fallback: proceed with original request
                chain.proceed(chain.request())
            }
        }
    }

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // API Result wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0, val details: String? = null) : ApiResult<Nothing>()
    }

    // Test Connection with crash protection
    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            Log.d("NetworkTest", "Testing connection to: $baseUrl")
            
            val simpleClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val endpoints = listOf("/health", "/test", "/", "/api")
            
            for (endpoint in endpoints) {
                try {
                    Log.d("NetworkTest", "Trying endpoint: $endpoint")
                    val url = "$baseUrl$endpoint"
                    val request = Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("User-Agent", "ResumeWriter-Android")
                        .build()
                
                    val response = simpleClient.newCall(request).execute()
                    val body = response.body?.string()
                
                    Log.d("NetworkTest", "Response for $endpoint: ${response.code}")
                
                    if (response.isSuccessful && body != null) {
                        Log.d("NetworkTest", "✅ Success with endpoint: $endpoint")
                        return ApiResult.Success(JSONObject(body))
                    }
                } catch (e: Exception) {
                    Log.e("NetworkTest", "❌ Error with endpoint $endpoint: ${e.message}")
                }
            }
            
            ApiResult.Error("All endpoints failed", 0, "Could not connect to any server endpoint")
        } catch (e: Exception) {
            Log.e("NetworkTest", "❌ Connection test crashed: ${e.message}")
            ApiResult.Error("Connection test failed: ${e.message}")
        }
    }

    suspend fun waitForServerWakeUp(maxAttempts: Int = 6, delayBetweenAttempts: Long = 10000L): Boolean {
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

    // Enhanced API Methods with crash protection
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "Deducting credit for user: $userId")
            
            val requestBody = DeductCreditRequest(userId)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Deduct credit response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "❌ Deduct credit crashed: ${e.message}")
            ApiResult.Error("Deduct credit failed: ${e.message}")
        }
    }

    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "Generating resume with tone: $tone")
            
            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Generate resume response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "❌ Generate resume crashed: ${e.message}")
            ApiResult.Error("Resume generation failed: ${e.message}")
        }
    }

    // ADDED BACK: generateResumeFromFiles method
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

    // FIXED: getUserCredits with crash protection
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "🔄 Getting user credits...")

            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "💰 Credits response: ${response.code}")

                when {
                    response.isSuccessful -> {
                        try {
                            val jsonResponse = JSONObject(respBody)
                            Log.d("ApiService", "✅ Credits success: ${jsonResponse.toString()}")
                            ApiResult.Success(jsonResponse)
                        } catch (e: Exception) {
                            Log.e("ApiService", "❌ JSON parsing error for credits", e)
                            ApiResult.Error("Invalid server response format", response.code)
                        }
                    }
                    else -> {
                        Log.e("ApiService", "❌ Server error: HTTP ${response.code}")
                        ApiResult.Error("Server error: ${response.code}", response.code)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ApiService", "💥 getUserCredits crashed: ${e.message}")
            ApiResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    // Test secure authentication
    suspend fun testSecureAuth(): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/security-test")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Security test response: ${response.code}")

                if (!response.isSuccessful) {
                    return ApiResult.Error("Security test failed: ${response.message}", response.code)
                }

                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "❌ Security test crashed", e)
            ApiResult.Error("Security test failed: ${e.message}")
        }
    }

    // ADDED BACK: File utility methods
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

    // ADDED BACK: Base64 and file storage methods
    fun decodeBase64File(base64Data: String): ByteArray = 
        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    // Comprehensive Debug Method
    suspend fun debugAuthenticationFlow(): String {
        return try {
            val debugInfo = StringBuilder()
            debugInfo.appendLine("=== AUTHENTICATION FLOW DEBUG ===")
        
            // 1. Check Firebase Auth State
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("1. FIREBASE AUTH STATE:")
            debugInfo.appendLine("   • User ID: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            debugInfo.appendLine("   • Is Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
        
            // 2. Check UserManager State
            debugInfo.appendLine("2. USER MANAGER STATE:")
            debugInfo.appendLine("   • Is User Logged In: ${userManager.isUserLoggedIn()}")
            debugInfo.appendLine("   • User ID: ${userManager.getCurrentUserId() ?: "NULL"}")
            debugInfo.appendLine("   • User Email: ${userManager.getCurrentUserEmail() ?: "NULL"}")
        
            // 3. Test secure authentication
            debugInfo.appendLine("3. SECURE AUTH TEST:")
            val authTest = testSecureAuth()
            when (authTest) {
                is ApiResult.Success -> {
                    debugInfo.appendLine("   • ✅ Secure authentication SUCCESS")
                    debugInfo.appendLine("   • Response: ${authTest.data}")
                }
                is ApiResult.Error -> {
                    debugInfo.appendLine("   • ❌ Secure authentication FAILED: ${authTest.message}")
                }
            }
        
            debugInfo.appendLine("=== END DEBUG ===")
        
            val result = debugInfo.toString()
            Log.d("AuthDebug", result)
            result
        } catch (e: Exception) {
            "❌ Debug flow crashed: ${e.message}"
        }
    }

    // Utility method for LoginActivity compatibility
    fun initializeUserSession(userId: String?) {
        // This is a no-op for now, but exists for compatibility
        Log.d("ApiService", "User session initialized for: $userId")
    }
}
