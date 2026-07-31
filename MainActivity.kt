package com.example.mbaiimageai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var splashCountdownRunnable: Runnable? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: DownloadSpec? = null
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
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (granted && download != null) {
            enqueueDownload(download)
        } else if (!granted) {
            showMessage("需要存储权限才能将文件保存到下载目录")
        }
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
        val splashDuration = clampSplashDuration(prefs.getInt("splash_duration", DEFAULT_SPLASH_DURATION))
        val splashMaxDailyViews = clampSplashDailyViews(
            prefs.getInt("splash_max_daily_views", DEFAULT_SPLASH_MAX_DAILY_VIEWS)
        )

        // Reset daily views if date changed
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = sdf.format(Date())
        val lastDate = prefs.getString("splash_last_date", "")
        if (today != lastDate) {
            prefs.edit().putInt("splash_daily_views", 0).putString("splash_last_date", today).apply()
        }
        val dailyViews = prefs.getInt("splash_daily_views", 0)

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
                    splashCountdownRunnable?.let(mainHandler::removeCallbacks)
                    splashCountdownRunnable = null
                    rootLayout.removeView(imageView)
                    rootLayout.removeView(this)
                }
            }
            rootLayout.addView(skipText)

            // Countdown logic
            var timeLeft = splashDuration
            val runnable = object : Runnable {
                override fun run() {
                    timeLeft--
                    if (timeLeft > 0) {
                        skipText.text = "跳过 ${timeLeft}s"
                        mainHandler.postDelayed(this, 1000)
                    } else {
                        rootLayout.removeView(imageView)
                        rootLayout.removeView(skipText)
                        splashCountdownRunnable = null
                    }
                }
            }
            splashCountdownRunnable = runnable
            mainHandler.postDelayed(runnable, 1000)
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
                val url = URL("$APP_ORIGIN/api/public/splash-ad")
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
                    val imageUrl = resolveAppUrlForOrigin(
                        json.optString("image_url", ""),
                        APP_ORIGIN,
                    )
                    val duration = clampSplashDuration(
                        json.optInt("duration", DEFAULT_SPLASH_DURATION)
                    )
                    val maxDailyViews = clampSplashDailyViews(
                        json.optInt("max_daily_views", DEFAULT_SPLASH_MAX_DAILY_VIEWS)
                    )

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
            .setDomain(APP_HOST)
            .setHttpAllowed(APP_SCHEME == "http")
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
            addJavascriptInterface(AppBridge(), "MbaiAndroid")

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url
                    if (url != null && isInternalWebUrl(url.toString())) {
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

            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                if (!isAllowedDownloadUrl(url)) {
                    openExternalUrl(url)
                    return@setDownloadListener
                }
                requestDownload(
                    DownloadSpec(
                        url = url,
                        userAgent = userAgent.orEmpty(),
                        contentDisposition = contentDisposition.orEmpty(),
                        mimeType = mimeType.orEmpty(),
                    )
                )
            }

            val cachedWebAssetVersion = prefs.getInt(WEB_ASSET_VERSION_KEY, -1)
            if (cachedWebAssetVersion != BuildConfig.VERSION_CODE) {
                clearCache(true)
                clearHistory()
                prefs.edit().putInt(WEB_ASSET_VERSION_KEY, BuildConfig.VERSION_CODE).apply()
            }
            val cookies = cookieManager.getCookie(APP_ORIGIN)
            val hasSession = hasSessionCookie(cookies)
            if (hasSession) {
                loadUrl("$APP_ORIGIN/")
            } else {
                loadUrl("$APP_ORIGIN/app_login.html")
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
        splashCountdownRunnable?.let(mainHandler::removeCallbacks)
        splashCountdownRunnable = null
        pendingDownload = null
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

    private fun requestDownload(download: DownloadSpec) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(download)
    }

    private fun enqueueDownload(download: DownloadSpec) {
        try {
            val filename = suggestDownloadFileName(
                download.url,
                download.requestedFilename
                    .takeIf(String::isNotBlank)
                    ?.let { """attachment; filename="$it"""" }
                    ?: download.contentDisposition,
                download.mimeType,
            )
            val request = DownloadManager.Request(Uri.parse(download.url))
                .setTitle(filename)
                .setDescription("正在下载")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_PICTURES,
                    "墨白/$filename",
                )
            if (download.mimeType.isNotBlank()) {
                request.setMimeType(download.mimeType)
            }
            if (download.userAgent.isNotBlank()) {
                request.addRequestHeader("User-Agent", download.userAgent)
            }
            if (isInternalWebUrl(download.url)) {
                CookieManager.getInstance().getCookie(download.url)
                    ?.takeIf(String::isNotBlank)
                    ?.let { request.addRequestHeader("Cookie", it) }
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            showToast("已开始下载：$filename")
        } catch (_: Exception) {
            showToast("下载启动失败，请稍后重试")
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showMessage(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private inner class AppBridge {
        @JavascriptInterface
        fun downloadImage(url: String, filename: String, mimeType: String) {
            mainHandler.post {
                if (!isAllowedDownloadUrl(url)) {
                    showToast("下载地址无效或不安全")
                    return@post
                }
                requestDownload(
                    DownloadSpec(
                        url = url,
                        userAgent = webView.settings.userAgentString.orEmpty(),
                        contentDisposition = "",
                        mimeType = mimeType,
                        requestedFilename = filename,
                    )
                )
            }
        }

        @JavascriptInterface
        fun getCacheSize(): String = directorySize(cacheDir).toString()

        @JavascriptInterface
        fun clearCache() {
            val before = directorySize(cacheDir)
            mainHandler.post {
                webView.clearCache(true)
                scope.launch {
                    cacheDir.listFiles().orEmpty().forEach(::deleteCacheEntry)
                    val after = directorySize(cacheDir)
                    val script = """
                        window.dispatchEvent(new CustomEvent('mbai:cache-cleared', {
                          detail: { freedBytes: ${maxOf(0L, before - after)}, currentBytes: $after }
                        }));
                    """.trimIndent()
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed && ::webView.isInitialized) {
                            webView.evaluateJavascript(script, null)
                        }
                    }
                }
            }
        }
    }

    private fun directorySize(file: File): Long = runCatching {
        if (!file.exists()) 0L
        else if (file.isFile) file.length()
        else file.listFiles().orEmpty().sumOf(::directorySize)
    }.getOrDefault(0L)

    private fun deleteCacheEntry(file: File) {
        runCatching {
            if (file.isDirectory) file.listFiles().orEmpty().forEach(::deleteCacheEntry)
            file.delete()
        }
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
        private const val DEFAULT_SPLASH_DURATION = 3
        private const val DEFAULT_SPLASH_MAX_DAILY_VIEWS = 3
        private const val MIN_SPLASH_DURATION = 1
        private const val MAX_SPLASH_DURATION = 15
        private const val MIN_SPLASH_DAILY_VIEWS = 0
        private const val MAX_SPLASH_DAILY_VIEWS = 20
        private val APP_ORIGIN = BuildConfig.APP_ORIGIN.trimEnd('/')
        private val APP_URI = URI(APP_ORIGIN)
        private val APP_SCHEME = APP_URI.scheme
        private val APP_HOST = APP_URI.host
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

        internal fun isInternalWebUrl(url: String): Boolean =
            isInternalWebUrlForOrigin(url, APP_ORIGIN)

        internal fun isInternalWebUrlForOrigin(url: String, origin: String): Boolean = try {
            val uri = URI(url)
            val originUri = URI(origin)
            uri.scheme.equals(originUri.scheme, ignoreCase = true) &&
                uri.host.equals(originUri.host, ignoreCase = true) &&
                normalizedPort(uri) == normalizedPort(originUri)
        } catch (_: Exception) {
            false
        }

        private fun normalizedPort(uri: URI): Int =
            if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

        internal fun resolveAppUrlForOrigin(value: String, origin: String): String {
            val trimmed = value.trim()
            return if (trimmed.startsWith("/")) {
                "${origin.trimEnd('/')}$trimmed"
            } else {
                trimmed
            }
        }

        internal fun isAllowedExternalUrl(url: String): Boolean = try {
            URI(url).scheme?.lowercase(Locale.ROOT) in EXTERNAL_SCHEMES
        } catch (_: Exception) {
            false
        }

        internal fun isAllowedDownloadUrl(url: String): Boolean = try {
            val scheme = URI(url).scheme?.lowercase(Locale.ROOT)
            scheme == "https" || (scheme == "http" && isInternalWebUrl(url))
        } catch (_: Exception) {
            false
        }

        internal fun clampSplashDuration(value: Int): Int =
            value.coerceIn(MIN_SPLASH_DURATION, MAX_SPLASH_DURATION)

        internal fun clampSplashDailyViews(value: Int): Int =
            value.coerceIn(MIN_SPLASH_DAILY_VIEWS, MAX_SPLASH_DAILY_VIEWS)

        internal fun suggestDownloadFileName(
            url: String,
            contentDisposition: String?,
            mimeType: String?,
        ): String {
            val encodedName = Regex(
                """filename\*\s*=\s*UTF-8''([^;]+)""",
                RegexOption.IGNORE_CASE,
            ).find(contentDisposition.orEmpty())?.groupValues?.getOrNull(1)
            val plainName = Regex(
                """filename\s*=\s*(?:"([^"]+)"|([^;]+))""",
                RegexOption.IGNORE_CASE,
            ).find(contentDisposition.orEmpty())?.let {
                it.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)
                    ?: it.groupValues.getOrNull(2)
            }
            val decodedName = encodedName?.let {
                runCatching {
                    URLDecoder.decode(it, StandardCharsets.UTF_8.name())
                }.getOrNull()
            }
            val pathName = runCatching {
                URI(url).path.substringAfterLast('/').takeIf(String::isNotBlank)
            }.getOrNull()
            var candidate = decodedName ?: plainName ?: pathName ?: "mbai-download"
            candidate = candidate
                .replace(Regex("""[\u0000-\u001f/\\:*?"<>|]"""), "_")
                .trim()
                .trim('.')
                .ifBlank { "mbai-download" }
            if (!candidate.substringAfterLast('.', "").contains(Regex("""[A-Za-z0-9]{1,8}"""))) {
                val extension = when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    "application/zip" -> "zip"
                    "application/json" -> "json"
                    "application/pdf" -> "pdf"
                    else -> ""
                }
                if (extension.isNotEmpty()) candidate += ".$extension"
            }
            return candidate.take(120)
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

    private data class DownloadSpec(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String,
        val requestedFilename: String = "",
    )
}
