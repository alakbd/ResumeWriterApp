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
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import java.io.OutputStream
import android.view.MotionEvent
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.AdapterView
import android.text.TextWatcher
import android.text.Editable

class ResumeGenerationActivity : AppCompatActivity() {

    private val MIN_API_CALL_INTERVAL = 5000L // 5 seconds between API calls
    private val MAX_REQUESTS_PER_MINUTE = 6
    private val MAX_RETRIES = 1
    private val RETRY_DELAY = 15000L
    
    // Use lazy initialization for better performance - REMOVE THE DUPLICATE lateinit declarations
    private val creditManager: CreditManager by lazy { CreditManager(this) }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private lateinit var binding: ActivityResumeGenerationBinding
    private lateinit var apiService: ApiService
    private lateinit var userManager: UserManager
    
    // REMOVED: private lateinit var auth: FirebaseAuth
    // REMOVED: private lateinit var creditManager: CreditManager

    private var selectedResumeUri: Uri? = null
    private var selectedJobDescUri: Uri? = null
    private var currentGeneratedResume: ApiService.GenerateResumeResponse? = null

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>
    private lateinit var spinnerTone: Spinner
    private var selectedTone: String = "Professional"
    private var lastToastTime: Long = 0
    private val TOAST_COOLDOWN_MS = 3000L

    // Rate limiting protection
    private val apiCallTimestamps = mutableListOf<Long>()
    private var lastApiCallTime: Long = 0

    // Conservative limits - PREVENT hitting server limits
    private companion object {
        const val MAX_REQUESTS_PER_MINUTE = 6  // Even more conservative
        const val MIN_TIME_BETWEEN_CALLS = 5000L // 5 seconds between API calls
        const val MAX_RETRIES = 1 // Only retry once
        const val RETRY_DELAY = 15000L // 15 seconds for retry
    }

    // More efficient rate limiting
    private fun canMakeApiCall(): Boolean {
        val now = System.currentTimeMillis()
        
        // Clean up old timestamps (older than 1 minute)
        val oneMinuteAgo = now - 60000L
        apiCallTimestamps.removeAll { it < oneMinuteAgo }
        
        // Check per-minute limit
        if (apiCallTimestamps.size >= MAX_REQUESTS_PER_MINUTE) {
            val timeToWait = (apiCallTimestamps.first() + 60000L - now) / 1000
            showToast("Too many requests. Please wait ${timeToWait}s", true)
            return false
        }
        
        // Check minimum time between calls
        if (now - lastApiCallTime < MIN_API_CALL_INTERVAL) {
            val timeToWait = (MIN_API_CALL_INTERVAL - (now - lastApiCallTime)) / 1000
            showToast("Please wait ${timeToWait}s between requests", true)
            return false
        }
        
        return true
    }

    private fun recordApiCall() {
        val now = System.currentTimeMillis()
        apiCallTimestamps.add(now)
        lastApiCallTime = now
        
        // Keep list from growing too large
        if (apiCallTimestamps.size > 20) {
            apiCallTimestamps.removeAt(0)
        }
        
        Log.d("RateLimit", "📊 API calls in last minute: ${apiCallTimestamps.size}/$MAX_REQUESTS_PER_MINUTE")
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
        // REMOVED: auth = FirebaseAuth.getInstance() - using lazy val instead
        // REMOVED: creditManager = CreditManager(this) - using lazy val instead

        registerFilePickers()
        setupUI()
        setupToneSpinner()
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
        creditManager.resetResumeCooldown() // This will initialize creditManager via lazy

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

  
    // Handle permission results
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            1001 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    showToast("Storage permission granted", false)
                } else {
                    showToast("Storage permission denied - cannot save files", true)
                }
            }
        }
    }
    
    /** ---------------- UI Setup ---------------- **/
    private fun setupUI() {
        binding.btnSelectResume.setOnClickListener { resumePicker.launch("application/*") }
        binding.btnSelectJobDesc.setOnClickListener { jobDescPicker.launch("application/*") }
        // Add text change listeners
        binding.etResumeText.addTextChangedListener(textWatcher)
        binding.etJobDescription.addTextChangedListener(textWatcher)
        
        //Simple scrolling setup:
        setupScrollableResumeArea() 
        
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
            // ⭐⭐⭐ ADD EMAIL VERIFICATION CHECK HERE - AT THE VERY START
            if (!userManager.canGenerateResume()) {
                showVerificationRequiredDialog()
                return@setOnClickListener
            }
            
            if (!userManager.isUserLoggedIn()) {
                showToast("Please log in to generate resumes", true)
                return@setOnClickListener
            }
            
            // ENHANCED RATE LIMIT CHECK
            if (!canMakeApiCall()) {
                return@setOnClickListener
            }
            
            recordApiCall() // Record the attempt
            
    // DETECT INPUT COMBINATION AND ROUTE ACCORDINGLY
    when {
        // CASE 1: File → File (both files selected)
        selectedResumeUri != null && selectedJobDescUri != null -> 
            generateResumeFromFiles()
        
        // CASE 2: Text → Text (both text fields filled)
        binding.etResumeText.text.isNotEmpty() && binding.etJobDescription.text.isNotEmpty() -> 
            generateResumeFromText()
        
        // CASE 3: NEW! File → Text (resume file + job description text)
        selectedResumeUri != null && binding.etJobDescription.text.isNotEmpty() -> 
            generateResumeFromFileToText()
        
        // CASE 4: Text → File (resume text + job description file) - if needed
        binding.etResumeText.text.isNotEmpty() && selectedJobDescUri != null -> 
            showToast("Text resume + File job description not supported yet", true)
        
        else -> showToast("Please provide both resume and job description", true)
    }
 }
        
        binding.btnDownloadDocx.setOnClickListener { 
                downloadFile("docx") 
        }
        
        binding.btnDownloadPdf.setOnClickListener { 
                downloadFile("pdf")  
        }
        
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
        
        //binding.btnDebugAuth.setOnClickListener {debugAuthFlow()}
    }

    private val textWatcher = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) {
        checkGenerateButtonState()
    }
}

    private fun setupToneSpinner() {
    spinnerTone = binding.spinnerTone    
    // Set default selection
    spinnerTone.setSelection(0) // Professional
    
    // Listen for tone selection changes
    spinnerTone.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            selectedTone = parent?.getItemAtPosition(position).toString()
            Log.d("ToneSelection", "Selected tone: $selectedTone")
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            selectedTone = "Professional"
        }
    }
}

    /** ---------------- Simple Scroll Setup ---------------- **/
private fun setupScrollableResumeArea() {
    binding.tvGeneratedResume.apply {
        // Enable touch scrolling
        movementMethod = ScrollingMovementMethod.getInstance()
        
        // Enable visual scrollbars
        isVerticalScrollBarEnabled = true
        
        // Keep text selectable
        setTextIsSelectable(true)
        
        // Remove any complex touch listeners
        setOnTouchListener(null)
    }
}

    private fun checkGenerateButtonState() {
    val hasResumeFile = selectedResumeUri != null
    val hasJobDescFile = selectedJobDescUri != null
    val hasResumeText = binding.etResumeText.text.toString().isNotEmpty()
    val hasJobDescText = binding.etJobDescription.text.toString().isNotEmpty()
    val isLoggedIn = userManager.isUserLoggedIn()
    val isEmailVerified = isEmailVerified()

    // Determine which combinations are valid
    val fileToFile = hasResumeFile && hasJobDescFile
    val textToText = hasResumeText && hasJobDescText
    val fileToText = hasResumeFile && hasJobDescText  // NEW!
    val textToFile = hasResumeText && hasJobDescFile  // Optional for future

    val shouldEnable = (fileToFile || textToText || fileToText) && isLoggedIn && isEmailVerified
    
    Log.d("ButtonState", "ResumeFile: $hasResumeFile, JobFile: $hasJobDescFile, ResumeText: $hasResumeText, JobText: $hasJobDescText, Enable: $shouldEnable")
    
    binding.btnGenerateResume.isEnabled = shouldEnable
    
    // Update button text to show what will happen
    binding.btnGenerateResume.text = when {
        !isLoggedIn -> "Please Log In"
        !isEmailVerified -> "Verify Email First"
        fileToFile -> "Generate from Files (1 Credit)"
        textToText -> "Generate from Text (1 Credit)"
        fileToText -> "Generate from File+Text (1 Credit)"  // NEW!
        else -> "Generate Resume"
    }
}

    /** ---------------- Resume Generation ---------------- **/
    private fun generateResumeFromFiles() {
        val resumeUri = selectedResumeUri ?: return showToast("Please select resume file", true)
        val jobDescUri = selectedJobDescUri ?: return showToast("Please select job description file", true)

        // ⭐⭐⭐ SECONDARY CHECK - ALREADY CHECKED IN BUTTON CLICK BUT KEEP FOR SAFETY
        if (!userManager.canGenerateResume()) {
            showVerificationRequiredDialog()
            return
        }

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
                            apiService.generateResumeFromFiles(resumeUri, jobDescUri, selectedTone) 
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

        // ⭐⭐⭐ SECONDARY CHECK - ALREADY CHECKED IN BUTTON CLICK BUT KEEP FOR SAFETY
        if (!userManager.canGenerateResume()) {
            showVerificationRequiredDialog()
            return
        }

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
                            apiService.generateResumeFromText(resumeText, jobDesc, selectedTone) 
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

/** ---------------- NEW: File to Text Resume Generation ---------------- **/
private fun generateResumeFromFileToText() {
    Log.d("FLOW_DEBUG", "🎯 START: generateResumeFromFileToText() called")
    
    val resumeUri = selectedResumeUri ?: return showToast("Please select resume file", true)
    val jobDescText = binding.etJobDescription.text.toString().trim()
    
    Log.d("FLOW_DEBUG", "📄 Resume URI: $resumeUri")
    Log.d("FLOW_DEBUG", "📝 Job desc length: ${jobDescText.length}")

    if (jobDescText.isEmpty()) {
        showToast("Please enter job description text", true)
        return
    }

    // Email verification check
    if (!userManager.canGenerateResume()) {
        showVerificationRequiredDialog()
        return
    }

    // Rate limiting check
    if (!canMakeApiCall()) {
        return
    }

    recordApiCall()
    disableGenerateButton("Processing...")

    Log.d("FLOW_DEBUG", "🔴 Button disabled")

    lifecycleScope.launch {
        Log.d("FLOW_DEBUG", "🚀 Coroutine started")
        
        try {
            Log.d("FLOW_DEBUG", "💰 Step 1: Checking credits...")
            val creditResult = safeApiCallWithResult<ApiService.UserCreditsResponse>("getUserCredits") { 
                apiService.getUserCredits() 
            }

            Log.d("FLOW_DEBUG", "💰 Credit result: ${creditResult.javaClass.simpleName}")
            
            when (creditResult) {
                is ApiService.ApiResult.Success -> {
                    Log.d("FLOW_DEBUG", "✅ Credits available: ${creditResult.data.available_credits}")
                    
                    Log.d("FLOW_DEBUG", "🚀 Step 2: Calling generateResumeFromFileToText API...")
                    val genResult = safeApiCallWithResult<ApiService.GenerateResumeResponse>("generateResumeFromFileToText") { 
                        apiService.generateResumeFromFileToText(resumeUri, jobDescText, selectedTone) 
                    }

                    Log.d("FLOW_DEBUG", "📬 Generation result: ${genResult.javaClass.simpleName}")
                    
                    // CRITICAL: Check what type of result we got
                    when (genResult) {
                        is ApiService.ApiResult.Success -> {
                            Log.d("FLOW_DEBUG", "🎉 API SUCCESS - Resume length: ${genResult.data.resume_text.length}")
                            Log.d("FLOW_DEBUG", "🎉 Remaining credits: ${genResult.data.remaining_credits}")
                            Log.d("FLOW_DEBUG", "🎉 Success flag: ${genResult.data.success}")
                        }
                        is ApiService.ApiResult.Error -> {
                            Log.e("FLOW_DEBUG", "❌ API ERROR: ${genResult.message}")
                            Log.e("FLOW_DEBUG", "❌ Error code: ${genResult.code}")
                        }
                    }
                    
                    Log.d("FLOW_DEBUG", "🔄 Calling handleGenerationResult...")
                    handleGenerationResult(genResult)
                }
                is ApiService.ApiResult.Error -> {
                    Log.e("FLOW_DEBUG", "❌ CREDIT CHECK ERROR: ${creditResult.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("FLOW_DEBUG", "💥 COROUTINE EXCEPTION: ${e.message}", e)
        } finally {
            Log.d("FLOW_DEBUG", "🏁 Finally block reached")
            resetGenerateButton()
        }
    }
    Log.d("FLOW_DEBUG", "🎯 END: generateResumeFromFileToText() completed")
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

            Log.d("Download", "📥 Downloading $format from: $url")
            val downloadResult = apiService.downloadFile(url)

            when (downloadResult) {
                is ApiService.ApiResult.Success -> {
                    val fileData = downloadResult.data
                    val fileName = "SkillSync_Resume_${resumeData.generation_id ?: System.currentTimeMillis()}.$format"

                    // ✅ Save safely via MediaStore (no storage permission required)
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
                        showToast("❌ Failed to create file in Downloads", true)
                        return@launch
                    }

                    resolver.openOutputStream(uri)?.use { output ->
                        output.write(fileData)
                        output.flush()
                    }

                    showToast("✅ ${format.uppercase()} file saved to Downloads!", false)

                    // Optional: if your showDownloadSuccess() needs to be triggered
                    showDownloadSuccess(null, format.uppercase(), uri)
                }

                is ApiService.ApiResult.Error -> {
                    showToast("❌ Download failed: ${downloadResult.message}", true)
                }
            }
        } catch (e: Exception) {
            Log.e("ResumeActivity", "Download failed: ${e.message}", e)
            showToast("❌ Download failed: ${e.message}", true)
        } finally {
            binding.progressGenerate.visibility = View.GONE
            binding.btnDownloadDocx.isEnabled = true
            binding.btnDownloadPdf.isEnabled = true
        }
    }
}

    private fun showDownloadSuccess(file: File?, format: String, uri: Uri? = null) {
    val message = when {
        uri != null -> "✅ Resume saved to Downloads folder as SkillSync_Resume.$format"
        file?.absolutePath?.contains("Download") == true -> "✅ Resume saved to Downloads folder as SkillSync_Resume.$format"
        else -> "✅ Resume saved as SkillSync_Resume.$format"
    }

    showToast(message, false)

    // Use the provided Uri (modern MediaStore) or fallback to File
    val shareUri = uri ?: file?.let { Uri.fromFile(it) } ?: return

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

    // Delay to ensure file write completes before sharing
    lifecycleScope.launch {
        delay(500)
        startActivity(Intent.createChooser(shareIntent, "Share SkillSync_Resume"))
    }
}

    /** ---------------- Generation Result Handler ---------------- **/
    private fun <T> handleGenerationResult(result: ApiService.ApiResult<T>) {
    Log.d("ResumeActivity", "🎯 handleGenerationResult CALLED")
    Log.d("ResumeActivity", "📊 Result type: ${result.javaClass.simpleName}")
    
    when (result) {
        is ApiService.ApiResult.Success -> {
            Log.d("ResumeActivity", "✅ SUCCESS branch entered")
            when (val data = result.data) {
                is ApiService.GenerateResumeResponse -> {
                    Log.d("ResumeActivity", "📝 Processing GenerateResumeResponse")
                    Log.d("ResumeActivity", "💰 Credits: ${data.remaining_credits}")
                    Log.d("ResumeActivity", "📄 Resume length: ${data.resume_text.length}")
                    
                    currentGeneratedResume = data
                    
                    runOnUiThread {
                        Log.d("ResumeActivity", "🎯 UI Thread - Calling displayGeneratedResume")
                        displayGeneratedResume(data)
                        binding.creditText.text = "Credits: ${data.remaining_credits}"
                        showToast("Resume generated successfully!", false)
                        Log.d("ResumeActivity", "✅ UI updates completed")
                    }
                }
                else -> {
                    Log.e("ResumeActivity", "❌ UNEXPECTED TYPE: ${data?.let { it::class.java.simpleName } ?: "null"}")
                    showToast("Unexpected response from server", true)
                }
            }
        }
        is ApiService.ApiResult.Error -> {
            Log.e("ResumeActivity", "❌ ERROR: ${result.message} - Code: ${result.code}")
                
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
    // Optimized API call method
    private suspend fun <T> safeApiCallWithResult(
        operation: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        var lastError: Exception? = null
        
        for (attempt in 0..maxRetries) {
            try {
                Log.d("SafeApiCall", "🔧 Attempt ${attempt + 1} for $operation")
                
                val result = block()
                
                when {
                    result is ApiService.ApiResult.Success -> {
                        Log.d("SafeApiCall", "✅ $operation succeeded")
                        return result
                    }
                    result is ApiService.ApiResult.Error && result.code == 429 -> {
                        val waitTime = 30000L
                        Log.w("SafeApiCall", "⏳ Rate limit hit, waiting ${waitTime/1000}s")
                        showToast("Server busy. Waiting 30 seconds...", true)
                        delay(waitTime)
                        if (attempt == maxRetries) {
                            return ApiService.ApiResult.Error("Rate limit exceeded", 429)
                        }
                    }
                    result is ApiService.ApiResult.Error && result.code in 500..599 -> {
                        val waitTime = (attempt + 1) * 10000L
                        Log.w("SafeApiCall", "🔄 Server error, retrying in ${waitTime/1000}s")
                        delay(waitTime)
                    }
                    else -> return result
                }
            } catch (e: Exception) {
                lastError = e
                Log.e("SafeApiCall", "❌ Exception in attempt ${attempt + 1}: ${e.message}")
                
                if (attempt < maxRetries) {
                    val waitTime = (attempt + 1) * 5000L
                    delay(waitTime)
                }
            }
        }
        
        return ApiService.ApiResult.Error(
            "Service temporarily unavailable. Please try again later.",
            503
        )
    }

    private suspend fun ensureUserAuthenticated(): Boolean {
        return try {
            userManager.emergencySyncWithFirebase()
            
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = auth.currentUser // Use the lazy auth instance
            
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
        // ENHANCED RATE LIMIT CHECK
        if (!canMakeApiCall()) {
            Log.d("CreditUpdate", "⏳ Skipping credit update due to client-side rate limiting")
            return
        }
        
        recordApiCall()
        
        try {
            delay(2000L) // Additional safety delay
            
            if (!userManager.isUserLoggedIn()) {
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                }
                return
            }

            Log.d("ResumeActivity", "🔄 Updating credit display...")
            
            val result = apiService.getUserCredits()
            when (result) {
                is ApiService.ApiResult.Success -> {
                    withContext(Dispatchers.Main) {
                        val credits = result.data.available_credits
                        binding.creditText.text = "Credits: $credits"
                        Log.d("ResumeActivity", "✅ Credits updated: $credits")
                    }
                }
                is ApiService.ApiResult.Error -> {
                    Log.w("ResumeActivity", "Failed to get credits: ${result.message}")
                    if (result.code == 429) {
                        withContext(Dispatchers.Main) {
                            binding.creditText.text = "Credits: Rate Limited"
                            showToast("Too many credit checks. Please wait 1 minute.", true)
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
            val firebaseUser = auth.currentUser // Use the lazy auth instance
            
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
        val user = auth.currentUser // Use the lazy auth instance
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
                    auth.signOut() // Use the lazy auth instance
                    finish()
                }

            val dialog = builder.create()
            dialog.show()
        }
    }

    private fun sendEmailVerification() {
        val user = auth.currentUser // Use the lazy auth instance
        user?.sendEmailVerification()?.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                showToast("Verification email sent to ${user.email}", false)
            } else {
                showToast("Failed to send verification email", true)
            }
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

    // ⭐⭐⭐ ADD THIS NEW METHOD FOR VERIFICATION DIALOG
    private fun showVerificationRequiredDialog() {
        val user = auth.currentUser
        AlertDialog.Builder(this)
            .setTitle("Email Verification Required")
            .setMessage("Please verify your email address (${user?.email ?: "your email"}) before generating resumes. Check your inbox for the verification link.")
            .setPositiveButton("Resend Verification") { _, _ ->
                userManager.sendEmailVerification { success, message ->
                    if (success) {
                        Toast.makeText(this, "Verification email sent!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "Failed to send: $message", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("OK", null)
            .show()
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

    // Clear memory when needed
    private fun clearMemoryCache() {
        selectedResumeUri = null
        selectedJobDescUri = null
        currentGeneratedResume = null
        binding.etResumeText.text?.clear()
        binding.etJobDescription.text?.clear()
        System.gc()
    }

    // Optimize onDestroy
    override fun onDestroy() {
        super.onDestroy()
        clearMemoryCache()
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore
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
                
                val firebaseUser = auth.currentUser // Use the lazy auth instance
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
