package com.khayali.pulao.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson

/**
 * D3.js chart rendered from the app's own assets (assets/viz). Fully offline:
 * network loads are blocked and any non-asset request is refused, so a chart can
 * never fetch or send anything. Transparent so it sits inside native cards.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class VizView(ctx: Context, type: String, data: Any, heightDp: Int) : WebView(ctx) {

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true          // no http(s) at all
            blockNetworkImage = true
            allowFileAccess = false           // assets only (file:///android_asset is still allowed)
            allowContentAccess = false
            domStorageEnabled = false
            setGeolocationEnabled(false)
        }
        val spec = Gson().toJson(mapOf("type" to type, "height" to heightDp, "data" to data))
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                if (req.url.toString().startsWith("file:///android_asset/viz/")) null
                else WebResourceResponse("text/plain", "utf-8", null)          // refuse everything else
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                // every navigation is refused; a tapped stock label in a chart asks for kp://ticker/SYMBOL — open its wheel
                val u = req.url
                if (u.scheme == "kp" && u.host == "ticker") u.lastPathSegment?.uppercase()?.takeIf { Tickers.isKnown(it) }?.let { sym -> view.post { Tickers.onTap?.invoke(sym) } }
                return true
            }
            override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript("render($spec)", null) }
        }
        loadUrl("file:///android_asset/viz/viz.html")
    }
}
