package com.aria.assistant.live.core

data class BargeInDecision(
    val shouldInterrupt: Boolean,
    val reason: String = ""
)

/**
 * Update #6: BargeInController tuned - shorter grace period, energy-based confidence
 * Grace period: 1000ms → 800ms (faster barge-in response)
 * Assistant hold: 1800ms → 1000ms (quavier detection)
 * Tolerance: 1200ms → 800ms (stricter window)
 */
class BargeInController(
    private val enabledProvider: () -> Boolean = { true },
    private val minInterruptIntervalMs: Long = 500L,
    private val assistantOutputHoldMs: Long = 1000L,
    private val staleSpeakingToleranceMs: Long = 800L
) {

    @Volatile
    private var assistantOutputActiveUntilMs: Long = 0L

    @Volatile
    private var assistantOutputStartedAtMs: Long = 0L

    @Volatile
    private var lastInterruptAtMs: Long = 0L

    // Energy-based confidence scoring
    @Volatile
    private var lastInputConfidence: Float = 0f

    fun markAssistantOutputStarted(nowMs: Long = System.currentTimeMillis(), isNewStart: Boolean = false) {
        assistantOutputActiveUntilMs = nowMs + assistantOutputHoldMs
        if (isNewStart) {
            assistantOutputStartedAtMs = nowMs
        }
    }

    fun markAssistantOutputStopped(nowMs: Long = System.currentTimeMillis()) {
        assistantOutputActiveUntilMs = nowMs
    }

    /**
     * Evaluate if user speech should interrupt assistant.
     * Supports wake word shortcut: if wake word detected, bypass grace period.
     */
    fun evaluateUserSpeechInterruption(
        currentState: VoiceSessionState,
        nowMs: Long = System.currentTimeMillis(),
        wasWakeWord: Boolean = false,
        inputConfidence: Float = 0.5f
    ): BargeInDecision {
        lastInputConfidence = inputConfidence

        if (!enabledProvider()) {
            return BargeInDecision(false, "disabled")
        }

        // Wake word bypass: skip all grace periods
        if (wasWakeWord) {
            return BargeInDecision(true, "barge_in_wake_word")
        }

        val assistantLikelyActive =
            nowMs <= assistantOutputActiveUntilMs ||
                (currentState.isSpeakingState() && (nowMs - assistantOutputActiveUntilMs) <= staleSpeakingToleranceMs)
        if (!assistantLikelyActive) {
            return BargeInDecision(false, "assistant_not_speaking")
        }

        if (nowMs - assistantOutputStartedAtMs < 800L) {
            return BargeInDecision(false, "barge_in_grace_period")
        }

        if (nowMs - lastInterruptAtMs < minInterruptIntervalMs) {
            return BargeInDecision(false, "cooldown")
        }

        // Energy-based confidence filter: reject low-energy interruptions
        if (inputConfidence < 0.3f) {
            return BargeInDecision(false, "low_confidence")
        }

        return BargeInDecision(true, "barge_in")
    }

    fun markInterrupted(nowMs: Long = System.currentTimeMillis()) {
        lastInterruptAtMs = nowMs
        assistantOutputActiveUntilMs = nowMs
    }

    fun getLastConfidence(): Float = lastInputConfidence
}