package com.aria.assistant.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandPolicyTest {

    @Test
    fun `high-risk command requires explicit approval`() {
        val decision = TerminalCommandPolicy.evaluate("rm -rf /", approvedHighRisk = false)
        assertFalse(decision.allowed)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `high-risk command allowed after explicit approval`() {
        val decision = TerminalCommandPolicy.evaluate("rm -rf /", approvedHighRisk = true)
        assertTrue(decision.allowed)
    }

    @Test
    fun `normal command is allowed`() {
        val decision = TerminalCommandPolicy.evaluate("pwd", approvedHighRisk = false)
        assertTrue(decision.allowed)
        assertFalse(decision.requiresConfirmation)
    }
}
