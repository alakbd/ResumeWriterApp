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
    // SIMPLIFIED OkHttp Client - REMOVED RETRY INTERCEPTOR
    // -----------------------------
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)  // Reduced from 60
        .readTimeout(60, TimeUnit.SECONDS)     // Reduced from 120  
        .writeTimeout(30, TimeUnit.SECONDS)    // Reduced from 60
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
        return try {
            userManager.getUserToken()?.let { return "Bearer $it" }
            val currentUser = FirebaseAuth.getInstance().currentUser
            if (currentUser != null) {
                val idToken = currentUser.getIdToken(true).await().token
                if (!idToken.isNullOrEmpty()) {
                    userManager.saveUserToken(idToken)
                    "Bearer $idToken"
                } else null
            } else null
        } catch (e: Exception) {
            Log.e("ApiService", "Auth error: ${e.message}")
            null
        }
    }

    // -----------------------------
    // SIMPLIFIED Test Connection - NO RETRY LOGIC
    // -----------------------------
    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            val endpoints = listOf("/health", "/", "/user/credits")
            var lastError: String? = null
            
            for (endpoint in endpoints) {
                try {
                    val url = "$baseUrl$endpoint"
                    val request = Request.Builder().url(url).get().build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string()
                    
                    if (response.isSuccessful && body != null) {
                        return ApiResult.Success(JSONObject(body))
                    } else {
                        lastError = "HTTP ${response.code} for $endpoint"
                    }
                } catch (e: Exception) {
                    lastError = "Network error: ${e.message}"
                    // Continue to next endpoint instead of failing immediately
                }
            }
            ApiResult.Error(lastError ?: "All endpoints failed")
        } catch (e: Exception) {
            ApiResult.Error("Connection test failed: ${e.message}", getErrorCode(e))
        }
    }

    // -----------------------------
    // SIMPLIFIED API METHODS - NO RETRY WRAPPER
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
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            ApiResult.Error("Deduct credit failed: ${e.message}", getErrorCode(e))
        }
    }

    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val body = gson.toJson(GenerateResumeRequest(resumeText, jobDescription, tone)).toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .addHeader("X-Auth-Token", auth)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            ApiResult.Error("Resume generation failed: ${e.message}", getErrorCode(e))
        }
    }

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

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            ApiResult.Error("File resume generation failed: ${e.message}", getErrorCode(e))
        }
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            val auth = getAuthIdentifier() ?: return ApiResult.Error("User authentication unavailable", 401)
            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .addHeader("X-Auth-Token", auth)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            ApiResult.Error("Get credits failed: ${e.message}", getErrorCode(e))
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

    private fun File.asRequestBody(mediaType: MediaType): RequestBody {
        return this.inputStream().readBytes().toRequestBody(mediaType)
    }

    private fun logNetworkError(tag: String, e: Exception) {
        val logMessage = "❌ ${e::class.simpleName}: ${e.message}"
        Log.e(tag, logMessage, e)
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

    private fun getErrorCode(e: Exception): Int = when (e) {
        is java.net.SocketTimeoutException -> 1002
        is java.net.UnknownHostException -> 1003
        is IOException -> 1001
        else -> 1000
    }
}
