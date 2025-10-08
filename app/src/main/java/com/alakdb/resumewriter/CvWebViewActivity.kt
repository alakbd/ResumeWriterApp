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
            
            console.log('Injecting SIMPLIFIED credit control system...');
            
            let resumeButtonBlocked = false;
            
            // Function to disable generate button
            function disableGenerateButton() {
                console.log('Disabling generate button...');
                const buttons = document.querySelectorAll('button');
                let foundButtons = 0;
                
                buttons.forEach(btn => {
                    const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                    if (btnText.includes('generate') && 
                        (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                        console.log('Found generate button to disable:', btnText);
                        btn.disabled = true;
                        btn.style.opacity = '0.5';
                        btn.style.cursor = 'not-allowed';
                        btn.innerHTML = '⏳ Checking Credits...';
                        foundButtons++;
                    }
                });
                
                resumeButtonBlocked = true;
                console.log('Disabled ' + foundButtons + ' generate buttons');
            }
            
            // Function to enable generate button
            function enableGenerateButton() {
                console.log('Enabling generate button...');
                const buttons = document.querySelectorAll('button');
                let foundButtons = 0;
                
                buttons.forEach(btn => {
                    const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                    if (btnText.includes('generate') && 
                        (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                        btn.disabled = false;
                        btn.style.opacity = '1';
                        btn.style.cursor = 'pointer';
                        const originalText = btn.getAttribute('data-original-text') || '✨ Generate Tailored Résumé';
                        btn.innerHTML = originalText;
                        foundButtons++;
                    }
                });
                
                resumeButtonBlocked = false;
                console.log('Enabled ' + foundButtons + ' generate buttons');
            }
            
            // Function to show error message
            function showCreditError(message) {
                console.log('Showing credit error:', message);
                let errorDiv = document.getElementById('android-credit-error');
                if (!errorDiv) {
                    errorDiv = document.createElement('div');
                    errorDiv.id = 'android-credit-error';
                    errorDiv.style.cssText = 'background: #ffebee; color: #c62828; padding: 12px; margin: 10px 0; border-radius: 4px; border: 1px solid #ffcdd2; font-size: 14px;';
                    const mainContent = document.querySelector('.main') || document.body;
                    mainContent.insertBefore(errorDiv, mainContent.firstChild);
                }
                errorDiv.innerHTML = '🚫 <strong>Credit Error:</strong> ' + message;
                
                setTimeout(() => {
                    if (errorDiv && errorDiv.parentNode) {
                        errorDiv.parentNode.removeChild(errorDiv);
                    }
                }, 5000);
            }
            
            // Function to show success message
            function showSuccessMessage(message) {
                console.log('Showing success message:', message);
                let successDiv = document.getElementById('android-success-message');
                if (!successDiv) {
                    successDiv = document.createElement('div');
                    successDiv.id = 'android-success-message';
                    successDiv.style.cssText = 'background: #e8f5e8; color: #2e7d32; padding: 12px; margin: 10px 0; border-radius: 4px; border: 1px solid #c8e6c9; font-size: 14px;';
                    const mainContent = document.querySelector('.main') || document.body;
                    mainContent.insertBefore(successDiv, mainContent.firstChild);
                }
                successDiv.innerHTML = '✅ <strong>Success:</strong> ' + message;
                
                setTimeout(() => {
                    if (successDiv && successDiv.parentNode) {
                        successDiv.parentNode.removeChild(successDiv);
                    }
                }, 5000);
            }
            
            // Store original button texts
            function storeOriginalButtonTexts() {
                const buttons = document.querySelectorAll('button');
                buttons.forEach(btn => {
                    const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                    if (btnText.includes('generate') && 
                        (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                        btn.setAttribute('data-original-text', btn.innerHTML);
                    }
                });
            }
            
            // SIMPLE button interception - just block and call Android
            function setupButtonInterception() {
                document.addEventListener('click', function(e) {
                    const target = e.target;
                    const btnText = (target.textContent || target.innerText || '').toLowerCase();
                    
                    if (target.tagName === 'BUTTON' && 
                        btnText.includes('generate') && 
                        (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                        
                        console.log('=== GENERATE BUTTON CLICK INTERCEPTED ===');
                        console.log('Button text:', btnText);
                        
                        if (resumeButtonBlocked) {
                            console.log('Button already blocked, preventing click');
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            showCreditError('Please wait for current generation to complete');
                            return;
                        }
                        
                        // ALWAYS call Android for credit check
                        if (window.AndroidApp) {
                            console.log('Calling AndroidApp.checkAndUseCredit()');
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            
                            disableGenerateButton();
                            window.AndroidApp.checkAndUseCredit();
                        } else {
                            console.log('AndroidApp not available, allowing normal click');
                            // If Android interface not available, allow normal operation
                        }
                    }
                }, true);
            }
            
            // Initialize
            function initializeCreditControl() {
                console.log('Initializing SIMPLIFIED credit control...');
                storeOriginalButtonTexts();
                setupButtonInterception();
                
                // Show Android app indicator
                const appIndicator = document.createElement('div');
                appIndicator.innerHTML = '<div style="background: #e3f2fd; color: #1565c0; padding: 10px; margin: 10px 0; border-radius: 4px; border: 1px solid #bbdefb; font-size: 14px;">📱 <strong>Mobile App:</strong> 1 Credit deducted per resume generation</div>';
                const mainContent = document.querySelector('.main') || document.body;
                mainContent.insertBefore(appIndicator, mainContent.firstChild);
                
                console.log('Credit control initialized successfully');
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
        android.util.Log.d("WebView", "Simplified credit control script injected")
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

            // DEDUCT CREDIT IMMEDIATELY - This is the key fix
            creditManager.useCreditForResume { success ->
                runOnUiThread {
                    if (success) {
                        // Credit deducted - now trigger generation
                        Toast.makeText(
                            this@CvWebViewActivity,
                            "✅ 1 credit deducted - Generating resume...",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Direct approach: Simply re-enable the button and let the original click happen
                        webView.evaluateJavascript("""
                            console.log('Credit deducted, allowing original click to proceed...');
                            
                            // Re-enable the button first
                            if (window.androidCreditControl) {
                                window.androidCreditControl.enableButton();
                            }
                            
                            // Now find and click the original generate button
                            setTimeout(() => {
                                const buttons = document.querySelectorAll('button');
                                buttons.forEach(btn => {
                                    const btnText = (btn.textContent || btn.innerText || '').toLowerCase();
                                    if (btnText.includes('generate') && 
                                        (btnText.includes('tailored') || btnText.includes('resume') || btnText.includes('cv'))) {
                                        console.log('Clicking generate button:', btnText);
                                        
                                        // Remove any disabled state we added
                                        btn.disabled = false;
                                        btn.style.opacity = '1';
                                        btn.style.pointerEvents = 'auto';
                                        
                                        // Trigger a real click event that will bubble properly
                                        const clickEvent = new MouseEvent('click', {
                                            bubbles: true,
                                            cancelable: true,
                                            view: window
                                        });
                                        
                                        btn.dispatchEvent(clickEvent);
                                    }
                                });
                            }, 100);
                            
                            // Show success message
                            if (window.androidCreditControl) {
                                window.androidCreditControl.showSuccess('1 credit used - generating your resume...');
                            }
                        """.trimIndent(), null)

                    } else {
                        // Credit deduction failed
                        webView.evaluateJavascript(
                            "if (window.androidCreditControl) { window.androidCreditControl.showError('Credit deduction failed'); window.androidCreditControl.enableButton(); }",
                            null
                        )
                    }
                    isGenerating = false
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
