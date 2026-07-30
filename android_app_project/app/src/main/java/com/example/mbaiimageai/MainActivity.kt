package com.example.mbaiimageai

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
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
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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
    private var updateDownloadId: Long = -1L
    private var pendingUpdate: AppUpdateSpec? = null
    private var pendingUpdateFile: File? = null
    private var updatePromptVisible = false
    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val completedId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (completedId != updateDownloadId) return
            handleCompletedUpdateDownload(completedId)
        }
    }
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
        ContextCompat.registerReceiver(
            this,
            updateDownloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )
        restorePendingUpdateDownload()

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
        checkForAppUpdate(manual = false)
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

    private inner class AndroidUpdateBridge {
        @JavascriptInterface
        fun checkForUpdates() {
            mainHandler.post { checkForAppUpdate(manual = true) }
        }

        @JavascriptInterface
        fun downloadImage(url: String, filename: String) {
            mainHandler.post {
                if (!isSafeDownloadUrl(url)) {
                    showToast("下载地址不安全")
                    return@post
                }
                val safeFilename = suggestDownloadFileName(
                    "",
                    """attachment; filename="$filename"""",
                    "image/png",
                )
                requestDownload(
                    DownloadSpec(
                        url = url,
                        userAgent = webView.settings.userAgentString.orEmpty(),
                        contentDisposition = """attachment; filename="$safeFilename"""",
                        mimeType = "image/*",
                        directoryType = Environment.DIRECTORY_PICTURES,
                        subdirectory = GENERATED_IMAGE_DIRECTORY,
                    )
                )
            }
        }

        @JavascriptInterface
        fun saveGeneratedImage(url: String, filename: String, taskId: String) {
            mainHandler.post {
                if (!isSafeDownloadUrl(url) || isInternalWebUrl(url) || isInlineImageDataUrl(url)) {
                    return@post
                }
                val safeFilename = suggestDownloadFileName(
                    "",
                    """attachment; filename="$filename"""",
                    "image/png",
                )
                val trackingKey = generatedImageTrackingKey(taskId, safeFilename)
                val savedKeys = prefs.getStringSet(GENERATED_IMAGE_KEYS, emptySet()).orEmpty()
                if (trackingKey in savedKeys) return@post
                requestDownload(
                    DownloadSpec(
                        url = url,
                        userAgent = webView.settings.userAgentString.orEmpty(),
                        contentDisposition = """attachment; filename="$safeFilename"""",
                        mimeType = "image/*",
                        directoryType = Environment.DIRECTORY_PICTURES,
                        subdirectory = GENERATED_IMAGE_DIRECTORY,
                        trackingKey = trackingKey,
                        silent = true,
                    )
                )
            }
        }
    }

    private fun checkForAppUpdate(manual: Boolean) {
        if (BuildConfig.FLAVOR != "production") {
            if (manual) showToast("本地测试版不使用线上自动更新")
            return
        }
        val now = System.currentTimeMillis()
        val lastCheck = prefs.getLong(UPDATE_LAST_CHECK_KEY, 0L)
        if (!manual && now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(UPDATE_LAST_CHECK_KEY, now).apply()
        scope.launch {
            val result = runCatching {
                val connection = URL(
                    "$APP_ORIGIN/api/public/android-update?version_code=${BuildConfig.VERSION_CODE}"
                ).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/json")
                try {
                    if (connection.responseCode != 200) {
                        throw IllegalStateException("更新服务返回 ${connection.responseCode}")
                    }
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    if (!json.optBoolean("update_available", false)) return@runCatching null
                    val versionCode = json.optInt("version_code", 0)
                    val downloadUrl = resolveAppUrlForOrigin(
                        json.optString("download_url", ""),
                        APP_ORIGIN,
                    )
                    require(versionCode > BuildConfig.VERSION_CODE) { "更新版本号无效" }
                    require(isSafeDownloadUrl(downloadUrl)) { "更新下载地址不安全" }
                    val packageName = json.optString("package_name", "")
                    val certificateSha256 = json.optString("certificate_sha256", "")
                        .lowercase(Locale.ROOT)
                    val sha256 = json.optString("sha256", "").lowercase(Locale.ROOT)
                    val sizeBytes = json.optLong("size_bytes", 0L)
                    require(packageName == BuildConfig.APPLICATION_ID) { "更新包名不匹配" }
                    require(certificateSha256.matches(Regex("^[0-9a-f]{64}$"))) {
                        "更新签名摘要无效"
                    }
                    require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "更新文件摘要无效" }
                    require(sizeBytes > 0L) { "更新文件大小无效" }
                    AppUpdateSpec(
                        versionCode = versionCode,
                        versionName = json.optString("version_name", versionCode.toString()),
                        mandatory = json.optBoolean("mandatory", false),
                        releaseNotes = json.optString("release_notes", ""),
                        downloadUrl = downloadUrl,
                        packageName = packageName,
                        certificateSha256 = certificateSha256,
                        sha256 = sha256,
                        sizeBytes = sizeBytes,
                    )
                } finally {
                    connection.disconnect()
                }
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { update ->
                        if (update == null) {
                            if (manual) showToast("当前已经是最新版本")
                        } else {
                            val skipped = prefs.getInt(UPDATE_SKIPPED_VERSION_KEY, 0)
                            if (manual || update.mandatory || skipped != update.versionCode) {
                                showAppUpdatePrompt(update)
                            }
                        }
                    },
                    onFailure = {
                        if (manual) showMessage("检查更新失败，请稍后重试")
                    },
                )
            }
        }
    }

    private fun showAppUpdatePrompt(update: AppUpdateSpec) {
        if (updatePromptVisible || isFinishing || isDestroyed) return
        updatePromptVisible = true
        val message = buildString {
            append("发现新版本 ")
            append(update.versionName)
            if (update.mandatory) append("（必须更新）")
            if (update.releaseNotes.isNotBlank()) {
                append("\n\n")
                append(update.releaseNotes)
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("APP更新")
            .setMessage(message)
            .setPositiveButton("下载更新") { _, _ ->
                updatePromptVisible = false
                startAppUpdateDownload(update)
            }
            .setOnDismissListener { updatePromptVisible = false }
        if (update.mandatory) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton("稍后提醒", null)
            builder.setNeutralButton("跳过此版本") { _, _ ->
                prefs.edit().putInt(UPDATE_SKIPPED_VERSION_KEY, update.versionCode).apply()
            }
        }
        builder.show()
    }

    private fun startAppUpdateDownload(update: AppUpdateSpec) {
        try {
            val updateDirectory = File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "updates",
            )
            check(updateDirectory.exists() || updateDirectory.mkdirs()) { "无法创建更新目录" }
            val destination = File(updateDirectory, "MBai-${update.versionCode}.apk")
            destination.delete()
            val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
                .setTitle("墨白 ${update.versionName}")
                .setDescription("正在下载APP更新")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationUri(Uri.fromFile(destination))
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            pendingUpdate = update
            pendingUpdateFile = destination
            updateDownloadId = manager.enqueue(request)
            persistPendingUpdate()
            showToast("已开始下载APP更新")
        } catch (_: Exception) {
            clearPendingUpdate()
            showMessage("更新下载启动失败，请稍后重试")
        }
    }

    private fun handleCompletedUpdateDownload(downloadId: Long) {
        val update = pendingUpdate ?: return
        val destination = pendingUpdateFile ?: return
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        val successful = cursor.use {
            it.moveToFirst() &&
                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                DownloadManager.STATUS_SUCCESSFUL
        }
        updateDownloadId = -1L
        persistPendingUpdate()
        if (!successful || !destination.isFile) {
            destination.delete()
            clearPendingUpdate()
            showMessage("更新下载失败，请重新检查更新")
            return
        }
        scope.launch {
            val verified = runCatching {
                require(destination.length() == update.sizeBytes) { "文件大小不一致" }
                require(fileSha256(destination) == update.sha256) { "SHA-256校验失败" }
                verifyDownloadedApkIdentity(destination, update)
                true
            }.getOrElse { false }
            withContext(Dispatchers.Main) {
                if (!verified) {
                    destination.delete()
                    clearPendingUpdate()
                    showMessage("更新文件校验失败，已自动删除")
                } else {
                    installDownloadedUpdate(destination)
                }
            }
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun persistPendingUpdate() {
        val update = pendingUpdate ?: return
        val file = pendingUpdateFile ?: return
        val payload = JSONObject()
            .put("versionCode", update.versionCode)
            .put("versionName", update.versionName)
            .put("mandatory", update.mandatory)
            .put("releaseNotes", update.releaseNotes)
            .put("downloadUrl", update.downloadUrl)
            .put("packageName", update.packageName)
            .put("certificateSha256", update.certificateSha256)
            .put("sha256", update.sha256)
            .put("sizeBytes", update.sizeBytes)
        prefs.edit()
            .putLong(UPDATE_DOWNLOAD_ID_KEY, updateDownloadId)
            .putString(UPDATE_FILE_PATH_KEY, file.absolutePath)
            .putString(UPDATE_SPEC_KEY, payload.toString())
            .apply()
    }

    private fun clearPendingUpdate() {
        pendingUpdate = null
        pendingUpdateFile = null
        updateDownloadId = -1L
        prefs.edit()
            .remove(UPDATE_DOWNLOAD_ID_KEY)
            .remove(UPDATE_FILE_PATH_KEY)
            .remove(UPDATE_SPEC_KEY)
            .apply()
    }

    private fun restorePendingUpdateDownload() {
        val raw = prefs.getString(UPDATE_SPEC_KEY, null) ?: return
        val filePath = prefs.getString(UPDATE_FILE_PATH_KEY, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            pendingUpdate = AppUpdateSpec(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                mandatory = json.getBoolean("mandatory"),
                releaseNotes = json.getString("releaseNotes"),
                downloadUrl = json.getString("downloadUrl"),
                packageName = json.getString("packageName"),
                certificateSha256 = json.getString("certificateSha256"),
                sha256 = json.getString("sha256"),
                sizeBytes = json.getLong("sizeBytes"),
            )
            pendingUpdateFile = File(filePath)
            updateDownloadId = prefs.getLong(UPDATE_DOWNLOAD_ID_KEY, -1L)
            if (updateDownloadId >= 0L) {
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val cursor = manager.query(DownloadManager.Query().setFilterById(updateDownloadId))
                val status = cursor.use {
                    if (it.moveToFirst()) {
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    } else {
                        DownloadManager.STATUS_FAILED
                    }
                }
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> handleCompletedUpdateDownload(updateDownloadId)
                    DownloadManager.STATUS_FAILED -> {
                        pendingUpdateFile?.delete()
                        clearPendingUpdate()
                    }
                }
            }
        }.onFailure {
            clearPendingUpdate()
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfoForArchive(file: File): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } ?: throw IllegalStateException("无法读取更新 APK 信息")
    }

    @Suppress("DEPRECATION")
    private fun installedPackageInfo(): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            packageManager.getPackageInfo(packageName, flags)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerSha256(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyDownloadedApkIdentity(file: File, update: AppUpdateSpec) {
        val archive = packageInfoForArchive(file)
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        require(archive.packageName == update.packageName) { "APK 包名校验失败" }
        require(archiveVersionCode == update.versionCode.toLong()) { "APK 版本号校验失败" }
        val archiveSigners = signerSha256(archive)
        val installedSigners = signerSha256(installedPackageInfo())
        require(update.certificateSha256 in archiveSigners) { "APK 签名校验失败" }
        require(archiveSigners.intersect(installedSigners).isNotEmpty()) { "APK 签名与当前 APP 不一致" }
    }

    private fun installDownloadedUpdate(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingUpdateFile = file
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            showToast("请允许墨白安装未知应用，然后返回继续安装")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            clearPendingUpdate()
            startActivity(intent)
        } catch (_: Exception) {
            showMessage("无法打开系统安装界面")
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
            addJavascriptInterface(AndroidUpdateBridge(), "MoBaiAndroid")

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
                if (!isSafeDownloadUrl(url)) {
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
        pendingUpdateFile?.takeIf { updateDownloadId == -1L && it.isFile }?.let { file ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                installDownloadedUpdate(file)
            }
        }
        checkForAppUpdate(manual = false)
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
        runCatching { unregisterReceiver(updateDownloadReceiver) }
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
        if (isInlineImageDataUrl(download.url)) {
            saveInlineImage(download)
            return
        }
        try {
            val filename = suggestDownloadFileName(
                download.url,
                download.contentDisposition,
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
                    download.directoryType,
                    if (download.subdirectory.isBlank()) filename else "${download.subdirectory}/$filename",
                )
            if (download.mimeType.isNotBlank()) {
                request.setMimeType(download.mimeType)
            }
            if (download.userAgent.isNotBlank()) {
                request.addRequestHeader("User-Agent", download.userAgent)
            }
            CookieManager.getInstance().getCookie(download.url)
                ?.takeIf(String::isNotBlank)
                ?.let { request.addRequestHeader("Cookie", it) }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            if (download.trackingKey.isNotBlank()) {
                val savedKeys = prefs.getStringSet(GENERATED_IMAGE_KEYS, emptySet()).orEmpty().toMutableSet()
                savedKeys.add(download.trackingKey)
                prefs.edit().putStringSet(GENERATED_IMAGE_KEYS, savedKeys).apply()
            }
            if (!download.silent) {
                showToast("已开始下载：$filename")
            }
        } catch (_: Exception) {
            if (!download.silent) {
                showToast("下载启动失败，请稍后重试")
            }
        }
    }

    private fun generatedImageTrackingKey(taskId: String, filename: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${taskId.trim()}|${filename.trim()}".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun saveInlineImage(download: DownloadSpec) {
        scope.launch {
            val result = runCatching {
                val separator = download.url.indexOf(',')
                require(separator > 5) { "Invalid image data URL" }
                val metadata = download.url.substring(5, separator)
                require(
                    metadata.split(';').any { it.equals("base64", ignoreCase = true) }
                ) { "Image data URL is not base64 encoded" }
                val mimeType = metadata.substringBefore(';')
                    .trim()
                    .ifBlank { download.mimeType.substringBefore(';').trim() }
                require(mimeType.startsWith("image/", ignoreCase = true)) {
                    "Unsupported inline file type"
                }
                val bytes = Base64.decode(download.url.substring(separator + 1), Base64.DEFAULT)
                require(bytes.isNotEmpty()) { "Empty inline image" }
                val filename = suggestDownloadFileName(
                    "",
                    download.contentDisposition,
                    mimeType,
                )
                saveBytesToDownloads(filename, mimeType, bytes)
                filename
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { showToast("已保存至下载目录：$it") },
                    onFailure = { showToast("图片保存失败，请稍后重试") },
                )
            }
        }
    }

    private fun saveBytesToDownloads(filename: String, mimeType: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = checkNotNull(
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ) { "Unable to create download" }
            try {
                checkNotNull(contentResolver.openOutputStream(uri)).use { output ->
                    output.write(bytes)
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
            return
        }

        val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        check(directory.exists() || directory.mkdirs()) { "Unable to create download directory" }
        var destination = File(directory, filename)
        if (destination.exists()) {
            val stem = destination.nameWithoutExtension
            val extension = destination.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
            destination = File(directory, "${stem}-${System.currentTimeMillis()}$extension")
        }
        FileOutputStream(destination).use { output -> output.write(bytes) }
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
        private const val UPDATE_LAST_CHECK_KEY = "android_update_last_check"
        private const val UPDATE_SKIPPED_VERSION_KEY = "android_update_skipped_version"
        private const val UPDATE_DOWNLOAD_ID_KEY = "android_update_download_id"
        private const val UPDATE_FILE_PATH_KEY = "android_update_file_path"
        private const val UPDATE_SPEC_KEY = "android_update_spec"
        private const val GENERATED_IMAGE_KEYS = "generated_image_download_keys"
        private const val GENERATED_IMAGE_DIRECTORY = "墨白"
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
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

        internal fun isInlineImageDataUrl(url: String): Boolean {
            val header = url.substringBefore(',', missingDelimiterValue = "")
            return header.startsWith("data:image/", ignoreCase = true) &&
                header.split(';').any { it.equals("base64", ignoreCase = true) }
        }

        internal fun isSafeDownloadUrl(url: String): Boolean {
            if (isInlineImageDataUrl(url) || isInternalWebUrl(url)) return true
            return try {
                URI(url).scheme.equals("https", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }

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
        val directoryType: String = Environment.DIRECTORY_DOWNLOADS,
        val subdirectory: String = "",
        val trackingKey: String = "",
        val silent: Boolean = false,
    )

    private data class AppUpdateSpec(
        val versionCode: Int,
        val versionName: String,
        val mandatory: Boolean,
        val releaseNotes: String,
        val downloadUrl: String,
        val packageName: String,
        val certificateSha256: String,
        val sha256: String,
        val sizeBytes: Long,
    )
}
