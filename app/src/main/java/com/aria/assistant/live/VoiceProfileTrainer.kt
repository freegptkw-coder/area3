package com.aria.assistant.live

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Update #8: Voice Profile Trainer - 5 sample enrollment for STT accuracy.
 * Collects voice samples to personalize speech recognition.
 */
class VoiceProfileTrainer(
    private val context: Context
) {
    companion object {
        private const val TAG = "VoiceProfile"
        private const val REQUIRED_SAMPLES = 5
        private const val SAMPLES_DIR = "voice_samples"
    }

    data class TrainingState(
        val samplesCollected: Int,
        val isComplete: Boolean,
        val profileId: String
    )

    private var currentProfileId: String? = null
    private var samplesCollected = 0

    fun startEnrollment(profileId: String): TrainingState {
        currentProfileId = profileId
        samplesCollected = 0
        val profileDir = getProfileDir(profileId)
        profileDir.deleteRecursively()
        profileDir.mkdirs()
        Log.i(TAG, "Voice enrollment started for: $profileId")
        return getState()
    }

    fun addVoiceSample(sampleNumber: Int, audioData: ByteArray): TrainingState {
        val profileId = currentProfileId ?: run {
            Log.w(TAG, "No active enrollment session")
            return getState()
        }

        val profileDir = getProfileDir(profileId)
        val sampleFile = File(profileDir, "sample_$sampleNumber.pcm")
        sampleFile.writeBytes(audioData)

        samplesCollected++
        Log.d(TAG, "Voice sample # $samplesCollected saved")

        return getState()
    }

    fun completeEnrollment(): TrainingState {
        val state = getState()
        Log.i(TAG, "Voice enrollment complete: ${state.profileId} - ${state.samplesCollected} samples")
        currentProfileId = null
        return state
    }

    private fun getState(): TrainingState {
        return TrainingState(
            samplesCollected = samplesCollected,
            isComplete = samplesCollected >= REQUIRED_SAMPLES,
            profileId = currentProfileId ?: "none"
        )
    }

    private fun getProfileDir(profileId: String): File {
        val dir = File(context.filesDir, "$SAMPLES_DIR/$profileId")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun hasProfile(profileId: String): Boolean {
        return getProfileDir(profileId).exists() && getProfileDir(profileId).listFiles()?.isNotEmpty() == true
    }

    fun deleteProfile(profileId: String) {
        getProfileDir(profileId).deleteRecursively()
        Log.i(TAG, "Voice profile deleted: $profileId")
    }
}
