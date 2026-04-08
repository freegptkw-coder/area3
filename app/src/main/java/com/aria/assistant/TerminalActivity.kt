package com.aria.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.live.core.PersistentLogger
import com.aria.assistant.terminal.TerminalCommandPolicy
import com.aria.assistant.terminal.TerminalSessionManager
import com.aria.assistant.workspace.WorkspacePolicyDecider
import com.aria.assistant.workspace.WorkspaceRegistry
import com.google.android.material.button.MaterialButton
import java.io.File

class TerminalActivity : AppCompatActivity(), TerminalSessionManager.Listener {

    companion object {
        const val EXTRA_WORKSPACE_ID = "workspace_id"
    }

    private lateinit var workspaceLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var runButton: MaterialButton
    private lateinit var interruptButton: MaterialButton

    private val sessionManager = TerminalSessionManager()
    private var lastApprovedDangerCommand: String? = null
    private var lastApprovedWriteCommand: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        supportActionBar?.title = "Terminal"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        workspaceLabel = findViewById(R.id.terminalWorkspaceLabel)
        modeLabel = findViewById(R.id.terminalModeLabel)
        terminalOutput = findViewById(R.id.terminalOutput)
        commandInput = findViewById(R.id.terminalCommandInput)
        runButton = findViewById(R.id.terminalRunButton)
        interruptButton = findViewById(R.id.terminalInterruptButton)

        runButton.setOnClickListener { attemptExecuteCommand() }

        interruptButton.setOnClickListener {
            val interrupted = sessionManager.interrupt()
            appendOutput(if (interrupted) "[system] interrupt requested" else "[system] no running command")
        }

        findViewById<MaterialButton>(R.id.terminalRestartButton).setOnClickListener {
            sessionManager.restartSession()
            appendOutput("[system] session restarted")
        }

        findViewById<MaterialButton>(R.id.terminalClearButton).setOnClickListener {
            terminalOutput.text = ""
        }

        findViewById<MaterialButton>(R.id.terminalCopyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Output", terminalOutput.text))
            Toast.makeText(this, "Output copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.terminalPasteButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (text.isNotBlank()) {
                commandInput.setText(text)
                commandInput.setSelection(commandInput.text?.length ?: 0)
            }
        }

        renderWorkspaceHeader()
        appendOutput("[system] ready")
    }

    private fun renderWorkspaceHeader() {
        val workspace = resolveWorkspace()
        if (workspace == null) {
            workspaceLabel.text = "Workspace: none"
            modeLabel.text = "Mode: default (${WorkspaceRegistry.getDefaultMode(this).label})"
            return
        }

        workspaceLabel.text = "Workspace: ${workspace.name}\n${workspace.localPath}"
        modeLabel.text = "Mode: ${workspace.permissionMode.label}"
    }

    private fun attemptExecuteCommand() {
        val command = commandInput.text?.toString().orEmpty().trim()
        if (command.isBlank()) {
            Toast.makeText(this, "Enter a command", Toast.LENGTH_SHORT).show()
            return
        }

        val workspace = resolveWorkspace()

        val dangerApproved = command == lastApprovedDangerCommand
        val dangerDecision = TerminalCommandPolicy.evaluate(command, approvedHighRisk = dangerApproved)
        if (!dangerDecision.allowed) {
            if (dangerDecision.requiresConfirmation) {
                askDangerApproval(command, dangerDecision.reason)
            } else {
                appendOutput("[blocked] ${dangerDecision.reason}")
            }
            return
        }

        val mode = workspace?.permissionMode ?: WorkspaceRegistry.getDefaultMode(this)
        val writeApproved = command == lastApprovedWriteCommand
        val workspaceDecision = WorkspacePolicyDecider.evaluate(mode, command, userConfirmedWrite = writeApproved)
        if (!workspaceDecision.allowed) {
            if (workspaceDecision.requiresConfirmation) {
                askWriteApproval(command, workspaceDecision.reason)
            } else {
                appendOutput("[blocked] ${workspaceDecision.reason}")
            }
            return
        }

        if (workspace != null) {
            WorkspaceRegistry.markOpened(this, workspace.id)
        }
        val workDir = workspace?.localPath?.let(::File)?.takeIf { it.exists() }
        sessionManager.execute(command, workDir, this)
        lastApprovedDangerCommand = null
        lastApprovedWriteCommand = null
    }

    private fun askDangerApproval(command: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("High-risk command")
            .setMessage("$reason\n\nCommand:\n$command")
            .setPositiveButton("Approve once") { _, _ ->
                lastApprovedDangerCommand = command
                attemptExecuteCommand()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askWriteApproval(command: String, reason: String) {
        AlertDialog.Builder(this)
            .setTitle("Write confirmation")
            .setMessage("$reason\n\nCommand:\n$command")
            .setPositiveButton("Allow once") { _, _ ->
                lastApprovedWriteCommand = command
                attemptExecuteCommand()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resolveWorkspace() = WorkspaceRegistry.getById(
        this,
        intent.getStringExtra(EXTRA_WORKSPACE_ID)
    ) ?: WorkspaceRegistry.getLastWorkspace(this)

    private fun appendOutput(line: String) {
        val current = terminalOutput.text?.toString().orEmpty()
        val updated = if (current.isBlank()) line else "$current\n$line"
        terminalOutput.text = updated.takeLast(40_000)
    }

    override fun onSessionOutput(line: String) {
        runOnUiThread { appendOutput(line) }
    }

    override fun onCommandStarted(command: String) {
        runOnUiThread {
            runButton.isEnabled = false
            interruptButton.isEnabled = true
            appendOutput("$ $command")
            PersistentLogger.log(this, "TERMINAL", "cmd_start:$command")
        }
    }

    override fun onCommandFinished(exitCode: Int) {
        runOnUiThread {
            runButton.isEnabled = true
            interruptButton.isEnabled = false
            appendOutput("[exit $exitCode]")
            PersistentLogger.log(this, "TERMINAL", "cmd_exit:$exitCode")
        }
    }

    override fun onCommandRejected(reason: String) {
        runOnUiThread {
            appendOutput("[reject] $reason")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
