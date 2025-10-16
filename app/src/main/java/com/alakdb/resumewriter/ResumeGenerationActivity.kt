package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
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
        
        debugAuthState()
        registerFilePickers()
        setupUI()
        checkGenerateButtonState()
    }

    override fun onResume() {
    super.onResume()

    lifecycleScope.launch {
        // ✅ Use UserManager to check token instead of calling private ApiService method
        val userManager = UserManager(this@ResumeGenerationActivity)
        val token = userManager.getUserToken()
        val tokenValid = userManager.isTokenValid()
        
        Log.d("ResumeActivity", "Token exists: ${token != null}")
        Log.d("ResumeActivity", "Token valid: $tokenValid")
        Log.d("ResumeActivity", "User logged in: ${userManager.isUserLoggedIn()}")
        
        // Update credits display (this will test if auth is working)
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

    private fun testAuthentication() {
    lifecycleScope.launch {
        val debugInfo = apiService.debugAuthState()
        Log.d("AuthTest", debugInfo)
        
        // Test credits API
        val result = apiService.getUserCredits()
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("AuthTest", "✅ Credits API success!")
                val credits = result.data.optInt("credits", 0)
                binding.creditText.text = "Credits: $credits"
            }
            is ApiService.ApiResult.Error -> {
                Log.e("AuthTest", "❌ Credits API failed: ${result.message}")
                if (result.code == 401) {
                    showError("Authentication failed. Please log in again.")
                }
            }
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
                Log.d("ResumeActivity", "Testing server warm-up")
                val warmUpResult = apiService.warmUpServer()
                when (warmUpResult) {
                    is ApiService.ApiResult.Success -> {
                        Log.d("ResumeActivity", "Server warm-up successful")
                        val connectionResult = apiService.testConnection()
                        when (connectionResult) {
                            is ApiService.ApiResult.Success -> {
                                updateConnectionStatus("✅ API Connected", false)
                                updateCreditDisplay()
                            }
                            is ApiService.ApiResult.Error -> {
                                updateConnectionStatus("❌ API Connection Failed", true)
                                showError("API endpoints not responding")
                            }
                        }
                    }
                    is ApiService.ApiResult.Error -> {
                        updateConnectionStatus("🔄 Server is starting...", true)
                        showError("Server is waking up. This may take 30-60 seconds. Please wait...")
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
                    val credits = creditResult.data.optInt("credits", 0)
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
                        val credits = creditResult.data.optInt("credits", 0)
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
            val credits = result.data.optInt("credits", 0)
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
                    val credits = creditsResult.data.optInt("credits", 0)
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
    private fun testAuthHeader() {
    lifecycleScope.launch {
        val token = userManager.getUserToken()
        Log.d("AuthDebug", "Token: ${token?.take(10)}...")
        Log.d("AuthDebug", "Header will be: X-Auth-Token: Bearer ${token?.take(10)}...")
        
        val result = apiService.getUserCredits()
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("AuthDebug", "✅ SUCCESS! Credits: ${result.data}")
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

            // ✅ Wrap in coroutine
            lifecycleScope.launch {
                updateCreditDisplay()
            }
        }
        is ApiService.ApiResult.Error -> {
            Log.e("ResumeActivity", "Resume generation failed: ${result.message}")
            showError("Generation failed: ${result.message}")
        }
    }
}
}
