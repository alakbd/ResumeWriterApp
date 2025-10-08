package com.alakdb.resumewriter

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var creditManager: CreditManager

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 101
    private var isGenerating = false
    
    private val loadTimeoutHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable {
        if (progressBar.visibility == View.VISIBLE) {
            progressBar.visibility = View.GONE
            Toast.makeText(this, "Page loaded with reduced functionality", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)
        
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        creditManager = CreditManager(this)

        // Configure WebView
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        
        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
    		databaseEnabled = true
        }

        // Clear cache and history
        webView.clearCache(true)
        webView.clearHistory()
        
        // Add JavaScript interface
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
                
                // Inject credit control JavaScript
                webView.postDelayed({
                    injectCreditControlScript()
                }, 1500)
            }

            override fun onReceivedError(
                view: WebView?, 
                request: WebResourceRequest?, 
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
                progressBar.visibility = View.GONE
                
                Toast.makeText(
                    this@CvWebViewActivity, 
                    "Page loading error: ${error?.description}", 
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return if (url.contains(BuildConfig.API_BASE_URL)) {
                    false
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                }
            }
        }

        // WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 80) {
                    progressBar.visibility = View.GONE
                } else if (newProgress < 100 && progressBar.visibility != View.VISIBLE) {
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                android.util.Log.d(
                    "WebViewConsole",
                    "${consoleMessage?.message()} (Line: ${consoleMessage?.lineNumber()})"
                )
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@CvWebViewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                return try {
                    if (intent != null) {
                        startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                        true
                    } else false
                } catch (e: Exception) {
                    this@CvWebViewActivity.filePathCallback = null
                    false
                }
            }
        }

        // Download listener
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val finalFileName = when {
                guessedFileName.endsWith(".pdf", true) -> guessedFileName
                guessedFileName.endsWith(".docx", true) -> guessedFileName
                mimeType.contains("pdf") -> "resume_${System.currentTimeMillis()}.pdf"
                mimeType.contains("word") || mimeType.contains("officedocument") -> "resume_${System.currentTimeMillis()}.docx"
                else -> "resume_${System.currentTimeMillis()}.docx"
            }

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFileName)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, "Downloading $finalFileName...", Toast.LENGTH_LONG).show()
        }

        // Enable debugging
        WebView.setWebContentsDebuggingEnabled(true)

        // Set load timeout
        loadTimeoutHandler.postDelayed(loadTimeoutRunnable, 15000)

        // Load the URL
        webView.loadUrl("${BuildConfig.API_BASE_URL}?fromApp=true&embedded=true&androidApp=true")
    }

    private fun injectCreditControlScript() {
        val creditControlScript = """
            (function() {
                'use strict';
            
                console.log('Injecting enhanced credit control system...');
            
                let resumeButtonBlocked = false;
                let originalButtonHandler = null;
            
            // Function to disable generate button
            function disableGenerateButton() {
                const buttons = document.querySelectorAll('button');
                buttons.forEach(btn => {
                    if (btn.textContent.includes('Generate Tailored') || 
                        btn.textContent.includes('Tailored Resume')) {
                        // Store the original onclick handler
                        if (btn.onclick) {
                            originalButtonHandler = btn.onclick;
                        }
                        btn.disabled = true;
                        btn.style.opacity = '0.5';
                        btn.style.cursor = 'not-allowed';
                        btn.innerHTML = '⏳ Checking Credits...';
                    }
                });
                resumeButtonBlocked = true;
            }
            
            // Function to enable generate button
            function enableGenerateButton() {
                const buttons = document.querySelectorAll('button');
                buttons.forEach(btn => {
                    if (btn.textContent.includes('Generate Tailored') || 
                        btn.textContent.includes('Tailored Resume')) {
                        btn.disabled = false;
                        btn.style.opacity = '1';
                        btn.style.cursor = 'pointer';
                        const originalText = btn.getAttribute('data-original-text') || '✨ Generate Tailored Résumé';
                        btn.innerHTML = originalText;
                    }
                });
                resumeButtonBlocked = false;
            }
            
            // ... rest of your existing JavaScript functions ...
            
            // Enhanced button interception
            function setupButtonInterception() {
                document.addEventListener('click', function(e) {
                    const target = e.target;
                    const btnText = target.textContent || target.innerText;
                    
                    if (target.tagName === 'BUTTON' && 
                        (btnText.includes('Generate Tailored') || 
                         btnText.includes('Tailored Resume'))) {
                        
                        console.log('Generate button clicked - checking credits...');
                        
                        if (resumeButtonBlocked) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            showCreditError('Please wait for current generation to complete');
                            return;
                        }
                        
                        // ALWAYS check with Android app for EACH generation
                        if (window.AndroidApp) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            
                            disableGenerateButton();
                            
                            // Request credit check from Android for EACH generation
                            window.AndroidApp.checkAndUseCredit();
                        } else {
                            console.log('AndroidApp interface not available');
                            showCreditError('App communication error. Please restart the app.');
                            enableGenerateButton();
                        }
                    }
                }, true);
            }
            
            // Initialize the credit control system
            function initializeCreditControl() {
                console.log('Initializing enhanced credit control system...');
                storeOriginalButtonTexts();
                setupButtonInterception();
                
                // Enhanced Android app indicator
                const appIndicator = document.createElement('div');
                appIndicator.innerHTML = '<div style="background: #e3f2fd; color: #1565c0; padding: 10px; margin: 10px 0; border-radius: 4px; border: 1px solid #bbdefb; font-size: 14px;">📱 <strong>Mobile App:</strong> 1 Credit will be deducted for each successful resume generation</div>';
                const mainContent = document.querySelector('.main') || document.body;
                mainContent.insertBefore(appIndicator, mainContent.firstChild);
                
                // Add debug info
                console.log('Credit control system initialized successfully');
                console.log('Available buttons:', document.querySelectorAll('button').length);
            }
            
            // Expose functions to Android
            window.androidCreditControl = {
                enableButton: enableGenerateButton,
                disableButton: disableGenerateButton,
                showError: showCreditError,
                showSuccess: showSuccessMessage
            };
            
            // Start initialization
            initializeCreditControl();
            
        })();
    """.trimIndent()

    webView.evaluateJavascript(creditControlScript) { _ ->
        android.util.Log.d("WebView", "Enhanced credit control script injected")
    }
}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = when {
                resultCode == RESULT_OK && data != null -> {
                    arrayOf(data.data ?: return)
                }
                resultCode == RESULT_OK -> null
                else -> null
            }
        
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
        webView.stopLoading()
        webView.destroy()
    }

    inner class AndroidBridge {

    @JavascriptInterface
fun checkAndUseCredit() {
    runOnUiThread {
        try {
            if (isGenerating) {
                webView.evaluateJavascript(
                    "if (window.androidCreditControl) { window.androidCreditControl.showError('Generation already in progress'); window.androidCreditControl.enableButton(); }",
                    null
                )
                return@runOnUiThread
            }

            // Quick credit check first
            if (creditManager.getAvailableCredits() <= 0) {
                webView.evaluateJavascript(
                    "if (window.androidCreditControl) { window.androidCreditControl.showError('Not enough credits! Please purchase more.'); window.androidCreditControl.enableButton(); }",
                    null
                )
                return@runOnUiThread
            }

            // Then check cooldown
            if (!creditManager.canGenerateResume()) {
                webView.evaluateJavascript(
                    "if (window.androidCreditControl) { window.androidCreditControl.showError('Please wait 30 seconds between generations'); window.androidCreditControl.enableButton(); }",
                    null
                )
                return@runOnUiThread
            }

            isGenerating = true
            
            // FIRST: Trigger the actual resume generation on the web page
            webView.evaluateJavascript("""
                // Try multiple approaches to trigger generation
                function triggerResumeGeneration() {
                    console.log('Attempting to trigger resume generation...');
                    
                    // Approach 1: Look for and click the actual generate button
                    const generateButtons = document.querySelectorAll('button');
                    let foundButton = false;
                    
                    generateButtons.forEach(btn => {
                        const btnText = btn.textContent || btn.innerText;
                        if (btnText.includes('Generate Tailored') || 
                            btnText.includes('Tailored Resume') ||
                            btnText.includes('Generate') && btnText.includes('Resume')) {
                            console.log('Found generate button:', btnText);
                            
                            // Create and dispatch a proper click event
                            const clickEvent = new MouseEvent('click', {
                                view: window,
                                bubbles: true,
                                cancelable: true
                            });
                            btn.dispatchEvent(clickEvent);
                            foundButton = true;
                        }
                    });
                    
                    // Approach 2: Look for form submission
                    if (!foundButton) {
                        const forms = document.querySelectorAll('form');
                        forms.forEach(form => {
                            if (form.innerHTML.includes('generate') || form.innerHTML.includes('resume')) {
                                console.log('Found resume form, submitting...');
                                form.submit();
                                foundButton = true;
                            }
                        });
                    }
                    
                    // Approach 3: Look for and call generation functions
                    if (!foundButton) {
                        // Common function names for resume generation
                        const functionNames = [
                            'generateResume', 'generateCV', 'createResume', 
                            'startGeneration', 'generateTailoredResume'
                        ];
                        
                        functionNames.forEach(funcName => {
                            if (typeof window[funcName] === 'function') {
                                console.log('Calling generation function:', funcName);
                                window[funcName]();
                                foundButton = true;
                            }
                        });
                    }
                    
                    return foundButton;
                }
                
                // Execute the generation trigger
                const generationStarted = triggerResumeGeneration();
                
                if (generationStarted) {
                    console.log('Resume generation triggered successfully');
                    if (window.androidCreditControl) {
                        window.androidCreditControl.showSuccess('1 credit deducted - generating your resume...');
                    }
                } else {
                    console.log('Could not trigger resume generation automatically');
                    if (window.androidCreditControl) {
                        window.androidCreditControl.showError('Could not start generation. Please try again.');
                        window.androidCreditControl.enableButton();
                    }
                    // Notify Android to revert credit since generation failed
                    if (window.AndroidApp) {
                        AndroidApp.revertCreditDeduction();
                    }
                }
            """.trimIndent(), null)

            // SECOND: Only deduct credit if generation was successfully triggered
            // We'll deduct credit after confirming generation started
            Handler(Looper.getMainLooper()).postDelayed({
                // Double-check that generation actually started before deducting credit
                webView.evaluateJavascript("""
                    // Check if generation indicators are visible (loading spinners, progress bars, etc.)
                    const loadingIndicators = document.querySelectorAll('.loading, .spinner, .progress, [aria-busy=true]');
                    const isGeneratingVisible = loadingIndicators.length > 0;
                    
                    // Also check if download buttons or success messages appeared
                    const successElements = document.querySelectorAll('.success, .download, .completed');
                    const isSuccessVisible = successElements.length > 0;
                    
                    isGeneratingVisible || isSuccessVisible;
                """.trimIndent()) { result ->
                    val generationConfirmed = result == "true"
                    
                    if (generationConfirmed) {
                        // Deduct credit only after confirming generation started
                        creditManager.useCreditForResume { success ->
                            runOnUiThread {
                                if (success) {
                                    Toast.makeText(
                                        this@CvWebViewActivity,
                                        "✅ 1 credit deducted - Resume generation started!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    webView.evaluateJavascript(
                                        "if (window.androidCreditControl) { window.androidCreditControl.showError('Credit deduction failed'); window.androidCreditControl.enableButton(); }",
                                        null
                                    )
                                }
                                isGenerating = false
                            }
                        }
                    } else {
                        // Generation didn't start, don't deduct credit
                        runOnUiThread {
                            webView.evaluateJavascript(
                                "if (window.androidCreditControl) { window.androidCreditControl.showError('Generation failed to start. Credit not deducted.'); window.androidCreditControl.enableButton(); }",
                                null
                            )
                            isGenerating = false
                        }
                    }
                }
            }, 2000) // Wait 2 seconds to confirm generation started

        } catch (e: Exception) {
            webView.evaluateJavascript(
                "if (window.androidCreditControl) { window.androidCreditControl.showError('System error: ${e.message}'); window.androidCreditControl.enableButton(); }",
                null
            )
            isGenerating = false
        }
    }
}

    @JavascriptInterface
    fun notifyResumeGenerated() {
        runOnUiThread {
            Toast.makeText(
                this@CvWebViewActivity,
                "✅ Resume generated successfully! 1 credit used.",
                Toast.LENGTH_SHORT
            ).show()

            webView.evaluateJavascript(
                "if (window.androidCreditControl) { window.androidCreditControl.showSuccess('Resume generated successfully! 1 credit used. You can download it now.'); }",
                null
            )

            webView.evaluateJavascript(
                "if (window.androidCreditControl) { window.androidCreditControl.enableButton(); }",
                null
            )
        }
    }

    @JavascriptInterface
    fun log(message: String) {
        android.util.Log.d("WebViewJS", message)
    }

    @JavascriptInterface
    fun getRemainingCredits(): Int {
        return creditManager.getAvailableCredits()
        }
    }
}
