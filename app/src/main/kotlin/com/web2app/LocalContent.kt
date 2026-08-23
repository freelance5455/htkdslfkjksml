package com.web2app

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader

/**
 * Serves an app built from a picked HTML file or website ZIP.
 *
 * The builder's CI unpacks that content into `assets/web/`, and this serves it over
 * `https://appassets.androidplatform.net/web/` rather than as a `file://` URL. The
 * origin is what makes the difference: under `file://` the browser blocks ES modules,
 * `fetch()` and the storage APIs outright, so a site that works everywhere else would
 * look broken inside the app — and would not match what the builder previewed, which
 * serves the same content the same way.
 */
object LocalContent {

    /** Reserved authority the asset loader answers on; nothing leaves the device. */
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PATH = "/web/"

    fun loaderFor(context: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .addPathHandler(PATH, WebViewAssetLoader.AssetsPathHandler(context))
            .build()

    /**
     * The URL for a page inside the bundle. [entry] is relative to the bundle root;
     * an absolute http(s) address is left alone, so a tab may still point at a real
     * website even in a locally-sourced app.
     */
    fun urlFor(entry: String): String {
        val clean = entry.trim().trimStart('/')
        if (clean.startsWith("http://", true) || clean.startsWith("https://", true)) return clean
        val encoded = clean.split('/').joinToString("/") { Uri.encode(it) }
        return "$ORIGIN$PATH${encoded.ifEmpty { "index.html" }}"
    }

    /** Answers a WebView request out of the assets, or null to let it go to the network. */
    fun intercept(
        loader: WebViewAssetLoader?,
        request: WebResourceRequest,
    ): WebResourceResponse? = loader?.shouldInterceptRequest(request.url)

    /** True for URLs this serves, so in-app navigation can be told from outside links. */
    fun isLocal(url: String): Boolean = url.startsWith(ORIGIN)

    @Suppress("UNUSED_PARAMETER")
    fun noop(view: WebView) = Unit
}
