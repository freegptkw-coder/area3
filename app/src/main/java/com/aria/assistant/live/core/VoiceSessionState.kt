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