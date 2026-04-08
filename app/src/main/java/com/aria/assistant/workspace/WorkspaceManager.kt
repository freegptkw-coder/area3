package com.aria.assistant.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorkspaceImportResult(
    val success: Boolean,
    val message: String,
    val record: WorkspaceRecord? = null
)

data class PersistedGrantInfo(
    val uri: String,
    val read: Boolean,
    val write: Boolean,
    val persistedAt: Long
)

object WorkspaceManager {
    private const val WORKSPACE_ROOT = "workspaces"
    private const val EXPORT_ROOT = "workspace_exports"
    private const val MAX_IMPORT_FILE_COUNT = 8000
    private const val MAX_IMPORT_BYTES = 300L * 1024L * 1024L

    private data class CopyStats(
        var files: Int = 0,
        var bytes: Long = 0
    )

    fun workspaceRoot(context: Context): File {
        return File(context.filesDir, WORKSPACE_ROOT).apply { mkdirs() }
    }

    fun importFromTreeUri(
        context: Context,
        treeUri: Uri,
        displayName: String?,
        mode: WorkspacePermissionMode
    ): WorkspaceImportResult {
        val resolver = context.contentResolver
        takePersistablePermissions(resolver, treeUri)

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: return WorkspaceImportResult(false, "Unable to open selected folder")

        val safeName = sanitizeWorkspaceName(displayName ?: rootDoc.name ?: "workspace")
        val targetDir = File(workspaceRoot(context), "${System.currentTimeMillis()}_$safeName").apply { mkdirs() }
        val stats = CopyStats()

        return runCatching {
            copyDocumentTreeRecursively(resolver, rootDoc, targetDir, stats)
            val now = System.currentTimeMillis()
            val record = WorkspaceRecord(
                id = WorkspaceRegistry.createWorkspaceId(),
                name = safeName,
                treeUri = treeUri.toString(),
                localPath = targetDir.absolutePath,
                permissionMode = mode,
                createdAt = now,
                lastOpenedAt = now
            )
            WorkspaceRegistry.upsert(context, record)
            WorkspaceImportResult(
                success = true,
                message = "Imported ${stats.files} files (${formatBytes(stats.bytes)})",
                record = record
            )
        }.getOrElse {
            targetDir.deleteRecursively()
            WorkspaceImportResult(false, "Import failed: ${it.message ?: "unknown"}")
        }
    }

    fun importFromZipUri(
        context: Context,
        zipUri: Uri,
        displayName: String?,
        mode: WorkspacePermissionMode
    ): WorkspaceImportResult {
        val resolver = context.contentResolver
        val safeName = sanitizeWorkspaceName(displayName ?: "zip_workspace")
        val targetDir = File(workspaceRoot(context), "${System.currentTimeMillis()}_$safeName").apply { mkdirs() }
        val stats = CopyStats()

        return runCatching {
            resolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry: ZipEntry?
                    val rootCanonical = targetDir.canonicalPath
                    while (zis.nextEntry.also { entry = it } != null) {
                        val zipEntry = entry ?: continue
                        val outFile = File(targetDir, zipEntry.name)
                        val outCanonical = outFile.canonicalPath
                        if (!outCanonical.startsWith(rootCanonical)) {
                            throw SecurityException("Invalid zip path: ${zipEntry.name}")
                        }

                        if (zipEntry.isDirectory) {
                            outFile.mkdirs()
                            continue
                        }

                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                fos.write(buffer, 0, len)
                                stats.bytes += len
                                enforceImportLimit(stats)
                            }
                        }
                        stats.files += 1
                        enforceImportLimit(stats)
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected zip")

            val now = System.currentTimeMillis()
            val record = WorkspaceRecord(
                id = WorkspaceRegistry.createWorkspaceId(),
                name = safeName,
                treeUri = null,
                localPath = targetDir.absolutePath,
                permissionMode = mode,
                createdAt = now,
                lastOpenedAt = now
            )
            WorkspaceRegistry.upsert(context, record)
            WorkspaceImportResult(
                success = true,
                message = "Zip imported ${stats.files} files (${formatBytes(stats.bytes)})",
                record = record
            )
        }.getOrElse {
            targetDir.deleteRecursively()
            WorkspaceImportResult(false, "Zip import failed: ${it.message ?: "unknown"}")
        }
    }

    fun exportWorkspaceZip(context: Context, record: WorkspaceRecord): File? {
        val source = File(record.localPath)
        if (!source.exists() || !source.isDirectory) return null
        val exportRoot = File(context.cacheDir, EXPORT_ROOT).apply { mkdirs() }
        val outFile = File(exportRoot, "${sanitizeWorkspaceName(record.name)}_${System.currentTimeMillis()}.zip")

        return runCatching {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                source.walkTopDown().forEach { file ->
                    if (file == source) return@forEach
                    val relative = source.toPath().relativize(file.toPath()).toString()
                    val entryName = if (file.isDirectory) "$relative/" else relative
                    val entry = ZipEntry(entryName)
                    zos.putNextEntry(entry)
                    if (file.isFile) {
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (fis.read(buffer).also { len = it } > 0) {
                                zos.write(buffer, 0, len)
                            }
                        }
                    }
                    zos.closeEntry()
                }
            }
            outFile
        }.getOrNull()
    }

    fun listPersistedGrants(context: Context): List<PersistedGrantInfo> {
        return context.contentResolver.persistedUriPermissions
            .map {
                PersistedGrantInfo(
                    uri = it.uri.toString(),
                    read = it.isReadPermission,
                    write = it.isWritePermission,
                    persistedAt = it.persistedTime
                )
            }
            .sortedByDescending { it.persistedAt }
    }

    fun revokePersistedGrant(context: Context, uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        val flags = IntentFlags.readWrite
        return runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
    }

    private fun copyDocumentTreeRecursively(
        resolver: ContentResolver,
        source: DocumentFile,
        destination: File,
        stats: CopyStats
    ) {
        source.listFiles().forEach { child ->
            val name = child.name ?: "node_${System.nanoTime()}"
            val out = File(destination, name)
            if (child.isDirectory) {
                out.mkdirs()
                copyDocumentTreeRecursively(resolver, child, out, stats)
            } else if (child.isFile) {
                out.parentFile?.mkdirs()
                resolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (input.read(buffer).also { len = it } > 0) {
                            output.write(buffer, 0, len)
                            stats.bytes += len
                            enforceImportLimit(stats)
                        }
                    }
                }
                stats.files += 1
                enforceImportLimit(stats)
            }
        }
    }

    private fun takePersistablePermissions(resolver: ContentResolver, treeUri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(treeUri, IntentFlags.readWrite)
        }
    }

    private fun enforceImportLimit(stats: CopyStats) {
        if (stats.files > MAX_IMPORT_FILE_COUNT) {
            throw IllegalStateException("Too many files (>${MAX_IMPORT_FILE_COUNT})")
        }
        if (stats.bytes > MAX_IMPORT_BYTES) {
            throw IllegalStateException("Workspace import exceeded ${formatBytes(MAX_IMPORT_BYTES)}")
        }
    }

    private fun sanitizeWorkspaceName(raw: String): String {
        return raw.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_')
            .ifBlank { "workspace" }
            .take(50)
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private object IntentFlags {
        const val readWrite =
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
