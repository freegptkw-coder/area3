package com.aria.assistant.live.core

import android.content.Context
import com.aria.assistant.LettaApiService
import com.aria.assistant.LettaResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProviderStreamingGateway(context: Context) : StreamLlmGateway {

    private val apiService = LettaApiService(context.applicationContext)

    override suspend fun streamResponse(
        userText: String,
        liveShortResponse: Boolean,
        onChunk: (String) -> Unit
    ): LettaResponse {
        return withContext(Dispatchers.IO) {
            apiService.streamMessage(
                message = userText,
                liveShortResponse = liveShortResponse,
                onChunk = onChunk
            )
        }
    }
}
