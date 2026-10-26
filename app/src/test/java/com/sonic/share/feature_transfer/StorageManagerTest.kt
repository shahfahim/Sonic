package com.sonic.share.feature_transfer

import android.content.Context
import android.content.ContextWrapper
import com.sonic.share.feature_transfer.data.StorageManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getCacheDir(): File {
            val cache = File(baseDir, "cache")
            cache.mkdirs()
            return cache
        }

        override fun getFilesDir(): File {
            val files = File(baseDir, "files")
            files.mkdirs()
            return files
        }

        override fun getExternalFilesDir(type: String?): File? {
            val ext = File(baseDir, "external_files/${type ?: "default"}")
            ext.mkdirs()
            return ext
        }

        override fun getContentResolver(): android.content.ContentResolver? = null
    }

    @Test
    fun testCreateTempPartFile() {
        val root = tempFolder.newFolder("storage_test")
        val context = FakeContext(root)
        val manager = StorageManager(context)

        val tempFile = manager.createTempPartFile("photo-123.jpg")
        assertTrue(tempFile.name.endsWith(".sonic.part"))
        assertTrue(tempFile.absolutePath.contains("sonic_transfers"))

        // Create the file
        tempFile.writeText("sample partial content")
        assertTrue(tempFile.exists())

        // Test cleanup
        val deletedCount = manager.cleanupTempFiles()
        assertEquals(1, deletedCount)
        assertFalse(tempFile.exists())
    }

    @Test
    fun testFallbackCommitWhenMediaStoreUnavailable() {
        val root = tempFolder.newFolder("storage_test_fallback")
        val context = FakeContext(root)
        val manager = StorageManager(context)

        val tempFile = manager.createTempPartFile("doc-test")
        tempFile.writeText("Complete document content")
        assertTrue(tempFile.exists())

        manager.commitToMediaStore(tempFile, "final_doc.txt", "text/plain")

        // Temp file should be deleted after successful commit
        assertFalse(tempFile.exists())

        // Download directory should contain the committed file
        val targetDir = File(context.getExternalFilesDir("Download"), "Sonic")
        val destFile = File(targetDir, "final_doc.txt")
        assertTrue("Destination file should exist", destFile.exists())
        assertEquals("Complete document content", destFile.readText())
    }

    @Test
    fun testPathTraversalProtection() {
        val root = tempFolder.newFolder("storage_traversal_test")
        val context = FakeContext(root)
        val manager = StorageManager(context)

        // 1. Verify filename sanitization directly
        val sanitized = manager.sanitizeDisplayName("../../outside_file.txt")
        assertEquals("outside_file.txt", sanitized)

        val tempFile = manager.createTempPartFile("malicious")
        tempFile.writeText("sensitive data")

        manager.commitToMediaStore(tempFile, "../../outside_file.txt")

        // Temp file should be deleted after commit
        assertFalse(tempFile.exists())

        val targetDir = File(context.getExternalFilesDir("Download"), "Sonic")
        // File must be saved strictly inside targetDir with sanitized name
        val destFile = File(targetDir, "outside_file.txt")
        assertTrue("File should be sanitized and placed within Sonic directory", destFile.exists())
        assertEquals("sensitive data", destFile.readText())

        val outsideFile = File(root, "outside_file.txt")
        assertFalse("File must not escape into outer directories", outsideFile.exists())
    }
}
