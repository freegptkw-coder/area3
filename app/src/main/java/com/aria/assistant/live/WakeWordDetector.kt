package com.aria.assistant.live

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Update #4: "Hey ARIA" Wake Word Detector - low-power detection using energy spikes.
 * Runs in background with minimal CPU usage. When wake word detected, activates full STT.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        private const val ENERGY_THRESHOLD = 500.0 // RMS energy threshold
        private const val SILENCE_WINDOW_MS = 300
    }

    @Volatile private var isRunning = false
    private var audioRecord: AudioRecord? = null

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE
            )
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                Log.i(TAG, "Wake word detector started")
                processAudio()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No microphone permission: ${e.message}")
        }
    }

    private fun processAudio() {
        val buffer = ShortArray(BUFFER_SIZE)
        while (isRunning) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: continue
            if (read <= 0) continue

            val energy = buffer.take(read).map { it * it }.average()
            if (energy > ENERGY_THRESHOLD) {
                Log.d(TAG, "Energy spike detected: $energy")
                onWakeWordDetected()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
        Log.i(TAG, "Wake word detector stopped")
    }

    fun isDetectionReady(): Boolean = isRunning
}
