package com.aria.assistant

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.aria.assistant.theme.ThemeManager

class ARIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        ThemeManager.applySavedTheme(this)
        ErrorRecoveryManager.install(this)

        Thread {
            ErrorRecoveryManager.processPendingRecovery(this)
        }.start()
    }
}
