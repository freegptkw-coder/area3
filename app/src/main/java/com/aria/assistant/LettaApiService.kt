package com.aria.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LettaResponse(
    val text: String,
    val rootCommand: String? = null
)

class LettaApiService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val prefs = context.getSharedPreferences("ARIA_PREFS", Context.MODE_PRIVATE)

    private fun getProvider(): String = prefs.getString("ai_provider", "groq") ?: "groq"
    private fun getModel(): String = prefs.getString("model", "llama-3.3-70b-versatile") ?: "llama-3.3-70b-versatile"
    private fun getApiKey(): String? {
        val key = SecurePrefs.getDecryptedString(context, "ARIA_PREFS", "api_key_enc", "api_key")
        return key.ifEmpty { null }
    }

    private fun getBaseUrl(): String {
        return when (getProvider()) {
            "groq" -> "https://api.groq.com/openai/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
        }
    }

    private fun getBaseUrl(provider: String): String {
        return when (provider) {
            "groq" -> "https://api.groq.com/openai/v1"
            "openrouter" -> "https://openrouter.ai/api/v1"
            "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
            else -> prefs.getString("api_endpoint", "https://api.letta.com") ?: "https://api.letta.com"
        }
    }

    private fun getDefaultModel(provider: String): String {
        return when (provider) {
            "groq" -> "llama-3.3-70b-versatile"
            "openrouter" -> "meta-llama/llama-3.3-70b-instruct"
            "gemini" -> "gemini-1.5-flash"
            else -> "default"
        }
    }

    private fun getApiKey(provider: String): String? {
        val providerSpecific = SecurePrefs.getDecryptedString(
            context,
            "ARIA_PREFS",
            "api_key_${provider}_enc",
            "api_key_${provider}"
        )
        if (providerSpecific.isNotBlank()) return providerSpecific
        return getApiKey()
    }

    fun sendMessage(message: String): LettaResponse {
        val session = buildSessionContext(message, liveShortResponse = false)
        return sendThroughProviderChain(
            session = session,
            streamChunks = false,
            onChunk = null
        )
    }

    fun streamMessage(
        message: String,
        liveShortResponse: Boolean = true,
        onChunk: (String) -> Unit
    ): LettaResponse {
        val session = buildSessionContext(message, liveShortResponse = liveShortResponse)
        return sendThroughProviderChain(
            session = session,
            streamChunks = true,
            onChunk = onChunk
        )
    }

    private data class SessionContext(
        val providerChain: List<String>,
        val selectedProvider: String,
        val selectedModel: String,
        val systemPrompt: String,
        val message: String,
        val historyMessages: List<Message>
    )

    private fun buildSessionContext(message: String, liveShortResponse: Boolean): SessionContext {
        val selectedProvider = getProvider()
        val selectedModel = getModel()

        val personality = prefs.getString("personality", "girlfriend") ?: "girlfriend"
        val userName = prefs.getString("user_name", "") ?: ""
        val nickname = prefs.getString("nickname", "") ?: ""
        val customSystemPrompt = prefs.getString("custom_system_prompt", "") ?: ""

        val banglaModeEnabled = prefs.getBoolean("bangla_mode", true)
        val hasBanglaText = message.any { it.code in 0x0980..0x09FF }
        val wantsBangla = hasBanglaText || message.lowercase().contains("bangla") || message.contains("বাংলা")
        val lowerMessage = message.lowercase()
        val wantsScreenContext = lowerMessage.contains("screen") || lowerMessage.contains("what am i looking at") || lowerMessage.contains("what is this")

        var finalUserMessage = message
        if (wantsScreenContext) {
            val screenText = ARIAAccessibilityService.instance?.readScreen()
            if (!screenText.isNullOrBlank()) {
                finalUserMessage += "\n\n[System Context: The user's screen currently contains the following text:\n$screenText\n]"
            } else {
                finalUserMessage += "\n\n[System Context: Screen reading is currently unavailable. Ask the user to ensure Accessibility Service is enabled.]"
            }
        }

        var systemPrompt = PersonalityPrompts.getSystemPrompt(personality, userName, nickname)
        systemPrompt += """

            Safety automation rules:
            - Never output raw shell/root commands.
            - For device automation requests, respond with a warm Banglalish acknowledgement, then include a JSON object only using safe intents.
            - Preferred JSON formats:
              1) {"action":"launch_multiple_apps","target_apps":["whatsapp","chrome"]}
              2) {"action":"automation_request","tasks":[{"type":"read_incoming_sms","enabled":true,"risk_level":"low","require_confirmation":false}]}
            - Sensitive tasks (send_sms, social_post, contact_edit) must set "require_confirmation": true.
        """.trimIndent()
        if (banglaModeEnabled && wantsBangla) {
            systemPrompt += "\n\nLanguage rule: Reply in natural Bangla/Banglish. Keep it warm, simple, and easy for TTS."
        }
        if (liveShortResponse) {
            systemPrompt += "\n\nLive voice mode rule: Keep replies short, fast, and spoken-language friendly. Prefer 1-3 short sentences unless the user asks for details."
        }
        
        if (customSystemPrompt.isNotBlank()) {
            systemPrompt += "\n\n[USER CUSTOM INSTRUCTION (Strictly follow this open-mindedly)]:\n$customSystemPrompt"
        }

        val historyMessages = ConversationMemory.getMessages(context)
        ConversationMemory.addMessage(context, "user", message)

        val fallbackOrderRaw = prefs.getString("ai_fallback_order", "")
            .orEmpty()
            .split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }

        val providerChain = linkedSetOf<String>().apply {
            add(selectedProvider)
            if (fallbackOrderRaw.isNotEmpty()) {
                addAll(fallbackOrderRaw)
            } else {
                addAll(listOf("groq", "gemini", "openrouter", "letta"))
            }
        }.toList()

        return SessionContext(
            providerChain = providerChain,
            selectedProvider = selectedProvider,
            selectedModel = selectedModel,
            systemPrompt = systemPrompt,
            message = finalUserMessage,
            historyMessages = historyMessages
        )
    }

    private fun sendThroughProviderChain(
        session: SessionContext,
        streamChunks: Boolean,
        onChunk: ((String) -> Unit)?
    ): LettaResponse {
        val errors = mutableListOf<String>()

        session.providerChain.forEach { provider ->
            val apiKey = getApiKey(provider)
            if (provider != "letta" && apiKey.isNullOrBlank()) {
                errors += "$provider: missing api key"
                AiReliabilityLogger.log(context, "skip provider=$provider reason=missing_key")
                return@forEach
            }

            val model = if (provider == session.selectedProvider) session.selectedModel else getDefaultModel(provider)

            try {
                val response = if (streamChunks && onChunk != null) {
                    streamMessageWithProvider(
                        provider = provider,
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = session.systemPrompt,
                        message = session.message,
                        historyMessages = session.historyMessages,
                        onChunk = onChunk
                    )
                } else {
                    sendMessageWithProvider(
                        provider = provider,
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = session.systemPrompt,
                        message = session.message,
                        historyMessages = session.historyMessages
                    )
                }

                if (provider != session.selectedProvider) {
                    AiReliabilityLogger.log(context, "fallback success selected=${session.selectedProvider} used=$provider")
                }

                ConversationMemory.addMessage(context, "assistant", response.text)
                return response
            } catch (e: Exception) {
                val reason = e.message ?: "unknown_error"
                errors += "$provider: $reason"
                AiReliabilityLogger.log(context, "provider failure provider=$provider reason=$reason")
            }
        }

        throw Exception("All AI providers failed: ${errors.joinToString(" | ")}")
    }

    private fun sendMessageWithProvider(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>
    ): LettaResponse {
        val baseUrl = getBaseUrl(provider)
        val url = when (provider) {
            "gemini" -> "$baseUrl/models/$model:generateContent?key=$apiKey"
            "letta" -> "$baseUrl/v1/agents/${prefs.getString("agent_id", "")}/messages"
            else -> "$baseUrl/chat/completions"
        }

        val payload = buildPayload(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            message = message,
            historyMessages = historyMessages,
            stream = false
        )

        val request = buildRequest(url, provider, apiKey, payload)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("API error ${response.code}: ${response.message}")
            }
            val responseBody = response.body?.string() ?: throw Exception("Empty response")
            return parseResponse(responseBody, provider)
        }
    }

    private fun streamMessageWithProvider(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        onChunk: (String) -> Unit
    ): LettaResponse {
        if (provider == "letta") {
            val fallback = sendMessageWithProvider(provider, model, apiKey, systemPrompt, message, historyMessages)
            emitChunkedFallback(fallback.text, onChunk)
            return fallback
        }

        return runCatching {
            when (provider) {
                "gemini" -> streamGeminiResponse(provider, model, apiKey, systemPrompt, message, historyMessages, onChunk)
                else -> streamOpenAiCompatible(provider, model, apiKey, systemPrompt, message, historyMessages, onChunk)
            }
        }.getOrElse {
            val fallback = sendMessageWithProvider(provider, model, apiKey, systemPrompt, message, historyMessages)
            emitChunkedFallback(fallback.text, onChunk)
            fallback
        }
    }

    private fun streamOpenAiCompatible(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        onChunk: (String) -> Unit
    ): LettaResponse {
        val url = "${getBaseUrl(provider)}/chat/completions"
        val payload = buildPayload(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            message = message,
            historyMessages = historyMessages,
            stream = true
        )
        val request = buildRequest(url, provider, apiKey, payload)
        val fullText = StringBuilder()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("API error ${response.code}: ${response.message}")
            }
            val source = response.body?.source() ?: throw Exception("Empty stream body")
            readSseLines(source) { data ->
                if (data == "[DONE]") return@readSseLines
                val chunk = parseOpenAiStreamChunk(data)
                if (chunk.isNotBlank()) {
                    fullText.append(chunk)
                    onChunk(chunk)
                }
            }
        }

        val finalText = fullText.toString().trim()
        if (finalText.isBlank()) throw Exception("Empty streaming response")
        return LettaResponse(finalText)
    }

    private fun streamGeminiResponse(
        provider: String,
        model: String,
        apiKey: String?,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        onChunk: (String) -> Unit
    ): LettaResponse {
        val url = "${getBaseUrl(provider)}/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val payload = buildPayload(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            message = message,
            historyMessages = historyMessages,
            stream = true
        )
        val request = buildRequest(url, provider, apiKey, payload)
        val fullText = StringBuilder()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("API error ${response.code}: ${response.message}")
            }
            val source = response.body?.source() ?: throw Exception("Empty stream body")
            readSseLines(source) { data ->
                val chunk = parseGeminiStreamChunk(data)
                if (chunk.isNotBlank()) {
                    fullText.append(chunk)
                    onChunk(chunk)
                }
            }
        }

        val finalText = fullText.toString().trim()
        if (finalText.isBlank()) throw Exception("Empty streaming response")
        return LettaResponse(finalText)
    }

    private fun buildPayload(
        provider: String,
        model: String,
        systemPrompt: String,
        message: String,
        historyMessages: List<Message>,
        stream: Boolean
    ): JSONObject {
        return when (provider) {
            "gemini" -> {
                val history = ConversationMemory.getConversationHistoryForAPI(context)
                val finalPrompt = "$systemPrompt\n\n$history\nUser: $message"
                JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", finalPrompt)))
                    ))
                }
            }
            "letta" -> JSONObject().apply {
                put("message", message)
            }
            else -> {
                val messages = JSONArray().put(
                    JSONObject().put("role", "system").put("content", systemPrompt)
                )

                historyMessages.forEach { msg ->
                    if (msg.role == "user" || msg.role == "assistant") {
                        messages.put(
                            JSONObject()
                                .put("role", msg.role)
                                .put("content", msg.content)
                        )
                    }
                }

                messages.put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", message)
                )

                JSONObject().apply {
                    put("model", model)
                    put("messages", messages)
                    put("stream", stream)
                }
            }
        }
    }

    private fun buildRequest(
        url: String,
        provider: String,
        apiKey: String?,
        payload: JSONObject
    ): Request {
        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (provider != "gemini") {
            apiKey?.let { requestBuilder.addHeader("Authorization", "Bearer $it") }
        }

        return requestBuilder.build()
    }

    private fun readSseLines(source: BufferedSource, onData: (String) -> Unit) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isNotBlank()) {
                onData(data)
            }
        }
    }

    private fun parseOpenAiStreamChunk(data: String): String {
        return runCatching {
            val root = JsonParser.parseString(data).asJsonObject
            root.getAsJsonArray("choices")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("delta")
                ?.get("content")?.asString
                .orEmpty()
        }.getOrDefault("")
    }

    private fun parseGeminiStreamChunk(data: String): String {
        return runCatching {
            val root = JsonParser.parseString(data).asJsonObject
            root.getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject
                ?.get("text")?.asString
                .orEmpty()
        }.getOrDefault("")
    }

    private fun emitChunkedFallback(text: String, onChunk: (String) -> Unit) {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return
        val parts = normalized.split(Regex("(?<=[.!?।])\\s+|(?<=,)\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(normalized) }
        parts.forEach { onChunk(it) }
    }

    private fun parseResponse(jsonResponse: String, provider: String): LettaResponse {
        try {
            val jsonObject = JsonParser.parseString(jsonResponse).asJsonObject

            val assistantText = when (provider) {
                "gemini" -> {
                    jsonObject.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: "No response"
                }
                "letta" -> {
                    val messages = jsonObject.getAsJsonArray("messages")
                    var text = ""
                    for (msg in messages) {
                        val msgObj = msg.asJsonObject
                        if (msgObj.has("message_type") &&
                            msgObj.get("message_type").asString == "assistant_message"
                        ) {
                            text = msgObj.get("message").asString
                            break
                        }
                    }
                    text
                }
                else -> {
                    jsonObject.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString ?: "No response"
                }
            }

            return LettaResponse(assistantText)
        } catch (e: Exception) {
            throw Exception("Parse error: ${e.message}")
        }
    }
}
