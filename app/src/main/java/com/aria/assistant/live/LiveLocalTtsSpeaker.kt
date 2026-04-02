package com.aria.assistant.live

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class LiveLocalTtsSpeaker(context: Context) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onUtteranceStarted(utteranceId: String)
        fun onUtteranceCompleted(utteranceId: String)
        fun onUtteranceError(utteranceId: String, reason: String)
        fun onQueueIdle()
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    @Volatile
    private var ready = false
    @Volatile
    private var listener: Listener? = null
    private val pendingCount = AtomicInteger(0)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.getDefault()
            tts?.setSpeechRate(1.05f)
            tts?.setPitch(1.02f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    utteranceId ?: return
                    listener?.onUtteranceStarted(utteranceId)
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId ?: return
                    val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                    listener?.onUtteranceCompleted(utteranceId)
                    if (remaining == 0) {
                        listener?.onQueueIdle()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId ?: return
                    val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                    listener?.onUtteranceError(utteranceId, "unknown")
                    if (remaining == 0) {
                        listener?.onQueueIdle()
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId ?: return
                    val remaining = pendingCount.decrementAndGet().coerceAtLeast(0)
                    listener?.onUtteranceError(utteranceId, "code_$errorCode")
                    if (remaining == 0) {
                        listener?.onQueueIdle()
                    }
                }
            })
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!ready || text.isBlank()) return
        val safe = text.replace("\n", " ").trim().take(260)
        if (safe.isBlank()) return

        if (flush) {
            stopNow()
        }

        val utteranceId = "live_${System.currentTimeMillis()}_${pendingCount.incrementAndGet()}"
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(safe, queueMode, Bundle(), utteranceId)
    }

    fun stopNow() {
        pendingCount.set(0)
        runCatching { tts?.stop() }
        listener?.onQueueIdle()
    }

    fun shutdown() {
        stopNow()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
