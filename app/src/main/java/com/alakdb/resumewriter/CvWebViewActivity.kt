package com.alakdb.resumewriter

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var generateResumeButton: Button
    private lateinit var creditManager: CreditManager

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
        settings.setSupportZoom(false)
        settings.displayZoomControls = false

        // JS interface
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide Tailor Resume button
                webView.evaluateJavascript(
                    "document.querySelector('#tailorResumeButton').style.display='none';",
                    null
                )
            }
        }

        // Load backend URL (from build.gradle)
        webView.loadUrl(BuildConfig.API_BASE_URL)

        // Generate Resume button logic
        generateResumeButton.setOnClickListener {
            // Check available credits
            if (creditManager.getAvailableCredits() > 0) {
                // Deduct a credit asynchronously
                creditManager.useCredit { success ->
                    if (success) {
                        // Trigger hidden Tailor Resume button only after credit is deducted
                        webView.evaluateJavascript(
                            "document.querySelector('#tailorResumeButton').click();", null
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "Error using credit. Please try again.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else {
                Toast.makeText(this, "Not enough credits. Please top up!", Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    // JS interface to communicate with the site (optional)
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
