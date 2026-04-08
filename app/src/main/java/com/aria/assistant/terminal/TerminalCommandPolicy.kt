package com.aria.assistant.terminal

data class CommandPolicyDecision(
    val allowed: Boolean,
    val requiresConfirmation: Boolean,
    val reason: String
)

object TerminalCommandPolicy {
    private val hardBlockPatterns = listOf(
        Regex("rm\\s+-rf\\s+/(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("mkfs(\\.|\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)dd\\s+if=", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)reboot(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)shutdown(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)poweroff(\\s|$)", RegexOption.IGNORE_CASE),
        Regex(":\\(\\)\\s*\\{\\s*:\\|:\\s*&\\s*\\};:")
    )

    private val confirmationPatterns = listOf(
        Regex("(^|\\s)su(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)sudo(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)iptables(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)setprop(\\s|$)", RegexOption.IGNORE_CASE),
        Regex("(^|\\s)settings\\s+put", RegexOption.IGNORE_CASE)
    )

    fun evaluate(command: String, approvedHighRisk: Boolean): CommandPolicyDecision {
        val trimmed = command.trim()
        if (trimmed.isBlank()) {
            return CommandPolicyDecision(false, false, "Command is empty")
        }

        if (hardBlockPatterns.any { it.containsMatchIn(trimmed) }) {
            return if (approvedHighRisk) {
                CommandPolicyDecision(true, false, "High-risk command approved")
            } else {
                CommandPolicyDecision(
                    allowed = false,
                    requiresConfirmation = true,
                    reason = "High-risk command requires explicit approval"
                )
            }
        }

        if (confirmationPatterns.any { it.containsMatchIn(trimmed) }) {
            return if (approvedHighRisk) {
                CommandPolicyDecision(true, false, "Privileged command approved")
            } else {
                CommandPolicyDecision(
                    allowed = false,
                    requiresConfirmation = true,
                    reason = "Privileged command requires approval"
                )
            }
        }

        return CommandPolicyDecision(true, false, "Allowed")
    }
}
