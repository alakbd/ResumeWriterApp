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

    // Rate limiting protection
    private var lastApiCallTime: Long = 0
    private val MIN_API_CALL_INTERVAL = 2000L // 2 seconds between API calls

    private companion object {
        const val MAX_RETRIES = 2 // Reduced from 4 to avoid rate limits
        const val RETRY_DELAY_MS = 5000L // Increased delay between retries
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
        
        // Don't auto-test API on create to avoid rate limits
        // testBasicApiCall()

        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 Initial auth setup...")
            
            val isLoggedIn = userManager.isUserLoggedIn()
            Log.d("ResumeActivity", "Initial auth state: $isLoggedIn")
            
            if (isLoggedIn) {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Loading..."
                }
                
                if (apiService.isNetworkAvailable()) {
                    // Delay initial credit check to avoid rate limits
                    delay(1000L)
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
                delay(1000L) // Reduced delay
                val authValid = ensureUserAuthenticated()

                if (authValid) {
                    if (apiService.isNetworkAvailable()) {
                        // Don't auto-update credits on resume to avoid rate limits
                        // Only update if we don't have cached credits
                        val cachedCredits = userManager.getCachedCredits()
                        if (cachedCredits <= 0) {
                            updateCreditDisplay()
                        } else {
                            withContext(Dispatchers.Main) {
                                binding.creditText.text = "Credits: $cachedCredits"
                            }
                        }
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
            showToast("Unsupported file type. Please select PDF, DOCX, or TXT", true)
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
                showToast("Please log in to generate resumes", true)
                return@setOnClickListener
            }
            
            // Rate limiting protection
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastApiCallTime < MIN_API_CALL_INTERVAL) {
                showToast("Please wait before making another request", true)
                return@setOnClickListener
            }
            lastApiCallTime = currentTime
            
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
            // Rate limiting protection for manual retry
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastApiCallTime < MIN_API_CALL_INTERVAL) {
                showToast("Please wait before testing connection", true)
                return@setOnClickListener
            }
            lastApiCallTime = currentTime
            testApiConnection() 
        }
        
        binding.btnDebugAuth.setOnClickListener {
            debugAuthFlow()
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
        val resumeUri = selectedResumeUri ?: return showToast("Please select resume file", true)
        val jobDescUri = selectedJobDescUri ?: return showToast("Please select job description file", true)

        disableGenerateButton("Processing...")

        lifecycleScope.launch {
            try {
                if (!ensureUserAuthenticated()) {
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
                            showToastAndReset("Insufficient credits. Please purchase more.", true)
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
                        
                        if (creditResult.code == 429) {
                            showToastAndReset("Rate limit exceeded. Please wait a moment before trying again.", true)
                        } else {
                            showToastAndReset("Failed to check credits: ${creditResult.message}", true)
                        }

                        if (creditResult.code == 401) {
                            showToast("Authentication failed. Please log out and log in again.", true)
                            userManager.logout()
                            checkGenerateButtonState()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Exception in generateResumeFromFiles: ${e.message}", e)
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
                        Log.d("ResumeActivity", "User has $credits credits")

                        if (credits <= 0) {
                            showToastAndReset("Insufficient credits. Please purchase more.", true)
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
                        
                        if (creditResult.code == 429) {
                            showToastAndReset("Rate limit exceeded. Please wait a moment before trying again.", true)
                        } else {
                            showToastAndReset("Failed to check credits: ${creditResult.message}", true)
                        }

                        if (creditResult.code == 401) {
                            showToast("Authentication failed. Please log out and log in again.", true)
                            userManager.logout()
                            checkGenerateButtonState()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Exception in generateResumeFromText: ${e.message}", e)
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
            
            // Update cached credits
            userManager.cacheCredits(remaining)
            
        } catch (e: Exception) {
            Log.e("ResumeActivity", "Error displaying resume: ${e.message}", e)
            showToast("Error displaying resume: ${e.message}", true)
        }
    }

    private fun downloadFile(format: String) {
        val resumeData = currentGeneratedResume ?: return showToast("No resume generated yet", true)
        
        lifecycleScope.launch {
            try {
                val url = when (format.lowercase()) {
                    "docx" -> resumeData.docx_url
                    "pdf" -> resumeData.pdf_url
                    else -> return@launch showToast("Unsupported format: $format", true)
                }

                if (url.isBlank()) {
                    showToast("Download URL not available for $format", true)
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
                        showToast("Download failed: ${downloadResult.message}", true)
                    }
                }
            } catch (e: Exception) {
                Log.e("ResumeActivity", "Download failed: ${e.message}", e)
                showToast("Download failed: ${e.message}", true)
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

        showToast("$format file saved successfully!", false)
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
                        showToast("Resume generated successfully!", false)

                        val remaining = data.remaining_credits
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                binding.creditText.text = "Credits: $remaining"
                                // Update cached credits
                                userManager.cacheCredits(remaining)
                            }
                        }
                    }
                    else -> {
                        Log.e("ResumeActivity", "Unexpected response type: ${data?.let { it::class.java.simpleName } ?: "null"}")
                        showToast("Unexpected response from server", true)
                    }
                }
            }
            is ApiService.ApiResult.Error -> {
                Log.e("ResumeActivity", "Resume generation failed: ${result.message}")
                
                if (result.code == 429) {
                    showToast("Rate limit exceeded. Please wait before trying again.", true)
                } else {
                    showToast("Generation failed: ${result.message}", true)
                }
                
                lifecycleScope.launch {
                    // Don't immediately update credits after failure to avoid rate limits
                    delay(2000L)
                    updateCreditDisplay()
                }
            }
        }
    }

    /** ---------------- Consolidated Helper Methods ---------------- **/
    private suspend fun <T> safeApiCallWithResult(
        operation: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                if (!ensureUserAuthenticated()) {
                    return ApiService.ApiResult.Error("Authentication failed", 401)
                }
                
                val result = block()
                if (result is ApiService.ApiResult.Success) {
                    return result
                }
                
                // Don't retry on rate limit errors
                if (result is ApiService.ApiResult.Error && (result.code == 429 || result.code == 401)) {
                    return result
                }
                
                Log.w("SafeApiCall", "Attempt $attempt failed for $operation: ${(result as? ApiService.ApiResult.Error)?.message}")
                
            } catch (e: Exception) {
                lastError = e
                Log.e("SafeApiCall", "Exception in $operation attempt $attempt: ${e.message}")
            }
            
            if (attempt < maxRetries - 1) {
                delay(RETRY_DELAY_MS * (attempt + 1)) // Exponential backoff
            }
        }
        
        return ApiService.ApiResult.Error(
            lastError?.message ?: "All retry attempts failed for $operation", 
            0
        )
    }

    private suspend fun ensureUserAuthenticated(): Boolean {
        return try {
            userManager.emergencySyncWithFirebase()
            
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            
            Log.d("AUTH_CHECK", "UserManager: $isLoggedIn, Firebase: ${firebaseUser != null}")
            
            if (isLoggedIn && firebaseUser != null) {
                Log.d("AUTH_CHECK", "✅ User properly authenticated")
                true
            } else {
                Log.e("AUTH_CHECK", "❌ User not authenticated")
                withContext(Dispatchers.Main) {
                    showToast("Please log in to continue", true)
                    binding.creditText.text = "Credits: Please log in"
                }
                false
            }
            
        } catch (e: Exception) {
            Log.e("AUTH_CHECK", "💥 Authentication check failed: ${e.message}", e)
            withContext(Dispatchers.Main) {
                showToast("Authentication system error", true)
                binding.creditText.text = "Credits: Error"
            }
            false
        }
    }

    private suspend fun updateCreditDisplay() {
        try {
            // Rate limiting: don't update credits too frequently
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastApiCallTime < MIN_API_CALL_INTERVAL) {
                Log.d("CreditUpdate", "Skipping credit update due to rate limiting")
                return
            }
            
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
                            userManager.cacheCredits(credits) // Cache the credits
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
                                showToast("Authentication failed. Please log out and log in again.", true)
                                userManager.logout()
                            }
                            429 -> {
                                binding.creditText.text = "Credits: Rate Limited"
                                showToast("Too many requests. Please wait a moment.", true)
                                // Use cached credits when rate limited
                                val cachedCredits = userManager.getCachedCredits()
                                if (cachedCredits > 0) {
                                    binding.creditText.text = "Credits: $cachedCredits (cached)"
                                }
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
                val cachedCredits = userManager.getCachedCredits()
                binding.creditText.text = "Credits: $cachedCredits (cached)"
            }
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
            Log.e("ResumeActivity", "Failed to get file name: ${e.message}", e)
            null
        }
    }

    private fun showToast(message: String, isError: Boolean) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTime > TOAST_COOLDOWN_MS) {
            val prefix = if (isError) "❌ " else "✅ "
            Toast.makeText(this, "$prefix$message", Toast.LENGTH_LONG).show()
            lastToastTime = currentTime
        }
        
        if (isError) {
            Log.e("ResumeActivity", message)
        } else {
            Log.d("ResumeActivity", message)
        }
    }

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
                    showToast("Session expired. Please log in again.", true)
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
                    showToast("Some features may not work without email verification", true)
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
                showToast("Verification email sent to ${user.email}", false)
            } else {
                showToast("Failed to send verification email", true)
            }
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
                        showToast("Basic connectivity: ✅ WORKING", false)
                    }
                    is ApiService.ApiResult.Error -> {
                        binding.tvConnectionStatus.text = "❌ Basic API failed: ${result.message}"
                        Log.e("BasicTest", "❌ Basic API call FAILED: ${result.message}")
                        showToast("Basic connectivity: ❌ FAILED - ${result.message}", true)
                    }
                }
            } catch (e: Exception) {
                binding.tvConnectionStatus.text = "💥 Test crashed: ${e.message}"
                Log.e("BasicTest", "💥 Test crashed", e)
                showToast("Test crashed: ${e.message}", true)
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
                        // Don't auto-update credits after connection test to avoid rate limits
                    }
                    is ApiService.ApiResult.Error -> {
                        if (connectionResult.code in 500..599 || connectionResult.code == 0) {
                            updateConnectionStatus("🔄 Server is waking up...", true)
                            showServerWakeupMessage()
                            
                            val serverAwake = apiService.waitForServerWakeUp(maxAttempts = 6, delayBetweenAttempts = 15000L) // Reduced attempts
                            
                            if (serverAwake) {
                                updateConnectionStatus("✅ Server is ready!", false)
                            } else {
                                updateConnectionStatus("⏰ Server taking too long", true)
                                showToast("Server is taking longer than expected. Please try again in a minute.", true)
                            }
                        } else {
                            updateConnectionStatus("❌ API Connection Failed", true)
                            showToast("API error: ${connectionResult.message}", true)
                        }
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
