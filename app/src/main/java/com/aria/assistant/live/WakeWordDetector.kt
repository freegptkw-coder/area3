package com.aria.assistant.live

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Update #4: "Hey ARIA" Wake Word Detector - low-power detection using energy spikes.
 * Runs in a background executor thread to avoid freezing the UI. When wake word detected, activates full STT.
 */
class WakeWordDetector(
    private val onWakeWordDetected: () -> Unit
) {
    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000
        private val BUFFER_SIZE: Int = runCatching {
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ) * 2
        }.getOrElse { 4096 }
        private const val ENERGY_THRESHOLD = 500.0
    }

    private val isRunning = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WakeWordDetector").apply { isDaemon = true }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        executor.execute {
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
                } else {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isRunning.set(false)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "No microphone permission: ${e.message}")
                isRunning.set(false)
            } catch (e: Exception) {
                Log.e(TAG, "Wake word detector error: ${e.message}")
                isRunning.set(false)
            }
        }
    }

    private fun processAudio() {
        val buffer = ShortArray(BUFFER_SIZE)
        while (isRunning.get()) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            var energySum = 0.0
            for (i in 0 until read) {
                energySum += buffer[i] * buffer[i]
            }
            val energy = energySum / read

            if (energy > ENERGY_THRESHOLD) {
                Log.d(TAG, "Energy spike: $energy")
                onWakeWordDetected()
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) { }
        audioRecord = null
        executor.shutdownNow()
        Log.i(TAG, "Wake word detector stopped")
    }

    fun isDetectionReady(): Boolean = isRunning.get()
}
