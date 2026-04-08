package com.aria.assistant.terminal

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TerminalSessionManager {

    interface Listener {
        fun onSessionOutput(line: String)
        fun onCommandStarted(command: String)
        fun onCommandFinished(exitCode: Int)
        fun onCommandRejected(reason: String)
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    var sessionId: Long = System.currentTimeMillis()
        private set

    fun execute(command: String, workingDir: File?, listener: Listener) {
        if (!running.compareAndSet(false, true)) {
            listener.onCommandRejected("Another command is still running")
            return
        }

        executor.execute {
            try {
                listener.onCommandStarted(command)
                val builder = ProcessBuilder("sh", "-c", command)
                if (workingDir != null && workingDir.exists()) {
                    builder.directory(workingDir)
                }
                builder.redirectErrorStream(true)
                val process = builder.start()
                currentProcess = process

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> listener.onSessionOutput(line) }
                }
                val code = process.waitFor()
                listener.onCommandFinished(code)
            } catch (t: Throwable) {
                listener.onSessionOutput("[terminal-error] ${t.message ?: t.javaClass.simpleName}")
                listener.onCommandFinished(-1)
            } finally {
                currentProcess = null
                running.set(false)
            }
        }
    }

    fun interrupt(): Boolean {
        val process = currentProcess ?: return false
        return runCatching {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
            true
        }.getOrDefault(false)
    }

    fun restartSession() {
        interrupt()
        sessionId = System.currentTimeMillis()
    }

    fun isRunning(): Boolean = running.get()
}
