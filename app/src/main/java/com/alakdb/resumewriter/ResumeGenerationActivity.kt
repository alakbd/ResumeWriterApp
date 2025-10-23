package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.tasks.await
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.appcompat.app.AlertDialog
import android.content.DialogInterface
import com.alakdb.resumewriter.UserManager
import com.alakdb.resumewriter.BuildConfig
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.Request

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
    private var lastToastTime: Long = 0
    private val TOAST_COOLDOWN_MS = 3000L // 3 seconds between toasts

    private companion object {
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        apiService = ApiService(this) // FIXED: Remove userManager parameter
        auth = FirebaseAuth.getInstance()

        // ⚠️ CRITICAL: These MUST be called synchronously in onCreate
        registerFilePickers() // Must be called before activity reaches STARTED state
        setupUI() // Setup UI components immediately
        checkEmailVerification() // Can be called here safely
        checkGenerateButtonState() // Update button state
        handleFreshInstall()
        testHeaderSending()

    // ADD this method to ResumeGenerationActivity.kt (around line 70)
    private fun testHeaderSending() {
    lifecycleScope.launch {
        try {
            binding.tvGeneratedResume.text = "Testing headers...\n(Server may be waking up)"
            binding.progressGenerate.visibility = View.VISIBLE
            
            // First, test if server is awake
            val connectionResult = apiService.testConnection()
            
            when (connectionResult) {
                is ApiService.ApiResult.Success -> {
                    // Server is awake - test headers with credits endpoint
                    binding.tvGeneratedResume.text = "Server awake! Testing headers..."
                    
                    val result = apiService.getUserCredits()
                    when (result) {
                        is ApiService.ApiResult.Success -> {
                            val credits = result.data.optInt("available_credits", 0)
                            showMessage("✅ Headers working! Credits: $credits")
                            binding.tvGeneratedResume.text = "Headers OK - Credits: $credits"
                        }
                        is ApiService.ApiResult.Error -> {
                            showMessage("❌ Header issue: ${result.message}")
                            binding.tvGeneratedResume.text = "Header failed: ${result.message}"
                        }
                    }
                }
                is ApiService.ApiResult.Error -> {
                    // Server is sleeping or having issues
                    if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                        binding.tvGeneratedResume.text = "🔄 Server is waking up...\nThis may take 30-60 seconds\n\nTrying automatic wake-up..."
                        showMessage("Server is waking up. Please wait...")
                        
                        // Trigger server wake-up
                        val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 6, delayBetweenAttempts = 10000L)
                        
                        if (serverAwake) {
                            binding.tvGeneratedResume.text = "✅ Server is now awake!\nTesting headers..."
                            testHeaderSending() // Recursive call to test again
                        } else {
                            binding.tvGeneratedResume.text = "⏰ Server taking too long\nPlease try again in 1 minute"
                            showMessage("Server is taking longer than expected. Please try the test again.")
                        }
                    } else {
                        showMessage("❌ Connection issue: ${connectionResult.message}")
                        binding.tvGeneratedResume.text = "Connection failed: ${connectionResult.message}"
                    }
                }
            }
        } catch (e: Exception) {
            showMessage("Test failed: ${e.message}")
            binding.tvGeneratedResume.text = "Test error: ${e.message}"
        } finally {
            binding.progressGenerate.visibility = View.GONE
        }
    }
}
    
        // Debug calls (safe to call here)
        debugUserManagerState()

        // CRITICAL: Force immediate UserManager sync with Firebase
        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 Initializing UserManager sync...")
            
            // Step 1: Emergency sync to ensure UserManager has current user data
            userManager.emergencySyncWithFirebase()
            
            // Step 2: Debug the current state
            userManager.logCurrentUserState()
            userManager.debugStoredData()
            
            // Step 3: Test API connection if user is authenticated
            delay(1000) // Give time for sync to complete
            
            if (ensureUserAuthenticated()) {
                Log.d("ResumeActivity", "✅ User authenticated - testing API connection")
                testApiConnection()
            } else {
                Log.w("ResumeActivity", "⚠️ User not authenticated - skipping API test")
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                    binding.layoutConnectionStatus.visibility = View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 onResume - refreshing data...")
            
            // Force sync to ensure fresh data
            userManager.emergencySyncWithFirebase()
            
            // Update UI with current state
            withContext(Dispatchers.Main) {
                checkGenerateButtonState()
            }
            
            // Update credits display if user is logged in
            if (userManager.isUserLoggedIn()) {
                updateCreditDisplay()
                testApiConnection()
            } else {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                }
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
            // Check if user is logged in first
            if (!userManager.isUserLoggedIn()) {
                showError("Please log in to generate resumes")
                return@setOnClickListener
            }
            
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

      / Add server status text view if not already there
        binding.tvServerStatus.text = "Server status: Checking..."
        binding.tvServerStatus.setTextColor(getColor(android.R.color.darker_gray))
        
        // Add debug button
        binding.btnDebugAuth.setOnClickListener {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val debugInfo = "Firebase UID: ${firebaseUser?.uid ?: "NULL"}"
    
            showMessage("$debugInfo\n\nTesting server connection...")
            Log.d("AuthDebug", debugInfo)
    
        // Test header sending with server wake-up awareness
        testHeaderSending()
        }
    

        
    private fun checkGenerateButtonState() {
        val hasFiles = selectedResumeUri != null && selectedJobDescUri != null
        val hasText = binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty()
        val isLoggedIn = userManager.isUserLoggedIn()

        binding.btnGenerateResume.isEnabled = (hasFiles || hasText) && isLoggedIn
        binding.btnGenerateResume.text = when {
            !isLoggedIn -> "Please Log In"
            hasFiles -> "Generate Resume from Files (1 Credit)"
            hasText -> "Generate Resume from Text (1 Credit)"
            else -> "Generate Resume"
        }
    }

    /** ---------------- Check email/email sending Verification ---------------- **/
    private fun checkEmailVerification() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null && !user.isEmailVerified) {
            Log.w("AuthDebug", "⚠️ Email is not verified: ${user.email}")

            val builder = AlertDialog.Builder(this)
                .setTitle("Email Verification Required")
                .setMessage("Your email ${user.email} is not verified. Some features may not work properly. Please check your email for a verification link.")
                .setPositiveButton("Send Verification", DialogInterface.OnClickListener { _, _ ->
                    sendEmailVerification()
                })
                .setNegativeButton("Continue Anyway", DialogInterface.OnClickListener { _, _ ->
                    Toast.makeText(this, "Some features may not work without email verification", Toast.LENGTH_LONG).show()
                })
                .setNeutralButton("Sign Out") { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    finish()
                }

            val dialog = builder.create()
            dialog.show()
        }
    }

    private fun sendEmailVerification() {
        val user = FirebaseAuth.getInstance().currentUser
        user?.sendEmailVerification()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Toast.makeText(this, "Verification email sent to ${user.email}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Failed to send verification email", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showRateLimitedToast(message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTime > TOAST_COOLDOWN_MS) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            lastToastTime = currentTime
            Log.d("Toast", "Showing: $message")
        } else {
            Log.d("Toast", "Rate limited: $message")
        }
    }

    private fun showRateLimitedError(message: String) {
        showRateLimitedToast("❌ $message")
    }

    private fun showRateLimitedSuccess(message: String) {
        showRateLimitedToast("✅ $message")
    }
    
    /** ---------------- API Connection Test ---------------- **/
    private fun testApiConnection() {
    binding.layoutConnectionStatus.visibility = View.VISIBLE
    binding.tvConnectionStatus.text = "Testing connection...\n(Server may be waking up)"
    binding.progressConnection.visibility = View.VISIBLE
    binding.btnRetryConnection.isEnabled = false

    lifecycleScope.launch {
        safeApiCall {
            if (!isNetworkAvailable()) {
                updateConnectionStatus("❌ No internet connection", true)
                return@safeApiCall
            }

            try {
                val connectionResult = apiService.testConnection()
                
                when (connectionResult) {
                    is ApiService.ApiResult.Success -> {
                        updateConnectionStatus("✅ API Connected", false)
                        updateCreditDisplay()
                    }
                    is ApiService.ApiResult.Error -> {
                        // Handle server wake-up specifically
                        if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                            updateConnectionStatus("🔄 Server is waking up...", true)
                            showServerWakeupMessage()
                            
                            val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 8, delayBetweenAttempts = 10000L)
                            
                            if (serverAwake) {
                                updateConnectionStatus("✅ Server is ready!", false)
                                updateCreditDisplay()
                            } else {
                                updateConnectionStatus("⏰ Server taking too long", true)
                                showError("Server is taking longer than expected. Please try again in a minute.")
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
            } finally {
                binding.progressConnection.visibility = View.GONE
                binding.btnRetryConnection.isEnabled = true
            }
        }
    }
}

    // ADD THIS MISSING METHOD:
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun ensureUserAuthenticated(): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            if (firebaseUser == null) {
                Log.e("Auth", "❌ No Firebase user")
                return@withContext false
            }
            
            val userId = firebaseUser.uid
            val email = firebaseUser.email ?: ""
            
            // CRITICAL: Force UserManager to match Firebase
            userManager.saveUserDataLocally(email, userId)
            
            Log.d("Auth", "✅ User authenticated: ${userId.take(8)}...")
            true
        } catch (e: Exception) {
            Log.e("Auth", "💥 Auth check failed: ${e.message}")
            false
        }
    }
}

    private suspend fun safeApiCall(block: suspend () -> Unit) {
        try {
            if (!ensureUserAuthenticated()) {
                Log.w("ResumeActivity", "⚠️ Blocked API call - user not authenticated")
                return
            }
            block()
        } catch (e: Exception) {
            Log.e("ResumeActivity", "💥 Safe API call failed: ${e.message}", e)
            showError("Network error: ${e.message}")
        }
    }

    private fun showReloginPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Authentication Issue")
            .setMessage("There seems to be an authentication problem. Would you like to log out and log in again?")
            .setPositiveButton("Log Out & Re-login") { _, _ ->
                userManager.logout()
                finish() // Go back to login screen
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri ?: return showError("Please select resume file")
        val jobDescUri = selectedJobDescUri ?: return showError("Please select job description file")

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                // Check authentication first
                if (!ensureAuthenticatedBeforeApiCall()) {
                    resetGenerateButton()
                    return@launch
                }

                Log.d("ResumeActivity", "Checking user credits")
                val creditResult = apiService.getUserCredits()
                
                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.optInt("available_credits", 0)
                        Log.d("ResumeActivity", "User has $credits credits")
                        
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }

                        Log.d("ResumeActivity", "Generating resume from files")
                        val genResult = retryApiCall { 
                            apiService.generateResumeFromFiles(resumeUri, jobDescUri) 
                        }
                        handleGenerationResult(genResult)
                    }
                    is ApiService.ApiResult.Error -> {
                        Log.e("ResumeActivity", "Failed to get credits: ${creditResult.message}")
                        showErrorAndReset("Failed to check credits: ${creditResult.message}")
                        
                        // If it's an auth error, suggest re-login
                        if (creditResult.code == 401) {
                            showError("Authentication failed. Please log out and log in again.")
                            userManager.logout()
                            checkGenerateButtonState()
                        }
                    }
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
                // Check authentication first - ADD THIS CHECK
                if (!ensureAuthenticatedBeforeApiCall()) {
                    resetGenerateButton()
                    return@launch
                }

                // Use the same retry pattern as generateResumeFromFiles
                val creditResult = retryApiCall { apiService.getUserCredits() }
                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.optInt("available_credits", 0)
                        Log.d("ResumeActivity", "User has $credits credits")
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }
                        
                        Log.d("ResumeActivity", "Generating resume from text input")
                        val genResult = retryApiCall { 
                            apiService.generateResume(resumeText, jobDesc) 
                        }
                        handleGenerationResult(genResult)
                    }
                    is ApiService.ApiResult.Error -> {
                        Log.e("ResumeActivity", "Failed to check credits: ${creditResult.message}")
                        showErrorAndReset("Failed to check credits: ${creditResult.message}")
                        
                        // Handle auth errors specifically - ADD THIS
                        if (creditResult.code == 401) {
                            showError("Authentication failed. Please log out and log in again.")
                            userManager.logout()
                            checkGenerateButtonState()
                        }
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
        maxRetries: Int = 2,
        initialDelay: Long = 1000L,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        var lastResult: ApiService.ApiResult<T>? = null
        repeat(maxRetries) { attempt ->
            val result = block()
            if (result is ApiService.ApiResult.Success) return result
            lastResult = result
            if (attempt < maxRetries - 1) {
                val delayTime = initialDelay * (attempt + 1)
                Log.d("ResumeActivity", "Retry ${attempt + 1}/$maxRetries in ${delayTime}ms")
                delay(delayTime)
            }
        }
        return lastResult ?: ApiService.ApiResult.Error("All retry attempts failed")
    }

    /** ---------------- Display & Download ---------------- **/
    private fun displayGeneratedResume(resumeData: JSONObject) {
        try {
            binding.tvGeneratedResume.text = resumeData.getString("resume_text")
            binding.layoutDownloadButtons.visibility = View.VISIBLE

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

                // FIXED: Call methods on apiService instance
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

    private suspend fun ensureAuthenticatedBeforeApiCall(): Boolean {
        if (!userManager.isUserLoggedIn()) {
            Log.e("ResumeActivity", "❌ User not logged in for API call")
            withContext(Dispatchers.Main) {
                showError("Please log in to continue")
                binding.creditText.text = "Credits: Please log in"
            }
            return false
        }
        
        val userId = userManager.getCurrentUserId()
        if (userId.isNullOrBlank()) {
            Log.e("ResumeActivity", "❌ User ID is null for API call")
            withContext(Dispatchers.Main) {
                showError("Authentication error. Please log out and log in again.")
                binding.creditText.text = "Credits: Auth error"
            }
            return false
        }
        
        Log.d("ResumeActivity", "✅ User authenticated for API call: ${userId.take(8)}...")
        return true
    }

    /** ---------------- Credit Display ---------------- **/
    private suspend fun updateCreditDisplay() {
        try {
            val result = apiService.getUserCredits()
            when (result) {
                is ApiService.ApiResult.Success -> {
                    runOnUiThread {
                        val credits = result.data.optInt("credits", 0)
                        binding.creditText.text = "Credits: $credits"
                    }
                }
                is ApiService.ApiResult.Error -> {
                    Log.w("ResumeGeneration", "Failed to get credits: ${result.message}")
                    // Use cached credits or show default
                    runOnUiThread {
                        val cachedCredits = userManager.getCachedCredits()
                        binding.creditText.text = "Credits: $cachedCredits"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ResumeGeneration", "Credit update failed", e)
            runOnUiThread {
                binding.creditText.text = "Credits: --"
            }
        }
    }

    /** ---------------- Debug Methods ---------------- **/
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
    
    private fun quickAuthCheck(): String {
    val firebaseUser = FirebaseAuth.getInstance().currentUser
    val userManagerUser = userManager.getCurrentUserId()
    
    return """
    🔍 QUICK AUTH CHECK:
    Firebase UID: ${firebaseUser?.uid ?: "NULL"}
    UserManager UID: ${userManagerUser ?: "NULL"}
    Match: ${firebaseUser?.uid == userManagerUser}
    Firebase Email: ${firebaseUser?.email ?: "NULL"}
    UserManager Email: ${userManager.getCurrentUserEmail() ?: "NULL"}
    """.trimIndent()
}
    
    private fun debugAuthAndCredits() {
        lifecycleScope.launch {
            try {
                binding.progressGenerate.visibility = View.VISIBLE
                
                // Force sync UserManager with Firebase first
                apiService.forceSyncUserManager()
                
                val debugInfo = apiService.debugAuthenticationFlow()
                
                // Show the debug info
                binding.tvGeneratedResume.text = debugInfo
                Log.d("AuthDebug", debugInfo)
                
            } catch (e: Exception) {
                showMessage("Debug failed: ${e.message}")
            } finally {
                binding.progressGenerate.visibility = View.GONE
            }
        }
    }

    private fun handleFreshInstall() {
    val firebaseUser = FirebaseAuth.getInstance().currentUser
    if (firebaseUser != null && !userManager.isUserLoggedIn()) {
        Log.w("FreshInstall", "🆘 Firebase user exists but UserManager is empty - RECOVERING")
        
        val userId = firebaseUser.uid
        val email = firebaseUser.email ?: ""
        
        userManager.saveUserDataLocally(email, userId)
        Log.d("FreshInstall", "✅ Recovered user session: ${userId.take(8)}...")
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
        showRateLimitedError(message)
        Log.e("ResumeActivity", message)
    }

    private fun showSuccess(message: String) {
        showRateLimitedSuccess(message)
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

    private fun showServerWakeupMessage() {
        Toast.makeText(
            this, 
            "🔄 Server is waking up... This may take 30-60 seconds on first launch.", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun debugUserManagerState() {
        Log.d("Debug", "=== USER MANAGER STATE DEBUG ===")
        
        // Check UserManager directly
        val userId = userManager.getCurrentUserId()
        Log.d("Debug", "UserManager.getCurrentUserId(): '$userId'")
        Log.d("Debug", "UserManager.isUserLoggedIn(): ${userManager.isUserLoggedIn()}")
        
        // Check Firebase directly
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        Log.d("Debug", "FirebaseAuth.currentUser: ${firebaseUser?.uid}")
        Log.d("Debug", "FirebaseAuth.email: ${firebaseUser?.email}")
        
        // Check if they match
        if (userId != null && firebaseUser != null) {
            Log.d("Debug", "MATCH: ${userId == firebaseUser.uid}")
        } else {
            Log.d("Debug", "MISMATCH: One or both are null")
        }
        
        Log.d("Debug", "=== END DEBUG ===")
    }

    private fun handleGenerationResult(result: ApiService.ApiResult<JSONObject>) {
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("ResumeActivity", "Resume generation success: ${result.data}")
                currentGeneratedResume = result.data
                displayGeneratedResume(result.data)
                showSuccess("Resume generated successfully!")

                // Update credits from response if available
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
