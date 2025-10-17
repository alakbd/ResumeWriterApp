package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.tasks.await
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import com.google.gson.Gson



class ResumeGenerationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var auth: FirebaseAuth
    private lateinit var userManager: UserManager

    private var selectedResumeUri: Uri? = null
    private var selectedJobDescUri: Uri? = null
    private var currentGeneratedResume: JSONObject? = null

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>

    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        apiService = ApiService(this)
        auth = FirebaseAuth.getInstance()

        // ✅ ADD THESE DEBUG CALLS HERE:
        comprehensiveAuthDebug()
        testServerDirectly()
        forceTokenRefreshAndTest()
    
        debugAuthState()
        registerFilePickers()
        setupUI()
        checkGenerateButtonState()
         
    }

    private fun comprehensiveAuthDebug() {
    lifecycleScope.launch {
        Log.d("DEBUG", "=== COMPREHENSIVE AUTH DEBUG ===")
        
        // 1. Your existing app-state checks
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        Log.d("DEBUG", "Firebase User: ${firebaseUser?.uid ?: "NULL"}")
        Log.d("DEBUG", "Firebase Email: ${firebaseUser?.email ?: "NULL"}")
        
        val userManager = UserManager(this@ResumeGenerationActivity)
        Log.d("DEBUG", "UserManager logged in: ${userManager.isUserLoggedIn()}")
        Log.d("DEBUG", "UserManager token valid: ${userManager.isTokenValid()}")
        Log.d("DEBUG", "UserManager token: ${userManager.getUserToken()?.take(10) ?: "NULL"}...")
        
        // 2. Test server connection using ApiService
        Log.d("DEBUG", "Testing server connection via ApiService...")
        val connectionResult = apiService.testConnection() // Calls ApiService method
        when (connectionResult) {
            is ApiService.ApiResult.Success -> {
                Log.d("DEBUG", "✅ Server connection successful")
                Log.d("DEBUG", "Response: ${connectionResult.data}")
            }
            is ApiService.ApiResult.Error -> {
                Log.e("DEBUG", "❌ Server connection failed: ${connectionResult.message}")
            }
        }
        
        // 3. Test authentication using ApiService  
        Log.d("DEBUG", "Testing authentication via ApiService...")
        val token = userManager.getUserToken()
        if (!token.isNullOrBlank()) {
            Log.d("DEBUG", "Token available, testing API call...")
            val creditsResult = apiService.getUserCredits() // Calls ApiService method
            when (creditsResult) {
                is ApiService.ApiResult.Success -> {
                    Log.d("DEBUG", "✅ Authentication SUCCESS!")
                    Log.d("DEBUG", "Credits data: ${creditsResult.data}")
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("DEBUG", "❌ Authentication FAILED: ${creditsResult.message}")
                    Log.e("DEBUG", "Error code: ${creditsResult.code}")
                }
            }
        } else {
            Log.e("DEBUG", "❌ No token available for testing")
        }
        
        Log.d("DEBUG", "=== END DEBUG ===")
    }
}

    
    override fun onResume() {
    super.onResume()
    lifecycleScope.launch {
        val userManager = UserManager(this@ResumeGenerationActivity)
        val token = userManager.getUserToken()
        val tokenValid = userManager.isTokenValid()
        
        Log.d("ResumeActivity", "Token exists: ${token != null}")
        Log.d("ResumeActivity", "Token valid: $tokenValid")
        Log.d("ResumeActivity", "User logged in: ${userManager.isUserLoggedIn()}")
        
        // Quick pre-warm server without UI updates
        if (isNetworkAvailable()) {
            launch {
                try {
                    // Silent server ping to wake up Render if needed
                    val result = apiService.testConnection()
                    if (result is ApiService.ApiResult.Success) {
                        Log.d("ResumeActivity", "✅ Server is already awake")
                    } else {
                        Log.d("ResumeActivity", "🔄 Server might be waking up...")
                    }
                } catch (e: Exception) {
                    Log.d("ResumeActivity", "Pre-warm attempt: ${e.message}")
                    // Silent fail - this is just for warming up
                }
            }
        }
        
        // Update credits display (this will test if auth is working)
        updateCreditDisplay()
        
        // Only show connection test UI if there's a specific issue
        checkAndShowConnectionIfNeeded()
    }
}
    private fun testAuthenticationStepByStep() {
    lifecycleScope.launch {
        Log.d("AuthTest", "=== STEP BY STEP AUTH TEST ===")
        
        // Step 1: Check Firebase
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        Log.d("AuthTest", "Step 1 - Firebase User: ${firebaseUser?.uid ?: "NULL"}")
        
        if (firebaseUser == null) {
            showError("No Firebase user - please login again")
            return@launch
        }
        
        // Step 2: Get fresh token
        Log.d("AuthTest", "Step 2 - Getting fresh token...")
        try {
            val tokenResult = firebaseUser.getIdToken(true).await()
            val token = tokenResult.token
            Log.d("AuthTest", "Step 2 - Token: ${token?.take(20) ?: "NULL"}")
            
            if (token.isNullOrBlank()) {
                showError("Failed to get Firebase token")
                return@launch
            }
            
            // Step 3: Save token
            userManager.saveUserToken(token)
            Log.d("AuthTest", "Step 3 - Token saved to UserManager")
            
            // Step 4: Test API call manually
            Log.d("AuthTest", "Step 4 - Testing API call...")
            val creditsResult = apiService.getUserCredits()
            
            when (creditsResult) {
                is ApiService.ApiResult.Success -> {
                    Log.d("AuthTest", "Step 4 - ✅ SUCCESS! Credits: ${creditsResult.data}")
                    showSuccess("Authentication working!")
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("AuthTest", "Step 4 - ❌ FAILED: ${creditsResult.message}")
                    showError("API call failed: ${creditsResult.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e("AuthTest", "Step 2 - Token error: ${e.message}")
            showError("Token error: ${e.message}")
        }
    }
}


    
    /** ---------------- File Picker Setup ---------------- **/
    private fun registerFilePickers() {
        resumePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleSelectedFile(it, binding.tvResumeFile) { selectedResumeUri = it } }
        }

        jobDescPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleSelectedFile(it, binding.tvJobDescFile) { selectedJobDescUri = it } }
        }
    }

    private fun handleSelectedFile(uri: Uri, textView: TextView, setUri: (Uri) -> Unit) {
        val name = getFileName(uri) ?: ""
        if (name.endsWith(".pdf", true) || name.endsWith(".docx", true) || name.endsWith(".txt", true)) {
            setUri(uri)
            textView.text = name
            textView.setTextColor(getColor(android.R.color.holo_green_dark))
            checkGenerateButtonState()
        } else {
            showError("Unsupported file type. Please select PDF, DOCX, or TXT")
        }
    }

    /** ---------------- UI Setup ---------------- **/
    private fun setupUI() {
        binding.btnSelectResume.setOnClickListener { resumePicker.launch("application/*") }
        binding.btnSelectJobDesc.setOnClickListener { jobDescPicker.launch("application/*") }

        binding.btnClearResume.setOnClickListener {
            selectedResumeUri = null
            binding.tvResumeFile.text = "No file selected"
            binding.tvResumeFile.setTextColor(getColor(android.R.color.darker_gray))
            checkGenerateButtonState()
        }

        binding.btnClearJobDesc.setOnClickListener {
            selectedJobDescUri = null
            binding.tvJobDescFile.text = "No file selected"
            binding.tvJobDescFile.setTextColor(getColor(android.R.color.darker_gray))
            checkGenerateButtonState()
        }

        binding.btnGenerateResume.setOnClickListener {
            when {
                selectedResumeUri != null && selectedJobDescUri != null -> generateResumeFromFiles()
                binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty() -> generateResumeFromText()
                else -> showError("Please provide both resume and job description")
            }
        }

        binding.btnDownloadDocx.setOnClickListener { downloadFile("docx") }
        binding.btnDownloadPdf.setOnClickListener { downloadFile("pdf") }
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRetryConnection.setOnClickListener { testApiConnection() }
    }

    private fun checkGenerateButtonState() {
        val hasFiles = selectedResumeUri != null && selectedJobDescUri != null
        val hasText = binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty()

        binding.btnGenerateResume.isEnabled = hasFiles || hasText
        binding.btnGenerateResume.text = when {
            hasFiles -> "Generate Resume from Files (1 Credit)"
            hasText -> "Generate Resume from Text (1 Credit)"
            else -> "Generate Resume"
        }
    }

    // Add this to your setupUI() method in ResumeGenerationActivity.kt
binding.btnDebugAuth.setOnClickListener {
    lifecycleScope.launch {
        try {
            val debugInfo = apiService.debugAuthenticationFlow()
            Log.d("AuthDebug", debugInfo)

             binding.btnDebugAuth.setOnClickListener {
                runApiServiceDebug()
            }
            // Show in UI for easy viewing
            binding.tvGeneratedResume.text = debugInfo
            binding.layoutDownloadButtons.visibility = View.GONE
        } catch (e: Exception) {
            Log.e("AuthDebug", "Debug failed: ${e.message}")
        }
    }
}
 

/** ---------------- API Connection Test ---------------- **/
/** ---------------- Smart Connection Check ---------------- **/
private fun checkAndShowConnectionIfNeeded() {
    lifecycleScope.launch {
        // Only show connection UI if we haven't successfully connected recently
        val lastSuccessTime = getLastSuccessTime()
        val timeSinceLastSuccess = System.currentTimeMillis() - lastSuccessTime
        
        // If we had a successful connection in the last 5 minutes, don't show connection UI
        if (timeSinceLastSuccess < 5 * 60 * 1000) {
            Log.d("ResumeActivity", "Recent successful connection, skipping UI test")
            return@launch
        }
        
        // Otherwise, test connection with UI
        testApiConnection()
    }
}

private fun getLastSuccessTime(): Long {
    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getLong("last_successful_connection", 0)
}

private fun saveSuccessTime() {
    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    prefs.edit().putLong("last_successful_connection", System.currentTimeMillis()).apply()
}

/** ---------------- Updated Connection Test ---------------- **/
private fun testApiConnection() {
    binding.layoutConnectionStatus.visibility = View.VISIBLE
    binding.tvConnectionStatus.text = "Testing connection..."
    binding.progressConnection.visibility = View.VISIBLE
    binding.btnRetryConnection.isEnabled = false

    lifecycleScope.launch {
        if (!isNetworkAvailable()) {
            updateConnectionStatus("❌ No internet connection", true)
            binding.progressConnection.visibility = View.GONE
            binding.btnRetryConnection.isEnabled = true
            showError("Please check your internet connection")
            return@launch
        }

        try {
            Log.d("ResumeActivity", "Testing API connection...")
            
            // First, test basic connection
            val connectionResult = apiService.testConnection()
            
            when (connectionResult) {
                is ApiService.ApiResult.Success -> {
                    updateConnectionStatus("✅ API Connected", false)
                    saveSuccessTime() // Remember we had a successful connection
                    updateCreditDisplay()
                }
                is ApiService.ApiResult.Error -> {
                    // Check if it's a server wake-up issue
                    if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                        updateConnectionStatus("🔄 Server is waking up...", true)
                        showServerWakeupMessage()
                        
                        // Wait for server to wake up
                        val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 8, delayBetweenAttempts = 5000L)
                        
                        if (serverAwake) {
                            updateConnectionStatus("✅ Server is ready!", false)
                            saveSuccessTime()
                            updateCreditDisplay()
                        } else {
                            updateConnectionStatus("⏰ Server taking too long", true)
                            showError("Render server is taking longer than expected. Please try again in a minute.")
                        }
                    } else {
                        updateConnectionStatus("❌ API Connection Failed", true)
                        showError("API error: ${connectionResult.message}")
                    }
                }
            }
        } catch (e: Exception) {
            updateConnectionStatus("❌ Connection Error", true)
            Log.e("ResumeActivity", "Connection test failed", e)
            showError("Connection failed: ${e.message}")
        } finally {
            binding.progressConnection.visibility = View.GONE
            binding.btnRetryConnection.isEnabled = true
        }
    }
}

private fun showServerWakeupMessage() {
    Toast.makeText(
        this, 
        "🔄 Server is waking up... This may take 30-60 seconds on first launch.", 
        Toast.LENGTH_LONG
    ).show()
}

/** ---------------- Enhanced Connection Status ---------------- **/
private fun updateConnectionStatus(message: String, isError: Boolean = false, isWarning: Boolean = false) {
    binding.tvConnectionStatus.text = message
    
    val color = when {
        isError -> getColor(android.R.color.holo_red_dark)
        isWarning -> getColor(android.R.color.holo_orange_dark)
        else -> getColor(android.R.color.holo_green_dark)
    }
    
    binding.tvConnectionStatus.setTextColor(color)
    Log.d("ResumeActivity", "Connection status: $message")
}

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri ?: return showError("Please select resume file")
        val jobDescUri = selectedJobDescUri ?: return showError("Please select job description file")

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                Log.d("ResumeActivity", "Checking user credits")
                val creditResult = apiService.getUserCredits()
                if (creditResult is ApiService.ApiResult.Success) {
                    // ✅ FIXED: Use correct field name
                    val credits = creditResult.data.optInt("available_credits", 0)
                    Log.d("ResumeActivity", "User has $credits credits")
                    if (credits <= 0) {
                        showErrorAndReset("Insufficient credits. Please purchase more.")
                        return@launch
                    }

                    Log.d("ResumeActivity", "Generating resume from files")
                    val genResult = retryApiCall { apiService.generateResumeFromFiles(resumeUri, jobDescUri) }
                    handleGenerationResult(genResult)
                } else if (creditResult is ApiService.ApiResult.Error) {
                    Log.e("ResumeActivity", "Failed to get credits: ${creditResult.message}")
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Exception in generateResumeFromFiles: ${e.message}", e)
                showErrorAndReset("Generation failed: ${e.message}")
            } finally {
                resetGenerateButton()
            }
        }
    }

    private fun generateResumeFromText() {
        val resumeText = binding.etResumeText.text.toString().trim()
        val jobDesc = binding.etJobDescription.text.toString().trim()

        if (resumeText.isEmpty() || jobDesc.isEmpty()) {
            showError("Please enter both resume text and job description")
            return
        }

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                val creditResult = retryApiCall { apiService.getUserCredits() }
                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        // ✅ FIXED: Use correct field name
                        val credits = creditResult.data.optInt("available_credits", 0)
                        Log.d("ResumeActivity", "User has $credits credits")
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }
                        val genResult = retryApiCall { apiService.generateResume(resumeText, jobDesc) }
                        handleGenerationResult(genResult)
                    }
                    is ApiService.ApiResult.Error -> {
                        Log.e("ResumeActivity", "Failed to check credits: ${creditResult.message}")
                        showErrorAndReset("Failed to check credits: ${creditResult.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Exception in generateResumeFromText: ${e.message}", e)
                showErrorAndReset("Generation failed: ${e.message}")
            } finally {
                resetGenerateButton()
            }
        }
    }

    private suspend fun <T> retryApiCall(
    maxRetries: Int = 3,
    initialDelay: Long = 2000L,
    block: suspend () -> ApiService.ApiResult<T>
): ApiService.ApiResult<T> {
    var lastResult: ApiService.ApiResult<T>? = null
    
    repeat(maxRetries) { attempt ->
        val result = block()
        
        if (result is ApiService.ApiResult.Success) {
            return result
        }
        
        lastResult = result
        
        // Handle server wake-up specifically
        if (result is ApiService.ApiResult.Error) {
            if (result.code in 500..599) {
                Log.w("Retry", "Server error detected, waiting for wake-up...")
                // Wait longer for server wake-up
                val waitTime = initialDelay * (attempt + 1) * 2 // Exponential backoff
                Log.d("Retry", "Waiting ${waitTime}ms before retry ${attempt + 1}/$maxRetries")
                delay(waitTime)
            } else {
                // Regular retry for other errors
                if (attempt < maxRetries - 1) {
                    val delayTime = initialDelay * (attempt + 1)
                    Log.d("Retry", "Waiting ${delayTime}ms before retry ${attempt + 1}/$maxRetries")
                    delay(delayTime)
                }
            }
        } else {
            // Non-error case (shouldn't happen)
            if (attempt < maxRetries - 1) {
                val delayTime = initialDelay * (attempt + 1)
                delay(delayTime)
            }
        }
    }
    
    return lastResult ?: ApiService.ApiResult.Error("All retry attempts failed")
}

    /** ---------------- Display & Download ---------------- **/
    private fun displayGeneratedResume(resumeData: JSONObject) {
        try {
            binding.tvGeneratedResume.text = resumeData.getString("resume_text")
            binding.layoutDownloadButtons.visibility = View.VISIBLE

            // ✅ FIXED: Use correct field name
            if (resumeData.has("remaining_credits")) {
                val remaining = resumeData.getInt("remaining_credits")
                binding.tvCreditInfo.text = "Remaining credits: $remaining"
                binding.tvCreditInfo.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e("ResumeActivity", "Error displaying resume: ${e.message}", e)
            showError("Error displaying resume: ${e.message}")
        }
    }

    private fun downloadFile(format: String) {
        val resumeData = currentGeneratedResume ?: return showError("No resume generated yet")
        lifecycleScope.launch {
            try {
                val fileName = "generated_resume.${format.lowercase()}"
                val base64Key = "${format.lowercase()}_data"

                if (!resumeData.has(base64Key)) {
                    showError("$format format not available for download")
                    return@launch
                }

                val fileData = apiService.decodeBase64File(resumeData.getString(base64Key))
                val file = apiService.saveFileToStorage(fileData, fileName)
                showDownloadSuccess(file, format.uppercase())
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Download failed: ${e.message}", e)
                showError("Download failed: ${e.message}")
            }
        }
    }

    private fun showDownloadSuccess(file: File, format: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = when (format) {
                "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "PDF" -> "application/pdf"
                else -> "*/*"
            }
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            putExtra(Intent.EXTRA_SUBJECT, "Generated Resume")
            putExtra(Intent.EXTRA_TEXT, "Here's your generated resume in $format format")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        Toast.makeText(this, "$format file saved successfully!", Toast.LENGTH_LONG).show()
        startActivity(Intent.createChooser(shareIntent, "Share Resume"))
    }

    /** ---------------- Credit Display ---------------- **/
private suspend fun updateCreditDisplay() {
    Log.d("ResumeActivity", "Fetching user credits...")

    // ✅ Step 1: Ensure token is available and valid before making API call
    var token = userManager.getUserToken()
    if (token.isNullOrEmpty()) {
        Log.w("ResumeActivity", "No auth token found — attempting to refresh.")
        FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.addOnSuccessListener {
            it.token?.let { newToken ->
                userManager.saveUserToken(newToken)
                Log.d("ResumeActivity", "Token refreshed successfully.")
            }
        }
        // Optional: short delay to give the refresh a moment (or skip)
        token = userManager.getUserToken()
        if (token.isNullOrEmpty()) {
            Log.e("ResumeActivity", "Still no token available, aborting credit fetch.")
            withContext(Dispatchers.Main) {
                binding.creditText.text = "Credits: --"
            }
            return
        }
    }

    // ✅ Step 2: Proceed with normal API call
    when (val result = apiService.getUserCredits()) {
        is ApiService.ApiResult.Success -> {
            // ✅ FIXED: Use correct field name
            val credits = result.data.optInt("available_credits", 0)
            Log.d("ResumeActivity", "Credits retrieved: $credits")

            withContext(Dispatchers.Main) {
                binding.creditText.text = "Credits: $credits"
            }
        }

        is ApiService.ApiResult.Error -> {
            Log.e("ResumeActivity", "Failed to fetch credits: ${result.message}")
            withContext(Dispatchers.Main) {
                binding.creditText.text = "Credits: --"
                Toast.makeText(
                    this@ResumeGenerationActivity,
                    result.message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
    /** ---------------- Helpers ---------------- **/
    private fun disableGenerateButton(text: String) {
        binding.btnGenerateResume.isEnabled = false
        binding.btnGenerateResume.text = text
        binding.progressGenerate.visibility = View.VISIBLE
    }

    private fun resetGenerateButton() {
        binding.btnGenerateResume.isEnabled = true
        binding.btnGenerateResume.text = "Generate Resume"
        binding.progressGenerate.visibility = View.GONE
        checkGenerateButtonState()
    }

    private fun showErrorAndReset(msg: String) {
        showError(msg)
        resetGenerateButton()
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                cursor.getString(nameIndex)
            }
        } catch (e: Exception) {
            Log.e("ResumeActivity", "Failed to get file name: ${e.message}", e)
            null
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
        Log.e("ResumeActivity", message)
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, "✅ $message", Toast.LENGTH_SHORT).show()
        Log.d("ResumeActivity", message)
    }

    private fun updateConnectionStatus(message: String, isError: Boolean = false) {
        binding.tvConnectionStatus.text = message
        binding.tvConnectionStatus.setTextColor(
            if (isError) getColor(android.R.color.holo_red_dark)
            else getColor(android.R.color.holo_green_dark)
        )
        Log.d("ResumeActivity", "Connection status updated: $message")
    }

    private fun testServerDirectly() {
    lifecycleScope.launch {
        Log.d("DirectTest", "Testing server endpoints directly...")
        
        // Test public endpoints
        val endpoints = listOf(
            "https://resume-writer-api.onrender.com/health",
            "https://resume-writer-api.onrender.com/test",
            "https://resume-writer-api.onrender.com/"
        )
        
        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .get()
                    .build()
                
                val response = OkHttpClient().newCall(request).execute()
                Log.d("DirectTest", "$endpoint → ${response.code}")
                response.body?.string()?.let { body ->
                    Log.d("DirectTest", "Response: $body")
                }
            } catch (e: Exception) {
                Log.e("DirectTest", "$endpoint → ERROR: ${e.message}")
            }
        }
    }
}

    private fun forceTokenRefreshAndTest() {
    lifecycleScope.launch {
        Log.d("TokenDebug", "Forcing token refresh...")
        
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            try {
                val tokenResult = user.getIdToken(true).await()
                val newToken = tokenResult.token
                
                if (!newToken.isNullOrBlank()) {
                    userManager.saveUserToken(newToken)
                    Log.d("TokenDebug", "✅ New token saved: ${newToken.take(10)}...")
                    
                    // Test with new token
                    val creditsResult = apiService.getUserCredits()
                    when (creditsResult) {
                        is ApiService.ApiResult.Success -> {
                            Log.d("TokenDebug", "✅ SUCCESS with new token!")
                        }
                        is ApiService.ApiResult.Error -> {
                            Log.e("TokenDebug", "❌ FAILED with new token: ${creditsResult.message}")
                        }
                    }
                } else {
                    Log.e("TokenDebug", "❌ New token is null")
                }
            } catch (e: Exception) {
                Log.e("TokenDebug", "❌ Token refresh failed: ${e.message}")
            }
        } else {
            Log.e("TokenDebug", "❌ No Firebase user")
        }
    }
}
    
    
    private fun debugAuthState() {
    val userManager = UserManager(this)
    
    Log.d("AuthDebug", "=== AUTHENTICATION DEBUG ===")
    Log.d("AuthDebug", "Firebase current user: ${FirebaseAuth.getInstance().currentUser?.uid ?: "NULL"}")
    Log.d("AuthDebug", "UserManager logged in: ${userManager.isUserLoggedIn()}")
    Log.d("AuthDebug", "Token valid: ${userManager.isTokenValid()}")
    Log.d("AuthDebug", "Cached token: ${userManager.getUserToken()?.take(10) ?: "NULL"}...")
    
    userManager.debugStoredData()
    
    lifecycleScope.launch {
        try {
            Log.d("AuthDebug", "Testing credits API...")
            val creditsResult = apiService.getUserCredits()

            when (creditsResult) {
                is ApiService.ApiResult.Success -> {
                    Log.d("AuthDebug", "✅ Credits API SUCCESS - Auth is working")
                    // ✅ FIXED: Use correct field name
                    val credits = creditsResult.data.optInt("available_credits", 0)
                    Log.d("AuthDebug", "Available credits: $credits")
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("AuthDebug", "❌ Credits API FAILED: ${creditsResult.message}")
                    Log.e("AuthDebug", "Error code: ${creditsResult.code}")
                }
            }
        } catch (e: Exception) {
            Log.e("AuthDebug", "❌ Exception during auth test: ${e.message}")
        }
    }
}

    private fun runApiServiceDebug() {
    lifecycleScope.launch {
        binding.tvGeneratedResume.text = "Running API service debug..."
        binding.progressGenerate.visibility = View.VISIBLE
        
        try {
            // This calls debugAuthenticationFlow() from ApiService.kt
            val debugResult = apiService.debugAuthenticationFlow()
            
            // Display the results
            binding.tvGeneratedResume.text = debugResult
            binding.layoutDownloadButtons.visibility = View.GONE
            
        } catch (e: Exception) {
            binding.tvGeneratedResume.text = "Debug failed: ${e.message}"
            Log.e("Debug", "API debug failed", e)
        } finally {
            binding.progressGenerate.visibility = View.GONE
        }
    }
}
    
    private fun testAuthHeader() {
    lifecycleScope.launch {
        val token = userManager.getUserToken()
        Log.d("AuthDebug", "Token: ${token?.take(10)}...")
        Log.d("AuthDebug", "Header will be: X-Auth-Token: Bearer ${token?.take(10)}...")
        
        val result = apiService.getUserCredits()
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("AuthDebug", "✅ SUCCESS! Credits: ${result.data}")
                // ✅ FIXED: Use correct field name
                val credits = result.data.optInt("available_credits", 0)
                binding.creditText.text = "Credits: $credits"
            }
            is ApiService.ApiResult.Error -> {
                Log.e("AuthDebug", "❌ FAILED: ${result.message} (Code: ${result.code})")
            }
        }
    }
}

    
    
    private fun handleGenerationResult(result: ApiService.ApiResult<JSONObject>) {
    when (result) {
        is ApiService.ApiResult.Success -> {
            Log.d("ResumeActivity", "Resume generation success: ${result.data}")
            currentGeneratedResume = result.data
            displayGeneratedResume(result.data)
            showSuccess("Resume generated successfully!")

            // ✅ Update credits from response if available
            if (result.data.has("remaining_credits")) {
                val remaining = result.data.getInt("remaining_credits")
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        binding.creditText.text = "Credits: $remaining"
                    }
                }
            } else {
                // Fallback to API call
                lifecycleScope.launch {
                    updateCreditDisplay()
                }
            }
        }
        is ApiService.ApiResult.Error -> {
            Log.e("ResumeActivity", "Resume generation failed: ${result.message}")
            showError("Generation failed: ${result.message}")
        }
    }
}
}
