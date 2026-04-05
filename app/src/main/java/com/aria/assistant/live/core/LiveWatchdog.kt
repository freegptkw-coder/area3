package com.aria.assistant.live.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveWatchdog(
    private val context: Context,
    private val sttGateway: StreamingSttGateway,
    private val emitEvent: (VoiceSessionEvent) -> Unit,
    private val onRecover: () -> Unit
) {
    private var job: Job? = null
    private var lastActivityMs: Long = System.currentTimeMillis()
    private val timeoutMs = 60_000L // Increased from 12s to 60s — normal pauses can exceed 12s

    fun ping() {
        lastActivityMs = System.currentTimeMillis()
    }

    fun start() {
        if (job?.isActive == true) return
        job = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(2000L)
                val now = System.currentTimeMillis()
                if (now - lastActivityMs > timeoutMs) {
                    PersistentLogger.log(context, "WATCHDOG", "STT inactivity timeout triggered. Resetting gateway.")
                    runCatching {
                        emitEvent(VoiceSessionEvent.RecoverableWarning("watchdog_timeout"))
                        sttGateway.stop()
                        delay(250L)
                        onRecover()
                        sttGateway.start()
                    }
                    ping()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
