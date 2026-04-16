package com.webview.ElevatorForum

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastSentToken: String? = null

    // 푸시로 들어온 앵커(#c_79 같은 댓글 위치) 보관
    private var pendingAnchor: String? = null
    private var lastLoadedPushUrl: String? = null

    // 고정 기기 식별값
    private val appDeviceId: String by lazy { getStableDeviceId() }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchAndSendFcmToken()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swipeRefresh.isEnabled = false
        configureWebView()
        configureBackPress()

        applyDeviceCookies(getString(R.string.start_url))

        loadInitialUrl(intent)
        clearAppNotifications()

        webView.postDelayed({ requestPushPermissionIfNeeded() }, 1800)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
            userAgentString = "$userAgentString ElevatorForumApp/5.1"
        }

        webView.setBackgroundColor(0xFF191919.toInt())
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString().orEmpty()
                return handleExternalOrHttpUrl(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleExternalOrHttpUrl(view, url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (!url.isNullOrBlank()) {
                    applyDeviceCookies(url)
                }

                runCatching { CookieManager.getInstance().flush() }

                injectAppGlobals()
                scrollToPendingAnchor()

                webView.postDelayed({
                    lastSentToken?.let { sendTokenToServer(it) }
                }, 800)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                return try {
                    val chooserIntent = fileChooserParams?.createIntent()
                    if (chooserIntent != null) {
                        fileChooserLauncher.launch(chooserIntent)
                        true
                    } else {
                        this@MainActivity.filePathCallback = null
                        false
                    }
                } catch (_: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }