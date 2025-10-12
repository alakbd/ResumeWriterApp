package com.alakdb.resumewriter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiService = ApiService(this)
        creditManager = CreditManager(this)
        auth = FirebaseAuth.getInstance()

        registerFilePickers()
        setupUI()
        checkGenerateButtonState()
        testApiConnection()
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

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri ?: return showError("Please select both files")
        val jobDescUri = selectedJobDescUri ?: return showError("Please select both files")

        disableGenerateButton("Processing...")

        CoroutineScope(Dispatchers.Main).launch {
            val user = auth.currentUser ?: return@launch showErrorAndReset("User not logged in")

            when (val creditResult = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val available = creditResult.data.optInt("available_credits", 0)
                    if (available <= 0) return@launch showErrorAndReset("Insufficient credits. Please purchase more.")
                }
                is ApiService.ApiResult.Error -> return@launch showErrorAndReset("Failed to check credits: ${creditResult.message}")
            }

            when (val deduct = apiService.deductCredit(user.uid)) {
                is ApiService.ApiResult.Success -> {
                    when (val gen = apiService.generateResumeFromFiles(resumeUri, jobDescUri)) {
                        is ApiService.ApiResult.Success -> {
                            currentGeneratedResume = gen.data
                            displayGeneratedResume(gen.data)
                            showSuccess("Resume generated successfully!")
                            updateCreditDisplay()
                        }
                        is ApiService.ApiResult.Error -> showError("Generation failed: ${gen.message}")
                    }
                }
                is ApiService.ApiResult.Error -> showError("Credit deduction failed: ${deduct.message}")
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

        disableGenerateButton("Processing...")

        CoroutineScope(Dispatchers.Main).launch {
            val user = auth.currentUser ?: return@launch showErrorAndReset("User not logged in")

            when (val creditResult = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val available = creditResult.data.optInt("available_credits", 0)
                    if (available <= 0) return@launch showErrorAndReset("Insufficient credits. Please purchase more.")
                }
                is ApiService.ApiResult.Error -> return@launch showErrorAndReset("Failed to check credits: ${creditResult.message}")
            }

            when (val deduct = apiService.deductCredit(user.uid)) {
                is ApiService.ApiResult.Success -> {
                    when (val gen = apiService.generateResume(resumeText, jobDesc)) {
                        is ApiService.ApiResult.Success -> {
                            currentGeneratedResume = gen.data
                            displayGeneratedResume(gen.data)
                            showSuccess("Resume generated successfully!")
                            updateCreditDisplay()
                        }
                        is ApiService.ApiResult.Error -> showError("Generation failed: ${gen.message}")
                    }
                }
                is ApiService.ApiResult.Error -> showError("Credit deduction failed: ${deduct.message}")
            }

            resetGenerateButton()
        }
    }

    /** ---------------- Display & Download ---------------- **/
    private fun displayGeneratedResume(resumeData: JSONObject) {
        try {
            binding.tvGeneratedResume.text = resumeData.getString("resume_text")
            binding.layoutDownloadButtons.visibility = android.view.View.VISIBLE

            resumeData.optInt("remaining_credits").takeIf { it > 0 }?.let {
                binding.tvCreditInfo.text = "Remaining credits: $it"
                binding.tvCreditInfo.visibility = android.view.View.VISIBLE
            }

        } catch (e: Exception) {
            showError("Error displaying resume: ${e.message}")
        }
    }

    private fun downloadFile(format: String) {
        val resumeData = currentGeneratedResume ?: return showError("No resume generated yet")

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val fileName = "generated_resume.${format.lowercase()}"
                val base64Key = "${format.lowercase()}_data"
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
        CoroutineScope(Dispatchers.Main).launch {
            when (val result = apiService.getUserCredits()) {
                is ApiService.ApiResult.Success -> {
                    val available = result.data.optInt("available_credits", 0)
                    val used = result.data.optInt("used_credits", 0)
                    binding.tvCreditInfo.text = "Credits: $available available, $used used"
                }
                is ApiService.ApiResult.Error -> {
                    binding.tvCreditInfo.text = "Credits: Unable to load"
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

    override fun onResume() {
        super.onResume()
        updateCreditDisplay()
    }
}
