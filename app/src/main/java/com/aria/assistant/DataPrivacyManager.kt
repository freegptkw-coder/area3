package com.aria.assistant.live.core

import android.content.SharedPreferences
import android.util.Log

/**
 * Update #16: Data Privacy Manager - opt-out for screen context, history, location send.
 * Users can toggle privacy per feature type.
 */
class DataPrivacyManager(
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "DataPrivacy"
        const val PREF_SCREEN_CONTEXT = "privacy_screen_context"
        const val PREF_CONVERSATION_HISTORY = "privacy_conversation_history"
        const val PREF_LOCATION_SEND = "privacy_location_send"
        const val PREF_ANALYTICS = "privacy_analytics"

        private const val DEFAULT_SCREEN_CONTEXT = true
        private const val DEFAULT_HISTORY = true
        private const val DEFAULT_LOCATION = true
        private const val DEFAULT_ANALYTICS = true
    }

    fun isScreenContextAllowed(): Boolean =
        prefs.getBoolean(PREF_SCREEN_CONTEXT, DEFAULT_SCREEN_CONTEXT)

    fun isHistoryAllowed(): Boolean =
        prefs.getBoolean(PREF_CONVERSATION_HISTORY, DEFAULT_HISTORY)

    fun isLocationSendAllowed(): Boolean =
        prefs.getBoolean(PREF_LOCATION_SEND, DEFAULT_LOCATION)

    fun isAnalyticsAllowed(): Boolean =
        prefs.getBoolean(PREF_ANALYTICS, DEFAULT_ANALYTICS)

    fun setScreenContextAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(PREF_SCREEN_CONTEXT, allowed).apply()
        Log.i(TAG, "Screen context: $allowed")
    }

    fun setHistoryAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(PREF_CONVERSATION_HISTORY, allowed).apply()
        Log.i(TAG, "Conversation history: $allowed")
    }

    fun setLocationSendAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(PREF_LOCATION_SEND, allowed).apply()
        Log.i(TAG, "Location send: $allowed")
    }

    fun setAnalyticsAllowed(allowed: Boolean) {
        prefs.edit().putBoolean(PREF_ANALYTICS, allowed).apply()
        Log.i(TAG, "Analytics: $allowed")
    }

    /** Get all privacy settings as a summary */
    fun getPrivacySummary(): String {
        val items = listOf(
            "Screen Context: ${if (isScreenContextAllowed()) "ON" else "OFF"}",
            "History: ${if (isHistoryAllowed()) "ON" else "OFF"}",
            "Location: ${if (isLocationSendAllowed()) "ON" else "OFF"}",
            "Analytics: ${if (isAnalyticsAllowed()) "ON" else "OFF"}"
        )
        return items.joinToString("\n")
    }

    /** Block all data collection (strictest mode) */
    fun blockAllData() {
        prefs.edit().apply {
            putBoolean(PREF_SCREEN_CONTEXT, false)
            putBoolean(PREF_CONVERSATION_HISTORY, false)
            putBoolean(PREF_LOCATION_SEND, false)
            putBoolean(PREF_ANALYTICS, false)
        }.apply()
        Log.w(TAG, "All data collection blocked")
    }
}
