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

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString().orEmpty()
                if (url.isBlank()) return false

                // 댓글 앵커 링크는 WebView가 그대로 처리
                if (url.contains("#")) {
                    return false
                }

                return when {
                    url.startsWith("http://") || url.startsWith("https://") -> {
                        // 일반 웹 주소는 WebView 기본 처리
                        false
                    }
                    else -> {
                        openUrl(url)
                    }
                }
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

        webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            if (!url.isNullOrBlank()) {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        })
    }

    private fun configureBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        clearAppNotifications()
        applyDeviceCookies(getString(R.string.start_url))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadInitialUrl(intent)
        clearAppNotifications()
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

        val uri = Uri.parse(url)
        pendingAnchor = uri.fragment

        applyDeviceCookies(url)

        runCatching {
            if (webView.url != url || lastLoadedPushUrl != url) {
                lastLoadedPushUrl = url
                webView.loadUrl(url, buildAppHeaders())
            } else {
                scrollToPendingAnchor()
            }
        }
    }

    private fun scrollToPendingAnchor() {
        val anchor = pendingAnchor ?: return

        val js = """
            (function() {
                try {
                    var id = ${jsonString(anchor)};
                    var tries = 0;
                    var done = false;

                    function findEl() {
                        var el = document.getElementById(id);

                        if (!el) {
                            el = document.querySelector('[id="' + id + '"]');
                        }

                        if (!el) {
                            var byName = document.getElementsByName(id);
                            if (byName && byName.length > 0) {
                                el = byName[0];
                            }
                        }

                        return el;
                    }

                    function run() {
                        if (done) return;

                        tries++;
                        var el = findEl();

                        if (el) {
                            done = true;

                            var rect = el.getBoundingClientRect();
                            var absoluteTop = window.pageYOffset + rect.top;
                            var targetTop = absoluteTop - (window.innerHeight * 0.3);

                            window.scrollTo({
                                top: targetTop < 0 ? 0 : targetTop,
                                behavior: "auto"
                            });

                            return;
                        }

                        if (tries < 10) {
                            setTimeout(run, 300);
                        }
                    }

                    run();
                } catch (e) {}
            })();
        """.trimIndent()

        webView.postDelayed({
            runCatching { webView.evaluateJavascript(js, null) }
            pendingAnchor = null
        }, 900)
    }

    private fun openUrl(url: String): Boolean {
        if (url.isBlank()) return true

        return when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                false
            }

            url.startsWith("intent:") -> {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)

                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrBlank()) {
                            applyDeviceCookies(fallbackUrl)
                            webView.loadUrl(fallbackUrl, buildAppHeaders())
                        }
                    }
                } catch (_: Exception) {
                }
                true
            }

            url.startsWith("nmap://") ||
            url.startsWith("kakaomap://") ||
            url.startsWith("tmmap://") -> {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    }
                } catch (_: Exception) {
                }
                true
            }

            url.startsWith("tel:") -> {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url)))
                } catch (_: Exception) {
                }
                true
            }

            url.startsWith("mailto:") || url.startsWith("sms:") -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: ActivityNotFoundException) {
                }
                true
            }

            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                }
                true
            }
        }
    }

    private fun clearAppNotifications() {
        runCatching { NotificationManagerCompat.from(this).cancelAll() }
    }

    private fun requestPushPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                fetchAndSendFcmToken()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            fetchAndSendFcmToken()
        }
    }

    private fun fetchAndSendFcmToken() {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    lastSentToken = token
                    sendTokenToServer(token)
                }
            }
            .addOnFailureListener {
            }
    }

    private fun sendTokenToServer(token: String) {
        val safeToken = token
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")

        val safeDeviceId = appDeviceId
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")

        val tokenUrl = getString(R.string.token_url)

        val js = """
            (function() {
                try {
                    var uid = '';

                    if (window.APP_USER_ID) uid = String(window.APP_USER_ID);
                    if (!uid && window.mb_id) uid = String(window.mb_id);
                    if (!uid && window.g5_user_id) uid = String(window.g5_user_id);
                    if (!uid && window.member && window.member.mb_id) uid = String(window.member.mb_id);

                    var form = 'token=' + encodeURIComponent('$safeToken') +
                               '&device=android' +
                               '&device_id=' + encodeURIComponent('$safeDeviceId');

                    if (uid) {
                        form += '&user_idx=' + encodeURIComponent(uid);
                    }

                    fetch('$tokenUrl', {
                        method: 'POST',
                        credentials: 'include',
                        headers: {
                            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                            'X-App-Device-Id': '$safeDeviceId',
                            'X-App-Client': 'android'
                        },
                        body: form
                    });
                } catch (e) {}
            })();
        """.trimIndent()

        runOnUiThread {
            runCatching { webView.evaluateJavascript(js, null) }
        }
    }

    private fun injectAppGlobals() {
        val safeDeviceId = appDeviceId
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "")
            .replace("\r", "")

        val js = """
            (function() {
                try {
                    window.APP_DEVICE_ID = '$safeDeviceId';
                    window.APP_CLIENT = 'android';
                    window.IS_ELEVATOR_FORUM_APP = true;
                } catch (e) {}
            })();
        """.trimIndent()

        runCatching { webView.evaluateJavascript(js, null) }
    }

    private fun getStableDeviceId(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (!androidId.isNullOrBlank()) {
            "android-$androidId"
        } else {
            "android-unknown"
        }
    }

    private fun buildAppHeaders(): Map<String, String> {
        return mapOf(
            "X-App-Device-Id" to appDeviceId,
            "X-App-Client" to "android"
        )
    }

    private fun applyDeviceCookies(url: String) {
        val cookieManager = CookieManager.getInstance()
        val encodedId = runCatching { URLEncoder.encode(appDeviceId, "UTF-8") }.getOrDefault(appDeviceId)

        val commonCookies = listOf(
            "ef_device_id=$encodedId; path=/; max-age=315360000",
            "ef_app=1; path=/; max-age=315360000",
            "ef_app_client=android; path=/; max-age=315360000"
        )

        runCatching {
            commonCookies.forEach { cookieManager.setCookie(url, it) }
        }

        runCatching {
            val uri = Uri.parse(url)
            val host = uri.host ?: ""
            if (host.isNotBlank()) {
                commonCookies.forEach { raw ->
                    cookieManager.setCookie(
                        "${uri.scheme}://$host",
                        "$raw; domain=$host"
                    )
                }
            }
        }

        runCatching { cookieManager.flush() }
    }

    private fun jsonString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\""
    }
}