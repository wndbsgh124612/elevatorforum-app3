package com.webview.ElevatorForum

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class IntroActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val pushUrl = intent?.getStringExtra("push_url")
        val deepLink = intent?.data

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtras(intent ?: Intent())
                if (!pushUrl.isNullOrBlank()) {
                    putExtra("push_url", pushUrl)
                }
                if (deepLink != null) {
                    data = deepLink
                }
            })
            finish()
        }, 1200L)
    }
}
