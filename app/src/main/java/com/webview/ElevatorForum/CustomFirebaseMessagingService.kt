package com.webview.ElevatorForum

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.math.abs

class CustomFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        createChannel()

        val title = message.data["title"]
            ?: message.notification?.title
            ?: getString(R.string.app_name)

        val rawBody = message.data["body"]
            ?: message.notification?.body
            ?: "새 알림이 도착했습니다."

        val targetUrl = message.data["url"]
            ?: extractUrlFromText(rawBody)
            ?: getString(R.string.start_url)

        val category = (message.data["category"] ?: "general").lowercase()
        val roomId = message.data["room_id"] ?: ""
        val cleanBody = stripUrlFromText(rawBody).ifBlank { "새 알림이 도착했습니다." }
        val notificationId = buildNotificationId(category, roomId, targetUrl)

        val intent = Intent(this, IntroActivity::class.java).apply {
            putExtra("push_url", targetUrl)
            putExtra("push_category", category)
            putExtra("push_room_id", roomId)
            data = Uri.parse(targetUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = Uri.parse("android.resource://${packageName}/${R.raw.elevator_alert}")
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(cleanBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(
                when (category) {
                    "chat" -> NotificationCompat.CATEGORY_MESSAGE
                    "friend_request" -> NotificationCompat.CATEGORY_SOCIAL
                    else -> NotificationCompat.CATEGORY_MESSAGE
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_LIGHTS)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 250, 150, 250))

        if (category == "chat") {
            builder.setOnlyAlertOnce(false)
        }

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(notificationId, builder.build())
        }
    }

    private fun buildNotificationId(category: String, roomId: String, url: String): Int {
        val base = when {
            category == "chat" && roomId.isNotBlank() -> "chat:$roomId"
            category.isNotBlank() -> "$category:$url"
            else -> url
        }
        return abs(base.hashCode())
    }

    private fun extractUrlFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(text)?.value
    }

    private fun stripUrlFromText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.replace(Regex("""https?://[^\s]+"""), "")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val soundUri = Uri.parse("android.resource://${packageName}/${R.raw.elevator_alert}")
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "엘리베이터포럼 알림",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "엘리베이터포럼 채팅/알림 푸시"
                    enableVibration(true)
                    enableLights(true)
                    vibrationPattern = longArrayOf(0, 250, 150, 250)
                    setSound(soundUri, attrs)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "elevator_forum_alert_v13"
    }
}
