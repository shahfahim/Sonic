package com.sonic.share.feature_transfer.data

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance chunk-based file writer using RandomAccessFile.
 *
 * Designed for resumable and out-of-order streaming transfers:
 * - Writes chunks at arbitrary byte offsets to temporary `.sonic.part` files.
 * - Supports preallocation when expected size is known.
 * - Provides synchronous buffer flushing and SHA-256 integrity verification.
 */
class ChunkedFileWriter(
    val file: File,
    val expectedSize: Long = -1L
) : Closeable {

    private val raf: RandomAccessFile
    private val bytesWrittenCounter = AtomicLong(0)
    @Volatile
    private var isClosed = false

    init {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        raf = RandomAccessFile(file, "rw")
        if (expectedSize > 0) {
            raf.setLength(expectedSize)
        }
    }

    /**
     * Total number of chunk bytes written through this writer instance.
     */
    val bytesWritten: Long
        get() = bytesWrittenCounter.get()

    /**
     * Whether the destination file has reached its expected size.
     */
    val isComplete: Boolean
        get() = if (expectedSize >= 0) file.length() >= expectedSize else file.length() > 0

    /**
     * Writes [data] of length [length] starting at [offset] within the destination file.
     * Thread-safe and handles arbitrary out-of-order chunk arrivals.
     */
    @Synchronized
    fun writeChunk(offset: Long, data: ByteArray, length: Int = data.size) {
        check(!isClosed) { "ChunkedFileWriter is closed" }
        raf.seek(offset)
        raf.write(data, 0, length)
        bytesWrittenCounter.addAndGet(length.toLong())
    }

    /**
     * Flushes channel buffers and forces metadata/data synchronization to disk.
     */
    @Synchronized
    fun flush() {
        if (!isClosed) {
            try {
                raf.channel.force(true)
            } catch (_: Exception) {}
            try {
                raf.fd.sync()
            } catch (_: Exception) {}
        }
    }

    /**
     * Computes the SHA-256 digest of the entire file on disk and returns it as a 64-character
     * lowercase hexadecimal string.
     */
    @Synchronized
    fun computeSha256(): String {
        flush()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        val hexChars = CharArray(hashBytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in hashBytes.indices) {
            val v = hashBytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    /**
     * Verifies that the file's SHA-256 matches [expectedSha256].
     * Returns true if [expectedSha256] is empty or blank, or if the hashes match (case-insensitively).
     */
    fun verifyIntegrity(expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true
        val computed = computeSha256()
        return computed.equals(expectedSha256, ignoreCase = true)
    }

    @Synchronized
    override fun close() {
        if (!isClosed) {
            try {
                flush()
            } catch (_: Exception) {}
            try {
                raf.close()
            } catch (_: Exception) {}
            isClosed = true
        }
    }
}
