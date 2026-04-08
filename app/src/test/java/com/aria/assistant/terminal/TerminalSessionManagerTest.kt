package com.aria.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TerminalSessionManagerTest {

    @Test
    fun `execute command emits output and exit code`() {
        val manager = TerminalSessionManager()
        val latch = CountDownLatch(1)
        val exitCode = AtomicInteger(999)
        val outputs = mutableListOf<String>()

        manager.execute("echo terminal_ok", File("."), object : TerminalSessionManager.Listener {
            override fun onSessionOutput(line: String) {
                synchronized(outputs) { outputs += line }
            }

            override fun onCommandStarted(command: String) = Unit

            override fun onCommandFinished(exitCodeValue: Int) {
                exitCode.set(exitCodeValue)
                latch.countDown()
            }

            override fun onCommandRejected(reason: String) = Unit
        })

        assertTrue("Timed out waiting for command", latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, exitCode.get())
        assertTrue(outputs.joinToString("\n").contains("terminal_ok"))
        assertFalse(manager.isRunning())
    }
}
