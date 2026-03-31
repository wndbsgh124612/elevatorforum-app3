package com.webview.ElevatorForum

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var safeTopPx: Int = 0
    private var safeBottomPx: Int = 0

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
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        swipeRefresh.isEnabled = false

        applyWindowInsets()
        configureWebView()
        configureBackPress()
        loadInitialUrl(intent)

        webView.postDelayed({ requestPushPermissionIfNeeded() }, 2500)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(swipeRefresh) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            safeTopPx = systemBars.top
            safeBottomPx = systemBars.bottom

            view.setPadding(0, safeTopPx, 0, safeBottomPx)
            webView.setPadding(0, 0, 0, 0)
            webView.clipToPadding = false

            runCatching { applySafeAreaToWeb() }
            insets
        }
        ViewCompat.requestApplyInsets(swipeRefresh)
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
            userAgentString = "$userAgentString ElevatorForumApp/2.0 RebuilderImproved"
        }

        webView.setBackgroundColor(0xFF191919.toInt())
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                return openUrl(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                runCatching { CookieManager.getInstance().flush() }
                applySafeAreaToWeb()
                disableOverscrollBounceByJs()
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

        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (!url.isNullOrBlank()) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
        })
    }

    private fun applySafeAreaToWeb() {
        val js = """
            (function() {
                try {
                    var topPx = '${'$'}{safeTopPx}px';
                    var bottomPx = '${'$'}{safeBottomPx}px';

                    document.documentElement.style.setProperty('--app-safe-top', topPx);
                    document.documentElement.style.setProperty('--app-safe-bottom', bottomPx);

                    if (!document.getElementById('ef-app-safe-style')) {
                        var style = document.createElement('style');
                        style.id = 'ef-app-safe-style';
                        style.innerHTML = `
                            :root {
                                --app-safe-top: ${'$'}{topPx};
                                --app-safe-bottom: ${'$'}{bottomPx};
                            }
                            html, body {
                                background:#191919 !important;
                                overscroll-behavior-y:none !important;
                            }
                            body::before {
                                content:'';
                                position:fixed;
                                top:0; left:0; right:0;
                                height:calc(var(--app-safe-top) + 8px);
                                background:#191919;
                                z-index:9998;
                                pointer-events:none;
                            }
                            #header, .ef-header, .mobile-header, #hd, .header, .site-header {
                                position:relative;
                                z-index:9999 !important;
                                background:#191919 !important;
                                top:var(--app-safe-top) !important;
                            }
                            #contents_wrap, .contents_wrap, .ef-contents-wrap,
                            #container, #content, .content_wrap, section.sub, section.index {
                                padding-top: calc(var(--app-safe-top) + 30px) !important;
                                box-sizing:border-box !important;
                            }
                            .ef-board, .ef-partner-board-tabs {
                                margin-top:30px !important;
                            }
                            #quick_menu, .rb-bottombar, .bottom-nav, .tabbar, .footer-nav {
                                bottom:calc(var(--app-safe-bottom) + 8px) !important;
                            }
                            #sidebar, #gnb, .mobile-menu, .side-menu, .drawer-menu, .rb-sidebar, .menu-panel {
                                overscroll-behavior-y:contain !important;
                                padding-top:calc(var(--app-safe-top) + 8px) !important;
                                padding-bottom:calc(var(--app-safe-bottom) + 8px) !important;
                            }
                        `;
                        document.head.appendChild(style);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun disableOverscrollBounceByJs() {
        val js = """
            (function() {
                try {
                    document.documentElement.style.overscrollBehaviorY = 'none';
                    document.body.style.overscrollBehaviorY = 'none';
                } catch(e) {}
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun configureBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadInitialUrl(intent)
    }

    override fun onResume() {
        super.onResume()
        runCatching { webView.onResume() }
    }

    override fun onPause() {
        runCatching { webView.onPause() }
        super.onPause()
    }

    override fun onDestroy() {
        runCatching {
            webView.stopLoading()
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {}
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun loadInitialUrl(intent: Intent?) {
        val pushUrl = intent?.getStringExtra("push_url")
        val deepLink = intent?.dataString
        val candidate = when {
            !pushUrl.isNullOrBlank() -> pushUrl
            !deepLink.isNullOrBlank() -> deepLink
            else -> getString(R.string.start_url)
        }
        val url = if (URLUtil.isNetworkUrl(candidate)) candidate else getString(R.string.start_url)
        runCatching { webView.loadUrl(url) }
    }

    private fun openUrl(url: String): Boolean {
        if (url.isBlank()) return true
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> false
            url.startsWith("intent:") -> {
                try { startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME)) } catch (_: Exception) {}
                true
            }
            url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) {}
                true
            }
            else -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                true
            }
        }
    }

    private fun requestPushPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (granted) fetchAndSendFcmToken() else notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            fetchAndSendFcmToken()
        }
    }

    private fun fetchAndSendFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) sendTokenToServer(token)
            }
            .addOnFailureListener { }
    }

    private fun sendTokenToServer(token: String) {
        val safeToken = token
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")

        val tokenUrl = getString(R.string.token_url)

        val js = """
            (function() {
                try {
                    fetch('${'$'}tokenUrl', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8'
                        },
                        body: 'token=' + encodeURIComponent('${'$'}safeToken') + '&device=android'
                    });
                } catch (e) {}
            })();
        """.trimIndent()

        runOnUiThread {
            runCatching { webView.evaluateJavascript(js, null) }
        }
    }
}