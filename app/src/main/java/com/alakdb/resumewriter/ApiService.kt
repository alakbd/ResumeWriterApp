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

    private val userManager = UserManager(context)
    private val gson = Gson()
    private val baseUrl = "resume-writer-api.onrender.com"

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

    // ---------------------------
    // Request / Result classes
    // ---------------------------
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

    // ---------------------------
    // Retry & Connectivity
    // ---------------------------
    private class RetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var response: Response? = null
            var retries = 0
            val maxRetries = 3
            val request = chain.request()

            while (retries <= maxRetries) {
                try {
                    response = chain.proceed(request)
                    if (response.isSuccessful || retries >= maxRetries) return response
                } catch (e: IOException) {
                    if (retries >= maxRetries) throw e
                }
                retries++
                Thread.sleep((1000L * retries))
            }
            return response ?: throw IOException("Request failed after $maxRetries retries")
        }
    }

    private class ConnectivityInterceptor(private val context: Context) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!isNetworkAvailable()) throw IOException("No internet connection")
            return chain.proceed(chain.request())
        }

        private fun isNetworkAvailable(): Boolean {
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

    // ---------------------------
    // Helper: get token
    // ---------------------------
    private suspend fun getToken(): String? {
        return userManager.getUserToken() ?: run {
            val currentUser = FirebaseAuth.getInstance().currentUser ?: return null
            return try {
                currentUser.getIdToken(true).await().token?.also { userManager.saveUserToken(it) }
            } catch (e: Exception) {
                Log.e("ApiService", "Error fetching token: ${e.message}")
                null
            }
        }
    }

    // ---------------------------
    // GET /user/credits
    // ---------------------------
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        val token = getToken() ?: return ApiResult.Error("User not authenticated", 401)
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(baseUrl)
            .addPathSegment("user")
            .addPathSegment("credits")
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $token")
            .build()

        return executeRequest(request)
    }

    // ---------------------------
    // POST /deduct-credit
    // ---------------------------
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        val token = getToken() ?: return ApiResult.Error("User not authenticated", 401)
        val url = HttpUrl.Builder().scheme("https").host(baseUrl).addPathSegment("deduct-credit").build()

        val requestBody = gson.toJson(DeductCreditRequest(userId)).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request)
    }

    // ---------------------------
    // POST /generate-resume
    // ---------------------------
    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        val token = getToken() ?: return ApiResult.Error("User not authenticated", 401)
        val url = HttpUrl.Builder().scheme("https").host(baseUrl).addPathSegment("generate-resume").build()

        val requestBody = gson.toJson(GenerateResumeRequest(resumeText, jobDescription, tone))
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .build()

        return executeRequest(request)
    }

    // ---------------------------
    // POST /generate-resume-from-files
    // ---------------------------
    suspend fun generateResumeFromFiles(resumeFileUri: Uri, jobDescFileUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        val token = getToken() ?: return ApiResult.Error("User not authenticated", 401)
        val url = HttpUrl.Builder().scheme("https").host(baseUrl).addPathSegment("generate-resume-from-files").build()

        val resumeFile = uriToFile(resumeFileUri)
        val jobFile = uriToFile(jobDescFileUri)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("tone", tone)
            .addFormDataPart("resume_file", resumeFile.name, resumeFile.asRequestBody(getMediaType(resumeFileUri)))
            .addFormDataPart("job_description_file", jobFile.name, jobFile.asRequestBody(getMediaType(jobDescFileUri)))
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $token")
            .build()

        return executeRequest(request)
    }

    // ---------------------------
    // Helper: execute request
    // ---------------------------
    private fun executeRequest(request: Request): ApiResult<JSONObject> {
        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful) return ApiResult.Error(body ?: "Error ${response.code}", response.code)
            if (body == null) return ApiResult.Error("Empty response", response.code)
            ApiResult.Success(JSONObject(body))
        } catch (e: Exception) {
            ApiResult.Error("Network error: ${e.message}", 1001)
        }
    }

    // ---------------------------
    // Helpers for files
    // ---------------------------
    private fun uriToFile(uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open URI")
        val file = File.createTempFile("upload_", "_tmp", context.cacheDir)
        input.use { i -> file.outputStream().use { it.write(i.readBytes()) } }
        return file
    }

    private fun getMediaType(uri: Uri): MediaType? {
        val mime = context.contentResolver.getType(uri)
        return mime?.toMediaType() ?: "application/octet-stream".toMediaType()
    }

    private fun File.asRequestBody(mediaType: MediaType?) = this.inputStream().readBytes().toRequestBody(mediaType)
}
