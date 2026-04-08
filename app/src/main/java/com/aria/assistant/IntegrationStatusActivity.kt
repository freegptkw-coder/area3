package com.aria.assistant

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.integrations.IntegrationCapabilityManager
import com.google.android.material.button.MaterialButton

class IntegrationStatusActivity : AppCompatActivity() {

    private lateinit var listView: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_integration_status)

        supportActionBar?.title = "Integration Capability Center"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listView = findViewById(R.id.integrationList)

        findViewById<MaterialButton>(R.id.integrationRefreshButton).setOnClickListener {
            renderCapabilities()
        }

        findViewById<MaterialButton>(R.id.integrationOpenSettingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.integrationNotificationButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        renderCapabilities()
    }

    private fun renderCapabilities() {
        val rows = IntegrationCapabilityManager.collect(this).map {
            val status = if (it.available) "✅" else "⚠️"
            "$status ${it.title}\n${it.details}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
