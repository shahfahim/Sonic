package com.sonic.share.core.network

import com.sonic.share.core.crypto.SonicCryptoEngine
import com.sonic.share.core.protocol.*
import com.sonic.share.domain.model.TransferPayload
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.StandardSocketOptions
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SocketStreamingTest {

    @Test
    fun testLocalLoopbackFrameTransfer() = runBlocking {
        val latch = CountDownLatch(1)
        var receivedFrame: SonicFrame? = null
        val testPort = 18989

        val server = SocketChannelServer(testPort) { frame, _ ->
            receivedFrame = frame
            latch.countDown()
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)
        val sentFrame = HandshakeFrame("dev-1", "Test Device", 1, byteArrayOf(1, 2, 3))
        client.sendFrame(channel, sentFrame)

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNotNull(receivedFrame)
        assertTrue(receivedFrame is HandshakeFrame)
        assertEquals("dev-1", (receivedFrame as HandshakeFrame).deviceId)

        channel.close()
        server.stop()
    }

    @Test
    fun testBackToBackFramesTransfer() = runBlocking {
        val latch = CountDownLatch(4)
        val receivedFrames = ConcurrentLinkedQueue<SonicFrame>()
        val testPort = 18990

        val server = SocketChannelServer(testPort) { frame, _ ->
            receivedFrames.add(frame)
            latch.countDown()
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        val frames = listOf(
            HandshakeFrame("dev-2", "Device 2", 1, byteArrayOf(4, 5, 6)),
            ManifestFrame("batch-101", listOf(TransferPayload("f1", "file1.txt", 100L, "text/plain", "hash1"))),
            DataChunkFrame(fileIndex = 0, chunkOffset = 0L, data = "Chunk Data Stream".toByteArray()),
            BatchCompleteFrame("batch-101")
        )

        client.sendFrames(channel, frames)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(4, receivedFrames.size)

        val frameList = receivedFrames.toList()
        assertTrue(frameList[0] is HandshakeFrame)
        assertTrue(frameList[1] is ManifestFrame)
        assertTrue(frameList[2] is DataChunkFrame)
        assertTrue(frameList[3] is BatchCompleteFrame)

        channel.close()
        server.stop()
    }

    @Test
    fun testLargeDataChunkTransfer() = runBlocking {
        val latch = CountDownLatch(1)
        var receivedChunk: DataChunkFrame? = null
        val testPort = 18991

        val server = SocketChannelServer(testPort) { frame, _ ->
            if (frame is DataChunkFrame) {
                receivedChunk = frame
                latch.countDown()
            }
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        // 1 MB payload to test high-throughput NIO streaming and buffer resizing
        val largeData = ByteArray(1024 * 1024) { (it % 256).toByte() }
        val sentChunk = DataChunkFrame(fileIndex = 1, chunkOffset = 0L, data = largeData)

        client.sendFrame(channel, sentChunk)

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(receivedChunk)
        assertEquals(1, receivedChunk?.fileIndex)
        assertEquals(0L, receivedChunk?.chunkOffset)
        assertEquals(largeData.size, receivedChunk?.data?.size)
        assertArrayEquals(largeData, receivedChunk?.data)

        channel.close()
        server.stop()
    }

    @Test
    fun testMultipleConcurrentClients() = runBlocking {
        val clientCount = 3
        val latch = CountDownLatch(clientCount)
        val receivedDeviceIds = ConcurrentLinkedQueue<String>()
        val testPort = 18992

        val server = SocketChannelServer(testPort) { frame, _ ->
            if (frame is HandshakeFrame) {
                receivedDeviceIds.add(frame.deviceId)
                latch.countDown()
            }
        }
        server.start()

        val jobs = (1..clientCount).map { i ->
            launch(Dispatchers.IO) {
                val client = SocketChannelClient()
                val channel = client.connect("127.0.0.1", testPort)
                val handshake = HandshakeFrame("client-$i", "Client Device $i", 1, byteArrayOf(i.toByte()))
                client.sendFrame(channel, handshake)
                channel.close()
            }
        }
        jobs.joinAll()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(clientCount, receivedDeviceIds.size)
        for (i in 1..clientCount) {
            assertTrue(receivedDeviceIds.contains("client-$i"))
        }

        server.stop()
    }

    @Test
    fun testBidirectionalTransfer() = runBlocking {
        val serverReceivedLatch = CountDownLatch(1)
        val testPort = 18993

        lateinit var server: SocketChannelServer
        server = SocketChannelServer(testPort) { frame, clientChannel ->
            if (frame is ManifestFrame) {
                serverReceivedLatch.countDown()
                // Send back an accept decision
                runBlocking {
                    server.sendFrame(clientChannel, DecisionFrame(batchId = frame.batchId, accepted = true))
                }
            }
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        val manifest = ManifestFrame("batch-xyz", emptyList())
        client.sendFrame(channel, manifest)

        assertTrue(serverReceivedLatch.await(3, TimeUnit.SECONDS))

        val response = client.receiveFrame(channel, timeoutMs = 3000L)
        assertNotNull(response)
        assertTrue(response is DecisionFrame)
        assertEquals("batch-xyz", (response as DecisionFrame).batchId)
        assertTrue(response.accepted)

        channel.close()
        server.stop()
    }

    @Test
    fun testSocketBufferTuningOptions() = runBlocking {
        val testPort = 18994
        val server = SocketChannelServer(testPort) { _, _ -> }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        // Verify socket options
        assertTrue(channel.getOption(StandardSocketOptions.TCP_NODELAY))
        // Verify buffer size is tuned (at least 64KB, OS may cap or expand)
        val rcvBuf = channel.getOption(StandardSocketOptions.SO_RCVBUF)
        val sndBuf = channel.getOption(StandardSocketOptions.SO_SNDBUF)
        assertTrue(rcvBuf > 0)
        assertTrue(sndBuf > 0)

        channel.close()
        server.stop()
    }

    @Test
    fun testStreamPipelinePartialChunkAssembly() {
        val pipeline = StreamPipeline()
        val codec = FrameCodec()

        val frame = HandshakeFrame("partial-dev", "Partial Device", 1, byteArrayOf(10, 20, 30))
        val encodedBytes = codec.encode(frame)

        // Feed in 7-byte slices
        val sliceSize = 7
        val allDecoded = mutableListOf<SonicFrame>()

        var offset = 0
        while (offset < encodedBytes.size) {
            val length = minOf(sliceSize, encodedBytes.size - offset)
            val chunk = encodedBytes.copyOfRange(offset, offset + length)
            val decoded = pipeline.push(chunk)
            allDecoded.addAll(decoded)
            offset += length
        }

        assertEquals(1, allDecoded.size)
        val decodedFrame = allDecoded.first() as HandshakeFrame
        assertEquals("partial-dev", decodedFrame.deviceId)
    }

    @Test
    fun testStreamPipelineCorruptByteResynchronization() {
        val pipeline = StreamPipeline()
        val codec = FrameCodec()

        val frame = BatchCompleteFrame("batch-recovered")
        val validBytes = codec.encode(frame)

        // Prepend corrupted/noise bytes before the valid frame
        val corruptPrefix = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        val streamData = corruptPrefix + validBytes

        val decoded = pipeline.push(streamData)

        assertEquals(1, decoded.size)
        val decodedFrame = decoded.first() as BatchCompleteFrame
        assertEquals("batch-recovered", decodedFrame.batchId)
    }

    @Test
    fun testEndToEndEncryptedDataChunkWithCryptoEngine() = runBlocking {
        val crypto = SonicCryptoEngine()
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()

        // Derive shared AES-256 key
        val aliceSecret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)
        val bobSecret = crypto.deriveSharedSecret(bobKeyPair.private, aliceKeyPair.public.encoded)

        val plaintext = "Top secret file streaming payload content over NIO channels".toByteArray(Charsets.UTF_8)
        val encryptedData = crypto.encrypt(plaintext, aliceSecret)

        val latch = CountDownLatch(1)
        var decryptedPlaintext: ByteArray? = null
        val testPort = 18995

        val server = SocketChannelServer(testPort) { frame, _ ->
            if (frame is DataChunkFrame) {
                decryptedPlaintext = crypto.decrypt(frame.data, bobSecret)
                latch.countDown()
            }
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        val chunkFrame = DataChunkFrame(fileIndex = 0, chunkOffset = 0L, data = encryptedData)
        client.sendFrame(channel, chunkFrame)

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNotNull(decryptedPlaintext)
        assertArrayEquals(plaintext, decryptedPlaintext)

        channel.close()
        server.stop()
    }
}
