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
            
            console.log('Injecting CREDIT VERIFICATION system...');
            
            let creditCheckInProgress = false;
            let originalButtonState = null;
            
            // Store original button state
            function storeOriginalButtonState(button) {
                originalButtonState = {
                    html: button.innerHTML,
                    disabled: button.disabled,
                    onclick: button.onclick,
                    form: button.form
                };
                console.log('Stored original button state');
            }
            
            // Show checking state
            function showCheckingState(button) {
                button.innerHTML = '⏳ Checking Credits...';
                button.disabled = true;
                button.style.opacity = '0.6';
                button.style.cursor = 'not-allowed';
                creditCheckInProgress = true;
            }
            
            // Restore original state
            function restoreOriginalState(button) {
                if (originalButtonState) {
                    button.innerHTML = originalButtonState.html;
                    button.disabled = originalButtonState.disabled;
                    button.style.opacity = '1';
                    button.style.cursor = 'pointer';
                }
                creditCheckInProgress = false;
            }
            
            // Show error state
            function showErrorState(button, message) {
                button.innerHTML = '❌ ' + message;
                button.disabled = true;
                button.style.opacity = '0.8';
                button.style.cursor = 'not-allowed';
                button.style.background = '#ffebee';
                button.style.color = '#c62828';
                button.style.border = '1px solid #ffcdd2';
                
                // Auto-restore after 3 seconds
                setTimeout(() => {
                    restoreOriginalState(button);
                }, 3000);
            }
            
            // Show success state and allow natural click
            function showSuccessState(button) {
                button.innerHTML = '✅ Credit Approved - Click Again';
                button.disabled = false;
                button.style.opacity = '1';
                button.style.cursor = 'pointer';
                button.style.background = '#e8f5e8';
                button.style.color = '#2e7d32';
                button.style.border = '1px solid #c8e6c9';
                
                creditCheckInProgress = false;
            }
            
            // Main button interception
            function setupButtonInterception() {
                document.addEventListener('click', function(e) {
                    const target = e.target;
                    const btnText = (target.textContent || target.innerText || '').toLowerCase().trim();
                    
                    // Only intercept main resume generation button
                    if (target.tagName === 'BUTTON' && 
                        ((btnText.includes('generate tailored resume') || 
                          btnText.includes('generate tailored résumé') ||
                          btnText.includes('create tailored resume') ||
                          (btnText.includes('generate') && btnText.includes('tailored') && btnText.includes('resume'))) &&
                         !btnText.includes('sample') && !btnText.includes('preview'))) {
                        
                        console.log('=== GENERATE BUTTON CLICKED ===');
                        
                        // If we're already checking credits, prevent another check
                        if (creditCheckInProgress) {
                            console.log('Credit check already in progress, preventing click');
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            return;
                        }
                        
                        // If button is in success state, allow the natural click to proceed
                        if (btnText.includes('credit approved') || btnText.includes('click again')) {
                            console.log('Button in success state, allowing natural generation');
                            creditCheckInProgress = false;
                            // Don't prevent default - let the natural click happen
                            return;
                        }
                        
                        // This is the first click - check credits
                        console.log('First click - checking credits with Android');
                        e.preventDefault();
                        e.stopImmediatePropagation();
                        
                        // Store original state and show checking state
                        storeOriginalButtonState(target);
                        showCheckingState(target);
                        
                        // Call Android to check and use credit
                        if (window.AndroidApp) {
                            window.AndroidApp.checkAndUseCredit();
                        } else {
                            console.error('AndroidApp not available');
                            restoreOriginalState(target);
                        }
                    }
                }, true);
            }
            
            // Initialize
            function initializeCreditControl() {
                console.log('Initializing credit verification system...');
                setupButtonInterception();
                
                // Add Android app indicator
                const appIndicator = document.createElement('div');
                appIndicator.innerHTML = '<div style="background: #e3f2fd; color: #1565c0; padding: 10px; margin: 10px 0; border-radius: 4px; border: 1px solid #bbdefb; font-size: 14px; z-index: 9999; position: relative;">📱 <strong>Mobile App:</strong> 1 Credit = 1 Resume Generation</div>';
                const mainContent = document.querySelector('.main') || document.body;
                mainContent.insertBefore(appIndicator, mainContent.firstChild);
                
                console.log('Credit verification system initialized');
            }
            
            // Expose functions to Android
            window.androidCreditControl = {
                onCreditApproved: function() {
                    console.log('Credit approved - enabling button for generation');
                    const buttons = document.querySelectorAll('button');
                    buttons.forEach(btn => {
                        const btnText = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        if ((btnText.includes('generate tailored resume') || 
                             btnText.includes('generate tailored résumé') ||
                             btnText.includes('create tailored resume') ||
                             (btnText.includes('generate') && btnText.includes('tailored') && btnText.includes('resume'))) &&
                            !btnText.includes('sample') && !btnText.includes('preview')) {
                            
                            showSuccessState(btn);
                        }
                    });
                },
                
                onCreditError: function(message) {
                    console.log('Credit error:', message);
                    const buttons = document.querySelectorAll('button');
                    buttons.forEach(btn => {
                        const btnText = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        if ((btnText.includes('generate tailored resume') || 
                             btnText.includes('generate tailored résumé') ||
                             btnText.includes('create tailored resume') ||
                             (btnText.includes('generate') && btnText.includes('tailored') && btnText.includes('resume'))) &&
                            !btnText.includes('sample') && !btnText.includes('preview')) {
                            
                            showErrorState(btn, message);
                        }
                    });
                },
                
                restoreButton: function() {
                    const buttons = document.querySelectorAll('button');
                    buttons.forEach(btn => {
                        const btnText = (btn.textContent || btn.innerText || '').toLowerCase().trim();
                        if ((btnText.includes('generate tailored resume') || 
                             btnText.includes('generate tailored résumé') ||
                             btnText.includes('create tailored resume') ||
                             (btnText.includes('generate') && btnText.includes('tailored') && btnText.includes('resume'))) &&
                            !btnText.includes('sample') && !btnText.includes('preview')) {
                            
                            restoreOriginalState(btn);
                        }
                    });
                }
            };
            
            // Start initialization
            initializeCreditControl();
            
        })();
    """.trimIndent()

    webView.evaluateJavascript(creditControlScript) { _ ->
        android.util.Log.d("WebView", "Credit verification script injected")
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
                        "if (window.androidCreditControl) { window.androidCreditControl.onCreditError('Generation already in progress'); }",
                        null
                    )
                    return@runOnUiThread
                }

                // Quick credit check
                if (creditManager.getAvailableCredits() <= 0) {
                    webView.evaluateJavascript(
                        "if (window.androidCreditControl) { window.androidCreditControl.onCreditError('Not enough credits! Please purchase more.'); }",
                        null
                    )
                    return@runOnUiThread
                }

                // Check cooldown
                if (!creditManager.canGenerateResume()) {
                    webView.evaluateJavascript(
                        "if (window.androidCreditControl) { window.androidCreditControl.onCreditError('Please wait 30 seconds between generations'); }",
                        null
                    )
                    return@runOnUiThread
                }

                isGenerating = true

                // Deduct credit
                creditManager.useCreditForResume { success ->
                    runOnUiThread {
                        if (success) {
                            // Credit deducted successfully
                            Toast.makeText(
                                this@CvWebViewActivity,
                                "✅ 1 credit deducted - Click 'Generate' again to continue",
                                Toast.LENGTH_LONG
                            ).show()

                            // Notify JavaScript that credit is approved
                            webView.evaluateJavascript(
                                "if (window.androidCreditControl) { window.androidCreditControl.onCreditApproved(); }",
                                null
                            )

                            // Reset generating flag after a delay to allow user to click again
                            Handler(Looper.getMainLooper()).postDelayed({
                                isGenerating = false
                            }, 5000)

                        } else {
                            // Credit deduction failed
                            webView.evaluateJavascript(
                                "if (window.androidCreditControl) { window.androidCreditControl.onCreditError('Credit deduction failed'); }",
                                null
                            )
                            isGenerating = false
                        }
                    }
                }

            } catch (e: Exception) {
                webView.evaluateJavascript(
                    "if (window.androidCreditControl) { window.androidCreditControl.onCreditError('System error: ${e.message}'); }",
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
