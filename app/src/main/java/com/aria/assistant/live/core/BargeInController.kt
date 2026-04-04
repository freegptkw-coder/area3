package com.aria.assistant.live.core

data class BargeInDecision(
    val shouldInterrupt: Boolean,
    val reason: String = ""
)

class BargeInController(
    private val enabledProvider: () -> Boolean = { true },
    private val minInterruptIntervalMs: Long = 500L,
    private val assistantOutputHoldMs: Long = 1800L,
    private val staleSpeakingToleranceMs: Long = 1200L
) {

    @Volatile
    private var assistantOutputActiveUntilMs: Long = 0L

    @Volatile
    private var assistantOutputStartedAtMs: Long = 0L

    @Volatile
    private var lastInterruptAtMs: Long = 0L

    fun markAssistantOutputStarted(nowMs: Long = System.currentTimeMillis(), isNewStart: Boolean = false) {
        assistantOutputActiveUntilMs = nowMs + assistantOutputHoldMs
        if (isNewStart) {
            assistantOutputStartedAtMs = nowMs
        }
    }

    fun markAssistantOutputStopped(nowMs: Long = System.currentTimeMillis()) {
        assistantOutputActiveUntilMs = nowMs
    }

    fun evaluateUserSpeechInterruption(
        currentState: VoiceSessionState,
        nowMs: Long = System.currentTimeMillis()
    ): BargeInDecision {
        if (!enabledProvider()) {
            return BargeInDecision(false, "disabled")
        }

        val assistantLikelyActive =
            nowMs <= assistantOutputActiveUntilMs ||
                (currentState.isSpeechOutputState() && (nowMs - assistantOutputActiveUntilMs) <= staleSpeakingToleranceMs)
        if (!assistantLikelyActive) {
            return BargeInDecision(false, "assistant_not_speaking")
        }

        if (nowMs - assistantOutputStartedAtMs < 1000L) {
            return BargeInDecision(false, "barge_in_grace_period")
        }

        if (nowMs - lastInterruptAtMs < minInterruptIntervalMs) {
            return BargeInDecision(false, "cooldown")
        }

        return BargeInDecision(true, "barge_in")
    }

    fun markInterrupted(nowMs: Long = System.currentTimeMillis()) {
        lastInterruptAtMs = nowMs
        assistantOutputActiveUntilMs = nowMs
    }
}