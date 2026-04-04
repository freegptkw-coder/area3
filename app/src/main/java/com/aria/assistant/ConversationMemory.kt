package com.aria.assistant

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Message(
    val role: String, // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

object ConversationMemory {
    private const val PREFS_NAME = "ARIA_MEMORY"
    private const val KEY_MESSAGES = "conversation_messages"
    private const val KEY_FACTS = "long_term_memories"
    private const val MAX_MESSAGES = 40 // Keep last 40 messages
    
    private val gson = Gson()
    
    fun addMessage(context: Context, role: String, content: String) {
        val messages = getMessages(context).toMutableList()
        messages.add(Message(role, content))
        
        // Keep only last MAX_MESSAGES
        val trimmed = if (messages.size > MAX_MESSAGES) {
            messages.takeLast(MAX_MESSAGES)
        } else {
            messages
        }
        
        saveMessages(context, trimmed)
    }
    
    fun saveFact(context: Context, fact: String) {
        val facts = getFacts(context).toMutableList()
        if (!facts.contains(fact)) {
            facts.add(fact)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FACTS, gson.toJson(facts)).apply()
        }
    }
    
    fun getFacts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FACTS, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getMessages(context: Context): List<Message> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MESSAGES, "[]") ?: "[]"
        
        return try {
            val type = object : TypeToken<List<Message>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun getConversationHistory(): String {
        // Returns formatted history for API context
        return ""
    }
    
    fun getConversationHistoryForAPI(context: Context): String {
        val facts = getFacts(context)
        val memoryBlock = if (facts.isNotEmpty()) {
            "\n[LONG-TERM MEMORIES (Saved Facts about user):\n${facts.joinToString("\n- ")}]\n"
        } else ""

        val messages = getMessages(context)
        if (messages.isEmpty() && memoryBlock.isEmpty()) return ""
        
        val history = StringBuilder()
        history.append(memoryBlock)
        
        if (messages.isNotEmpty()) {
            history.append("\nPrevious conversation:\n")
            messages.forEach { msg ->
                when(msg.role) {
                    "user" -> history.append("User: ${msg.content}\n")
                    "assistant" -> history.append("ARIA: ${msg.content}\n")
                }
            }
        }
        return history.toString()
    }
    
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_MESSAGES).apply()
    }
    
    private fun saveMessages(context: Context, messages: List<Message>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(messages)
        prefs.edit().putString(KEY_MESSAGES, json).apply()
    }
}
