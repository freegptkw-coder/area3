package com.aria.assistant.workspace

enum class WorkspacePermissionMode(val key: String, val label: String) {
    READ_ONLY("read_only", "Read-only"),
    ASK_BEFORE_WRITE("ask_before_write", "Ask before write"),
    FULL_ACCESS("full_access", "Full access");

    companion object {
        fun fromKey(raw: String?): WorkspacePermissionMode {
            return values().firstOrNull { it.key == raw } ?: ASK_BEFORE_WRITE
        }
    }
}

data class WorkspacePolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String
)

object WorkspacePolicyDecider {
    private val writeIndicators = listOf(
        Regex("(^|\\s)rm(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)mv(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)cp(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)mkdir(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)rmdir(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)touch(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)chmod(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)chown(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)sed\\s+-i", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)git\\s+commit", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)git\\s+push", RegexOption.IGNORE_CASE),
        Regex(">"),
        Regex(">>")
    )

    fun isLikelyWriteCommand(command: String): Boolean {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return false
        return writeIndicators.any { it.containsMatchIn(trimmed) }
    }

    fun evaluate(
        mode: WorkspacePermissionMode,
        command: String,
        userConfirmedWrite: Boolean
    ): WorkspacePolicyDecision {
        val isWrite = isLikelyWriteCommand(command)
        return when (mode) {
            WorkspacePermissionMode.READ_ONLY -> {
                if (isWrite) {
                    WorkspacePolicyDecision(
                        allowed = false,
                        requiresConfirmation = false,
                        reason = "Read-only workspace blocks write commands"
                    )
                } else {
                    WorkspacePolicyDecision(true, false, "Read-only mode allows non-write command")
                }
            }

            WorkspacePermissionMode.ASK_BEFORE_WRITE -> {
                if (!isWrite) {
                    WorkspacePolicyDecision(true, false, "Non-write command allowed")
                } else if (userConfirmedWrite) {
                    WorkspacePolicyDecision(true, false, "Write command confirmed by user")
                } else {
                    WorkspacePolicyDecision(
                        allowed = false,
                        requiresConfirmation = true,
                        reason = "Write command needs confirmation"
                    )
                }
            }

            WorkspacePermissionMode.FULL_ACCESS -> {
                WorkspacePolicyDecision(true, false, "Full access mode")
            }
        }
    }
}
