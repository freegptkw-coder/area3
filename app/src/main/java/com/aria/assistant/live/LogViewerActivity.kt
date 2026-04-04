package com.aria.assistant.live

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.aria.assistant.R
import com.aria.assistant.live.core.PersistentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogViewerActivity : AppCompatActivity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var copyButton: Button
    private lateinit var shareButton: Button
    private lateinit var refreshButton: Button
    private lateinit var clearButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        supportActionBar?.title = "Persistent Logs"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logTextView = findViewById(R.id.logTextView)
        scrollView = findViewById(R.id.logScrollView)
        copyButton = findViewById(R.id.copyLogsButton)
        shareButton = findViewById(R.id.shareLogsButton)
        refreshButton = findViewById(R.id.refreshLogsButton)
        clearButton = findViewById(R.id.clearLogsButton)

        copyButton.setOnClickListener { copyLogsToClipboard() }
        shareButton.setOnClickListener { shareLogs() }
        refreshButton.setOnClickListener { loadLogs() }
        clearButton.setOnClickListener { clearLogs() }

        loadLogs()
    }

    private fun loadLogs() {
        CoroutineScope(Dispatchers.IO).launch {
            val logs = PersistentLogger.readLogs(this@LogViewerActivity, maxLines = 2000)
            withContext(Dispatchers.Main) {
                logTextView.text = logs
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }
    }

    private fun copyLogsToClipboard() {
        val logs = logTextView.text.toString()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ARIA Logs", logs)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun shareLogs() {
        CoroutineScope(Dispatchers.IO).launch {
            val logFile = PersistentLogger.getLogFile(this@LogViewerActivity)
            if (logFile != null && logFile.exists()) {
                withContext(Dispatchers.Main) {
                    try {
                        val uri = FileProvider.getUriForFile(
                            this@LogViewerActivity,
                            "${applicationContext.packageName}.fileprovider",
                            logFile
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "ARIA Persistent Logs")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share logs via"))
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@LogViewerActivity,
                            "Share failed: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LogViewerActivity, "No log file found", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun clearLogs() {
        // For safety, we don't implement clear - logs should persist for debugging
        Toast.makeText(this, "Logs auto-rotate at 2MB. No manual clear needed.", Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
