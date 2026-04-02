package com.aria.assistant.live.core

import com.aria.assistant.live.LiveLocalTtsSpeaker
import com.aria.assistant.live.StreamingTtsPlayer

class LiveSpeechOutputArbiter(
    private val onAudioStateChanged: ((active: Boolean, source: String) -> Unit)? = null
) {

    @Volatile
    private var localSpeaker: LiveLocalTtsSpeaker? = null

    @Volatile
    private var streamingPlayer: StreamingTtsPlayer? = null

    @Volatile
    private var localSpeaking = false

    @Volatile
    private var pcmSpeaking = false

    fun attachLocalSpeaker(speaker: LiveLocalTtsSpeaker?) {
        localSpeaker = speaker
        speaker?.setListener(object : LiveLocalTtsSpeaker.Listener {
            override fun onUtteranceStarted(utteranceId: String) {
                localSpeaking = true
                dispatchStateChange(true, "local_tts_start")
            }

            override fun onUtteranceCompleted(utteranceId: String) {
                localSpeaking = false
                dispatchStateChange(pcmSpeaking, "local_tts_done")
            }

            override fun onUtteranceError(utteranceId: String, reason: String) {
                localSpeaking = false
                dispatchStateChange(pcmSpeaking, "local_tts_error")
            }

            override fun onQueueIdle() {
                localSpeaking = false
                dispatchStateChange(pcmSpeaking, "local_tts_idle")
            }
        })
    }

    fun attachStreamingPlayer(player: StreamingTtsPlayer?) {
        streamingPlayer = player
        player?.setListener(object : StreamingTtsPlayer.Listener {
            override fun onPlaybackStarted() {
                pcmSpeaking = true
                dispatchStateChange(true, "pcm_start")
            }

            override fun onPlaybackStopped() {
                pcmSpeaking = false
                dispatchStateChange(localSpeaking, "pcm_stop")
            }

            override fun onPlaybackIdle() {
                pcmSpeaking = false
                dispatchStateChange(localSpeaking, "pcm_idle")
            }
        })
    }

    fun speakText(text: String, flush: Boolean = false, source: String = "local_tts") {
        val safe = text.replace("\n", " ").trim().take(320)
        if (safe.isBlank()) return
        if (flush) {
            stopNow("flush_before_text")
        }
        localSpeaker?.speak(safe, flush = false)
        dispatchStateChange(true, source)
    }

    fun playPcmChunk(chunk: ByteArray, source: String = "pcm") {
        if (chunk.isEmpty()) return
        streamingPlayer?.playChunk(chunk)
        dispatchStateChange(true, source)
    }

    fun stopNow(reason: String = "manual") {
        localSpeaker?.stopNow()
        streamingPlayer?.stopNow()
        localSpeaking = false
        pcmSpeaking = false
        dispatchStateChange(false, reason)
    }

    fun shutdown() {
        stopNow("shutdown")
        streamingPlayer?.shutdown()
        localSpeaker?.shutdown()
    }

    fun isSpeaking(): Boolean = localSpeaking || pcmSpeaking

    private fun dispatchStateChange(active: Boolean, source: String) {
        onAudioStateChanged?.invoke(active, source)
    }
}
