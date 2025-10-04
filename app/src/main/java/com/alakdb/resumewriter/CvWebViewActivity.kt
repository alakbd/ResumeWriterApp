package com.alakdb.resumewriter

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.net.Uri
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class CvWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cv_webview)

        webView = findViewById(R.id.webView)

        // WebView settings
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.setSupportZoom(false)
        settings.displayZoomControls = false

        // Handle file uploads (upload CV / job description)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
    webView: WebView?,
    filePathCallback: ValueCallback<Array<Uri>>?,
    fileChooserParams: WebChromeClient.FileChooserParams?
): Boolean {
    this@CvWebViewActivity.filePathCallback = filePathCallback

    val intent: Intent? = fileChooserParams?.createIntent()
    return if (intent != null) {
        try {
            startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
            true
        } catch (e: Exception) {
            this@CvWebViewActivity.filePathCallback = null
            false
        }
    } else {
        this@CvWebViewActivity.filePathCallback = null
        false
    }
}


        // Optional: Hide native Streamlit buttons if needed via JS
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Example: hide unwanted buttons from site
                webView.evaluateJavascript(
                    "var btn = document.querySelector('#someButtonId'); if(btn){ btn.style.display='none'; }",
                    null
                )
            }
        }

        // Load your Streamlit site
        webView.loadUrl(BuildConfig.API_BASE_URL)
    }

    // Handle file chooser result
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results = if (data == null || resultCode != RESULT_OK) null else arrayOf(data.data!!)
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
}
