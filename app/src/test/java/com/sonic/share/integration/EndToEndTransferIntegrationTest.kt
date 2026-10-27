package com.sonic.share.integration

import android.content.Context
import android.content.ContextWrapper
import com.sonic.share.core.crypto.SonicCryptoEngine
import com.sonic.share.core.network.SocketChannelClient
import com.sonic.share.core.network.SocketChannelServer
import com.sonic.share.core.protocol.*
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.domain.model.TransferPayload
import com.sonic.share.domain.model.TransferState
import com.sonic.share.feature_transfer.data.ChunkedFileWriter
import com.sonic.share.feature_transfer.data.StorageManager
import com.sonic.share.feature_transfer.domain.TransferManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.channels.SocketChannel
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

/**
 * End-to-End Loopback Transfer Integration Test Suite for Sonic.
 *
 * Runs entirely on the host JVM without an emulator or physical Android device.
 * Tests:
 * 1. [testCompleteAirDropTransferPipeline]:
 *    - Full 5 MB loopback socket transfer over 127.0.0.1
 *    - Ephemeral ECDH key exchange (secp256r1) + AES-256-GCM chunk encryption
 *    - Streaming across five 1 MB chunks
 *    - Complete SHA-256 calculation and verification
 *    - Byte-for-byte exact equality between original and transferred payload.
 * 2. [testTransferManagerHighLevelEndToEndLoopback]:
 *    - High-level coordinator test using TransferManager sender and receiver
 *    - Verified file writing to `.sonic.part` and Scoped Storage commitment via fallback
 *    - Moving average speed calculation validation and final TransferState.Completed.
 * 3. [testMultiFileEncryptedTransfer]:
 *    - Multi-payload batch in a single manifest (text document + binary image)
 *    - Sequential chunk streaming with individual FileAckFrame roundtrips and BatchComplete.
 */
class EndToEndTransferIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

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
    fun testCompleteAirDropTransferPipeline() = runBlocking {
        val testPort = findFreePort()
        val crypto = SonicCryptoEngine()

        // 1. Generate synthetic 5 MB binary data
        val totalSize = 5 * 1024 * 1024 // 5 MB
        val originalData = ByteArray(totalSize) { i ->
            ((i * 31 + 17) % 256).toByte()
        }
        val expectedSha256 = crypto.computeSha256(originalData)

        val batchId = "batch-5mb-${UUID.randomUUID()}"
        val payload = TransferPayload(
            id = "payload-5mb",
            name = "sonic_payload_5mb.bin",
            size = totalSize.toLong(),
            mimeType = "application/octet-stream",
            sha256 = expectedSha256
        )

        val destinationFile = tempFolder.newFile("received_5mb.bin.part")
        var writer: ChunkedFileWriter? = null
        var serverSharedSecret: SecretKey? = null
        val transferCompletedLatch = CountDownLatch(1)
        val fileAckReceivedLatch = CountDownLatch(1)
        var receivedAck: FileAckFrame? = null

        // 2. Start Receiver Server
        lateinit var server: SocketChannelServer
        server = SocketChannelServer(testPort) { frame, channel ->
            runBlocking {
                when (frame) {
                    is HandshakeFrame -> {
                        // Derive receiver shared secret
                        val receiverKeyPair = crypto.generateKeyPair()
                        serverSharedSecret = crypto.deriveSharedSecret(
                            receiverKeyPair.private,
                            frame.publicKey
                        )
                        // Send receiver HandshakeFrame back
                        val reply = HandshakeFrame(
                            deviceId = "receiver-pipeline",
                            deviceName = "Sonic Receiver",
                            version = 1,
                            publicKey = receiverKeyPair.public.encoded
                        )
                        server.sendFrame(channel, reply)
                    }
                    is ManifestFrame -> {
                        assertEquals(batchId, frame.batchId)
                        assertEquals(1, frame.files.size)
                        writer = ChunkedFileWriter(destinationFile, expectedSize = totalSize.toLong())
                        // Accept transfer batch
                        val decision = DecisionFrame(batchId = frame.batchId, accepted = true)
                        server.sendFrame(channel, decision)
                    }
                    is DataChunkFrame -> {
                        assertNotNull("Server shared secret must be established", serverSharedSecret)
                        val decryptedChunk = crypto.decrypt(frame.data, serverSharedSecret!!)
                        writer!!.writeChunk(frame.chunkOffset, decryptedChunk)

                        if (writer!!.bytesWritten >= totalSize) {
                            writer!!.flush()
                            val computedSha = writer!!.computeSha256()
                            assertTrue("Integrity verification must pass", writer!!.verifyIntegrity(expectedSha256))
                            // Acknowledge file completion
                            val ack = FileAckFrame(fileIndex = frame.fileIndex, sha256 = computedSha)
                            server.sendFrame(channel, ack)
                        }
                    }
                    is BatchCompleteFrame -> {
                        assertEquals(batchId, frame.batchId)
                        writer?.close()
                        transferCompletedLatch.countDown()
                    }
                    else -> {}
                }
            }
        }
        server.start()

        // 3. Sender initiates AirDrop connection
        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        // Ephemeral ECDH Handshake
        val senderKeyPair = crypto.generateKeyPair()
        val clientHandshake = HandshakeFrame(
            deviceId = "sender-pipeline",
            deviceName = "Sonic Sender",
            version = 1,
            publicKey = senderKeyPair.public.encoded
        )
        client.sendFrame(channel, clientHandshake)

        val serverHandshake = client.receiveFrame(channel, timeoutMs = 5000L) as? HandshakeFrame
        assertNotNull("Server handshake reply must not be null", serverHandshake)
        val clientSharedSecret = crypto.deriveSharedSecret(
            senderKeyPair.private,
            serverHandshake!!.publicKey
        )

        // Send Manifest
        val manifest = ManifestFrame(batchId = batchId, files = listOf(payload))
        client.sendFrame(channel, manifest)

        val decision = client.receiveFrame(channel, timeoutMs = 5000L) as? DecisionFrame
        assertNotNull("Decision frame must not be null", decision)
        assertTrue("Decision must be accepted", decision!!.accepted)

        // Stream 5 MB in 1 MB chunks encrypted with AES-256-GCM
        val chunkSize = 1024 * 1024 // 1 MB
        var offset = 0
        var chunkIndex = 0
        while (offset < totalSize) {
            val length = minOf(chunkSize, totalSize - offset)
            val chunkBytes = originalData.copyOfRange(offset, offset + length)
            val encryptedChunk = crypto.encrypt(chunkBytes, clientSharedSecret)

            val chunkFrame = DataChunkFrame(
                fileIndex = 0,
                chunkOffset = offset.toLong(),
                data = encryptedChunk
            )
            client.sendFrame(channel, chunkFrame)
            offset += length
            chunkIndex++
        }
        assertEquals(5, chunkIndex) // Exactly five 1 MB chunks

        // Await FileAckFrame from receiver
        val ack = client.receiveFrame(channel, timeoutMs = 10000L) as? FileAckFrame
        assertNotNull("FileAckFrame must be received", ack)
        assertEquals(0, ack!!.fileIndex)
        assertEquals(expectedSha256.lowercase(), ack.sha256.lowercase())

        // Send BatchCompleteFrame
        client.sendFrame(channel, BatchCompleteFrame(batchId = batchId))

        // Wait for receiver to finish writing and close
        assertTrue("Receiver must complete within 10 seconds", transferCompletedLatch.await(10, TimeUnit.SECONDS))

        channel.close()
        server.stop()

        // 4. Verify byte-for-byte exact equality between original and transferred data
        assertTrue("Destination file must exist", destinationFile.exists())
        assertEquals("File length must match 5 MB exactly", totalSize.toLong(), destinationFile.length())

        val receivedBytes = destinationFile.readBytes()
        assertArrayEquals("Transferred 5 MB payload must match original byte-for-byte", originalData, receivedBytes)
    }

    @Test
    fun testTransferManagerHighLevelEndToEndLoopback() = runBlocking {
        val testPort = findFreePort()
        val crypto = SonicCryptoEngine()

        val rootDir = tempFolder.newFolder("tm_high_level_loopback")
        val fakeContext = FakeContext(rootDir)
        val storageManager = StorageManager(fakeContext)

        // 3 MB synthetic binary data
        val dataSize = 3 * 1024 * 1024
        val sourceData = ByteArray(dataSize) { i ->
            ((i * 19 + 73) % 256).toByte()
        }
        val expectedSha = crypto.computeSha256(sourceData)

        val payload = TransferPayload(
            id = "high-level-payload",
            name = "high_level_doc.bin",
            size = sourceData.size.toLong(),
            mimeType = "application/octet-stream",
            sha256 = expectedSha
        )

        // Receiver TransferManager with automatic acceptance and storage commitment
        val receiver = TransferManager(
            context = fakeContext,
            storageManager = storageManager,
            localDeviceId = "rx-tm-device",
            localDeviceName = "Receiver TM",
            localPort = testPort,
            autoAccept = true
        )
        receiver.startServer(testPort)

        // Sender TransferManager
        val sender = TransferManager(
            localDeviceId = "tx-tm-device",
            localDeviceName = "Sender TM",
            payloadStreamProvider = { p ->
                if (p.id == payload.id) ByteArrayInputStream(sourceData) else null
            }
        )

        val peer = PeerDevice(
            id = "rx-tm-device",
            name = "Receiver TM",
            model = "Pixel 9 Pro",
            ipAddress = "127.0.0.1",
            port = testPort,
            rssi = -35,
            discoveryType = DiscoveryType.MDNS
        )

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload)).collect { state ->
            states.add(state)
        }

        // Verify state flow transitions
        assertTrue("States must include Connecting", states.any { it is TransferState.Connecting })
        assertTrue("States must include Handshaking", states.any { it is TransferState.Handshaking })
        assertTrue("States must include Transferring", states.any { it is TransferState.Transferring })
        assertTrue("Final state must be Completed", states.last() is TransferState.Completed)

        // Verify speed and progress reporting
        val transferringStates = states.filterIsInstance<TransferState.Transferring>()
        assertTrue("Must have multiple transferring progress states", transferringStates.size >= 3)
        assertEquals(sourceData.size.toLong(), transferringStates.last().bytesTransferred)
        assertEquals(1.0f, transferringStates.last().progress, 0.001f)
        assertTrue("Moving average speed calculator must emit non-negative speeds", transferringStates.any { it.speedBytesPerSec >= 0L })

        // Give receiver async coroutine a brief window to finish commit
        delay(300)

        // Verify Storage Commitment:
        // 1. Temporary `.sonic.part` files should be cleaned up / committed
        assertEquals(0, storageManager.cleanupTempFiles())

        // 2. Committed file must exist in download destination directory
        val targetDir = File(fakeContext.getExternalFilesDir("Download"), "Sonic")
        val committedFile = File(targetDir, "high_level_doc.bin")
        assertTrue("Committed file must exist in Scoped Storage destination: ${committedFile.absolutePath}", committedFile.exists())
        assertEquals(sourceData.size.toLong(), committedFile.length())
        assertArrayEquals("Committed file must match source byte-for-byte", sourceData, committedFile.readBytes())

        sender.teardown()
        receiver.teardown()
    }

    @Test
    fun testMultiFileEncryptedTransfer() = runBlocking {
        val testPort = findFreePort()
        val crypto = SonicCryptoEngine()

        val rootDir = tempFolder.newFolder("tm_multi_file")
        val fakeContext = FakeContext(rootDir)
        val storageManager = StorageManager(fakeContext)

        // File 1: Text document
        val textContent = "Sonic P2P AirDrop-style protocol test document.\n".repeat(300)
        val file1Data = textContent.toByteArray(Charsets.UTF_8)
        val file1Sha = crypto.computeSha256(file1Data)

        // File 2: Binary image/data (1.5 MB spanning two 1 MB chunks)
        val file2Data = ByteArray(1024 * 1024 + 512 * 1024) { i ->
            ((i * 47) % 256).toByte()
        }
        val file2Sha = crypto.computeSha256(file2Data)

        val payload1 = TransferPayload(
            id = "multi-txt-1",
            name = "notes.txt",
            size = file1Data.size.toLong(),
            mimeType = "text/plain",
            sha256 = file1Sha
        )
        val payload2 = TransferPayload(
            id = "multi-img-2",
            name = "photo.png",
            size = file2Data.size.toLong(),
            mimeType = "image/png",
            sha256 = file2Sha
        )

        val receiver = TransferManager(
            context = fakeContext,
            storageManager = storageManager,
            localDeviceId = "multi-rx",
            localDeviceName = "Multi Receiver",
            localPort = testPort,
            autoAccept = true
        )
        receiver.startServer(testPort)

        val sender = TransferManager(
            localDeviceId = "multi-tx",
            localDeviceName = "Multi Sender",
            payloadStreamProvider = { p ->
                when (p.id) {
                    payload1.id -> ByteArrayInputStream(file1Data)
                    payload2.id -> ByteArrayInputStream(file2Data)
                    else -> null
                }
            }
        )

        val peer = PeerDevice(
            id = "multi-rx",
            name = "Multi Receiver",
            model = "Pixel Tablet",
            ipAddress = "127.0.0.1",
            port = testPort,
            rssi = -25,
            discoveryType = DiscoveryType.MDNS
        )

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload1, payload2)).collect {
            states.add(it)
        }

        assertTrue("Batch transfer must complete successfully", states.last() is TransferState.Completed)

        val totalExpectedBytes = file1Data.size.toLong() + file2Data.size.toLong()
        val lastTransferring = states.filterIsInstance<TransferState.Transferring>().last()
        assertEquals(totalExpectedBytes, lastTransferring.bytesTransferred)
        assertEquals(1.0f, lastTransferring.progress, 0.001f)

        delay(300)

        // Verify both files committed to destination folder
        val targetDir = File(fakeContext.getExternalFilesDir("Download"), "Sonic")
        val committedFile1 = File(targetDir, "notes.txt")
        val committedFile2 = File(targetDir, "photo.png")

        assertTrue("Committed text document must exist", committedFile1.exists())
        assertEquals(file1Data.size.toLong(), committedFile1.length())
        assertArrayEquals("Committed text file must match byte-for-byte", file1Data, committedFile1.readBytes())

        assertTrue("Committed image binary must exist", committedFile2.exists())
        assertEquals(file2Data.size.toLong(), committedFile2.length())
        assertArrayEquals("Committed image file must match byte-for-byte", file2Data, committedFile2.readBytes())

        sender.teardown()
        receiver.teardown()
    }
}
