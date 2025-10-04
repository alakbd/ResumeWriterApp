package com.alakdb.resumewriter

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.*
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var generateResumeButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var creditManager: CreditManager

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val FILE_CHOOSER_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)

        webView = findViewById(R.id.webView)
        generateResumeButton = findViewById(R.id.generateResumeButton)
        progressBar = findViewById(R.id.progressBar)
        creditManager = CreditManager(this)

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            displayZoomControls = false
        }

        // Enable JS bridge
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // Handle downloads properly
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val fileName = when {
                mimeType.contains("pdf") || url.endsWith(".pdf") ->
                    "resume_${System.currentTimeMillis()}.pdf"
                mimeType.contains("word") || url.endsWith(".docx") ->
                    "resume_${System.currentTimeMillis()}.docx"
                else -> "resume_${System.currentTimeMillis()}"
            }

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)

            Toast.makeText(this, "Downloading $fileName...", Toast.LENGTH_LONG).show()
        }

        // Handle page events
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide site’s Tailor Resume button (so only native is visible)
                webView.evaluateJavascript(
                    "document.querySelector('#tailorResumeButton').style.display='none';",
                    null
                )
            }
        }

        // Progress + file uploads
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = android.view.View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = android.view.View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@CvWebViewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent()
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    true
                } catch (e: Exception) {
                    this@CvWebViewActivity.filePathCallback = null
                    false
                }
            }
        }

        // Load your backend
        webView.loadUrl(BuildConfig.API_BASE_URL)

        // Native Generate Resume button
        generateResumeButton.setOnClickListener {
            if (creditManager.getAvailableCredits() > 0) {
                // Deduct 1 credit
                creditManager.useCredit { success ->
                    if (success) {
                        // Hide button to prevent double press
                        runOnUiThread { generateResumeButton.visibility = android.view.View.GONE }

                        // Trigger site’s hidden button
                        webView.evaluateJavascript(
                            "document.querySelector('#tailorResumeButton').click();", null
                        )
                    } else {
                        Toast.makeText(this, "Not enough credits!", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "No credits available!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Bridge for JS → Android communication
    inner class AndroidBridge {
        @JavascriptInterface
        fun notifyResumeGenerated() {
            runOnUiThread {
                Toast.makeText(this@CvWebViewActivity, "Resume generated!", Toast.LENGTH_SHORT).show()
                // Show Generate Resume button again
                generateResumeButton.visibility = android.view.View.VISIBLE
            }
        }
    }
}
