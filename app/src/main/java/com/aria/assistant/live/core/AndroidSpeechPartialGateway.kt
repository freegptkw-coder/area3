package com.aria.assistant.live.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class AndroidSpeechPartialGateway(
    context: Context,
    private val locale: Locale = Locale.getDefault(),
    private val onEvent: (SttTranscriptEvent) -> Unit
) : StreamingSttGateway {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var listening: Boolean = false

    @Volatile
    private var restartScheduled: Boolean = false

    private var recognizer: SpeechRecognizer? = null
    private var consecutiveErrors: Int = 0
    private var lastPartialText: String = ""
    private var lastStartAttemptAtMs: Long = 0L

    private val minRestartGapMs: Long = 750L
    private val maxConsecutiveErrorsBeforeUnavailable: Int = 6
    private val watchdogTimeoutMs: Long = 6_000L // Reduced from 10s to 6s for faster hang detection

    private val restartRunnable = Runnable {
        if (!running) return@Runnable
        restartScheduled = false
        startListeningInternal()
    }

    override fun start() {
        if (running) return
        running = true
        com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT", "Gateway start requested")
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                running = false
                com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT_ERROR", "Recognition unavailable")
                onEvent(SttTranscriptEvent.Unavailable)
                return@post
            }
            if (recognizer == null) {
                recognizer = createRecognizerOrNull()
            }
            if (recognizer == null) {
                running = false
                com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT_ERROR", "Failed to create recognizer")
                onEvent(SttTranscriptEvent.Unavailable)
                return@post
            }
            // We wait for onVoiceActivity(true) from the VAD engine to avoid constant 5s timeout beeps and audio focus drops.
            com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT", "Gateway started, waiting for VAD trigger")
            onEvent(SttTranscriptEvent.ListeningStopped)
        }
    }

    override fun stop() {
        running = false
        mainHandler.post {
            mainHandler.removeCallbacks(restartRunnable)
            mainHandler.removeCallbacks(watchdogRunnable)
            restartScheduled = false
            listening = false
            runCatching { recognizer?.stopListening() }
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            onEvent(SttTranscriptEvent.ListeningStopped)
        }
    }

    override fun onVoiceActivity(active: Boolean) {
        if (!active || !running) return
        if (!listening && !restartScheduled) {
            mainHandler.post {
                if (running && !listening && !restartScheduled) {
                    startListeningInternal()
                }
            }
        }
    }

    private fun createRecognizerOrNull(): SpeechRecognizer? {
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
            }
        }.getOrNull()
    }

    private fun startListeningInternal() {
        val sr = recognizer ?: return
        if (!running) return
        if (listening) return

        val now = System.currentTimeMillis()
        val sinceLastAttempt = now - lastStartAttemptAtMs
        if (lastStartAttemptAtMs > 0L && sinceLastAttempt in 0 until minRestartGapMs) {
            scheduleRestart(minRestartGapMs - sinceLastAttempt)
            return
        }
        lastStartAttemptAtMs = now

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 650L)
        }

        mainHandler.removeCallbacks(restartRunnable)
        restartScheduled = false
        runCatching {
            com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT", "Starting listening")
            sr.startListening(intent)
            listening = true
            lastActivityMs = System.currentTimeMillis()
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.postDelayed(watchdogRunnable, 1500L)
            onEvent(SttTranscriptEvent.ListeningStarted)
        }.onFailure {
            listening = false
            com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT_ERROR", "Start listening failed: ${it.message}")
            onEvent(
                SttTranscriptEvent.Error(
                    code = Int.MIN_VALUE,
                    reason = "stt_start_failed:${it.javaClass.simpleName}",
                    recoverable = true
                )
            )
            scheduleRestart(1200L)
        }
    }

    private var lastActivityMs: Long = 0L
    private val watchdogRunnable: Runnable = object : Runnable {
        override fun run() {
            if (!listening || !running) return
            val now = System.currentTimeMillis()
            if (now - lastActivityMs > watchdogTimeoutMs) {
                // Hung - force recovery
                com.aria.assistant.live.core.PersistentLogger.log(
                    appContext,
                    "STT_WATCHDOG",
                    "STT hung detected. Forcing recovery. Last activity: ${now - lastActivityMs}ms ago"
                )
                onEvent(SttTranscriptEvent.Timeout)
                listening = false
                mainHandler.removeCallbacks(this)
                runCatching { 
                    recognizer?.cancel()
                    recognizer?.stopListening()
                }
                scheduleRestart(800L)
            } else {
                mainHandler.postDelayed(this, 1500L) // Check more frequently
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!running) return
        mainHandler.removeCallbacks(restartRunnable)
        restartScheduled = true
        mainHandler.postDelayed(restartRunnable, delayMs.coerceAtLeast(minRestartGapMs))
    }

    private fun handleError(code: Int) {
        listening = false
        mainHandler.removeCallbacks(watchdogRunnable)

        if (code == SpeechRecognizer.ERROR_NO_MATCH || code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            consecutiveErrors = 0
            onEvent(SttTranscriptEvent.ListeningStopped)
            return
        }

        consecutiveErrors += 1

        val recoverable = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> false
            else -> false
        }

        val reason = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient_permissions"
            else -> "unknown_$code"
        }

        onEvent(
            SttTranscriptEvent.Error(
                code = code,
                reason = reason,
                recoverable = recoverable
            )
        )

        if (!recoverable || consecutiveErrors >= maxConsecutiveErrorsBeforeUnavailable) {
            running = false
            mainHandler.removeCallbacks(restartRunnable)
            restartScheduled = false
            if (!recoverable) {
                onEvent(SttTranscriptEvent.Unavailable)
            } else {
                onEvent(
                    SttTranscriptEvent.Error(
                        code = code,
                        reason = "unstable_recognizer",
                        recoverable = false
                    )
                )
                onEvent(SttTranscriptEvent.Unavailable)
            }
            return
        }

        val retryDelay = when (code) {
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L + (consecutiveErrors * 350L)
            else -> 900L + (consecutiveErrors * 300L)
        }
        scheduleRestart(retryDelay.coerceAtMost(6000L))
    }

    private fun onResultsOrFinalized() {
        listening = false
        consecutiveErrors = 0
        lastPartialText = ""
        mainHandler.removeCallbacks(watchdogRunnable)
        onEvent(SttTranscriptEvent.ListeningStopped)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() {
            lastActivityMs = System.currentTimeMillis()
        }

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            // Do not set listening to false here. The recognizer is still processing results.
            // It will be set to false in onResults or onError.
            lastActivityMs = System.currentTimeMillis()
        }

        override fun onError(error: Int) {
            handleError(error)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = extractBestText(results)
            if (!text.isNullOrBlank()) {
                com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT_FINAL", text.take(100))
                onEvent(SttTranscriptEvent.Final(text))
            } else {
                com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT", "Final result empty")
            }
            onResultsOrFinalized()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            lastActivityMs = System.currentTimeMillis()
            val text = extractBestText(partialResults)?.trim().orEmpty()
            if (text.isBlank()) return
            if (text == lastPartialText) return
            lastPartialText = text
            com.aria.assistant.live.core.PersistentLogger.log(appContext, "STT_PARTIAL", text.take(60))
            onEvent(SttTranscriptEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun extractBestText(bundle: Bundle?): String? {
        if (bundle == null) return null
        val list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        return list?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}
