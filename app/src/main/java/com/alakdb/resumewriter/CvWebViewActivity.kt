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
            
            // ... (keep all your existing functions: disableGenerateButton, enableGenerateButton, etc.)
            
            // Enhanced button interception with better debugging
            function setupButtonInterception() {
                document.addEventListener('click', function(e) {
                    const target = e.target;
                    const btnText = (target.textContent || target.innerText || '').toLowerCase();
                    
                    if (target.tagName === 'BUTTON' && 
                        (btnText.includes('generate tailored') || 
                         btnText.includes('tailored resume') ||
                         btnText.includes('generate resume') ||
                         btnText.includes('generate cv'))) {
                        
                        console.log('=== GENERATE BUTTON CLICKED ===');
                        console.log('Button text:', btnText);
                        console.log('Button HTML:', target.outerHTML);
                        console.log('Button classes:', target.className);
                        console.log('Button disabled:', target.disabled);
                        
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
                            
                            console.log('Calling AndroidApp.checkAndUseCredit()');
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
            
            // ... (rest of your existing initialization code)
            
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

            // Quick credit check first (just for UI feedback)
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
            
            // STEP 1: First try to trigger the resume generation
            webView.evaluateJavascript("""
                console.log('Attempting to trigger resume generation...');
                
                // Enhanced function to trigger generation
                function triggerResumeGeneration() {
                    let generationTriggered = false;
                    
                    // Method 1: Look for generate buttons and click them
                    const buttons = document.querySelectorAll('button');
                    for (let btn of buttons) {
                        const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                        if (btnText.includes('generate') && 
                            (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                            console.log('Found generate button:', btnText);
                            
                            // Store original state
                            const originalDisabled = btn.disabled;
                            const originalOpacity = btn.style.opacity;
                            
                            // Ensure button is clickable
                            btn.disabled = false;
                            btn.style.opacity = '1';
                            btn.style.pointerEvents = 'auto';
                            
                            // Create and dispatch proper click event
                            const clickEvent = new MouseEvent('click', {
                                bubbles: true,
                                cancelable: true,
                                view: window
                            });
                            
                            btn.dispatchEvent(clickEvent);
                            
                            // Restore original state
                            setTimeout(() => {
                                btn.disabled = originalDisabled;
                                btn.style.opacity = originalOpacity;
                            }, 100);
                            
                            generationTriggered = true;
                            break;
                        }
                    }
                    
                    // Method 2: Look for form submissions
                    if (!generationTriggered) {
                        const forms = document.querySelectorAll('form');
                        for (let form of forms) {
                            const formHtml = form.innerHTML.toLowerCase();
                            if (formHtml.includes('generate') || formHtml.includes('resume')) {
                                console.log('Found resume form, submitting...');
                                form.submit();
                                generationTriggered = true;
                                break;
                            }
                        }
                    }
                    
                    // Method 3: Look for specific generation functions
                    if (!generationTriggered) {
                        const functionNames = [
                            'generateResume', 'generateCV', 'createResume', 
                            'startGeneration', 'generateTailoredResume',
                            'generateResumeClick', 'generateCvClick'
                        ];
                        
                        for (let funcName of functionNames) {
                            if (typeof window[funcName] === 'function') {
                                console.log('Calling generation function:', funcName);
                                window[funcName]();
                                generationTriggered = true;
                                break;
                            }
                        }
                    }
                    
                    return generationTriggered;
                }
                
                // Execute and return result
                const result = triggerResumeGeneration();
                result;
            """.trimIndent()) { generationResult ->
                // STEP 2: Check if generation was triggered successfully
                val generationTriggered = generationResult == "true"
                
                if (generationTriggered) {
                    // STEP 3: Wait a moment to confirm generation actually started
                    Handler(Looper.getMainLooper()).postDelayed({
                        webView.evaluateJavascript("""
                            // Check multiple indicators that generation is in progress
                            function isGenerationInProgress() {
                                // Check for loading indicators
                                const loadingSelectors = [
                                    '.loading', '.spinner', '.progress', 
                                    '[aria-busy=true]', '.generating', '.processing',
                                    '.btn-primary:disabled', 'button:disabled',
                                    '.progress-bar', '.load'
                                ];
                                
                                for (let selector of loadingSelectors) {
                                    const elements = document.querySelectorAll(selector);
                                    if (elements.length > 0) {
                                        console.log('Found loading indicator:', selector);
                                        return true;
                                    }
                                }
                                
                                // Check for text changes that indicate generation started
                                const buttons = document.querySelectorAll('button');
                                for (let btn of buttons) {
                                    const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                                    if (btnText.includes('generating') || 
                                        btnText.includes('processing') ||
                                        btnText.includes('creating') ||
                                        btnText.includes('please wait')) {
                                        console.log('Found generation text:', btnText);
                                        return true;
                                    }
                                }
                                
                                // Check for any network requests that might indicate generation
                                if (window.performance) {
                                    const resources = window.performance.getEntriesByType('resource');
                                    const recentResources = resources.filter(r => 
                                        (r.name.includes('generate') || r.name.includes('resume')) &&
                                        (performance.now() - r.startTime) < 3000
                                    );
                                    if (recentResources.length > 0) {
                                        console.log('Found recent generation requests');
                                        return true;
                                    }
                                }
                                
                                return false;
                            }
                            
                            isGenerationInProgress();
                        """.trimIndent()) { confirmationResult ->
                            val generationConfirmed = confirmationResult == "true"
                            
                            runOnUiThread {
                                if (generationConfirmed) {
                                    // STEP 4: FINALLY deduct the credit since generation started
                                    creditManager.useCreditForResume { success ->
                                        runOnUiThread {
                                            if (success) {
                                                Toast.makeText(
                                                    this@CvWebViewActivity,
                                                    "✅ 1 credit deducted - Resume generation in progress!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                
                                                // Notify web page
                                                webView.evaluateJavascript(
                                                    "if (window.androidCreditControl) { window.androidCreditControl.showSuccess('1 credit deducted - Generating your resume...'); }",
                                                    null
                                                )
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
                                    // Generation didn't start properly
                                    webView.evaluateJavascript(
                                        "if (window.androidCreditControl) { window.androidCreditControl.showError('Generation failed to start. Please try again.'); window.androidCreditControl.enableButton(); }",
                                        null
                                    )
                                    isGenerating = false
                                }
                            }
                        }
                    }, 1500) // Wait 1.5 seconds to confirm generation started
                    
                } else {
                    // Could not trigger generation at all
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if (window.androidCreditControl) { window.androidCreditControl.showError('Could not start resume generation. Please try again.'); window.androidCreditControl.enableButton(); }",
                            null
                        )
                        isGenerating = false
                    }
                }
            }

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
