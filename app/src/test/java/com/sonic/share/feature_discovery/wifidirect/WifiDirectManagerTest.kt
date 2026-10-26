package com.sonic.share.feature_discovery.wifidirect

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.feature_discovery.PeerAggregator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WifiDirectManagerTest {

    private class FakeWifiP2pAdapter : WifiP2pAdapter {
        var initialized = false
        var prefer5GhzRequested: Boolean? = null
        var shouldSucceedCreateGroup = true
        var createGroupFailReason = 0
        var createGroupFailMessage = "P2P Error"

        var shouldSucceedConnect = true
        var connectFailReason = 0
        var connectFailMessage = "Connect Error"

        var groupInfoSsid: String? = "DIRECT-Sonic-Test"
        var groupInfoPassphrase: String? = "passphrase123"
        var groupInfoIsOwner: Boolean = true

        var connInfoIsOwner: Boolean = true
        var connInfoOwnerIp: String? = "192.168.49.1"

        var removeGroupCalled = false
        var cancelConnectCalled = false
        var receiverRegistered = false
        var receiverUnregistered = false

        var stateChangedCallback: ((Boolean) -> Unit)? = null
        var connectionChangedCallback: ((Boolean) -> Unit)? = null
        var deviceChangedCallback: ((String, String) -> Unit)? = null

        override fun initialize(onChannelDisconnected: () -> Unit) {
            initialized = true
        }

        override fun createGroup(
            prefer5GHz: Boolean,
            onSuccess: () -> Unit,
            onFailure: (reasonCode: Int, message: String) -> Unit
        ) {
            prefer5GhzRequested = prefer5GHz
            if (shouldSucceedCreateGroup) {
                onSuccess()
            } else {
                onFailure(createGroupFailReason, createGroupFailMessage)
            }
        }

        override fun requestGroupInfo(onGroupInfoAvailable: (ssid: String?, passphrase: String?, isOwner: Boolean) -> Unit) {
            onGroupInfoAvailable(groupInfoSsid, groupInfoPassphrase, groupInfoIsOwner)
        }

        override fun requestConnectionInfo(onConnectionInfoAvailable: (isOwner: Boolean, groupOwnerAddress: String?) -> Unit) {
            onConnectionInfoAvailable(connInfoIsOwner, connInfoOwnerIp)
        }

        override fun connect(
            ssid: String,
            passphrase: String,
            onSuccess: () -> Unit,
            onFailure: (reasonCode: Int, message: String) -> Unit
        ) {
            if (shouldSucceedConnect) {
                onSuccess()
            } else {
                onFailure(connectFailReason, connectFailMessage)
            }
        }

        override fun removeGroup(onComplete: (success: Boolean) -> Unit) {
            removeGroupCalled = true
            onComplete(true)
        }

        override fun cancelConnect(onComplete: (success: Boolean) -> Unit) {
            cancelConnectCalled = true
            onComplete(true)
        }

        override fun registerReceiver(
            onStateChanged: (enabled: Boolean) -> Unit,
            onConnectionChanged: (connected: Boolean) -> Unit,
            onThisDeviceChanged: (deviceName: String, deviceAddress: String) -> Unit
        ) {
            receiverRegistered = true
            stateChangedCallback = onStateChanged
            connectionChangedCallback = onConnectionChanged
            deviceChangedCallback = onThisDeviceChanged
        }

        override fun unregisterReceiver() {
            receiverUnregistered = true
        }
    }

    @Test
    fun testP2pConnectionStateTransitions() {
        val stateMachine = P2pStateMachine()
        assertEquals(P2pState.Disconnected, stateMachine.currentState)

        stateMachine.transition(P2pEvent.CreatingGroup)
        assertEquals(P2pState.CreatingGroup, stateMachine.currentState)

        stateMachine.transition(P2pEvent.GroupFormed(isOwner = true, ownerIp = "192.168.49.1"))
        assertTrue(stateMachine.currentState is P2pState.Connected)
        assertEquals("192.168.49.1", (stateMachine.currentState as P2pState.Connected).ownerIp)
    }

    @Test
    fun testP2pStateMachineClientConnectionAndFailureTransitions() {
        val stateMachine = P2pStateMachine()
        assertEquals(P2pState.Disconnected, stateMachine.currentState)

        stateMachine.transition(P2pEvent.Connecting("DIRECT-PeerNetwork"))
        val connecting = stateMachine.currentState
        assertTrue(connecting is P2pState.Connecting)
        assertEquals("DIRECT-PeerNetwork", (connecting as P2pState.Connecting).targetSsid)

        stateMachine.transition(
            P2pEvent.Connected(
                isOwner = false,
                ownerIp = "192.168.49.1",
                ssid = "DIRECT-PeerNetwork",
                passphrase = "password99"
            )
        )
        val connected = stateMachine.currentState
        assertTrue(connected is P2pState.Connected)
        val p2pConnected = connected as P2pState.Connected
        assertFalse(p2pConnected.isOwner)
        assertEquals("192.168.49.1", p2pConnected.ownerIp)
        assertEquals("DIRECT-PeerNetwork", p2pConnected.ssid)
        assertEquals("password99", p2pConnected.passphrase)

        stateMachine.transition(P2pEvent.Failure("Connection timed out"))
        val failed = stateMachine.currentState
        assertTrue(failed is P2pState.Failed)
        assertEquals("Connection timed out", (failed as P2pState.Failed).reason)

        stateMachine.reset()
        assertEquals(P2pState.Disconnected, stateMachine.currentState)
    }

    @Test
    fun testP2pStateMachineStateFlowEmissionAndCallback() = runBlocking {
        val stateMachine = P2pStateMachine()
        var lastCallbackState: P2pState? = null
        stateMachine.onStateChanged = { state ->
            lastCallbackState = state
        }

        assertEquals(P2pState.Disconnected, stateMachine.stateFlow.first())

        stateMachine.transition(P2pEvent.CreatingGroup)
        assertEquals(P2pState.CreatingGroup, stateMachine.stateFlow.first())
        assertEquals(P2pState.CreatingGroup, lastCallbackState)

        stateMachine.transition(P2pEvent.GroupFormed(isOwner = true, ownerIp = "192.168.49.1", ssid = "DIRECT-Test", passphrase = "123"))
        val state = stateMachine.stateFlow.first()
        assertTrue(state is P2pState.Connected)
        assertEquals(state, lastCallbackState)

        stateMachine.reset()
        assertEquals(P2pState.Disconnected, stateMachine.stateFlow.first())
        assertEquals(P2pState.Disconnected, lastCallbackState)
    }

    @Test
    fun testWifiDirectManagerCreateGroupSuccessWith5GHzPreference() {
        val fakeAdapter = FakeWifiP2pAdapter()
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter)

        var reportedSsid: String? = null
        var reportedPassphrase: String? = null
        var reportedIp: String? = null

        manager.createGroup(prefer5GHz = true) { ssid, passphrase, ip ->
            reportedSsid = ssid
            reportedPassphrase = passphrase
            reportedIp = ip
        }

        assertTrue(fakeAdapter.initialized)
        assertTrue(fakeAdapter.receiverRegistered)
        assertEquals(true, fakeAdapter.prefer5GhzRequested)

        assertEquals("DIRECT-Sonic-Test", reportedSsid)
        assertEquals("passphrase123", reportedPassphrase)
        assertEquals("192.168.49.1", reportedIp)

        assertTrue(manager.isConnected)
        assertTrue(manager.isGroupOwner)
        assertEquals("192.168.49.1", manager.groupOwnerIp)
    }

    @Test
    fun testWifiDirectManagerCreateGroupFailure() {
        val fakeAdapter = FakeWifiP2pAdapter().apply {
            shouldSucceedCreateGroup = false
            createGroupFailReason = 2
            createGroupFailMessage = "Wi-Fi Direct framework busy"
        }
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter)

        var failureReported: String? = null
        var successReported = false

        manager.createGroup(
            prefer5GHz = true,
            onFailure = { reason -> failureReported = reason },
            onSuccess = { _, _, _ -> successReported = true }
        )

        assertFalse(successReported)
        assertNotNull(failureReported)
        assertEquals("Wi-Fi Direct framework busy", failureReported)
        assertTrue(manager.currentState is P2pState.Failed)
        assertFalse(manager.isConnected)
    }

    @Test
    fun testWifiDirectManagerConnectToGroupSuccess() {
        val fakeAdapter = FakeWifiP2pAdapter().apply {
            connInfoIsOwner = false
            connInfoOwnerIp = "192.168.49.1"
        }
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter)

        var connectedIp: String? = null
        manager.connectToGroup("DIRECT-Sonic-Remote", "pass8888") { ip ->
            connectedIp = ip
        }

        assertTrue(fakeAdapter.initialized)
        assertTrue(fakeAdapter.receiverRegistered)
        assertEquals("192.168.49.1", connectedIp)

        assertTrue(manager.isConnected)
        assertFalse(manager.isGroupOwner)
        assertEquals("192.168.49.1", manager.groupOwnerIp)
    }

    @Test
    fun testWifiDirectManagerTeardown() {
        val fakeAdapter = FakeWifiP2pAdapter()
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter)

        manager.createGroup { _, _, _ -> }
        assertTrue(manager.isConnected)

        manager.teardown()

        assertTrue(fakeAdapter.cancelConnectCalled)
        assertTrue(fakeAdapter.removeGroupCalled)
        assertTrue(fakeAdapter.receiverUnregistered)
        assertEquals(P2pState.Disconnected, manager.currentState)
        assertFalse(manager.isConnected)
    }

    @Test
    fun testWifiDirectManagerPeerAggregatorIntegration() = runBlocking {
        val fakeAdapter = FakeWifiP2pAdapter()
        val aggregator = PeerAggregator()
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter, peerAggregator = aggregator)

        manager.createGroup { _, _, _ -> }

        val peers = aggregator.peers.first()
        assertEquals(1, peers.size)
        val peer = peers[0]
        assertEquals("p2p-owner", peer.id)
        assertEquals("192.168.49.1", peer.ipAddress)
        assertEquals(DiscoveryType.WIFI_DIRECT, peer.discoveryType)

        // Register custom connected client peer
        val clientPeer = PeerDevice("dev-client", "Client Phone", "Pixel 9", "192.168.49.200", 8989, -40, DiscoveryType.BLE)
        manager.registerConnectedPeer(clientPeer)

        val updatedPeers = aggregator.peers.first()
        assertEquals(2, updatedPeers.size)
        val registered = updatedPeers.first { it.id == "dev-client" }
        assertEquals(DiscoveryType.WIFI_DIRECT, registered.discoveryType)
        assertEquals("192.168.49.200", registered.ipAddress)
    }

    @Test
    fun testBroadcastReceiverWifiDisabledTriggersFailure() {
        val fakeAdapter = FakeWifiP2pAdapter()
        val manager = WifiDirectManager(p2pAdapter = fakeAdapter)

        var failureReported: String? = null
        manager.createGroup(
            onFailure = { reason -> failureReported = reason },
            onSuccess = { _, _, _ -> }
        )

        // Simulate Wi-Fi state broadcast turning off
        fakeAdapter.stateChangedCallback?.invoke(false)

        assertTrue(manager.currentState is P2pState.Failed)
        assertEquals("Wi-Fi Direct disabled by system", (manager.currentState as P2pState.Failed).reason)
        assertEquals("Wi-Fi Direct disabled by system", failureReported)
    }
}
