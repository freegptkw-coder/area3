package com.aria.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun removeLastMessage() {
        if (messages.isNotEmpty()) {
            val position = messages.size - 1
            messages.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun getItemCountCurrent() = messages.size

    override fun getItemViewType(position: Int): Int {
        return messages[position].sender.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            ChatMessage.SenderType.USER.ordinal -> {
                val view = inflater.inflate(R.layout.item_chat_user, parent, false)
                UserViewHolder(view)
            }
            ChatMessage.SenderType.ASSISTANT.ordinal -> {
                val view = inflater.inflate(R.layout.item_chat_assistant, parent, false)
                AssistantViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_chat_system, parent, false)
                SystemViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message)
            is SystemViewHolder -> holder.bind(message)
        }
    }

    override fun getItemCount(): Int = messages.size

    inner class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.messageText)
        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }

    inner class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.messageText)
        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }

    inner class SystemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.messageText)
        fun bind(message: ChatMessage) {
            textView.text = message.text
        }
    }
}
