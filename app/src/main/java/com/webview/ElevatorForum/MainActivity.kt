// ⭐ 핵심 변경 포함 완성본
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
import android.webkit.*
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
    private var lastSentToken: String? = null

    private val appDeviceId: String by lazy { getStableDeviceId() }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchAndSendFcmToken()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        setupWebView()

        applyDeviceCookies(getString(R.string.start_url))
        webView.loadUrl(getString(R.string.start_url), buildAppHeaders())

        requestPushPermissionIfNeeded()
    }

    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "$userAgentString ElevatorForumApp/6.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                applyDeviceCookies(url)
                view?.loadUrl(url, buildAppHeaders())
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectAppGlobals()

                // ⭐ 로그인 이후 1회만 토큰 전송
                webView.evaluateJavascript(
                    "(function(){return window.mb_id || window.g5_user_id || ''})()"
                ) { user ->
                    if (!user.isNullOrBlank() && user != "\"\"") {
                        lastSentToken?.let { sendTokenToServer(it) }
                    }
                }
            }
        }
    }

    private fun fetchAndSendFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener {
            if (!it.isNullOrBlank()) {
                lastSentToken = it
            }
        }
    }

    // ⭐ fetch → form submit 방식으로 변경 (핵심)
    private fun sendTokenToServer(token: String) {

        val safeToken = token.replace("'", "")
        val safeDeviceId = appDeviceId.replace("'", "")
        val tokenUrl = getString(R.string.token_url)

        val js = """
        (function(){
            try{
                var uid = window.mb_id || window.g5_user_id || '';
                if(!uid) return;

                var form = document.createElement('form');
                form.method = 'POST';
                form.action = '$tokenUrl';

                function add(name,value){
                    var i = document.createElement('input');
                    i.type='hidden';
                    i.name=name;
                    i.value=value;
                    form.appendChild(i);
                }

                add('token','$safeToken');
                add('device','android');
                add('device_id','$safeDeviceId');
                add('user_idx',uid);

                document.body.appendChild(form);
                form.submit();

            }catch(e){}
        })();
        """

        webView.evaluateJavascript(js, null)
    }

    private fun injectAppGlobals() {
        val js = """
            window.APP_DEVICE_ID='${appDeviceId}';
            window.APP_CLIENT='android';
        """
        webView.evaluateJavascript(js, null)
    }

    private fun getStableDeviceId(): String {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return "android-$id"
    }

    private fun buildAppHeaders(): Map<String, String> {
        return mapOf(
            "X-App-Device-Id" to appDeviceId,
            "X-App-Client" to "android"
        )
    }

    private fun applyDeviceCookies(url: String) {
        val cm = CookieManager.getInstance()
        val id = URLEncoder.encode(appDeviceId, "UTF-8")

        cm.setCookie(url, "ef_device_id=$id; path=/")
        cm.setCookie(url, "ef_app=1; path=/")
        cm.setCookie(url, "ef_app_client=android; path=/")
        cm.flush()
    }

    private fun requestPushPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            if (granted == PackageManager.PERMISSION_GRANTED) {
                fetchAndSendFcmToken()
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            fetchAndSendFcmToken()
        }
    }
}