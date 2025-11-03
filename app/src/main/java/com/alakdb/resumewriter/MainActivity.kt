package com.alakdb.resumewriter

import android.app.Dialog
import android.content.Intent
import android.util.Log
import android.os.Bundle
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var userManager: UserManager
    private lateinit var creditManager: CreditManager
    private lateinit var billingManager: BillingManager
    private lateinit var auth: FirebaseAuth

    private var adminTapCount = 0
    private var isBillingInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Firebase Auth
        auth = FirebaseAuth.getInstance()

        // Initialize managers first
        initializeManagers()

        // DEBUG: Check current state with new validation methods
        Log.d("MAIN_ACTIVITY_DEBUG", "=== MAIN ACTIVITY START ===")
        Log.d("MAIN_ACTIVITY_DEBUG", "Firebase User: ${auth.currentUser?.uid ?: "NULL"}")
        
        // Use new debug methods
        userManager.debugUserState()
        creditManager.debugCreditState()
        
        Log.d("MAIN_ACTIVITY_DEBUG", "User Data Persisted: ${userManager.isUserDataPersisted()}")
        Log.d("MAIN_ACTIVITY_DEBUG", "Credit Data Persisted: ${creditManager.isCreditDataPersisted()}")
        Log.d("MAIN_ACTIVITY_DEBUG", "=== MAIN ACTIVITY END ===")

        // Check authentication - redirect to login if not authenticated
        if (!checkAuthentication()) {
            return // Will start LoginActivity and finish this one
        }

        // Only initialize app if user is properly authenticated
        initializeApp()
    }

    private fun initializeManagers() {
        userManager = UserManager(this)
        creditManager = CreditManager(this)
        billingManager = BillingManager(this, creditManager)
    }

    private fun checkAuthentication(): Boolean {
        // Check if fresh install - force login
        if (userManager.isFreshInstall()) {
            Log.d("MainActivity", "🚨 Fresh install detected - forcing login")
            redirectToLogin()
            return false
        }
        
        // NEW: Check if user data is properly persisted
        if (!userManager.isUserDataPersisted()) {
            Log.w("MainActivity", "⚠️ User data not persisted - attempting emergency sync")
            val syncSuccess = userManager.emergencySyncWithFirebase()
            if (!syncSuccess) {
                Log.e("MainActivity", "❌ Emergency sync failed - forcing login")
                redirectToLogin()
                return false
            }
        }
        
        // Check if user is properly logged in
        if (!userManager.isUserLoggedIn()) {
            Log.d("MainActivity", "❌ User not logged in - redirecting to login")
            redirectToLogin()
            return false
        }
        
        Log.d("MainActivity", "✅ User authenticated - proceeding to main screen")
        return true
    }

    private fun redirectToLogin() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun initializeApp() {
        setupClickListeners()
        initializeBilling()
        loadUserData()
        updateAdminIndicator()
        checkEmailVerification()
        
        // NEW: Auto-resync if needed
        userManager.autoResyncIfNeeded()
    }

    private fun setupClickListeners() {
        // Generate CV Button - Opens WebView without deducting credit
        binding.btnGenerateCv.setOnClickListener { generateCV() }

        // Purchase Buttons
        binding.btnBuy3Cv.setOnClickListener {
            if (isBillingInitialized) purchaseProduct("cv_package_3")
            else showMessage("Store not ready. Please wait...")
        }

        binding.btnBuy8Cv.setOnClickListener {
            if (isBillingInitialized) purchaseProduct("cv_package_8")
            else showMessage("Store not ready. Please wait...")
        }

        // Sample Preview Buttons - FIXED: Proper implementation
        binding.btnViewSampleCv.setOnClickListener {
            showHtmlDialog("Sample CV", SAMPLE_CV_HTML)
        }

        binding.btnViewSampleJob.setOnClickListener {
            showHtmlDialog("Sample Job Description", SAMPLE_JOB_HTML)
        }

        binding.btnGenerateSampleResume.setOnClickListener {
            showHtmlDialog("Sample Generated Resume", GENERATED_SAMPLE_RESUME_HTML)
        }

        // Admin Access
        binding.tvVersion.setOnClickListener { handleAdminTap() }
        binding.btnAdminAccess.setOnClickListener { navigateToAdmin() }

        // Logout Button - UPDATED with proper data clearing
        binding.btnLogout.setOnClickListener {
            showLogoutConfirmation()
        }

        // Refresh Button - UPDATED with force sync
        binding.btnRefresh.setOnClickListener {
            binding.btnRefresh.isEnabled = false
            binding.btnRefresh.text = "Refreshing..."
            
            // Use force sync for both managers
            userManager.forceSyncWithFirebase { userSuccess ->
                creditManager.forceSyncWithFirebase { creditSuccess, credits ->
                    binding.btnRefresh.isEnabled = true
                    binding.btnRefresh.text = "Refresh"
                    
                    if (userSuccess && creditSuccess) {
                        updateCreditDisplay()
                        showMessage("Data refreshed successfully!")
                        Log.d("MainActivity", "Force sync completed successfully")
                    } else {
                        updateCreditDisplay()
                        showMessage("Partial sync. Using available data.")
                        Log.w("MainActivity", "Force sync partially failed - User: $userSuccess, Credits: $creditSuccess")
                    }
                }
            }
        }

        // NEW: Debug button (optional - you can remove this in production)
        binding.tvVersion.setOnLongClickListener {
            showDebugInfo()
            true
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        Log.d("MainActivity", "Performing logout with data clearing")
        
        // Clear all data from both managers
        userManager.clearUserData()
        creditManager.clearCreditData()
        
        // Firebase logout
        auth.signOut()
        
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    private fun handleAdminTap() {
        adminTapCount++
        when (adminTapCount) {
            1 -> binding.tvVersion.text = "v1.0 (1/3)"
            2 -> binding.tvVersion.text = "v1.0 (2/3)"
            3 -> {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
                navigateToAdmin()
            }
        }
        binding.tvVersion.postDelayed({
            if (adminTapCount in 1..2) {
                adminTapCount = 0
                binding.tvVersion.text = "v1.0"
            }
        }, 2000)
    }

    // FIXED: Proper implementation of showHtmlDialog
    private fun showHtmlDialog(title: String, htmlContent: String) {
        try {
            val dialog = Dialog(this)
            dialog.setContentView(R.layout.dialog_webview)
            dialog.setTitle(title)
            dialog.window?.setBackgroundDrawableResource(android.R.color.white)

            val webView = dialog.findViewById<WebView>(R.id.webview)
            val btnClose = dialog.findViewById<android.widget.Button>(R.id.btn_close)

            webView.settings.javaScriptEnabled = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.useWideViewPort = true
            webView.settings.domStorageEnabled = true
            
            // Load the HTML content with proper styling
            val styledHtml = """
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { 
                            font-family: Arial, sans-serif; 
                            line-height: 1.6; 
                            margin: 20px; 
                            color: #333;
                        }
                        h2 { color: #2c3e50; border-bottom: 2px solid #3498db; padding-bottom: 5px; }
                        h3 { color: #34495e; margin-top: 20px; }
                        b { color: #2c3e50; }
                        ul { padding-left: 20px; }
                        li { margin-bottom: 5px; }
                        .header { text-align: center; margin-bottom: 20px; }
                        .section { margin-bottom: 15px; }
                        .section-title { 
                            background-color: #f8f9fa; 
                            padding: 8px 12px; 
                            font-weight: bold; 
                            border-left: 4px solid #3498db;
                            margin-bottom: 10px;
                        }
                    </style>
                </head>
                <body>
                    $htmlContent
                </body>
                </html>
            """.trimIndent()
            
            webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)

            btnClose.setOnClickListener { dialog.dismiss() }

            dialog.show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error showing HTML dialog: ${e.message}")
            showMessage("Error loading content. Please try again.")
        }
    }

    private fun initializeBilling() {
        billingManager.initializeBilling { success, _ ->
            isBillingInitialized = success
            if (success) {
                showMessage("Store ready")
                updateProductPrices()
            } else {
                showMessage("Store temporarily unavailable")
                binding.btnBuy3Cv.text = "3 CV Credits - Unavailable"
                binding.btnBuy8Cv.text = "8 CV Credits - Unavailable"
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy8Cv.isEnabled = false
            }
        }
    }

    private fun loadUserData() {
        binding.btnGenerateCv.isEnabled = false
        binding.btnGenerateCv.text = "Loading..."

        // NEW: Use auto-sync method for credits
        creditManager.getCreditsWithAutoSync { credits ->
            binding.btnGenerateCv.isEnabled = true
            updateGenerateButton()

            if (credits >= 0) {
                updateCreditDisplay()
                showMessage("Data loaded successfully")
            } else {
                updateCreditDisplay()
                showMessage("Using cached data")
            }
        }
    }

    private fun generateCV() {
        // NEW: Use the enhanced resume generation check
        if (!creditManager.canGenerateResume()) {
            if (creditManager.getAvailableCredits() <= 0) {
                showMessage("Not enough credits! Please purchase more.")
            } else {
                showMessage("Please wait before generating another resume.")
            }
            return
        }

        // Open the new Resume Generation Activity
        val intent = Intent(this, ResumeGenerationActivity::class.java)
        startActivity(intent)
    }

    private fun purchaseProduct(productId: String) {
        if (!isBillingInitialized) {
            showMessage("Billing not ready. Please try again later.")
            return
        }

        when (productId) {
            "cv_package_3" -> {
                binding.btnBuy3Cv.isEnabled = false
                binding.btnBuy3Cv.text = "Processing..."
            }
            "cv_package_8" -> {
                binding.btnBuy8Cv.isEnabled = false
                binding.btnBuy8Cv.text = "Processing..."
            }
        }

        billingManager.purchaseProduct(this, productId) { success, message ->
            updatePurchaseButtons()
            showMessage(message)
            if (success) {
                // NEW: Force sync after purchase to ensure credits are updated
                creditManager.forceSyncWithFirebase { syncSuccess, credits ->
                    updateCreditDisplay()
                    if (syncSuccess) {
                        showMessage("Credits updated successfully!")
                    } else {
                        showMessage("Purchase completed but sync failed. Credits will update soon.")
                    }
                }
            }
        }
    }

    private fun updatePurchaseButtons() {
        if (isBillingInitialized) {
            binding.btnBuy3Cv.isEnabled = true
            binding.btnBuy8Cv.isEnabled = true
            updateProductPrices()
        }
    }

    private fun updateProductPrices() {
        val price3 = billingManager.getProductPrice("cv_package_3")
        val price8 = billingManager.getProductPrice("cv_package_8")

        binding.btnBuy3Cv.text = "3 CV Credits - ${if (price3 != "Not available") price3 else "€5"}"
        binding.btnBuy8Cv.text = "8 CV Credits - ${if (price8 != "Not available") price8 else "€10"}"
    }

    private fun updateCreditDisplay() {
        binding.tvAvailableCredits.text = "Available CV Credits: ${creditManager.getAvailableCredits()}"
        binding.tvCreditStats.text = "Used: ${creditManager.getUsedCredits()} | Total Earned: ${creditManager.getTotalCredits()}"
        updateGenerateButton()
    }

    private fun updateGenerateButton() {
        val available = creditManager.getAvailableCredits()
        val canGenerate = creditManager.canGenerateResume()
        
        binding.btnGenerateCv.isEnabled = canGenerate
        binding.btnGenerateCv.text = when {
            !canGenerate && available > 0 -> "Please Wait..."
            available > 0 -> "✨ Generate Tailored Résumé"
            else -> "No Credits Available"
        }
    }

    private fun navigateToAdmin() {
        val intent = if (creditManager.isAdminMode()) {
            Intent(this, AdminPanelActivity::class.java)
        } else {
            Intent(this, AdminLoginActivity::class.java)
        }
        startActivity(intent)
    }

    private fun updateAdminIndicator() {
        val isAdmin = creditManager.isAdminMode()
        binding.tvAdminIndicator.visibility = if (isAdmin) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnAdminAccess.visibility = if (isAdmin) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showMessage(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Exit") { _, _ -> finishAffinity() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::creditManager.isInitialized) {
            // NEW: Auto-resync when returning to the app
            userManager.autoResyncIfNeeded()
            
            // Refresh credit display when returning from other activities
            creditManager.syncWithFirebase { success, _ ->
                updateCreditDisplay()
                updateAdminIndicator()
                
                // NEW: Reset resume cooldown when returning from WebView
                creditManager.resetResumeCooldown()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::billingManager.isInitialized) billingManager.destroy()
    }

    private fun checkEmailVerification() {
        val user = userManager.getCurrentFirebaseUser()
        
        if (user != null && !user.isEmailVerified) {
            showVerificationReminder(user.email)
        }
    }

    private fun showVerificationReminder(email: String?) {
        AlertDialog.Builder(this)
            .setTitle("Email Not Verified")
            .setMessage("Your email address ($email) is not verified. Please check your inbox for the verification link. Some features may be limited until you verify your email.")
            .setPositiveButton("Resend Verification") { dialog, _ ->
                resendVerification()
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun resendVerification() {
        userManager.resendVerificationEmail { success, error ->
            if (success) {
                showMessage("Verification email sent!")
            } else {
                showMessage("Failed to send verification: $error")
            }
        }
    }

    // NEW: Debug info dialog
    private fun showDebugInfo() {
        val userState = """
            === DEBUG INFO ===
            Firebase UID: ${auth.currentUser?.uid ?: "NULL"}
            Local UID: ${userManager.getCurrentUserId() ?: "NULL"}
            User Data Persisted: ${userManager.isUserDataPersisted()}
            Credit Data Persisted: ${creditManager.isCreditDataPersisted()}
            Available Credits: ${creditManager.getAvailableCredits()}
            Can Generate Resume: ${creditManager.canGenerateResume()}
            Admin Mode: ${creditManager.isAdminMode()}
            Billing Ready: $isBillingInitialized
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Debug Information")
            .setMessage(userState)
            .setPositiveButton("OK", null)
            .show()
    }

    companion object {
        private const val SAMPLE_CV_HTML = """
            <h2>John Doe</h2>
            <p><b>Email:</b> john.doe@email.com | <b>Phone:</b> +1 234 567 8901</p>
            <h3>Professional Summary</h3>
            <p>Full Stack Developer with over 5 years of experience in building scalable web applications and leading development teams. Strong expertise in JavaScript, Python, and cloud technologies.</p>
            <h3>Technical Skills</h3>
            <ul>
                <li><b>Frontend:</b> React, Angular, Vue.js, TypeScript</li>
                <li><b>Backend:</b> Node.js, Python (Flask, FastAPI), Java Spring</li>
                <li><b>Databases:</b> PostgreSQL, MongoDB, Redis</li>
                <li><b>Cloud & DevOps:</b> AWS, Docker, CI/CD Pipelines, Kubernetes</li>
            </ul>
            <h3>Professional Experience</h3>
            <p><b>Senior Software Engineer — ABC Tech</b> (2020–Present)</p>
            <ul>
                <li>Led development of scalable SaaS products serving 50,000+ users</li>
                <li>Reduced application load time by 40% through performance optimization</li>
                <li>Mentored 3 junior developers and established code review processes</li>
            </ul>
            <p><b>Software Developer — XYZ Labs</b> (2017–2020)</p>
            <ul>
                <li>Developed full-stack applications and RESTful APIs using Flask and PostgreSQL</li>
                <li>Collaborated in Agile teams to deliver multiple successful client projects</li>
                <li>Implemented automated testing reducing bugs by 60%</li>
            </ul>
            <h3>Education</h3>
            <p><b>B.Sc. in Computer Science</b> — University of Technology (2013-2017)</p>
            <p>Graduated Magna Cum Laude with specialization in Software Engineering</p>
            <h3>Skills</h3>
            <ul>
                <li><b>Programming Languages:</b> JavaScript, Python, Java, SQL</li>
                <li><b>Frameworks:</b> React, Node.js, Django, Express.js</li>
                <li><b>Tools:</b> Git, Docker, AWS, Jenkins, MongoDB</li>
                <li><b>Certifications:</b> AWS Certified Developer, Scrum Master</li>
            </ul>
        """

        private const val SAMPLE_JOB_HTML = """
            <h2>Senior Full Stack Developer Position</h2>
            <p><b>Company:</b> Tech Innovations Inc.<br>
            <b>Location:</b> Remote / San Francisco, CA<br>
            <b>Salary Range:</b> $120,000 - $150,000</p>
            
            <h3>Job Description</h3>
            <p>We are looking for an experienced Senior Full Stack Developer to join our dynamic team and help build the next generation of our platform. The ideal candidate will have strong technical skills and leadership experience.</p>
            
            <h3>Key Responsibilities</h3>
            <ul>
                <li>Design, develop, and maintain scalable web applications using modern technologies</li>
                <li>Collaborate with cross-functional teams to define, design, and ship new features</li>
                <li>Write clean, maintainable, and efficient code following best practices</li>
                <li>Implement security and data protection measures</li>
                <li>Optimize applications for maximum speed and scalability</li>
                <li>Mentor junior developers and conduct code reviews</li>
                <li>Participate in Agile development processes and sprint planning</li>
            </ul>
            
            <h3>Requirements</h3>
            <ul>
                <li>5+ years of experience in full-stack development</li>
                <li>Proficiency with JavaScript frameworks (React, Angular, or Vue)</li>
                <li>Strong experience with Node.js and Python backend development</li>
                <li>Familiarity with MongoDB and PostgreSQL databases</li>
                <li>Experience with cloud services (AWS, Azure, or GCP)</li>
                <li>Knowledge of Git and CI/CD pipelines</li>
                <li>Excellent problem-solving skills and attention to detail</li>
                <li>Strong communication and teamwork skills</li>
            </ul>
            
            <h3>Preferred Qualifications</h3>
            <ul>
                <li>Experience with microservices architecture</li>
                <li>Knowledge of containerization (Docker, Kubernetes)</li>
                <li>Previous experience in a tech lead or mentoring role</li>
                <li>Bachelor's degree in Computer Science or related field</li>
            </ul>
        """

        private const val GENERATED_SAMPLE_RESUME_HTML = """
            <div class="header">
                <h1>John Doe</h1>
                <p>Email: john.doe@example.com | Phone: (555) 123-4567 | LinkedIn: linkedin.com/in/johndoe</p>
            </div>

            <div class="section">
                <div class="section-title">PROFESSIONAL SUMMARY</div>
                <p>Experienced Senior Full Stack Developer with 5+ years of expertise in JavaScript frameworks, Node.js, and cloud technologies. Proven track record in designing and delivering scalable web applications, optimizing performance, and leading development teams.</p>
            </div>

            <div class="section">
                <div class="section-title">EXPERIENCE</div>
                
                <p><b>Senior Full Stack Developer, Tech Solutions Inc.</b> (2020-Present)</p>
                <ul>
                    <li>Designed and maintained scalable web applications using React.js and Node.js</li>
                    <li>Led a team of 4 developers to successfully deliver a customer portal, boosting user engagement by 35%</li>
                    <li>Implemented CI/CD pipelines, reducing deployment time by 40%</li>
                    <li>Enhanced application performance by 25% through database query optimization</li>
                </ul>

                <p><b>Software Developer, Innovate Labs</b> (2018-2020)</p>
                <ul>
                    <li>Developed RESTful APIs with Python and Django framework</li>
                    <li>Collaborated with UX designers to create intuitive user interfaces</li>
                    <li>Achieved a 20% reduction in server costs through code optimization and caching strategies</li>
                </ul>
            </div>

            <div class="section">
                <div class="section-title">EDUCATION</div>
                <p><b>Bachelor of Science in Computer Science</b><br>
                University of Technology, Graduated 2018</p>
            </div>

            <div class="section">
                <div class="section-title">SKILLS</div>
                <ul>
                    <li><b>Programming Languages:</b> JavaScript, Python, Java, SQL</li>
                    <li><b>Frameworks:</b> React, Node.js, Django, Express.js</li>
                    <li><b>Tools:</b> Git, Docker, AWS, Jenkins, MongoDB</li>
                    <li><b>Certifications:</b> AWS Certified Developer, Scrum Master</li>
                </ul>
            </div>
        """
    }
}
