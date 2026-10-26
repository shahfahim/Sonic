package com.sonic.share.feature_discovery

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.feature_discovery.ble.BleAdvertiser
import com.sonic.share.feature_discovery.ble.BleBeaconPayload
import com.sonic.share.feature_discovery.ble.BleScanner
import com.sonic.share.feature_discovery.mdns.MdnsManager
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
    }

    @Test
    fun testMdnsDiscoveredFirstThenBleUpdatesRssiWithoutDowngrading() = runBlocking {
        val aggregator = PeerAggregator()
        val mdnsPeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, 0, DiscoveryType.MDNS)
        val blePeer = PeerDevice("dev-1", "Phone", "Model X", "", 8989, -45, DiscoveryType.BLE)

        aggregator.onPeerFound(mdnsPeer)
        aggregator.onPeerFound(blePeer)

        val peers = aggregator.peers.first()
        assertEquals(1, peers.size)
        assertEquals(DiscoveryType.MDNS, peers[0].discoveryType)
        assertEquals("192.168.1.50", peers[0].ipAddress)
        assertEquals(-45, peers[0].rssi) // RSSI updated from BLE proximity
    }

    @Test
    fun testWifiDirectIpPrecedenceOverMdns() = runBlocking {
        val aggregator = PeerAggregator()
        val wfdPeer = PeerDevice("dev-1", "Tablet", "Tab S", "192.168.49.1", 8989, -40, DiscoveryType.WIFI_DIRECT)
        val mdnsPeer = PeerDevice("dev-1", "Tablet", "Tab S", "192.168.1.100", 8989, 0, DiscoveryType.MDNS)

        aggregator.onPeerFound(wfdPeer)
        aggregator.onPeerFound(mdnsPeer)

        val peers = aggregator.peers.first()
        assertEquals(1, peers.size)
        assertEquals(DiscoveryType.WIFI_DIRECT, peers[0].discoveryType)
        assertEquals("192.168.49.1", peers[0].ipAddress) // P2P GO IP preserved over LAN IP
    }

    @Test
    fun testEvictStalePeersEvictsBleAndPreservesMdnsAndWifiDirect() = runBlocking {
        val aggregator = PeerAggregator(peerTimeoutMs = 30_000L)
        val baseTime = 1_000_000L

        val blePeer = PeerDevice("ble-dev", "BLE Beacon", "Model A", "", 8989, -60, DiscoveryType.BLE)
        val mdnsPeer = PeerDevice("mdns-dev", "LAN Host", "Model B", "192.168.1.10", 8989, 0, DiscoveryType.MDNS)
        val wfdPeer = PeerDevice("wfd-dev", "P2P Peer", "Model C", "192.168.49.2", 8989, -35, DiscoveryType.WIFI_DIRECT)

        aggregator.onPeerFound(blePeer)
        aggregator.onPeerFound(mdnsPeer)
        aggregator.onPeerFound(wfdPeer)

        assertEquals(3, aggregator.peers.first().size)

        // Evict with simulated timestamp 35s in the future
        val evictedCount = aggregator.evictStalePeers(now = System.currentTimeMillis() + 35_000L)
        assertEquals(1, evictedCount) // Only BLE should be evicted

        val remainingPeers = aggregator.peers.first()
        assertEquals(2, remainingPeers.size)
        assertTrue(remainingPeers.any { it.id == "mdns-dev" && it.discoveryType == DiscoveryType.MDNS })
        assertTrue(remainingPeers.any { it.id == "wfd-dev" && it.discoveryType == DiscoveryType.WIFI_DIRECT })
        assertFalse(remainingPeers.any { it.id == "ble-dev" })
    }

    @Test
    fun testPeerLossRemovesPeer() = runBlocking {
        val aggregator = PeerAggregator()
        val peer = PeerDevice("dev-1", "Laptop", "Air", "192.168.1.20", 8989, 0, DiscoveryType.MDNS)

        aggregator.onPeerFound(peer)
        assertEquals(1, aggregator.peers.first().size)

        aggregator.onPeerLost("dev-1")
        assertEquals(0, aggregator.peers.first().size)
    }

    @Test
    fun testBleBeaconPayloadPacketBudgetAndRoundtrip() {
        val payload = BleBeaconPayload(
            deviceId = "dev-1234",
            deviceName = "Pixel 9",
            model = "Pixel",
            port = 8989
        )
        val bytes = payload.toByteArray()

        // Verify primary advertising packet stays strictly below 14 bytes (well within 31-byte legacy limit)
        assertTrue("Payload bytes length ${bytes.size} must be <= 13", bytes.size <= 13)

        val parsed = BleBeaconPayload.parse(bytes)
        assertNotNull(parsed)
        assertEquals("dev-1234", parsed!!.deviceId)
        assertEquals(8989, parsed.port)
    }

    @Test
    fun testBleBeaconPayloadParseTruncatedReturnsNull() {
        assertNull(BleBeaconPayload.parse(null))
        assertNull(BleBeaconPayload.parse(byteArrayOf(0x53, 0x4E))) // Too short (< 5 bytes)
        assertNull(BleBeaconPayload.parse(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00))) // Wrong magic

        // Correct magic + port, but length header says 10 bytes while only 2 remain
        val corruptPayload = byteArrayOf(
            0x53, 0x4E, // Magic
            0x00, 0x50, // Port 80
            10,         // Length claims 10 bytes
            0x41, 0x42  // Only 2 bytes present -> Truncated!
        )
        assertNull(BleBeaconPayload.parse(corruptPayload))
    }

    @Test
    fun testDefaultDiscoveryEngineLifecycleAndSelfFiltering() = runBlocking {
        var bleAdvertiserStarted = false
        var bleAdvertiserStopped = false
        var bleScannerStarted = false
        var bleScannerStopped = false
        var mdnsAdvertiserStarted = false
        var mdnsAdvertiserStopped = false
        var mdnsDiscoveryStarted = false
        var mdnsDiscoveryStopped = false

        var bleScanCallback: ((PeerDevice) -> Unit)? = null
        var mdnsPeerCallback: ((PeerDevice) -> Unit)? = null
        var mdnsLostCallback: ((String) -> Unit)? = null

        val fakeBleAdvertiser = object : BleAdvertiser {
            override val isAdvertising: Boolean get() = bleAdvertiserStarted
            override fun startAdvertising(deviceId: String, deviceName: String, port: Int, model: String, onSuccess: (() -> Unit)?, onError: ((Int) -> Unit)?) {
                bleAdvertiserStarted = true
                onSuccess?.invoke()
            }
            override fun stopAdvertising() {
                bleAdvertiserStopped = true
                bleAdvertiserStarted = false
            }
        }

        val fakeBleScanner = object : BleScanner {
            override val isScanning: Boolean get() = bleScannerStarted
            override fun startScan(onPeerDiscovered: (PeerDevice) -> Unit, onError: ((Int) -> Unit)?) {
                bleScannerStarted = true
                bleScanCallback = onPeerDiscovered
            }
            override fun stopScan() {
                bleScannerStopped = true
                bleScannerStarted = false
            }
        }

        val fakeMdnsManager = object : MdnsManager {
            override val isAdvertising: Boolean get() = mdnsAdvertiserStarted
            override val isDiscovering: Boolean get() = mdnsDiscoveryStarted
            override fun startAdvertising(deviceId: String, deviceName: String, port: Int, model: String, onSuccess: (() -> Unit)?, onError: ((Int) -> Unit)?) {
                mdnsAdvertiserStarted = true
                onSuccess?.invoke()
            }
            override fun stopAdvertising() {
                mdnsAdvertiserStopped = true
                mdnsAdvertiserStarted = false
            }
            override fun startDiscovery(onPeerDiscovered: (PeerDevice) -> Unit, onPeerLost: (String) -> Unit, onError: ((Int) -> Unit)?) {
                mdnsDiscoveryStarted = true
                mdnsPeerCallback = onPeerDiscovered
                mdnsLostCallback = onPeerLost
            }
            override fun stopDiscovery() {
                mdnsDiscoveryStopped = true
                mdnsDiscoveryStarted = false
            }
        }

        val selfId = "my-device-id"
        val engine = DefaultDiscoveryEngine(
            bleAdvertiser = fakeBleAdvertiser,
            bleScanner = fakeBleScanner,
            mdnsManager = fakeMdnsManager,
            deviceId = selfId
        )

        assertFalse(engine.isAdvertising)
        assertFalse(engine.isDiscovering)

        // 1. Test Advertising Start & Stop
        engine.startAdvertising("My Phone", 8989)
        assertTrue(engine.isAdvertising)
        assertTrue(bleAdvertiserStarted)
        assertTrue(mdnsAdvertiserStarted)

        // 2. Test Discovery Start
        engine.startDiscovery()
        assertTrue(engine.isDiscovering)
        assertTrue(bleScannerStarted)
        assertTrue(mdnsDiscoveryStarted)

        // 3. Test Self-Discovery Filtering: discovering self-broadcast must be ignored
        val selfPeer = PeerDevice(selfId, "My Phone", "Pixel", "192.168.1.2", 8989, 0, DiscoveryType.MDNS)
        mdnsPeerCallback?.invoke(selfPeer)
        assertEquals(0, engine.discoveredPeers.first().size)

        // 4. Test Remote Peer Discovery
        val remotePeer = PeerDevice("remote-id", "Remote Phone", "Galaxy", "192.168.1.3", 8989, 0, DiscoveryType.MDNS)
        mdnsPeerCallback?.invoke(remotePeer)
        assertEquals(1, engine.discoveredPeers.first().size)
        assertEquals("remote-id", engine.discoveredPeers.first()[0].id)

        // 5. Test Discovery Stop
        engine.stopDiscovery()
        assertFalse(engine.isDiscovering)
        assertTrue(bleScannerStopped)
        assertTrue(mdnsDiscoveryStopped)
        assertEquals(0, engine.discoveredPeers.first().size)

        // 6. Test Advertising Stop
        engine.stopAdvertising()
        assertFalse(engine.isAdvertising)
        assertTrue(bleAdvertiserStopped)
        assertTrue(mdnsAdvertiserStopped)
    }
}
