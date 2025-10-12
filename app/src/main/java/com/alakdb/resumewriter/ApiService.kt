package com.alakdb.resumewriter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.tasks.await
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.logging.HttpLoggingInterceptor

class ApiService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)  // Increased timeout
        .readTimeout(120, TimeUnit.SECONDS)    // Increased for file processing
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.BASIC
            }
        })
        .addInterceptor(RetryInterceptor())  // Add retry mechanism
        .addInterceptor(ConnectivityInterceptor(context)) // Add connectivity check
        .build()
    
    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"

    // Request data classes
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(
        val resume_text: String,
        val job_description: String,
        val tone: String = "Professional"
    )

    // Result sealed class
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

    // Retry Interceptor
    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var retryCount = 0
            val maxRetries = 3
            
            while (retryCount <= maxRetries) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful || retryCount >= maxRetries) {
                        return response
                    }
                } catch (e: IOException) {
                    if (retryCount >= maxRetries) {
                        throw e
                    }
                }
                
                retryCount++
                // Wait before retrying (exponential backoff)
                try {
                    Thread.sleep((1000 * retryCount).toLong())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Request interrupted", e)
                }
            }
            
            return response ?: throw IOException("Request failed after $maxRetries retries")
        }
    }

    // Connectivity Interceptor
    private class ConnectivityInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!isNetworkAvailable()) {
                throw IOException("No internet connection available")
            }
            return chain.proceed(chain.request())
        }

        private fun isNetworkAvailable(): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                capabilities != null && (
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo
                networkInfo != null && networkInfo.isConnected
            }
        }
    }

    suspend fun getCurrentUserToken(): String? {
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            // true = force refresh
            currentUser.getIdToken(true).await().token
        } catch (e: Exception) {
            Log.e("ApiService", "Error getting user token: ${e.message}")
            null
        }
    }

    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            // Try multiple endpoints to verify connection
            val endpoints = listOf("/", "/health", "/api", "/test")
            var lastError: String? = null
            
            for (endpoint in endpoints) {
                try {
                    val fullUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("resume-writer-api.onrender.com")
                        .addPathSegment(endpoint.removePrefix("/"))
                        .build()

                    val request = Request.Builder()
                        .url(fullUrl)
                        .get()
                        .build()

                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string()
                    
                    if (response.isSuccessful && responseBody != null) {
                        Log.d("ApiService", "Connection test successful for $endpoint")
                        return ApiResult.Success(JSONObject(responseBody))
                    } else {
                        lastError = "HTTP ${response.code} for $endpoint: ${response.message}"
                        Log.w("ApiService", "Connection test failed for $endpoint: $lastError")
                    }
                } catch (e: Exception) {
                    lastError = "Failed to connect to $endpoint: ${e.message}"
                    Log.e("ApiService", "Connection test failed for $endpoint: ${e.message}")
                }
            }
            
            ApiResult.Error(lastError ?: "All connection tests failed")
        } catch (e: Exception) {
            ApiResult.Error("Connection test failed: ${e.message}")
        }
    }

    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated", 401)
            }

            val requestBody = gson.toJson(DeductCreditRequest(user_id = userId))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = handleErrorResponse(response)
                return ApiResult.Error(errorMessage, response.code)
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.getBoolean("success")) {
                    ApiResult.Success(jsonResponse)
                } else {
                    ApiResult.Error(jsonResponse.getString("message"), response.code)
                }
            } else {
                ApiResult.Error("Empty response from server", response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    suspend fun generateResume(
        resumeText: String,
        jobDescription: String,
        tone: String = "Professional"
    ): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated", 401)
            }

            val requestBody = gson.toJson(
                GenerateResumeRequest(resumeText, jobDescription, tone)
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = handleErrorResponse(response)
                return ApiResult.Error(errorMessage, response.code)
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server", response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    suspend fun generateResumeFromFiles(
        resumeFileUri: Uri,
        jobDescFileUri: Uri,
        tone: String = "Professional"
    ): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated", 401)
            }

            // Convert URIs to files and create multipart request
            val resumeFile = uriToFile(resumeFileUri)
            val jobDescFile = uriToFile(jobDescFileUri)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart(
                    "resume_file", 
                    resumeFile.name ?: "resume.pdf",
                    resumeFile.asRequestBody(getMediaType(resumeFileUri))
                )
                .addFormDataPart(
                    "job_description_file", 
                    jobDescFile.name ?: "job_description.pdf",
                    jobDescFile.asRequestBody(getMediaType(jobDescFileUri))
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = handleErrorResponse(response)
                return ApiResult.Error(errorMessage, response.code)
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server", response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("File upload error: ${e.message}", getErrorCode(e))
        }
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken()
            if (token == null) {
                return ApiResult.Error("User not authenticated", 401)
            }

            val fullUrl = HttpUrl.Builder()
                .scheme("https")
                .host("resume-writer-api.onrender.com")
                .addPathSegment("user")
                .addPathSegment("credits")
                .build()

            val request = Request.Builder()
                .url(fullUrl)
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorMessage = handleErrorResponse(response)
                return ApiResult.Error(errorMessage, response.code)
            }
            
            val responseBody = response.body?.string()
            if (responseBody != null) {
                ApiResult.Success(JSONObject(responseBody))
            } else {
                ApiResult.Error("Empty response from server", response.code)
            }
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    // Helper functions for file handling
    private fun uriToFile(uri: Uri): File {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val file = File.createTempFile("upload_", "_temp", context.cacheDir)
            inputStream?.use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            throw IOException("Failed to convert URI to file: ${e.message}")
        }
    }

    private fun getMediaType(uri: Uri): MediaType? {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.toMediaType() ?: "application/octet-stream".toMediaType()
    }

    // Extension function for File to RequestBody
    private fun File.asRequestBody(mediaType: MediaType?): RequestBody {
        return this.inputStream().readBytes().toRequestBody(mediaType)
    }

    // Utility function to decode base64 file data
    fun decodeBase64File(base64Data: String): ByteArray {
        return android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
    }

    // Utility function to save file data to storage
    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    // Enhanced error handling
    private fun handleErrorResponse(response: Response): String {
        return try {
            val errorBody = response.body?.string()
            "HTTP ${response.code}: ${response.message}. Body: $errorBody"
        } catch (e: Exception) {
            "HTTP ${response.code}: ${response.message}"
        }
    }

    private fun getErrorCode(exception: Exception): Int {
        return when (exception) {
            is IOException -> 1001 // Network error code
            is java.net.SocketTimeoutException -> 1002 // Timeout error code
            is java.net.UnknownHostException -> 1003 // DNS error code
            else -> 1000 // Generic error code
        }
    }

    // Network availability check
    fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            )
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            networkInfo != null && networkInfo.isConnected
        }
    }

    // Get detailed connection diagnostics
    suspend fun getConnectionDiagnostics(): Map<String, Any> {
        val diagnostics = mutableMapOf<String, Any>()
        
        // Network availability
        diagnostics["network_available"] = isNetworkAvailable()
        
        // Test each endpoint
        val endpoints = listOf("/", "/health", "/api", "/test", "/user/credits")
        val endpointResults = mutableMapOf<String, String>()
        
        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl$endpoint")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                endpointResults[endpoint] = if (response.isSuccessful) "SUCCESS (${response.code})" 
                                           else "FAILED (${response.code}: ${response.message})"
            } catch (e: Exception) {
                endpointResults[endpoint] = "ERROR: ${e.message}"
            }
        }
        
        diagnostics["endpoint_tests"] = endpointResults
        diagnostics["base_url"] = baseUrl
        
        return diagnostics
    }
}
