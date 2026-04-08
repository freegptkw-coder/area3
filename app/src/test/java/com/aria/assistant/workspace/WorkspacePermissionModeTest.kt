package com.aria.assistant.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspacePermissionModeTest {

    @Test
    fun `fromKey falls back to ask-before-write`() {
        assertEquals(
            WorkspacePermissionMode.ASK_BEFORE_WRITE,
            WorkspacePermissionMode.fromKey("unknown")
        )
    }

    @Test
    fun `fromKey parses known keys`() {
        assertEquals(
            WorkspacePermissionMode.READ_ONLY,
            WorkspacePermissionMode.fromKey("read_only")
        )
        assertEquals(
            WorkspacePermissionMode.FULL_ACCESS,
            WorkspacePermissionMode.fromKey("full_access")
        )
    }
}
