package com.aria.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aria.assistant.live.core.DataPrivacyManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * v3.0 Data Privacy Activity - allows users to opt-out of various data collection features.
 */
class DataPrivacyActivity : AppCompatActivity() {

    private lateinit var screenContextSwitch: SwitchMaterial
    private lateinit var historySwitch: SwitchMaterial
    private lateinit var locationSendSwitch: SwitchMaterial
    private lateinit var analyticsSwitch: SwitchMaterial
    private lateinit var saveButton: MaterialButton

    private lateinit var privacyManager: DataPrivacyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_privacy)

        supportActionBar?.title = "🔒 Data Privacy"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val prefs = getSharedPreferences("ARIA_PREFS", MODE_PRIVATE)
        privacyManager = DataPrivacyManager(prefs)

        screenContextSwitch = findViewById(R.id.screenContextSwitch)
        historySwitch = findViewById(R.id.historySwitch)
        locationSendSwitch = findViewById(R.id.locationSendSwitch)
        analyticsSwitch = findViewById(R.id.analyticsSwitch)
        saveButton = findViewById(R.id.privacySaveButton)

        // Load current settings
        screenContextSwitch.isChecked = privacyManager.isScreenContextAllowed()
        historySwitch.isChecked = privacyManager.isHistoryAllowed()
        locationSendSwitch.isChecked = privacyManager.isLocationSendAllowed()
        analyticsSwitch.isChecked = privacyManager.isAnalyticsAllowed()

        saveButton.setOnClickListener {
            prefs.edit().apply {
                putBoolean(DataPrivacyManager.PREF_SCREEN_CONTEXT, screenContextSwitch.isChecked)
                putBoolean(DataPrivacyManager.PREF_CONVERSATION_HISTORY, historySwitch.isChecked)
                putBoolean(DataPrivacyManager.PREF_LOCATION_SEND, locationSendSwitch.isChecked)
                putBoolean(DataPrivacyManager.PREF_ANALYTICS, analyticsSwitch.isChecked)
                apply()
            }
            Toast.makeText(this, "✅ Privacy settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
