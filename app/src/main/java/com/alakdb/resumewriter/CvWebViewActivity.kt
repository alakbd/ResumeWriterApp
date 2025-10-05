package com.alakdb.resumewriter

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
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
    private var creditUsed = false // ✅ Prevent double credit usage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)
        
        // Initialize views after setContentView
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        creditManager = CreditManager(this)

        // Configure WebView hardware acceleration and scrollbars
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // JS interface must be added before loading any page
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // Set WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Hide native duplicate button
                webView.evaluateJavascript(
                    "document.querySelector('#nativeTailorResumeButton')?.style.display='none';",
                    null
                )

                // Inject global JS error catcher
                webView.evaluateJavascript("""
                    window.onerror = function(message, source, lineno, colno, error) {
                        console.log("JS ERROR: " + message + " at " + source + ":" + lineno + ":" + colno);
                    };
                    window.onunhandledrejection = function(event) {
                        console.log("Unhandled Promise rejection: " + event.reason);
                    };
                """.trimIndent(), null)

                // Inject Streamlit button listener
                injectStreamlitButtonListener()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                view?.loadUrl(request?.url.toString())
                return true
            }
        }

        // Set WebChromeClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
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

        // Set download listener
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

        // Load your backend (Streamlit site)
        webView.loadUrl("${BuildConfig.API_BASE_URL}?fromApp=true")
    }

    // Inject JavaScript to listen for Streamlit button clicks
    private fun injectStreamlitButtonListener() {
        val jsCode = """
            function connectToStreamlitButton() {
                console.log('Looking for Streamlit button...');
                
                const buttons = document.querySelectorAll('button, [role="button"], .stButton button');
                let targetButton = null;
                
                for (let button of buttons) {
                    if (button.textContent.includes('Generate Tailored Resume') || 
                        button.textContent.includes('Tailored Resume') ||
                        button.innerText.includes('Generate Tailored Resume') ||
                        button.innerText.includes('Tailored Resume')) {
                        targetButton = button;
                        console.log('Found button by text:', button.textContent);
                        break;
                    }
                }
                
                if (!targetButton) {
                    targetButton = document.querySelector('[data-testid="baseButton-secondary"]') ||
                                  document.querySelector('.stButton > button') ||
                                  document.querySelector('[kind="secondary"]') ||
                                  document.querySelector('button[class*="secondary"]');
                    console.log('Found button by selector:', targetButton);
                }
                
                if (targetButton) {
                    targetButton.replaceWith(targetButton.cloneNode(true));
                    const newButton = document.querySelector('button[class*="secondary"]') || 
                                     document.querySelector('[data-testid="baseButton-secondary"]') ||
                                     document.querySelector('.stButton > button');
                    
                    if (newButton) {
                        newButton.addEventListener('click', function() {
                            console.log('Generate Tailored Resume button clicked!');
                            if (window.AndroidApp) {
                                AndroidApp.onTailorResumeButtonClicked();
                            } else {
                                console.log('AndroidApp interface not found');
                            }
                        });
                        console.log('Listener attached to Streamlit button');
                    }
                } else {
                    console.log('Streamlit button not found, retrying in 1 second...');
                    setTimeout(connectToStreamlitButton, 1000);
                }
            }
            
            connectToStreamlitButton();
            
            const observer = new MutationObserver(function() {
                connectToStreamlitButton();
            });
            
            observer.observe(document.body, {
                childList: true,
                subtree: true
            });
        """.trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = when {
                resultCode == RESULT_OK && data != null -> {
                    arrayOf(data.data ?: return)
                }
                resultCode == RESULT_OK -> {
                    null
                }
                else -> {
                    null
                }
            }
        
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun onTailorResumeButtonClicked() {
            runOnUiThread {
                if (!creditUsed) {
                    if (creditManager.getAvailableCredits() > 0) {
                        creditManager.useCredit { success ->
                            if (success) {
                                creditUsed = true
                                Toast.makeText(
                                    this@CvWebViewActivity,
                                    "1 Credit used — Generating your tailored resume...",
                                    Toast.LENGTH_SHORT
                                ).show()
                                
                                webView.evaluateJavascript(
                                    "if(window.streamlitApp) { streamlitApp.creditApproved(); }",
                                    null
                                )
                            } else {
                                Toast.makeText(
                                    this@CvWebViewActivity,
                                    "Credit deduction failed!",
                                    Toast.LENGTH_LONG
                                ).show()
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
    }
}
