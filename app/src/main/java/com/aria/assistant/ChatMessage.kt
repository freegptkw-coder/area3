package com.aria.assistant

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    var text: String,
    val sender: SenderType
) {
    enum class SenderType {
        USER,
        ASSISTANT,
        SYSTEM
    }
}
