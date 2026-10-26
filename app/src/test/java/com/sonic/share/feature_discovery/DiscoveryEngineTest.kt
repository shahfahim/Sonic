package com.sonic.share.feature_discovery

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.feature_discovery.ble.BleBeaconPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryEngineTest {

    @Test
    fun testPeerAggregationAndDeduplication() = runBlocking {
        val aggregator = PeerAggregator()
        val blePeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -50, DiscoveryType.BLE)
        val mdnsPeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -30, DiscoveryType.MDNS)

        aggregator.onPeerFound(blePeer)
        aggregator.onPeerFound(mdnsPeer)

        val peers = aggregator.peers.first()
        assertEquals(1, peers.size)
        assertEquals(DiscoveryType.MDNS, peers[0].discoveryType) // mDNS upgrades connectivity
        assertEquals(-30, peers[0].rssi)
    }

    @Test
    fun testPeerRemovalOnLost() = runBlocking {
        val aggregator = PeerAggregator()
        val peer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -50, DiscoveryType.MDNS)

        aggregator.onPeerFound(peer)
        assertEquals(1, aggregator.peers.first().size)

        aggregator.onPeerLost("dev-1")
        assertEquals(0, aggregator.peers.first().size)
    }

    @Test
    fun testWifiDirectPrecedenceOverMdnsAndBle() = runBlocking {
        val aggregator = PeerAggregator()
        val blePeer = PeerDevice("dev-1", "Phone", "Model X", "", 0, -60, DiscoveryType.BLE)
        val mdnsPeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -40, DiscoveryType.MDNS)
        val p2pPeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.49.1", 8989, -20, DiscoveryType.WIFI_DIRECT)

        aggregator.onPeerFound(blePeer)
        assertEquals(DiscoveryType.BLE, aggregator.peers.first()[0].discoveryType)

        aggregator.onPeerFound(mdnsPeer)
        assertEquals(DiscoveryType.MDNS, aggregator.peers.first()[0].discoveryType)

        aggregator.onPeerFound(p2pPeer)
        assertEquals(DiscoveryType.WIFI_DIRECT, aggregator.peers.first()[0].discoveryType)
        assertEquals("192.168.49.1", aggregator.peers.first()[0].ipAddress)
    }

    @Test
    fun testMultiplePeersManagement() = runBlocking {
        val aggregator = PeerAggregator()
        val peer1 = PeerDevice("dev-1", "Phone 1", "Pixel 9", "192.168.1.101", 8989, -45, DiscoveryType.MDNS)
        val peer2 = PeerDevice("dev-2", "Tablet", "Galaxy Tab", "192.168.1.102", 8989, -55, DiscoveryType.MDNS)

        aggregator.onPeerFound(peer1)
        aggregator.onPeerFound(peer2)

        val peers = aggregator.peers.first()
        assertEquals(2, peers.size)
        assertTrue(peers.any { it.id == "dev-1" })
        assertTrue(peers.any { it.id == "dev-2" })
    }

    @Test
    fun testBleBeaconPayloadSerializationRoundtrip() {
        val original = BleBeaconPayload(
            deviceId = "sonic-abc1234",
            deviceName = "Bob's Pixel",
            model = "Pixel 9",
            port = 8989
        )
        val bytes = original.toByteArray()
        val parsed = BleBeaconPayload.parse(bytes)

        assertNotNull(parsed)
        assertEquals("sonic-abc1234", parsed?.deviceId)
        assertEquals("Bob's Pixel", parsed?.deviceName)
        assertEquals("Pixel 9", parsed?.model)
        assertEquals(8989, parsed?.port)
    }

    @Test
    fun testBleBeaconPayloadRejectsCorruptHeader() {
        val corruptBytes = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x05)
        assertNull(BleBeaconPayload.parse(corruptBytes))
        assertNull(BleBeaconPayload.parse(null))
        assertNull(BleBeaconPayload.parse(ByteArray(3)))
    }
}
