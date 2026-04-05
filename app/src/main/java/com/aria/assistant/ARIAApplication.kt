package com.aria.assistant

import android.app.Application
import com.aria.assistant.theme.ThemeManager

class ARIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // DynamicColors removed - it conflicts with our custom gradient theme system
        ThemeManager.applySavedTheme(this)
        ErrorRecoveryManager.install(this)

        Thread {
            ErrorRecoveryManager.processPendingRecovery(this)
        }.start()
    }
}
