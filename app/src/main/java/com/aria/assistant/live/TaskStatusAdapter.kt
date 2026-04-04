package com.aria.assistant.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.R
import com.aria.assistant.multitask.TaskSnapshot
import com.aria.assistant.multitask.TaskStatus
import com.google.android.material.button.MaterialButton

class TaskStatusAdapter(
    private val onCancel: (TaskSnapshot) -> Unit,
    private val onPauseResume: (TaskSnapshot) -> Unit
) : RecyclerView.Adapter<TaskStatusAdapter.TaskViewHolder>() {

    private val items = mutableListOf<TaskSnapshot>()

    fun submitList(newList: List<TaskSnapshot>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_status, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val task = items[position]
        holder.bind(task)
        
        // Simple fade-in animation for new items
        holder.itemView.alpha = 0f
        holder.itemView.animate().alpha(1f).setDuration(300).start()
    }

    override fun getItemCount(): Int = items.size

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.taskTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.taskSubtitle)
        private val status = itemView.findViewById<TextView>(R.id.taskStatus)
        private val progressText = itemView.findViewById<TextView>(R.id.taskProgressText)
        private val progress = itemView.findViewById<ProgressBar>(R.id.taskProgress)
        private val cancelButton = itemView.findViewById<MaterialButton>(R.id.cancelTaskButton)
        private val pauseResumeButton = itemView.findViewById<MaterialButton>(R.id.pauseResumeTaskButton)

        fun bind(task: TaskSnapshot) {
            title.text = task.title
            subtitle.text = task.description
            status.text = "${task.type} • ${task.priority.name.lowercase()} • ${task.status.name.lowercase()}"
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                progress.setProgress(task.progressPercent, true)
            } else {
                progress.progress = task.progressPercent
            }
            
            progressText.text = "${task.progressPercent}%"

            val runningLike = task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED
            val pausable = runningLike || task.status == TaskStatus.PAUSED
            val cancelable = runningLike || task.status == TaskStatus.PAUSED

            pauseResumeButton.visibility = if (pausable) View.VISIBLE else View.GONE
            pauseResumeButton.text = if (task.status == TaskStatus.PAUSED) "Resume" else "Pause"
            pauseResumeButton.setOnClickListener { onPauseResume(task) }

            cancelButton.visibility = if (cancelable) View.VISIBLE else View.GONE
            cancelButton.setOnClickListener { onCancel(task) }
        }
    }
}
