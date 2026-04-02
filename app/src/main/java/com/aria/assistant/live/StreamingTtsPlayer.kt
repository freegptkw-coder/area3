package com.aria.assistant.live

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

class StreamingTtsPlayer(
    sampleRate: Int = 24000,
    bufferSizeBytes: Int = 24000
) {

    interface Listener {
        fun onPlaybackStarted()
        fun onPlaybackStopped()
        fun onPlaybackIdle()
    }

    private val audioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(bufferSizeBytes)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleRunnable = Runnable {
        listener?.onPlaybackIdle()
    }

    @Volatile
    private var started = false
    @Volatile
    private var released = false
    @Volatile
    private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (released) return
        if (!started) {
            audioTrack.play()
            started = true
        }
    }

    fun playChunk(pcmChunk: ByteArray) {
        if (released || pcmChunk.isEmpty()) return
        if (!started) {
            start()
        }
        listener?.onPlaybackStarted()
        mainHandler.removeCallbacks(idleRunnable)
        audioTrack.write(pcmChunk, 0, pcmChunk.size)
        mainHandler.postDelayed(idleRunnable, 700L)
    }

    fun stopNow() {
        if (released) return
        mainHandler.removeCallbacks(idleRunnable)
        runCatching {
            if (started) {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.play()
            }
        }
        listener?.onPlaybackStopped()
    }

    fun stop() {
        shutdown()
    }

    fun shutdown() {
        if (released) return
        mainHandler.removeCallbacks(idleRunnable)
        runCatching {
            audioTrack.stop()
            audioTrack.flush()
            audioTrack.release()
        }
        released = true
        started = false
        listener?.onPlaybackStopped()
    }
}
