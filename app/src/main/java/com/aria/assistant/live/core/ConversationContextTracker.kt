package com.aria.assistant.live.core

import android.util.Log
import java.util.concurrent.LinkedBlockingDeque

/**
 * Update #7: Conversation Context Tracker - remembers last 3 topics, sentiment.
 * Helps maintain conversational continuity across sessions.
 */
class ConversationContextTracker(private val maxTopics: Int = 3) {
    companion object {
        private const val TAG = "ContextTracker"
    }

    data class ContextEntry(
        val topic: String,
        val timestampMs: Long = System.currentTimeMillis(),
        val sentiment: Float = 0f // -1.0 to 1.0
    )

    private val recentTopics = LinkedBlockingDeque<ContextEntry>(maxTopics)

    fun onUserMessage(text: String, sentiment: Float = 0f) {
        val words = text.split(" ").take(5).joinToString(" ")
        if (words.isBlank()) return

        val entry = ContextEntry(words.trim(), System.currentTimeMillis(), sentiment)
        if (recentTopics.size >= maxTopics) recentTopics.removeFirst()
        recentTopics.add(entry)
        Log.d(TAG, "Context: Added topic '$words', sentiment=$sentiment")
    }

    fun getContextSummary(): String {
        if (recentTopics.isEmpty()) return "No prior context"
        return recentTopics.joinToString(", ") { it.topic }
    }

    fun getLastTopics(): List<ContextEntry> = recentTopics.toList()

    fun getAverageSentiment(): Float {
        if (recentTopics.isEmpty()) return 0f
        return recentTopics.map { it.sentiment }.average().toFloat()
    }

    fun clear() {
        recentTopics.clear()
        Log.i(TAG, "Context cleared")
    }

    fun size(): Int = recentTopics.size
}
