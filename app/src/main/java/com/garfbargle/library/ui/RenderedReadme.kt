package com.garfbargle.library.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
internal fun RenderedReadme(
    html: String,
    repository: String?,
    modifier: Modifier = Modifier
) {
    val baseUrl = repository?.let { "https://github.com/$it/raw/HEAD/" } ?: "https://github.com/"
    val document = readmeHtmlDocument(html)
    val documentKey = remember(baseUrl, document) { "$baseUrl:${document.hashCode()}" }
    var contentHeightPx by remember(documentKey) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val targetHeight = with(density) {
        val minHeightPx = 320.dp.toPx().roundToInt()
        val maxHeightPx = 6000.dp.toPx().roundToInt()
        contentHeightPx.coerceIn(minHeightPx, maxHeightPx).toDp()
    }

    fun updateContentHeight(webView: WebView) {
        val measured = (webView.contentHeight * webView.scale).roundToInt()
        if (measured > 0 && measured != contentHeightPx) contentHeightPx = measured
    }

    AndroidView(
        modifier = modifier.height(targetHeight),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.scheme == "http" || uri.scheme == "https") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val webView = view ?: return
                        webView.post { updateContentHeight(webView) }
                        webView.postDelayed({ updateContentHeight(webView) }, 250L)
                        webView.postDelayed({ updateContentHeight(webView) }, 1000L)
                    }
                }
            }
        },
        update = { webView ->
            if (webView.tag != documentKey) {
                webView.tag = documentKey
                webView.loadDataWithBaseURL(baseUrl, document, "text/html", "utf-8", null)
            }
        }
    )
}

private fun readmeHtmlDocument(content: String): String = """
    <!doctype html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <style>
            :root { color-scheme: dark; }
            html, body {
                margin: 0;
                padding: 0;
                background: #111214;
                color: #b7bac3;
                font-family: sans-serif;
                font-size: 14px;
                line-height: 1.5;
                overflow-wrap: anywhere;
            }
            body { padding: 16px; }
            h1, h2, h3, h4, h5, h6 { color: #f4f5f7; line-height: 1.25; }
            h1, h2 { border-bottom: 1px solid #24262b; padding-bottom: 0.3em; }
            a { color: #a9ff68; }
            img, video {
                display: inline-block;
                max-width: 100%;
                height: auto;
                object-fit: contain;
            }
            pre {
                overflow-x: auto;
                padding: 13px;
                border-radius: 12px;
                background: #0e0f11;
            }
            code, pre { font-family: monospace; }
            code { color: #c7cad2; }
            blockquote {
                margin-left: 0;
                padding: 10px 12px;
                border-left: 3px solid #4b4e55;
                color: #9a9da6;
                background: #111315;
            }
            table {
                display: block;
                max-width: 100%;
                overflow-x: auto;
                border-collapse: collapse;
            }
            th, td { border: 1px solid #34363c; padding: 6px 10px; }
            hr { border: 0; border-top: 1px solid #24262b; }
        </style>
    </head>
    <body>$content</body>
    </html>
""".trimIndent()
