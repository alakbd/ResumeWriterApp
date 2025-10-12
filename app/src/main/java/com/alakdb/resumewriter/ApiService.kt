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

    private val userManager = UserManager(context)
    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"

    // -----------------------------
    // API Result Sealed Class
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
            var retryCount = 0
            val maxRetries = 3

            while (retryCount <= maxRetries) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful || retryCount >= maxRetries) return response
                } catch (e: IOException) {
                    if (retryCount >= maxRetries) throw e
                }
                retryCount++
                Thread.sleep((1000 * retryCount).toLong())
            }
            return response ?: throw IOException("Request failed after $maxRetries retries")
        }
    }

    // -----------------------------
    // Connectivity Interceptor
    // -----------------------------
    private class ConnectivityInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!isNetworkAvailable()) throw IOException("No internet connection available")
            return chain.proceed(chain.request())
        }

        private fun isNetworkAvailable(): Boolean {
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
    }

    // -----------------------------
    // User Token Management
    // -----------------------------
    suspend fun getCurrentUserToken(): String? {
        return userManager.getUserToken() ?: run {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return null
            return try {
                currentUser.getIdToken(true).await().token?.also { userManager.saveUserToken(it) }
            } catch (e: Exception) {
                Log.e("ApiService", "Error getting user token: ${e.message}")
                null
            }
        }
    }

    // -----------------------------
    // Test Connection
    // -----------------------------
    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            val endpoints = listOf("/", "/health", "/api", "/user/credits")
            var lastError: String? = null
            for (endpoint in endpoints) {
                try {
                    val fullUrl = HttpUrl.Builder()
                        .scheme("https")
                        .host("resume-writer-api.onrender.com")
                        .addPathSegment(endpoint.removePrefix("/"))
                        .build()

                    val request = Request.Builder().url(fullUrl).get().build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string()

                    if (response.isSuccessful && body != null) {
                        return ApiResult.Success(JSONObject(body))
                    } else {
                        lastError = "HTTP ${response.code} for $endpoint"
                    }
                } catch (e: Exception) {
                    lastError = "Failed to connect to $endpoint: ${e.message}"
                }
            }
            ApiResult.Error(lastError ?: "All connection tests failed")
        } catch (e: Exception) {
            ApiResult.Error("Connection test failed: ${e.message}")
        }
    }

    // -----------------------------
    // Deduct Credit API
    // -----------------------------
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken() ?: return ApiResult.Error("User not authenticated", 401)

            val requestBody = gson.toJson(mapOf("user_id" to userId))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) return ApiResult.Error("HTTP ${response.code}: ${response.message}", response.code)
            if (responseBody != null) return ApiResult.Success(JSONObject(responseBody))
            ApiResult.Error("Empty response from server", response.code)
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", 1001)
        }
    }

    // -----------------------------
    // Generate Resume API
    // -----------------------------
    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken() ?: return ApiResult.Error("User not authenticated", 401)
            val requestBody = gson.toJson(mapOf(
                "resume_text" to resumeText,
                "job_description" to jobDescription,
                "tone" to tone
            )).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) return ApiResult.Error("HTTP ${response.code}", response.code)
            if (body != null) return ApiResult.Success(JSONObject(body))
            ApiResult.Error("Empty response from server", response.code)
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", 1001)
        }
    }

    // -----------------------------
    // Generate Resume From Files API
    // -----------------------------
    suspend fun generateResumeFromFiles(resumeFileUri: Uri, jobDescFileUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken() ?: return ApiResult.Error("User not authenticated", 401)

            val resumeFile = uriToFile(resumeFileUri)
            val jobDescFile = uriToFile(jobDescFileUri)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart("resume_file", resumeFile.name, resumeFile.asRequestBody(getMediaType(resumeFileUri)))
                .addFormDataPart("job_description_file", jobDescFile.name, jobDescFile.asRequestBody(getMediaType(jobDescFileUri)))
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) return ApiResult.Error("HTTP ${response.code}", response.code)
            if (body != null) return ApiResult.Success(JSONObject(body))
            ApiResult.Error("Empty response from server", response.code)
        } catch (e: Exception) {
            ApiResult.Error("File upload error: ${e.message}", 1001)
        }
    }

    // -----------------------------
    // Get User Credits
    // -----------------------------
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
            val token = getCurrentUserToken() ?: return ApiResult.Error("User not authenticated", 401)

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
            val responseBody = response.body?.string()

            if (!response.isSuccessful) return ApiResult.Error("HTTP ${response.code}: ${response.message}", response.code)
            if (responseBody != null) return ApiResult.Success(JSONObject(responseBody))
            ApiResult.Error("Empty response from server", response.code)
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", 1001)
        }
    }

    // -----------------------------
    // Helper Functions for File Handling
    // -----------------------------
    private fun uriToFile(uri: Uri): File {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val file = File.createTempFile("upload_", "_temp", context.cacheDir)
            inputStream?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            file
        } catch (e: Exception) {
            throw IOException("Failed to convert URI to file: ${e.message}")
        }
    }

    private fun getMediaType(uri: Uri): MediaType? {
        val mimeType = context.contentResolver.getType(uri)
        return mimeType?.toMediaType() ?: "application/octet-stream".toMediaType()
    }

    private fun File.asRequestBody(mediaType: MediaType?): RequestBody {
        return this.inputStream().readBytes().toRequestBody(mediaType)
    }

    fun decodeBase64File(base64Data: String): ByteArray {
        return android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
    }

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
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
}
