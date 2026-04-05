package com.aria.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aria.assistant.live.LocationReminderManager
import com.aria.assistant.live.MusicController
import com.aria.assistant.live.WakeWordDetector
import com.aria.assistant.live.core.ConversationContextTracker
import com.aria.assistant.live.core.SmartBatteryOptimizer

/**
 * Central hub for all v3.0 feature integrations.
 * Called from MainActivity, SettingsActivity, and AssistantActivity.
 */
object V3FeatureManager {

    // === Prefs Keys ===
    private const val KEY_BATTERY_OPTIMIZER = "v3_battery_optimizer"
    private const val KEY_LOCATION_REMINDERS = "v3_location_reminders"
    private const val KEY_CALL_SCREENING = "v3_call_screening"
    private const val KEY_MUSIC_CONTROL = "v3_music_control"
    private const val KEY_VOICE_PROFILE = "v3_voice_profile_enabled"
    private const val KEY_CONTEXT_TRACKING = "v3_context_tracking"
    private const val KEY_OFFLINE_STT = "v3_offline_stt"
    private const val KEY_BATTERY_OPT_THRESHOLD = "v3_battery_opt_threshold"
    private const val KEY_STT_LANGUAGE = "v3_stt_language"
    private const val KEY_WAKE_WORD = "wake_word_enabled"

    // === Runtime Permissions ===
    private val V3_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    /**
     * Initialize all v3.0 features based on saved preferences.
     * Should be called from Application.onCreate or MainActivity.onCreate.
     */
    fun initializeAll(context: Context, prefs: SharedPreferences) {
        initializeContextTracker()
    }

    /**
     * Initialize Conversation Context Tracker as a singleton.
     */
    private fun initializeContextTracker() {
        // ContextTracker is just a regular class - it gets instantiated where needed
        // No singleton init needed here; tracked via prefs
    }

    // ============================================================
    // PUBLIC: Settings Activity Helpers
    // ============================================================

    /**
     * Save v3.0 feature toggles to SharedPreferences.
     */
    fun saveFeatureToggles(
        prefs: SharedPreferences,
        batteryOptimizer: Boolean,
        batteryThreshold: Int,
        locationReminders: Boolean,
        callScreening: Boolean,
        musicControl: Boolean,
        voiceProfile: Boolean,
        contextTracking: Boolean,
        offlineStt: Boolean,
        sttLanguage: String
    ) {
        prefs.edit().apply {
            putBoolean(KEY_BATTERY_OPTIMIZER, batteryOptimizer)
            putInt(KEY_BATTERY_OPT_THRESHOLD, batteryThreshold)
            putBoolean(KEY_LOCATION_REMINDERS, locationReminders)
            putBoolean(KEY_CALL_SCREENING, callScreening)
            putBoolean(KEY_MUSIC_CONTROL, musicControl)
            putBoolean(KEY_VOICE_PROFILE, voiceProfile)
            putBoolean(KEY_CONTEXT_TRACKING, contextTracking)
            putBoolean(KEY_OFFLINE_STT, offlineStt)
            putString(KEY_STT_LANGUAGE, sttLanguage)
            apply()
        }
    }

    /**
     * Load v3.0 feature toggles from SharedPreferences.
     */
    fun loadFeatureToggles(prefs: SharedPreferences): Map<String, Any> {
        return mapOf(
            KEY_BATTERY_OPTIMIZER to prefs.getBoolean(KEY_BATTERY_OPTIMIZER, true),
            KEY_BATTERY_OPT_THRESHOLD to prefs.getInt(KEY_BATTERY_OPT_THRESHOLD, 20),
            KEY_LOCATION_REMINDERS to prefs.getBoolean(KEY_LOCATION_REMINDERS, false),
            KEY_CALL_SCREENING to prefs.getBoolean(KEY_CALL_SCREENING, false),
            KEY_MUSIC_CONTROL to prefs.getBoolean(KEY_MUSIC_CONTROL, false),
            KEY_VOICE_PROFILE to prefs.getBoolean(KEY_VOICE_PROFILE, false),
            KEY_CONTEXT_TRACKING to prefs.getBoolean(KEY_CONTEXT_TRACKING, true),
            KEY_OFFLINE_STT to prefs.getBoolean(KEY_OFFLINE_STT, false),
            KEY_STT_LANGUAGE to (prefs.getString(KEY_STT_LANGUAGE, "en") ?: "en"),
            KEY_WAKE_WORD to prefs.getBoolean(KEY_WAKE_WORD, false)
        )
    }

    /**
     * Request v3.0 runtime permissions. Returns list of granted permissions.
     */
    fun requestV3Permissions(activity: Activity): List<String> {
        val permissionsToRequest = V3_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Toast.makeText(activity, "✅ All v3.0 permissions already granted", Toast.LENGTH_SHORT).show()
            return V3_PERMISSIONS.toList()
        }

        ActivityCompat.requestPermissions(
            activity,
            permissionsToRequest.toTypedArray(),
            1001
        )
        return emptyList()
    }

    // ============================================================
    // PUBLIC: Feature Factory Methods (called from AssistantActivity)
    // ============================================================

    /**
     * Create SmartBatteryOptimizer - registers battery receiver.
     */
    fun createBatteryOptimizer(
        context: Context,
        onLowBattery: (Int) -> Unit,
        onCharging: () -> Unit,
        onPowerSaveChange: (Boolean) -> Unit
    ): SmartBatteryOptimizer {
        return SmartBatteryOptimizer(context, onLowBattery, onCharging, onPowerSaveChange)
    }

    /**
     * Create ConversationContextTracker.
     */
    fun createContextTracker(maxTopics: Int = 3): ConversationContextTracker {
        return ConversationContextTracker(maxTopics)
    }

    /**
     * Create WakeWordDetector if enabled.
     */
    fun createWakeWordDetector(
        context: Context,
        prefs: SharedPreferences,
        onWakeWord: () -> Unit
    ): WakeWordDetector? {
        val enabled = prefs.getBoolean(KEY_WAKE_WORD, false)
        if (!enabled) return null

        return try {
            WakeWordDetector(context, onWakeWord)
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Create LocationReminderManager.
     */
    fun createLocationReminderManager(context: Context): LocationReminderManager {
        return LocationReminderManager(context)
    }

    /**
     * Create MusicController.
     */
    fun createMusicController(context: Context): MusicController {
        return MusicController(context)
    }

    /**
     * Create VoiceProfileTrainer.
     */
    fun createVoiceProfileTrainer(context: Context): com.aria.assistant.live.VoiceProfileTrainer {
        return com.aria.assistant.live.VoiceProfileTrainer(context)
    }

    /**
     * Create DataPrivacyManager.
     */
    fun createDataPrivacyManager(prefs: SharedPreferences): com.aria.assistant.live.core.DataPrivacyManager {
        return com.aria.assistant.live.core.DataPrivacyManager(prefs)
    }

    /**
     * Check if battery is too low for heavy operations.
     */
    fun shouldPauseHeavyFeatures(context: Context, prefs: SharedPreferences): Boolean {
        val enabled = prefs.getBoolean(KEY_BATTERY_OPTIMIZER, true)
        if (!enabled) return false

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else 100

        return level < 20 && !bm.isCharging
    }

    /**
     * Get current battery level percentage.
     */
    fun getBatteryLevel(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } else {
            100
        }
    }

    /**
     * Handle "stop listening due to low battery" logic.
     */
    fun handleLowBattery(context: Context, prefs: SharedPreferences, stopListening: () -> Unit) {
        if (shouldPauseHeavyFeatures(context, prefs)) {
            stopListening()
        }
    }

    /**
     * Resume features after battery is recharged.
     */
    fun handleBatteryRecharged(context: Context, prefs: SharedPreferences, restartListening: () -> Unit) {
        val wasPaused = prefs.getBoolean("battery_optimizer_paused", false)
        if (wasPaused) {
            prefs.edit().putBoolean("battery_optimizer_paused", false).apply()
            Toast.makeText(context, "🔋 Battery ok - resuming features", Toast.LENGTH_SHORT).show()
            restartListening()
        }
    }
}
