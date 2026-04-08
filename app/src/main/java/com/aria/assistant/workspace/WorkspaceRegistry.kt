package com.aria.assistant.workspace

import android.content.Context
import org.json.JSONArray
import java.util.UUID

object WorkspaceRegistry {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_WORKSPACES = "workspace_registry_v1"
    private const val KEY_LAST_WORKSPACE_ID = "workspace_last_id"
    private const val KEY_DEFAULT_MODE = "workspace_default_mode"
    private const val MAX_WORKSPACES = 40

    fun list(context: Context): List<WorkspaceRecord> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_WORKSPACES, "[]")
            .orEmpty()

        return runCatching {
            val arr = JSONArray(raw)
            val out = mutableListOf<WorkspaceRecord>()
            for (i in 0 until arr.length()) {
                WorkspaceRecord.fromJson(arr.getJSONObject(i))?.let { out += it }
            }
            out.sortedByDescending { it.lastOpenedAt }
        }.getOrDefault(emptyList())
    }

    fun upsert(context: Context, record: WorkspaceRecord) {
        val updated = list(context)
            .filterNot { it.id == record.id }
            .plus(record)
            .sortedByDescending { it.lastOpenedAt }
            .take(MAX_WORKSPACES)
        save(context, updated)
        setLastWorkspaceId(context, record.id)
    }

    fun remove(context: Context, workspaceId: String) {
        val updated = list(context).filterNot { it.id == workspaceId }
        save(context, updated)
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_WORKSPACE_ID, "") == workspaceId) {
            prefs.edit().remove(KEY_LAST_WORKSPACE_ID).apply()
        }
    }

    fun updateMode(context: Context, workspaceId: String, mode: WorkspacePermissionMode) {
        val now = System.currentTimeMillis()
        val updated = list(context).map {
            if (it.id == workspaceId) it.copy(permissionMode = mode, lastOpenedAt = now) else it
        }
        save(context, updated)
    }

    fun markOpened(context: Context, workspaceId: String) {
        val now = System.currentTimeMillis()
        val updated = list(context).map {
            if (it.id == workspaceId) it.copy(lastOpenedAt = now) else it
        }
        save(context, updated)
        setLastWorkspaceId(context, workspaceId)
    }

    fun getById(context: Context, workspaceId: String?): WorkspaceRecord? {
        if (workspaceId.isNullOrBlank()) return null
        return list(context).firstOrNull { it.id == workspaceId }
    }

    fun getLastWorkspace(context: Context): WorkspaceRecord? {
        val id = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_LAST_WORKSPACE_ID, "")
            .orEmpty()
        return getById(context, id)
    }

    fun getDefaultMode(context: Context): WorkspacePermissionMode {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_MODE, WorkspacePermissionMode.ASK_BEFORE_WRITE.key)
        return WorkspacePermissionMode.fromKey(raw)
    }

    fun setDefaultMode(context: Context, mode: WorkspacePermissionMode) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEFAULT_MODE, mode.key)
            .apply()
    }

    fun createWorkspaceId(): String = "ws_${UUID.randomUUID()}"

    private fun save(context: Context, items: List<WorkspaceRecord>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKSPACES, array.toString())
            .apply()
    }

    private fun setLastWorkspaceId(context: Context, id: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_WORKSPACE_ID, id)
            .apply()
    }
}
