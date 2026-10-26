package com.sonic.share.feature_discovery.wifidirect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import com.sonic.share.feature_discovery.DefaultDiscoveryEngine
import com.sonic.share.feature_discovery.DiscoveryEngine
import com.sonic.share.feature_discovery.PeerAggregator
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

interface WifiP2pAdapter {
    fun initialize(onChannelDisconnected: () -> Unit = {})
    fun createGroup(
        prefer5GHz: Boolean,
        onSuccess: () -> Unit,
        onFailure: (reasonCode: Int, message: String) -> Unit
    )
    fun requestGroupInfo(onGroupInfoAvailable: (ssid: String?, passphrase: String?, isOwner: Boolean) -> Unit)
    fun requestConnectionInfo(onConnectionInfoAvailable: (isOwner: Boolean, groupOwnerAddress: String?) -> Unit)
    fun connect(
        ssid: String,
        passphrase: String,
        onSuccess: () -> Unit,
        onFailure: (reasonCode: Int, message: String) -> Unit
    )
    fun removeGroup(onComplete: (success: Boolean) -> Unit = {})
    fun cancelConnect(onComplete: (success: Boolean) -> Unit = {})
    fun registerReceiver(
        onStateChanged: (enabled: Boolean) -> Unit,
        onConnectionChanged: (connected: Boolean) -> Unit,
        onThisDeviceChanged: (deviceName: String, deviceAddress: String) -> Unit
    )
    fun unregisterReceiver()
}

@Suppress("DEPRECATION")
open class AndroidWifiP2pAdapter(
    private val context: Context?
) : WifiP2pAdapter {

    private val wifiP2pManager: WifiP2pManager? = try {
        context?.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    } catch (e: Exception) {
        null
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered: Boolean = false

    override fun initialize(onChannelDisconnected: () -> Unit) {
        if (wifiP2pManager != null && context != null && channel == null) {
            try {
                channel = wifiP2pManager.initialize(context, context.mainLooper) {
                    channel = null
                    onChannelDisconnected()
                }
            } catch (e: Exception) {
                channel = null
            }
        }
    }

    override fun createGroup(
        prefer5GHz: Boolean,
        onSuccess: () -> Unit,
        onFailure: (reasonCode: Int, message: String) -> Unit
    ) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onFailure(WifiP2pManager.ERROR, "Wi-Fi Direct manager or channel not initialized")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && prefer5GHz) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .build()

                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        // Fallback to standard 2.4GHz / Auto group creation if 5GHz fails
                        createGroupStandard(mgr, ch, onSuccess, onFailure)
                    }
                })
            } catch (t: Throwable) {
                createGroupStandard(mgr, ch, onSuccess, onFailure)
            }
        } else {
            createGroupStandard(mgr, ch, onSuccess, onFailure)
        }
    }

    private fun createGroupStandard(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onSuccess: () -> Unit,
        onFailure: (reasonCode: Int, message: String) -> Unit
    ) {
        try {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onSuccess()
                }

                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        // Stale group might exist, clean up and retry once
                        try {
                            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() {
                                    mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                                        override fun onSuccess() {
                                            onSuccess()
                                        }

                                        override fun onFailure(retryReason: Int) {
                                            onFailure(retryReason, p2pErrorString(retryReason))
                                        }
                                    })
                                }

                                override fun onFailure(removeReason: Int) {
                                    onFailure(reason, p2pErrorString(reason))
                                }
                            })
                        } catch (e: Exception) {
                            onFailure(reason, p2pErrorString(reason))
                        }
                    } else {
                        onFailure(reason, p2pErrorString(reason))
                    }
                }
            })
        } catch (e: Exception) {
            onFailure(WifiP2pManager.ERROR, e.message ?: "Failed to invoke createGroup")
        }
    }

    override fun requestGroupInfo(onGroupInfoAvailable: (ssid: String?, passphrase: String?, isOwner: Boolean) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onGroupInfoAvailable(null, null, false)
            return
        }
        try {
            mgr.requestGroupInfo(ch) { group ->
                if (group != null) {
                    onGroupInfoAvailable(group.networkName, group.passphrase, group.isGroupOwner)
                } else {
                    onGroupInfoAvailable(null, null, false)
                }
            }
        } catch (e: Exception) {
            onGroupInfoAvailable(null, null, false)
        }
    }

    override fun requestConnectionInfo(onConnectionInfoAvailable: (isOwner: Boolean, groupOwnerAddress: String?) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onConnectionInfoAvailable(false, null)
            return
        }
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null) {
                    val ip = info.groupOwnerAddress?.hostAddress
                    onConnectionInfoAvailable(info.isGroupOwner, ip)
                } else {
                    onConnectionInfoAvailable(false, null)
                }
            }
        } catch (e: Exception) {
            onConnectionInfoAvailable(false, null)
        }
    }

    override fun connect(
        ssid: String,
        passphrase: String,
        onSuccess: () -> Unit,
        onFailure: (reasonCode: Int, message: String) -> Unit
    ) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onFailure(WifiP2pManager.ERROR, "Wi-Fi Direct manager or channel not initialized")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(ssid)
                    .setPassphrase(passphrase)
                    .build()

                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        onSuccess()
                    }

                    override fun onFailure(reason: Int) {
                        onFailure(reason, p2pErrorString(reason))
                    }
                })
            } catch (t: Throwable) {
                onFailure(WifiP2pManager.ERROR, t.message ?: "Failed to build P2P connect config")
            }
        } else {
            onFailure(WifiP2pManager.P2P_UNSUPPORTED, "Direct SSID/passphrase connect requires Android 10+")
        }
    }

    override fun removeGroup(onComplete: (success: Boolean) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onComplete(false)
            return
        }
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onComplete(true)
                override fun onFailure(reason: Int) = onComplete(false)
            })
        } catch (e: Exception) {
            onComplete(false)
        }
    }

    override fun cancelConnect(onComplete: (success: Boolean) -> Unit) {
        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            onComplete(false)
            return
        }
        try {
            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onComplete(true)
                override fun onFailure(reason: Int) = onComplete(false)
            })
        } catch (e: Exception) {
            onComplete(false)
        }
    }

    override fun registerReceiver(
        onStateChanged: (enabled: Boolean) -> Unit,
        onConnectionChanged: (connected: Boolean) -> Unit,
        onThisDeviceChanged: (deviceName: String, deviceAddress: String) -> Unit
    ) {
        if (isReceiverRegistered || context == null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        onStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        onConnectionChanged(networkInfo?.isConnected == true)
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        if (device != null) {
                            onThisDeviceChanged(device.deviceName ?: "", device.deviceAddress ?: "")
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, intentFilter)
            }
            isReceiverRegistered = true
        } catch (e: Exception) {
            // Ignore in restricted environments
        }
    }

    override fun unregisterReceiver() {
        if (isReceiverRegistered && context != null && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignore
            }
            receiver = null
            isReceiverRegistered = false
        }
    }

    private fun p2pErrorString(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "Internal framework error"
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported on this device"
        WifiP2pManager.BUSY -> "Wi-Fi Direct framework busy"
        else -> "Wi-Fi Direct error code $reason"
    }
}

class WifiDirectManager(
    val context: Context? = null,
    val discoveryEngine: DiscoveryEngine? = null,
    val p2pAdapter: WifiP2pAdapter = AndroidWifiP2pAdapter(context),
    val stateMachine: P2pStateMachine = P2pStateMachine(),
    val peerAggregator: PeerAggregator? = (discoveryEngine as? DefaultDiscoveryEngine)?.aggregator
) {
    companion object {
        const val DEFAULT_P2P_OWNER_IP = "192.168.49.1"
        const val DEFAULT_P2P_PORT = 8989
    }

    val currentState: P2pState get() = stateMachine.currentState
    val connectionState: StateFlow<P2pState> get() = stateMachine.stateFlow
    val isConnected: Boolean get() = currentState is P2pState.Connected
    val isGroupOwner: Boolean get() = (currentState as? P2pState.Connected)?.isOwner == true
    val groupOwnerIp: String? get() = (currentState as? P2pState.Connected)?.ownerIp

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun notifyPeerDiscovered(peer: PeerDevice) {
        peerAggregator?.onPeerFound(peer)
            ?: (discoveryEngine as? DefaultDiscoveryEngine)?.aggregator?.onPeerFound(peer)
    }

    fun createGroup(
        onSuccess: (ssid: String, passphrase: String, ip: String) -> Unit
    ) = createGroup(prefer5GHz = true, onFailure = null, onSuccess = onSuccess)

    fun createGroup(
        prefer5GHz: Boolean = true,
        onFailure: ((reason: String) -> Unit)? = null,
        onSuccess: (ssid: String, passphrase: String, ip: String) -> Unit
    ) {
        stateMachine.transition(P2pEvent.CreatingGroup)
        p2pAdapter.initialize()
        p2pAdapter.registerReceiver(
            onStateChanged = { enabled ->
                if (!enabled && stateMachine.currentState !is P2pState.Disconnected) {
                    val err = "Wi-Fi Direct disabled by system"
                    stateMachine.transition(P2pEvent.Failure(err))
                    onFailure?.invoke(err)
                }
            },
            onConnectionChanged = { connected ->
                if (connected && stateMachine.currentState is P2pState.CreatingGroup) {
                    queryAndPublishGroupInfo(onSuccess)
                }
            },
            onThisDeviceChanged = { _, _ -> }
        )

        p2pAdapter.createGroup(
            prefer5GHz = prefer5GHz,
            onSuccess = {
                queryAndPublishGroupInfo(onSuccess)
            },
            onFailure = { _, errorMsg ->
                stateMachine.transition(P2pEvent.Failure(errorMsg))
                onFailure?.invoke(errorMsg)
            }
        )
    }

    private fun queryAndPublishGroupInfo(
        onSuccess: (ssid: String, passphrase: String, ip: String) -> Unit
    ) {
        p2pAdapter.requestGroupInfo { ssid, passphrase, _ ->
            p2pAdapter.requestConnectionInfo { _, groupOwnerAddress ->
                val ownerIp = groupOwnerAddress?.takeIf { it.isNotBlank() } ?: DEFAULT_P2P_OWNER_IP
                val resolvedSsid = ssid?.takeIf { it.isNotBlank() } ?: "DIRECT-Sonic-${UUID.randomUUID().toString().take(4)}"
                val resolvedPassphrase = passphrase ?: ""

                stateMachine.transition(
                    P2pEvent.GroupFormed(
                        isOwner = true,
                        ownerIp = ownerIp,
                        ssid = resolvedSsid,
                        passphrase = resolvedPassphrase
                    )
                )

                notifyPeerDiscovered(
                    PeerDevice(
                        id = "p2p-owner",
                        name = "Sonic Group Owner",
                        model = "Local P2P GO",
                        ipAddress = ownerIp,
                        port = DEFAULT_P2P_PORT,
                        rssi = 0,
                        discoveryType = DiscoveryType.WIFI_DIRECT
                    )
                )

                onSuccess(resolvedSsid, resolvedPassphrase, ownerIp)
            }
        }
    }

    fun connectToGroup(
        ssid: String,
        passphrase: String,
        onConnected: (groupOwnerIp: String) -> Unit
    ) = connectToGroup(ssid, passphrase, onFailure = null, onConnected = onConnected)

    fun connectToGroup(
        ssid: String,
        passphrase: String,
        onFailure: ((reason: String) -> Unit)? = null,
        onConnected: (groupOwnerIp: String) -> Unit
    ) {
        stateMachine.transition(P2pEvent.Connecting(ssid))
        p2pAdapter.initialize()
        p2pAdapter.registerReceiver(
            onStateChanged = { enabled ->
                if (!enabled && stateMachine.currentState !is P2pState.Disconnected) {
                    val err = "Wi-Fi Direct disabled by system"
                    stateMachine.transition(P2pEvent.Failure(err))
                    onFailure?.invoke(err)
                }
            },
            onConnectionChanged = { connected ->
                if (connected && stateMachine.currentState is P2pState.Connecting) {
                    queryAndPublishConnection(ssid, passphrase, onConnected)
                }
            },
            onThisDeviceChanged = { _, _ -> }
        )

        p2pAdapter.connect(
            ssid = ssid,
            passphrase = passphrase,
            onSuccess = {
                queryAndPublishConnection(ssid, passphrase, onConnected)
            },
            onFailure = { _, _ ->
                // Fallback to SoftAP / Wi-Fi Network Specifier connection
                connectViaWifiSpecifier(ssid, passphrase, onFailure, onConnected)
            }
        )
    }

    private fun queryAndPublishConnection(
        ssid: String,
        passphrase: String,
        onConnected: (groupOwnerIp: String) -> Unit
    ) {
        p2pAdapter.requestConnectionInfo { isOwner, groupOwnerAddress ->
            val ownerIp = groupOwnerAddress?.takeIf { it.isNotBlank() } ?: DEFAULT_P2P_OWNER_IP
            stateMachine.transition(
                P2pEvent.Connected(
                    isOwner = isOwner,
                    ownerIp = ownerIp,
                    ssid = ssid,
                    passphrase = passphrase
                )
            )

            notifyPeerDiscovered(
                PeerDevice(
                    id = "p2p-peer",
                    name = "Sonic Group Host",
                    model = "Remote P2P Peer",
                    ipAddress = ownerIp,
                    port = DEFAULT_P2P_PORT,
                    rssi = -30,
                    discoveryType = DiscoveryType.WIFI_DIRECT
                )
            )

            onConnected(ownerIp)
        }
    }

    private fun connectViaWifiSpecifier(
        ssid: String,
        passphrase: String,
        onFailure: ((String) -> Unit)?,
        onConnected: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && context != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm == null) {
                val err = "ConnectivityManager not available for SoftAP fallback"
                stateMachine.transition(P2pEvent.Failure(err))
                onFailure?.invoke(err)
                return
            }

            try {
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(passphrase)
                    .build()

                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        try {
                            cm.bindProcessToNetwork(network)
                        } catch (e: Exception) {
                            // Ignore bind failure if unsupported
                        }
                        val ownerIp = DEFAULT_P2P_OWNER_IP
                        stateMachine.transition(
                            P2pEvent.Connected(
                                isOwner = false,
                                ownerIp = ownerIp,
                                ssid = ssid,
                                passphrase = passphrase
                            )
                        )
                        notifyPeerDiscovered(
                            PeerDevice(
                                id = "softap-owner",
                                name = "Sonic SoftAP Host",
                                model = "P2P Host",
                                ipAddress = ownerIp,
                                port = DEFAULT_P2P_PORT,
                                rssi = -30,
                                discoveryType = DiscoveryType.WIFI_DIRECT
                            )
                        )
                        onConnected(ownerIp)
                    }

                    override fun onUnavailable() {
                        val err = "SoftAP connection unavailable or timed out"
                        stateMachine.transition(P2pEvent.Failure(err))
                        onFailure?.invoke(err)
                    }
                }

                networkCallback = callback
                cm.requestNetwork(request, callback)
            } catch (t: Throwable) {
                val err = "SoftAP connection error: ${t.message}"
                stateMachine.transition(P2pEvent.Failure(err))
                onFailure?.invoke(err)
            }
        } else {
            val err = "SoftAP connect requires Android 10+ with active Context"
            stateMachine.transition(P2pEvent.Failure(err))
            onFailure?.invoke(err)
        }
    }

    fun registerConnectedPeer(peer: PeerDevice) {
        val wfdPeer = if (peer.discoveryType == DiscoveryType.WIFI_DIRECT) {
            peer
        } else {
            peer.copy(discoveryType = DiscoveryType.WIFI_DIRECT)
        }
        notifyPeerDiscovered(wfdPeer)
    }

    fun teardown() {
        p2pAdapter.cancelConnect()
        p2pAdapter.removeGroup()
        p2pAdapter.unregisterReceiver()

        if (networkCallback != null && context != null) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            try {
                cm?.bindProcessToNetwork(null)
                cm?.unregisterNetworkCallback(networkCallback!!)
            } catch (e: Exception) {
                // Ignore
            }
            networkCallback = null
        }

        stateMachine.reset()
    }
}
