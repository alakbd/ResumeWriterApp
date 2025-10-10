package com.alakdb.resumewriter

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
                
                // Inject credit control JavaScript with delay
                Handler(Looper.getMainLooper()).postDelayed({
                    injectCreditControlScript()
                }, 2000)
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
                
                console.log('=== SIMPLE CREDIT CONTROL INJECTED ===');
                
                let creditProcessed = false;
                let isChecking = false;
                
                // Simple button click handler
                document.addEventListener('click', function(e) {
                    try {
                        var target = e.target;
                        if (target && target.tagName === 'BUTTON') {
                            var text = (target.textContent || target.innerText || '').toLowerCase().trim();
                            
                            // Check if this is the generate tailored resume button
                            if (text.includes('generate') && 
                                text.includes('tailored') && 
                                text.includes('resume') && 
                                !text.includes('sample') && 
                                !text.includes('preview')) {
                                
                                console.log('=== GENERATE TAILORED RESUME BUTTON CLICKED ===');
                                console.log('Credit processed:', creditProcessed);
                                console.log('Is checking:', isChecking);
                                
                                // If credit already processed for this session, allow normal behavior
                                if (creditProcessed) {
                                    console.log('Credit already processed - allowing normal generation');
                                    return;
                                }
                                
                                // If already checking credits, prevent multiple checks
                                if (isChecking) {
                                    console.log('Already checking credits - preventing click');
                                    e.preventDefault();
                                    e.stopImmediatePropagation();
                                    return;
                                }
                                
                                // First click - process credit check
                                console.log('First click - processing credit check');
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                
                                // Store original button state
                                if (!target.hasAttribute('data-original-text')) {
                                    target.setAttribute('data-original-text', target.innerHTML);
                                }
                                
                                // Show checking state
                                target.innerHTML = '⏳ Checking Credits...';
                                target.disabled = true;
                                target.style.opacity = '0.6';
                                isChecking = true;
                                
                                // Call Android bridge
                                if (window.AndroidApp && typeof window.AndroidApp.checkAndUseCredit === 'function') {
                                    console.log('Calling AndroidApp.checkAndUseCredit()');
                                    window.AndroidApp.checkAndUseCredit();
                                } else {
                                    console.error('Android bridge not available');
                                    // Fallback: restore button and allow normal click
                                    restoreButton(target);
                                }
                            }
                        }
                    } catch (error) {
                        console.error('Error in click handler:', error);
                        // Restore button on error
                        if (target) {
                            restoreButton(target);
                        }
                    }
                }, true);
                
                // Function to restore button state
                function restoreButton(button) {
                    if (!button) return;
                    
                    var originalText = button.getAttribute('data-original-text') || 'Generate Tailored Resume';
                    button.innerHTML = originalText;
                    button.disabled = false;
                    button.style.opacity = '1';
                    button.style.background = '';
                    button.style.color = '';
                    isChecking = false;
                }
                
                // Function to handle credit success
                window.handleCreditSuccess = function() {
                    console.log('Credit success - triggering generation');
                    creditProcessed = true;
                    isChecking = false;
                    
                    // Find the generate button
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        var btn = buttons[i];
                        var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        
                        if (text.includes('generate') && 
                            text.includes('tailored') && 
                            text.includes('resume') && 
                            !text.includes('sample') && 
                            !text.includes('preview')) {
                            
                            // Restore button state
                            restoreButton(btn);
                            
                            // Auto-click to start generation after a short delay
                            setTimeout(function() {
                                console.log('Auto-clicking generate button to start generation');
                                btn.click();
                            }, 300);
                            
                            break;
                        }
                    }
                };
                
                // Function to handle credit error
                window.handleCreditError = function(message) {
                    console.log('Credit error:', message);
                    isChecking = false;
                    creditProcessed = false;
                    
                    // Find the generate button and show error
                    var buttons = document.querySelectorAll('button');
                    for (var i = 0; i < buttons.length; i++) {
                        var btn = buttons[i];
                        var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        
                        if (text.includes('generate') && 
                            text.includes('tailored') && 
                            text.includes('resume') && 
                            !text.includes('sample') && 
                            !text.includes('preview')) {
                            
                            btn.innerHTML = '❌ ' + message;
                            btn.disabled = true;
                            btn.style.background = '#ffebee';
                            btn.style.color = '#c62828';
                            btn.style.border = '1px solid #ffcdd2';
                            
                            // Auto-restore after 3 seconds
                            setTimeout(function() {
                                restoreButton(btn);
                            }, 3000);
                            
                            break;
                        }
                    }
                };
                
                // Reset credit processed flag when page changes (for new sessions)
                var currentUrl = window.location.href;
                setInterval(function() {
                    if (window.location.href !== currentUrl) {
                        currentUrl = window.location.href;
                        creditProcessed = false;
                        console.log('URL changed - resetting credit processed flag');
                    }
                }, 1000);
                
                console.log('Simple credit control setup complete');
                
            })();
        """.trimIndent()

        try {
            webView.evaluateJavascript(creditControlScript) { result ->
                android.util.Log.d("WebView", "Credit control script injected successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("WebView", "Error injecting credit script: ${e.message}")
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
                    android.util.Log.d("WebViewJS", "checkAndUseCredit called from JavaScript")

                    if (isGenerating) {
                        webView.evaluateJavascript(
                            "if (typeof handleCreditError !== 'undefined') { handleCreditError('Generation already in progress'); }",
                            null
                        )
                        return@runOnUiThread
                    }

                    // Quick credit check
                    val availableCredits = creditManager.getAvailableCredits()
                    if (availableCredits <= 0) {
                        webView.evaluateJavascript(
                            "if (typeof handleCreditError !== 'undefined') { handleCreditError('No credits available. Please purchase more.'); }",
                            null
                        )
                        return@runOnUiThread
                    }

                    // Check cooldown
                    if (!creditManager.canGenerateResume()) {
                        webView.evaluateJavascript(
                            "if (typeof handleCreditError !== 'undefined') { handleCreditError('Please wait 30 seconds between generations'); }",
                            null
                        )
                        return@runOnUiThread
                    }

                    isGenerating = true

                    // Deduct credit
                    creditManager.useCreditForResume { success ->
                        runOnUiThread {
                            if (success) {
                                android.util.Log.d("WebViewJS", "Credit deducted successfully")
                                Toast.makeText(
                                    this@CvWebViewActivity,
                                    "✅ 1 credit deducted - Generating resume...",
                                    Toast.LENGTH_SHORT
                                ).show()

                                // Notify JavaScript of success
                                webView.evaluateJavascript(
                                    "if (typeof handleCreditSuccess !== 'undefined') { handleCreditSuccess(); }",
                                    null
                                )

                            } else {
                                android.util.Log.e("WebViewJS", "Credit deduction failed")
                                webView.evaluateJavascript(
                                    "if (typeof handleCreditError !== 'undefined') { handleCreditError('Credit deduction failed'); }",
                                    null
                                )
                                isGenerating = false
                            }
                        }
                    }

                } catch (e: Exception) {
                    android.util.Log.e("WebViewJS", "Error in checkAndUseCredit: ${e.message}")
                    webView.evaluateJavascript(
                        "if (typeof handleCreditError !== 'undefined') { handleCreditError('System error: please try again'); }",
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
                    "✅ Resume generated successfully!",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Reset generating flag
                isGenerating = false
                
                // Reset credit processed flag in JavaScript for next session
                webView.evaluateJavascript("""
                    if (typeof creditProcessed !== 'undefined') {
                        creditProcessed = false;
                        console.log('Reset credit processed flag after generation');
                    }
                """.trimIndent(), null)
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebViewJS", "JS: $message")
        }

        @JavascriptInterface
        fun getRemainingCredits(): Int {
            return creditManager.getAvailableCredits()
        }
        
        @JavascriptInterface
        fun debugPageState() {
            webView.evaluateJavascript("""
                console.log('=== DEBUG PAGE STATE ===');
                console.log('URL:', window.location.href);
                console.log('AndroidApp available:', typeof AndroidApp !== 'undefined');
                
                var buttons = document.querySelectorAll('button');
                console.log('Total buttons:', buttons.length);
                
                for (var i = 0; i < buttons.length; i++) {
                    var btn = buttons[i];
                    var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                    if (text.includes('generate') && text.includes('resume')) {
                        console.log('Generate button ' + i + ':', {
                            text: text,
                            disabled: btn.disabled,
                            html: btn.innerHTML
                        });
                    }
                }
            """.trimIndent(), null)
        }
    }
}
