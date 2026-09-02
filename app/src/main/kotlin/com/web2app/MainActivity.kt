package com.web2app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.web2app.data.AppRepository
import com.web2app.handlers.PermissionsHandler
import com.web2app.models.parseAppConfig

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: View

    /** Holder for the configured page loader; null/false means use the default spinner. */
    private var pageLoaderHolder: View? = null
    private var useCustomPageLoader = false

    /** Floating "Made with [icon]" watermark badge. */
    private var watermarkBadge: LinearLayout? = null

    private lateinit var permissionsHandler: PermissionsHandler

    companion object {
        const val EXTRA_APP_ID = "extra_app_id"
        private const val DEFAULT_THEME = 0xFF5B5BD6.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Preview mode: load the app being previewed from the builder's storage.
        // Falls back to the bundled assets/app_settings.json when launched standalone.
        val appId = intent.getStringExtra(EXTRA_APP_ID)
        val isPreview = appId != null
        appConfig = if (isPreview) {
            AppRepository(this).getApp(appId!!) ?: appConfig
        } else {
            parseAppConfig(readJson(this, "app_settings.json"))
        }

        // Setup permissions handler (must be created before setContentView for ActivityResult)
        permissionsHandler = PermissionsHandler(this)

        setContentView(R.layout.activity_main)
        if (appConfig.fullScreen) {
            // No system bars to sit under, so skip the insets/scrim entirely.
            enterFullScreen()
            findViewById<View>(R.id.statusBarScrim).visibility = View.GONE
        } else {
            applySystemBarInsets(R.id.mainRoot, R.id.statusBarScrim)
            applyStatusBarColor()
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        progressBar = findViewById(R.id.progressBar)

        // Show the preview close button (faint eye behind a clear X) only when
        // previewing an app from the builder; tapping it dismisses the preview.
        findViewById<View>(R.id.previewControls).apply {
            visibility = if (isPreview) View.VISIBLE else View.GONE
            setOnClickListener { finish() }
        }

        setupPageLoader()
        setupWebView()
        requestConfiguredPermissions()
        setupWatermark()

        swipeRefresh.isEnabled = appConfig.pullRefresh
        swipeRefresh.setOnRefreshListener { webView.reload() }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            loadCurrentUrl()
        }
    }

    /** Re-hide the bars after they were revealed by a swipe or another window. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && appConfig.fullScreen) enterFullScreen()
    }

    // ─── Watermark ────────────────────────────────────────────────────────────

    /**
     * Clips the app icon inside the watermark badge into a perfect circle and
     * plays a subtle slide-up + fade-in entrance animation after a short delay.
     */
    private fun setupWatermark() {
        val badge = findViewById<LinearLayout>(R.id.watermarkBadge)

        // "watermark": false in app_settings.json strips the badge from the view tree.
        if (!appConfig.watermark) {
            (badge?.parent as? ViewGroup)?.removeView(badge)
            watermarkBadge = null
            return
        }
        watermarkBadge = badge

        // Clip the icon ImageView into a circle using ViewOutlineProvider
        val iconView = findViewById<ImageView>(R.id.watermarkIcon)
        iconView?.apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = (5 * resources.displayMetrics.density) // 5dp in pixels
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
        }

        // Slide-up + fade-in entrance animation
        watermarkBadge?.apply {
            alpha = 0f
            translationY = 60f
            postDelayed({
                val fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f)
                val slideUp = ObjectAnimator.ofFloat(this, "translationY", 60f, 0f)
                AnimatorSet().apply {
                    playTogether(fadeIn, slideUp)
                    duration = 450
                    interpolator = DecelerateInterpolator(1.8f)
                    start()
                }
            }, 600)

            // Open Play Store listing on tap
            setOnClickListener {
                val uri = Uri.parse("https://play.google.com/store/apps/details?id=com.webtoapp.convertwebsitetoapp")
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        }
    }

    // ─── Page loader ─────────────────────────────────────────────────────────

    /**
     * Builds the configured in-page loader into [R.id.pageLoaderHolder]. When the page
     * loader isn't enabled we fall back to the default centered [progressBar].
     */
    private fun setupPageLoader() {
        val holder = findViewById<FrameLayout>(R.id.pageLoaderHolder)
        pageLoaderHolder = holder
        val pl = appConfig.pageLoader
        val theme = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        if (!pl.enabled) {
            useCustomPageLoader = false
            // Tint the default spinner with the configured theme colour too.
            (progressBar as? android.widget.ProgressBar)?.indeterminateTintList =
                android.content.res.ColorStateList.valueOf(theme)
            return
        }
        val icon = Base64ImageUtil.base64ToBitmap(appConfig.appIcon)
        val opts = PageLoaderViews.Opts(
            card = pl.card,
            cardColor = safeParseColor(pl.cardColor, Color.WHITE),
            cardOpacity = pl.cardOpacity,
            cardRadius = pl.cardRadius,
            gap = pl.gap,
            loaderWidth = pl.loaderWidth,
            loaderThickness = pl.loaderThickness
        )
        holder.removeAllViews()
        holder.addView(PageLoaderViews.create(this, pl.style, icon, theme, opts))
        useCustomPageLoader = true
    }

    /** Shows/hides whichever loader is in use (configured page loader or the default). */
    private fun showLoading(show: Boolean) {
        val target = if (useCustomPageLoader) pageLoaderHolder else progressBar
        target?.visibility = if (show) View.VISIBLE else View.GONE
    }

    // ─── WebView ───────────────────────────────────────────────────────────────

    /** Non-null only when this app ships its own content (see [LocalContent]). */
    private val assetLoader by lazy {
        if (appConfig.localContent) LocalContent.loaderFor(this) else null
    }

    @Suppress("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // Allow mixed content (http resources on https pages) – mirrors reference Webview.kt
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            // Custom user-agent matching the reference
            userAgentString =
                "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        // Enable cookies (including third-party) – mirrors reference Webview.kt
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            // A bundled site is served from the app's own assets; a website app has no
            // loader and every request goes to the network as before.
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = LocalContent.intercept(assetLoader, request)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                // A bundled page may link to the placeholder address the builder wrote
                // (or to a plain path); both mean "stay inside the bundle". Resolving
                // here keeps in-page navigation working, not just the initial load.
                val target = request.url.toString()
                view.loadUrl(
                    if (appConfig.localContent) LocalContent.urlFor(target) else target
                )
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                showLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String) {
                showLoading(false)
                swipeRefresh.isRefreshing = false
            }
        }

        // WebChromeClient with popup-window support – mirrors reference Webview.kt onCreateWindow
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val ctx = view?.context ?: return false

                val popupWebView = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        setSupportMultipleWindows(true)
                        javaScriptCanOpenWindowsAutomatically = true
                    }
                    webViewClient = object : WebViewClient() {}
                }

                android.app.Dialog(ctx).apply {
                    setContentView(popupWebView)
                    window?.setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    show()
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun loadCurrentUrl() {
        webView.loadUrl(
            if (appConfig.localContent) LocalContent.urlFor(appConfig.localEntry)
            else appConfig.websiteURL
        )
    }

    // ─── Permissions (mirrors PermissionsWrapper composable in reference) ──────

    private fun requestConfiguredPermissions() {
        val perms = appConfig.permissions
        val needsLocation = perms.contains(0)
        val needsFileCamera = perms.contains(1)
        val needsAudioVideo = perms.contains(2)

        when {
            needsLocation -> permissionsHandler.requestLocationPermissions {
                if (needsFileCamera) {
                    permissionsHandler.requestFileAndCameraPermissions {
                        if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
                    }
                } else if (needsAudioVideo) {
                    permissionsHandler.requestAudioVideoPermissions()
                }
            }
            needsFileCamera -> permissionsHandler.requestFileAndCameraPermissions {
                if (needsAudioVideo) permissionsHandler.requestAudioVideoPermissions()
            }
            needsAudioVideo -> permissionsHandler.requestAudioVideoPermissions()
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private fun safeParseColor(hex: String, default: Int): Int {
        return try {
            if (hex.isBlank()) default else Color.parseColor(hex)
        } catch (e: Exception) {
            default
        }
    }

    /**
     * Paints the status-bar scrim with the configured theme colour (the layout's
     * hardcoded colour was ignoring the setting), and flips the status-bar icons to
     * dark on a light theme for legibility.
     */
    private fun applyStatusBarColor() {
        val theme = safeParseColor(appConfig.themeColor, DEFAULT_THEME)
        findViewById<View>(R.id.statusBarScrim).setBackgroundColor(theme)
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(theme) > 0.5
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = isLight
    }

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
