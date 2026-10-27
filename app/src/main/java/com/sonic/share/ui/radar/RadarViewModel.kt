package com.sonic.share.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.domain.model.TransferPayload
import com.sonic.share.domain.model.TransferSession
import com.sonic.share.domain.model.TransferState
import com.sonic.share.feature_discovery.DiscoveryEngine
import com.sonic.share.feature_transfer.domain.TransferManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State representing the AirDrop-style radar UI.
 */
data class RadarUiState(
    val discoveredPeers: List<PeerDevice> = emptyList(),
    val selectedPeer: PeerDevice? = null,
    val activeTransfer: TransferSession? = null,
    val incomingRequest: TransferSession? = null,
    val isScanning: Boolean = true
)

/**
 * ViewModel orchestrating nearby peer discovery, AirDrop radar ripples,
 * file transfer sessions, and incoming transfer authorization prompts.
 */
class RadarViewModel(
    val discoveryEngine: DiscoveryEngine,
    val transferManager: TransferManager? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(RadarUiState(isScanning = true))
    val uiState: StateFlow<RadarUiState> = _uiState.asStateFlow()

    private var activeTransferJob: Job? = null
    private var peerCollectorJob: Job? = null
    private var incomingSessionJob: Job? = null

    init {
        startScanning()
        observeDiscoveredPeers()
        observeIncomingTransfers()
    }

    private fun observeDiscoveredPeers() {
        peerCollectorJob?.cancel()
        peerCollectorJob = viewModelScope.launch {
            discoveryEngine.discoveredPeers.collect { peers ->
                _uiState.update { current ->
                    current.copy(discoveredPeers = peers)
                }
            }
        }
    }

    private fun observeIncomingTransfers() {
        if (transferManager == null) return
        incomingSessionJob?.cancel()
        incomingSessionJob = viewModelScope.launch {
            transferManager.incomingSession.collect { session ->
                _uiState.update { current ->
                    when {
                        session == null -> current.copy(
                            incomingRequest = null,
                            activeTransfer = if (current.activeTransfer?.isIncoming == true) null else current.activeTransfer
                        )
                        session.isIncoming && session.state is TransferState.Idle -> current.copy(
                            incomingRequest = session
                        )
                        else -> current.copy(
                            incomingRequest = null,
                            activeTransfer = session
                        )
                    }
                }
            }
        }
    }

    fun selectPeer(peer: PeerDevice) {
        _uiState.update { it.copy(selectedPeer = peer) }
    }

    fun clearSelectedPeer() {
        _uiState.update { it.copy(selectedPeer = null) }
    }

    fun toggleScanning() {
        if (_uiState.value.isScanning) {
            stopScanning()
        } else {
            startScanning()
        }
    }

    fun startScanning() {
        _uiState.update { it.copy(isScanning = true) }
        discoveryEngine.startDiscovery()
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
        discoveryEngine.stopDiscovery()
    }

    /**
     * Initiates an outbound file transfer batch to the specified [peer].
     */
    fun startTransfer(peer: PeerDevice, payloads: List<TransferPayload>) {
        val totalBytes = payloads.sumOf { it.size }
        val session = TransferSession(
            sessionId = UUID.randomUUID().toString(),
            peer = peer,
            payloads = payloads,
            state = TransferState.Connecting,
            isIncoming = false
        )
        _uiState.update { it.copy(activeTransfer = session) }

        if (transferManager != null) {
            activeTransferJob?.cancel()
            activeTransferJob = viewModelScope.launch {
                transferManager.sendBatch(peer, payloads).collect { state ->
                    _uiState.update { current ->
                        current.copy(
                            activeTransfer = current.activeTransfer?.copy(state = state)
                        )
                    }
                }
            }
        }
    }

    /**
     * Authorizes an incoming transfer request, alerting the sender and starting file reassembly.
     */
    fun acceptIncomingTransfer() {
        val request = _uiState.value.incomingRequest ?: return
        _uiState.update { it.copy(incomingRequest = null) }
        transferManager?.acceptBatch(request.sessionId)
    }

    /**
     * Declines an incoming transfer request and cleans up the session prompt.
     */
    fun declineIncomingTransfer() {
        val request = _uiState.value.incomingRequest ?: return
        _uiState.update { it.copy(incomingRequest = null) }
        transferManager?.declineBatch(request.sessionId)
    }

    /**
     * Cancels any in-flight outbound or inbound transfer session.
     */
    fun cancelActiveTransfer() {
        activeTransferJob?.cancel()
        activeTransferJob = null
        _uiState.update { it.copy(activeTransfer = null) }
    }

    /**
     * Dismisses the active transfer HUD when a transfer is completed or failed.
     */
    fun dismissTransfer() {
        _uiState.update { it.copy(activeTransfer = null) }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryEngine.stopDiscovery()
        activeTransferJob?.cancel()
        peerCollectorJob?.cancel()
        incomingSessionJob?.cancel()
    }
}
