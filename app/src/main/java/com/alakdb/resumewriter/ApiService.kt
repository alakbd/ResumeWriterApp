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
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody


class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { message ->
                Log.d("NetworkLog", message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
        )
        .addInterceptor(RetryInterceptor())
        .build()
    private fun logNetworkError(tag: String, e: Exception) {
        val logMessage = "❌ ${e::class.simpleName}: ${e.message}"
        Log.e(tag, logMessage, e)
         appendLogToFile(logMessage)
        }

    private fun appendLogToFile(message: String) {
    try {
        val logFile = File(context.getExternalFilesDir(null), "network_log.txt")
        logFile.appendText("${System.currentTimeMillis()}: $message\n")
    } catch (e: Exception) {
        Log.e("ApiService", "Failed to write log file: ${e.message}")
        }
    }

    // -----------------------------
    // Data Classes
    // -----------------------------
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    // -----------------------------
    // API Result Wrapper
    // -----------------------------
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

    // -----------------------------
    // Connectivity Interceptor
    // -----------------------------
    private class ConnectivityInterceptor(private val context: Context) : Interceptor {
        
    }
        // -----------------------------
        // Network Check
        // -----------------------------
    
        private fun isNetworkAvailable(context: Context): Boolean {

       
        }    
        
}
        // -----------------------------
        // RetryInterceptor
        // -----------------------------
    private class RetryInterceptor: Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response: Response? = null
        var retries = 0
        val maxRetries = 3

        while (retries < maxRetries) {
            try {
                response = chain.proceed(request)
                if (response.isSuccessful) return response
            } catch (e: IOException) {
                Log.w("RetryInterceptor", "Request failed (attempt ${retries + 1}): ${e.message}")
                if (retries >= maxRetries - 1) throw e
            }

            retries++
            try {
                // Non-blocking delay using system clock instead of Thread.sleep
                val backoff = 500L * retries
                Log.d("RetryInterceptor", "Retrying after ${backoff}ms (attempt $retries/$maxRetries)")
                Thread.sleep(backoff) // Safe here since OkHttp uses a background thread
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Retry interrupted", e)
            }
        }

        return response ?: throw IOException("Request failed after $maxRetries retries")
    }
}

    
    suspend fun getCurrentUserToken(): String? {
    // Try saved token
    userManager.getUserToken()?.let { return it }

    // Try Firebase token
    val currentUser = FirebaseAuth.getInstance().currentUser ?: return null
    return try {
        val token = currentUser.getIdToken(true).await().token
        token?.also { userManager.saveUserToken(it) }
    } catch (e: Exception) {
        Log.e("ApiService", "Error fetching Firebase token: ${e.message}")
        null
    }
}

    suspend fun testConnection(): ApiResult<JSONObject> {
    return try {
        val endpoints = listOf("/", "/health", "/user/credits")
        var lastError: String? = null
        for (endpoint in endpoints) {
            try {
                val fullUrl = "$baseUrl$endpoint"
                val request = Request.Builder()
                    .url(fullUrl)
                    .get()
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) {
                    return ApiResult.Success(JSONObject(body))
                } else {
                    lastError = "HTTP ${response.code} for $endpoint"
                }
            } catch (e: Exception) {
                logNetworkError("ApiService", e)
                return ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
            }
        }
        ApiResult.Error(lastError ?: "All endpoints failed")
    } catch (e: Exception) {
        ApiResult.Error("Connection test failed: ${e.message}")
    }
}
    
    // -----------------------------
    // Authentication
    // -----------------------------
    private suspend fun getAuthIdentifier(): String? {
        // Try stored token first
        userManager.getUserToken()?.let { return "Bearer $it" }

        // Firebase ID token
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser != null) {
            return try {
                val idToken = currentUser.getIdToken(true).await().token
                if (!idToken.isNullOrEmpty()) {
                    userManager.saveUserToken(idToken)
                    "Bearer $idToken"
                } else null
            } catch (e: Exception) {
                null
            }
        }

        // Fallback: Firebase UID
        return currentUser?.uid
    }

    // -----------------------------
    // Deduct Credit
    // -----------------------------
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val body = gson.toJson(DeductCreditRequest(userId)).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            logNetworkError("ApiService", e)
            return ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    // -----------------------------
    // Generate Resume
    // -----------------------------
    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val body = gson.toJson(GenerateResumeRequest(resumeText, jobDescription, tone))
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            logNetworkError("ApiService", e)
            return ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    // -----------------------------
    // Generate Resume from Files
    // -----------------------------
    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val resumeFile = uriToFile(resumeUri)
            val jobDescFile = uriToFile(jobDescUri)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart("resume_file", resumeFile.name, resumeFile.asRequestBody("application/pdf".toMediaType()))
                .addFormDataPart("job_description_file", jobDescFile.name, jobDescFile.asRequestBody("application/pdf".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            logNetworkError("ApiService", e)
            return ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    // -----------------------------
    // Get User Credits
    // -----------------------------
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .addHeader("X-Auth-Token", auth)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            logNetworkError("ApiService", e)
            return ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
        }
    }

    // -----------------------------
    // URI/File Utilities
    // -----------------------------
    private fun uriToFile(uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open URI: $uri")
        val file = File.createTempFile("upload_", "_tmp", context.cacheDir)
        input.use { inputStream -> file.outputStream().use { it.write(inputStream.readBytes()) } }
        return file
    }

    private fun File.asRequestBody(mediaType: MediaType) = this.inputStream().readBytes().toRequestBody(mediaType)

    fun decodeBase64File(base64Data: String): ByteArray {
        return android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
    }

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    // -----------------------------
    // Error Handling
    // -----------------------------
    private fun handleErrorResponse(response: Response): String {
        return try {
            val body = response.body?.string()
            "HTTP ${response.code}: ${response.message}. Body: $body"
        } catch (e: Exception) {
            "HTTP ${response.code}: ${response.message}"
        }
    }

    private fun getErrorCode(e: Exception) = when (e) {
        is java.net.SocketTimeoutException -> 1002
        is java.net.UnknownHostException -> 1003
        is IOException -> 1001
        else -> 1000
    }




    // -----------------------------
    // Optional: Server Diagnostics
    // -----------------------------
    suspend fun runServerDiagnostics(): Map<String, String> {
        val endpoints = listOf(
            "/" to "GET",
            "/health" to "GET",
            "/credit" to "POST",
            "/generate-resume" to "POST"
        )
        val results = mutableMapOf<String, String>()
        for ((path, method) in endpoints) {
            try {
                val url = "$baseUrl$path"
                val reqBuilder = Request.Builder().url(url)
                when (method) {
                    "GET" -> reqBuilder.get()
                    "POST" -> reqBuilder.post("{}".toRequestBody("application/json".toMediaType()))
                }
                getCurrentUserToken()?.let { token -> reqBuilder.addHeader("X-Auth-Token", "Bearer $token") }
                val resp = client.newCall(reqBuilder.build()).execute()
                results[path] = "HTTP ${resp.code} - ${resp.message}"
            } catch (e: Exception) {
                results[path] = "ERROR: ${e.message}"
            }
        }
        return results
    }
}
