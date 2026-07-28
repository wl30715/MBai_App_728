package com.example.mbaiimageai

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import coil.ImageLoader
import coil.load
import coil.request.ImageRequest
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback ?: return@registerForActivityResult
        fileChooserCallback = null
        val selected = if (result.resultCode == Activity.RESULT_OK) {
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        } else {
            null
        }
        callback.onReceiveValue(selected)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prefs = getSharedPreferences("splash_prefs", Context.MODE_PRIVATE)

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(systemInsets.left, systemInsets.top, systemInsets.right, systemInsets.bottom)
            insets
        }

        // 1. Setup WebView
        setupWebView()
        rootLayout.addView(webView)

        // 2. Setup Splash Screen if needed
        val splashEnabled = prefs.getBoolean("splash_enabled", false)
        val splashImageUrl = prefs.getString("splash_image_url", "") ?: ""
        val splashDuration = prefs.getInt("splash_duration", 3)
        val splashMaxDailyViews = prefs.getInt("splash_max_daily_views", 3)

        // Reset daily views if date changed
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val lastDate = prefs.getString("splash_last_date", "")
        if (today != lastDate) {
            prefs.edit().putInt("splash_daily_views", 0).putString("splash_last_date", today).apply()
        }
        val dailyViews = prefs.getInt("splash_daily_views", 0)

        var splashView: View? = null
        var skipButton: TextView? = null

        if (splashEnabled && splashImageUrl.isNotEmpty() && dailyViews < splashMaxDailyViews) {
            // Increment daily views
            prefs.edit().putInt("splash_daily_views", dailyViews + 1).apply()

            // Create Splash Image
            val imageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.WHITE)
                // Load from cache instantly
                load(splashImageUrl)
            }
            rootLayout.addView(imageView)
            splashView = imageView

            // Create Skip Button
            val skipText = TextView(this).apply {
                val params = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
                params.gravity = Gravity.TOP or Gravity.END
                params.setMargins(0, 100, 50, 0) // top margin for status bar
                layoutParams = params
                text = "跳过 ${splashDuration}s"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(30, 10, 30, 10)
                setBackgroundColor(Color.parseColor("#80000000")) // Semi-transparent black

                setOnClickListener {
                    rootLayout.removeView(imageView)
                    rootLayout.removeView(this)
                }
            }
            rootLayout.addView(skipText)
            skipButton = skipText

            // Countdown logic
            var timeLeft = splashDuration
            val handler = Handler(Looper.getMainLooper())
            val runnable = object : Runnable {
                override fun run() {
                    timeLeft--
                    if (timeLeft > 0) {
                        skipText.text = "跳过 ${timeLeft}s"
                        handler.postDelayed(this, 1000)
                    } else {
                        rootLayout.removeView(imageView)
                        rootLayout.removeView(skipText)
                    }
                }
            }
            handler.postDelayed(runnable, 1000)
        }

        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        // 3. Silently fetch new ad config in background
        fetchAndCacheSplashAd()
    }

    private fun fetchAndCacheSplashAd() {
        scope.launch {
            try {
                val url = URL("https://mbai.wang/api/public/splash-ad")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val responseStr = reader.readText()
                    reader.close()

                    val json = JSONObject(responseStr)
                    val enabled = json.optBoolean("enabled", false)
                    val imageUrl = json.optString("image_url", "")
                    val duration = json.optInt("duration", 3)
                    val maxDailyViews = json.optInt("max_daily_views", 3)

                    // Save to SharedPreferences
                    prefs.edit()
                        .putBoolean("splash_enabled", enabled)
                        .putString("splash_image_url", imageUrl)
                        .putInt("splash_duration", duration)
                        .putInt("splash_max_daily_views", maxDailyViews)
                        .apply()

                    // If enabled and url is not empty, prefetch the image via Coil to disk cache
                    if (enabled && imageUrl.isNotEmpty()) {
                        val request = ImageRequest.Builder(this@MainActivity)
                            .data(imageUrl)
                            .build()
                        val imageLoader = ImageLoader(this@MainActivity)
                        imageLoader.enqueue(request)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain("mbai.wang")
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFocusable = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            cookieManager.setAcceptThirdPartyCookies(this, false)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                // Content URIs are required for files explicitly selected via
                // Android's system document picker. Direct file:// access stays disabled.
                allowContentAccess = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
                setSupportZoom(false)
                builtInZoomControls = false
                userAgentString = "$userAgentString MoBaiApp/${BuildConfig.VERSION_NAME}"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url
                    if (url != null) {
                        val path = url.path ?: ""
                        if (path == "/" || path == "/index.html" || path.isEmpty()) {
                            try { return WebResourceResponse("text/html", "UTF-8", assets.open("static/index.html")) } catch (e: Exception) {}
                        }
                        if (path == "/login" || path == "/login.html" || path == "/app_login.html") {
                            try { return WebResourceResponse("text/html", "UTF-8", assets.open("static/app_login.html")) } catch (e: Exception) {}
                        }
                        if (path == "/history" || path == "/history.html") {
                            try { return WebResourceResponse("text/html", "UTF-8", assets.open("static/history.html")) } catch (e: Exception) {}
                        }
                        if (isBundledAssetPath(path)) {
                            val intercepted = assetLoader.shouldInterceptRequest(url)
                            if (intercepted != null) return intercepted
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.requestFocus(View.FOCUS_DOWN)
                    view?.evaluateJavascript(
                        """
                        (() => {
                          if (!/\bMoBaiApp\//.test(navigator.userAgent)) return;
                          navigator.serviceWorker?.getRegistrations?.()
                            .then(items => items.forEach(item => item.unregister()))
                            .catch(() => {});
                        })();
                        """.trimIndent(),
                        null
                    )
                    CookieManager.getInstance().flush()
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (isInternalWebUrl(url)) return false
                    return openExternalUrl(url)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?
                ): Boolean {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage(message.orEmpty())
                        .setPositiveButton("确定") { _, _ -> result?.confirm() }
                        .setOnCancelListener { result?.cancel() }
                        .show()
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    if (filePathCallback == null) return false
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = filePathCallback
                    val pickerIntent = try {
                        fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }
                    } catch (_: Exception) {
                        fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        return false
                    }
                    return try {
                        fileChooserLauncher.launch(pickerIntent)
                        true
                    } catch (_: Exception) {
                        fileChooserCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    }
                }
            }

            val cachedWebAssetVersion = prefs.getInt(WEB_ASSET_VERSION_KEY, -1)
            if (cachedWebAssetVersion != BuildConfig.VERSION_CODE) {
                clearCache(true)
                clearHistory()
                prefs.edit().putInt(WEB_ASSET_VERSION_KEY, BuildConfig.VERSION_CODE).apply()
            }
            val cookies = cookieManager.getCookie("https://mbai.wang")
            val hasSession = hasSessionCookie(cookies)
            if (hasSession) {
                loadUrl("https://mbai.wang/")
            } else {
                loadUrl("https://mbai.wang/app_login.html")
            }
        }
    }

    override fun onPause() {
        if (::webView.isInitialized) {
            webView.onPause()
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            webView.evaluateJavascript(
                "window.dispatchEvent(new Event('focus')); document.dispatchEvent(new Event('visibilitychange'));",
                null
            )
        }
    }

    override fun onDestroy() {
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun openExternalUrl(url: String): Boolean {
        if (!isAllowedExternalUrl(url)) return true
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        private const val APP_HOST = "mbai.wang"
        private const val SESSION_COOKIE = "ilab_session"
        private const val WEB_ASSET_VERSION_KEY = "web_asset_version"
        private val EXTERNAL_SCHEMES = setOf("https", "mailto", "tel")
        private val BUNDLED_STATIC_FILES = setOf(
            "/static/app.js",
            "/static/history.js",
            "/static/styles.css",
            "/static/tailwind-cdn.js",
            "/static/pwa.js",
            "/static/service-worker.js",
            "/static/manifest.webmanifest",
            "/static/logo.png",
        )

        internal fun isBundledAssetPath(path: String): Boolean =
            path in BUNDLED_STATIC_FILES ||
                path.startsWith("/static/brand/") ||
                path.startsWith("/static/gallery/thumbs/") ||
                path.startsWith("/static/gallery/images/category-covers/")

        internal fun isInternalWebUrl(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(APP_HOST, ignoreCase = true)
        } catch (_: Exception) {
            false
        }

        internal fun isAllowedExternalUrl(url: String): Boolean = try {
            URI(url).scheme?.lowercase(Locale.ROOT) in EXTERNAL_SCHEMES
        } catch (_: Exception) {
            false
        }

        internal fun hasSessionCookie(cookies: String?): Boolean =
            cookies.orEmpty()
                .split(';')
                .map(String::trim)
                .any { cookie ->
                    cookie.substringBefore('=').trim() == SESSION_COOKIE &&
                        cookie.substringAfter('=', "").isNotBlank()
                }
    }
}
