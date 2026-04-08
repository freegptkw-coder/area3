package com.aria.assistant.workspace

import org.json.JSONObject

data class WorkspaceRecord(
    val id: String,
    val name: String,
    val treeUri: String?,
    val localPath: String,
    val permissionMode: WorkspacePermissionMode,
    val createdAt: Long,
    val lastOpenedAt: Long
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("tree_uri", treeUri ?: "")
            put("local_path", localPath)
            put("permission_mode", permissionMode.key)
            put("created_at", createdAt)
            put("last_opened_at", lastOpenedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): WorkspaceRecord? {
            val id = json.optString("id").trim()
            val name = json.optString("name").trim()
            val localPath = json.optString("local_path").trim()
            if (id.isBlank() || name.isBlank() || localPath.isBlank()) return null

            return WorkspaceRecord(
                id = id,
                name = name,
                treeUri = json.optString("tree_uri").takeIf { it.isNotBlank() },
                localPath = localPath,
                permissionMode = WorkspacePermissionMode.fromKey(json.optString("permission_mode")),
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                lastOpenedAt = json.optLong("last_opened_at", System.currentTimeMillis())
            )
        }
    }
}
