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
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    // Enhanced OkHttp Client with better debugging
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS) // Increased from 10 to 30
        .readTimeout(30, TimeUnit.SECONDS)    // Increased from 10 to 30
        .writeTimeout(30, TimeUnit.SECONDS)   // Increased from 10 to 30
        .addInterceptor(HttpLoggingInterceptor { msg -> 
            Log.d("NetworkLog", "🔗 $msg") 
        }.apply { 
            level = HttpLoggingInterceptor.Level.BODY 
        })
        .addInterceptor(ErrorInterceptor(context))  // Custom error interceptor
        .addInterceptor(AuthInterceptor(context))
        .build()

    // Data Classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

  
    // API Result wrapper
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0, val details: String? = null) : ApiResult<Nothing>()
    }

    // Example: warm up server
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


    
    // Custom Interceptor for better error handling
    class ErrorInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val userManager = UserManager(context)
            val token = userManager.getUserToken()

            val request = chain.request().newBuilder()
                .addHeader("X-Auth-Token", token ?: "")
                .build()

            return try {
                val response = chain.proceed(request)

                if (!response.isSuccessful) {
                    Log.e("NetworkError", "HTTP error: ${response.code}")
                }

                response
            } catch (e: IOException) {
                Log.e("NetworkError", "Network request failed", e)
                throw e
        }
    }
}

    class AuthInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            return try {
                val userManager = UserManager(context)
                val token = userManager.getUserToken()

                val requestBuilder = chain.request().newBuilder()

                if (!token.isNullOrBlank()) {
                    requestBuilder.addHeader("X-Auth-Token", token)
                    Log.d("AuthInterceptor", "✅ Added X-Auth-Token header (first 20 chars): ${token.take(20)}...")
                } else {
                    Log.w("AuthInterceptor", "⚠️ No token found — request will be unauthenticated")
                }

                val request = requestBuilder.build()
                val response = chain.proceed(request)

                if (!response.isSuccessful) {
                    Log.e("AuthInterceptor", "❌ Request failed — HTTP ${response.code} ${response.message}")
                }

                response
            } catch (e: Exception) {
            Log.e("AuthInterceptor", "🚨 Exception in AuthInterceptor: ${e.message}", e)
            throw e
        }
    }
}


    // Current User Token with better error handling
    suspend fun getCurrentUserToken(): String? {
        return try {
            // Try to get cached token first
            userManager.getUserToken()?.let {
                Log.d("AuthDebug", "Using cached token")
                    return it
                }

        // Fetch new token from Firebase
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null) {
            Log.e("AuthDebug", "No user signed in! Can't fetch token.")
            return null
        }

        val tokenResult = currentUser.getIdToken(true).await()
        val token = tokenResult.token

        if (!token.isNullOrBlank()) {
            userManager.saveUserToken(token)
            Log.d("AuthDebug", "Token obtained successfully: ${token.take(20)}...")
            token
        } else {
            Log.e("AuthDebug", "Token is null or empty")
            null
        }
    } catch (e: Exception) {
        Log.e("AuthDebug", "Error fetching Firebase token: ${e.message}", e)
        null
    }
}

    // Enhanced Authentication Helper
    suspend fun getAuthIdentifier(): String? {
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

    // Simple warm-up server method
    

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
        return try {
        // Fetch the current user token (cached or fresh)
        val token = getCurrentUserToken()
        if (token.isNullOrEmpty()) {
            Log.e("ApiService", "Auth token is null or empty")
            return ApiResult.Error(
                message = "User authentication unavailable. Please log in.",
                code = 401
            )
        }

        // Build the request with the auth token
        val request = Request.Builder()
            .url("$baseUrl/user/credits")
            .get()
            .addHeader("User-Agent", "ResumeWriter-Android")
            .addHeader("X-Auth-Token", token)  // ⚡ This is required by the API
            .build()

        // Execute request
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                Log.e("ApiService", "Failed to fetch credits: HTTP ${response.code}")
                return ApiResult.Error(
                    message = "Failed to get credits: ${response.message}",
                    code = response.code
                )
            }

            // Return successful result
            ApiResult.Success(JSONObject(respBody))
        }
    } catch (e: Exception) {
        Log.e("ApiService", "Exception while fetching credits", e)
        ApiResult.Error(
            message = "Exception while fetching credits: ${e.message}",
            code = -1,
            details = e.stackTraceToString()
        )
    }
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
