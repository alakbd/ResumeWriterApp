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

    private companion object {
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 3000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        apiService = ApiService(this)
        auth = FirebaseAuth.getInstance()
        
        // Ensure user info is saved in UserManager if logged in
        FirebaseAuth.getInstance().currentUser?.let { user ->
            val uid = user.uid
            val email = user.email ?: ""
            userManager.saveUserDataLocally(email, uid)
            }


        // Debug calls
        checkEmailVerification()
        registerFilePickers()
        setupUI()
        checkGenerateButtonState()

        // Test connection safely
        lifecycleScope.launch {
            delay(30000)
            if (ensureUserAuthenticated()) {
                testApiConnection()
            } else {
                Log.e("ResumeActivity", "Skipped testApiConnection — user not authenticated yet.")
            }
        }
    } // Added missing closing brace for onCreate()

    private fun comprehensiveAuthDebug() {
    lifecycleScope.launch {
        val debugInfo = StringBuilder()
        debugInfo.appendLine("=== COMPREHENSIVE AUTH DEBUG ===")

        // 1. Check Firebase Auth state
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        debugInfo.appendLine("1. FIREBASE AUTH STATE:")
        debugInfo.appendLine("   • User ID: ${firebaseUser?.uid ?: "NULL"}")
        debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
        debugInfo.appendLine("   • Email Verified: ${firebaseUser?.isEmailVerified ?: false}")

        // 2. Check UserManager state
        debugInfo.appendLine("2. USER MANAGER STATE:")
        debugInfo.appendLine("   • Is User Logged In: ${userManager.isUserLoggedIn()}")
        debugInfo.appendLine("   • User ID: ${userManager.getCurrentUserId() ?: "NULL"}")
        debugInfo.appendLine("   • User Email: ${userManager.getCurrentUserEmail() ?: "NULL"}")

        // 3. Check SharedPreferences directly
        debugInfo.appendLine("3. SHARED PREFERENCES:")
        try {
            val prefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val allEntries = prefs.all
            allEntries.forEach { (key, value) ->
                debugInfo.appendLine("   • $key: $value")
            }
        } catch (e: Exception) {
            debugInfo.appendLine("   • ⚠️ Error reading SharedPreferences: ${e.message}")
        }

        // 4. Check network connectivity
        debugInfo.appendLine("4. NETWORK:")
        try {
            debugInfo.appendLine("   • Available: ${isNetworkAvailable()}")
        } catch (e: Exception) {
            debugInfo.appendLine("   • ⚠️ Error checking network: ${e.message}")
        }

        // 5. Test server connection without auth
        debugInfo.appendLine("5. BASIC SERVER CONNECTION:")
        try {
            val connectionResult = apiService.testConnection()
            when (connectionResult) {
                is ApiService.ApiResult.Success -> {
                    debugInfo.appendLine("   • ✅ Server connection successful")
                    debugInfo.appendLine("   • Response: ${connectionResult.data}")
                }
                is ApiService.ApiResult.Error -> {
                    debugInfo.appendLine("   • ❌ Server connection failed: ${connectionResult.message}")
                    debugInfo.appendLine("   • Error code: ${connectionResult.code}")
                }
            }
        } catch (e: Exception) {
            debugInfo.appendLine("   • ⚠️ Exception during testConnection(): ${e.message}")
            Log.e("DEBUG", "testConnection() failed", e)
        }

        // 6. Check if user ID is available for auth
        val userId = userManager.getCurrentUserId()
        var creditsResult: ApiService.ApiResult<org.json.JSONObject>? = null
        debugInfo.appendLine("6. AUTHENTICATION READINESS:")
        if (!userId.isNullOrBlank()) {
            debugInfo.appendLine("   • ✅ User ID available: ${userId.take(8)}...")

            // 7. Test BuildConfig app secret
            debugInfo.appendLine("7. APP CONFIGURATION:")
            try {
                val appSecret = BuildConfig.APP_SECRET_KEY
                debugInfo.appendLine("   • App Secret: ${if (appSecret.isBlank()) "BLANK" else "CONFIGURED"}")
                debugInfo.appendLine("   • Secret length: ${appSecret.length}")
            } catch (e: Exception) {
                debugInfo.appendLine("   • ❌ BuildConfig error: ${e.message}")
            }

            // 8. Test secure authentication with server
            debugInfo.appendLine("8. SECURE AUTHENTICATION TEST:")
            try {
                creditsResult = apiService.getUserCredits()
                when (creditsResult) {
                    is ApiService.ApiResult.Success -> {
                        debugInfo.appendLine("   • ✅ Secure Authentication SUCCESS!")
                        debugInfo.appendLine("   • Credits data: ${creditsResult.data}")
                        val credits = creditsResult.data.optInt("available_credits", -1)
                        debugInfo.appendLine("   • Available credits: $credits")
                    }
                    is ApiService.ApiResult.Error -> {
                        debugInfo.appendLine("   • ❌ Secure Authentication FAILED")
                        debugInfo.appendLine("   • Error: ${creditsResult.message}")
                        debugInfo.appendLine("   • Error code: ${creditsResult.code}")
                        debugInfo.appendLine("   • Details: ${creditsResult.details ?: "None"}")
                    }
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   • ⚠️ Exception during getUserCredits(): ${e.message}")
                Log.e("DEBUG", "getUserCredits() failed", e)
            }

            // 9. Test the security endpoint directly
            debugInfo.appendLine("9. SECURITY ENDPOINT TEST:")
            try {
                val securityTest = apiService.testSecureAuth()
                when (securityTest) {
                    is ApiService.ApiResult.Success -> {
                        debugInfo.appendLine("   • ✅ Security endpoint SUCCESS")
                        debugInfo.appendLine("   • Response: ${securityTest.data}")
                    }
                    is ApiService.ApiResult.Error -> {
                        debugInfo.appendLine("   • ❌ Security endpoint FAILED: ${securityTest.message}")
                    }
                }
            } catch (e: Exception) {
                debugInfo.appendLine("   • ⚠️ Exception during testSecureAuth(): ${e.message}")
                Log.e("DEBUG", "testSecureAuth() failed", e)
            }

        } else {
            debugInfo.appendLine("   • ❌ No user ID available for authentication")
            debugInfo.appendLine("   • 🔍 Diagnosis: User needs to log in again")
        }

        // 10. Recommended actions
        debugInfo.appendLine("10. RECOMMENDED ACTION:")
        when {
            userId.isNullOrBlank() -> debugInfo.appendLine("   • 👉 Please log out and log in again")
            creditsResult is ApiService.ApiResult.Error -> {
                when (creditsResult.code) {
                    401 -> debugInfo.appendLine("   • 👉 Re-authenticate: Log out and log in")
                    403 -> debugInfo.appendLine("   • 👉 Check APP_SECRET_KEY configuration")
                    else -> debugInfo.appendLine("   • 👉 Check server status and network")
                }
            }
            else -> debugInfo.appendLine("   • ✅ Everything looks good!")
        }

        debugInfo.appendLine("=== END DEBUG ===")

        // Log and display in UI
        Log.d("DEBUG", debugInfo.toString())
        withContext(Dispatchers.Main) {
            binding.tvGeneratedResume.text = debugInfo.toString()
            binding.layoutDownloadButtons.visibility = View.GONE
        }
    }
}



    
    override fun onResume() {
        super.onResume()
        
        lifecycleScope.launch {
            Log.d("ResumeActivity", "User logged in: ${userManager.isUserLoggedIn()}")
            Log.d("ResumeActivity", "User ID: ${userManager.getCurrentUserId()}")
            
            // Add a small delay to ensure everything is initialized
            delay(1000)
            
            // Update credits display
            updateCreditDisplay()
            
            // Test server connection
            testApiConnection()
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
        
        // Add debug button
        binding.btnDebugAuth.setOnClickListener {
            binding.progressGenerate.visibility = View.VISIBLE
            binding.tvGeneratedResume.text = "Running comprehensive debug..."
            comprehensiveAuthDebug()
        }
    } // Fixed: Added missing closing brace for setupUI()

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
    
    /** ---------------- API Connection Test ---------------- **/
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
                        updateCreditDisplay()
                    }
                    is ApiService.ApiResult.Error -> {
                        // Check if it's a server wake-up issue
                        if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                            updateConnectionStatus("🔄 Server is waking up...", true)
                            showServerWakeupMessage()
                            
                            // Wait for server to wake up with fewer attempts
                            val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 12, delayBetweenAttempts = 10000L)
                            
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
                showError("Connection failed: ${e.message}")
            } finally {
                binding.progressConnection.visibility = View.GONE
                binding.btnRetryConnection.isEnabled = true
            }
        }
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun ensureUserAuthenticated(): Boolean {
        if (!userManager.isUserLoggedIn()) {
            Log.e("ResumeActivity", "User not logged in")
            withContext(Dispatchers.Main) {
                showError("Please log in to continue")
            }
            return false
        }
        
        val userId = userManager.getCurrentUserId()
        if (userId.isNullOrBlank()) {
            Log.e("ResumeActivity", "User ID is null or blank")
            withContext(Dispatchers.Main) {
                showError("Authentication error. Please log in again.")
            }
            return false
        }
        
        Log.d("ResumeActivity", "User authenticated: ${userId.take(8)}...")
        return true
    }

    private fun testDirectConnection() {
        lifecycleScope.launch {
            Log.d("DirectTest", "Testing direct connection without interceptors...")
            
            val simpleClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            
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
                    
                    val response = simpleClient.newCall(request).execute()
                    Log.d("DirectTest", "$endpoint → ${response.code}")
                    
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        Log.d("DirectTest", "✅ SUCCESS: $body")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ResumeGenerationActivity, "Server connected!", Toast.LENGTH_SHORT).show()
                        }
                        break
                    }
                } catch (e: Exception) {
                    Log.e("DirectTest", "$endpoint → ERROR: ${e.message}")
                }
            }
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
        Log.d("ResumeActivity", "Fetching user credits...")

        // Check authentication first
        if (!ensureAuthenticatedBeforeApiCall()) {
            return
        }

        when (val result = apiService.getUserCredits()) {
            is ApiService.ApiResult.Success -> {
                val credits = result.data.optInt("available_credits", 0)
                Log.d("ResumeActivity", "Credits retrieved: $credits")

                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: $credits"
                }
            }

            is ApiService.ApiResult.Error -> {
                Log.e("ResumeActivity", "Failed to fetch credits: ${result.message}")
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Error"
                    if (result.code == 401) {
                        showError("Authentication failed. Please log in again.")
                        // Force logout to clear invalid state
                        userManager.logout()
                        checkGenerateButtonState()
                    } else {
                        showError("Failed to load credits: ${result.message}")
                    }
                }
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

    private fun showServerWakeupMessage() {
        Toast.makeText(
            this, 
            "🔄 Server is waking up... This may take 30-60 seconds on first launch.", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun testAuthStepByStep() {
        lifecycleScope.launch {
            binding.tvGeneratedResume.text = "Testing authentication step by step..."
            
            val steps = StringBuilder()
            steps.appendLine("🔍 AUTHENTICATION STEP-BY-STEP TEST")
            steps.appendLine()
            
            // Step 1: Check local authentication
            steps.appendLine("1. LOCAL AUTHENTICATION")
            val userId = userManager.getCurrentUserId()
            if (!userId.isNullOrBlank()) {
                steps.appendLine("   ✅ User ID: ${userId.take(8)}...")
            } else {
                steps.appendLine("   ❌ No user ID - cannot proceed")
                updateUIWithResults(steps)
                return@launch
            }
            
            // Step 2: Test basic connection
            steps.appendLine("2. BASIC CONNECTION")
            val connection = apiService.testConnection()
            if (connection is ApiService.ApiResult.Success) {
                steps.appendLine("   ✅ Server is reachable")
            } else {
                steps.appendLine("   ❌ Server unreachable: ${(connection as ApiService.ApiResult.Error).message}")
                updateUIWithResults(steps)
                return@launch
            }
            
            // Step 3: Test secure authentication
            steps.appendLine("3. SECURE AUTHENTICATION")
            val credits = apiService.getUserCredits()
            when (credits) {
                is ApiService.ApiResult.Success -> {
                    steps.appendLine("   ✅ Authentication SUCCESS!")
                    val creditCount = credits.data.optInt("available_credits", 0)
                    steps.appendLine("   📊 Available credits: $creditCount")
                }
                is ApiService.ApiResult.Error -> {
                    steps.appendLine("   ❌ Authentication FAILED")
                    steps.appendLine("   💡 Error: ${credits.message} (Code: ${credits.code})")
                }
            }
            
            updateUIWithResults(steps)
        }
    }

    private fun updateUIWithResults(steps: StringBuilder) {
        lifecycleScope.launch(Dispatchers.Main) {
            binding.tvGeneratedResume.text = steps.toString()
            binding.layoutDownloadButtons.visibility = View.GONE
        }
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

    // Removed token-related debug methods since we're using UID-based auth now
    
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
} // End of class
