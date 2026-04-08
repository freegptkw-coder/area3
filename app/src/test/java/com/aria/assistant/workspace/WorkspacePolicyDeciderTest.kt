package com.aria.assistant.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePolicyDeciderTest {

    @Test
    fun `read-only blocks write commands`() {
        val decision = WorkspacePolicyDecider.evaluate(
            mode = WorkspacePermissionMode.READ_ONLY,
            command = "rm test.txt",
            userConfirmedWrite = false
        )
        assertFalse(decision.allowed)
        assertFalse(decision.requiresConfirmation)
    }

    @Test
    fun `ask-before-write requires confirmation for write`() {
        val decision = WorkspacePolicyDecider.evaluate(
            mode = WorkspacePermissionMode.ASK_BEFORE_WRITE,
            command = "mkdir tmp",
            userConfirmedWrite = false
        )
        assertFalse(decision.allowed)
        assertTrue(decision.requiresConfirmation)
    }

    @Test
    fun `ask-before-write allows read commands`() {
        val decision = WorkspacePolicyDecider.evaluate(
            mode = WorkspacePermissionMode.ASK_BEFORE_WRITE,
            command = "ls -la",
            userConfirmedWrite = false
        )
        assertTrue(decision.allowed)
    }

    @Test
    fun `full-access allows write without extra confirm`() {
        val decision = WorkspacePolicyDecider.evaluate(
            mode = WorkspacePermissionMode.FULL_ACCESS,
            command = "touch a.txt",
            userConfirmedWrite = false
        )
        assertTrue(decision.allowed)
    }
}
