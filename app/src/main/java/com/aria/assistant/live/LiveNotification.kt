package com.aria.assistant.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aria.assistant.R

object LiveNotification {

    const val CHANNEL_ID = "aria_live_mode"
    const val NOTIFICATION_ID = 501
    const val ACTION_STOP = "com.aria.assistant.live.STOP"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ARIA Live Mode",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when live mode microphone is active"
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, speaking: Boolean = false): Notification {
        val stopIntent = Intent(context, LiveModeService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            991,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (speaking) R.drawable.ic_stat_aria_speaking else R.drawable.ic_stat_aria_listening)
            .setContentTitle("ARIA Live Mode")
            .setContentText(if (speaking) "Speaking now • Tap Stop anytime" else "Listening with consent. Tap Stop anytime.")
            .setOngoing(true)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }
}
