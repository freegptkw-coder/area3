package com.aria.assistant.live

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aria.assistant.R
import com.aria.assistant.multitask.AriaTaskRuntime
import com.aria.assistant.multitask.TaskStatus
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LiveTaskManagerActivity : AppCompatActivity() {

    private val orchestrator = AriaTaskRuntime.orchestrator
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private var taskCollectorJob: Job? = null

    private lateinit var adapter: TaskStatusAdapter
    private lateinit var taskCountText: TextView
    private lateinit var emptyText: TextView
    private lateinit var clearDoneButton: MaterialButton
    private lateinit var viewLogsButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_task_manager)

        supportActionBar?.title = "Task Manager"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        taskCountText = findViewById(R.id.taskCountText)
        emptyText = findViewById(R.id.emptyText)
        clearDoneButton = findViewById(R.id.clearDoneButton)

        val recycler = findViewById<RecyclerView>(R.id.taskRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        adapter = TaskStatusAdapter(
            onCancel = { task -> orchestrator.cancelTask(task.id) },
            onPauseResume = { task ->
                if (task.status == TaskStatus.PAUSED) {
                    orchestrator.resumeTask(task.id)
                } else {
                    orchestrator.pauseTask(task.id)
                }
            }
        )
        recycler.adapter = adapter

        clearDoneButton.setOnClickListener {
            orchestrator.clearFinishedTasks()
        }
    }

    override fun onStart() {
        super.onStart()
        taskCollectorJob = uiScope.launch {
            orchestrator.tasks.collect { tasks ->
                adapter.submitList(tasks)
                val activeCount = tasks.count {
                    it.status == TaskStatus.RUNNING ||
                        it.status == TaskStatus.PAUSED ||
                        it.status == TaskStatus.QUEUED
                }
                taskCountText.text = "Active: $activeCount • Total: ${tasks.size}"
                emptyText.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
                clearDoneButton.isEnabled = tasks.any {
                    it.status == TaskStatus.COMPLETED ||
                        it.status == TaskStatus.CANCELED ||
                        it.status == TaskStatus.FAILED
                }
            }
        }
    }

    override fun onStop() {
        taskCollectorJob?.cancel()
        taskCollectorJob = null
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }
}
