package com.aria.assistant

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.integrations.IntegrationCapabilityManager
import com.aria.assistant.setup.SetupChecks
import com.aria.assistant.workspace.WorkspaceRegistry
import com.google.android.material.button.MaterialButton

class OnboardingDashboardActivity : AppCompatActivity() {

    private lateinit var summaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding_dashboard)

        supportActionBar?.title = "Onboarding Dashboard"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        summaryText = findViewById(R.id.onboardingSummaryText)

        findViewById<MaterialButton>(R.id.openSetupWizardButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.openWorkspaceManagerButton).setOnClickListener {
            startActivity(Intent(this, WorkspaceManagerActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.openCapabilityCenterButton).setOnClickListener {
            startActivity(Intent(this, IntegrationStatusActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.openDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, EnvironmentDiagnosticsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        renderSummary()
    }

    private fun renderSummary() {
        val setup = SetupChecks.evaluate(this)
        val capabilities = IntegrationCapabilityManager.collect(this)
        val available = capabilities.count { it.available }
        val total = capabilities.size
        val workspaceCount = WorkspaceRegistry.list(this).size

        summaryText.text = buildString {
            appendLine("Setup completion")
            appendLine("- Permissions: ${if (setup.permissionsDone) "✅" else "⚠️"} (${setup.grantedPermissions}/${setup.totalPermissions})")
            appendLine("- Overlay: ${if (setup.overlayDone) "✅" else "⚠️"}")
            appendLine("- Default assistant: ${if (setup.assistantDone) "✅" else "⚠️"}")
            appendLine()
            appendLine("Integrations")
            appendLine("- Available: $available / $total")
            appendLine()
            appendLine("Workspace")
            appendLine("- Registered projects: $workspaceCount")
            appendLine("- Default mode: ${WorkspaceRegistry.getDefaultMode(this@OnboardingDashboardActivity).label}")
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
