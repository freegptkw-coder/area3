package com.aria.assistant.live

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Update #12: Location Reminder Manager - Geofencing-based reminders.
 * User says "Remind me to buy milk when I reach the store" - 
 * ARIA sets up geofence and triggers notification.
 */
class LocationReminderManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "LocationReminder"
        private const val GEOFENCE_RADIUS = 100f // meters
        private const val GEOFENCE_EXPIRATION_MS = 6 * 60 * 60 * 1000L // 6 hours
    }

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    data class LocationReminder(
        val id: String,
        val message: String,
        val latitude: Double,
        val longitude: Double
    )

    /** Add a geofence-based reminder */
    fun addReminder(reminder: LocationReminder, pendingIntent: PendingIntent): Boolean {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No ACCESS_FINE_LOCATION permission")
            return false
        }

        val geofence = Geofence.Builder()
            .setRequestId(reminder.id)
            .setCircularRegion(reminder.latitude, reminder.longitude, GEOFENCE_RADIUS)
            .setExpirationDuration(GEOFENCE_EXPIRATION_MS)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient.addGeofences(request, pendingIntent)?.addOnSuccessListener {
            Log.i(TAG, "Geofence added: ${reminder.message}")
        }?.addOnFailureListener { e ->
            Log.e(TAG, "Failed to add geofence: ${e.message}")
        }

        return true
    }

    /** Remove a specific reminder */
    fun removeReminder(reminderId: String) {
        geofencingClient.removeGeofences(listOf(reminderId))?.addOnSuccessListener {
            Log.i(TAG, "Geofence removed: $reminderId")
        }
    }

    /** Remove all reminders */
    fun removeAllReminders() {
        geofencingClient.removeGeofences(createGeofencePendingIntent())?.addOnSuccessListener {
            Log.i(TAG, "All geofences removed")
        }
    }

    private fun createGeofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// Broadcast receiver for geofence transitions
class GeofenceBroadcastReceiver : android.content.BroadcastReceiver() {
    companion object {
        private const val TAG = "GeofenceReceiver"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Log.i(TAG, "Geofence transition received")
        // Handle geofence enter event - show reminder notification
    }
}
