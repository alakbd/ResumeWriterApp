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

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        creditManager = CreditManager(this)

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
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // ✅ Hide native Tailor Resume button (if any duplicate)
                webView.evaluateJavascript(
                    "document.querySelector('#nativeTailorResumeButton')?.style.display='none';",
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progressBar.progress = newProgress
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

        // ✅ Properly detect correct extension and MIME type
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

        // ✅ Load your backend (Streamlit site)
        webView.loadUrl("${BuildConfig.API_BASE_URL}?fromApp=true")
    }

    // ✅ CORRECT PLACEMENT - onActivityResult is at activity level, NOT inside onCreate
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            // Handle the file selection result
            val results = when {
                resultCode == RESULT_OK && data != null -> {
                    // Single file selection
                    arrayOf(data.data ?: return)
                }
                resultCode == RESULT_OK -> {
                    // If you need to handle camera or multiple files, add here
                    null
                }
                else -> {
                    // User cancelled
                    null
                }
            }
        
            // Pass the result back to WebView
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    inner class AndroidBridge {
        // ✅ Only deduct 1 credit when user actually presses the Tailor Resume button
        @JavascriptInterface
        fun onGenerateResumePressed() {
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
                            } else {
                                Toast.makeText(
                                    this@CvWebViewActivity,
                                    "Not enough credits!",
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
                creditUsed = false // ✅ Allow next generation to use a credit
            }
        }
    }
}
