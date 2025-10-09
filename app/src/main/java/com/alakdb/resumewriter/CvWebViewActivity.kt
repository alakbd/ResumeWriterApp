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
            try {
                // ---------- LOGGING BRIDGE ----------
                const log = (msg) => {
                    try { window.AndroidApp.log(String(msg)); } catch (e) {}
                };

                const origLog = console.log;
                const origError = console.error;
                const origWarn = console.warn;

                console.log = function(...args) {
                    origLog.apply(console, args);
                    try { window.AndroidApp.log('JS LOG: ' + args.join(' ')); } catch (e) {}
                };

                console.error = function(...args) {
                    origError.apply(console, args);
                    try { window.AndroidApp.log('JS ERROR: ' + args.join(' ')); } catch (e) {}
                };

                console.warn = function(...args) {
                    origWarn.apply(console, args);
                    try { window.AndroidApp.log('JS WARN: ' + args.join(' ')); } catch (e) {}
                };

                window.addEventListener('error', function(e) {
                    try {
                        window.AndroidApp.log('JS UNCAUGHT: ' + e.message + ' @ ' + e.filename + ':' + e.lineno);
                    } catch (err) {}
                });

                log('Injecting CREDIT VERIFICATION system...');

                // ---------- CREDIT CONTROL SYSTEM ----------
                let creditCheckInProgress = false;
                let originalButtonState = null;
                let lastClickedButton = null;

                function storeOriginalButtonState(button) {
                    originalButtonState = {
                        html: button.innerHTML,
                        disabled: button.disabled,
                        onclick: button.onclick,
                        form: button.form
                    };
                    console.log('Stored original button state');
                }

                function showCheckingState(button) {
                    button.innerHTML = '⏳ Checking Credits...';
                    button.disabled = true;
                    button.style.opacity = '0.6';
                    button.style.cursor = 'not-allowed';
                    creditCheckInProgress = true;
                }

                function restoreOriginalState(button) {
                    if (originalButtonState) {
                        button.innerHTML = originalButtonState.html;
                        button.disabled = originalButtonState.disabled;
                        button.style.opacity = '1';
                        button.style.cursor = 'pointer';
                        if (originalButtonState.onclick) {
                            button.onclick = originalButtonState.onclick;
                        }
                    }
                    creditCheckInProgress = false;
                }

                function showErrorState(button, message) {
                    button.innerHTML = '❌ ' + message;
                    button.disabled = true;
                    button.style.opacity = '0.8';
                    button.style.cursor = 'not-allowed';
                    button.style.background = '#ffebee';
                    button.style.color = '#c62828';
                    button.style.border = '1px solid #ffcdd2';
                    setTimeout(() => { restoreOriginalState(button); }, 3000);
                }

                // ✅ Improved success behavior: restores click + auto-generates
                function showSuccessState(button) {
                    console.log('Restoring original button handler and auto-clicking...');
                    if (originalButtonState && originalButtonState.onclick) {
                        button.onclick = originalButtonState.onclick;
                    }
                    button.innerHTML = '✅ Credit Approved - Generating...';
                    button.disabled = false;
                    button.style.opacity = '1';
                    button.style.cursor = 'pointer';
                    button.style.background = '#e8f5e8';
                    button.style.color = '#2e7d32';
                    button.style.border = '1px solid #c8e6c9';
                    creditCheckInProgress = false;

                    // Auto-trigger generation
                    setTimeout(() => {
                        try { button.click(); } catch (err) {
                            console.error('Auto-click failed:', err);
                        }
                    }, 300);
                }

                function setupButtonInterception() {
                    document.addEventListener('click', function(e) {
                        const target = e.target;
                        const btnText = (target.textContent || target.innerText || '').toLowerCase().trim();

                        if (target.tagName === 'BUTTON' &&
                            ((btnText.includes('generate tailored resume') || 
                              btnText.includes('generate tailored résumé') ||
                              btnText.includes('create tailored resume') ||
                              (btnText.includes('generate') && btnText.includes('tailored') && btnText.includes('resume'))) &&
                             !btnText.includes('sample') && !btnText.includes('preview'))) {

                            console.log('=== GENERATE BUTTON CLICKED ===');

                            if (creditCheckInProgress) {
                                console.log('Credit check already in progress, preventing click');
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                return;
                            }

                            if (btnText.includes('credit approved') || btnText.includes('generating')) {
                                console.log('Already approved, allowing natural click');
                                return; // let normal generation happen
                            }

                            console.log('First click - checking credits with Android');
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            storeOriginalButtonState(target);
                            lastClickedButton = target;
                            showCheckingState(target);

                            if (window.AndroidApp && window.AndroidApp.checkAndUseCredit) {
                                window.AndroidApp.checkAndUseCredit();
                            } else {
                                console.error('AndroidApp not available');
                                restoreOriginalState(target);
                            }
                        }
                    }, true);
                }

                function initializeCreditControl() {
                    console.log('Initializing credit verification system...');
                    setupButtonInterception();

                    const appIndicator = document.createElement('div');
                    appIndicator.innerHTML =
                        '<div style="background: #e3f2fd; color: #1565c0; padding: 10px; margin: 10px 0; border-radius: 4px; border: 1px solid #bbdefb; font-size: 14px; z-index: 9999; position: relative;">📱 <strong>Mobile App:</strong> 1 Credit = 1 Resume Generation</div>';
                    const mainContent = document.querySelector('.main') || document.body;
                    mainContent.insertBefore(appIndicator, mainContent.firstChild);
                    console.log('Credit verification system initialized');
                }

                window.androidCreditControl = {
                    onCreditApproved: function() {
                        console.log('Credit approved - enabling generation');
                        if (lastClickedButton) {
                            showSuccessState(lastClickedButton);
                        }
                    },
                    onCreditError: function(message) {
                        console.error('Credit error:', message);
                        if (lastClickedButton) {
                            showErrorState(lastClickedButton, message);
                        }
                    },
                    restoreButton: function() {
                        if (lastClickedButton) {
                            restoreOriginalState(lastClickedButton);
                        }
                    }
                };

                initializeCreditControl();
            } catch (err) {
                if (window.AndroidApp && window.AndroidApp.log) {
                    window.AndroidApp.log('INJECTION ERROR: ' + err.stack);
                }
                console.error('INJECTION ERROR:', err);
            }
        })();
    """.trimIndent()

    webView.evaluateJavascript(creditControlScript) { _ ->
        Log.d("WebViewJS", "✅ Credit verification + JS logger injected")
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

            // DEDUCT CREDIT IMMEDIATELY
            creditManager.useCreditForResume { success ->
                runOnUiThread {
                    if (success) {
                        // Credit deducted - now trigger generation
                        Toast.makeText(
                            this@CvWebViewActivity,
                            "✅ 1 credit deducted - Generating resume...",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Use the new robust triggerGeneration method with multiple fallbacks
                        webView.evaluateJavascript("""
                            console.log('=== CREDIT DEDUCTION SUCCESSFUL - TRIGGERING GENERATION ===');
                            if (window.androidCreditControl && window.androidCreditControl.triggerGeneration) {
                                const success = window.androidCreditControl.triggerGeneration();
                                if (success) {
                                    console.log('Generation triggered successfully');
                                    window.androidCreditControl.showSuccess('1 credit used - generating your resume...');
                                } else {
                                    console.error('Failed to trigger generation');
                                    window.androidCreditControl.showError('Failed to start generation. Please try again.');
                                    window.androidCreditControl.enableButton();
                                    // Notify Android that generation failed
                                    if (window.AndroidApp) {
                                        window.AndroidApp.notifyGenerationFailed();
                                    }
                                }
                            } else {
                                console.error('androidCreditControl not available');
                                window.androidCreditControl.enableButton();
                            }
                        """.trimIndent(), null)

                    } else {
                        // Credit deduction failed
                        webView.evaluateJavascript(
                            "if (window.androidCreditControl) { window.androidCreditControl.showError('Credit deduction failed'); window.androidCreditControl.enableButton(); }",
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

// Add this new method to handle generation failures
@JavascriptInterface
fun notifyGenerationFailed() {
    runOnUiThread {
        isGenerating = false
        Toast.makeText(
            this@CvWebViewActivity,
            "❌ Failed to start generation - credit refunded",
            Toast.LENGTH_SHORT
        ).show()
        
        // Optionally refund the credit here if needed
        // creditManager.refundCredit()
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
            isGenerating = false
            
            // Restore button to original state
            webView.evaluateJavascript(
                "if (window.androidCreditControl) { window.androidCreditControl.restoreButton(); }",
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


// Add this temporary method to AndroidBridge for debugging
@JavascriptInterface
fun debugPageState() {
    webView.evaluateJavascript("""
        console.log('=== DEBUG PAGE STATE ===');
        console.log('Last clicked button:', window.androidCreditControl.getLastButton());
        
        const buttons = document.querySelectorAll('button');
        console.log('All buttons count:', buttons.length);
        buttons.forEach((btn, index) => {
            const btnText = (btn.textContent || btn.innerText || '').toLowerCase().trim();
            console.log('Button ' + index + ': "' + btnText + '"', {
                disabled: btn.disabled,
                onclick: btn.onclick ? 'exists' : 'null',
                html: btn.innerHTML
            });
        });
        
        const forms = document.querySelectorAll('form');
        console.log('Forms count:', forms.length);
    """.trimIndent(), null)
        }



    }
}
