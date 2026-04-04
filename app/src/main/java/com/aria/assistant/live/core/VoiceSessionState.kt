package com.aria.assistant.live.core

enum class VoiceSessionState {
    IDLE,
    LISTENING,
    PARTIAL_TRANSCRIPTION,
    THINKING,
    SPEAKING,
    MULTI_TASK_ACTIVE,
    INTERRUPTED,
    EXECUTING_ACTION,
    TASK_COMPLETED,
    AWAITING_CONFIRMATION,
    ERROR_RECOVERY
}

fun VoiceSessionState.isSpeechOutputState(): Boolean {
    return this == VoiceSessionState.SPEAKING
}

/**
 * Update #6: isSpeakingState() for BargeInController compatibility
 */
fun VoiceSessionState.isSpeakingState(): Boolean {
    return this == VoiceSessionState.SPEAKING ||
           this == VoiceSessionState.EXECUTING_ACTION
}

fun VoiceSessionState.isInteractiveState(): Boolean {
    return this == VoiceSessionState.IDLE ||
           this == VoiceSessionState.LISTENING ||
           this == VoiceSessionState.PARTIAL_TRANSCRIPTION
}