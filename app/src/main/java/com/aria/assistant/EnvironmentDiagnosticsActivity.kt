package com.aria.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.live.LogViewerActivity
import com.google.android.material.button.MaterialButton

class EnvironmentDiagnosticsActivity : AppCompatActivity() {

    private lateinit var reportText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_environment_diagnostics)

        supportActionBar?.title = "Environment Diagnostics"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        reportText = findViewById(R.id.envReportText)

        findViewById<MaterialButton>(R.id.envRefreshButton).setOnClickListener {
            refreshReport()
        }

        findViewById<MaterialButton>(R.id.envRestartButton).setOnClickListener {
            EnvironmentManager.restartEnvironment(this)
            Toast.makeText(this, "Environment restart marker set", Toast.LENGTH_SHORT).show()
            refreshReport()
        }

        findViewById<MaterialButton>(R.id.envRebuildButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Rebuild embedded environment?")
                .setMessage("This clears and recreates app embedded env files.")
                .setPositiveButton("Rebuild") { _, _ ->
                    val ok = EnvironmentManager.rebuildEnvironment(this)
                    Toast.makeText(this, if (ok) "Rebuild complete" else "Rebuild failed", Toast.LENGTH_SHORT).show()
                    refreshReport()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        findViewById<MaterialButton>(R.id.envClearCacheButton).setOnClickListener {
            val ok = EnvironmentManager.clearCache(this)
            Toast.makeText(this, if (ok) "Cache cleared" else "Cache clear failed", Toast.LENGTH_SHORT).show()
            refreshReport()
        }

        findViewById<MaterialButton>(R.id.envOpenLogsButton).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.envCopyReportButton).setOnClickListener {
            val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clip.setPrimaryClip(ClipData.newPlainText("Environment Report", reportText.text))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }

        refreshReport()
    }

    private fun refreshReport() {
        reportText.text = EnvironmentManager.diagnosticReport(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
