package com.sonic.share.feature_discovery

import android.content.Context
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.feature_discovery.ble.BleAdvertiser
import com.sonic.share.feature_discovery.ble.BleAdvertiserManager
import com.sonic.share.feature_discovery.ble.BleScanner
import com.sonic.share.feature_discovery.ble.BleScannerManager
import com.sonic.share.feature_discovery.mdns.MdnsDiscoveryManager
import com.sonic.share.feature_discovery.mdns.MdnsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

interface DiscoveryEngine {
    val discoveredPeers: StateFlow<List<PeerDevice>>
    fun startDiscovery()
    fun stopDiscovery()
    fun startAdvertising(deviceName: String, port: Int)
    fun stopAdvertising()
}

class PeerAggregator(
    private val peerTimeoutMs: Long = 30_000L
) {
    private val lock = Any()
    private val peerRecords = LinkedHashMap<String, PeerRecord>()
    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private data class PeerRecord(
        val device: PeerDevice,
        val lastSeen: Long
    )

    fun onPeerFound(peer: PeerDevice) {
        synchronized(lock) {
            val existingRecord = peerRecords[peer.id]
            val mergedDevice = if (existingRecord == null) {
                peer
            } else {
                val existing = existingRecord.device

                // DiscoveryType precedence: WIFI_DIRECT > MDNS > BLE
                val upgradedType = when {
                    peer.discoveryType == DiscoveryType.WIFI_DIRECT || existing.discoveryType == DiscoveryType.WIFI_DIRECT -> DiscoveryType.WIFI_DIRECT
                    peer.discoveryType == DiscoveryType.MDNS || existing.discoveryType == DiscoveryType.MDNS -> DiscoveryType.MDNS
                    else -> DiscoveryType.BLE
                }

                // IP resolution precedence: WIFI_DIRECT IP > MDNS IP > other
                // Strictly preserve WIFI_DIRECT IP so a local LAN mDNS IP never overwrites direct P2P connection IP
                val resolvedIp = when {
                    peer.discoveryType == DiscoveryType.WIFI_DIRECT && isValidIp(peer.ipAddress) -> peer.ipAddress
                    existing.discoveryType == DiscoveryType.WIFI_DIRECT && isValidIp(existing.ipAddress) -> existing.ipAddress
                    peer.discoveryType == DiscoveryType.MDNS && isValidIp(peer.ipAddress) -> peer.ipAddress
                    existing.discoveryType == DiscoveryType.MDNS && isValidIp(existing.ipAddress) -> existing.ipAddress
                    isValidIp(peer.ipAddress) -> peer.ipAddress
                    isValidIp(existing.ipAddress) -> existing.ipAddress
                    else -> peer.ipAddress
                }

                // Port resolution precedence follows connection type
                val resolvedPort = when {
                    peer.discoveryType == DiscoveryType.WIFI_DIRECT && peer.port > 0 -> peer.port
                    existing.discoveryType == DiscoveryType.WIFI_DIRECT && existing.port > 0 -> existing.port
                    peer.discoveryType == DiscoveryType.MDNS && peer.port > 0 -> peer.port
                    existing.discoveryType == DiscoveryType.MDNS && existing.port > 0 -> existing.port
                    peer.port > 0 -> peer.port
                    else -> existing.port
                }

                val resolvedRssi = if (peer.rssi != 0) peer.rssi else existing.rssi
                val resolvedName = if (peer.name.isNotBlank()) peer.name else existing.name
                val resolvedModel = if (peer.model.isNotBlank()) peer.model else existing.model

                PeerDevice(
                    id = peer.id,
                    name = resolvedName,
                    model = resolvedModel,
                    ipAddress = resolvedIp,
                    port = resolvedPort,
                    rssi = resolvedRssi,
                    discoveryType = upgradedType
                )
            }

            peerRecords[peer.id] = PeerRecord(mergedDevice, System.currentTimeMillis())
            _peers.value = peerRecords.values.map { it.device }
        }
    }

    fun onPeerLost(peerId: String) {
        synchronized(lock) {
            if (peerRecords.remove(peerId) != null) {
                _peers.value = peerRecords.values.map { it.device }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            peerRecords.clear()
            _peers.value = emptyList()
        }
    }

    /**
     * Evicts stale BLE proximity beacons older than peerTimeoutMs.
     * Peers discovered via mDNS or Wi-Fi Direct are NOT evicted by timer, as their lifecycle
     * is explicitly governed by network status and onPeerLost.
     */
    fun evictStalePeers(now: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            val staleIds = peerRecords.entries
                .filter { (_, record) ->
                    record.device.discoveryType == DiscoveryType.BLE && (now - record.lastSeen > peerTimeoutMs)
                }
                .map { it.key }

            if (staleIds.isNotEmpty()) {
                staleIds.forEach { peerRecords.remove(it) }
                _peers.value = peerRecords.values.map { it.device }
            }
            return staleIds.size
        }
    }

    fun getPeer(peerId: String): PeerDevice? {
        return synchronized(lock) {
            peerRecords[peerId]?.device
        }
    }

    private fun isValidIp(ip: String): Boolean {
        return ip.isNotBlank() && ip != "0.0.0.0" && ip != "::" && ip != "localhost"
    }
}

class DefaultDiscoveryEngine(
    private val bleAdvertiser: BleAdvertiser? = null,
    private val bleScanner: BleScanner? = null,
    private val mdnsManager: MdnsManager? = null,
    val aggregator: PeerAggregator = PeerAggregator(),
    val deviceId: String = UUID.randomUUID().toString().substring(0, 8),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : DiscoveryEngine {

    override val discoveredPeers: StateFlow<List<PeerDevice>> = aggregator.peers

    var isDiscovering: Boolean = false
        private set

    var isAdvertising: Boolean = false
        private set

    private var evictionJob: Job? = null

    override fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true

        bleScanner?.startScan(
            onPeerDiscovered = { peer ->
                // Filter out self-broadcasts
                if (peer.id != deviceId) {
                    aggregator.onPeerFound(peer)
                }
            }
        )

        mdnsManager?.startDiscovery(
            onPeerDiscovered = { peer ->
                // Filter out self-broadcasts
                if (peer.id != deviceId) {
                    aggregator.onPeerFound(peer)
                }
            },
            onPeerLost = { id ->
                if (id != deviceId) {
                    aggregator.onPeerLost(id)
                }
            }
        )

        evictionJob = scope.launch {
            while (isActive) {
                delay(5_000L)
                aggregator.evictStalePeers()
            }
        }
    }

    override fun stopDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false

        evictionJob?.cancel()
        evictionJob = null

        bleScanner?.stopScan()
        mdnsManager?.stopDiscovery()
        aggregator.clear()
    }

    override fun startAdvertising(deviceName: String, port: Int) {
        if (isAdvertising) stopAdvertising()
        isAdvertising = true

        bleAdvertiser?.startAdvertising(
            deviceId = deviceId,
            deviceName = deviceName,
            port = port
        )

        mdnsManager?.startAdvertising(
            deviceId = deviceId,
            deviceName = deviceName,
            port = port
        )
    }

    override fun stopAdvertising() {
        if (!isAdvertising) return
        isAdvertising = false

        bleAdvertiser?.stopAdvertising()
        mdnsManager?.stopAdvertising()
    }

    companion object {
        fun create(
            context: Context,
            deviceId: String = UUID.randomUUID().toString().substring(0, 8)
        ): DefaultDiscoveryEngine {
            return DefaultDiscoveryEngine(
                bleAdvertiser = BleAdvertiserManager(),
                bleScanner = BleScannerManager(),
                mdnsManager = MdnsDiscoveryManager(context),
                aggregator = PeerAggregator(),
                deviceId = deviceId
            )
        }
    }
}
