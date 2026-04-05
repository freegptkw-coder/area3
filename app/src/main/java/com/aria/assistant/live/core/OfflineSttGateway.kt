package com.aria.assistant.live.core

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * v3.1: Real Offline STT using Vosk library.
 * Supports Bangla, English, Hindi, and 5 more languages.
 * 
 * Setup:
 * 1. Download Vosk model from https://alphacephei.com/vosk/models
 *    - Bangla: vosk-model-bn-0.4 (~50MB)
 *    - English (small): vosk-model-en-us-daanzu-20200905 (~40MB)
 *    - Hindi: vosk-model-hi-0.4 (~45MB)
 * 2. Place extracted model in: internal storage / filesDir / vosk_models/<langCode>/
 *    Example: /data/data/com.aria.assistant/files/vosk_models/bn/
 * 3. Model files needed: am/final.mdl, conf/model.conf, graph/HCLG.fst, ivector/
 */
class OfflineSttGateway(
    private val context: Context,
    private val languageCode: String,
    private val onEvent: (SttTranscriptEvent) -> Unit
) : StreamingSttGateway {

    companion object {
        private const val TAG = "OfflineSttGateway"
        const val MODEL_DIR = "vosk_models"
        const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 4096
        private const val WATCHDOG_MS = 8000L

        // Language code -> model zip filename on alphacephei
        val MODEL_MAP = mapOf(
            "bn" to "bn",
            "en" to "en-us",
            "hi" to "hi",
            "es" to "es",
            "fr" to "fr",
            "ar" to "ar",
            "zh" to "cn"
        )

        fun create(
            context: Context,
            languageCode: String = "en",
            onEvent: (SttTranscriptEvent) -> Unit
        ): OfflineSttGateway {
            return OfflineSttGateway(context.applicationContext, languageCode, onEvent)
        }
    }

    // State
    private var isRunning: Boolean = false
    private var audioRecord: AudioRecord? = null
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recordThread: Job? = null
    private var lastAudioActivityMs: Long = 0
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 5

    // Model directory for the selected language
    private val modelPath: String
        get() = "${context.filesDir.absolutePath}/$MODEL_DIR/$languageCode"

    /**
     * Load the Vosk model for the configured language.
     * Returns true if model is found and loaded.
     */
    fun loadModel(): Boolean {
        val dir = File(modelPath)
        if (!dir.exists()) {
            Log.e(TAG, "Model not found at: $dir")
            onEvent(SttTranscriptEvent.Unavailable)
            return false
        }

        return try {
            model = Model(dir.absolutePath)
            recognizer = Recognizer(model!!, SAMPLE_RATE.toFloat())
            Log.i(TAG, "Vosk model loaded: lang=$languageCode, path=$dir")
            onEvent(SttTranscriptEvent.Error(200, "Vosk model loaded ($languageCode)", true))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}")
            onEvent(SttTranscriptEvent.Error(500, "Vosk load failed: ${e.message}", false))
            false
        }
    }

    override fun start() {
        if (isRunning) return

        // Load model if not already loaded
        if (model == null) {
            if (!loadModel()) return
        }

        isRunning = true
        consecutiveErrors = 0

        // Create AudioRecord
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(BUFFER_SIZE)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                isRunning = false
                onEvent(SttTranscriptEvent.Error(500, "Mic init failed", false))
                return
            }

            audioRecord?.startRecording()
            lastAudioActivityMs = SystemClock.elapsedRealtime()
            Log.i(TAG, "Vosk STT started (lang=$languageCode)")
            onEvent(SttTranscriptEvent.ListeningStarted)

            // Start recording coroutine
            recordThread = CoroutineScope(Dispatchers.IO).launch {
                recordLoop()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission: ${e.message}")
            isRunning = false
            onEvent(SttTranscriptEvent.Error(403, "No mic permission", false))
        } catch (e: Exception) {
            Log.e(TAG, "start() failed: ${e.message}")
            isRunning = false
            onEvent(SttTranscriptEvent.Error(500, "Vosk start failed: ${e.message}", false))
        }
    }

    private suspend fun recordLoop() {
        val buffer = ShortArray(BUFFER_SIZE / 2) // 16-bit = 2 bytes per sample

        while (isRunning) {
            // Watchdog: if no audio activity for WATCHDOG_MS, fire timeout
            val now = SystemClock.elapsedRealtime()
            if (now - lastAudioActivityMs > WATCHDOG_MS) {
                Log.w(TAG, "Watchdog: no audio activity for ${WATCHDOG_MS}ms")
                onEvent(SttTranscriptEvent.Timeout)
                delay(1000)
                continue
            }

            try {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    lastAudioActivityMs = now

                    // Check if there's actual audio (non-silence)
                    val rms = calculateRMS(buffer, read)
                    if (rms > 50.0) { // Voice activity threshold
                        recognizer?.let { rec ->
                            val hasResult = rec.acceptWaveForm(buffer, read)

                            if (hasResult) {
                                // Vosk detected end of utterance - emit final
                                val result = rec.getResult()
                                val json = org.json.JSONObject(result)
                                val finalText = json.optString("text", "").trim()
                                if (finalText.isNotBlank()) {
                                    onEvent(SttTranscriptEvent.Final(finalText))
                                }
                            } else {
                                // Get partial result (interim)
                                val partial = rec.getPartialResult()
                                val partialJson = org.json.JSONObject(partial)
                                val partialText = partialJson.optString("partial", "").trim()
                                if (partialText.isNotBlank()) {
                                    onEvent(SttTranscriptEvent.Partial(partialText))
                                }
                            }
                        }
                    }
                } else if (read < 0) {
                    Log.e(TAG, "AudioRecord error: $read")
                    consecutiveErrors++
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        onEvent(SttTranscriptEvent.Error(500, "AudioRecord errors", false))
                        break
                    }
                    delay(200)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Record loop error: ${e.message}")
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    onEvent(SttTranscriptEvent.Error(500, "Vosk error: ${e.message}", false))
                    break
                }
                delay(200)
            }
        }
    }

    override fun stop() {
        isRunning = false
        recordThread?.cancel()
        recordThread = null

        // Get final result if any pending speech
        recognizer?.let { rec ->
            try {
                val result = rec.getFinalResult()
                val json = org.json.JSONObject(result)
                val text = json.optString("text", "").trim()
                if (text.isNotEmpty()) {
                    onEvent(SttTranscriptEvent.Final(text))
                } else {
                    // No speech detected, do nothing
                    Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "Final result error: ${e.message}")
            }
        }

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
            // Ignore stop errors
        }
        audioRecord?.release()
        audioRecord = null

        // Clean up recognizer (but keep model in memory)
        recognizer = null

        Log.i(TAG, "Vosk STT stopped")
        onEvent(SttTranscriptEvent.ListeningStopped)
    }

    override fun onVoiceActivity(active: Boolean) {
        // Vosk gateway owns its own AudioRecord, so VAD gating is not needed
        // This is a no-op for the Vosk implementation
    }

    fun isModelLoaded(): Boolean = model != null

    /**
     * Get the size of the model directory in bytes.
     */
    fun getModelSizeBytes(): Long {
        val dir = File(modelPath)
        return try {
            if (dir.exists()) {
                dir.walk().sumOf { if (it.isFile) it.length() else 0L }
            } else {
                0L
            }
        } catch (_: Exception) { 0L }
    }

    /**
     * Delete the model directory to free space.
     */
    fun deleteModel(): Boolean {
        return try {
            val dir = File(modelPath)
            if (dir.exists()) {
                dir.deleteRecursively()
                model = null
                recognizer = null
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model: ${e.message}")
            false
        }
    }

    /**
     * Detect language from the model path name.
     */
    fun detectLanguage(): String {
        return when (languageCode) {
            "bn" -> "Bangla"
            "hi" -> "Hindi"
            "es" -> "Spanish"
            "fr" -> "French"
            "ar" -> "Arabic"
            "zh", "cn" -> "Chinese"
            else -> "English"
        }
    }

    private fun calculateRMS(samples: ShortArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            sum += samples[i] * samples[i]
        }
        return if (count > 0) kotlin.math.sqrt(sum / count) else 0.0
    }
}
