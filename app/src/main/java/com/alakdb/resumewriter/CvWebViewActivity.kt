package com.alakdb.resumewriter

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var generateResumeButton: Button
    private lateinit var creditManager: CreditManager

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)

        webView = findViewById(R.id.webView)
        generateResumeButton = findViewById(R.id.generateResumeButton)
        creditManager = CreditManager(this)

        // WebView settings
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(false)
        settings.displayZoomControls = false

        // JS bridge (optional, to notify app from webpage)
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // Handle navigation + JS injection
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide Tailor Resume button (uses aria-label)
                webView.evaluateJavascript(
                    """
                    (function() {
                        var btn = document.querySelector('button[aria-label="Tailor Resume"]');
                        if(btn) { btn.style.display = 'none'; }
                    })();
                    """
                , null)
            }
        }

        // File upload support
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@CvWebViewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: Exception) {
                    this@CvWebViewActivity.filePathCallback = null
                    return false
                }
                return true
            }
        }

        // Download support (PDF/Word)
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(url))
                request.addRequestHeader("User-Agent", webView.settings.userAgentString)
                request.setDescription("Downloading file...")
                request.setTitle(URLUtil.guessFileName(url, null, mimeType))
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, null, mimeType)
                )
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading File...", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        // Load backend site
        webView.loadUrl(BuildConfig.API_BASE_URL)

        // Native "Generate Resume" button logic
        generateResumeButton.setOnClickListener {
            if (creditManager.getAvailableCredits() > 0) {
                creditManager.useCredit { success ->
                    if (success) {
                        // Click hidden Tailor Resume button on site
                        webView.evaluateJavascript(
                            """
                            (function() {
                                var btn = document.querySelector('button[aria-label="Tailor Resume"]');
                                if(btn) { btn.click(); }
                            })();
                            """
                        , null)
                    } else {
                        Toast.makeText(
                            this,
                            "Error using credit. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(this, "Not enough credits. Please top up!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // File chooser result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results: Array<Uri>? =
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.data?.let { arrayOf(it) }
                } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    // Back button = WebView back
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JS bridge (optional)
    inner class AndroidBridge {
        @JavascriptInterface
        fun notifyResumeGenerated() {
            runOnUiThread {
                Toast.makeText(this@CvWebViewActivity, "Resume Generated!", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
}
