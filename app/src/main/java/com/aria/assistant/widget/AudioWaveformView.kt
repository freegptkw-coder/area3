package com.aria.assistant.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.aria.assistant.live.core.VoiceSessionState

/**
 * Update #13: Audio waveform visualization - Gemini-like pulsing bars, state-aware colors.
 */
class AudioWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    companion object {
        private const val TAG = "AudioWaveformView"
        private const val BAR_COUNT = 50
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val amplitudes = FloatArray(BAR_COUNT) { 0.2f }
    private var currentState: VoiceSessionState = VoiceSessionState.IDLE

    init {
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 4f
    }

    fun onAudioLevel(level: Float) {
        // Shift amplitudes array left and add new value
        System.arraycopy(amplitudes, 1, amplitudes, 0, BAR_COUNT - 1)
        amplitudes[BAR_COUNT - 1] = level.coerceIn(0.1f, 1.0f)
        invalidate()
    }

    fun updateState(state: VoiceSessionState) {
        currentState = state
        paint.color = when (state) {
            VoiceSessionState.IDLE -> android.graphics.Color.rgb(0x60, 0x80, 0xFF)
            VoiceSessionState.LISTENING -> android.graphics.Color.rgb(0x40, 0xFF, 0x80)
            VoiceSessionState.SPEAKING -> android.graphics.Color.rgb(0xFF, 0x60, 0x40)
            VoiceSessionState.THINKING -> android.graphics.Color.rgb(0xFF, 0xFF, 0x40)
            else -> android.graphics.Color.GRAY
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val barWidth = width / (BAR_COUNT * 1.5f)
        val gap = barWidth * 0.5f

        for (i in 0 until BAR_COUNT) {
            val x = i * (barWidth + gap)
            val barHeight = amplitudes[i] * height * 0.8f
            val y = (height - barHeight) / 2f
            
            canvas.drawRoundRect(x, y, x + barWidth, y + barHeight, barWidth / 2f, barWidth / 2f, paint)
        }
    }

    fun reset() {
        for (i in amplitudes.indices) amplitudes[i] = 0.2f
        invalidate()
    }
}
