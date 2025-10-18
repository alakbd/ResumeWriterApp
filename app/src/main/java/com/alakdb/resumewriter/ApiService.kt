package com.alakdb.resumewriter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    // Enhanced OkHttp Client with request/response logging
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        })
        .addInterceptor(DetailedLoggingInterceptor())
        .addInterceptor(AuthInterceptor(userManager))
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

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // API Result wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0, val details: String? = null) : ApiResult<Nothing>()
    }

    // Fixed AuthInterceptor
    class AuthInterceptor(private val userManager: UserManager) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val url = originalRequest.url.toString()
        
            Log.d("AuthInterceptor", "Processing: $url")
        
        try {
            // Skip auth for public endpoints
            val publicEndpoints = listOf("/health", "/test")
            val isPublic = publicEndpoints.any { url.contains(it) }
            
            if (isPublic) {
                Log.d("AuthInterceptor", "✅ Public endpoint - no auth needed: $url")
                return chain.proceed(originalRequest)
            }
            
            // For authenticated endpoints, get token safely
            val token = try {
                userManager.getUserToken()
            } catch (e: Exception) {
                Log.e("AuthInterceptor", "❌ Error getting token: ${e.message}")
                null
            }
            
            return if (!token.isNullOrBlank()) {
                Log.d("AuthInterceptor", "✅ Adding auth token to: $url")
                Log.d("AuthInterceptor", "⚠️ Note: Email may not be verified")
                
                val requestWithAuth = originalRequest.newBuilder()
                    .addHeader("X-Auth-Token", token)
                    .build()
                chain.proceed(requestWithAuth)
            } else {
                Log.w("AuthInterceptor", "⚠️ No token available for: $url - proceeding without auth")
                chain.proceed(originalRequest)
            }
        } catch (e: Exception) {
            Log.e("AuthInterceptor", "❌ Critical error: ${e.message}")
            return chain.proceed(originalRequest)
        }
    }
}
    
    // Improved token fetching with better error handling and token validation
    suspend fun getCurrentUserToken(): String? {
    return try {
        // Check if user is actually logged in first
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.e("AuthDebug", "❌ No user signed in! Can't fetch token.")
            userManager.clearUserToken()
            return null
        }

        // Check if we have a valid cached token
        if (userManager.isTokenValid()) {
            userManager.getUserToken()?.let { cachedToken ->
                if (cachedToken.isNotBlank()) {
                    Log.d("AuthDebug", "✅ Using cached token: ${cachedToken.length} chars")
                    // Verify it's a valid JWT
                    if (cachedToken.split(".").size == 3) {
                        return cachedToken
                    } else {
                        Log.w("AuthDebug", "⚠️ Cached token has invalid JWT format, fetching new one")
                    }
                }
            }
        }

        // Fetch new token from Firebase
        Log.d("AuthDebug", "🔄 Fetching fresh token from Firebase for user: ${currentUser.uid}")
        val tokenResult = currentUser.getIdToken(true).await()
        val token = tokenResult.token

        if (!token.isNullOrBlank()) {
            Log.d("AuthDebug", "✅ New token obtained: ${token.length} chars")
            userManager.saveUserToken(token) // This will clean and validate
            userManager.getUserToken() // Return the cleaned version
        } else {
            Log.e("AuthDebug", "❌ Token is null or empty")
            userManager.clearUserToken()
            null
        }
    } catch (e: Exception) {
        Log.e("AuthDebug", "❌ Error fetching Firebase token: ${e.message}")
        userManager.clearUserToken()
        null
    }
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

    


    private suspend fun testServerImmediately(): Boolean {
    return try {
        val request = Request.Builder()
            .url("$baseUrl/health")
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        val isSuccess = response.isSuccessful
        Log.d("ServerTest", "Immediate health check: $isSuccess (${response.code})")
        isSuccess
    } catch (e: Exception) {
        Log.e("ServerTest", "Immediate health check failed: ${e.message}")
        false
    }
}

    // Enhanced API Methods with better error handling
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        Log.d("ApiService", "Deducting credit for user: $userId")
        
        return try {
            // Ensure we have a valid token before making the request
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("Authentication required - please log in again", 401)
            }

            val requestBody = DeductCreditRequest(userId)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Deduct credit response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "Deduct credit failed: $errorMsg")
                    
                    // If it's an auth error, clear the token
                    if (response.code == 401) {
                        userManager.clearUserToken()
                    }
                    
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
            // Ensure we have a valid token before making the request
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("Authentication required - please log in again", 401)
            }

            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Generate resume response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "Generate resume failed: $errorMsg")
                    
                    // If it's an auth error, clear the token
                    if (response.code == 401) {
                        userManager.clearUserToken()
                    }
                    
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
            // Ensure we have a valid token before making the request
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("Authentication required - please log in again", 401)
            }

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
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Generate resume from files response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "File resume generation failed: $errorMsg")
                    
                    // If it's an auth error, clear the token
                    if (response.code == 401) {
                        userManager.clearUserToken()
                    }
                    
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
        
        // Get token safely without causing circular calls
        val token = getCurrentUserToken()
        if (token == null) {
            Log.e("ApiService", "No token available for credits request")
            return ApiResult.Error("Authentication required", 401)
        }

        val request = Request.Builder()
            .url("$baseUrl/user/credits")
            .get()
            .addHeader("User-Agent", "ResumeWriter-Android")
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            Log.d("ApiService", "Credits response: ${response.code}")

            if (!response.isSuccessful) {
                Log.e("ApiService", "Failed to fetch credits: HTTP ${response.code}")
                
                if (response.code == 401) {
                    userManager.clearUserToken()
                    return ApiResult.Error("Authentication failed - please log in again", 401)
                }
                
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

    // Comprehensive Debug Method
    suspend fun debugAuthenticationFlow(): String {
        val debugInfo = StringBuilder()
        debugInfo.appendLine("=== AUTHENTICATION FLOW DEBUG ===")
    
    // 1. Check Firebase Auth State
    val firebaseUser = FirebaseAuth.getInstance().currentUser
    debugInfo.appendLine("1. FIREBASE AUTH STATE:")
    debugInfo.appendLine("   • User ID: ${firebaseUser?.uid ?: "NULL"}")
    debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
    debugInfo.appendLine("   • Is Email Verified: ${firebaseUser?.isEmailVerified ?: false} ⚠️ THIS IS THE PROBLEM!")
    
    // 2. Check UserManager State
    debugInfo.appendLine("2. USER MANAGER STATE:")
    debugInfo.appendLine("   • Is User Logged In: ${userManager.isUserLoggedIn()}")
    debugInfo.appendLine("   • Is Token Valid: ${userManager.isTokenValid()}")
    
    val cachedToken = userManager.getUserToken()
    debugInfo.appendLine("   • Cached Token: ${if (!cachedToken.isNullOrBlank()) "PRESENT (${cachedToken.length} chars)" else "NULL"}")
    
    if (!cachedToken.isNullOrBlank()) {
        debugInfo.appendLine("   • Token Preview: '${cachedToken.take(50)}'")
    }
    
    // 3. Add critical warning about email verification
    if (firebaseUser != null && !firebaseUser.isEmailVerified) {
        debugInfo.appendLine("3. ⚠️ CRITICAL ISSUE DETECTED:")
        debugInfo.appendLine("   • Email is NOT verified: ${firebaseUser.email}")
        debugInfo.appendLine("   • Firebase tokens for unverified emails may be rejected by server")
        debugInfo.appendLine("   • SOLUTION: Verify your email or update server to allow unverified emails")
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
