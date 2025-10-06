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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var creditManager: CreditManager

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 101
    private var creditUsed = false
    
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

        // Configure WebView for better performance
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
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        // Clear cache and history
        lifecycleScope.launch {    
            webView.clearCache(true)
            webView.clearHistory()
        }
        
        // Add JavaScript interface
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // WebViewClient with better error handling
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
                
                // Single consolidated injection after page load
                webView.postDelayed({
                    injectConsolidatedHelpers()
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
                // Allow same domain URLs to load in WebView, open others in browser
                return if (url.contains(BuildConfig.API_BASE_URL)) {
                    false // Let WebView handle it
                } else {
                    // Open external links in browser
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    true
                }
            }
            
            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                // Page is visibly loading, remove timeout
                loadTimeoutHandler.removeCallbacks(loadTimeoutRunnable)
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

        // Enable debugging in development
        WebView.setWebContentsDebuggingEnabled(true)

        // Set 15-second load timeout
        loadTimeoutHandler.postDelayed(loadTimeoutRunnable, 15000)

        // Load the URL
        webView.loadUrl("${BuildConfig.API_BASE_URL}?fromApp=true&embedded=true")
    }

    private fun injectConsolidatedHelpers() {
        val consolidatedCode = """
            (function() {
                'use strict';
                
                console.log('Injecting consolidated Streamlit helpers...');
                
                // Streamlit helper functions
                window.streamlitApp = {
                    creditApproved: function() {
                        console.log('Credit approved - resume generation can proceed');
                    },
                    
                    forceSidebarOpen: function() {
                        try {
                            const sidebar = document.querySelector('[data-testid="stSidebar"]');
                            if (sidebar) {
                                // Gentle approach - don't force display changes
                                sidebar.style.cssText += '; min-width: 250px !important; z-index: 999 !important; position: relative !important;';
                                console.log('Sidebar visibility enhanced');
                            }
                        } catch (e) {
                            console.log('Sidebar open error:', e);
                        }
                    }
                };

                // Simplified button listener
                function setupButtonListener() {
                    console.log('Setting up simplified button listener...');
                    
                    function handleButtonClick(e) {
                        const target = e.target;
                        if (target.tagName === 'BUTTON' && 
                            (target.textContent.includes('Generate Tailored') || 
                             target.textContent.includes('Tailored Resume'))) {
                            console.log('Generate Tailored Resume button clicked!');
                            
                            if (window.AndroidApp) {
                                e.preventDefault();
                                e.stopImmediatePropagation();
                                window.AndroidApp.onTailorResumeButtonClicked();
                                return false;
                            }
                        }
                        return true;
                    }
                    
                    // Use event delegation
                    document.addEventListener('click', handleButtonClick, true);
                    
                    // One-time setup for existing buttons
                    setTimeout(() => {
                        const buttons = document.querySelectorAll('button');
                        buttons.forEach(btn => {
                            if (btn.textContent.includes('Generate Tailored') || 
                                btn.textContent.includes('Tailored Resume')) {
                                btn.style.cssText += '; pointer-events: auto !important; cursor: pointer !important;';
                                btn.addEventListener('click', handleButtonClick);
                            }
                        });
                    }, 1000);
                }

                // Initialize everything
                setTimeout(function() {
                    if (window.streamlitApp) {
                        window.streamlitApp.forceSidebarOpen();
                    }
                    setupButtonListener();
                    
                    // Notify that helpers are loaded
                    console.log('Streamlit helpers loaded successfully');
                }, 500);

            })();
        """.trimIndent()

        webView.evaluateJavascript(consolidatedCode) { result ->
            android.util.Log.d("WebView", "JavaScript injection completed")
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
        fun onTailorResumeButtonClicked() {
            runOnUiThread {
                if (!creditUsed) {
                    if (creditManager.getAvailableCredits() > 0) {
                        creditManager.useCredit { success ->
                            runOnUiThread {
                                if (success) {
                                    creditUsed = true
                                    Toast.makeText(
                                        this@CvWebViewActivity,
                                        "1 Credit used — Generating your tailored resume...",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    
                                    // Allow the actual generation to proceed in Streamlit
                                    webView.postDelayed({
                                        webView.evaluateJavascript(
                                            """
                                            // Simulate button click to proceed with generation
                                            const buttons = document.querySelectorAll('button');
                                            buttons.forEach(btn => {
                                                if (btn.textContent.includes('Generate Tailored') || 
                                                    btn.textContent.includes('Tailored Resume')) {
                                                    setTimeout(() => {
                                                        btn.click();
                                                    }, 500);
                                                }
                                            });
                                            """.trimIndent(), null
                                        )
                                    }, 1000)
                                } else {
                                    Toast.makeText(
                                        this@CvWebViewActivity,
                                        "Credit deduction failed!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    } else {
                        Toast.makeText(
                            this@CvWebViewActivity,
                            "You don't have enough credits!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this@CvWebViewActivity,
                        "Credit already used for this session",
                        Toast.LENGTH_SHORT
                    ).show()
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
                creditUsed = false
            }
        }
        
        @JavascriptInterface
        fun log(message: String) {
            android.util.Log.d("WebViewJS", message)
        }
    }
}
