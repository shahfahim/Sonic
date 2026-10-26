package com.sonic.share.feature_transfer.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Android Scoped Storage manager for AirDrop-style file reception and commitment.
 *
 * Responsibilities:
 * - Allocates isolated `.sonic.part` temporary files in the app cache directory.
 * - Commits fully assembled and verified files to Android Scoped Storage (`MediaStore.Downloads`)
 *   on Android 10+ (API 29+) with `IS_PENDING` isolation, avoiding broad external storage permissions.
 * - Provides graceful fallback for API < 29 and unit test environments.
 */
class StorageManager(
    val context: Context
) {
    companion object {
        const val SUBDIRECTORY_NAME = "Sonic"
        const val TEMP_DIR_NAME = "sonic_transfers"
        const val PART_EXTENSION = ".sonic.part"
        const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }

    /**
     * Creates or returns a temporary file with `.sonic.part` extension for receiving incoming chunks.
     *
     * @param fileId Unique identifier or filename for the payload
     * @return [File] in the application cache directory
     */
    fun createTempPartFile(fileId: String): File {
        val safeId = fileId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (safeId.endsWith(PART_EXTENSION)) safeId else "$safeId$PART_EXTENSION"
        val cacheDir = try {
            context.cacheDir
        } catch (_: Exception) {
            null
        } ?: File(System.getProperty("java.io.tmpdir", "."), "sonic_cache")

        val tempDir = File(cacheDir, TEMP_DIR_NAME)
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        return File(tempDir, fileName)
    }

    /**
     * Commits a completed and verified temporary file to Android Scoped Storage (`MediaStore.Downloads`).
     *
     * On API 29+ (Android 10+):
     * 1. Inserts into MediaStore with `IS_PENDING = 1` and `RELATIVE_PATH = Download/Sonic`
     * 2. Streams bytes from [tempFile] into the output stream
     * 3. Updates `IS_PENDING = 0` to publish the file to other apps
     * 4. Deletes [tempFile]
     *
     * Fallback for API < 29 or unit tests:
     * Copies directly to app external files / download directory, deletes [tempFile], and returns a file URI.
     *
     * @param tempFile Assembled temporary part file
     * @param displayName The public filename shown to the user (e.g. "report.pdf")
     * @param mimeType Target MIME type (defaults to application/octet-stream)
     * @return Content [Uri] or file [Uri] pointing to the saved file, or null on failure
     */
    fun commitToMediaStore(
        tempFile: File,
        displayName: String,
        mimeType: String = DEFAULT_MIME_TYPE
    ): Uri? {
        if (!tempFile.exists()) return null

        // 1. Attempt Scoped Storage MediaStore commit (Android 10+ / API 29+)
        val mediaStoreUri = tryCommitToMediaStoreApi29(tempFile, displayName, mimeType)
        if (mediaStoreUri != null) {
            tempFile.delete()
            return mediaStoreUri
        }

        // 2. Fallback for legacy Android or unit tests without active MediaStore
        return tryFallbackCommit(tempFile, displayName)
    }

    /**
     * Cleans up all orphaned `.sonic.part` files in the temporary directory.
     */
    fun cleanupTempFiles(): Int {
        var count = 0
        try {
            val cacheDir = context.cacheDir ?: return 0
            val tempDir = File(cacheDir, TEMP_DIR_NAME)
            if (tempDir.exists() && tempDir.isDirectory) {
                tempDir.listFiles { file -> file.name.endsWith(PART_EXTENSION) }?.forEach { part ->
                    if (part.delete()) count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    private fun tryCommitToMediaStoreApi29(
        tempFile: File,
        displayName: String,
        mimeType: String
    ): Uri? {
        return try {
            val resolver = context.contentResolver ?: return null
            val downloadDir = try {
                Environment.DIRECTORY_DOWNLOADS ?: "Download"
            } catch (_: Exception) {
                "Download"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$downloadDir/$SUBDIRECTORY_NAME")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Files.getContentUri("external")
            }

            val uri = resolver.insert(collection, values) ?: return null

            val copied = resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
                outStream.flush()
                true
            } ?: false

            if (!copied) {
                resolver.delete(uri, null, null)
                return null
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            uri
        } catch (_: Exception) {
            null
        }
    }

    private fun tryFallbackCommit(tempFile: File, displayName: String): Uri? {
        return try {
            val downloadDir = try {
                Environment.DIRECTORY_DOWNLOADS ?: "Download"
            } catch (_: Exception) {
                "Download"
            }
            val baseDir = try {
                context.getExternalFilesDir(downloadDir)
                    ?: context.filesDir
                    ?: tempFile.parentFile
            } catch (_: Exception) {
                tempFile.parentFile
            } ?: return null

            val targetDir = File(baseDir, SUBDIRECTORY_NAME)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            var destFile = File(targetDir, displayName)
            if (destFile.exists()) {
                val dotIndex = displayName.lastIndexOf('.')
                val name = if (dotIndex > 0) displayName.substring(0, dotIndex) else displayName
                val ext = if (dotIndex > 0) displayName.substring(dotIndex) else ""
                destFile = File(targetDir, "${name}_${System.currentTimeMillis()}$ext")
            }

            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()

            try {
                Uri.fromFile(destFile)
            } catch (_: Exception) {
                Uri.parse("file://${destFile.absolutePath}")
            }
        } catch (_: Exception) {
            null
        }
    }
}
