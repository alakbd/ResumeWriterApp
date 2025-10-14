package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.net.NetworkCapabilities
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.delay
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResumeGenerationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var auth: FirebaseAuth

    private var selectedResumeUri: Uri? = null
    private var selectedJobDescUri: Uri? = null
    private var currentGeneratedResume: JSONObject? = null

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiService = ApiService(this)
        auth = FirebaseAuth.getInstance()

        registerFilePickers()
        setupUI()
        checkGenerateButtonState()     
    }
    // Add these constants
    private companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 2000L
    }
    
    // Call these in onResume instead
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            // Delay the API calls to avoid ANR
            delay(1000)
            updateCreditDisplay()
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
        binding.btnSelectResume.setOnClickListener {
            resumePicker.launch("application/*")
        }

        binding.btnSelectJobDesc.setOnClickListener {
            jobDescPicker.launch("application/*")
        }

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
            // Step 1: Simple warm-up check
            binding.tvConnectionStatus.text = "Checking server status..."
            val warmUpResult = apiService.warmUpServer()
            
            when (warmUpResult) {
                is ApiService.ApiResult.Success -> {
                    // Server is ready, now test actual API
                    binding.tvConnectionStatus.text = "Testing API endpoints..."
                    val connectionResult = retryApiCall { apiService.testConnection() }
                    
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
            Log.e("ConnectionTest", "Connection test failed", e)
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

    private fun provideConnectionHelp(errorMessage: String?) {
        when {
            errorMessage?.contains("Failed to connect") == true -> {
                showError("Cannot reach server. Please check if the server is running.")
            }
            errorMessage?.contains("timeout") == true -> {
                showError("Connection timeout. Server might be overloaded. Please try again.")
            }
            errorMessage?.contains("SSL") == true -> {
                showError("SSL handshake failed. Please check your connection security.")
            }
            else -> {
                showError("Connection failed. Please check your internet and try again.")
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
            // Check credits first
            val creditResult = apiService.getUserCredits()
            if (creditResult is ApiService.ApiResult.Success) {
                val credits = creditResult.data.optInt("credits", 0)
                if (credits <= 0) {
                    showErrorAndReset("Insufficient credits. Please purchase more.")
                    return@launch
                }
                
                // Generate resume with single retry
                val genResult = retryApiCall { 
                    apiService.generateResumeFromFiles(resumeUri, jobDescUri) 
                }
                
                handleGenerationResult(genResult)
            } else {
                showErrorAndReset("Failed to check credits: ${(creditResult as ApiService.ApiResult.Error).message}")
            }
        } catch (e: Exception) {
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
            val user = auth.currentUser ?: return@launch showErrorAndReset("User not logged in")

            // First check if user has credits with retry logic
            val creditResult = retryApiCall { apiService.getUserCredits() }
            
            when (creditResult) {
                is ApiService.ApiResult.Success -> {
                    try {
                        val credits = creditResult.data.getInt("credits")
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }
                        
                        // User has credits, proceed with generation with retry
                        val genResult = retryApiCall { 
                            apiService.generateResume(resumeText, jobDesc) 
                        }
                        
                        when (genResult) {
                            is ApiService.ApiResult.Success -> {
                                currentGeneratedResume = genResult.data
                                displayGeneratedResume(genResult.data)
                                showSuccess("Resume generated successfully!")
                                updateCreditDisplay()
                            }
                            is ApiService.ApiResult.Error -> {
                                showError("Generation failed: ${genResult.message}")
                            }
                        }
                    } catch (e: Exception) {
                        showErrorAndReset("Error checking credits: ${e.message}")
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")
                }
            }

            resetGenerateButton()
        }
    }

    // Add this retry helper function
private suspend fun <T> retryApiCall(
    maxRetries: Int = 2, // Reduced from 3 to 2
    initialDelay: Long = 1000L, // Start with 1 second
    block: suspend () -> ApiService.ApiResult<T>
): ApiService.ApiResult<T> {
    var lastResult: ApiService.ApiResult<T>? = null
    
    repeat(maxRetries) { attempt ->
        val result = block()
        
        if (result is ApiService.ApiResult.Success) {
            return result
        }
        
        lastResult = result
        
        // Exponential backoff
        if (attempt < maxRetries - 1) {
            val delay = initialDelay * (attempt + 1)
            Log.d("RetryApi", "Retry ${attempt + 1}/$maxRetries in ${delay}ms")
            delay(delay)
        }
    }
    
    return lastResult ?: ApiService.ApiResult.Error("All retry attempts failed")
}



    
    /** ---------------- Display & Download ---------------- **/
    private fun displayGeneratedResume(resumeData: JSONObject) {
        try {
            binding.tvGeneratedResume.text = resumeData.getString("resume_text")
            binding.layoutDownloadButtons.visibility = android.view.View.VISIBLE

            // Show remaining credits if available
            if (resumeData.has("remaining_credits")) {
                val remaining = resumeData.getInt("remaining_credits")
                binding.tvCreditInfo.text = "Remaining credits: $remaining"
                binding.tvCreditInfo.visibility = android.view.View.VISIBLE
            }

        } catch (e: Exception) {
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
                
                val base64Data = resumeData.getString(base64Key)
                val fileData = apiService.decodeBase64File(base64Data)
                val file = apiService.saveFileToStorage(fileData, fileName)
                showDownloadSuccess(file, format.uppercase())

            } catch (e: Exception) {
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
private fun updateCreditDisplay() {
    lifecycleScope.launch {
        try {
            // Show a loading message if the view is visible
            binding.tvCreditInfo.text = "Loading credits..."
            binding.tvCreditInfo.visibility = View.VISIBLE

            // Call API safely
            val result = withContext(Dispatchers.IO) { apiService.getUserCredits() }

            withContext(Dispatchers.Main) {
                when (result) {
                    is ApiService.ApiResult.Success -> {
                        val credits = try {
                            result.data.optInt("credits", -1)
                        } catch (e: Exception) {
                            Log.e("ResumeGeneration", "JSON parsing error: ${e.message}")
                            -1
                        }

                        if (credits >= 0) {
                            binding.tvCreditInfo.text = "Available credits: $credits"
                        } else {
                            binding.tvCreditInfo.text = "Credits: Data unavailable"
                        }
                    }

                    is ApiService.ApiResult.Error -> {
                        binding.tvCreditInfo.text = "Credits: Unable to load"
                        Log.e(
                            "ResumeGeneration",
                            "Error loading credits: ${result.message}"
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            // Coroutine was canceled (e.g., Activity closed) — ignore
        } catch (e: Exception) {
            Log.e("ResumeGeneration", "Unexpected error while fetching credits", e)
            withContext(Dispatchers.Main) {
                binding.tvCreditInfo.text = "Credits: Error"
            }
        }
    }
}


    /** ---------------- Helpers ---------------- **/
    private fun disableGenerateButton(text: String) {
        binding.btnGenerateResume.isEnabled = false
        binding.btnGenerateResume.text = text
        binding.progressGenerate.visibility = android.view.View.VISIBLE
    }

    private fun resetGenerateButton() {
        binding.btnGenerateResume.isEnabled = true
        binding.btnGenerateResume.text = "Generate Resume"
        binding.progressGenerate.visibility = android.view.View.GONE
        checkGenerateButtonState()
    }

    private fun showServerStartingMessage() {
        binding.tvConnectionStatus.text = "🔄 Server is starting up..."
        binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
        showError("Server is waking up. This may take 30-60 seconds. Please wait...")
    }    

    private fun updateConnectionStatus(message: String, isError: Boolean = false) {
    binding.tvConnectionStatus.text = message
    binding.tvConnectionStatus.setTextColor(
        if (isError) getColor(android.R.color.holo_red_dark)
        else getColor(android.R.color.holo_green_dark)
        )
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
            null
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
        Log.e("ResumeGeneration", message)
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, "✅ $message", Toast.LENGTH_SHORT).show()
    }

    private fun handleGenerationResult(result: ApiService.ApiResult<JSONObject>) {
    when (result) {
        is ApiService.ApiResult.Success -> {
            currentGeneratedResume = result.data
            displayGeneratedResume(result.data)
            showSuccess("Resume generated successfully!")
            updateCreditDisplay()
        }
        is ApiService.ApiResult.Error -> {
            showError("Generation failed: ${result.message}")
        }
    }
}

    


}
