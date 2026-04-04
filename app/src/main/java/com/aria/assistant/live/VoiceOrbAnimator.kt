package com.aria.assistant.live

import android.animation.ValueAnimator
import android.graphics.Paint
import android.util.Log
import android.view.View
import com.aria.assistant.live.core.VoiceSessionState

/**
 * Update #13: Gemini-like pulsing orb animation for ARIA voice assistant.
 * State-aware animations: idle, listening, thinking, speaking
 */
class VoiceOrbAnimator(
    private val view: View
) {
    companion object {
        private const val TAG = "OrbAnimator"
        private const val ANIM_DURATION_IDLE = 2000L
        private const val ANIM_DURATION_THINKING = 800L
        private const val ANIM_DURATION_SPEAKING = 400L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private var currentAnimator: ValueAnimator? = null
    private var currentAlpha = 0.5f
    private var currentState = VoiceSessionState.IDLE

    fun updateState(newState: VoiceSessionState) {
        if (newState == currentState) return
        currentState = newState
        animateForState(newState)
    }

    private fun animateForState(state: VoiceSessionState) {
        currentAnimator?.cancel()
        currentAnimator = null

        val (startAlpha, endAlpha, duration) = when (state) {
            VoiceSessionState.IDLE -> Triple(0.3f, 0.7f, ANIM_DURATION_IDLE)
            VoiceSessionState.LISTENING -> Triple(0.5f, 1.0f, ANIM_DURATION_THINKING)
            VoiceSessionState.SPEAKING -> Triple(0.6f, 1.0f, ANIM_DURATION_SPEAKING)
            VoiceSessionState.THINKING -> Triple(0.4f, 0.9f, ANIM_DURATION_THINKING)
            VoiceSessionState.ERROR_RECOVERY -> Triple(0.8f, 0.3f, 500L)
            else -> Triple(0.3f, 0.7f, ANIM_DURATION_IDLE)
        }

        val animator = ValueAnimator.ofFloat(startAlpha, endAlpha).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animation ->
                currentAlpha = animation.animatedValue as Float
                paint.alpha = (currentAlpha * 255).toInt()
                view.invalidate()
            }
            start()
        }
        currentAnimator = animator
        Log.d(TAG, "Orb animation started for state: $state")
    }

    fun getPaint(): Paint = paint

    fun stop() {
        currentAnimator?.cancel()
        currentAnimator = null
        Log.d(TAG, "Orb animation stopped")
    }
}
