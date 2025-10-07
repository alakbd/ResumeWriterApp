package com.alakdb.resumewriter

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
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
    private var creditUsedInThisSession = false
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
                
                console.log('Injecting credit control system...');
                
                let resumeButtonBlocked = false;
                
                // Function to disable generate button
                function disableGenerateButton() {
                    const buttons = document.querySelectorAll('button');
                    buttons.forEach(btn => {
                        if (btn.textContent.includes('Generate Tailored') || 
                            btn.textContent.includes('Tailored Resume')) {
                            btn.disabled = true;
                            btn.style.opacity = '0.5';
                            btn.style.cursor = 'not-allowed';
                            btn.innerHTML = '⏳ Processing...';
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
                            btn.innerHTML = btn.innerHTML.replace('⏳ Processing...', '✨ Generate Tailored Résumé');
                        }
                    });
                    resumeButtonBlocked = false;
                }
                
                // Function to show error message
                function showCreditError(message) {
                    // Create or update error message
                    let errorDiv = document.getElementById('android-credit-error');
                    if (!errorDiv) {
                        errorDiv = document.createElement('div');
                        errorDiv.id = 'android-credit-error';
                        errorDiv.style.cssText = 'background: #ffebee; color: #c62828; padding: 12px; margin: 10px 0; border-radius: 4px; border: 1px solid #ffcdd2;';
                        document.body.insertBefore(errorDiv, document.body.firstChild);
                    }
                    errorDiv.innerHTML = `🚫 <strong>Credit Error:</strong> ${message}`;
                }
                
                // Intercept button clicks
                document.addEventListener('click', function(e) {
                    const target = e.target;
                    if (target.tagName === 'BUTTON' && 
                        (target.textContent.includes('Generate Tailored') || 
                         target.textContent.includes('Tailored Resume'))) {
                        
                        console.log('Generate button clicked - checking credits...');
                        
                        if (resumeButtonBlocked) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            showCreditError('Please wait for current generation to complete');
                            return;
                        }
                        
                        // Check with Android app before proceeding
                        if (window.AndroidApp) {
                            e.preventDefault();
                            e.stopImmediatePropagation();
                            
                            disableGenerateButton();
                            
                            // Request credit check from Android
                            window.AndroidApp.checkAndUseCredit();
                        }
                    }
                });
                
                // Expose functions to Android
                window.androidCreditControl = {
                    enableButton: enableGenerateButton,
                    disableButton: disableGenerateButton,
                    showError: showCreditError
                };
                
                console.log('Credit control system injected successfully');
                
            })();
        """.trimIndent()

        webView.evaluateJavascript(creditControlScript) { result ->
            android.util.Log.d("WebView", "Credit control script injected")
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
                if (isGenerating) {
                    webView.evaluateJavascript(
                        "if (window.androidCreditControl) { window.androidCreditControl.showError('Generation already in progress'); }",
                        null
                    )
                    return@runOnUiThread
                }
                
                if (creditUsedInThisSession) {
                    webView.evaluateJavascript(
                        "if (window.androidCreditControl) { window.androidCreditControl.showError('Credit already used in this session'); window.androidCreditControl.enableButton(); }",
                        null
                    )
                    return@runOnUiThread
                }
                
                if (!creditManager.canGenerateResume()) {
                    webView.evaluateJavascript(
                        "if (window.androidCreditControl) { window.androidCreditControl.showError('Not enough credits'); window.androidCreditControl.enableButton(); }",
                        null
                    )
                    return@runOnUiThread
                }
                
                // All checks passed, use credit
                isGenerating = true
                creditManager.useCreditForResume { success ->
                    runOnUiThread {
                        if (success) {
                            creditUsedInThisSession = true
                            Toast.makeText(
                                this@CvWebViewActivity,
                                "1 Credit used — Generating your tailored resume...",
                                Toast.LENGTH_SHORT
                            ).show()
                            
                            // Enable the button and trigger the actual generation
                            webView.evaluateJavascript(
                                """
                                if (window.androidCreditControl) {
                                    window.androidCreditControl.enableButton();
                                }
                                // Trigger the actual generation
                                setTimeout(() => {
                                    const buttons = document.querySelectorAll('button');
                                    buttons.forEach(btn => {
                                        if (btn.textContent.includes('Generate Tailored') || 
                                            btn.textContent.includes('Tailored Resume')) {
                                            btn.click();
                                        }
                                    });
                                }, 500);
                                """.trimIndent(), null
                            )
                            
                        } else {
                            webView.evaluateJavascript(
                                "if (window.androidCreditControl) { window.androidCreditControl.showError('Credit deduction failed'); window.androidCreditControl.enableButton(); }",
                                null
                            )
                            Toast.makeText(
                                this@CvWebViewActivity,
                                "Credit deduction failed!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        isGenerating = false
                    }
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
                // Don't reset creditUsedInThisSession here - keep it for the session
            }
        }
        
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebViewJS", message)
        }
    }
}
