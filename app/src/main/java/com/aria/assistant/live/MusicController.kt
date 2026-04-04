package com.aria.assistant.live

import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log

/**
 * Update #11: Music Controller - MediaSession API for music control.
 * Play/pause/next/volume via voice commands.
 */
class MusicController(private val context: Context) {
    companion object {
        private const val TAG = "MusicController"
    }

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null

    init {
        mediaSessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    /** Get the currently active media session */
    private fun getActiveSession(): MediaController? {
        return try {
            val sessions = mediaSessionManager?.getActiveSessions(null)
            activeController = sessions?.firstOrNull { it.isActive } ?: sessions?.firstOrNull()
            activeController
        } catch (e: SecurityException) {
            Log.e(TAG, "No notification access permission: ${e.message}")
            null
        }
    }

    fun play(): Boolean {
        return try {
            getActiveSession()?.transportControls?.play()
            Log.i(TAG, "Play")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Play failed: ${e.message}")
            false
        }
    }

    fun pause(): Boolean {
        return try {
            getActiveSession()?.transportControls?.pause()
            Log.i(TAG, "Pause")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pause failed: ${e.message}")
            false
        }
    }

    fun next(): Boolean {
        return try {
            getActiveSession()?.transportControls?.skipToNext()
            Log.i(TAG, "Next")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Next failed: ${e.message}")
            false
        }
    }

    fun previous(): Boolean {
        return try {
            getActiveSession()?.transportControls?.skipToPrevious()
            Log.i(TAG, "Previous")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Previous failed: ${e.message}")
            false
        }
    }

    fun togglePlayback(): Boolean {
        val session = getActiveSession() ?: return false
        return when (session.playbackState?.state) {
            android.media.session.PlaybackState.STATE_PLAYING -> pause()
            else -> play()
        }
    }

    /** Get currently playing track info */
    fun getNowPlaying(): String {
        val session = getActiveSession() ?: return "Nothing playing"
        val md = session.metadata
        val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown"
        val artist = md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "Unknown"
        return "$artist - $title"
    }

    fun getVolume(): Int {
        return getActiveSession()?.volumeMax ?: 0
    }
}
