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
    private var creditUsed = false

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
            setSupportZoom(false)
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        // Make sure these lines are INSIDE onCreate(), but OUTSIDE apply { }
        lifecycleScope.launch(Dispatchers.IO) {    
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
                
                // Wait a bit for Streamlit to fully load
                webView.postDelayed({
                    injectStreamlitHelpers()
                    injectSidebarHandler()
                    injectButtonListener()
                }, 2000) // Wait 2 seconds for Streamlit to initialize
            }

            override fun onReceivedError(
                view: WebView?, 
                request: WebResourceRequest?, 
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
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
        }

        // WebChromeClient
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

        // Load the URL
        webView.loadUrl("${BuildConfig.API_BASE_URL}?fromApp=true&embedded=true")
    }

    private fun injectStreamlitHelpers() {
        val helperCode = """
            // Streamlit helper functions
            window.streamlitApp = {
                creditApproved: function() {
                    console.log('Credit approved - resume generation can proceed');
                    // You might want to trigger the actual generation here
                },
                
                forceSidebarOpen: function() {
                    try {
                        // Try multiple ways to ensure sidebar is open
                        const sidebar = document.querySelector('[data-testid="stSidebar"]');
                        if (sidebar) {
                            const isHidden = sidebar.style.display === 'none' || 
                                           sidebar.style.visibility === 'hidden' ||
                                           sidebar.offsetParent === null;
                            
                            if (isHidden) {
                                sidebar.style.display = 'block';
                                sidebar.style.visibility = 'visible';
                                console.log('Sidebar forced open');
                            }
                        }
                        
                        // Also try Streamlit's own methods
                        if (window.streamlitDebug) {
                            window.streamlitDebug.setSidebarVisible(true);
                        }
                    } catch (e) {
                        console.log('Error forcing sidebar open:', e);
                    }
                },
                
                fixSidebarInteractions: function() {
                    // Add click handlers to ensure sidebar responds
                    document.addEventListener('click', function(e) {
                        const target = e.target;
                        if (target.closest('[data-testid="stSidebar"]') || 
                            target.closest('.stButton') ||
                            target.textContent.includes('Generate') ||
                            target.textContent.includes('Tailor')) {
                            console.log('Sidebar interaction detected');
                        }
                    });
                }
            };

            // Initialize sidebar fixes
            setTimeout(function() {
                window.streamlitApp.forceSidebarOpen();
                window.streamlitApp.fixSidebarInteractions();
            }, 1000);

        """.trimIndent()

        webView.evaluateJavascript(helperCode, null)
    }

    private fun injectSidebarHandler() {
        val sidebarCode = """
            // Enhanced sidebar handler for Streamlit
            function setupSidebarHandler() {
                console.log('Setting up sidebar handler...');
                
                // Method 1: Wait for Streamlit to be ready
                const waitForStreamlit = setInterval(function() {
                    const sidebar = document.querySelector('[data-testid="stSidebar"]');
                    const buttons = document.querySelectorAll('.stButton button, button[role="button"]');
                    
                    if (sidebar || buttons.length > 0) {
                        clearInterval(waitForStreamlit);
                        console.log('Streamlit elements found:', {
                            sidebar: !!sidebar,
                            buttons: buttons.length
                        });
                        
                        // Ensure sidebar is visible and interactive
                        if (sidebar) {
                            sidebar.style.cssText = 'display: block !important; visibility: visible !important;';
                            sidebar.setAttribute('data-app-active', 'true');
                        }
                        
                        // Make all buttons interactive
                        buttons.forEach(btn => {
                            btn.style.pointerEvents = 'auto';
                            btn.style.opacity = '1';
                        });
                        
                        // Force redraw
                        document.body.style.display = 'none';
                        document.body.offsetHeight; // Trigger reflow
                        document.body.style.display = '';
                    }
                }, 500);

                // Stop trying after 10 seconds
                setTimeout(() => clearInterval(waitForStreamlit), 10000);
            }

            // Start sidebar handler
            setupSidebarHandler();

            // Also try when DOM changes (Streamlit is very dynamic)
            const sidebarObserver = new MutationObserver(function(mutations) {
                mutations.forEach(function(mutation) {
                    if (mutation.addedNodes.length > 0) {
                        const hasStreamlitElements = Array.from(mutation.addedNodes).some(node => {
                            return node.nodeType === 1 && (
                                node.querySelector?.('[data-testid="stSidebar"]') ||
                                node.querySelector?.('.stButton') ||
                                node.textContent?.includes('Generate') ||
                                node.textContent?.includes('Tailor')
                            );
                        });
                        
                        if (hasStreamlitElements) {
                            console.log('New Streamlit elements detected');
                            setupSidebarHandler();
                        }
                    }
                });
            });

            sidebarObserver.observe(document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class']
            });

        """.trimIndent()

        webView.evaluateJavascript(sidebarCode, null)
    }

    private fun injectButtonListener() {
        val buttonCode = """
            // Enhanced button listener for Streamlit
            function setupButtonListener() {
                console.log('Setting up button listener...');
                
                function findAndConnectButton() {
                    const selectors = [
                        'button:contains("Generate Tailored Resume")',
                        'button:contains("Tailored Resume")',
                        '[data-testid="baseButton-secondary"]',
                        '.stButton button',
                        'button[kind="secondary"]',
                        'button[class*="secondary"]',
                        'button[role="button"]'
                    ];
                    
                    let targetButton = null;
                    
                    // Try each selector
                    for (const selector of selectors) {
                        if (selector.includes('contains')) {
                            // Text-based search
                            const buttons = document.querySelectorAll('button');
                            for (let button of buttons) {
                                if (button.textContent.includes('Generate Tailored Resume') || 
                                    button.textContent.includes('Tailored Resume')) {
                                    targetButton = button;
                                    break;
                                }
                            }
                        } else {
                            // CSS selector search
                            targetButton = document.querySelector(selector);
                        }
                        
                        if (targetButton) {
                            console.log('Found button with selector:', selector);
                            break;
                        }
                    }
                    
                    if (targetButton) {
                        // Remove existing listeners and add new one
                        const newButton = targetButton.cloneNode(true);
                        targetButton.parentNode.replaceChild(newButton, targetButton);
                        
                        newButton.addEventListener('click', function(e) {
                            console.log('Generate Tailored Resume button clicked!');
                            e.preventDefault();
                            e.stopPropagation();
                            
                            if (window.AndroidApp) {
                                AndroidApp.onTailorResumeButtonClicked();
                            } else {
                                console.log('AndroidApp interface not found');
                                // Fallback: allow the click to proceed
                                setTimeout(() => {
                                    targetButton.click();
                                }, 100);
                            }
                        });
                        
                        // Make button clearly interactive
                        newButton.style.cssText = 'pointer-events: auto !important; opacity: 1 !important; cursor: pointer !important;';
                        
                        console.log('Button listener attached successfully');
                        return true;
                    }
                    
                    return false;
                }
                
                // Try to find button immediately
                if (!findAndConnectButton()) {
                    // Retry every second for 10 seconds
                    let attempts = 0;
                    const retryInterval = setInterval(() => {
                        attempts++;
                        if (findAndConnectButton() || attempts >= 10) {
                            clearInterval(retryInterval);
                        }
                    }, 1000);
                }
            }
            
            // Start button listener setup
            setupButtonListener();
            
            // Also set up when new content is added
            const buttonObserver = new MutationObserver(function() {
                setupButtonListener();
            });
            
            buttonObserver.observe(document.body, {
                childList: true,
                subtree: true
            });

        """.trimIndent()

        webView.evaluateJavascript(buttonCode, null)
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
