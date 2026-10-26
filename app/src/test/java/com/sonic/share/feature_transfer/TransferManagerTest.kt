package com.sonic.share.feature_transfer

import com.sonic.share.core.crypto.SonicCryptoEngine
import com.sonic.share.core.protocol.ManifestFrame
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.domain.model.TransferPayload
import com.sonic.share.domain.model.TransferState
import com.sonic.share.feature_transfer.domain.TransferManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

class TransferManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testSpeedCalculatorWindow() {
        val calc = TransferManager.SpeedCalculator(windowDurationMs = 1000L)
        assertEquals(0L, calc.update(0L, nowMs = 1000L))

        // Transferred 100 KB over 500 ms -> 200 KB/s
        val speed = calc.update(100_000L, nowMs = 1500L)
        assertEquals(200_000L, speed)

        // Reset
        calc.reset()
        assertEquals(0L, calc.update(0L, nowMs = 2000L))
    }

    @Test
    fun testEndToEndTransferBatchSuccess() = runBlocking {
        val testPort = 19189
        val crypto = SonicCryptoEngine()

        // Prepare test payload data
        val fileContent = "Sonic high speed P2P transfer payload data stream test! ".repeat(500)
        val fileBytes = fileContent.toByteArray(Charsets.UTF_8)
        val fileSha = crypto.computeSha256(fileBytes)

        val payload = TransferPayload(
            id = "test-payload-1",
            name = "sonic_test_doc.txt",
            size = fileBytes.size.toLong(),
            mimeType = "text/plain",
            sha256 = fileSha
        )

        // Create receiver TransferManager with autoAccept = true for seamless loopback test
        val receiver = TransferManager(
            localDeviceId = "receiver-1",
            localDeviceName = "Receiver Phone",
            localPort = testPort,
            autoAccept = true
        )
        receiver.startServer(testPort)

        // Sender TransferManager
        val sender = TransferManager(
            localDeviceId = "sender-1",
            localDeviceName = "Sender Phone",
            payloadStreamProvider = { p ->
                if (p.id == payload.id) ByteArrayInputStream(fileBytes) else null
            }
        )

        val peer = PeerDevice(
            id = "receiver-1",
            name = "Receiver Phone",
            model = "Pixel",
            ipAddress = "127.0.0.1",
            port = testPort,
            rssi = -30,
            discoveryType = DiscoveryType.MDNS
        )

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload)).collect { state ->
            states.add(state)
        }

        // Verify state flow transitions
        assertTrue("States should contain Connecting", states.any { it is TransferState.Connecting })
        assertTrue("States should contain Handshaking", states.any { it is TransferState.Handshaking })
        assertTrue("States should contain Transferring", states.any { it is TransferState.Transferring })
        assertTrue("States should conclude with Completed", states.last() is TransferState.Completed)

        val transferringStates = states.filterIsInstance<TransferState.Transferring>()
        assertTrue(transferringStates.isNotEmpty())
        assertEquals(payload.size, transferringStates.last().bytesTransferred)
        assertEquals(1.0f, transferringStates.last().progress, 0.001f)

        sender.teardown()
        receiver.teardown()
    }

    @Test
    fun testTransferDeclineHandling() = runBlocking {
        val testPort = 19190

        val payload = TransferPayload(
            id = "declined-1",
            name = "confidential.pdf",
            size = 1024L,
            mimeType = "application/pdf",
            sha256 = "dummy-sha"
        )

        // Receiver with autoAccept = false
        val receiver = TransferManager(
            localDeviceId = "receiver-decline",
            localDeviceName = "Strict Receiver",
            localPort = testPort,
            autoAccept = false
        )
        receiver.startServer(testPort)

        // Listen for incoming manifest and decline it
        val job = launch {
            receiver.incomingManifest.collect { manifest ->
                if (manifest != null) {
                    receiver.declineBatch(manifest.batchId)
                }
            }
        }

        val sender = TransferManager(
            localDeviceId = "sender-decline",
            payloadStreamProvider = { ByteArrayInputStream(ByteArray(1024)) }
        )

        val peer = PeerDevice(
            id = "receiver-decline",
            name = "Strict Receiver",
            model = "Pixel",
            ipAddress = "127.0.0.1",
            port = testPort,
            rssi = -40,
            discoveryType = DiscoveryType.MDNS
        )

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload)).collect { state ->
            states.add(state)
        }

        job.cancel()

        assertTrue("Final state should be Failed", states.last() is TransferState.Failed)
        val failureReason = (states.last() as TransferState.Failed).reason
        assertTrue("Reason should mention declined: $failureReason", failureReason.contains("declined", ignoreCase = true))

        sender.teardown()
        receiver.teardown()
    }

    @Test
    fun testMultiFileBatchTransfer() = runBlocking {
        val testPort = 19191
        val crypto = SonicCryptoEngine()

        val file1Data = "File One Content".toByteArray(Charsets.UTF_8)
        val file2Data = ByteArray(1024 * 1024 + 100) { (it % 256).toByte() } // > 1 MB chunk test

        val payload1 = TransferPayload("p1", "file1.txt", file1Data.size.toLong(), "text/plain", crypto.computeSha256(file1Data))
        val payload2 = TransferPayload("p2", "file2.bin", file2Data.size.toLong(), "application/octet-stream", crypto.computeSha256(file2Data))

        val receiver = TransferManager(
            localDeviceId = "multi-receiver",
            localPort = testPort,
            autoAccept = true
        )
        receiver.startServer(testPort)

        val sender = TransferManager(
            localDeviceId = "multi-sender",
            payloadStreamProvider = { p ->
                when (p.id) {
                    "p1" -> ByteArrayInputStream(file1Data)
                    "p2" -> ByteArrayInputStream(file2Data)
                    else -> null
                }
            }
        )

        val peer = PeerDevice("multi-receiver", "Receiver", "Pixel", "127.0.0.1", testPort, -50, DiscoveryType.MDNS)

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload1, payload2)).collect {
            states.add(it)
        }

        assertTrue("Final state must be Completed", states.last() is TransferState.Completed)
        val totalExpected = file1Data.size.toLong() + file2Data.size.toLong()
        val lastTransferring = states.filterIsInstance<TransferState.Transferring>().last()
        assertEquals(totalExpected, lastTransferring.bytesTransferred)
        assertEquals(1.0f, lastTransferring.progress, 0.001f)

        sender.teardown()
        receiver.teardown()
    }

    @Test
    fun testIntegrityFailureRejectionAndCleanup() = runBlocking {
        val testPort = 19192
        val crypto = SonicCryptoEngine()

        val legitimateData = "Legitimate contents before data corruption".toByteArray(Charsets.UTF_8)
        val wrongSha = crypto.computeSha256("Different unexpected content".toByteArray(Charsets.UTF_8))

        // Manifest declares wrongSha so verification will fail on receiver
        val payload = TransferPayload("corrupt-1", "corrupt_file.txt", legitimateData.size.toLong(), "text/plain", wrongSha)

        val receiver = TransferManager(
            localDeviceId = "strict-receiver",
            localPort = testPort,
            autoAccept = true
        )
        receiver.startServer(testPort)

        val sender = TransferManager(
            localDeviceId = "sender-corrupt",
            payloadStreamProvider = { ByteArrayInputStream(legitimateData) }
        )

        val peer = PeerDevice("strict-receiver", "Receiver", "Pixel", "127.0.0.1", testPort, -50, DiscoveryType.MDNS)

        val states = mutableListOf<TransferState>()
        sender.sendBatch(peer, listOf(payload)).collect {
            states.add(it)
        }

        assertTrue("Transfer must fail due to integrity check failure", states.last() is TransferState.Failed)
        val reason = (states.last() as TransferState.Failed).reason
        assertTrue("Reason should mention integrity: $reason", reason.contains("integrity", ignoreCase = true) || reason.contains("corrupt", ignoreCase = true))

        sender.teardown()
        receiver.teardown()
    }
}
