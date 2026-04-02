package com.aria.assistant.live.core

import android.content.Context
import com.aria.assistant.VoiceCommandParser
import com.aria.assistant.automation.ParsedAutomationCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveDialogOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val llmGateway: StreamLlmGateway,
    private val responseCoordinator: ResponseStreamCoordinator,
    private val speechOutputArbiter: LiveSpeechOutputArbiter,
    private val emitEvent: (VoiceSessionEvent) -> Unit,
    private val onOverlayMessage: (String) -> Unit,
    private val onAudit: (String) -> Unit,
    private val trySendWsTurn: ((String) -> Boolean)? = null,
    private val shouldUseWsTurn: (() -> Boolean)? = null,
    private val wsFallbackDelayMs: Long = 1600L
) {

    private var activeTurnJob: Job? = null
    private var wsFallbackJob: Job? = null
    private var pendingConfirmation: ParsedAutomationCommand? = null

    @Volatile
    private var awaitingWsResponse: Boolean = false

    fun handleFinalTranscript(text: String) {
        val finalText = text.trim()
        if (finalText.isBlank()) return

        onAudit("live_orchestrator:final:${finalText.take(80)}")

        if (handlePendingConfirmation(finalText)) {
            return
        }

        val automation = VoiceCommandParser.parseAutomation(finalText)
        if (automation != null) {
            handleAutomation(automation)
            return
        }

        startAssistantTurn(finalText)
    }

    fun onWsResponseSignal() {
        if (!awaitingWsResponse) return
        awaitingWsResponse = false
        wsFallbackJob?.cancel()
        wsFallbackJob = null
        onAudit("live_orchestrator:ws_response_signal")
    }

    fun cancelActiveTurn(reason: String) {
        awaitingWsResponse = false
        wsFallbackJob?.cancel()
        wsFallbackJob = null
        activeTurnJob?.cancel()
        activeTurnJob = null
        responseCoordinator.cancel()
        speechOutputArbiter.stopNow(reason)
        onAudit("live_orchestrator:cancel:$reason")
    }

    private fun handleAutomation(command: ParsedAutomationCommand) {
        val requiresConfirmation = VoiceCommandParser.requiresConfirmation(command.envelope)
        if (requiresConfirmation) {
            pendingConfirmation = command
            emitEvent(VoiceSessionEvent.ConfirmationRequested(command.envelope.action))
            val prompt = "এটা sensitive action. Confirm করলে করি, cancel বললে থামি।"
            onOverlayMessage(prompt)
            speechOutputArbiter.speakText(prompt, flush = true, source = "confirmation_prompt")
            return
        }

        executeAutomation(command, acknowledged = true)
    }

    private fun handleAssistantReplyText(text: String) {
        if (text.isBlank()) return
        responseCoordinator.onChunk(text)
        onOverlayMessage(text.take(180))
    }

    private fun startAssistantTurn(userText: String) {
        cancelActiveTurn("new_turn")
        responseCoordinator.reset()
        emitEvent(VoiceSessionEvent.LlmRequestStarted)

        val useWsFirst = shouldUseWsTurn?.invoke() == true
        if (useWsFirst && trySendWsTurn?.invoke(userText) == true) {
            awaitingWsResponse = true
            wsFallbackJob = scope.launch {
                delay(wsFallbackDelayMs)
                if (!awaitingWsResponse) return@launch
                awaitingWsResponse = false
                onAudit("live_orchestrator:ws_timeout_provider_fallback")
                startProviderStreaming(userText)
            }
            return
        }

        startProviderStreaming(userText)
    }

    private fun startProviderStreaming(userText: String) {
        activeTurnJob = scope.launch {
            runCatching {
                llmGateway.streamResponse(
                    userText = userText,
                    liveShortResponse = true
                ) { chunk ->
                    emitEvent(VoiceSessionEvent.LlmResponseChunk(chunk))
                    handleAssistantReplyText(chunk)
                }
            }.onFailure {
                if (it is CancellationException) {
                    onAudit("live_orchestrator:provider_cancelled:${it.javaClass.simpleName}")
                    return@onFailure
                }
                emitEvent(VoiceSessionEvent.BackendFailure("provider_stream_failed:${it.javaClass.simpleName}"))
                val fallbackText = "দুঃখিত, এই মুহূর্তে live response আসছে না। আবার বলুন।"
                onOverlayMessage(fallbackText)
                speechOutputArbiter.speakText(fallbackText, flush = true, source = "provider_error")
                onAudit("live_orchestrator:provider_error:${it.javaClass.simpleName}")
            }.onSuccess { response ->
                val full = responseCoordinator.finish().ifBlank { response.text }
                if (full.isNotBlank()) {
                    onOverlayMessage(full.take(180))
                }
                onAudit("live_orchestrator:provider_done:${full.take(80)}")
            }
        }
    }

    private fun executeAutomation(command: ParsedAutomationCommand, acknowledged: Boolean) {
        pendingConfirmation = null
        activeTurnJob = scope.launch {
            if (acknowledged && command.acknowledgement.isNotBlank()) {
                speechOutputArbiter.speakText(command.acknowledgement, flush = true, source = "automation_ack")
                onOverlayMessage(command.acknowledgement.take(180))
            }

            val envelope = command.envelope
            val actionName = envelope.action.ifBlank { "automation" }
            emitEvent(VoiceSessionEvent.ActionExecutionStarted(actionName))

            val success = if (envelope.tasks.isNullOrEmpty() && envelope.targetApps.isNullOrEmpty()) {
                true
            } else {
                val result = VoiceCommandParser.executeAutomation(context, envelope)
                val summary = result.summary.take(220)
                onOverlayMessage(summary)
                speechOutputArbiter.speakText(summary, flush = false, source = "automation_result")
                result.executed > 0 || result.blocked == 0
            }

            emitEvent(VoiceSessionEvent.ActionExecutionFinished(actionName, success))
            onAudit("live_orchestrator:automation_done:$actionName:$success")
        }
    }

    private fun handlePendingConfirmation(text: String): Boolean {
        val pending = pendingConfirmation ?: return false
        val normalized = text.lowercase()
        val confirmed = listOf("yes", "confirm", "ok", "okay", "haan", "ji", "kor", "koro", "hao", "হ্যাঁ", "ঠিক আছে", "করো").any {
            normalized.contains(it)
        }
        val cancelled = listOf("no", "cancel", "stop", "na", "নাহ", "না", "থামো", "বাদ").any {
            normalized.contains(it)
        }
        if (!confirmed && !cancelled) return false

        emitEvent(VoiceSessionEvent.ConfirmationResolved(confirmed = confirmed))
        if (confirmed) {
            executeAutomation(pending, acknowledged = false)
        } else {
            pendingConfirmation = null
            val textReply = "ঠিক আছে, আমি cancel করলাম।"
            onOverlayMessage(textReply)
            speechOutputArbiter.speakText(textReply, flush = true, source = "confirmation_cancel")
        }
        return true
    }
}
