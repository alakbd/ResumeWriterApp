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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ResumeGenerationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var auth: FirebaseAuth

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

        apiService = ApiService(this)
        auth = FirebaseAuth.getInstance()

        registerFilePickers()
        setupUI()
        checkGenerateButtonState()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            delay(1000) // prevent ANR
            Log.d("ResumeActivity", "onResume: Updating credits and warming up server")

        // First, update the user's credits
        updateCreditDisplay()

        // Then, warm up the server and handle result safely
        val warmUpResult = apiService.warmUpServer()
        when (warmUpResult) {
            is ApiService.ApiResult.Success<*> -> {
                Log.d("ResumeActivity", "Server warm-up successful")
            }
            is ApiService.ApiResult.Error -> {
                Log.e("ResumeActivity", "Server warm-up failed: ${warmUpResult.message}")
                showError("Server warm-up failed: ${warmUpResult.message}")
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
        Log.d("ResumeActivity", "Selected file: $name")
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
                binding.tvConnectionStatus.text = "Checking server status..."
                val warmUpResult = apiService.warmUpServer()
                when (warmUpResult) {
                    is ApiService.ApiResult.Success -> {
                        Log.d("ResumeActivity", "Server warm-up success")
                        binding.tvConnectionStatus.text = "Testing API endpoints..."
                        val connectionResult = apiService.testConnection()
                        when (connectionResult) {
                            is ApiService.ApiResult.Success -> {
                                Log.d("ResumeActivity", "API connection successful")
                                updateConnectionStatus("✅ API Connected", false)
                                updateCreditDisplay()
                            }
                            is ApiService.ApiResult.Error -> {
                                Log.e("ResumeActivity", "API endpoints not responding: ${connectionResult.message}")
                                updateConnectionStatus("❌ API Connection Failed", true)
                                showError("API endpoints not responding")
                            }
                        }
                    }
                    is ApiService.ApiResult.Error -> {
                        Log.w("ResumeActivity", "Server is starting: ${warmUpResult.message}")
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
        Log.d("ResumeActivity", "Generating resume from files")

        lifecycleScope.launch {
            try {
                val creditResult = apiService.getUserCredits()
                Log.d("ResumeActivity", "Credit check result: $creditResult")
                if (creditResult is ApiService.ApiResult.Success) {
                    val credits = creditResult.data.optInt("credits", 0)
                    if (credits <= 0) {
                        showErrorAndReset("Insufficient credits. Please purchase more.")
                        return@launch
                    }
                    val genResult = retryApiCall {
                        apiService.generateResumeFromFiles(resumeUri, jobDescUri)
                    }
                    handleGenerationResult(genResult)
                } else if (creditResult is ApiService.ApiResult.Error) {
                    Log.e("ResumeActivity", "Credit check failed: ${creditResult.message}")
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Generation failed", e)
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
        Log.d("ResumeActivity", "Generating resume from text")

        lifecycleScope.launch {
            val user = auth.currentUser ?: return@launch showErrorAndReset("User not logged in")
            val creditResult = retryApiCall { apiService.getUserCredits() }
            Log.d("ResumeActivity", "Credit check result: $creditResult")

            when (creditResult) {
                is ApiService.ApiResult.Success -> {
                    try {
                        val credits = creditResult.data.optInt("credits", 0)
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }

                        val genResult = retryApiCall { apiService.generateResume(resumeText, jobDesc) }
                        handleGenerationResult(genResult)

                    } catch (e: Exception) {
                        Log.e("ResumeActivity", "Error checking credits", e)
                        showErrorAndReset("Error checking credits: ${e.message}")
                    }
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("ResumeActivity", "Credit check API error: ${creditResult.message}")
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")
                }
            }

            resetGenerateButton()
        }
    }

    /** ---------------- Retry Helper ---------------- **/
    private suspend fun <T> retryApiCall(
        maxRetries: Int = 2,
        initialDelay: Long = 1000L,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        var lastResult: ApiService.ApiResult<T>? = null
        repeat(maxRetries) { attempt ->
            val result = block()
            Log.d("RetryApi", "Attempt ${attempt + 1}: $result")
            if (result is ApiService.ApiResult.Success) return result
            lastResult = result
            if (attempt < maxRetries - 1) {
                val delayMs = initialDelay * (attempt + 1)
                Log.d("RetryApi", "Retrying in $delayMs ms")
                delay(delayMs)
            }
        }
        Log.e("RetryApi", "All retry attempts failed")
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
            Log.e("ResumeActivity", "Error displaying resume", e)
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
                Log.d("ResumeActivity", "Downloading $format file")
                val fileData = apiService.decodeBase64File(base64Data)
                val file = apiService.saveFileToStorage(fileData, fileName)
                showDownloadSuccess(file, format.uppercase())
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Download failed", e)
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
                binding.tvCreditInfo.text = "Loading credits..."
                binding.tvCreditInfo.visibility = View.VISIBLE

                val result = withContext(Dispatchers.IO) {
                    Log.d("ResumeActivity", "Fetching user credits from API")
                    apiService.getUserCredits()
                }

                withContext(Dispatchers.Main) {
                    when (result) {
                        is ApiService.ApiResult.Success -> {
                            Log.d("ResumeActivity", "Credits fetched successfully: ${result.data}")
                            val credits = result.data.optInt("credits", -1)
                            binding.tvCreditInfo.text = if (credits >= 0) "Available credits: $credits" else "Credits: Data unavailable"
                        }
                        is ApiService.ApiResult.Error -> {
                            Log.e("ResumeActivity", "Error fetching credits: ${result.message}")
                            binding.tvCreditInfo.text = "Credits: Unable to load"
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.w("ResumeActivity", "Credit fetch cancelled", e)
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Unexpected error while fetching credits", e)
                withContext(Dispatchers.Main) { binding.tvCreditInfo.text = "Credits: Error" }
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
            Log.e("ResumeActivity", "Failed to get file name", e)
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

    private fun handleGenerationResult(result: ApiService.ApiResult<JSONObject>) {
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("ResumeActivity", "Resume generation success: ${result.data}")
                currentGeneratedResume = result.data
                displayGeneratedResume(result.data)
                showSuccess("Resume generated successfully!")
                updateCreditDisplay()
            }
            is ApiService.ApiResult.Error -> {
                Log.e("ResumeActivity", "Resume generation failed: ${result.message}")
                showError("Generation failed: ${result.message}")
            }
        }
    }
}
