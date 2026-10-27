package com.sonic.share.ui.radar

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.domain.model.TransferPayload
import com.sonic.share.domain.model.TransferSession
import com.sonic.share.domain.model.TransferState
import com.sonic.share.feature_discovery.DiscoveryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakeDiscoveryEngine : DiscoveryEngine {
    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    var isDiscoveryStarted: Boolean = false
        private set
    var isAdvertisingStarted: Boolean = false
        private set

    fun emitPeers(peers: List<PeerDevice>) {
        _discoveredPeers.value = peers
    }

    override fun startDiscovery() {
        isDiscoveryStarted = true
    }

    override fun stopDiscovery() {
        isDiscoveryStarted = false
    }

    override fun startAdvertising(deviceName: String, port: Int) {
        isAdvertisingStarted = true
    }

    override fun stopAdvertising() {
        isAdvertisingStarted = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RadarViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRadarViewModelFiltersAndEmitsPeers() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        val peer = PeerDevice("p1", "MacBook", "Laptop", "192.168.1.10", 8989, -45, DiscoveryType.MDNS)
        fakeDiscovery.emitPeers(listOf(peer))

        val state = viewModel.uiState.first { it.discoveredPeers.isNotEmpty() }
        assertEquals(1, state.discoveredPeers.size)
        assertEquals("MacBook", state.discoveredPeers[0].name)
        assertEquals("Laptop", state.discoveredPeers[0].model)
    }

    @Test
    fun testSelectAndClearPeer() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        val peer = PeerDevice("p1", "Pixel 9", "Phone", "192.168.1.15", 8989, -30, DiscoveryType.WIFI_DIRECT)
        viewModel.selectPeer(peer)

        assertEquals(peer, viewModel.uiState.value.selectedPeer)

        viewModel.clearSelectedPeer()
        assertNull(viewModel.uiState.value.selectedPeer)
    }

    @Test
    fun testToggleScanning() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        assertTrue(fakeDiscovery.isDiscoveryStarted)
        assertTrue(viewModel.uiState.value.isScanning)

        viewModel.toggleScanning()
        assertFalse(fakeDiscovery.isDiscoveryStarted)
        assertFalse(viewModel.uiState.value.isScanning)

        viewModel.toggleScanning()
        assertTrue(fakeDiscovery.isDiscoveryStarted)
        assertTrue(viewModel.uiState.value.isScanning)
    }

    @Test
    fun testCancelActiveTransferClearsTransfer() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        val peer = PeerDevice("p1", "iPad Pro", "Tablet", "192.168.1.20", 8989, -40, DiscoveryType.MDNS)
        val payloads = listOf(TransferPayload("f1", "doc.pdf", 1024L, "application/pdf", "dummyhash"))

        viewModel.startTransfer(peer, payloads)
        assertNotNull(viewModel.uiState.value.activeTransfer)

        viewModel.cancelActiveTransfer()
        assertNull(viewModel.uiState.value.activeTransfer)
    }

    @Test
    fun testDismissTransferClearsCompletedTransfer() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        val peer = PeerDevice("p1", "Galaxy Tab", "Tablet", "192.168.1.25", 8989, -35, DiscoveryType.BLE)
        val payloads = listOf(TransferPayload("f2", "image.png", 2048L, "image/png", "dummyhash"))

        viewModel.startTransfer(peer, payloads)
        assertEquals(peer, viewModel.uiState.value.activeTransfer?.peer)

        viewModel.dismissTransfer()
        assertNull(viewModel.uiState.value.activeTransfer)
    }
}
