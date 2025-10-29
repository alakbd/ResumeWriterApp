package com.alakdb.resumewriter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

// Extension functions for ResponseBody conversion
fun String.toResponseBody(mediaType: MediaType): ResponseBody {
    return this.toByteArray().toResponseBody(mediaType)
}

fun ByteArray.toResponseBody(mediaType: MediaType): ResponseBody {
    return ResponseBody.create(mediaType, this)
}

class ApiService(private val context: Context) {

    private val gson = Gson()
    private val baseUrl = "https://resume-writer-api.onrender.com/"

    // Simple client for health checks (no authentication needed)
    private val simpleClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Data classes for API requests
    data class GenerateResumeRequest(
        val resume_text: String,
        val job_description: String,
        val tone: String = "Professional"
    )

    // Data class for API responses
    data class GenerateResumeResponse(
        val success: Boolean,
        val resume_text: String,
        val remaining_credits: Int,
        val generation_id: String?,
        val docx_url: String,
        val pdf_url: String,
        val message: String
    )

    data class UserCreditsResponse(
        val available_credits: Int,
        val used_credits: Int,
        val total_credits: Int
    )

    // API Result sealed class
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }

    class SimpleLoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()

            // Log on background thread to avoid main thread issues
            CoroutineScope(Dispatchers.IO).launch {
                Log.d("NETWORK", "➡️ REQUEST: ${request.method} ${request.url}")
            }

            val response = chain.proceed(request)

            CoroutineScope(Dispatchers.IO).launch {
                Log.d("NETWORK", "⬅️ RESPONSE: ${response.code} ${response.message}")
            }

            return response
        }
    }

    // Main client with authentication
    private val client: OkHttpClient = createUnsafeOkHttpClient()

    private fun createUnsafeOkHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.X509TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            })

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0])
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(SimpleLoggingInterceptor())
                .addInterceptor(SafeAuthInterceptor(context))
                .build()
        } catch (e: Exception) {
            Log.e("SSL", "Failed to create unsafe client, using regular one: ${e.message}")
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(SimpleLoggingInterceptor())
                .addInterceptor(SafeAuthInterceptor(context))
                .build()
        }
    }

    // ==================== FILE HANDLING METHODS ====================

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

    // ==================== NETWORK DIAGNOSTICS ====================

    /**
     * Comprehensive network exception analyzer
     */
    private fun analyzeNetworkException(e: Exception, url: String): String {
        val analysis = StringBuilder()
        analysis.appendLine("🔍 NETWORK FAILURE ANALYSIS")
        analysis.appendLine("URL: $url")
        analysis.appendLine("Exception: ${e.javaClass.simpleName}")
        analysis.appendLine("Message: ${e.message}")

        when (e) {
            is UnknownHostException -> {
                analysis.appendLine("❌ DNS RESOLUTION FAILED")
                analysis.appendLine("   • Cannot resolve host: ${e.message}")
            }
            is ConnectException -> {
                analysis.appendLine("❌ CONNECTION REFUSED")
                analysis.appendLine("   • Server refused connection or is offline")
            }
            is SocketTimeoutException -> {
                analysis.appendLine("⏰ SOCKET TIMEOUT")
                analysis.appendLine("   • Connection/read timeout reached")
            }
            is SSLHandshakeException -> {
                analysis.appendLine("🔒 SSL HANDSHAKE FAILED")
                analysis.appendLine("   • SSL certificate issue")
            }
            is SocketException -> {
                analysis.appendLine("🔌 SOCKET ERROR")
                analysis.appendLine("   • General socket communication failure")
            }
            is IOException -> {
                analysis.appendLine("📡 IO EXCEPTION")
                analysis.appendLine("   • General network I/O failure")
            }
            else -> {
                analysis.appendLine("💥 UNEXPECTED EXCEPTION")
                analysis.appendLine("   • Non-network related error")
            }
        }

        return analysis.toString()
    }

    fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasNetwork = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            Log.d("NetworkCheck", "Internet: $hasInternet, Validated: $hasNetwork")
            hasInternet && hasNetwork
        } catch (e: Exception) {
            Log.e("NetworkCheck", "Network check failed: ${e.message}")
            false
        }
    }

    // ==================== API METHODS ====================

    suspend fun generateResumeFromFiles(
        resumeUri: Uri,
        jobDescUri: Uri,
        tone: String = "Professional"
    ): ApiResult<GenerateResumeResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "📄 Generating resume from files...")

            // Convert Uris to files
            val resumeFile = uriToFile(resumeUri)
            val jobDescFile = uriToFile(jobDescUri)

            Log.d("ApiService", "✅ Files selected: resume=${resumeFile.name}, jobDesc=${jobDescFile.name}")

            // Detect MIME type automatically
            fun getMimeType(file: File): String {
                return when {
                    file.name.endsWith(".pdf", true) -> "application/pdf"
                    file.name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> "application/octet-stream"
                }
            }

            val resumeMime = getMimeType(resumeFile)
            val jobDescMime = getMimeType(jobDescFile)

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("tone", tone)
                .addFormDataPart(
                    "resume_file",
                    resumeFile.name,
                    resumeFile.asRequestBody(resumeMime.toMediaType())
                )
                .addFormDataPart(
                    "job_description_file",
                    jobDescFile.name,
                    jobDescFile.asRequestBody(jobDescMime.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$baseUrl/generate-resume-from-files")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            Log.d("ApiService", "➡️ Sending file-based resume generation request to: $baseUrl/generate-resume-from-files")

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "📬 File-based resume generation response: ${response.code}")

                return@withContext if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(respBody)
                        val resumeResponse = GenerateResumeResponse(
                            success = jsonResponse.optBoolean("success", false),
                            resume_text = jsonResponse.optString("resume_text", ""),
                            remaining_credits = jsonResponse.optInt("remaining_credits", 0),
                            generation_id = jsonResponse.optString("generation_id", null),
                            docx_url = jsonResponse.optString("docx_url", ""),
                            pdf_url = jsonResponse.optString("pdf_url", ""),
                            message = jsonResponse.optString("message", "")
                        )
                        ApiResult.Success(resumeResponse)
                    } catch (e: Exception) {
                        Log.e("ApiService", "❌ JSON parsing error", e)
                        ApiResult.Error("Invalid server response format", response.code)
                    }
                } else {
                    val errorMsg = "HTTP ${response.code}: ${response.message} – $respBody"
                    Log.e("ApiService", errorMsg)
                    ApiResult.Error(errorMsg, response.code)
                }
            }
        } catch (e: Exception) {
            val analysis = analyzeNetworkException(e, "$baseUrl/generate-resume-from-files")
            Log.e("ApiService", analysis)
            ApiResult.Error("File resume generation failed: ${e.message}")
        }
    }

    suspend fun generateResumeFromText(
        resumeText: String,
        jobDescription: String,
        tone: String = "Professional"
    ): ApiResult<GenerateResumeResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "📝 Generating resume from text...")

            val requestBody = GenerateResumeRequest(resumeText, jobDescription, tone)
            val body = gson.toJson(requestBody).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/generate-resume")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            Log.d("ApiService", "➡️ Sending text-based resume generation request")

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "📬 Text-based resume generation response: ${response.code}")

                return@withContext if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(respBody)
                        val resumeResponse = GenerateResumeResponse(
                            success = jsonResponse.optBoolean("success", false),
                            resume_text = jsonResponse.optString("resume_text", ""),
                            remaining_credits = jsonResponse.optInt("remaining_credits", 0),
                            generation_id = jsonResponse.optString("generation_id", null),
                            docx_url = jsonResponse.optString("docx_url", ""),
                            pdf_url = jsonResponse.optString("pdf_url", ""),
                            message = jsonResponse.optString("message", "")
                        )
                        ApiResult.Success(resumeResponse)
                    } catch (e: Exception) {
                        Log.e("ApiService", "❌ JSON parsing error", e)
                        ApiResult.Error("Invalid server response format", response.code)
                    }
                } else {
                    val errorMsg = "HTTP ${response.code}: ${response.message} – $respBody"
                    Log.e("ApiService", errorMsg)
                    ApiResult.Error(errorMsg, response.code)
                }
            }
        } catch (e: Exception) {
            val analysis = analyzeNetworkException(e, "$baseUrl/generate-resume")
            Log.e("ApiService", analysis)
            ApiResult.Error("Text resume generation failed: ${e.message}")
        }
    }

    suspend fun validateFiles(
        resumeUri: Uri? = null,
        jobDescUri: Uri? = null
    ): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "🔍 Validating files...")

            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

            resumeUri?.let { uri ->
                val resumeFile = uriToFile(uri)
                val mimeType = when {
                    resumeFile.name.endsWith(".pdf", true) -> "application/pdf"
                    resumeFile.name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> "application/octet-stream"
                }
                bodyBuilder.addFormDataPart(
                    "resume_file",
                    resumeFile.name,
                    resumeFile.asRequestBody(mimeType.toMediaType())
                )
            }

            jobDescUri?.let { uri ->
                val jobDescFile = uriToFile(uri)
                val mimeType = when {
                    jobDescFile.name.endsWith(".pdf", true) -> "application/pdf"
                    jobDescFile.name.endsWith(".docx", true) -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> "application/octet-stream"
                }
                bodyBuilder.addFormDataPart(
                    "job_description_file",
                    jobDescFile.name,
                    jobDescFile.asRequestBody(mimeType.toMediaType())
                )
            }

            val body = bodyBuilder.build()

            val request = Request.Builder()
                .url("$baseUrl/validate-files")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                if (response.isSuccessful) {
                    ApiResult.Success(JSONObject(respBody))
                } else {
                    ApiResult.Error("HTTP ${response.code}: ${response.message}", response.code)
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("File validation failed: ${e.message}")
        }
    }

    suspend fun testConnection(): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "Testing connection to: $baseUrl/health")

            // First test DNS resolution
            try {
                InetAddress.getAllByName("resume-writer-api.onrender.com")
            } catch (e: Exception) {
                Log.e("ApiService", "❌ DNS Resolution failed: ${e.message}")
                return@withContext ApiResult.Error("DNS Resolution Failed: ${e.message}")
            }

            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            val response = simpleClient.newCall(request).execute()
            val body = response.body?.string()

            Log.d("ApiService", "Connection test response: ${response.code}")

            if (response.isSuccessful && body != null) {
                ApiResult.Success(JSONObject(body))
            } else {
                ApiResult.Error("HTTP ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            val analysis = analyzeNetworkException(e, "$baseUrl/health")
            Log.e("ApiService", analysis)
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
                    delay(delayBetweenAttempts)
                }
            }

            Log.e("ServerWakeUp", "❌ Server failed to wake up after $maxAttempts attempts")
            false
        } catch (e: Exception) {
            Log.e("ServerWakeUp", "❌ Server wakeup crashed: ${e.message}")
            false
        }
    }

    suspend fun getUserCredits(): ApiResult<UserCreditsResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "🔄 Getting user credits from: $baseUrl/user/credits")

            val request = Request.Builder()
                .url("$baseUrl/user/credits")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: "{}"
                Log.d("ApiService", "💰 Credits response: ${response.code}")

                if (response.isSuccessful) {
                    try {
                        val jsonResponse = JSONObject(respBody)
                        val creditsResponse = UserCreditsResponse(
                            available_credits = jsonResponse.optInt("available_credits", 0),
                            used_credits = jsonResponse.optInt("used_credits", 0),
                            total_credits = jsonResponse.optInt("total_credits", 0)
                        )
                        Log.d("ApiService", "✅ Credits success: $creditsResponse")
                        ApiResult.Success(creditsResponse)
                    } catch (e: Exception) {
                        Log.e("ApiService", "❌ JSON parsing error for credits", e)
                        ApiResult.Error("Invalid server response format", response.code)
                    }
                } else {
                    Log.e("ApiService", "❌ Server error: HTTP ${response.code}")
                    when (response.code) {
                        401 -> ApiResult.Error("Authentication failed", 401)
                        429 -> ApiResult.Error("Rate limit exceeded", 429)
                        else -> ApiResult.Error("Server error: ${response.code}", response.code)
                    }
                }
            }
        } catch (e: Exception) {
            val analysis = analyzeNetworkException(e, "$baseUrl/user/credits")
            Log.e("ApiService", analysis)
            ApiResult.Error("Network error: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun testSecureAuth(): ApiResult<JSONObject> = withContext(Dispatchers.IO) {
        try {
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
            val analysis = analyzeNetworkException(e, "$baseUrl/security-test")
            Log.e("ApiService", analysis)
            ApiResult.Error("Test failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ==================== DOWNLOAD METHODS ====================

    suspend fun downloadFile(url: String): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        try {
            Log.d("ApiService", "📥 Downloading file from: $url")

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null) {
                        ApiResult.Success(bytes)
                    } else {
                        ApiResult.Error("Empty response body")
                    }
                } else {
                    ApiResult.Error("Download failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Download failed: ${e.message}")
        }
    }

    // ==================== DEBUG & UTILITY METHODS ====================

    suspend fun debugHttpConnection(): String = withContext(Dispatchers.IO) {
        try {
            val debug = StringBuilder()
            debug.appendLine("🔌 HTTP CONNECTION DEBUG")
            debug.appendLine("=".repeat(50))

            // Test 1: Direct HTTP (bypass HTTPS)
            debug.appendLine("1. HTTP (port 80) test:")
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("http://resume-writer-api.onrender.com/health")
                    .build()

                val response = client.newCall(request).execute()
                debug.appendLine("   ✅ SUCCESS: HTTP ${response.code}")
                val body = response.body?.string()?.take(50) ?: "No body"
                debug.appendLine("   Body: $body...")
            } catch (e: Exception) {
                debug.appendLine("   ❌ FAILED: ${e.javaClass.simpleName}")
                debug.appendLine("   Error: ${e.message}")
            }

            // Test 2: HTTPS with normal client
            debug.appendLine("\n2. HTTPS with normal client:")
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url("https://resume-writer-api.onrender.com/health")
                    .build()

                val response = client.newCall(request).execute()
                debug.appendLine("   ✅ SUCCESS: HTTPS ${response.code}")
                val body = response.body?.string()?.take(50) ?: "No body"
                debug.appendLine("   Body: $body...")
            } catch (e: Exception) {
                debug.appendLine("   ❌ FAILED: ${e.javaClass.simpleName}")
                debug.appendLine("   Error: ${e.message}")
            }

            // Test 3: HTTPS with unsafe client
            debug.appendLine("\n3. HTTPS with unsafe client:")
            try {
                val request = Request.Builder()
                    .url("https://resume-writer-api.onrender.com/health")
                    .build()

                val response = client.newCall(request).execute()
                debug.appendLine("   ✅ SUCCESS: HTTPS ${response.code}")
                val body = response.body?.string()?.take(50) ?: "No body"
                debug.appendLine("   Body: $body...")
            } catch (e: Exception) {
                debug.appendLine("   ❌ FAILED: ${e.javaClass.simpleName}")
                debug.appendLine("   Error: ${e.message}")
            }

            // Test 4: Port connectivity test
            debug.appendLine("\n4. Port connectivity:")
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress("216.24.57.7", 443), 5000)
                socket.close()
                debug.appendLine("   ✅ SUCCESS: Port 443 is open")
            } catch (e: Exception) {
                debug.appendLine("   ❌ FAILED: ${e.message}")
            }

            debug.appendLine("=".repeat(50))
            debug.toString()
        } catch (e: Exception) {
            "HTTP debug failed: ${e.message}"
        }
    }

    suspend fun debugAuthenticationFlow(): String = withContext(Dispatchers.IO) {
        try {
            val debugInfo = StringBuilder()
            debugInfo.appendLine("=== AUTHENTICATION DEBUG ===")

            // 1. Firebase Auth State
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("1. FIREBASE AUTH STATE:")
            debugInfo.appendLine("   • UID: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            debugInfo.appendLine("   • Email Verified: ${firebaseUser?.isEmailVerified ?: false}")

            // 2. Basic Network Check
            debugInfo.appendLine("2. NETWORK CHECK:")
            try {
                val healthResult = testConnection()
                when (healthResult) {
                    is ApiResult.Success -> debugInfo.appendLine("   • Server Reachable: ✅ YES")
                    is ApiResult.Error -> debugInfo.appendLine("   • Server Reachable: ❌ NO - ${healthResult.message}")
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   • Server Reachable: 💥 CRASHED - ${e.message}")
            }

            // 3. Credits Endpoint Test
            debugInfo.appendLine("3. CREDITS ENDPOINT TEST:")
            try {
                val creditsResult = getUserCredits()
                when (creditsResult) {
                    is ApiResult.Success -> {
                        debugInfo.appendLine("   • Status: ✅ SUCCESS")
                        debugInfo.appendLine("   • Credits: ${creditsResult.data.available_credits}")
                    }
                    is ApiResult.Error -> {
                        debugInfo.appendLine("   • Status: ❌ FAILED")
                        debugInfo.appendLine("   • Error: ${creditsResult.message}")
                        debugInfo.appendLine("   • Code: ${creditsResult.code}")
                    }
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   • Status: 💥 CRASHED")
                debugInfo.appendLine("   • Error: ${e.message}")
            }

            debugInfo.appendLine("=== END DEBUG ===")
            debugInfo.toString()
        } catch (e: Exception) {
            "Debug failed: ${e.message}"
        }
    }

    suspend fun runNetworkDiagnostics(): String = withContext(Dispatchers.IO) {
        val diagnostic = StringBuilder()
        diagnostic.appendLine("🩺 NETWORK DIAGNOSTICS")
        diagnostic.appendLine("=".repeat(50))

        // 1. Basic connectivity
        diagnostic.appendLine("1. BASIC CONNECTIVITY:")
        val hasInternet = isNetworkAvailable()
        diagnostic.appendLine("   • Internet Access: ${if (hasInternet) "✅" else "❌"}")

        // 2. DNS Resolution test
        diagnostic.appendLine("2. DNS RESOLUTION:")
        try {
            val addresses = InetAddress.getAllByName("resume-writer-api.onrender.com")
            diagnostic.appendLine("   • Host resolved: ✅ (${addresses.size} IPs)")
            addresses.forEach { addr ->
                diagnostic.appendLine("     - ${addr.hostAddress}")
            }
        } catch (e: Exception) {
            diagnostic.appendLine("   • Host resolution: ❌ FAILED")
            diagnostic.appendLine("     Error: ${e.message}")
        }

        // 3. HTTP Health check
        diagnostic.appendLine("3. HTTP HEALTH CHECK:")
        val healthResult = testConnection()
        when (healthResult) {
            is ApiResult.Success -> {
                diagnostic.appendLine("   • API Health: ✅ SUCCESS")
                diagnostic.appendLine("     Response: ${healthResult.data}")
            }
            is ApiResult.Error -> {
                diagnostic.appendLine("   • API Health: ❌ FAILED")
                diagnostic.appendLine("     Error: ${healthResult.message}")
            }
        }

        // 4. Authentication test
        diagnostic.appendLine("4. AUTHENTICATION TEST:")
        val creditResult = getUserCredits()
        when (creditResult) {
            is ApiResult.Success -> {
                diagnostic.appendLine("   • Auth Headers: ✅ WORKING")
                diagnostic.appendLine("     Credits: ${creditResult.data.available_credits}")
            }
            is ApiResult.Error -> {
                diagnostic.appendLine("   • Auth Headers: ❌ FAILED")
                diagnostic.appendLine("     Error: ${creditResult.message} (Code: ${creditResult.code})")
            }
        }

        diagnostic.appendLine("=".repeat(50))
        diagnostic.toString()
    }

    fun decodeBase64File(base64Data: String): ByteArray =
        android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)

    fun saveFileToStorage(data: ByteArray, filename: String): File {
        val file = File(context.getExternalFilesDir(null), filename)
        file.outputStream().use { it.write(data) }
        return file
    }

    fun forceSyncUserManager() {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        if (firebaseUser != null) {
            Log.d("ApiService", "🔄 Force synced with Firebase: ${firebaseUser.uid}")
        } else {
            Log.w("ApiService", "⚠️ Cannot sync: No Firebase user")
        }
    }

    suspend fun testDnsResolution(): String = withContext(Dispatchers.IO) {
        try {
            Log.d("DNS", "🔍 Testing DNS resolution...")

            // Method 1: Basic DNS resolution
            val method1Result = try {
                val addresses = InetAddress.getAllByName("resume-writer-api.onrender.com")
                val ipList = addresses.joinToString(", ") { addr: InetAddress -> addr.hostAddress.orEmpty() }
                Log.d("DNS", "✅ Method 1 SUCCESS: $ipList")
                "✅ DNS Resolution SUCCESS\nIP Addresses: $ipList"
            } catch (e: Exception) {
                Log.e("DNS", "❌ Method 1 failed: ${e.message}")
                null
            }

            if (method1Result != null) return@withContext method1Result

            // Method 2: With timeout (using coroutine withTimeout instead of Executors)
            val method2Result = try {
                val addresses = withTimeout(10_000L) {
                    InetAddress.getAllByName("resume-writer-api.onrender.com")
                }
                val ipList = addresses.joinToString(", ") { addr: InetAddress -> addr.hostAddress.orEmpty() }
                Log.d("DNS", "✅ Method 2 SUCCESS: $ipList")
                "✅ DNS Resolution SUCCESS (with timeout)\nIP Addresses: $ipList"
            } catch (e: Exception) {
                Log.e("DNS", "❌ Method 2 failed: ${e.message}")
                null
            }

            if (method2Result != null) return@withContext method2Result

            // If all methods failed
            "❌ All DNS methods failed\nError: Unable to resolve host\n\nTry:\n1. Switch WiFi/Mobile data\n2. Restart device\n3. Check VPN/Proxy settings"

        } catch (e: Exception) {
            Log.e("DNS", "💥 DNS test crashed: ${e.message}")
            "❌ DNS test crashed: ${e.message}"
        }
    }
}

// ==================== INTERCEPTOR ====================

class SafeAuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val userManager = UserManager(context)
        val userId = userManager.getCurrentUserId()
        
        val requestBuilder = chain.request().newBuilder()
            .addHeader("User-Agent", "ResumeWriter-Android/1.0")
            .addHeader("Accept", "application/json")
        
        // Add user ID header if available
        if (!userId.isNullOrBlank()) {
            requestBuilder.addHeader("X-User-ID", userId)
            Log.d("AUTH_HEADER", "✅ Adding X-User-ID header: ${userId.take(8)}...")
        } else {
            Log.w("AUTH_HEADER", "⚠️ No X-User-ID available for headers")
        }
        
        val request = requestBuilder.build()
        
        // Log the request
        CoroutineScope(Dispatchers.IO).launch {
            Log.d("NETWORK", "➡️ REQUEST: ${request.method} ${request.url}")
            Log.d("NETWORK", "➡️ HEADERS: ${request.headers}")
        }

        return chain.proceed(request)
    }
}
