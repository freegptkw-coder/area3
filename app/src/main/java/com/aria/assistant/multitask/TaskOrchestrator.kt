package com.aria.assistant.multitask

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class TaskPriority(val weight: Int) {
    CRITICAL(4),
    HIGH(3),
    NORMAL(2),
    LOW(1)
}

enum class TaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELED,
    FAILED
}

object AriaTaskTypes {
    const val CHAT_QUERY = "chat_query"
    const val AUTOMATION = "automation"
    const val READ_INCOMING_SMS = "read_incoming_sms"
    const val SEND_SMS = "send_sms"
    const val SOCIAL_POST = "social_post"
    const val NOTIFICATION_ANNOUNCE = "notification_announce"
    const val SCREEN_SUMMARY = "screen_summary"
    const val NAVIGATION = "navigation"
}

data class TaskRequest(
    val title: String,
    val type: String,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val requiresConfirmation: Boolean = false,
    val description: String = "",
    val dependencies: List<String> = emptyList()
)

data class TaskSnapshot(
    val id: String,
    val title: String,
    val type: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val progressPercent: Int,
    val description: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val requiresConfirmation: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val errorMessage: String? = null
)

class TaskExecutionContext internal constructor(
    private val taskId: String,
    private val orchestrator: TaskOrchestrator
) {
    suspend fun awaitIfPaused() {
        orchestrator.awaitIfPaused(taskId)
    }

    fun updateProgress(progressPercent: Int, description: String) {
        orchestrator.updateProgress(taskId, progressPercent, description)
    }

    fun isCanceled(): Boolean = orchestrator.isTaskCanceled(taskId)
}

class TaskOrchestrator(
    maxConcurrentTasks: Int = 3
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executionSlots = Semaphore(maxConcurrentTasks)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val pauseSignals = ConcurrentHashMap<String, MutableStateFlow<Boolean>>()

    private val _tasks = MutableStateFlow<List<TaskSnapshot>>(emptyList())
    val tasks: StateFlow<List<TaskSnapshot>> = _tasks

    private val _taskEvents = MutableSharedFlow<TaskSnapshot>(extraBufferCapacity = 64)
    val taskEvents: SharedFlow<TaskSnapshot> = _taskEvents

    fun scheduleTask(
        request: TaskRequest,
        block: suspend TaskExecutionContext.() -> Unit
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        pauseSignals[id] = MutableStateFlow(false)

        upsert(
            TaskSnapshot(
                id = id,
                title = request.title,
                type = request.type,
                priority = request.priority,
                status = TaskStatus.QUEUED,
                progressPercent = 0,
                description = request.description.ifBlank { "Queued" },
                createdAtMs = now,
                updatedAtMs = now,
                requiresConfirmation = request.requiresConfirmation,
                dependencies = request.dependencies
            )
        )

        val job = scope.launch {
            val context = TaskExecutionContext(id, this@TaskOrchestrator)
            try {
                executionSlots.withPermit {
                    if (!isActive) throw CancellationException("task_cancelled_before_start")

                    setStatus(id, TaskStatus.RUNNING, 5, "Running")
                    context.awaitIfPaused()
                    context.block()

                    if (!isTaskCanceled(id)) {
                        setStatus(id, TaskStatus.COMPLETED, 100, "Completed")
                    }
                }
            } catch (_: CancellationException) {
                setStatus(id, TaskStatus.CANCELED, getProgress(id), "Canceled")
            } catch (t: Throwable) {
                setStatus(id, TaskStatus.FAILED, getProgress(id), "Failed: ${t.message.orEmpty()}", t.message)
            } finally {
                pauseSignals.remove(id)
                jobs.remove(id)
            }
        }

        jobs[id] = job
        return id
    }

    fun cancelTask(taskId: String) {
        jobs[taskId]?.cancel(CancellationException("user_cancelled"))
        setStatus(taskId, TaskStatus.CANCELED, getProgress(taskId), "Canceled by user")
    }

    fun pauseTask(taskId: String) {
        pauseSignals[taskId]?.value = true
        val current = find(taskId) ?: return
        if (current.status == TaskStatus.RUNNING || current.status == TaskStatus.QUEUED) {
            setStatus(taskId, TaskStatus.PAUSED, current.progressPercent, "Paused")
        }
    }

    fun resumeTask(taskId: String) {
        pauseSignals[taskId]?.value = false
        val current = find(taskId) ?: return
        if (current.status == TaskStatus.PAUSED) {
            setStatus(taskId, TaskStatus.RUNNING, current.progressPercent, "Resumed")
        }
    }

    fun pauseLowerPriorityTasks(priority: TaskPriority) {
        _tasks.value
            .filter {
                it.status == TaskStatus.RUNNING &&
                    it.priority.weight < priority.weight
            }
            .forEach { pauseTask(it.id) }
    }

    fun resumeAllPausedTasks() {
        _tasks.value
            .filter { it.status == TaskStatus.PAUSED }
            .forEach { resumeTask(it.id) }
    }

    internal fun updateProgress(taskId: String, progressPercent: Int, description: String) {
        val bounded = progressPercent.coerceIn(0, 100)
        val current = find(taskId) ?: return
        val status = if (current.status == TaskStatus.PAUSED) TaskStatus.PAUSED else TaskStatus.RUNNING
        setStatus(taskId, status, bounded, description)
    }

    internal suspend fun awaitIfPaused(taskId: String) {
        while (pauseSignals[taskId]?.value == true) {
            if (isTaskCanceled(taskId)) throw CancellationException("task_canceled_while_paused")
            delay(120)
        }
    }

    internal fun isTaskCanceled(taskId: String): Boolean {
        val job = jobs[taskId] ?: return false
        return job.isCancelled
    }

    fun clearFinishedTasks() {
        mutate { list ->
            list.removeAll {
                it.status == TaskStatus.COMPLETED ||
                    it.status == TaskStatus.CANCELED ||
                    it.status == TaskStatus.FAILED
            }
        }
    }

    private fun getProgress(taskId: String): Int = find(taskId)?.progressPercent ?: 0

    private fun find(taskId: String): TaskSnapshot? = _tasks.value.firstOrNull { it.id == taskId }

    private fun setStatus(
        taskId: String,
        status: TaskStatus,
        progressPercent: Int,
        description: String,
        errorMessage: String? = null
    ) {
        mutate { list ->
            val idx = list.indexOfFirst { it.id == taskId }
            if (idx < 0) return@mutate
            val old = list[idx]
            val updated = old.copy(
                status = status,
                progressPercent = progressPercent.coerceIn(0, 100),
                description = description,
                updatedAtMs = System.currentTimeMillis(),
                errorMessage = errorMessage
            )
            list[idx] = updated
            _taskEvents.tryEmit(updated)
        }
    }

    private fun upsert(snapshot: TaskSnapshot) {
        mutate { list ->
            val idx = list.indexOfFirst { it.id == snapshot.id }
            if (idx >= 0) {
                list[idx] = snapshot
            } else {
                list.add(snapshot)
            }
            _taskEvents.tryEmit(snapshot)
        }
    }

    private fun mutate(block: (MutableList<TaskSnapshot>) -> Unit) {
        synchronized(_tasks) {
            val mutable = _tasks.value.toMutableList()
            block(mutable)
            _tasks.value = mutable.sortedWith(
                compareByDescending<TaskSnapshot> {
                    when (it.status) {
                        TaskStatus.RUNNING -> 5
                        TaskStatus.PAUSED -> 4
                        TaskStatus.QUEUED -> 3
                        TaskStatus.FAILED -> 2
                        TaskStatus.CANCELED -> 1
                        TaskStatus.COMPLETED -> 0
                    }
                }.thenByDescending { it.priority.weight }
                    .thenByDescending { it.updatedAtMs }
            )
        }
    }
}

object AriaTaskRuntime {
    val orchestrator: TaskOrchestrator by lazy { TaskOrchestrator() }
}
