package com.aria.assistant.live.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

/**
 * Update #2: Smart Battery Optimizer - monitors battery level, DND, power save mode.
 * Auto-pauses ARIA on low battery, resumes on charge.
 */
class SmartBatteryOptimizer(
    private val context: Context,
    private val onLowBattery: (level: Int) -> Unit,
    private val onCharging: () -> Unit,
    private val onPowerSaveChange: (isActive: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "BatteryOptimizer"
        private const val LOW_BATTERY_THRESHOLD = 15
        private const val RESUME_THRESHOLD = 25
    }

    private var isPaused = false
    private var batteryReceiver: BroadcastReceiver? = null

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> handleBatteryChange(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                        onPowerSaveChange(pm.isPowerSaveMode)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        try {
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    private fun handleBatteryChange(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        if (pct < 0) return

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging

        Log.d(TAG, "Battery: $pct%, Charging: $isCharging, PowerSave: ${pm.isPowerSaveMode}")

        if (pct <= LOW_BATTERY_THRESHOLD && !isCharging) {
            Log.w(TAG, "Low battery ($pct%) - pausing ARIA")
            isPaused = true
            onLowBattery(pct)
        } else if (isPaused && (pct >= RESUME_THRESHOLD || isCharging)) {
            Log.i(TAG, "Battery recovered ($pct%) or charging - resuming ARIA")
            isPaused = false
            if (isCharging) onCharging()
        }
    }

    fun isPausedNow(): Boolean = isPaused

    fun shouldReduceWakeLocks(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode || isPaused
    }

    fun cleanup() {
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        batteryReceiver = null
    }
}
