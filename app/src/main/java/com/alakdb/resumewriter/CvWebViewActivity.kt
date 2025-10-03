package com.yourapp.activities

import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.yourapp.R
import com.yourapp.managers.CreditManager

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var generateResumeButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)

        webView = findViewById(R.id.webView)
        generateResumeButton = findViewById(R.id.generateResumeButton)

        // WebView settings
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(false)
        settings.displayZoomControls = false

        // Add JS bridge
        webView.addJavascriptInterface(AndroidBridge(), "AndroidApp")

        // WebViewClient to hide Tailor Resume button
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide Tailor Resume button
                webView.evaluateJavascript(
                    "document.querySelector('#tailorResumeButton').style.display='none';",
                    null
                )
                // Optional: hide other elements if needed (instructions sidebar)
                // webView.evaluateJavascript("document.querySelector('.sidebar-instructions').style.display='none';", null)
            }
        }

        // Load the site using BuildConfig (Render link hidden from users)
        webView.loadUrl(BuildConfig.API_BASE_URL)

        // Single Generate Resume button
        generateResumeButton.setOnClickListener {
            if (CreditManager.hasCredits(this)) {
                // Deduct credit
                CreditManager.useCredit(this)

                // Trigger hidden Tailor Resume button in WebView
                webView.evaluateJavascript(
                    "document.querySelector('#tailorResumeButton').click();", null
                )
            } else {
                Toast.makeText(this, "Not enough credits. Please top up!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // JS interface for site → Android communication
    inner class AndroidBridge {
        @JavascriptInterface
        fun notifyResumeGenerated() {
            runOnUiThread {
                Toast.makeText(this@CvWebViewActivity, "Resume Generated!", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getCredits(): Int {
            return CreditManager.getCredits(this@CvWebViewActivity)
        }
    }
}
