package com.aria.assistant

import android.content.Context
import android.os.Build
import com.aria.assistant.live.core.PersistentLogger
import com.aria.assistant.workspace.WorkspaceRegistry
import java.io.File
import java.util.Locale

object EnvironmentManager {
    private const val PREF = "ARIA_PREFS"
    private const val KEY_RESTART_AT = "env_restart_at"
    private const val KEY_REBUILD_AT = "env_rebuild_at"

    fun embeddedEnvRoot(context: Context): File {
        return File(context.filesDir, "embedded_env").apply { mkdirs() }
    }

    fun restartEnvironment(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_RESTART_AT, System.currentTimeMillis())
            .apply()
        PersistentLogger.log(context, "ENV", "Environment restart requested")
    }

    fun rebuildEnvironment(context: Context): Boolean {
        val root = embeddedEnvRoot(context)
        val ok = runCatching {
            root.deleteRecursively()
            root.mkdirs()
            true
        }.getOrDefault(false)

        if (ok) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_REBUILD_AT, System.currentTimeMillis())
                .apply()
            PersistentLogger.log(context, "ENV", "Environment rebuild completed")
        }
        return ok
    }

    fun clearCache(context: Context): Boolean {
        return runCatching {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            PersistentLogger.log(context, "ENV", "App cache cleared")
            true
        }.getOrDefault(false)
    }

    fun diagnosticReport(context: Context): String {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val lines = mutableListOf<String>()

        lines += "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
        lines += "Package: ${context.packageName}"
        lines += "filesDir: ${context.filesDir.absolutePath}"
        lines += "cacheDir: ${context.cacheDir.absolutePath}"
        lines += "embeddedEnv: ${embeddedEnvRoot(context).absolutePath}"
        lines += "workspaceCount: ${WorkspaceRegistry.list(context).size}"
        lines += "workspaceDefaultMode: ${WorkspaceRegistry.getDefaultMode(context).label}"

        val lastRestart = prefs.getLong(KEY_RESTART_AT, 0L)
        val lastRebuild = prefs.getLong(KEY_REBUILD_AT, 0L)
        lines += "lastRestartAt: ${if (lastRestart > 0) lastRestart else "never"}"
        lines += "lastRebuildAt: ${if (lastRebuild > 0) lastRebuild else "never"}"

        lines += ""
        lines += "Tooling checks:"
        lines += "- sh: ${checkTool("sh")}" 
        lines += "- git: ${checkTool("git")}" 
        lines += "- python3: ${checkTool("python3")}" 

        lines += ""
        lines += "Recent logs:"
        val recentLogs = PersistentLogger.readLogs(context, maxLines = 20)
        if (recentLogs.isBlank()) {
            lines += "(no persistent logs)"
        } else {
            lines += recentLogs.lines().takeLast(20)
        }

        return lines.joinToString("\n")
    }

    private fun checkTool(name: String): String {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "command -v $name").start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code == 0 && output.isNotBlank()) "ok ($output)" else "missing"
        }.getOrElse {
            "error (${it.message ?: "unknown"})"
        }
    }

    fun formatTimestamp(ms: Long): String {
        if (ms <= 0L) return "never"
        return String.format(Locale.US, "%d", ms)
    }
}
