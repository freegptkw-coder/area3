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
 * 1. Call downloadAndExtractModel(context, "en") { progress -> ... }
 * 2. Progress callback receives 0-100 for download, 100+ for extraction
 * 3. After completion, OfflineSttGateway("en") can be used
 */
object VoskModelManager {
    private const val TAG = "VoskModelMgr"
    private const val BASE_URL = "https://alphacephei.com/vosk/models"

    // Model zip filenames (downloaded from BASE_URL)
    // NOTE: bn, hi, ar models removed from alphacephei server (404 as of 2026-04)
    val MODELS = mapOf(
        "en" to "vosk-model-small-en-us-0.15.zip",
        "es" to "vosk-model-small-es-0.42.zip",
        "fr" to "vosk-model-small-fr-0.22.zip",
        "zh" to "vosk-model-small-cn-0.22.zip"
    )

    // Model sizes in MB (approximate, for progress UI)
    val MODEL_SIZES_MB = mapOf(
        "en" to 43,
        "es" to 36,
        "fr" to 38,
        "zh" to 41
    )

    val MODEL_LABELS = mapOf(
        "en" to "English 🇺🇸 (~43MB)",
        "es" to "Spanish 🇪🇸 (~36MB)",
        "fr" to "French 🇫🇷 (~38MB)",
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
     * Returns a Result with success status and error message if failed.
     */
    data class DownloadResult(
        val success: Boolean,
        val errorMessage: String? = null
    )

    suspend fun downloadAndExtractModel(
        context: Context,
        langCode: String,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val zipName = MODELS[langCode] ?: return@withContext DownloadResult(false, "Unknown language: $langCode")
        val url = "$BASE_URL/$zipName"
        val modelDir = File(context.filesDir, "${OfflineSttGateway.MODEL_DIR}/$langCode")

        // Clean up any previous failed extraction
        if (modelDir.exists()) modelDir.deleteRecursively()

        val zipFile = File(context.cacheDir, "vosk_${langCode}_model.zip")

        try {
            // Download
            Log.i(TAG, "Downloading $url")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 120000
            connection.readTimeout = 180000
            // Fix Bug 1: Add User-Agent header to avoid 403 from alphacephei.com
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) ARIA-Assistant/3.1")
            connection.setRequestProperty("Accept", "application/zip,*/*")
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val errorMsg = when (responseCode) {
                    403 -> "Server blocked request (403). Try again later or use different network."
                    404 -> "Model file removed from server. Vosk models bn/hi/ar have been discontinued. Try en/es/fr/zh instead."
                    500 -> "Server error. Try again later."
                    else -> "Server returned HTTP $responseCode"
                }
                Log.e(TAG, "Download failed: HTTP $responseCode for $url")
                return@withContext DownloadResult(false, errorMsg)
            }

            val totalSize = connection.contentLength.toLong()
            if (totalSize <= 0) {
                Log.e(TAG, "Download failed: Unknown content length for $url")
                return@withContext DownloadResult(false, "Server did not provide file size info")
            }
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
            DownloadResult(success = true)

        } catch (e: Exception) {
            val errorMsg = "Download/extract failed: ${e.message ?: "Unknown error"}"
            Log.e(TAG, "Failed to download/extract $langCode: ${e.message}", e)
            zipFile.delete()
            modelDir.deleteRecursively()
            DownloadResult(success = false, errorMessage = errorMsg)
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
