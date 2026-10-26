package com.sonic.share.core.protocol

import com.sonic.share.domain.model.TransferPayload
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class FrameCodecTest {
    private val codec = FrameCodec()

    @Test
    fun testHandshakeEncodingAndDecoding() {
        val original = HandshakeFrame(
            deviceId = "dev-42",
            deviceName = "Alex's Galaxy",
            version = 1,
            publicKey = byteArrayOf(1, 2, 3, 4, 5)
        )
        val encodedBytes = codec.encode(original)
        val decoded = codec.decode(ByteBuffer.wrap(encodedBytes)) as? HandshakeFrame

        assertNotNull(decoded)
        assertEquals("dev-42", decoded?.deviceId)
        assertEquals("Alex's Galaxy", decoded?.deviceName)
        assertEquals(1, decoded?.version)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded?.publicKey)
    }

    @Test
    fun testDataChunkFrameEncoding() {
        val payloadData = "Hello Sonic Stream".toByteArray(Charsets.UTF_8)
        val chunk = DataChunkFrame(
            fileIndex = 0,
            chunkOffset = 1024L,
            data = payloadData
        )
        val bytes = codec.encode(chunk)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? DataChunkFrame

        assertNotNull(decoded)
        assertEquals(0, decoded?.fileIndex)
        assertEquals(1024L, decoded?.chunkOffset)
        assertArrayEquals(payloadData, decoded?.data)
    }

    @Test
    fun testRejectsInvalidMagicBytes() {
        val invalidHeader = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0, 0, 0, 5, 1, 2, 3, 4, 5)
        val decoded = codec.decode(ByteBuffer.wrap(invalidHeader))
        assertNull(decoded)
    }

    @Test
    fun testManifestFrameEncodingAndDecoding() {
        val payloads = listOf(
            TransferPayload(
                id = "f1",
                name = "photo.jpg",
                size = 2048L,
                mimeType = "image/jpeg",
                sha256 = "abc123hash",
                uriString = "content://media/1"
            ),
            TransferPayload(
                id = "f2",
                name = "notes.txt",
                size = 512L,
                mimeType = "text/plain",
                sha256 = "def456hash"
            )
        )
        val original = ManifestFrame(batchId = "batch-101", files = payloads)
        val bytes = codec.encode(original)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? ManifestFrame

        assertNotNull(decoded)
        assertEquals("batch-101", decoded?.batchId)
        assertEquals(2, decoded?.files?.size)
        assertEquals("f1", decoded?.files?.get(0)?.id)
        assertEquals("photo.jpg", decoded?.files?.get(0)?.name)
        assertEquals(2048L, decoded?.files?.get(0)?.size)
        assertEquals("image/jpeg", decoded?.files?.get(0)?.mimeType)
        assertEquals("abc123hash", decoded?.files?.get(0)?.sha256)
        assertEquals("notes.txt", decoded?.files?.get(1)?.name)
    }

    @Test
    fun testDecisionFrameEncodingAndDecoding() {
        val accepted = DecisionFrame(batchId = "batch-101", accepted = true)
        val acceptedBytes = codec.encode(accepted)
        val decodedAccepted = codec.decode(ByteBuffer.wrap(acceptedBytes)) as? DecisionFrame

        assertNotNull(decodedAccepted)
        assertEquals("batch-101", decodedAccepted?.batchId)
        assertTrue(decodedAccepted?.accepted == true)
        assertNull(decodedAccepted?.reasonCode)

        val rejected = DecisionFrame(batchId = "batch-101", accepted = false, reasonCode = "USER_DECLINED")
        val rejectedBytes = codec.encode(rejected)
        val decodedRejected = codec.decode(ByteBuffer.wrap(rejectedBytes)) as? DecisionFrame

        assertNotNull(decodedRejected)
        assertEquals("batch-101", decodedRejected?.batchId)
        assertFalse(decodedRejected?.accepted == true)
        assertEquals("USER_DECLINED", decodedRejected?.reasonCode)
    }

    @Test
    fun testFileAckFrameEncodingAndDecoding() {
        val original = FileAckFrame(fileIndex = 3, sha256 = "sha256checksumvalue")
        val bytes = codec.encode(original)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? FileAckFrame

        assertNotNull(decoded)
        assertEquals(3, decoded?.fileIndex)
        assertEquals("sha256checksumvalue", decoded?.sha256)
    }

    @Test
    fun testBatchCompleteFrameEncodingAndDecoding() {
        val original = BatchCompleteFrame(batchId = "batch-xyz")
        val bytes = codec.encode(original)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? BatchCompleteFrame

        assertNotNull(decoded)
        assertEquals("batch-xyz", decoded?.batchId)
    }

    @Test
    fun testRejectsTruncatedBuffer() {
        // Less than 9 bytes header
        val tooShort = byteArrayOf(0x53, 0x4F, 0x4E, 0x43, 0x01)
        val decoded = codec.decode(ByteBuffer.wrap(tooShort))
        assertNull(decoded)

        // Declares 20 bytes length but only gives 5
        val truncatedPayload = byteArrayOf(0x53, 0x4F, 0x4E, 0x43, 0x01, 0, 0, 0, 20, 1, 2, 3, 4, 5)
        val decoded2 = codec.decode(ByteBuffer.wrap(truncatedPayload))
        assertNull(decoded2)
    }

    @Test
    fun testLargeDataChunk1MB() {
        val largeData = ByteArray(1024 * 1024) { (it % 128).toByte() }
        val chunk = DataChunkFrame(fileIndex = 1, chunkOffset = 5242880L, data = largeData)
        val bytes = codec.encode(chunk)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? DataChunkFrame

        assertNotNull(decoded)
        assertEquals(1, decoded?.fileIndex)
        assertEquals(5242880L, decoded?.chunkOffset)
        assertEquals(1024 * 1024, decoded?.data?.size)
        assertArrayEquals(largeData, decoded?.data)
    }
}
