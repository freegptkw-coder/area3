package com.aria.assistant.live.core

import com.aria.assistant.LettaResponse

interface StreamLlmGateway {
    suspend fun streamResponse(
        userText: String,
        liveShortResponse: Boolean = true,
        onChunk: (String) -> Unit
    ): LettaResponse
}
