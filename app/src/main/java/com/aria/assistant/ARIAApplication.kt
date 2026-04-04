package com.aria.assistant

import android.app.Application
import com.aria.assistant.theme.ThemeManager

class ARIAApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySavedTheme(this)
        ErrorRecoveryManager.install(this)

        Thread {
            ErrorRecoveryManager.processPendingRecovery(this)
        }.start()
    }
}
