package com.alakdb.resumewriter

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MultipartBody
import okhttp3.RequestBody


// Add this extension function for converting String to ResponseBody
fun String.toResponseBody(mediaType: MediaType): ResponseBody {
    return this.toByteArray().toResponseBody(mediaType)
}

fun ByteArray.toResponseBody(mediaType: MediaType): ResponseBody {
    return ResponseBody.create(mediaType, this)
}


class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(SafeAuthInterceptor())
        .build()

    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(val resume_text: String, val job_description: String, val tone: String = "Professional")

    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

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

    suspend fun generateResumeFromFiles(resumeUri: Uri, jobDescUri: Uri, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            Log.d("ApiService", "Generating resume from files")
            
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
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "Generate resume from files response: ${response.code}")
                
                if (!response.isSuccessful) {
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    return ApiResult.Error(errorMsg, response.code)
                }
                
                ApiResult.Success(JSONObject(respBody))
            }
        } catch (e: Exception) {
            Log.e("ApiService", "❌ File resume generation crashed: ${e.message}")
            ApiResult.Error("File resume generation failed: ${e.message}")
        }
    }
    
    suspend fun testConnection(): ApiResult<JSONObject> {
        return try {
            val simpleClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = simpleClient.newCall(request).execute()
            val body = response.body?.string()
            
            if (response.isSuccessful && body != null) {
                ApiResult.Success(JSONObject(body))
            } else {
                ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Connection failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun waitForServerWakeUp(maxAttempts: Int = 12, delayBetweenAttempts: Long = 10000L): Boolean {
        return try {
            Log.d("ServerWakeUp", "🔄 Waiting for server to wake up...")
            
            repeat(maxAttempts) { attempt ->
                try {
                    Log.d("ServerWakeUp", "Attempt ${attempt + 1}/$maxAttempts")
                    val result = testConnection()
                
                    when (result) {
                        is ApiResult.Success -> {
                            Log.d("ServerWakeUp", "✅ Server is awake and responding!")
                            return true
                        }
                        is ApiResult.Error -> {
                            Log.w("ServerWakeUp", "⏳ Server not ready: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ServerWakeUp", "🚨 Connection attempt ${attempt + 1} failed: ${e.message}")
                }
            
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(delayBetweenAttempts)
                }
            }
            
            Log.e("ServerWakeUp", "❌ Server failed to wake up after $maxAttempts attempts")
            false
        } catch (e: Exception) {
            Log.e("ServerWakeUp", "❌ Server wakeup crashed: ${e.message}")
            false
        }
    }
    
    suspend fun deductCredit(userId: String): ApiResult<JSONObject> {
        return try {
            val requestBody = DeductCreditRequest(userId)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/deduct-credit")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun generateResume(resumeText: String, jobDescription: String, tone: String = "Professional"): ApiResult<JSONObject> {
        return try {
            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .post(body)
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    fun decodeBase64File(base64Data: String): ByteArray = 
        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    suspend fun getUserCredits(): ApiResult<JSONObject> {
    return try {
        Log.d("ApiService", "🔄 Getting user credits from: $baseUrl/user/credits")
        
        // Check Firebase auth first
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser == null) {
            Log.e("ApiService", "❌ No Firebase user - cannot get credits")
            return ApiResult.Error("User not authenticated", 401)
        }

        val request = Request.Builder()
            .url("$baseUrl/user/credits")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string() ?: "{}"
            Log.d("ApiService", "💰 Credits response: ${response.code} - Body: $respBody")
            
            if (response.isSuccessful) {
                try {
                    val jsonResponse = JSONObject(respBody)
                    Log.d("ApiService", "✅ Credits success: ${jsonResponse.toString()}")
                    ApiResult.Success(jsonResponse)
                } catch (e: Exception) {
                    Log.e("ApiService", "❌ JSON parsing error for credits", e)
                    ApiResult.Error("Invalid server response format", response.code)
                }
            } else {
                Log.e("ApiService", "❌ Server error: HTTP ${response.code}")
                when (response.code) {
                    401 -> ApiResult.Error("Authentication failed - please log in again", 401)
                    429 -> ApiResult.Error("Rate limit exceeded - please wait", 429)
                    else -> ApiResult.Error("Server error: ${response.code}", response.code)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("ApiService", "💥 Network exception while fetching credits: ${e.message}", e)
        ApiResult.Error("Network error: ${e.message ?: "Unknown error"}")
    }
}
    
    suspend fun testSecureAuth(): ApiResult<JSONObject> {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/security-test")
                .post("".toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Test failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun debugAuthenticationFlow(): String {
        return try {
            val debugInfo = StringBuilder()
            debugInfo.appendLine("=== AUTHENTICATION DEBUG ===")
            
            // 1. Firebase Auth State
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("1. FIREBASE AUTH STATE:")
            debugInfo.appendLine("   • UID: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            debugInfo.appendLine("   • Email Verified: ${firebaseUser?.isEmailVerified ?: false}")
            
            // 2. Test credits endpoint (the one that's failing)
            debugInfo.appendLine("2. CREDITS ENDPOINT TEST:")
            val creditsRequest = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            try {
                val response = client.newCall(creditsRequest).execute()
                val respBody = response.body?.string() ?: "{}"
                debugInfo.appendLine("   • HTTP Status: ${response.code}")
                debugInfo.appendLine("   • Response: $respBody")
                
                if (response.code == 401 || respBody.contains("Missing X-User-ID")) {
                    debugInfo.appendLine("   ❌ AUTH FAILED: X-User-ID header missing or invalid")
                } else if (response.isSuccessful) {
                    debugInfo.appendLine("   ✅ AUTH SUCCESS")
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   ❌ Request failed: ${e.message}")
            }
            
            debugInfo.appendLine("=== END DEBUG ===")
            debugInfo.toString()
        } catch (e: Exception) {
            "Debug failed: ${e.message}"
        }
    }

    fun forceSyncUserManager() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            val uid = firebaseUser.uid
            val email = firebaseUser.email ?: ""
            
            // This would sync with UserManager - you'll need to implement based on your UserManager
            Log.d("ApiService", "🔄 Force synced with Firebase: $uid")
        } else {
            Log.w("ApiService", "⚠️ Cannot sync: No Firebase user")
        }
    }

    fun initializeUserSession(userId: String?) {
        // No-op for compatibility
        Log.d("ApiService", "initializeUserSession called with: $userId")
    }
}

class SafeAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "ResumeWriter-Android")
                .build()
            chain.proceed(request)
        } catch (e: Exception) {
            // If everything fails, just proceed with original request
            chain.proceed(chain.request())
        }
    }
}
