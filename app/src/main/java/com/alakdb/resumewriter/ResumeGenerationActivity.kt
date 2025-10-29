package com.alakdb.resumewriter

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.tasks.await
import android.net.ConnectivityManager
import android.net.Network
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
    private var currentGeneratedResume: ApiService.GenerateResumeResponse? = null

    private lateinit var creditManager: CreditManager
    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>
    private var lastToastTime: Long = 0
    private val TOAST_COOLDOWN_MS = 3000L

    private companion object {
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 3000L
    }

    private val connectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d("NetworkState", "📡 Network available - revalidating auth state")
            lifecycleScope.launch {
                revalidateAuthState()
            }
        }
        
        override fun onLost(network: Network) {
            Log.d("NetworkState", "📡 Network lost")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResumeGenerationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager(this)
        apiService = ApiService(this)
        auth = FirebaseAuth.getInstance()
        creditManager = CreditManager(this)

        registerFilePickers()
        setupUI()
        checkEmailVerification()
        checkGenerateButtonState()
        testBasicApiCall()

        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 Initial auth setup...")
            
            val isLoggedIn = userManager.isUserLoggedIn()
            Log.d("ResumeActivity", "Initial auth state: $isLoggedIn")
            
            if (isLoggedIn) {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Loading..."
                }
                
                if (isNetworkAvailable()) {
                    updateCreditDisplay()
                } else {
                    val cachedCredits = userManager.getCachedCredits()
                    withContext(Dispatchers.Main) {
                        binding.creditText.text = "Credits: $cachedCredits (offline)"
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w("NetworkState", "Failed to unregister network callback: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        creditManager.resetResumeCooldown()

        lifecycleScope.launch {
            try {
                delay(1000L)
                val authValid = checkAuthenticationState()

                if (authValid) {
                    if (isNetworkAvailable()) {
                        updateCreditDisplay()
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.creditText.text = "Credits: Offline"
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    checkGenerateButtonState()
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "❌ onResume failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Error"
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
        
        binding.btnDebugAuth.setOnClickListener {
            //debugAuthFlow()
            testFileUpload()
            true
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

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
    val resumeUri = selectedResumeUri ?: return showError("Please select resume file")
    val jobDescUri = selectedJobDescUri ?: return showError("Please select job description file")

    disableGenerateButton("Processing...")

    lifecycleScope.launch {
        try {
            if (!ensureAuthenticatedBeforeApiCall()) {
                resetGenerateButton()
                return@launch
            }

            Log.d("ResumeActivity", "Checking user credits")
            val creditResult = safeApiCallWithResult<ApiService.UserCreditsResponse>("getUserCredits") { 
                apiService.getUserCredits() 
            }

            when (creditResult) {
                is ApiService.ApiResult.Success -> {
                    val credits = creditResult.data.available_credits
                    Log.d("ResumeActivity", "User has $credits credits")

                    if (credits <= 0) {
                        showErrorAndReset("Insufficient credits. Please purchase more.")
                        return@launch
                    }

                    Log.d("ResumeActivity", "Generating resume from files")
                    val genResult = safeApiCallWithResult<ApiService.GenerateResumeResponse>("generateResumeFromFiles") { 
                        apiService.generateResumeFromFiles(resumeUri, jobDescUri) 
                    }

                    handleGenerationResult(genResult)
                }

                is ApiService.ApiResult.Error -> {
                    Log.e("ResumeActivity", "Failed to get credits: ${creditResult.message}")
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")

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
            if (!ensureAuthenticatedBeforeApiCall()) {
                resetGenerateButton()
                return@launch
            }

            val creditResult = safeApiCallWithResult<ApiService.UserCreditsResponse>("getUserCredits") { 
                apiService.getUserCredits() 
            }

            when (creditResult) {
                is ApiService.ApiResult.Success -> {
                    val credits = creditResult.data.available_credits
                    Log.d("ResumeActivity", "User has $credits credits")

                    if (credits <= 0) {
                        showErrorAndReset("Insufficient credits. Please purchase more.")
                        return@launch
                    }

                    Log.d("ResumeActivity", "Generating resume from text input")
                    val genResult = safeApiCallWithResult<ApiService.GenerateResumeResponse>("generateResumeFromText") { 
                        apiService.generateResumeFromText(resumeText, jobDesc) 
                    }

                    handleGenerationResult(genResult)
                }

                is ApiService.ApiResult.Error -> {
                    Log.e("ResumeActivity", "Failed to check credits: ${creditResult.message}")
                    showErrorAndReset("Failed to check credits: ${creditResult.message}")

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
            Log.e("ResumeActivity", "Error displaying resume: ${e.message}", e)
            showError("Error displaying resume: ${e.message}")
        }
    }

    private fun downloadFile(format: String) {
        val resumeData = currentGeneratedResume ?: return showError("No resume generated yet")
        
        lifecycleScope.launch {
            try {
                val url = when (format.lowercase()) {
                    "docx" -> resumeData.docx_url
                    "pdf" -> resumeData.pdf_url
                    else -> return@launch showError("Unsupported format: $format")
                }

                if (url.isBlank()) {
                    showError("Download URL not available for $format")
                    return@launch
                }

                Log.d("Download", "Downloading $format from: $url")
                val downloadResult = apiService.downloadFile(url)

                when (downloadResult) {
                    is ApiService.ApiResult.Success -> {
                        val fileData = downloadResult.data
                        val fileName = "generated_resume_${resumeData.generation_id ?: "unknown"}.$format"
                        val file = apiService.saveFileToStorage(fileData, fileName)
                        showDownloadSuccess(file, format.uppercase())
                    }
                    is ApiService.ApiResult.Error -> {
                        showError("Download failed: ${downloadResult.message}")
                    }
                }
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

    /** ---------------- Generation Result Handler ---------------- **/
    private fun <T> handleGenerationResult(result: ApiService.ApiResult<T>) {
        when (result) {
            is ApiService.ApiResult.Success -> {
                when (val data = result.data) {
                    is ApiService.GenerateResumeResponse -> {
                        Log.d("ResumeActivity", "Resume generation success: ${data.message}")
                        currentGeneratedResume = data
                        displayGeneratedResume(data)
                        showSuccess("Resume generated successfully!")

                        val remaining = data.remaining_credits
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                binding.creditText.text = "Credits: $remaining"
                            }
                        }
                    }
                    else -> {
                        Log.e("ResumeActivity", "Unexpected response type: ${data?.let { it::class.java.simpleName } ?: "null"}")
                        showError("Unexpected response from server")
                    }
                }
            }
            is ApiService.ApiResult.Error -> {
                Log.e("ResumeActivity", "Resume generation failed: ${result.message}")
                showError("Generation failed: ${result.message}")
                
                lifecycleScope.launch {
                    updateCreditDisplay()
                }
            }
        }
    }

    /** ---------------- Helper Methods ---------------- **/
    private suspend fun <T> safeApiCallWithResult(
    operation: String,
    maxRetries: Int = 2,
    block: suspend () -> ApiService.ApiResult<T>
): ApiService.ApiResult<T> {
    var lastError: Exception? = null
    
    repeat(maxRetries) { attempt ->
        try {
            Log.d("SafeApiCall", "🔄 Attempt ${attempt + 1}/$maxRetries for $operation")
            
            if (!ensureAuthenticatedBeforeApiCall()) {
                Log.e("SafeApiCall", "❌ Authentication failed for $operation")
                return ApiService.ApiResult.Error("Authentication failed", 401)
            }
            
            val result = block()
            
            when (result) {
                is ApiService.ApiResult.Success -> {
                    Log.d("SafeApiCall", "✅ $operation succeeded on attempt ${attempt + 1}")
                    return result
                }
                is ApiService.ApiResult.Error -> {
                    Log.w("SafeApiCall", "⚠️ $operation failed on attempt ${attempt + 1}: ${result.message} (Code: ${result.code})")
                    
                    // If it's an auth error, don't retry
                    if (result.code == 401) {
                        return result
                    }
                    
                    // If it's a client error (4xx), don't retry
                    if (result.code in 400..499) {
                        return result
                    }
                }
            }
            
        } catch (e: Exception) {
            lastError = e
            Log.e("SafeApiCall", "💥 Exception in $operation attempt ${attempt + 1}: ${e.message}", e)
        }
        
        if (attempt < maxRetries - 1) {
            val delayTime = 1000L * (attempt + 1)
            Log.d("SafeApiCall", "⏳ Waiting $delayTime ms before retry...")
            delay(delayTime)
        }
    }
    
    val errorMessage = lastError?.message ?: "All retry attempts failed for $operation"
    Log.e("SafeApiCall", "❌ $errorMessage")
    return ApiService.ApiResult.Error(errorMessage, 0)
}

private fun testFileUpload() {
    val resumeUri = selectedResumeUri ?: return showError("No resume file selected")
    val jobDescUri = selectedJobDescUri ?: return showError("No job description file selected")

    lifecycleScope.launch {
        try {
            binding.tvGeneratedResume.text = "Testing file upload..."
            
            // Test file reading
            val resumeFileName = getFileName(resumeUri)
            val jobDescFileName = getFileName(jobDescUri)
            
            var debugInfo = "📁 File Info:\n"
            debugInfo += "• Resume: $resumeFileName\n"
            debugInfo += "• Job Desc: $jobDescFileName\n\n"
            
            // Test file content extraction
            debugInfo += "🔍 Testing file content extraction...\n"
            
            try {
                val resumeText = apiService.extractTextFromUploadFile(
                    object : UploadFile {
                        override fun readBytes(): ByteArray {
                            return contentResolver.openInputStream(resumeUri)?.readBytes() ?: byteArrayOf()
                        }
                        override fun getFilename(): String? = resumeFileName
                    }
                )
                val jobText = apiService.extractTextFromUploadFile(
                    object : UploadFile {
                        override fun readBytes(): ByteArray {
                            return contentResolver.openInputStream(jobDescUri)?.readBytes() ?: byteArrayOf()
                        }
                        override fun getFilename(): String? = jobDescFileName
                    }
                )
                
                debugInfo += "✅ File extraction successful!\n"
                debugInfo += "• Resume chars: ${resumeText.length}\n"
                debugInfo += "• Job desc chars: ${jobText.length}\n"
                debugInfo += "• Resume preview: ${resumeText.take(100)}...\n"
                debugInfo += "• Job preview: ${jobText.take(100)}...\n"
                
            } catch (e: Exception) {
                debugInfo += "❌ File extraction failed: ${e.message}\n"
            }
            
            // Test API connection
            debugInfo += "\n🌐 Testing API connection...\n"
            val healthResult = apiService.testConnection()
            when (healthResult) {
                is ApiService.ApiResult.Success -> {
                    debugInfo += "✅ API is reachable\n"
                }
                is ApiService.ApiResult.Error -> {
                    debugInfo += "❌ API unreachable: ${healthResult.message}\n"
                }
            }
            
            binding.tvGeneratedResume.text = debugInfo
            
        } catch (e: Exception) {
            binding.tvGeneratedResume.text = "💥 Test failed: ${e.message}"
        }
    }
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

    private suspend fun updateCreditDisplay() {
        try {
            delay(500L)
            
            if (!userManager.isUserLoggedIn()) {
                runOnUiThread {
                    binding.creditText.text = "Credits: Please log in"
                }
                return
            }

            Log.d("ResumeActivity", "🔄 Updating credit display...")
            
            val result = apiService.getUserCredits()
            when (result) {
                is ApiService.ApiResult.Success -> {
                    runOnUiThread {
                        try {
                            val credits = result.data.available_credits
                            binding.creditText.text = "Credits: $credits"
                            Log.d("ResumeActivity", "✅ Credits updated: $credits")
                        } catch (e: Exception) {
                            binding.creditText.text = "Credits: Error"
                            Log.e("ResumeActivity", "❌ Error parsing credits response", e)
                        }
                    }
                }
                is ApiService.ApiResult.Error -> {
                    Log.w("ResumeGeneration", "Failed to get credits: ${result.message} (Code: ${result.code})")
                    runOnUiThread {
                        when (result.code) {
                            401 -> {
                                binding.creditText.text = "Credits: Auth Error"
                                showError("Authentication failed. Please log out and log in again.")
                                userManager.logout()
                            }
                            429 -> {
                                binding.creditText.text = "Credits: Rate Limited"
                                showError("Too many requests. Please wait a moment.")
                            }
                            else -> {
                                val cachedCredits = userManager.getCachedCredits()
                                binding.creditText.text = "Credits: $cachedCredits (cached)"
                                Log.w("ResumeActivity", "Using cached credits due to API error: ${result.message}")
                            }
                        }
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

    private suspend fun checkAuthenticationState(): Boolean {
        return try {
            userManager.emergencySyncWithFirebase()
            
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            
            Log.d("AuthCheck", "UserManager logged in: $isLoggedIn")
            Log.d("AuthCheck", "Firebase user: ${firebaseUser?.uid}")
            
            if (!isLoggedIn || firebaseUser == null) {
                withContext(Dispatchers.Main) {
                    showError("Please log in to continue")
                    binding.creditText.text = "Credits: Please log in"
                }
                return false
            }
            
            true
        } catch (e: Exception) {
            Log.e("AuthCheck", "Authentication check failed", e)
            withContext(Dispatchers.Main) {
                showError("Authentication error: ${e.message}")
                binding.creditText.text = "Credits: Auth Error"
            }
            false
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
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTime > TOAST_COOLDOWN_MS) {
            Toast.makeText(this, "❌ $message", Toast.LENGTH_LONG).show()
            lastToastTime = currentTime
        }
        Log.e("ResumeActivity", message)
    }

    private fun showSuccess(message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTime > TOAST_COOLDOWN_MS) {
            Toast.makeText(this, "✅ $message", Toast.LENGTH_LONG).show()
            lastToastTime = currentTime
        }
        Log.d("ResumeActivity", message)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ... Add any other missing helper methods as needed

    private suspend fun revalidateAuthState() {
        Log.d("AuthRevalidation", "🔄 Revalidating authentication state...")
        
        try {
            userManager.emergencySyncWithFirebase()
            
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            
            Log.d("AuthRevalidation", "After sync - Logged in: $isLoggedIn, Firebase: ${firebaseUser != null}")
            
            withContext(Dispatchers.Main) {
                if (isLoggedIn) {
                    binding.creditText.text = "Credits: Loading..."
                    lifecycleScope.launch {
                        updateCreditDisplay()
                    }
                    checkGenerateButtonState()
                    Log.d("AuthRevalidation", "✅ Auth revalidated - user is logged in")
                } else {
                    binding.creditText.text = "Credits: Please log in"
                    showError("Session expired. Please log in again.")
                    Log.w("AuthRevalidation", "❌ Auth revalidation failed - user needs to login")
                }
            }
        } catch (e: Exception) {
            Log.e("AuthRevalidation", "💥 Revalidation failed: ${e.message}")
        }
    }

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

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    private suspend fun ensureUserAuthenticated(): Boolean {
        return try {
            val userManagerLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            
            Log.d("AUTH_CHECK", "UserManager: $userManagerLoggedIn, Firebase: ${firebaseUser != null}")
            
            if (userManagerLoggedIn) {
                Log.d("AUTH_CHECK", "✅ User properly authenticated")
                true
            } else {
                Log.e("AUTH_CHECK", "❌ User not authenticated")
                withContext(Dispatchers.Main) {
                    showError("Please log in to continue")
                    binding.creditText.text = "Credits: Please log in"
                }
                false
            }
            
        } catch (e: Exception) {
            Log.e("AUTH_CHECK", "💥 Authentication check failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                showError("Authentication system error")
                binding.creditText.text = "Credits: Error"
            }
            false
        }
    }

    private fun testBasicApiCall() {
        lifecycleScope.launch {
            try {
                binding.tvConnectionStatus.text = "Testing basic API call..."
                binding.progressConnection.visibility = View.VISIBLE
                
                Log.d("BasicTest", "🔄 Testing basic API call without authentication...")
                
                val result = apiService.testConnection()
                
                when (result) {
                    is ApiService.ApiResult.Success -> {
                        binding.tvConnectionStatus.text = "✅ Basic API works!"
                        Log.d("BasicTest", "✅ Basic API call SUCCESS: ${result.data}")
                        showMessage("Basic connectivity: ✅ WORKING")
                    }
                    is ApiService.ApiResult.Error -> {
                        binding.tvConnectionStatus.text = "❌ Basic API failed: ${result.message}"
                        Log.e("BasicTest", "❌ Basic API call FAILED: ${result.message}")
                        showMessage("Basic connectivity: ❌ FAILED - ${result.message}")
                    }
                }
            } catch (e: Exception) {
                binding.tvConnectionStatus.text = "💥 Test crashed: ${e.message}"
                Log.e("BasicTest", "💥 Test crashed", e)
                showMessage("Test crashed: ${e.message}")
            } finally {
                binding.progressConnection.visibility = View.GONE
            }
        }
    }

    private fun testApiConnection() {
        binding.layoutConnectionStatus.visibility = View.VISIBLE
        binding.tvConnectionStatus.text = "Testing connection..."
        binding.progressConnection.visibility = View.VISIBLE
        binding.btnRetryConnection.isEnabled = false

        lifecycleScope.launch {
            safeApiCall {
                if (!isNetworkAvailable()) {
                    updateConnectionStatus("❌ No internet connection", true)
                    binding.progressConnection.visibility = View.GONE
                    binding.btnRetryConnection.isEnabled = true
                    showError("Please check your internet connection")
                    return@safeApiCall
                }

                try {
                    Log.d("ResumeActivity", "Testing API connection...")
                    
                    val connectionResult = apiService.testConnection()
                    
                    when (connectionResult) {
                        is ApiService.ApiResult.Success -> {
                            updateConnectionStatus("✅ API Connected", false)
                            updateCreditDisplay()
                        }
                        is ApiService.ApiResult.Error -> {
                            if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                                updateConnectionStatus("🔄 Server is waking up...", true)
                                showServerWakeupMessage()
                                
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

    private fun debugAuthFlow() {
        lifecycleScope.launch {
            try {
                binding.tvGeneratedResume.text = "🔐 Debugging Authentication Flow..."
                
                val debugInfo = StringBuilder()
                debugInfo.appendLine("🔐 AUTHENTICATION DEBUG")
                debugInfo.appendLine("=".repeat(50))
                
                debugInfo.appendLine("1. USER MANAGER STATE:")
                debugInfo.appendLine("   • isUserLoggedIn(): ${userManager.isUserLoggedIn()}")
                debugInfo.appendLine("   • getCurrentUserId(): ${userManager.getCurrentUserId()}")
                debugInfo.appendLine("   • getCurrentUserEmail(): ${userManager.getCurrentUserEmail()}")
                
                val firebaseUser = FirebaseAuth.getInstance().currentUser
                debugInfo.appendLine("\n2. FIREBASE AUTH STATE:")
                debugInfo.appendLine("   • Current User: ${firebaseUser?.uid ?: "NULL"}")
                debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
                debugInfo.appendLine("   • Verified: ${firebaseUser?.isEmailVerified ?: false}")
                
                debugInfo.appendLine("\n3. BASIC API TEST (no auth):")
                val healthResult = apiService.testConnection()
                when (healthResult) {
                    is ApiService.ApiResult.Success -> {
                        debugInfo.appendLine("   • Health Endpoint: ✅ SUCCESS")
                    }
                    is ApiService.ApiResult.Error -> {
                        debugInfo.appendLine("   • Health Endpoint: ❌ FAILED - ${healthResult.message}")
                    }
                }
                
                debugInfo.appendLine("\n4. AUTHENTICATED API TEST:")
                if (userManager.isUserLoggedIn()) {
                    val creditsResult = apiService.getUserCredits()
                    when (creditsResult) {
                        is ApiService.ApiResult.Success -> {
                            debugInfo.appendLine("   • Credits Endpoint: ✅ SUCCESS")
                            debugInfo.appendLine("   • Credits: ${creditsResult.data.available_credits}")
                        }
                        is ApiService.ApiResult.Error -> {
                            debugInfo.appendLine("   • Credits Endpoint: ❌ FAILED")
                            debugInfo.appendLine("   • Error: ${creditsResult.message}")
                            debugInfo.appendLine("   • Code: ${creditsResult.code}")
                        }
                    }
                } else {
                    debugInfo.appendLine("   • Credits Endpoint: ❌ SKIPPED (not logged in)")
                }
                
                debugInfo.appendLine("=".repeat(50))
                binding.tvGeneratedResume.text = debugInfo.toString()
                
            } catch (e: Exception) {
                binding.tvGeneratedResume.text = "💥 Auth debug failed: ${e.message}"
            }
        }
    }
}
