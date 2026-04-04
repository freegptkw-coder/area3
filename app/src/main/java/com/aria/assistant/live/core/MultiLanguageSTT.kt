package com.aria.assistant.live.core

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.util.Locale

/**
 * Update #5: Multi-Language STT Support - 7 locale support with auto-detect.
 * Dynamically switches STT language based on user's detected language.
 */
class MultiLanguageSTT(
    private val context: Context,
    private val sttFactory: (locale: Locale) -> StreamingSttGateway
) {
    companion object {
        private const val TAG = "MultiLangSTT"
        val SUPPORTED_LOCALES = mapOf(
            "en" to Locale.US,
            "bn" to Locale("bn", "BD"),
            "hi" to Locale("hi", "IN"),
            "es" to Locale("es", "ES"),
            "fr" to Locale.FRENCH,
            "ar" to Locale("ar", "SA"),
            "zh" to Locale.CHINESE
        )
    }

    private var currentGateway: StreamingSttGateway? = null
    private var currentLocale = Locale.getDefault()

    fun switchLanguage(langCode: String) {
        val locale = SUPPORTED_LOCALES[langCode]
        if (locale == null) {
            Log.w(TAG, "Unsupported language: $langCode")
            return
        }
        currentLocale = locale
        recreateGateway()
        Log.i(TAG, "Switched STT language to: $langCode ($locale)")
    }

    fun autoDetectAndSet(locale: Locale) {
        val langCode = SUPPORTED_LOCALES.entries
            .find { it.value.language == locale.language }
            ?.key ?: "en"
        currentLocale = locale
        recreateGateway()
        Log.i(TAG, "Auto-detected language: $langCode locale=$locale")
    }

    fun getGateway(onEvent: (SttTranscriptEvent) -> Unit): StreamingSttGateway {
        currentGateway?.stop()
        val gw = sttFactory(currentLocale)
        currentGateway = gw
        return gw
    }

    private fun recreateGateway() {
        currentGateway?.stop()
    }

    fun getCurrentLanguage(): String = currentLocale.language
    fun getDisplayName(): String =
        SUPPORTED_LOCALES.entries.find { it.value == currentLocale }?.key ?: currentLocale.language

    fun isSupported(langCode: String): Boolean = SUPPORTED_LOCALES.containsKey(langCode)
}
