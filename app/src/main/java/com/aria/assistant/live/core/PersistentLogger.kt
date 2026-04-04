package com.aria.assistant.live.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PersistentLogger {
    private const val LOG_FILE_NAME = "aria_persistent_log.txt"
    private const val MAX_LOG_SIZE_BYTES = 2 * 1024 * 1024 // 2MB
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(context: Context, category: String, message: String) {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            
            // Rotate if too large
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
                val backupFile = File(context.filesDir, "aria_persistent_log_backup.txt")
                if (backupFile.exists()) backupFile.delete()
                logFile.renameTo(backupFile)
            }
            
            val timestamp = dateFormat.format(Date())
            val logLine = "[$timestamp] [$category] $message\n"
            
            logFile.appendText(logLine)
        } catch (e: Exception) {
            // Silently ignore logging errors
        }
    }

    @Synchronized
    fun readLogs(context: Context, maxLines: Int = 1000): String {
        try {
            val logFile = File(context.filesDir, LOG_FILE_NAME)
            if (!logFile.exists()) return "No persistent logs found."
            
            val lines = logFile.readLines()
            val start = if (lines.size > maxLines) lines.size - maxLines else 0
            return lines.subList(start, lines.size).joinToString("\n")
        } catch (e: Exception) {
            return "Error reading logs: ${e.message}"
        }
    }
    
    @Synchronized
    fun getLogFile(context: Context): File? {
        val file = File(context.filesDir, LOG_FILE_NAME)
        return if (file.exists()) file else null
    }
}
