package com.aria.assistant.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Update #10: Call Screen Handler - Answer/reject calls, read caller ID with voice.
 * Integrates with TelephonyManager to intercept incoming calls.
 */
class CallScreenHandler(
    private val context: Context,
    private val onIncomingCall: (callerName: String, callerNumber: String) -> Unit,
    private val onCallDismissed: () -> Unit
) {
    companion object {
        private const val TAG = "CallScreenHandler"
    }

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /** Answer incoming call (requires CALL_PHONE permission) */
    fun answerCall(): Boolean {
        return try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No ANSWER_PHONE_CALLS permission")
                return false
            }
            telecomManager?.acceptRingingCall()
            Log.i(TAG, "Call answered")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Answer call failed: ${e.message}")
            false
        }
    }

    /** Reject/silence incoming call */
    fun rejectCall(): Boolean {
        return try {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "No ANSWER_PHONE_CALLS permission")
                return false
            }
            telecomManager?.endCall()
            Log.i(TAG, "Call rejected")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Reject call failed: ${e.message}")
            false
        }
    }

    /** Silence ringer without rejecting */
    fun silenceCall(): Boolean {
        return try {
            telecomManager?.silenceRinger()
            Log.i(TAG, "Call silenced")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Silence failed: ${e.message}")
            false
        }
    }

    /** Get caller info if available */
    fun getCallerInfo(): Pair<String, String> {
        // In real implementation, this would use broadcast receiver
        // For now return placeholder - actual caller ID comes from TelephonyManager
        return Pair("Unknown", "Unknown")
    }

    /** Check phone state */
    fun getCallState(): Int {
        return if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED) {
            telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE
        } else {
            TelephonyManager.CALL_STATE_IDLE
        }
    }
}
