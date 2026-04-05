package com.aria.assistant.live.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log

/**
 * Update #2: Smart Battery Optimizer - monitors battery level, DND, power save mode.
 * Auto-pauses ARIA on low battery, resumes on charge.
 *
 * FIX: ACTION_BATTERY_CHANGED is a sticky broadcast that cannot be received via
 * dynamically-registered BroadcastReceiver on Android 8+. We use direct polling instead.
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
        private const val CHECK_INTERVAL_MS = 30_000L // Poll every 30 seconds
    }

    private var isPaused = false
    private var lastCheckLevel = -1
    private var checkThread: Thread? = null
    @Volatile private var shouldStop = false

    init {
        startPollingThread()
    }

    private fun startPollingThread() {
        checkThread = Thread({
            checkBattery()
        }, "SmartBatteryOptimizer-Poller").apply {
            isDaemon = true
            start()
        }
    }

    private fun checkBattery() {
        while (!shouldStop) {
            try {
                // Directly query BatteryManager (no need for registerReceiver)
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = batteryManager.isCharging

                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

                // Check power save mode change
                val isPowerSave = pm.isPowerSaveMode

                Log.d(TAG, "Battery: ${level}%, Charging: $isCharging, PowerSave: $isPowerSave")

                if (level < 0) {
                    Thread.sleep(CHECK_INTERVAL_MS)
                    continue
                }

                // Detect power save mode change
                val lastPowerSave = lastCheckLevel != -1 && level == lastCheckLevel
                if (!lastPowerSave) {
                    onPowerSaveChange(isPowerSave)
                }

                if (level <= LOW_BATTERY_THRESHOLD && !isCharging) {
                    Log.w(TAG, "Low battery ($level%) - pausing ARIA")
                    isPaused = true
                    onLowBattery(level)
                } else if (isPaused && (level >= RESUME_THRESHOLD || isCharging)) {
                    Log.i(TAG, "Battery recovered ($level%) or charging - resuming ARIA")
                    isPaused = false
                    if (isCharging) onCharging()
                }

                lastCheckLevel = level
                Thread.sleep(CHECK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "Battery check error: ${e.message}")
                Thread.sleep(CHECK_INTERVAL_MS)
            }
        }
    }

    fun isPausedNow(): Boolean = isPaused

    fun shouldReduceWakeLocks(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode || isPaused
    }

    fun cleanup() {
        shouldStop = true
        checkThread?.interrupt()
        checkThread = null
    }
}
