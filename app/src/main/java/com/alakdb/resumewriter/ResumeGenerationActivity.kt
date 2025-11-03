package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.tasks.await
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
import com.alakdb.resumewriter.databinding.ActivityResumeGenerationBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import android.text.method.ScrollingMovementMethod
import android.provider.MediaStore
import android.content.ContentValues
import android.os.Environment
import java.io.File


class ResumeGenerationActivity : AppCompatActivity() {

    private val creditManager: CreditManager by lazy { CreditManager(this) }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var userManager: UserManager

    private var selectedResumeUri: Uri? = null
    private var selectedJobDescUri: Uri? = null
    private var currentGeneratedResume: ApiService.GenerateResumeResponse? = null

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>
    private var lastApiCallTime: Long = 0

    private companion object {
        const val MIN_API_CALL_INTERVAL = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        apiService = ApiService(this)

        registerFilePickers()
        setupUI()
        checkEmailVerification()
        checkGenerateButtonState()

        lifecycleScope.launch {
            if (userManager.isUserLoggedIn()) {
                binding.creditText.text = "Credits: Loading..."
                updateCreditDisplay()
            } else {
                binding.creditText.text = "Credits: Please log in"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        creditManager.resetResumeCooldown()

        lifecycleScope.launch {
            if (ensureUserAuthenticated()) {
                updateCreditDisplay()
            }
            checkGenerateButtonState()
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
            showToast("Unsupported file type. Please select PDF, DOCX, or TXT", true)
        }
    }

    /** ---------------- Email Verification Enforcement ---------------- **/
    private fun isEmailVerified(): Boolean {
        val user = auth.currentUser
        return user != null && user.isEmailVerified
    }

    private fun showEmailVerificationDialog() {
        val user = auth.currentUser
        val builder = AlertDialog.Builder(this)
            .setTitle("Email Verification Required")
            .setMessage("You must verify your email address (${user?.email ?: "your email"}) before generating resumes. Please check your inbox for the verification link.")
            .setPositiveButton("Resend Verification") { _, _ ->
                resendEmailVerification()
            }
            .setNegativeButton("Check Email") { _, _ ->
                openEmailApp()
            }
            .setNeutralButton("Cancel", null)

        builder.show()
    }

    private fun resendEmailVerification() {
        val user = auth.currentUser
        user?.sendEmailVerification()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                showToast("Verification email sent to ${user.email}", false)
            } else {
                showToast("Failed to send verification email", true)
            }
        }
    }

    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_APP_EMAIL)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            showToast("No email app found", true)
        }
    }

    private fun checkEmailVerification() {
        val user = auth.currentUser
        if (user != null && !user.isEmailVerified) {
            showEmailVerificationDialog()
        }
    }

    /** ---------------- UI Setup ---------------- **/
    private fun setupUI() {
        binding.btnSelectResume.setOnClickListener { resumePicker.launch("application/*") }
        binding.btnSelectJobDesc.setOnClickListener { jobDescPicker.launch("application/*") }
        
        binding.etResumeText.addTextChangedListener(textWatcher)
        binding.etJobDescription.addTextChangedListener(textWatcher)
        
        binding.tvGeneratedResume.apply {
            movementMethod = ScrollingMovementMethod.getInstance()
            isVerticalScrollBarEnabled = true
            setTextIsSelectable(true)
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
            if (!userManager.isUserLoggedIn()) {
                showToast("Please log in to generate resumes", true)
                return@setOnClickListener
            }
            
            // Email verification check
            if (!isEmailVerified()) {
                showToast("Please verify your email before generating resumes", true)
                showEmailVerificationDialog()
                return@setOnClickListener
            }
            
            if (!canMakeApiCall()) {
                return@setOnClickListener
            }
            
            lastApiCallTime = System.currentTimeMillis()
            
            when {
                selectedResumeUri != null && selectedJobDescUri != null -> generateResumeFromFiles()
                binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty() -> generateResumeFromText()
                else -> showToast("Please provide both resume and job description", true)
            }
        }
        
        binding.btnDownloadDocx.setOnClickListener { downloadFile("docx") }
        binding.btnDownloadPdf.setOnClickListener { downloadFile("pdf") }
        binding.btnBack.setOnClickListener { finish() }

           binding.btnRetryConnection.setOnClickListener { 
                testApiConnection() 
            }
    }

    private val textWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            checkGenerateButtonState()
        }
    }

    private fun testApiConnection() {
    binding.layoutConnectionStatus.visibility = View.VISIBLE
    binding.tvConnectionStatus.text = "Testing connection..."
    binding.progressConnection.visibility = View.VISIBLE
    binding.btnRetryConnection.isEnabled = false

    lifecycleScope.launch {
        try {
            if (!apiService.isNetworkAvailable()) {
                updateConnectionStatus("❌ No internet connection", true)
                binding.progressConnection.visibility = View.GONE
                binding.btnRetryConnection.isEnabled = true
                showToast("Please check your internet connection", true)
                return@launch
            }

            Log.d("ResumeActivity", "Testing API connection...")
            
            val connectionResult = apiService.testConnection()
            
            when (connectionResult) {
                is ApiService.ApiResult.Success -> {
                    updateConnectionStatus("✅ API Connected", false)
                    // Add this line to update credits after successful connection
                    lifecycleScope.launch { updateCreditDisplay() }
                    showToast("Connection test successful!", false)
                }
                is ApiService.ApiResult.Error -> {
                    updateConnectionStatus("❌ API Connection Failed", true)
                    showToast("API error: ${connectionResult.message}", true)
                }
            }
        } catch (e: Exception) {
            updateConnectionStatus("❌ Connection Error", true)
            Log.e("ResumeActivity", "Connection test failed", e)
            showToast("Connection failed: ${e.message}", true)
        } finally {
            binding.progressConnection.visibility = View.GONE
            binding.btnRetryConnection.isEnabled = true
        }
    }
}

private fun updateConnectionStatus(message: String, isError: Boolean = false) {
    binding.tvConnectionStatus.text = message
    binding.tvConnectionStatus.setTextColor(
        if (isError) getColor(android.R.color.holo_red_dark)
        else getColor(android.R.color.holo_green_dark)
    )
}

    
    private fun checkGenerateButtonState() {
        val hasFiles = selectedResumeUri != null && selectedJobDescUri != null
        val hasText = binding.etResumeText.text.toString().isNotEmpty() && 
                      binding.etJobDescription.text.toString().isNotEmpty()
        val isLoggedIn = userManager.isUserLoggedIn()
        val isEmailVerified = isEmailVerified()

        val shouldEnable = (hasFiles || hasText) && isLoggedIn && isEmailVerified
        
        binding.btnGenerateResume.isEnabled = shouldEnable
        
        binding.btnGenerateResume.text = when {
            !isLoggedIn -> "Please Log In"
            !isEmailVerified -> "Verify Email First"
            hasFiles -> "Generate Resume from Files (1 Credit)"
            hasText -> "Generate Resume from Text (1 Credit)"
            else -> "Generate Resume"
        }
    }

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri ?: return showToast("Please select resume file", true)
        val jobDescUri = selectedJobDescUri ?: return showToast("Please select job description file", true)

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                if (!ensureUserAuthenticated()) {
                    resetGenerateButton()
                    return@launch
                }

                val creditResult = safeApiCallWithResult<ApiService.UserCreditsResponse>("getUserCredits") { 
                    apiService.getUserCredits() 
                }

                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.available_credits
                        if (credits <= 0) {
                            showToastAndReset("Insufficient credits. Please purchase more.", true)
                            return@launch
                        }

                        val genResult = safeApiCallWithResult<ApiService.GenerateResumeResponse>("generateResumeFromFiles") { 
                            apiService.generateResumeFromFiles(resumeUri, jobDescUri) 
                        }
                        handleGenerationResult(genResult)
                    }
                    is ApiService.ApiResult.Error -> {
                        showToastAndReset("Failed to check credits: ${creditResult.message}", true)
                        if (creditResult.code == 401) {
                            userManager.logout()
                            checkGenerateButtonState()
                        }
                    }
                }
            } catch (e: Exception) {
                showToastAndReset("Generation failed: ${e.message}", true)
            } finally {
                resetGenerateButton()
            }
        }
    }

    private fun generateResumeFromText() {
        val resumeText = binding.etResumeText.text.toString().trim()
        val jobDesc = binding.etJobDescription.text.toString().trim()

        if (resumeText.isEmpty() || jobDesc.isEmpty()) {
            showToast("Please enter both resume text and job description", true)
            return
        }

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                if (!ensureUserAuthenticated()) {
                    resetGenerateButton()
                    return@launch
                }

                val creditResult = safeApiCallWithResult<ApiService.UserCreditsResponse>("getUserCredits") { 
                    apiService.getUserCredits() 
                }

                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.available_credits
                        if (credits <= 0) {
                            showToastAndReset("Insufficient credits. Please purchase more.", true)
                            return@launch
                        }

                        val genResult = safeApiCallWithResult<ApiService.GenerateResumeResponse>("generateResumeFromText") { 
                            apiService.generateResumeFromText(resumeText, jobDesc) 
                        }
                        handleGenerationResult(genResult)
                    }
                    is ApiService.ApiResult.Error -> {
                        showToastAndReset("Failed to check credits: ${creditResult.message}", true)
                        if (creditResult.code == 401) {
                            userManager.logout()
                            checkGenerateButtonState()
                        }
                    }
                }
            } catch (e: Exception) {
                showToastAndReset("Generation failed: ${e.message}", true)
            } finally {
                resetGenerateButton()
            }
        }
    }

    /** ---------------- Display & Download ---------------- **/
    private fun displayGeneratedResume(resumeData: ApiService.GenerateResumeResponse) {
        try {
            binding.tvGeneratedResume.text = resumeData.resume_text
            binding.layoutDownloadButtons.visibility = View.VISIBLE

            val remaining = resumeData.remaining_credits
            binding.tvCreditInfo.text = "Remaining credits: $remaining"
            binding.tvCreditInfo.visibility = View.VISIBLE
            binding.creditText.text = "Credits: $remaining"
            
        } catch (e: Exception) {
            showToast("Error displaying resume: ${e.message}", true)
        }
    }

    private fun downloadFile(format: String) {
        val resumeData = currentGeneratedResume ?: return showToast("No resume generated yet", true)

        lifecycleScope.launch {
            try {
                binding.progressGenerate.visibility = View.VISIBLE
                binding.btnDownloadDocx.isEnabled = false
                binding.btnDownloadPdf.isEnabled = false

                val url = when (format.lowercase()) {
                    "docx" -> resumeData.docx_url
                    "pdf" -> resumeData.pdf_url
                    else -> return@launch showToast("Unsupported format: $format", true)
                }

                if (url.isBlank()) {
                    showToast("Download URL not available for $format", true)
                    return@launch
                }

                val downloadResult = apiService.downloadFile(url)

                when (downloadResult) {
                    is ApiService.ApiResult.Success -> {
                        val fileData = downloadResult.data
                        val fileName = "SkillSync_Resume_${resumeData.generation_id ?: System.currentTimeMillis()}.$format"

                        val resolver = contentResolver
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                when (format.lowercase()) {
                                    "pdf" -> "application/pdf"
                                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                    else -> "application/octet-stream"
                                }
                            )
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }

                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        if (uri == null) {
                            showToast("Failed to create file in Downloads", true)
                            return@launch
                        }

                        resolver.openOutputStream(uri)?.use { output ->
                            output.write(fileData)
                            output.flush()
                        }

                        showToast("${format.uppercase()} file saved to Downloads!", false)
                        showDownloadSuccess(null, format.uppercase(), uri)
                    }
                    is ApiService.ApiResult.Error -> {
                        showToast("Download failed: ${downloadResult.message}", true)
                    }
                }
            } catch (e: Exception) {
                showToast("Download failed: ${e.message}", true)
            } finally {
                binding.progressGenerate.visibility = View.GONE
                binding.btnDownloadDocx.isEnabled = true
                binding.btnDownloadPdf.isEnabled = true
            }
        }
    }

    private fun showDownloadSuccess(file: File?, format: String, uri: Uri? = null) {
    val message = "✅ Resume saved to Downloads folder as SkillSync_Resume.$format"
    showToast(message, false)

    val shareUri = uri ?: return // Simplified - just use the uri we already have

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = when (format.uppercase()) {
            "DOCX" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "PDF" -> "application/pdf"
            else -> "*/*"
        }
        putExtra(Intent.EXTRA_STREAM, shareUri)
        putExtra(Intent.EXTRA_SUBJECT, "SkillSync_Resume")
        putExtra(Intent.EXTRA_TEXT, "Here's your enhanced resume generated by Resume Boost CV")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    lifecycleScope.launch {
        delay(500)
        startActivity(Intent.createChooser(shareIntent, "Share SkillSync_Resume"))
    }
}

    /** ---------------- Generation Result Handler ---------------- **/
    private fun <T> handleGenerationResult(result: ApiService.ApiResult<T>) {
        when (result) {
            is ApiService.ApiResult.Success -> {
                when (val data = result.data) {
                    is ApiService.GenerateResumeResponse -> {
                        currentGeneratedResume = data
                        displayGeneratedResume(data)
                        showToast("Resume generated successfully!", false)

                        val remaining = data.remaining_credits
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                binding.creditText.text = "Credits: $remaining"
                            }
                        }
                    }
                    else -> {
                        showToast("Unexpected response from server", true)
                    }
                }
            }
            is ApiService.ApiResult.Error -> {
                showToast("Generation failed: ${result.message}", true)
                lifecycleScope.launch {
                    delay(2000L)
                    updateCreditDisplay()
                }
            }
        }
    }

    /** ---------------- Helper Methods ---------------- **/
    private suspend fun <T> safeApiCallWithResult(
        operation: String,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        return try {
            block()
        } catch (e: Exception) {
            ApiService.ApiResult.Error("Service temporarily unavailable. Please try again later.", 503)
        }
    }

    private suspend fun ensureUserAuthenticated(): Boolean {
        return try {
            userManager.emergencySyncWithFirebase()
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = auth.currentUser
            
            if (isLoggedIn && firebaseUser != null) {
                true
            } else {
                withContext(Dispatchers.Main) {
                    showToast("Please log in to continue", true)
                    binding.creditText.text = "Credits: Please log in"
                }
                false
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showToast("Authentication system error", true)
                binding.creditText.text = "Credits: Error"
            }
            false
        }
    }

    private suspend fun updateCreditDisplay() {
        if (!canMakeApiCall()) {
            return
        }
        
        lastApiCallTime = System.currentTimeMillis()
        
        try {
            if (!userManager.isUserLoggedIn()) {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                }
                return
            }

            if (!isEmailVerified()) {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Verify Email Required"
                }
                return
            }

            val result = apiService.getUserCredits()
            when (result) {
                is ApiService.ApiResult.Success -> {
                    withContext(Dispatchers.Main) {
                        val credits = result.data.available_credits
                        binding.creditText.text = "Credits: $credits"
                    }
                }
                is ApiService.ApiResult.Error -> {
                    if (result.code == 429) {
                        withContext(Dispatchers.Main) {
                            binding.creditText.text = "Credits: Rate Limited"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ResumeActivity", "Credit update failed", e)
        }
    }

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

    private fun showToastAndReset(msg: String, isError: Boolean) {
        showToast(msg, isError)
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

    private fun showToast(message: String, isError: Boolean) {
        Toast.makeText(this, message, if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        if (isError) {
            Log.e("ResumeActivity", message)
        }
    }

    private fun canMakeApiCall(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastApiCallTime < MIN_API_CALL_INTERVAL) {
            showToast("Please wait 5 seconds between requests", true)
            return false
        }
        return true
    }
}
