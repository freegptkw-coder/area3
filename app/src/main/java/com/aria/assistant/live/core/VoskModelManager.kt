package com.aria.assistant.live.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * v3.1: Manages Vosk model downloads and extraction.
 * 
 * Models are downloaded from alphacephei.com, extracted to the app's
 * internal storage filesDir/vosk_models/<langCode>/.
 * 
 * Usage:
 * 1. Call downloadAndExtractModel(context, "bn") { progress -> ... }
 * 2. Progress callback receives 0-100 for download, 100+ for extraction
 * 3. After completion, OfflineSttGateway("bn") can be used
 */
object VoskModelManager {
    private const val TAG = "VoskModelMgr"
    private const val BASE_URL = "https://alphacephei.com/vosk/models"

    // Model zip filenames (downloaded from BASE_URL)
    val MODELS = mapOf(
        "bn" to "vosk-model-bn-0.4.zip",
        "en" to "vosk-model-small-en-us-0.15.zip",
        "hi" to "vosk-model-hi-0.4.zip",
        "es" to "vosk-model-small-es-0.42.zip",
        "fr" to "vosk-model-small-fr-0.22.zip",
        "ar" to "vosk-model-ar-mgb2-0.4.zip",
        "zh" to "vosk-model-small-cn-0.22.zip"
    )

    // Model sizes in MB (approximate, for progress UI)
    val MODEL_SIZES_MB = mapOf(
        "bn" to 49,
        "en" to 43,
        "hi" to 45,
        "es" to 36,
        "fr" to 38,
        "ar" to 52,
        "zh" to 41
    )

    val MODEL_LABELS = mapOf(
        "bn" to "Bangla 🇧🇩 (~49MB)",
        "en" to "English 🇺🇸 (~43MB)",
        "hi" to "Hindi 🇮🇳 (~45MB)",
        "es" to "Spanish 🇪🇸 (~36MB)",
        "fr" to "French 🇫🇷 (~38MB)",
        "ar" to "Arabic 🇸🇦 (~52MB)",
        "zh" to "Chinese 🇨🇳 (~41MB)"
    )

    /**
     * Check if a model is already downloaded and ready.
     */
    fun isModelAvailable(context: Context, langCode: String): Boolean {
        val modelDir = File(context.filesDir, "${OfflineSttGateway.MODEL_DIR}/$langCode")
        return modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true
    }

    /**
     * Get all currently downloaded model language codes.
     */
    fun getDownloadedModels(context: Context): List<String> {
        val baseDir = File(context.filesDir, OfflineSttGateway.MODEL_DIR)
        if (!baseDir.exists()) return emptyList()
        return baseDir.listFiles()?.filter { it.isDirectory && it.listFiles()?.isNotEmpty() == true }
            ?.map { it.name } ?: emptyList()
    }

    /**
     * Get total size of all downloaded models.
     */
    fun getTotalModelSize(context: Context): Long {
        val baseDir = File(context.filesDir, OfflineSttGateway.MODEL_DIR)
        if (!baseDir.exists()) return 0
        return baseDir.walk().sumOf { if (it.isFile) it.length() else 0L }
    }

    /**
     * Download and extract a Vosk model.
     * onProgress: 0-99 = download%, 100-200 = extraction%, 200 = done
     * Returns true on success.
     */
    suspend fun downloadAndExtractModel(
        context: Context,
        langCode: String,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val zipName = MODELS[langCode] ?: return@withContext false
        val url = "$BASE_URL/$zipName"
        val modelDir = File(context.filesDir, "${OfflineSttGateway.MODEL_DIR}/$langCode")

        // Clean up any previous failed extraction
        if (modelDir.exists()) modelDir.deleteRecursively()

        val zipFile = File(context.cacheDir, "vosk_${langCode}_model.zip")

        try {
            // Download
            Log.i(TAG, "Downloading $url")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 60000
            connection.readTimeout = 120000
            connection.connect()

            val totalSize = connection.contentLength.toLong()
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val progress = (downloadedBytes * 99 / totalSize).toInt()
                        onProgress(progress)
                    }
                }
            }

            onProgress(100)
            Log.i(TAG, "Download complete, extracting to ${modelDir.absolutePath}")

            // Extract
            extractZip(zipFile, modelDir) { extractProgress ->
                onProgress(100 + (extractProgress / 2)) // 100-199 for extraction
            }

            onProgress(200)
            zipFile.delete() // Clean up zip file
            Log.i(TAG, "Model extracted successfully: $langCode")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/extract $langCode: ${e.message}")
            zipFile.delete()
            modelDir.deleteRecursively()
            false
        }
    }

    /**
     * Extract a zip file to a directory.
     */
    private fun extractZip(zipFile: File, destDir: File, onProgress: (Int) -> Unit) {
        if (!destDir.exists()) destDir.mkdirs()

        ZipInputStream(BufferedInputStream(java.io.FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            var totalEntries = 0
            var processedEntries = 0

            // Count entries first (rough estimate)
            ZipInputStream(BufferedInputStream(java.io.FileInputStream(zipFile))).use { counter ->
                while (counter.nextEntry != null) {
                    totalEntries++
                }
            }

            while (zis.nextEntry.also { entry = it } != null) {
                val file = File(destDir, entry!!.name)

                // Security check - prevent zip slip
                if (!file.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw SecurityException("Zip entry outside destination: ${entry!!.name}")
                }

                if (entry!!.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { fos ->
                        val buffer = ByteArray(4096)
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }

                processedEntries++
                if (totalEntries > 0) {
                    onProgress((processedEntries * 100 / totalEntries).toInt())
                }
            }
        }
    }

    /**
     * Delete a downloaded model to free space.
     */
    fun deleteModel(context: Context, langCode: String): Boolean {
        val modelDir = File(context.filesDir, "${OfflineSttGateway.MODEL_DIR}/$langCode")
        return if (modelDir.exists()) {
            modelDir.deleteRecursively()
        } else true
    }

    /**
     * Delete all downloaded models.
     */
    fun deleteAllModels(context: Context): Boolean {
        val baseDir = File(context.filesDir, OfflineSttGateway.MODEL_DIR)
        return if (baseDir.exists()) {
            baseDir.deleteRecursively()
        } else true
    }
}
