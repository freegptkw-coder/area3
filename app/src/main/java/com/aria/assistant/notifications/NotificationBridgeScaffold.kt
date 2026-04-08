package com.aria.assistant.notifications

import android.content.Context

object NotificationBridgeScaffold {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_COUNT = "notif_bridge_count"
    private const val KEY_LAST_AT = "notif_bridge_last_at"
    private const val KEY_LAST_PACKAGE = "notif_bridge_last_package"
    private const val KEY_LAST_TITLE = "notif_bridge_last_title"

    fun recordNotification(context: Context, packageName: String, title: String) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_COUNT, count)
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putString(KEY_LAST_PACKAGE, packageName)
            .putString(KEY_LAST_TITLE, title)
            .apply()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COUNT, 0)
        val lastPackage = prefs.getString(KEY_LAST_PACKAGE, "-").orEmpty()
        val lastTitle = prefs.getString(KEY_LAST_TITLE, "-").orEmpty()
        return "events=$count, last=$lastPackage/$lastTitle"
    }
}
