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
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    // Enhanced OkHttp Client with better debugging
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor { msg -> 
            Log.d("NetworkLog", "🔗 $msg") 
        }.apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        })
        .addInterceptor(AuthInterceptor(userManager)) // Pass the same UserManager instance
        .build()

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // API Result wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0, val details: String? = null) : ApiResult<Nothing>()
    }

    // Fixed AuthInterceptor - Use the same UserManager instance and proper header format
    class AuthInterceptor(private val userManager: UserManager) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return try {
                val originalRequest = chain.request()
                
                // Skip auth for public endpoints
                if (isPublicEndpoint(originalRequest.url.toString())) {
                    Log.d("AuthInterceptor", "Skipping auth for public endpoint")
                    return chain.proceed(originalRequest)
                }

                val token = userManager.getUserToken()
                val requestBuilder = originalRequest.newBuilder()

                if (!token.isNullOrBlank()) {
                    // Use Bearer token format as expected by your server
                    requestBuilder.addHeader("Authorization", "Bearer $token")
                    Log.d("AuthInterceptor", "✅ Added Authorization header with Bearer token")
                } else {
                    Log.w("AuthInterceptor", "⚠️ No token found — request will be unauthenticated")
                    // Don't throw exception, just proceed without token (will get 401 from server)
                }

                val request = requestBuilder.build()
                chain.proceed(request)
                
            } catch (e: Exception) {
                Log.e("AuthInterceptor", "🚨 Exception in AuthInterceptor: ${e.message}")
                // Create a mock error response instead of throwing
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Authentication error")
                    .body("{\"error\": \"Authentication failed: ${e.message}\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        private fun isPublicEndpoint(url: String): Boolean {
            return url.contains("/health") || url.contains("/warmup") || url.endsWith("/")
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
                        Log.d("AuthDebug", "✅ Using cached token")
                        return cachedToken
                    }
                }
            }

            // Fetch new token from Firebase
            Log.d("AuthDebug", "🔄 Fetching fresh token from Firebase for user: ${currentUser.uid}")
            val tokenResult = currentUser.getIdToken(true).await()
            val token = tokenResult.token

            if (!token.isNullOrBlank()) {
                userManager.saveUserToken(token)
                Log.d("AuthDebug", "✅ New token obtained and saved successfully")
                token
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
        
        val endpoints = listOf("/health", "/")
        
        for (endpoint in endpoints) {
            try {
                Log.d("NetworkTest", "Trying endpoint: $endpoint")
                val url = "$baseUrl$endpoint"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("User-Agent", "ResumeWriter-Android")
                    .build()
                
                val response = client.newCall(request).execute()
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

    // Warm up server
    suspend fun warmUpServer(): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/warmup")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: "{}"
                if (!response.isSuccessful) {
                    return ApiResult.Error("Warm-up failed", response.code)
                }
                ApiResult.Success(JSONObject(body))
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Warm-up exception")
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
            // Ensure we have a valid token before making the request
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("Authentication required - please log in again", 401)
            }

            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"

                if (!response.isSuccessful) {
                    Log.e("ApiService", "Failed to fetch credits: HTTP ${response.code}")
                    
                    // If it's an auth error, clear the token
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
                message = "Exception while fetching credits: ${e.message}",
                code = -1
            )
        }
    }

    // Debug method to check authentication state
    suspend fun debugAuthState(): String {
        val currentUser = FirebaseAuth.getInstance().currentUser
        val token = getCurrentUserToken()
        val tokenValid = userManager.isTokenValid()
        val userLoggedIn = userManager.isUserLoggedIn()
        
        return """
            === AUTH DEBUG ===
            Firebase User: ${currentUser?.uid ?: "NULL"}
            UserManager Logged In: $userLoggedIn
            Token Valid: $tokenValid
            Token Present: ${!token.isNullOrBlank()}
            Token Preview: ${token?.take(10) ?: "NULL"}...
        """.trimIndent()
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
