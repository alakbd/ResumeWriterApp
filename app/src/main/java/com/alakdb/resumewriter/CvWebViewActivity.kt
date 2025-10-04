package com.alakdb.resumewriter

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.alakdb.resumewriter.databinding.ActivityCvWebviewBinding

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCvWebviewBinding
    private lateinit var creditManager: CreditManager

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCvWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        creditManager = CreditManager(this)

        val webView = binding.webView
        val generateResumeButton = binding.generateResumeButton
        val progressBar = binding.progressBar

        // WebView settings
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(false)
            displayZoomControls = false
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                webView.evaluateJavascript(
                    """
                    (function() {
                        var btn = document.querySelector('button[aria-label="Tailor Resume"]');
                        if(btn) { btn.style.display = 'none'; }
                    })();
                    """.trimIndent(), null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@CvWebViewActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: return false
                return try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                    true
                } catch (e: Exception) {
                    this@CvWebViewActivity.filePathCallback = null
                    false
                }
            }
        }

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

        webView.loadUrl(BuildConfig.API_BASE_URL)

        generateResumeButton.setOnClickListener {
            if (creditManager.getAvailableCredits() > 0) {
                creditManager.useCredit { success ->
                    if (success) {
                        webView.evaluateJavascript(
                            """
                            (function() {
                                var btn = document.querySelector('button[aria-label="Tailor Resume"]');
                                if(btn) { btn.click(); }
                            })();
                            """.trimIndent(), null
                        )
                    } else {
                        Toast.makeText(this, "Error using credit. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, "Not enough credits. Please top up!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
        val results: Array<Uri>? = when {
            resultCode == Activity.RESULT_OK && data != null -> {
                data.data?.let { arrayOf(it) } ?: arrayOf()
            }
            else -> null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }
}


    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun notifyResumeGenerated() {
            runOnUiThread {
                Toast.makeText(this@CvWebViewActivity, "Resume Generated!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
