package com.aria.assistant.integrations

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.aria.assistant.notifications.NotificationBridgeScaffold

data class IntegrationCapability(
    val key: String,
    val title: String,
    val available: Boolean,
    val details: String
)

object IntegrationCapabilityManager {

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return enabled?.contains(context.packageName) == true
    }

    fun collect(context: Context): List<IntegrationCapability> {
        val whatsappInstalled = isInstalled(context, "com.whatsapp")
        val browserInstalled = isInstalled(context, "com.android.chrome") || isInstalled(context, "org.mozilla.firefox")
        val facebookInstalled = isInstalled(context, "com.facebook.katana")
        val canvaInstalled = isInstalled(context, "com.canva.editor")

        val sendSms = hasPermission(context, Manifest.permission.SEND_SMS)
        val readSms = hasPermission(context, Manifest.permission.READ_SMS)

        return listOf(
            IntegrationCapability(
                key = "whatsapp",
                title = "WhatsApp automation",
                available = whatsappInstalled,
                details = if (whatsappInstalled) "Installed" else "Install WhatsApp"
            ),
            IntegrationCapability(
                key = "sms",
                title = "SMS actions",
                available = sendSms,
                details = if (sendSms) {
                    if (readSms) "SEND/READ granted" else "SEND granted (READ optional missing)"
                } else {
                    "SEND_SMS permission missing"
                }
            ),
            IntegrationCapability(
                key = "browser",
                title = "Browser actions",
                available = browserInstalled,
                details = if (browserInstalled) "Browser app detected" else "No supported browser detected"
            ),
            IntegrationCapability(
                key = "facebook",
                title = "Facebook automation",
                available = facebookInstalled,
                details = if (facebookInstalled) "Installed" else "Install Facebook"
            ),
            IntegrationCapability(
                key = "canva",
                title = "Canva automation",
                available = canvaInstalled,
                details = if (canvaInstalled) "Installed" else "Install Canva"
            ),
            IntegrationCapability(
                key = "notification_listener",
                title = "Notification listener",
                available = notificationListenerEnabled(context),
                details = if (notificationListenerEnabled(context)) {
                    "Enabled (${NotificationBridgeScaffold.summary(context)})"
                } else {
                    "Enable in system settings"
                }
            )
        )
    }
}
