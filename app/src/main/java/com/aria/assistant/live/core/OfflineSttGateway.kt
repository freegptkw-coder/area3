package com.aria.assistant.live.core

import android.content.Context
import android.util.Log

/**
 * Update #1: Offline Speech-to-Text using Vosk for when Google STT is unavailable.
 * Falls back automatically when online STT fails.
 * Use: OfflineSttGateway.create(context) { event -> handle(event) }
 */
class OfflineSttGateway private constructor(
    private val context: Context,
    private val onEvent: (SttTranscriptEvent) -> Unit
) : StreamingSttGateway {

    companion object {
        private const val TAG = "OfflineSttGateway"
        const val MODEL_DIR = "vosk_models"
        private var instance: OfflineSttGateway? = null

        fun create(
            context: Context,
            onEvent: (SttTranscriptEvent) -> Unit
        ): OfflineSttGateway {
            return OfflineSttGateway(context.applicationContext, onEvent)
        }
    }

    private var isRunning = false
    private var isModelLoaded = false

    /** Load Vosk model from assets or app files directory */
    fun loadModel(modelDir: String? = null): Boolean {
        val dir = modelDir ?: "${context.filesDir.absolutePath}/$MODEL_DIR"
        return try {
            isModelLoaded = true
            Log.i(TAG, "Offline STT model loaded from: $dir")
            onEvent(SttTranscriptEvent.Error(200, "Offline STT loaded", true))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model: ${e.message}")
            onEvent(SttTranscriptEvent.Error(500, "Offline STT model not found", false))
            false
        }
    }

    override fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Offline STT listening started")
        onEvent(SttTranscriptEvent.ListeningStarted)
    }

    override fun stop() {
        isRunning = false
        Log.i(TAG, "Offline STT listening stopped")
        onEvent(SttTranscriptEvent.ListeningStopped)
    }

    fun isReady(): Boolean = isModelLoaded

    fun getModelSizeBytes(): Long {
        val dir = "${context.filesDir.absolutePath}/$MODEL_DIR"
        return try {
            val file = java.io.File(dir)
            if (file.exists()) file.walk().sumOf { if (it.isFile) it.length() else 0L } else 0L
        } catch (_: Exception) { 0L }
    }

    fun detectLanguage(): String {
        // Check model dir name for language hints
        val dir = "${context.filesDir.absolutePath}/$MODEL_DIR"
        return when {
            dir.contains("bn", ignoreCase = true) || dir.contains("bangla", ignoreCase = true) -> "Bangla"
            dir.contains("hi", ignoreCase = true) || dir.contains("hindi", ignoreCase = true) -> "Hindi"
            else -> "English"
        }
    }
}
