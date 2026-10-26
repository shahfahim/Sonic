package com.sonic.share.domain

import com.sonic.share.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DomainModelTest {
    @Test
    fun testPeerDeviceCreation() {
        val peer = PeerDevice(
            id = "sonic-1234",
            name = "Pixel 9 Pro",
            model = "Google Pixel",
            ipAddress = "192.168.49.1",
            port = 8989,
            rssi = -42,
            discoveryType = DiscoveryType.WIFI_DIRECT
        )
        assertEquals("sonic-1234", peer.id)
        assertEquals("Pixel 9 Pro", peer.name)
        assertEquals("Google Pixel", peer.model)
        assertEquals("192.168.49.1", peer.ipAddress)
        assertEquals(8989, peer.port)
        assertEquals(-42, peer.rssi)
        assertEquals(DiscoveryType.WIFI_DIRECT, peer.discoveryType)
    }

    @Test
    fun testTransferPayloadCalculatesProgress() {
        val payload = TransferPayload(
            id = "f-1",
            name = "video.mp4",
            size = 1000L,
            mimeType = "video/mp4",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val state = TransferState.Transferring(
            progress = 0.5f,
            speedBytesPerSec = 50_000_000L,
            bytesTransferred = 500L,
            totalBytes = 1000L
        )
        assertEquals(0.5f, state.progress, 0.001f)
        assertEquals(50_000_000L, state.speedBytesPerSec)
        assertEquals(500L, state.bytesTransferred)
        assertEquals(1000L, state.totalBytes)
        assertNull(payload.uriString)
    }

    @Test
    fun testTransferStateVariants() {
        val idle: TransferState = TransferState.Idle
        val connecting: TransferState = TransferState.Connecting
        val handshaking: TransferState = TransferState.Handshaking
        val completed: TransferState = TransferState.Completed
        val failed: TransferState = TransferState.Failed("Connection timed out")

        assertTrue(idle is TransferState.Idle)
        assertTrue(connecting is TransferState.Connecting)
        assertTrue(handshaking is TransferState.Handshaking)
        assertTrue(completed is TransferState.Completed)
        assertTrue(failed is TransferState.Failed)
        assertEquals("Connection timed out", (failed as TransferState.Failed).reason)
    }

    @Test
    fun testTransferSessionCreation() {
        val peer = PeerDevice(
            id = "p-1",
            name = "Galaxy S24",
            model = "Samsung",
            ipAddress = "192.168.1.100",
            port = 8989,
            rssi = -35,
            discoveryType = DiscoveryType.MDNS
        )
        val payload = TransferPayload(
            id = "payload-1",
            name = "document.pdf",
            size = 2048L,
            mimeType = "application/pdf",
            sha256 = "deadbeef1234",
            uriString = "content://media/external/files/123"
        )
        val session = TransferSession(
            sessionId = "session-001",
            peer = peer,
            payloads = listOf(payload),
            state = TransferState.Connecting,
            isIncoming = true
        )

        assertEquals("session-001", session.sessionId)
        assertEquals("Galaxy S24", session.peer.name)
        assertEquals(1, session.payloads.size)
        assertEquals("content://media/external/files/123", session.payloads[0].uriString)
        assertTrue(session.state is TransferState.Connecting)
        assertTrue(session.isIncoming)
    }
}
