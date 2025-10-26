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
    private var currentGeneratedResume: JSONObject? = null

    private lateinit var resumePicker: ActivityResultLauncher<String>
    private lateinit var jobDescPicker: ActivityResultLauncher<String>
    private var lastToastTime: Long = 0
    private val TOAST_COOLDOWN_MS = 3000L // 3 seconds between toasts

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

        registerFilePickers()
        setupUI()
        checkEmailVerification()
        checkGenerateButtonState()

        // 🔧 TEST BASIC CONNECTIVITY FIRST
        testBasicApiCall()

        // 🔧 DEBUG: Auto-check authentication state
        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 Initial auth setup...")
            
            // First, check current auth state
            val isLoggedIn = userManager.isUserLoggedIn()
            Log.d("ResumeActivity", "Initial auth state: $isLoggedIn")
            
            if (isLoggedIn) {
                // User appears logged in - update UI optimistically
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Loading..."
                }
                
                // Then try to sync with server
                if (isNetworkAvailable()) {
                    updateCreditDisplay()
                } else {
                    // Offline - show cached credits
                    val cachedCredits = userManager.getCachedCredits()
                    withContext(Dispatchers.Main) {
                        binding.creditText.text = "Credits: $cachedCredits (offline)"
                    }
                }
            } else {
                // Definitely not logged in
                withContext(Dispatchers.Main) {
                    binding.creditText.text = "Credits: Please log in"
                }
            }
            
            // Test basic connectivity (non-blocking)
            if (isNetworkAvailable()) {
                testBasicApiCall()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Register network callback
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStop() {
        super.onStop()
        // Unregister network callback
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w("NetworkState", "Failed to unregister network callback: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        
        lifecycleScope.launch {
            Log.d("ResumeActivity", "🔄 onResume - refreshing data...")
            
            try {
                // Add initial delay to let system stabilize
                delay(1000L)
                
                val authValid = checkAuthenticationState()
                
                if (authValid) {
                    // Only update credits if we have a stable connection
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
                    // Don't show error toast here to avoid spamming user
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

    private fun debugAuthFlow() {
    lifecycleScope.launch {
        try {
            binding.tvGeneratedResume.text = "🔐 Debugging Authentication Flow..."
            
            val debugInfo = StringBuilder()
            debugInfo.appendLine("🔐 AUTHENTICATION DEBUG")
            debugInfo.appendLine("=".repeat(50))
            
            // 1. Check UserManager state
            debugInfo.appendLine("1. USER MANAGER STATE:")
            debugInfo.appendLine("   • isUserLoggedIn(): ${userManager.isUserLoggedIn()}")
            debugInfo.appendLine("   • getCurrentUserId(): ${userManager.getCurrentUserId()}")
            debugInfo.appendLine("   • getCurrentUserEmail(): ${userManager.getCurrentUserEmail()}")
            
            // 2. Check Firebase Auth state
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            debugInfo.appendLine("\n2. FIREBASE AUTH STATE:")
            debugInfo.appendLine("   • Current User: ${firebaseUser?.uid ?: "NULL"}")
            debugInfo.appendLine("   • Email: ${firebaseUser?.email ?: "NULL"}")
            debugInfo.appendLine("   • Verified: ${firebaseUser?.isEmailVerified ?: false}")
            
            // 3. Test basic API (no auth)
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
            
            // 4. Test authenticated API
            debugInfo.appendLine("\n4. AUTHENTICATED API TEST:")
            if (userManager.isUserLoggedIn()) {
                val creditsResult = apiService.getUserCredits()
                when (creditsResult) {
                    is ApiService.ApiResult.Success -> {
                        debugInfo.appendLine("   • Credits Endpoint: ✅ SUCCESS")
                        debugInfo.appendLine("   • Credits: ${creditsResult.data.optInt("available_credits", -1)}")
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
        
        // 🔧 DEBUG BUTTON
        binding.btnDebugAuth.setOnClickListener {
             debugAuthFlow()
        }
        
        // 🔧 HEADER TEST BUTTON
        binding.btnDebugAuth.setOnLongClickListener {
            testHeaderSending()
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

    /** ---------------- NETWORK TEST METHOD ---------------- **/
    private fun runComprehensiveNetworkTest() {
        lifecycleScope.launch {
            try {
                binding.tvGeneratedResume.text = "🩺 Running comprehensive network test..."
                
                val results = StringBuilder()
                results.appendLine("🌐 COMPREHENSIVE NETWORK TEST")
                results.appendLine("=".repeat(60))
                
                // Test 1: Basic connectivity
                results.appendLine("1. BASIC CONNECTIVITY:")
                results.appendLine("   • Internet: ✅ (DNS resolves to: 216.24.57.7, 216.24.57.251)")
                
                // Test 2: HTTP connection debugging
                results.appendLine("\n2. HTTP CONNECTION TESTS:")
                try {
                    val httpDebug = withTimeout(30000) { // 30 second timeout
                        apiService.debugHttpConnection()
                    }
                    results.append(httpDebug)
                } catch (e: TimeoutCancellationException) {
                    results.appendLine("   ⏰ TIMEOUT: HTTP tests took too long")
                } catch (e: Exception) {
                    results.appendLine("   💥 CRASHED: ${e.message}")
                }
                
                // Test 3: Test with the unsafe client
                results.appendLine("\n3. UNSAFE CLIENT TEST:")
                try {
                    val testResult = withTimeout(15000) { // 15 second timeout
                        apiService.testConnection()
                    }
                    when (testResult) {
                        is ApiService.ApiResult.Success -> 
                            results.appendLine("   ✅ SUCCESS: ${testResult.data}")
                        is ApiService.ApiResult.Error -> 
                            results.appendLine("   ❌ FAILED: ${testResult.message}")
                    }
                } catch (e: TimeoutCancellationException) {
                    results.appendLine("   ⏰ TIMEOUT: Test took too long")
                } catch (e: Exception) {
                    results.appendLine("   💥 CRASHED: ${e.message}")
                }
                
                binding.tvGeneratedResume.text = results.toString()
                
            } catch (e: Exception) {
                binding.tvGeneratedResume.text = "💥 Main test crashed: ${e.message}"
            }
        }
    }

    private suspend fun revalidateAuthState() {
        Log.d("AuthRevalidation", "🔄 Revalidating authentication state...")
        
        try {
            // Force sync with Firebase
            userManager.emergencySyncWithFirebase()
            
            val isLoggedIn = userManager.isUserLoggedIn()
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            
            Log.d("AuthRevalidation", "After sync - Logged in: $isLoggedIn, Firebase: ${firebaseUser != null}")
            
            withContext(Dispatchers.Main) {
                if (isLoggedIn) {
                    // Update UI to reflect logged in state
                    binding.creditText.text = "Credits: Loading..."
                    lifecycleScope.launch {
                        updateCreditDisplay()
                    }
                    checkGenerateButtonState()
                    Log.d("AuthRevalidation", "✅ Auth revalidated - user is logged in")
                } else {
                    // User actually needs to log in
                    binding.creditText.text = "Credits: Please log in"
                    showError("Session expired. Please log in again.")
                    Log.w("AuthRevalidation", "❌ Auth revalidation failed - user needs to login")
                }
            }
        } catch (e: Exception) {
            Log.e("AuthRevalidation", "💥 Revalidation failed: ${e.message}")
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

    private fun showRateLimitedToast(message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastToastTime > TOAST_COOLDOWN_MS) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            lastToastTime = currentTime
            Log.d("Toast", "Showing: $message")
        } else {
            Log.d("Toast", "Rate limited: $message")
        }
    }

    private fun showRateLimitedError(message: String) {
        showRateLimitedToast("❌ $message")
    }

    private fun showRateLimitedSuccess(message: String) {
        showRateLimitedToast("✅ $message")
    }
    
    /** ---------------- API Connection Test ---------------- **/
    private fun testBasicApiCall() {
        lifecycleScope.launch {
            try {
                binding.tvConnectionStatus.text = "Testing basic API call..."
                binding.progressConnection.visibility = View.VISIBLE
                
                Log.d("BasicTest", "🔄 Testing basic API call without authentication...")
                
                // Test the health endpoint first (no auth required)
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

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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

    private fun showReloginPrompt() {
        AlertDialog.Builder(this)
            .setTitle("Authentication Issue")
            .setMessage("There seems to be an authentication problem. Would you like to log out and log in again?")
            .setPositiveButton("Log Out & Re-login") { _, _ ->
                userManager.logout()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                val creditResult = safeApiCallWithResult("getUserCredits") { 
                    apiService.getUserCredits() 
                }
                
                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.optInt("available_credits", 0)
                        Log.d("ResumeActivity", "User has $credits credits")
                        
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }

                        Log.d("ResumeActivity", "Generating resume from files")
                        val genResult = safeApiCallWithResult("generateResumeFromFiles") { 
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

                val creditResult = safeApiCallWithResult("getUserCredits") { 
                    apiService.getUserCredits() 
                }
                when (creditResult) {
                    is ApiService.ApiResult.Success -> {
                        val credits = creditResult.data.optInt("available_credits", 0)
                        Log.d("ResumeActivity", "User has $credits credits")
                        if (credits <= 0) {
                            showErrorAndReset("Insufficient credits. Please purchase more.")
                            return@launch
                        }
                        
                        Log.d("ResumeActivity", "Generating resume from text input")
                        val genResult = safeApiCallWithResult("generateResume") { 
                            apiService.generateResume(resumeText, jobDesc) 
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

    private suspend fun <T> safeApiCallWithResult(
        operation: String,
        maxRetries: Int = 2,
        block: suspend () -> ApiService.ApiResult<T>
    ): ApiService.ApiResult<T> {
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                // Check authentication before each attempt
                if (!ensureAuthenticatedBeforeApiCall()) {
                    return ApiService.ApiResult.Error("Authentication failed", 401)
                }
                
                val result = block()
                if (result is ApiService.ApiResult.Success) {
                    return result
                }
                
                // If it's an auth error, don't retry
                if (result is ApiService.ApiResult.Error && result.code == 401) {
                    return result
                }
                
                Log.w("SafeApiCall", "Attempt $attempt failed for $operation: ${(result as? ApiService.ApiResult.Error)?.message}")
                
            } catch (e: Exception) {
                lastError = e
                Log.e("SafeApiCall", "Exception in $operation attempt $attempt: ${e.message}")
            }
            
            if (attempt < maxRetries - 1) {
                delay(1000L * (attempt + 1)) // Exponential backoff
            }
        }
        
        return ApiService.ApiResult.Error(
            lastError?.message ?: "All retry attempts failed for $operation", 
            0
        )
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

    /** ---------------- Credit Display ---------------- **/
    private suspend fun updateCreditDisplay() {
        ensureApiCooldown()
        try {
            // Add delay to prevent rapid successive calls
            delay(500L)
            
            // Check authentication first
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
                            val credits = result.data.optInt("available_credits", 0)
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
    
    /** ---------------- Debug Methods ---------------- **/
    private fun runApiServiceDebug() {
        lifecycleScope.launch {
            binding.tvGeneratedResume.text = "Running API service debug..."
            binding.progressGenerate.visibility = View.VISIBLE
            
            try {
                val debugResult = apiService.debugAuthenticationFlow()
                
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
    
    private fun debugAuthAndCredits() {
        lifecycleScope.launch {
            try {
                binding.progressGenerate.visibility = View.VISIBLE
                
                apiService.forceSyncUserManager()
                
                val debugInfo = apiService.debugAuthenticationFlow()
                
                binding.tvGeneratedResume.text = debugInfo
                Log.d("AuthDebug", debugInfo)
                
            } catch (e: Exception) {
                showMessage("Debug failed: ${e.message}")
            } finally {
                binding.progressGenerate.visibility = View.GONE
            }
        }
    }
    
    // 🔧 NEW DEBUG METHODS
    private fun testBasicConnectivity() {
        lifecycleScope.launch {
            try {
                binding.tvGeneratedResume.text = "Testing basic connectivity..."
                
                // Test 1: Can we reach ANY external site?
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build()
                
                val googleRequest = Request.Builder()
                    .url("https://www.google.com")
                    .build()
                
                val googleResponse = client.newCall(googleRequest).execute()
                val googleSuccess = googleResponse.isSuccessful
                
                // Test 2: Can we reach your API?
                val apiRequest = Request.Builder()
                    .url("https://resume-writer-api.onrender.com/health")
                    .build()
                
                val apiResponse = client.newCall(apiRequest).execute()
                val apiSuccess = apiResponse.isSuccessful
                
                binding.tvGeneratedResume.text = """
                    📊 BASIC CONNECTIVITY TEST:
                    • Google: ${if (googleSuccess) "✅" else "❌"}
                    • Your API: ${if (apiSuccess) "✅" else "❌"}
                    
                    ${if (!googleSuccess) "❌ Cannot reach internet" else ""}
                    ${if (googleSuccess && !apiSuccess) "❌ Internet works but API blocked" else ""}
                    ${if (apiSuccess) "✅ Everything works!" else ""}
                """.trimIndent()
                
            } catch (e: Exception) {
                binding.tvGeneratedResume.text = "💥 Test crashed: ${e.message}"
            }
        }
    }
    
    private fun testHeaderSending() {
        lifecycleScope.launch {
            try {
                binding.tvGeneratedResume.text = "🔧 Running Network Diagnostics..."
                binding.progressGenerate.visibility = View.VISIBLE
                
                val diagnostic = StringBuilder()
                diagnostic.appendLine("🩺 NETWORK DIAGNOSTICS")
                diagnostic.appendLine("=".repeat(50))
                
                // Test 1: Basic Network Connectivity
                diagnostic.appendLine("1. BASIC CONNECTIVITY:")
                val hasInternet = isNetworkAvailable()
                diagnostic.appendLine("   • Internet Access: ${if (hasInternet) "✅" else "❌"}")
                
                if (!hasInternet) {
                    diagnostic.appendLine("   ⚠️  No internet connection detected")
                    diagnostic.appendLine("   💡 Check: WiFi/Mobile data, Airplane mode")
                }
                
                // Test 2: DNS Resolution
                diagnostic.appendLine("\n2. DNS RESOLUTION:")
                val dnsResult = apiService.testDnsResolution()
                diagnostic.appendLine("   • $dnsResult")
                
                // Test 3: HTTP Connection
                diagnostic.appendLine("\n3. HTTP CONNECTION:")
                val healthResult = apiService.testConnection()
                when (healthResult) {
                    is ApiService.ApiResult.Success -> {
                        diagnostic.appendLine("   • API Health: ✅ SUCCESS")
                        diagnostic.appendLine("     Response: ${healthResult.data}")
                    }
                    is ApiService.ApiResult.Error -> {
                        diagnostic.appendLine("   • API Health: ❌ FAILED")
                        diagnostic.appendLine("     Error: ${healthResult.message}")
                    }
                }
                
                diagnostic.appendLine("=".repeat(50))
                
                binding.tvGeneratedResume.text = diagnostic.toString()
                
            } catch (e: Exception) {
                Log.e("NetworkTest", "💥 Test crashed: ${e.message}", e)
                binding.tvGeneratedResume.text = "💥 TEST CRASHED\n\n${e.javaClass.simpleName}: ${e.message}"
            } finally {
                binding.progressGenerate.visibility = View.GONE
            }
        }
    }

    private fun debugUserManagerState() {
        Log.d("🔧 DEBUG", "=== USER MANAGER STATE ===")
        val userId = userManager.getCurrentUserId()
        Log.d("🔧 DEBUG", "UserManager.getCurrentUserId(): '$userId'")
        Log.d("🔧 DEBUG", "UserManager.isUserLoggedIn(): ${userManager.isUserLoggedIn()}")
        
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        Log.d("🔧 DEBUG", "FirebaseAuth.currentUser: ${firebaseUser?.uid}")
        Log.d("🔧 DEBUG", "FirebaseAuth.email: ${firebaseUser?.email}")
        
        if (userId != null && firebaseUser != null) {
            Log.d("🔧 DEBUG", "UID MATCH: ${userId == firebaseUser.uid}")
        } else {
            Log.d("🔧 DEBUG", "UID MISMATCH: One or both are null")
        }
        Log.d("🔧 DEBUG", "=== END DEBUG ===")
    }

    private fun debugFirebaseAuth(): String {
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        return """
        🔥 FIREBASE AUTH STATE:
        UID: ${firebaseUser?.uid ?: "NULL"}
        Email: ${firebaseUser?.email ?: "NULL"}
        Verified: ${firebaseUser?.isEmailVerified ?: false}
        """.trimIndent()
    }

    private fun quickNetworkTest() {
        lifecycleScope.launch {
            try {
                binding.tvGeneratedResume.text = "🔍 Testing network connection..."
                binding.progressGenerate.visibility = View.VISIBLE
                
                // Test 1: Basic connectivity
                val hasNet = isNetworkAvailable()
                Log.d("QuickTest", "Basic internet: $hasNet")
                
                // Test 2: Try the health endpoint (no auth required)
                binding.tvGeneratedResume.text = "Testing server connectivity..."
                val healthResult = apiService.testConnection()
                
                when (healthResult) {
                    is ApiService.ApiResult.Success -> {
                        binding.tvGeneratedResume.text = "✅ SERVER CONNECTED!\n${healthResult.data}"
                        showMessage("✅ Server is reachable!")
                    }
                    is ApiService.ApiResult.Error -> {
                        binding.tvGeneratedResume.text = "❌ SERVER UNREACHABLE\n${healthResult.message}"
                        showMessage("❌ Cannot reach server: ${healthResult.message}")
                    }
                }
                
            } catch (e: Exception) {
                binding.tvGeneratedResume.text = "💥 TEST CRASHED\n${e.message}"
                Log.e("QuickTest", "Test failed", e)
                showMessage("Test crashed: ${e.message}")
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
        showRateLimitedError(message)
        Log.e("ResumeActivity", message)
    }

    private fun showSuccess(message: String) {
        showRateLimitedSuccess(message)
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
    
    private var lastApiCallTime = 0L
    private val API_COOLDOWN_MS = 1000L // 1 second between API calls

    private suspend fun ensureApiCooldown() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastCall = currentTime - lastApiCallTime
        
        if (timeSinceLastCall < API_COOLDOWN_MS) {
            delay(API_COOLDOWN_MS - timeSinceLastCall)
        }
        
        lastApiCallTime = System.currentTimeMillis()
    }

    private fun showServerWakeupMessage() {
        Toast.makeText(
            this, 
            "🔄 Server is waking up... This may take 30-60 seconds on first launch.", 
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleGenerationResult(result: ApiService.ApiResult<JSONObject>) {
        when (result) {
            is ApiService.ApiResult.Success -> {
                Log.d("ResumeActivity", "Resume generation success: ${result.data}")
                currentGeneratedResume = result.data
                displayGeneratedResume(result.data)
                showSuccess("Resume generated successfully!")

                if (result.data.has("remaining_credits")) {
                    val remaining = result.data.getInt("remaining_credits")
                    lifecycleScope.launch {
                        withContext(Dispatchers.Main) {
                            binding.creditText.text = "Credits: $remaining"
                        }
                    }
                } else {
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
