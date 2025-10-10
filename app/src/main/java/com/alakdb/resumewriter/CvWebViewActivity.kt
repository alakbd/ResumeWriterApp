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

        // WebViewClient - UPDATED
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
                
                // Inject credit control JavaScript multiple times to ensure it works
                Handler(Looper.getMainLooper()).postDelayed({
                    injectCreditControlScript()
                    // Second injection after delay to catch dynamically loaded content
                    Handler(Looper.getMainLooper()).postDelayed({
                        injectCreditControlScript()
                    }, 1000)
                }, 1000)
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

            // NEW: Inject script on every page load
            override fun onLoadResource(view: WebView?, url: String?) {
                super.onLoadResource(view, url)
                // Re-inject script periodically to ensure it's always active
                if (url?.contains("generate") == true || url?.contains("resume") == true) {
                    injectCreditControlScript()
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
                
                // Reinject script at various progress points
                if (newProgress == 50 || newProgress == 80 || newProgress == 100) {
                    injectCreditControlScript()
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
                
                console.log('=== FORCE CREDIT CONTROL INJECTED ===');
                
                // Remove any existing listeners to prevent duplicates
                document.removeEventListener('click', creditClickHandler);
                
                function creditClickHandler(e) {
                    var target = e.target;
                    
                    // Check if this is a button or within a button
                    while (target && target !== document) {
                        if (target.tagName === 'BUTTON' || target.tagName === 'A' || target.hasAttribute('onclick')) {
                            var text = (target.textContent || target.innerText || '').toLowerCase().trim();
                            var html = (target.innerHTML || '').toLowerCase();
                            
                            console.log('Clicked element text:', text);
                            console.log('Clicked element HTML:', html);
                            
                            // Check for generate resume button - more flexible matching
                            if ((text.includes('generate') && text.includes('resume')) ||
                                (text.includes('create') && text.includes('resume')) ||
                                (text.includes('generate') && text.includes('cv')) ||
                                (html.includes('generate') && html.includes('resume')) ||
                                target.id.includes('generate') ||
                                target.className.includes('generate')) {
                                
                                console.log('=== GENERATE RESUME BUTTON DETECTED ===');
                                
                                // ALWAYS prevent default and process credit
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                e.stopPropagation();
                                
                                // Store original button state
                                if (!target.hasAttribute('data-original-html')) {
                                    target.setAttribute('data-original-html', target.innerHTML);
                                }
                                if (!target.hasAttribute('data-original-onclick')) {
                                    target.setAttribute('data-original-onclick', target.onclick ? target.onclick.toString() : '');
                                }
                                
                                // Show checking state
                                var originalText = target.getAttribute('data-original-text') || 
                                                 target.textContent || 
                                                 target.innerText || 
                                                 'Generate Resume';
                                target.setAttribute('data-original-text', originalText);
                                
                                target.innerHTML = '⏳ Checking Credits...';
                                if (target.disabled !== undefined) target.disabled = true;
                                target.style.opacity = '0.6';
                                target.style.pointerEvents = 'none';
                                
                                console.log('Calling Android credit check...');
                                
                                // Call Android bridge with error handling
                                setTimeout(function() {
                                    if (window.AndroidApp && typeof window.AndroidApp.checkAndUseCredit === 'function') {
                                        window.AndroidApp.checkAndUseCredit();
                                    } else {
                                        console.error('Android bridge not available - restoring button');
                                        restoreButton(target);
                                    }
                                }, 100);
                                
                                return false;
                            }
                        }
                        target = target.parentElement;
                    }
                }
                
                // Add event listener with capture to catch events early
                document.addEventListener('click', creditClickHandler, true);
                
                // Also intercept form submissions
                var forms = document.querySelectorAll('form');
                forms.forEach(function(form) {
                    form.addEventListener('submit', function(e) {
                        var submitButtons = form.querySelectorAll('button[type="submit"], input[type="submit"]');
                        submitButtons.forEach(function(button) {
                            var text = (button.textContent || button.innerText || button.value || '').toLowerCase().trim();
                            if (text.includes('generate') && text.includes('resume')) {
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                creditClickHandler({ target: button, preventDefault: function() {}, stopImmediatePropagation: function() {}, stopPropagation: function() {} });
                                return false;
                            }
                        });
                    });
                });
                
                // Function to restore button state
                window.restoreButton = function(button) {
                    if (!button) return;
                    
                    var originalHtml = button.getAttribute('data-original-html');
                    var originalText = button.getAttribute('data-original-text');
                    
                    if (originalHtml) {
                        button.innerHTML = originalHtml;
                    } else if (originalText) {
                        button.innerHTML = originalText;
                    } else {
                        button.innerHTML = 'Generate Resume';
                    }
                    
                    if (button.disabled !== undefined) button.disabled = false;
                    button.style.opacity = '1';
                    button.style.pointerEvents = 'auto';
                    button.style.background = '';
                    button.style.color = '';
                };
                
                // Function to handle credit success
                window.handleCreditSuccess = function() {
                    console.log('Credit success - triggering actual generation');
                    
                    // Find and restore all generate buttons
                    var buttons = document.querySelectorAll('button, a, [onclick]');
                    buttons.forEach(function(btn) {
                        var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        var html = (btn.innerHTML || '').toLowerCase();
                        
                        if ((text.includes('generate') && text.includes('resume')) ||
                            (text.includes('create') && text.includes('resume')) ||
                            (html.includes('generate') && html.includes('resume'))) {
                            
                            restoreButton(btn);
                            
                            // Trigger the original click after delay
                            setTimeout(function() {
                                console.log('Triggering actual resume generation');
                                if (btn.hasAttribute('data-original-onclick')) {
                                    var originalOnclick = btn.getAttribute('data-original-onclick');
                                    if (originalOnclick && originalOnclick !== 'null') {
                                        eval(originalOnclick);
                                    }
                                } else {
                                    btn.click();
                                }
                            }, 500);
                        }
                    });
                };
                
                // Function to handle credit error
                window.handleCreditError = function(message) {
                    console.log('Credit error:', message);
                    
                    // Find and show error on generate buttons
                    var buttons = document.querySelectorAll('button, a, [onclick]');
                    buttons.forEach(function(btn) {
                        var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        var html = (btn.innerHTML || '').toLowerCase();
                        
                        if ((text.includes('generate') && text.includes('resume')) ||
                            (text.includes('create') && text.includes('resume')) ||
                            (html.includes('generate') && html.includes('resume'))) {
                            
                            btn.innerHTML = '❌ ' + (message || 'No credits available');
                            if (btn.disabled !== undefined) btn.disabled = true;
                            btn.style.background = '#ffebee';
                            btn.style.color = '#c62828';
                            btn.style.border = '1px solid #ffcdd2';
                            
                            // Auto-restore after 3 seconds
                            setTimeout(function() {
                                restoreButton(btn);
                            }, 3000);
                        }
                    });
                };
                
                console.log('Force credit control injection complete');
                
            })();
        """.trimIndent()

        try {
            webView.evaluateJavascript(creditControlScript) { result ->
                Log.d("WebView", "Credit control script injected: $result")
            }
        } catch (e: Exception) {
            Log.e("WebView", "Error injecting credit script: ${e.message}")
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
                    Log.d("WebViewJS", "checkAndUseCredit called from JavaScript - Credits: ${creditManager.getAvailableCredits()}")

                    if (isGenerating) {
                        Log.d("WebViewJS", "Generation already in progress")
                        webView.evaluateJavascript(
                            "if (typeof handleCreditError !== 'undefined') { handleCreditError('Generation already in progress'); }",
                            null
                        )
                        return@runOnUiThread
                    }

                    // Check credits
                    val availableCredits = creditManager.getAvailableCredits()
                    if (availableCredits <= 0) {
                        Log.d("WebViewJS", "No credits available")
                        webView.evaluateJavascript(
                            "if (typeof handleCreditError !== 'undefined') { handleCreditError('No credits available. Please purchase more.'); }",
                            null
                        )
                        return@runOnUiThread
                    }

                    // Check cooldown
                    if (!creditManager.canGenerateResume()) {
                        Log.d("WebViewJS", "Cooldown active")
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
                                Log.d("WebViewJS", "Credit deducted successfully")
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
                                Log.e("WebViewJS", "Credit deduction failed")
                                webView.evaluateJavascript(
                                    "if (typeof handleCreditError !== 'undefined') { handleCreditError('Credit deduction failed'); }",
                                    null
                                )
                                isGenerating = false
                            }
                        }
                    }

                } catch (e: Exception) {
                    Log.e("WebViewJS", "Error in checkAndUseCredit: ${e.message}")
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
                Log.d("WebViewJS", "Resume generation completed")
                Toast.makeText(
                    this@CvWebViewActivity,
                    "✅ Resume generated successfully!",
                    Toast.LENGTH_SHORT
                ).show()
                
                // Reset generating flag
                isGenerating = false
            }
        }

        @JavascriptInterface
        fun log(message: String) {
            Log.d("WebViewJS", "JS: $message")
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
                
                var buttons = document.querySelectorAll('button, a, [onclick]');
                console.log('Total interactive elements:', buttons.length);
                
                for (var i = 0; i < buttons.length; i++) {
                    var btn = buttons[i];
                    var text = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                    if (text.includes('generate') && text.includes('resume')) {
                        console.log('Generate button ' + i + ':', {
                            text: text,
                            disabled: btn.disabled,
                            html: btn.innerHTML,
                            id: btn.id,
                            className: btn.className
                        });
                    }
                }
            """.trimIndent(), null)
        }
    }
}
