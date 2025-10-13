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
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    private val userManager = UserManager(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.BASIC
        })
        .addInterceptor(RetryInterceptor())
        .addInterceptor(ConnectivityInterceptor(context))
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
    // Retry Interceptor
    // -----------------------------
    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var response: Response? = null
            var retries = 0
            val maxRetries = 3
            while (retries <= maxRetries) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful || retries >= maxRetries) return response
                } catch (e: IOException) {
                    if (retries >= maxRetries) throw e
                }
                retries++
                Thread.sleep((1000 * retries).toLong())
            }
            return response ?: throw IOException("Request failed after $maxRetries retries")
        }
    }

    // -----------------------------
    // Connectivity Interceptor
    // -----------------------------
    private class ConnectivityInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!isNetworkAvailable(context)) throw IOException("No internet connection")
            return chain.proceed(chain.request())
        }

        private fun isNetworkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities != null && (
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                        )
            } else {
                @Suppress("DEPRECATION")
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
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
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
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
                .addHeader("Authorization", auth)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
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
                .addHeader("Authorization", auth)
                .build()

            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            ApiResult.Error("File upload error: ${e.message}", getErrorCode(e))
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
                .addHeader("Authorization", auth)
                .build()
            val response = client.newCall(request).execute()
            val respBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) return ApiResult.Error(handleErrorResponse(response), response.code)
            ApiResult.Success(JSONObject(respBody))
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", getErrorCode(e))
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
    // Network Check
    // -----------------------------
    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork
            val cap = cm.getNetworkCapabilities(network)
            cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            info != null && info.isConnected
        }
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
                getCurrentUserToken()?.let { token -> reqBuilder.addHeader("Authorization", "Bearer $token") }
                val resp = client.newCall(reqBuilder.build()).execute()
                results[path] = "HTTP ${resp.code} - ${resp.message}"
            } catch (e: Exception) {
                results[path] = "ERROR: ${e.message}"
            }
        }
        return results
    }
}
