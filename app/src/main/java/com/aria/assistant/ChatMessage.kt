package com.aria.assistant

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val sender: SenderType
) {
    enum class SenderType {
        USER,
        ASSISTANT,
        SYSTEM
    }
}
