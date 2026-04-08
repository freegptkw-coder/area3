package com.aria.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.aria.assistant.workspace.WorkspaceManager
import com.aria.assistant.workspace.WorkspacePermissionMode
import com.aria.assistant.workspace.WorkspaceRegistry
import com.google.android.material.button.MaterialButton
import java.io.File

class WorkspaceManagerActivity : AppCompatActivity() {

    private lateinit var modeSpinner: Spinner
    private lateinit var recentList: ListView
    private lateinit var grantsList: ListView

    private var latestTreeImportLabel: String = "workspace"

    private val openTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        handleTreeImport(uri)
    }

    private val openZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val mode = selectedMode()
        val result = WorkspaceManager.importFromZipUri(
            context = this,
            zipUri = uri,
            displayName = "zip_workspace",
            mode = mode
        )
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workspace_manager)

        supportActionBar?.title = "Workspace Manager"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        modeSpinner = findViewById(R.id.workspaceModeSpinner)
        recentList = findViewById(R.id.recentWorkspaceList)
        grantsList = findViewById(R.id.grantsList)

        val modeLabels = WorkspacePermissionMode.values().map { it.label }
        modeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modeLabels)
        modeSpinner.setSelection(WorkspaceRegistry.getDefaultMode(this).ordinal)

        modeSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                WorkspaceRegistry.setDefaultMode(this@WorkspaceManagerActivity, WorkspacePermissionMode.values()[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        findViewById<MaterialButton>(R.id.openProjectButton).setOnClickListener {
            latestTreeImportLabel = "project"
            openTreeLauncher.launch(null)
        }

        findViewById<MaterialButton>(R.id.importFolderButton).setOnClickListener {
            latestTreeImportLabel = "folder"
            openTreeLauncher.launch(null)
        }

        findViewById<MaterialButton>(R.id.importZipButton).setOnClickListener {
            openZipLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        findViewById<MaterialButton>(R.id.exportLatestButton).setOnClickListener {
            exportLatestWorkspace()
        }

        recentList.setOnItemClickListener { _, _, position, _ ->
            val record = WorkspaceRegistry.list(this).getOrNull(position) ?: return@setOnItemClickListener
            WorkspaceRegistry.markOpened(this, record.id)
            val intent = Intent(this, TerminalActivity::class.java).apply {
                putExtra(TerminalActivity.EXTRA_WORKSPACE_ID, record.id)
            }
            startActivity(intent)
        }

        recentList.setOnItemLongClickListener { _, _, position, _ ->
            val record = WorkspaceRegistry.list(this).getOrNull(position) ?: return@setOnItemLongClickListener true
            showWorkspaceActions(record.id)
            true
        }

        grantsList.setOnItemClickListener { _, _, position, _ ->
            val grant = WorkspaceManager.listPersistedGrants(this).getOrNull(position)
                ?: return@setOnItemClickListener
            confirmRevokeGrant(grant.uri)
        }

        refreshUi()
    }

    private fun selectedMode(): WorkspacePermissionMode {
        return WorkspacePermissionMode.values().getOrElse(modeSpinner.selectedItemPosition) {
            WorkspacePermissionMode.ASK_BEFORE_WRITE
        }
    }

    private fun handleTreeImport(uri: Uri) {
        val result = WorkspaceManager.importFromTreeUri(
            context = this,
            treeUri = uri,
            displayName = latestTreeImportLabel,
            mode = selectedMode()
        )
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        refreshUi()
    }

    private fun showWorkspaceActions(workspaceId: String) {
        val options = arrayOf("Set permission mode", "Delete record")
        AlertDialog.Builder(this)
            .setTitle("Workspace Actions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showModePicker(workspaceId)
                    1 -> confirmDeleteWorkspace(workspaceId)
                }
            }
            .show()
    }

    private fun showModePicker(workspaceId: String) {
        val modes = WorkspacePermissionMode.values()
        val labels = modes.map { it.label }.toTypedArray()
        val current = WorkspaceRegistry.getById(this, workspaceId)?.permissionMode?.ordinal ?: 1

        AlertDialog.Builder(this)
            .setTitle("Workspace permission mode")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                WorkspaceRegistry.updateMode(this, workspaceId, modes[which])
                dialog.dismiss()
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteWorkspace(workspaceId: String) {
        val record = WorkspaceRegistry.getById(this, workspaceId) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete workspace record?")
            .setMessage("This removes it from recent list. Keep local files?")
            .setPositiveButton("Keep files") { _, _ ->
                WorkspaceRegistry.remove(this, workspaceId)
                refreshUi()
            }
            .setNeutralButton("Delete files") { _, _ ->
                runCatching { File(record.localPath).deleteRecursively() }
                WorkspaceRegistry.remove(this, workspaceId)
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRevokeGrant(uri: String) {
        AlertDialog.Builder(this)
            .setTitle("Revoke access?")
            .setMessage(uri)
            .setPositiveButton("Revoke") { _, _ ->
                val ok = WorkspaceManager.revokePersistedGrant(this, uri)
                Toast.makeText(this, if (ok) "Grant revoked" else "Could not revoke", Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportLatestWorkspace() {
        val latest = WorkspaceRegistry.getLastWorkspace(this)
        if (latest == null) {
            Toast.makeText(this, "No workspace to export", Toast.LENGTH_SHORT).show()
            return
        }

        val zip = WorkspaceManager.exportWorkspaceZip(this, latest)
        if (zip == null || !zip.exists()) {
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zip)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Export workspace ZIP"))
    }

    private fun refreshUi() {
        val workspaces = WorkspaceRegistry.list(this)
        val workspaceLines = if (workspaces.isEmpty()) {
            listOf("No recent workspace")
        } else {
            workspaces.map {
                "${it.name} • ${it.permissionMode.label}\n${it.localPath}"
            }
        }
        recentList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, workspaceLines)

        val grants = WorkspaceManager.listPersistedGrants(this)
        val grantLines = if (grants.isEmpty()) {
            listOf("No persisted SAF grants")
        } else {
            grants.map {
                val rw = buildString {
                    append(if (it.read) "R" else "-")
                    append(if (it.write) "W" else "-")
                }
                "$rw • ${it.uri}"
            }
        }
        grantsList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, grantLines)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
