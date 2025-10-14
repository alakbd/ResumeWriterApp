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
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    // Enhanced OkHttp Client with better debugging
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor { msg -> 
            Log.d("NetworkLog", "🔗 $msg") 
        }.apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        })
        .addInterceptor(ErrorInterceptor()) // Custom error interceptor
        .build()

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // API Result Wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0, val details: String? = null) : ApiResult<Nothing>()
    }

    // Custom Interceptor for better error handling
    class ErrorInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            try {
                val response = chain.proceed(request)
                
                if (!response.isSuccessful) {
                    Log.e("NetworkError", "HTTP ${response.code} for ${request.url}")
                }
                
                return response
            } catch (e: Exception) {
                Log.e("NetworkError", "Request failed: ${e.message}", e)
                throw e
            }
        }
    }

    // Current User Token with better error handling
    suspend fun getCurrentUserToken(): String? {
        return try {
            // Try to get cached token first
            userManager.getUserToken()?.let { return it }
            
            // Fetch new token from Firebase
            val currentUser = FirebaseAuth.getInstance().currentUser 
                ?: return null.also { Log.d("Auth", "No current user") }
                
            val tokenResult = currentUser.getIdToken(true).await()
            val token = tokenResult.token
            
            if (token != null) {
                userManager.saveUserToken(token)
                Log.d("Auth", "Token obtained successfully")
                token
            } else {
                Log.e("Auth", "Token is null")
                null
            }
        } catch (e: Exception) {
            Log.e("Auth", "Error fetching Firebase token: ${e.message}", e)
            null
        }
    }

    // Enhanced Authentication Helper
    private suspend fun getAuthIdentifier(): String? {
        return try {
            val token = getCurrentUserToken()
            if (token != null) {
                "Bearer $token".also { 
                    Log.d("Auth", "Using auth token: ${it.take(20)}...") 
                }
            } else {
                Log.e("Auth", "No auth token available")
                null
            }
        } catch (e: Exception) {
            Log.e("Auth", "Auth identifier error: ${e.message}", e)
            null
        }
    }

    // Enhanced Test Connection with detailed logging
    suspend fun testConnection(): ApiResult<JSONObject> {
        Log.d("NetworkTest", "Testing connection to: $baseUrl")
        
        val endpoints = listOf("/health", "/", "/user/credits")
        
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
                Log.e("NetworkTest", "❌ Error with endpoint $endpoint: ${e.message}", e)
            }
        }
        
        return ApiResult.Error(
            "All endpoints failed", 
            0, 
            "Could not connect to any server endpoint"
        )
    }

    // Enhanced API Methods with better error handling
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        Log.d("ApiService", "Deducting credit for user: $userId")
        
        return try {
            val auth = getAuthIdentifier() 
                ?: return ApiResult.Error("User authentication unavailable", 401, "No auth token")
            
            val requestBody = DeductCreditRequest(userId)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ResumeWriter-Android")
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
            Log.e("ApiService", "Deduct credit exception: ${e.message}", e)
            ApiResult.Error("Deduct credit failed: ${e.message}", errorCode, e.stackTraceToString())
        }
    }

    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        Log.d("ApiService", "Generating resume with tone: $tone")
        
        return try {
            val auth = getAuthIdentifier() 
                ?: return ApiResult.Error("User authentication unavailable", 401, "No auth token")
            
            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "ResumeWriter-Android")
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
            Log.e("ApiService", "Generate resume exception: ${e.message}", e)
            ApiResult.Error("Resume generation failed: ${e.message}", errorCode, e.stackTraceToString())
        }
    }

    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        Log.d("ApiService", "Generating resume from files")
        
        return try {
            val auth = getAuthIdentifier() 
                ?: return ApiResult.Error("User authentication unavailable", 401, "No auth token")
            
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
                .addHeader("X-Auth-Token", auth)
                .addHeader("User-Agent", "ResumeWriter-Android")
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
            Log.e("ApiService", "File resume generation exception: ${e.message}", e)
            ApiResult.Error("File resume generation failed: ${e.message}", errorCode, e.stackTraceToString())
        }
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
        Log.d("ApiService", "Getting user credits")
        
        return try {
            val auth = getAuthIdentifier() 
                ?: return ApiResult.Error("User authentication unavailable", 401, "No auth token")
            
            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .addHeader("X-Auth-Token", auth)
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Get credits response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = handleErrorResponse(response)
                    Log.e("ApiService", "Get credits failed: $errorMsg")
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            val errorCode = getErrorCode(e)
            Log.e("ApiService", "Get credits exception: ${e.message}", e)
            ApiResult.Error("Get credits failed: ${e.message}", errorCode, e.stackTraceToString())
        }
    }

    // WarmUp Server

suspend fun warmUpServer(): ApiResult<JSONObject> {
    Log.d("WarmUp", "🔥 Warming up server...")
    
    return try {
        // Try health endpoint first (lightweight)
        val healthUrl = "$baseUrl/health"
        val healthRequest = Request.Builder()
            .url(healthUrl)
            .get()
            .addHeader("User-Agent", "ResumeWriter-Android-WarmUp")
            .build()
        
        client.newCall(healthRequest).execute().use { response ->
            if (response.isSuccessful) {
                Log.d("WarmUp", "✅ Server is already warm")
                return ApiResult.Success(JSONObject().put("status", "warm"))
            }
        }
        
        // If health check fails or we want to ensure full warm-up, try a lightweight API call
        Log.d("WarmUp", "⚠️ Health check didn't confirm warm state, trying credits endpoint...")
        
        val creditsResult = getUserCredits()
        when (creditsResult) {
            is ApiResult.Success -> {
                Log.d("WarmUp", "✅ Server warmed up successfully")
                creditsResult
            }
            is ApiResult.Error -> {
                // Even if it fails, we tried to wake up the server
                Log.w("WarmUp", "⚠️ Warm-up attempt completed (server may be waking up)")
                ApiResult.Success(JSONObject().put("warmup_attempt", "completed"))
            }
        }
    } catch (e: Exception) {
        Log.w("WarmUp", "⚠️ Warm-up encountered error but may have woken server: ${e.message}")
        ApiResult.Success(JSONObject().put("warmup_attempt", "completed_with_error"))
    }
}

// Enhanced API methods with automatic warm-up
suspend fun generateResumeWithWarmUp(
    resumeText: String, 
    jobDescription: String, 
    tone: String = "Professional"
): ApiResult<JSONObject> {
    // Warm up first, then proceed with actual call
    warmUpServer()
    return generateResume(resumeText, jobDescription, tone)
}

suspend fun generateResumeFromFilesWithWarmUp(
    resumeUri: Uri, 
    jobDescUri: Uri, 
    tone: String = "Professional"
): ApiResult<JSONObject> {
    warmUpServer()
    return generateResumeFromFiles(resumeUri, jobDescUri, tone)
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
            Log.e("ApiService", "Error converting URI to file: ${e.message}", e)
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
            val errorMessage = "HTTP ${response.code}: ${response.message}. Body: $body"
            Log.e("NetworkError", errorMessage)
            errorMessage
        } catch (e: Exception) {
            val errorMessage = "HTTP ${response.code}: ${response.message}"
            Log.e("NetworkError", errorMessage)
            errorMessage
        }
    }

    private fun getErrorCode(e: Exception): Int = when (e) {
        is java.net.SocketTimeoutException -> {
            Log.e("NetworkError", "Socket timeout", e)
            1002
        }
        is java.net.UnknownHostException -> {
            Log.e("NetworkError", "Unknown host - check internet connection", e)
            1003
        }
        is IOException -> {
            Log.e("NetworkError", "IO Exception", e)
            1001
        }
        else -> {
            Log.e("NetworkError", "Unknown error: ${e::class.java.name}", e)
            1000
        }
    }
}
