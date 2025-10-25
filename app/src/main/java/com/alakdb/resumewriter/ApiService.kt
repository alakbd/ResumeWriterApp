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
import javax.net.ssl.SSLHandshakeException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.SocketException
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.tasks.await
import java.net.InetAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import com.alakdb.resumewriter.testDnsResolution

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
    
    // Main client with authentication interceptor
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)  // Reduced from 30
        .readTimeout(15, TimeUnit.SECONDS)     // Reduced from 30  
        .writeTimeout(30, TimeUnit.SECONDS)    // Reduced from 60
        .retryOnConnectionFailure(true)
        .addInterceptor(SimpleLoggingInterceptor())
        .build()

    // Simple client for health checks (no authentication needed)
    private val simpleClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Data classes for API requests
    data class DeductCreditRequest(val user_id: String)
    data class GenerateResumeRequest(
        val resume_text: String, 
        val job_description: String, 
        val tone: String = "Professional"
    )

    // API Result sealed class
    sealed class ApiResult<out T> {
        data class Success<out T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val code: Int = 0) : ApiResult<Nothing>()
    }
    
    class SimpleLoggingInterceptor: Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        Log.d("NETWORK", "➡️ REQUEST: ${request.method} ${request.url}")
        
        try {
            val response = chain.proceed(request)
            Log.d("NETWORK", "⬅️ RESPONSE: ${response.code} ${response.message}")
            return response
        } catch (e: Exception) {
            Log.e("NETWORK", "💥 NETWORK ERROR: ${e.javaClass.simpleName} - ${e.message}")
            throw e
        }
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
    ): ApiResult<JSONObject> {
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
            val analysis = analyzeNetworkException(e, "$baseUrl/generate-resume-from-files")
            Log.e("ApiService", analysis)
            ApiResult.Error("File resume generation failed: ${e.message}")
        }
    }

    suspend fun testConnection(): ApiResult<JSONObject> {
    return try {
        Log.d("ApiService", "Testing connection to: $baseUrl/health")
        
        // First test DNS resolution
        try {
            java.net.InetAddress.getAllByName("resume-writer-api.onrender.com")
        } catch (e: Exception) {
            Log.e("ApiService", "❌ DNS Resolution failed: ${e.message}")
            return ApiResult.Error("DNS Resolution Failed: ${e.message}")
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
            val analysis = analyzeNetworkException(e, "$baseUrl/deduct-credit")
            Log.e("ApiService", analysis)
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun generateResume(
        resumeText: String, 
        jobDescription: String, 
        tone: String = "Professional"
    ): ApiResult<JSONObject> {
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
            val analysis = analyzeNetworkException(e, "$baseUrl/generate-resume")
            Log.e("ApiService", analysis)
            ApiResult.Error("Request failed: ${e.message ?: "Unknown error"}")
        }
    }

    suspend fun testDnsResolution(): String {
    return try {
        Log.d("DNS", "🔍 Testing DNS resolution...")
        
        // Method 1: Basic DNS resolution
        try {
            val addresses = java.net.InetAddress.getAllByName("resume-writer-api.onrender.com")
            val ipList = addresses.joinToString(", ") { it.hostAddress }
            Log.d("DNS", "✅ DNS SUCCESS: $ipList")
            return "✅ DNS Resolution SUCCESS\nIP Addresses: $ipList"
        } catch (e: Exception) {
            Log.e("DNS", "❌ Method 1 failed: ${e.message}")
        }

        // Method 2: Try with timeout
        try {
            val future = Executors.newSingleThreadExecutor().submit<Array<InetAddress>> {
                java.net.InetAddress.getAllByName("resume-writer-api.onrender.com")
            }
            val addresses = future.get(10, TimeUnit.SECONDS)
            val ipList = addresses.joinToString(", ") { it.hostAddress }
            return "✅ DNS Resolution SUCCESS (with timeout)\nIP Addresses: $ipList"
        } catch (e: Exception) {
            Log.e("DNS", "❌ Method 2 failed: ${e.message}")
        }

        "❌ All DNS methods failed\nError: Unable to resolve host\n\nTry:\n1. Switch WiFi/Mobile data\n2. Restart device\n3. Check VPN/Proxy settings"

    } catch (e: Exception) {
        Log.e("DNS", "💥 DNS test crashed: ${e.message}")
        "❌ DNS test crashed: ${e.message}"
    }
}

    
    suspend fun getUserCredits(): ApiResult<JSONObject> {
        return try {
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
                        Log.d("ApiService", "✅ Credits success: ${jsonResponse.toString()}")
                        ApiResult.Success(jsonResponse)
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
            val analysis = analyzeNetworkException(e, "$baseUrl/security-test")
            Log.e("ApiService", analysis)
            ApiResult.Error("Test failed: ${e.message ?: "Unknown error"}")
        }
    }

    // ==================== DEBUG & UTILITY METHODS ====================

    suspend fun debugHttpConnection(): String {
    return try {
        val debug = StringBuilder()
        debug.appendLine("🔌 HTTP CONNECTION DEBUG")
        debug.appendLine("=".repeat(50))
        
        // Test 1: Direct HTTP (bypass HTTPS)
        debug.appendLine("1. HTTP (port 80) test:")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
            
            val request = Request.Builder()
                .url("http://resume-writer-api.onrender.com/health") // HTTP, not HTTPS
                .build()
            
            val response = client.newCall(request).execute()
            debug.appendLine("   ✅ SUCCESS: HTTP ${response.code}")
            debug.appendLine("   Body: ${response.body?.string()?.take(100)}")
        } catch (e: Exception) {
            debug.appendLine("   ❌ FAILED: ${e.javaClass.simpleName} - ${e.message}")
        }

        // Test 2: HTTPS with IP address (bypass DNS)
        debug.appendLine("\n2. HTTPS with IP test:")
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .hostnameVerifier { _, _ -> true } // Bypass hostname verification
                .build()
            
            val request = Request.Builder()
                .url("https://216.24.57.7/health") // Use the IP directly
                .addHeader("Host", "resume-writer-api.onrender.com") // Important for virtual hosting
                .build()
            
            val response = client.newCall(request).execute()
            debug.appendLine("   ✅ SUCCESS: HTTPS ${response.code}")
        } catch (e: Exception) {
            debug.appendLine("   ❌ FAILED: ${e.javaClass.simpleName} - ${e.message}")
        }

        // Test 3: Check if port 443 is reachable
        debug.appendLine("\n3. Port 443 connectivity:")
        try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress("216.24.57.7", 443), 10000)
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
                        val credits = creditsResult.data.optInt("available_credits", -1)
                        debugInfo.appendLine("   • Status: ✅ SUCCESS")
                        debugInfo.appendLine("   • Credits: $credits")
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

    suspend fun runNetworkDiagnostics(): String {
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
            val addresses = java.net.InetAddress.getAllByName("resume-writer-api.onrender.com")
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
                diagnostic.appendLine("     Credits: ${creditResult.data.optInt("available_credits")}")
            }
            is ApiResult.Error -> {
                diagnostic.appendLine("   • Auth Headers: ❌ FAILED")
                diagnostic.appendLine("     Error: ${creditResult.message} (Code: ${creditResult.code})")
            }
        }
        
        diagnostic.appendLine("=".repeat(50))
        return diagnostic.toString()
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
}

suspend fun testDnsResolution(): String {
    return try {
        Log.d("DNS Test", "Testing DNS resolution for: resume-writer-api.onrender.com")
        
        val addresses = java.net.InetAddress.getAllByName("resume-writer-api.onrender.com")
        val ipList = addresses.joinToString(", ") { it.hostAddress }
        
        Log.d("DNS Test", "✅ DNS Resolution SUCCESS: $ipList")
        "✅ DNS Resolution SUCCESS\nIP Addresses: $ipList"
    } catch (e: Exception) {
        Log.e("DNS Test", "❌ DNS Resolution FAILED: ${e.message}")
        "❌ DNS Resolution FAILED\nError: ${e.message}\n\nPossible causes:\n• No internet connection\n• DNS server issues\n• Firewall blocking\n• Domain doesn't exist"
    }
}

// ==================== INTERCEPTOR ====================

class SafeAuthInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        Log.d("AUTH", "🔄 Adding basic headers...")
        
        val request = chain.request().newBuilder()
            .addHeader("User-Agent", "ResumeWriter-Android/1.0")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d("AUTH", "➡️ Sending: ${request.method} ${request.url}")
        
        return chain.proceed(request)
    }
}
