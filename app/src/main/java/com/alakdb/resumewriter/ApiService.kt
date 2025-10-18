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


class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)
    
    // ✅ Use BuildConfig as you had before
    private val appSecretKey = BuildConfig.APP_SECRET_KEY

    // Enhanced OkHttp Client with request/response logging
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        })
        .addInterceptor(DetailedLoggingInterceptor())
        .addInterceptor(SecureAuthInterceptor(userManager, appSecretKey))
        .build()

    class DetailedLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            
            // Log request details
            Log.d("Network", "⬆️ REQUEST: ${request.method} ${request.url}")
            request.headers.forEach { (name, value) ->
                Log.d("Network", "   $name: $value")
            }
            
            val startTime = System.currentTimeMillis()
            val response = chain.proceed(request)
            val endTime = System.currentTimeMillis()
            
            // Log response details
            Log.d("Network", "⬇️ RESPONSE: ${response.code} ${response.message} (${endTime - startTime}ms)")
            response.headers.forEach { (name, value) ->
                Log.d("Network", "   $name: $value")
            }
            
            return response
        }
    }

    fun String.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(this.toByteArray(Charsets.UTF_8))
    return hashBytes.joinToString("") { "%02x".format(it) }
}
    
    // Secure Auth Interceptor for spoof-proof UID authentication
    class SecureAuthInterceptor(
        private val userManager: UserManager,
        private val appSecretKey: String
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()

            // Skip auth for public endpoints
            val publicEndpoints = listOf("/health", "/test")
            if (publicEndpoints.any { url.contains(it) }) {
                return chain.proceed(originalRequest)
            }

            // Get user ID from UserManager
            val userId = userManager.getCurrentUserId()
            if (userId.isNullOrBlank()) {
                Log.w("SecureAuth", "No user ID available for: $url")
                return chain.proceed(originalRequest)
            }

            // Generate security headers using the BuildConfig key
            val timestamp = System.currentTimeMillis().toString()
            val signature = generateSignature(userId, timestamp, url, appSecretKey)

            val newRequest = originalRequest.newBuilder()
                .addHeader("X-User-ID", userId)
                .addHeader("X-Timestamp", timestamp)
                .addHeader("X-Signature", signature)
                .addHeader("X-Request-Path", url)
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()
            
            Log.d("SecureAuth", "Added secure headers for user: ${userId.take(8)}...")
            return chain.proceed(newRequest)
        }

        private fun generateSignature(userId: String, timestamp: String, url: String, secret: String): String {
            val data = "$userId|$timestamp|$url|$secret"
            return data.sha256()
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

    fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(this.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
        }

    
    // Enhanced Test Connection with better error handling
    suspend fun testConnection(): ApiResult<JSONObject> {
        Log.d("NetworkTest", "Testing connection to: $baseUrl")
    
        // Use a simple client without interceptors for connection testing
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
                } else {
                    Log.w("NetworkTest", "❌ Failed with endpoint $endpoint: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("NetworkTest", "❌ Error with endpoint $endpoint: ${e.message}")
            }
        }
    
        return ApiResult.Error(
            "All endpoints failed", 
            0, 
            "Could not connect to any server endpoint"
        )
    }

    suspend fun waitForServerWakeUp(maxAttempts: Int = 8, delayBetweenAttempts: Long = 5000L): Boolean {
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
                        if (result.code in 500..599) {
                            Log.w("ServerWakeUp", "⏳ Server still waking up (HTTP ${result.code}), waiting...")
                        } else {
                            Log.w("ServerWakeUp", "⚠️ Connection issue (HTTP ${result.code}): ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ServerWakeUp", "🚨 Connection attempt ${attempt + 1} failed: ${e.message}")
            }
        
            // Wait before next attempt (except on last attempt)
            if (attempt < maxAttempts - 1) {
                Log.d("ServerWakeUp", "⏰ Waiting ${delayBetweenAttempts}ms before next attempt...")
                kotlinx.coroutines.delay(delayBetweenAttempts)
            }
        }
    
        Log.e("ServerWakeUp", "❌ Server failed to wake up after $maxAttempts attempts")
        return false
    }

    // Enhanced API Methods with secure UID authentication
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        Log.d("ApiService", "Deducting credit for user: $userId")
        
        return try {
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
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "Deduct credit failed: $errorMsg")
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            val errorCode = getErrorCode(e)
            Log.e("ApiService", "Deduct credit exception: ${e.message}")
            ApiResult.Error("Deduct credit failed: ${e.message}", errorCode)
        }
    }

    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        Log.d("ApiService", "Generating resume with tone: $tone")
        
        return try {
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
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "Generate resume failed: $errorMsg")
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            val errorCode = getErrorCode(e)
            Log.e("ApiService", "Generate resume exception: ${e.message}")
            ApiResult.Error("Resume generation failed: ${e.message}", errorCode)
        }
    }

    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        Log.d("ApiService", "Generating resume from files")
        
        return try {
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
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "File resume generation failed: $errorMsg")
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            val errorCode = getErrorCode(e)
            Log.e("ApiService", "File resume generation exception: ${e.message}")
            ApiResult.Error("File resume generation failed: ${e.message}", errorCode)
        }
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "Getting user credits...")

            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Credits response: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e("ApiService", "Failed to fetch credits: HTTP ${response.code}")
                    return ApiResult.Error(
                        message = "Failed to get credits: ${response.message}",
                        code = response.code
                    )
                }

                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "Exception while fetching credits", e)
            ApiResult.Error(
                message = "Network error: ${e.message}",
                code = -1
            )
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
            Log.e("ApiService", "Security test exception", e)
            ApiResult.Error("Security test failed: ${e.message}", -1)
        }
    }

    // Comprehensive Debug Method (updated for new auth)
    suspend fun debugAuthenticationFlow(): String {
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
        return result
    }

    // Utilities
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

    fun decodeBase64File(base64Data: String): ByteArray = 
        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    // Enhanced Error Handling
    private fun handleErrorResponse(response: Response): String {
        return try {
            val body = response.body?.string()
            "HTTP ${response.code}: ${response.message}. Body: $body"
        } catch (e: Exception) {
            "HTTP ${response.code}: ${response.message}"
        }
    }

    private fun getErrorCode(e: Exception): Int = when (e) {
        is java.net.SocketTimeoutException -> 1002
        is java.net.UnknownHostException -> 1003
        is IOException -> 1001
        else -> 1000
    }
}
