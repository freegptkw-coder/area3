package com.aria.assistant

import androidx.lifecycle.ViewModel

class MainHomeViewModel : ViewModel() {
    var logoAnimating: Boolean = true
        private set

    fun pauseLogoAnimation() {
        logoAnimating = false
    }

    fun resumeLogoAnimation() {
        logoAnimating = true
    }
}
