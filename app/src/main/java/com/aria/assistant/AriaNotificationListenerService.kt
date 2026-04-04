package com.aria.assistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import com.aria.assistant.multitask.AriaTaskRuntime
import com.aria.assistant.multitask.AriaTaskTypes
import com.aria.assistant.multitask.TaskPriority
import com.aria.assistant.multitask.TaskRequest
import java.util.ArrayDeque
import java.util.Locale

class AriaNotificationListenerService : NotificationListenerService(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayDeque<String>()
    private var lastSpokenAtMs: Long = 0L

    private val allowedPackages = setOf(
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.facebook.orca",
        "com.instagram.android"
    )

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val prefs = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
        if (!prefs.getBoolean("notification_listener_enabled", false)) return

        val packageName = sbn.packageName.orEmpty()
        if (packageName !in allowedPackages) return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = packageName.substringAfterLast('.')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        val compact = buildCompactSummary(appLabel, title, text)

        AriaTaskRuntime.orchestrator.scheduleTask(
            TaskRequest(
                title = "Notification • $appLabel",
                type = AriaTaskTypes.NOTIFICATION_ANNOUNCE,
                priority = TaskPriority.HIGH,
                description = compact
            )
        ) {
            updateProgress(100, compact)
        }

        if (prefs.getBoolean("notification_read_aloud_enabled", false)) {
            maybeSpeak(compact)
        }
    }

    private fun buildCompactSummary(appLabel: String, title: String, body: String): String {
        val sanitized = body.replace(Regex("\\s+"), " ").trim()
        val clippedBody = sanitized.take(120)
        return if (title.isNotBlank()) {
            "New $appLabel message from $title: $clippedBody"
        } else {
            "New $appLabel update: $clippedBody"
        }
    }

    private fun maybeSpeak(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastSpokenAtMs < 1500) return
        lastSpokenAtMs = now

        if (!ttsReady) {
            pendingSpeech.addLast(text)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "notif_${System.currentTimeMillis()}")
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) return

        tts?.language = Locale.US
        while (pendingSpeech.isNotEmpty()) {
            val msg = pendingSpeech.removeFirst()
            tts?.speak(msg, TextToSpeech.QUEUE_ADD, null, "notif_${System.currentTimeMillis()}")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
