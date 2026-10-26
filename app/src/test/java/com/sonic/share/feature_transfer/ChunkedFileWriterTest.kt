package com.sonic.share.feature_transfer

import com.sonic.share.feature_transfer.data.ChunkedFileWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ChunkedFileWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testChunkReassemblyAndIntegrityCheck() {
        val destFile = tempFolder.newFile("test_output.sonic.part")
        val writer = ChunkedFileWriter(destFile, expectedSize = 15L)

        writer.writeChunk(offset = 0L, data = "Hello ".toByteArray(Charsets.UTF_8))
        writer.writeChunk(offset = 6L, data = "Sonic ".toByteArray(Charsets.UTF_8))
        writer.writeChunk(offset = 12L, data = "App".toByteArray(Charsets.UTF_8))
        writer.flush()

        assertEquals(15L, destFile.length())
        assertEquals("Hello Sonic App", destFile.readText(Charsets.UTF_8))
    }

    @Test
    fun testOutOfOrderChunkWritingAndSha256Validation() {
        val destFile = tempFolder.newFile("test_out_of_order.sonic.part")
        val content = "The quick brown fox jumps over the lazy dog"
        val bytes = content.toByteArray(Charsets.UTF_8)

        // Compute expected SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedHash = digest.digest(bytes).joinToString("") { "%02x".format(it) }

        val writer = ChunkedFileWriter(destFile, expectedSize = bytes.size.toLong())

        // Write chunk 2 (middle)
        writer.writeChunk(offset = 16L, data = bytes.copyOfRange(16, 31))
        // Write chunk 3 (end)
        writer.writeChunk(offset = 31L, data = bytes.copyOfRange(31, bytes.size))
        // Write chunk 1 (beginning)
        writer.writeChunk(offset = 0L, data = bytes.copyOfRange(0, 16))

        writer.flush()

        assertEquals(bytes.size.toLong(), destFile.length())
        assertEquals(content, destFile.readText(Charsets.UTF_8))
        assertTrue(writer.verifyIntegrity(expectedHash))
        assertFalse(writer.verifyIntegrity("0000000000000000000000000000000000000000000000000000000000000000"))

        writer.close()
    }

    @Test
    fun testLargeChunkReassembly() {
        val destFile = tempFolder.newFile("large_test.sonic.part")
        val chunkSize = 256 * 1024 // 256 KB
        val totalChunks = 8 // 2 MB total
        val totalSize = (chunkSize * totalChunks).toLong()

        val fullData = ByteArray(totalSize.toInt()) { (it % 127).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(fullData)
            .joinToString("") { "%02x".format(it) }

        ChunkedFileWriter(destFile, expectedSize = totalSize).use { writer ->
            for (i in 0 until totalChunks) {
                val offset = (i * chunkSize).toLong()
                val chunk = fullData.copyOfRange(offset.toInt(), (offset + chunkSize).toInt())
                writer.writeChunk(offset, chunk)
            }
            writer.flush()
            assertEquals(totalSize, destFile.length())
            assertTrue(writer.verifyIntegrity(expectedSha))
        }
    }
}
