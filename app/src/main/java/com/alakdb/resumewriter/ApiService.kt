package com.alakdb.resumewriter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.delay
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

    // -----------------------------
    // OkHttp Client with Retry
    // -----------------------------
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor { msg -> Log.d("NetworkLog", msg) }
            .apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()

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
    // Retry Network Helper
    // -----------------------------
    suspend fun <T> retryNetwork(times: Int = 3, block: suspend () -> T): T {
        var currentAttempt = 0
        var lastError: Exception? = null
        while (currentAttempt < times) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                currentAttempt++
                if (currentAttempt < times) {
                    delay(500L * currentAttempt) // exponential backoff
                }
            }
        }
        throw lastError ?: IOException("Unknown network error")
    }

    // -----------------------------
    // Current User Token
    // -----------------------------
    suspend fun getCurrentUserToken(): String? {
        userManager.getUserToken()?.let { return it }
        val currentUser = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            val token = currentUser.getIdToken(true).await().token
            token?.also { userManager.saveUserToken(it) }
        } catch (e: Exception) {
            Log.e("ApiService", "Error fetching Firebase token: ${e.message}")
            null
        }
    }

    // -----------------------------
    // Authentication Helper
    // -----------------------------
    private suspend fun getAuthIdentifier(): String? {
        userManager.getUserToken()?.let { return "Bearer $it" }
        val currentUser = FirebaseAuth.getInstance().currentUser
        return if (currentUser != null) {
            try {
                val idToken = currentUser.getIdToken(true).await().token
                if (!idToken.isNullOrEmpty()) {
                    userManager.saveUserToken(idToken)
                    "Bearer $idToken"
                } else null
            } catch (e: Exception) {
                null
            }
        } else null
    }

    // -----------------------------
    // Test Connection
    // -----------------------------
    suspend fun testConnection(): ApiResult<JSONObject> = retryNetwork {
        val endpoints = listOf("/", "/health", "/user/credits")
        var lastError: String? = null
        for (endpoint in endpoints) {
            try {
                val url = "$baseUrl$endpoint"
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && body != null) return@retryNetwork ApiResult.Success(JSONObject(body))
                else lastError = "HTTP ${response.code} for $endpoint"
            } catch (e: Exception) {
                logNetworkError("ApiService", e)
                return@retryNetwork ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
            }
        }
        ApiResult.Error(lastError ?: "All endpoints failed")
    }

    // -----------------------------
    // Deduct Credit
    // -----------------------------
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> = retryNetwork {
        val auth = getAuthIdentifier() ?: return@retryNetwork ApiResult.Error("User authentication unavailable", 401)
        val body = gson.toJson(DeductCreditRequest(userId)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/deduct-credit")
            .post(body)
            .addHeader("X-Auth-Token", auth)
            .addHeader("Content-Type", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return@retryNetwork ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        }
    }

    // -----------------------------
    // Generate Resume
    // -----------------------------
    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> = retryNetwork {
        val auth = getAuthIdentifier() ?: return@retryNetwork ApiResult.Error("User authentication unavailable", 401)
        val body = gson.toJson(GenerateResumeRequest(resumeText, jobDescription, tone)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/generate-resume")
            .post(body)
            .addHeader("X-Auth-Token", auth)
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return@retryNetwork ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        }
    }

    // -----------------------------
    // Generate Resume from Files
    // -----------------------------
    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> = retryNetwork {
        val auth = getAuthIdentifier() ?: return@retryNetwork ApiResult.Error("User authentication unavailable", 401)
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

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return@retryNetwork ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        }
    }

    // -----------------------------
    // Get User Credits
    // -----------------------------
    suspend fun getUserCredits(): ApiResult<JSONObject> = retryNetwork {
        val auth = getAuthIdentifier() ?: return@retryNetwork ApiResult.Error("User authentication unavailable", 401)
        val request = Request.Builder()
            .url("$baseUrl/user/credits")
            .get()
            .addHeader("X-Auth-Token", auth)
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return@retryNetwork ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        }
    }

    // -----------------------------
    // Utilities
    // -----------------------------
    private fun uriToFile(uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Failed to open URI: $uri")
        val file = File.createTempFile("upload_", "_tmp", context.cacheDir)
        input.use { inputStream -> file.outputStream().use { it.write(inputStream.readBytes()) } }
        return file
    }

    private fun File.asRequestBody(mediaType: MediaType) = this.inputStream().readBytes().toRequestBody(mediaType)

    private fun logNetworkError(tag: String, e: Exception) {
        val logMessage = "❌ ${e::class.simpleName}: ${e.message}"
        Log.e(tag, logMessage, e)
        try {
            val logFile = File(context.getExternalFilesDir(null), "network_log.txt")
            logFile.appendText("${System.currentTimeMillis()}: $logMessage\n")
        } catch (_: Exception) {}
    }



    fun decodeBase64File(base64Data: String): ByteArray = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

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
