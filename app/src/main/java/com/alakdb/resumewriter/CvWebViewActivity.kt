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
    // Use a much simpler approach with minimal JavaScript
    val simpleScript = """
        console.log('=== SIMPLE CREDIT CONTROL INJECTED ===');
        
        // Very simple button click handler
        document.addEventListener('click', function(e) {
            var target = e.target;
            if (target.tagName === 'BUTTON') {
                var text = (target.textContent || target.innerText || '').toLowerCase();
                if (text.includes('generate') && text.includes('tailored') && text.includes('resume') && 
                    !text.includes('sample') && !text.includes('preview')) {
                    
                    console.log('Generate Tailored Resume button clicked');
                    
                    // Check if we've already processed credit for this session
                    if (window.creditAlreadyProcessed) {
                        console.log('Credit already processed for this session');
                        return; // Allow normal behavior
                    }
                    
                    // First time - process credit
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    
                    console.log('Calling Android credit check');
                    target.disabled = true;
                    target.innerHTML = 'Checking Credits...';
                    
                    // Call Android
                    if (window.AndroidApp && window.AndroidApp.checkAndUseCredit) {
                        window.AndroidApp.checkAndUseCredit();
                    } else {
                        console.error('Android bridge not available');
                        target.disabled = false;
                        target.innerHTML = 'Generate Tailored Resume';
                    }
                }
            }
        });
        
        // Simple function to handle credit success
        window.handleCreditSuccess = function() {
            console.log('Credit success - enabling generation');
            window.creditAlreadyProcessed = true;
            
            // Find and click the generate button
            var buttons = document.querySelectorAll('button');
            for (var i = 0; i < buttons.length; i++) {
                var btn = buttons[i];
                var text = (btn.textContent || btn.innerText || '').toLowerCase();
                if (text.includes('generate') && text.includes('tailored') && text.includes('resume') && 
                    !text.includes('sample') && !text.includes('preview')) {
                    
                    btn.disabled = false;
                    btn.innerHTML = 'Generate Tailored Resume';
                    
                    // Click the button to start generation
                    setTimeout(function() {
                        btn.click();
                    }, 100);
                    break;
                }
            }
        };
        
        // Simple function to handle credit error
        window.handleCreditError = function(message) {
            console.log('Credit error: ' + message);
            window.creditAlreadyProcessed = false;
            
            // Find and show error on button
            var buttons = document.querySelectorAll('button');
            for (var i = 0; i < buttons.length; i++) {
                var btn = buttons[i];
                var text = (btn.textContent || btn.innerText || '').toLowerCase();
                if (text.includes('generate') && text.includes('tailored') && text.includes('resume') && 
                    !text.includes('sample') && !text.includes('preview')) {
                    
                    btn.innerHTML = 'Error: ' + message;
                    btn.style.background = '#ffebee';
                    btn.style.color = '#c62828';
                    
                    // Reset after 3 seconds
                    setTimeout(function() {
                        btn.disabled = false;
                        btn.innerHTML = 'Generate Tailored Resume';
                        btn.style.background = '';
                        btn.style.color = '';
                    }, 3000);
                    break;
                }
            }
        };
        
        console.log('Simple credit control setup complete');
    """.trimIndent()

    // Inject with error handling
    try {
        webView.evaluateJavascript("(function(){$simpleScript})();", null)
        android.util.Log.d("WebView", "Simple credit script injected")
    } catch (e: Exception) {
        android.util.Log.e("WebView", "Error injecting script: ${e.message}")
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
                android.util.Log.d("WebViewJS", "checkAndUseCredit called from JS")

                // Quick credit check
                val availableCredits = creditManager.getAvailableCredits()
                if (availableCredits <= 0) {
                    webView.evaluateJavascript(
                        "if (typeof handleCreditError !== 'undefined') { handleCreditError('No credits available'); }",
                        null
                    )
                    return@runOnUiThread
                }

                // Check cooldown
                if (!creditManager.canGenerateResume()) {
                    webView.evaluateJavascript(
                        "if (typeof handleCreditError !== 'undefined') { handleCreditError('Please wait before generating again'); }",
                        null
                    )
                    return@runOnUiThread
                }

                // Deduct credit
                creditManager.useCreditForResume { success ->
                    runOnUiThread {
                        if (success) {
                            android.util.Log.d("WebViewJS", "Credit deducted successfully")
                            Toast.makeText(
                                this@CvWebViewActivity,
                                "✅ Credit deducted - Generating resume...",
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
                        }
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("WebViewJS", "Error in checkAndUseCredit: ${e.message}")
                webView.evaluateJavascript(
                    "if (typeof handleCreditError !== 'undefined') { handleCreditError('System error'); }",
                    null
                )
            }
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
