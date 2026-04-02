package com.aria.assistant.live.core

class ResponseStreamCoordinator(
    private val speechOutputArbiter: LiveSpeechOutputArbiter,
    private val onLlmChunk: ((String) -> Unit)? = null
) {

    private val aggregateBuffer = StringBuilder()
    private val speakBuffer = StringBuilder()

    @Synchronized
    fun reset() {
        aggregateBuffer.setLength(0)
        speakBuffer.setLength(0)
    }

    @Synchronized
    fun onChunk(chunk: String) {
        val safeChunk = chunk.trim()
        if (safeChunk.isBlank()) return

        aggregateBuffer.append(safeChunk)
        if (!safeChunk.endsWith(" ")) {
            aggregateBuffer.append(' ')
        }

        speakBuffer.append(safeChunk)
        if (!safeChunk.endsWith(" ")) {
            speakBuffer.append(' ')
        }

        onLlmChunk?.invoke(safeChunk)
        flushSpeakableSegments(force = false)
    }

    @Synchronized
    fun finish(): String {
        flushSpeakableSegments(force = true)
        return aggregateBuffer.toString().replace(Regex("\\s+"), " ").trim()
    }

    @Synchronized
    fun cancel() {
        reset()
    }

    private fun flushSpeakableSegments(force: Boolean) {
        var working = speakBuffer.toString()
        if (working.isBlank()) return

        val emitted = mutableListOf<String>()
        val sentenceRegex = Regex("(.+?[.!?।\\n])")
        sentenceRegex.findAll(working).forEach { match ->
            val sentence = match.groupValues[1].trim()
            if (sentence.isNotBlank()) {
                emitted += sentence
            }
        }

        val lastConsumedIndex = sentenceRegex.findAll(working).lastOrNull()?.range?.last?.plus(1) ?: 0
        if (lastConsumedIndex > 0) {
            working = working.substring(lastConsumedIndex)
        }

        if (force) {
            val tail = working.trim()
            if (tail.isNotBlank()) emitted += tail
            working = ""
        } else if (working.length >= 120) {
            val cutoff = working.lastIndexOf(' ').takeIf { it >= 48 } ?: 120
            val chunk = working.substring(0, cutoff).trim()
            if (chunk.isNotBlank()) emitted += chunk
            working = working.substring(cutoff).trimStart()
        }

        emitted.forEachIndexed { index, text ->
            speechOutputArbiter.speakText(
                text = text,
                flush = false,
                source = if (index == 0) "provider_stream_start" else "provider_stream"
            )
        }

        speakBuffer.setLength(0)
        speakBuffer.append(working)
    }
}
