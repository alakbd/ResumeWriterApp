package com.alakdb.resumewriter

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class ResumeGenerationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var creditManager: CreditManager
    private lateinit var auth: FirebaseAuth
    
    private var selectedResumeUri: Uri? = null
    private var selectedJobDescUri: Uri? = null
    private var currentGeneratedResume: JSONObject? = null
    private var genResult: String? = null

    // File picker launchers
    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>

    // File picker contracts
    private val resumeFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedResumeUri = it
            binding.tvResumeFile.text = getFileName(it) ?: "Resume file selected"
            binding.tvResumeFile.setTextColor(getColor(android.R.color.holo_green_dark))
            checkGenerateButtonState()
        }
    }

    private val jobDescFilePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            selectedJobDescUri = it
            binding.tvJobDescFile.text = getFileName(it) ?: "Job description file selected"
            binding.tvJobDescFile.setTextColor(getColor(android.R.color.holo_green_dark))
            checkGenerateButtonState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        apiService = ApiService(this)
        creditManager = CreditManager(this)
        auth = FirebaseAuth.getInstance()

        setupUI()
        checkGenerateButtonState()
        testApiConnection()
        registerFilePickers()
    }
        private fun registerFilePickers() {
        resumePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedResumeUri = it
                binding.tvResumeFile.text = getFileName(it) ?: "Resume file selected"
                binding.tvResumeFile.setTextColor(getColor(android.R.color.holo_green_dark))
                checkGenerateButtonState()
            }
        }

        jobDescPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedJobDescUri = it
                binding.tvJobDescFile.text = getFileName(it) ?: "Job description file selected"
                binding.tvJobDescFile.setTextColor(getColor(android.R.color.holo_green_dark))
                checkGenerateButtonState()
            }
        }
    }

    
    private fun setupUI() {
        // File selection buttons
        binding.btnSelectResume.setOnClickListener {
            openFilePicker(resumeFilePicker, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"))
        }

        binding.btnSelectJobDesc.setOnClickListener {
            openFilePicker(jobDescFilePicker, arrayOf("application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"))
        }

                // Clear selection buttons
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

        // Generate button
        binding.btnGenerateResume.setOnClickListener {
            if (selectedResumeUri != null && selectedJobDescUri != null) {
                generateResumeFromFiles()
            } else if (binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty()) {
                generateResumeFromText()
            } else {
                showError("Please provide both resume and job description")
            }
        }

        // Download buttons
        binding.btnDownloadDocx.setOnClickListener {
            downloadFile("docx")
        }

        binding.btnDownloadPdf.setOnClickListener {
            downloadFile("pdf")
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Refresh connection
        binding.btnRetryConnection.setOnClickListener {
            testApiConnection()
        }

        // Clear selection buttons
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
    }

    private fun openFilePicker(picker: ActivityResultLauncher<String>, mimeTypes: Array<String>) {
        val mimeType = if (mimeTypes.isNotEmpty()) mimeTypes[0] else "*/*"
        picker.launch(mimeType)
    }


    private fun checkGenerateButtonState() {
        val hasFiles = selectedResumeUri != null && selectedJobDescUri != null
        val hasText = binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty()
        
        binding.btnGenerateResume.isEnabled = hasFiles || hasText
        
        if (hasFiles) {
            binding.btnGenerateResume.text = "Generate Resume from Files (1 Credit)"
        } else if (hasText) {
            binding.btnGenerateResume.text = "Generate Resume from Text (1 Credit)"
        } else {
            binding.btnGenerateResume.text = "Generate Resume"
        }
    }

    private fun testApiConnection() {
        binding.layoutConnectionStatus.visibility = android.view.View.VISIBLE
        binding.tvConnectionStatus.text = "Testing connection..."
        binding.progressConnection.visibility = android.view.View.VISIBLE
        binding.btnRetryConnection.isEnabled = false

        CoroutineScope(Dispatchers.Main).launch {
            when (val result = apiService.testConnection()) {
                is ApiService.ApiResult.Success -> {
                    binding.tvConnectionStatus.text = "✅ API Connected Successfully"
                    binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                    binding.progressConnection.visibility = android.view.View.GONE
                    binding.btnRetryConnection.isEnabled = true
                    showSuccess("API connection successful")
                }
                is ApiService.ApiResult.Error -> {
                    binding.tvConnectionStatus.text = "❌ Connection Failed: ${result.message}"
                    binding.tvConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                    binding.progressConnection.visibility = android.view.View.GONE
                    binding.btnRetryConnection.isEnabled = true
                    showError("API connection failed: ${result.message}")
                }
            }
        }
    }

    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri
        val jobDescUri = selectedJobDescUri

        if (resumeUri == null || jobDescUri == null) {
            showError("Please select both files")
            return
        }

        binding.btnGenerateResume.isEnabled = false
        binding.btnGenerateResume.text = "Processing..."
        binding.progressGenerate.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val user = auth.currentUser
            if (user == null) {
                showError("User not logged in")
                resetGenerateButton()
                return@launch
            }

            // Step 1: Check credits first
            when (val creditResult = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val availableCredits = creditResult.data.getInt("available_credits")
                    if (availableCredits <= 0) {
                        showError("Insufficient credits. Please purchase more.")
                        resetGenerateButton()
                        return@launch
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showError("Failed to check credits: ${creditResult.message}")
                    resetGenerateButton()
                    return@launch
                }
            }

            // Step 2: Deduct credit
            when (val deductResult = apiService.deductCredit(user.uid)) {
                is ApiService.ApiResult.Success -> {
                    // Step 3: Generate resume
                    when (val genResult = apiService.generateResumeFromFiles(resumeUri, jobDescUri)) {
                        is ApiService.ApiResult.Success -> {
                            currentGeneratedResume = genResult.data
                            displayGeneratedResume(genResult.data)
                            showSuccess("Resume generated successfully!")
                            
                            // Update credit display
                            updateCreditDisplay()
                        }
                        is ApiService.ApiResult.Error -> {
                            showError("Generation failed: ${genResult.message}")
                            // Note: Credit was already deducted, this is intentional
                        }
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showError("Credit deduction failed: ${deductResult.message}")
                }
            }
            
            resetGenerateButton()
        }
    }

    private fun generateResumeFromText() {
        val resumeText = binding.etResumeText.text.toString().trim()
        val jobDesc = binding.etJobDescription.text.toString().trim()

        if (resumeText.isEmpty() || jobDesc.isEmpty()) {
            showError("Please enter both resume text and job description")
            return
        }

        binding.btnGenerateResume.isEnabled = false
        binding.btnGenerateResume.text = "Processing..."
        binding.progressGenerate.visibility = android.view.View.VISIBLE

        CoroutineScope(Dispatchers.Main).launch {
            val user = auth.currentUser
            if (user == null) {
                showError("User not logged in")
                resetGenerateButton()
                return@launch
            }

            // Step 1: Check credits first
            when (val creditResult = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val availableCredits = creditResult.data.getInt("available_credits")
                    if (availableCredits <= 0) {
                        showError("Insufficient credits. Please purchase more.")
                        resetGenerateButton()
                        return@launch
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showError("Failed to check credits: ${creditResult.message}")
                    resetGenerateButton()
                    return@launch
                }
            }

            // Step 2: Deduct credit
            when (val deductResult = apiService.deductCredit(user.uid)) {
                is ApiService.ApiResult.Success -> {
                    // Step 3: Generate resume
                    when (val genResult = apiService.generateResume(resumeText, jobDesc)) {
                        is ApiService.ApiResult.Success -> {
                            currentGeneratedResume = genResult.data
                            displayGeneratedResume(genResult.data)
                            showSuccess("Resume generated successfully!")
                            
                            // Update credit display
                            updateCreditDisplay()
                        }
                        is ApiService.ApiResult.Error -> {
                            showError("Generation failed: ${genResult.message}")
                            // Note: Credit was already deducted, this is intentional
                        }
                    }
                }
                is ApiService.ApiResult.Error -> {
                    showError("Credit deduction failed: ${genResult.message}")
                }
            }
            
            resetGenerateButton()
        }
    }

    private fun displayGeneratedResume(resumeData: JSONObject) {
        try {
            val resumeText = resumeData.getString("resume_text")
            binding.tvGeneratedResume.text = resumeText
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
        val resumeData = currentGeneratedResume ?: run {
            showError("No resume generated yet")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                when (format) {
                    "docx" -> {
                        val base64Data = resumeData.getString("docx_data")
                        val fileData = apiService.decodeBase64File(base64Data)
                        val file = apiService.saveFileToStorage(fileData, "generated_resume.docx")
                        showDownloadSuccess(file, "DOCX")
                    }
                    "pdf" -> {
                        val base64Data = resumeData.getString("pdf_data")
                        val fileData = apiService.decodeBase64File(base64Data)
                        val file = apiService.saveFileToStorage(fileData, "generated_resume.pdf")
                        showDownloadSuccess(file, "PDF")
                    }
                }
            } catch (e: Exception) {
                showError("Download failed: ${e.message}")
            }
        }
    }

    private fun showDownloadSuccess(file: File, format: String) {
        // Create share intent
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

        // Show success message with option to share
        Toast.makeText(this, "$format file saved successfully!", Toast.LENGTH_LONG).show()
        
        // Optionally start share dialog
        startActivity(Intent.createChooser(shareIntent, "Share Resume"))
    }

    private fun updateCreditDisplay() {
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val available = result.data.getInt("available_credits")
                    val used = result.data.getInt("used_credits")
                    binding.tvCreditInfo.text = "Credits: $available available, $used used"
                }
                is ApiService.ApiResult.Error -> {
                    binding.tvCreditInfo.text = "Credits: Unable to load"
                }
            }
        }
    }

    private fun resetGenerateButton() {
        binding.btnGenerateResume.isEnabled = true
        binding.btnGenerateResume.text = "Generate Resume"
        binding.progressGenerate.visibility = android.view.View.GONE
        checkGenerateButtonState()
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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

    override fun onResume() {
        super.onResume()
        updateCreditDisplay()
    }
}
