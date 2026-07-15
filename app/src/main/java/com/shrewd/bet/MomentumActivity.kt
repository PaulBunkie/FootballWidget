package com.shrewd.bet

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.shrewd.bet.databinding.ActivityMomentumBinding

class MomentumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMomentumBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMomentumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Activity itself is fully transparent — only WebView is visible as overlay

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        if (eventId == -1L) {
            finish()
            return
        }

        setupWebView(eventId)
    }

    private fun setupWebView(eventId: Long) {
        val widgetUrl = "https://widgets.sofascore.com/embed/attackMomentum?id=$eventId&widgetTheme=dark"
        
        binding.webViewMomentum.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    binding.progressBar.visibility = View.GONE
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (errorResponse?.statusCode == 403) {
                        binding.webViewMomentum.visibility = View.GONE
                        binding.errorContainer.visibility = View.VISIBLE
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return false
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                    finish()
                    return true
                }
            }
            
            loadUrl(widgetUrl)
        }
    }

    companion object {
        const val EXTRA_EVENT_ID = "extra_event_id"
    }
}