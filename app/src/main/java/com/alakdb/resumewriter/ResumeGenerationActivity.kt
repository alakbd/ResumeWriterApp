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

        // Debug calls
        checkEmailVerification()
        registerFilePickers()
        setupUI()
        checkGenerateButtonState()

        // Test connection safely
        lifecycleScope.launch {
            delay(1000) // Wait for initialization
            testApiConnection()
        }
    }

    private fun comprehensiveAuthDebug() {
        lifecycleScope.launch {
            Log.d("DEBUG", "=== COMPREHENSIVE AUTH DEBUG ===")
            
            // 1. Check Firebase Auth state
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            Log.d("DEBUG", "Firebase User: ${firebaseUser?.uid ?: "NULL"}")
            Log.d("DEBUG", "Firebase Email: ${firebaseUser?.email ?: "NULL"}")
            
            // 2. Check UserManager state
            Log.d("DEBUG", "UserManager logged in: ${userManager.isUserLoggedIn()}")
            Log.d("DEBUG", "UserManager user ID: ${userManager.getCurrentUserId()}")
            Log.d("DEBUG", "UserManager email: ${userManager.getCurrentUserEmail()}")
            
            // 3. Check network connectivity
            Log.d("DEBUG", "Network available: ${isNetworkAvailable()}")
            
            // 4. Test server connection without auth
            Log.d("DEBUG", "Testing server connection...")
            val connectionResult = apiService.testConnection()
            when (connectionResult) {
                is ApiService.ApiResult.Success -> {
                    Log.d("DEBUG", "✅ Server connection successful")
                    Log.d("DEBUG", "Response: ${connectionResult.data}")
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("DEBUG", "❌ Server connection failed: ${connectionResult.message}")
                }
            }
            
            // 5. Test secure authentication with server
            Log.d("DEBUG", "Testing secure authentication...")
            val userId = userManager.getCurrentUserId()
            if (!userId.isNullOrBlank()) {
                Log.d("DEBUG", "User ID available, testing API call...")
                val creditsResult = apiService.getUserCredits()
                when (creditsResult) {
                    is ApiService.ApiResult.Success -> {
                        Log.d("DEBUG", "✅ Secure Authentication SUCCESS!")
                        Log.d("DEBUG", "Credits data: ${creditsResult.data}")
                    }
                    is ApiService.ApiResult.Error -> {
                        Log.e("DEBUG", "❌ Secure Authentication FAILED: ${creditsResult.message}")
                        Log.e("DEBUG", "Error code: ${creditsResult.code}")
                    }
                }
            } else {
                Log.e("DEBUG", "❌ No user ID available for testing")
            }
            
            Log.d("DEBUG", "=== END DEBUG ===")
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
            runApiServiceDebug()
        }
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
                .setNeutralButton("Sign Out", DialogInterface.OnClickListener { _, _ ->
                    FirebaseAuth.getInstance().signOut()
                    userManager.clearUserToken()
                    finish()
                })

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
                            val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 5, delayBetweenAttempts = 3000L)
                            
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

    /** ---------------- Credit Display ---------------- **/
    private suspend fun updateCreditDisplay() {
        Log.d("ResumeActivity", "Fetching user credits...")

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
                    binding.creditText.text = "Credits: --"
                    if (result.code == 401) {
                        showError("Authentication failed. Please log in again.")
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
}
