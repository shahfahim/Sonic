package com.sonic.share.feature_discovery.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import java.util.concurrent.ConcurrentHashMap

interface MdnsManager {
    val isAdvertising: Boolean
    val isDiscovering: Boolean
    fun startAdvertising(
        deviceId: String,
        deviceName: String,
        port: Int,
        model: String = "Android",
        onSuccess: (() -> Unit)? = null,
        onError: ((Int) -> Unit)? = null
    )
    fun stopAdvertising()
    fun startDiscovery(
        onPeerDiscovered: (PeerDevice) -> Unit,
        onPeerLost: (String) -> Unit,
        onError: ((Int) -> Unit)? = null
    )
    fun stopDiscovery()
}

class MdnsDiscoveryManager(
    private val context: Context? = null,
    private val nsdManager: NsdManager? = try {
        context?.getSystemService(Context.NSD_SERVICE) as? NsdManager
    } catch (_: Throwable) {
        null
    }
) : MdnsManager {

    companion object {
        private const val TAG = "MdnsDiscoveryManager"
        const val SERVICE_TYPE = "_sonic._tcp."
        const val SERVICE_TYPE_BASE = "_sonic._tcp"
    }

    override var isAdvertising: Boolean = false
        private set

    override var isDiscovering: Boolean = false
        private set

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    // Cache to map dynamic Nsd serviceName to actual peerId (handles Android mDNS name-conflict renaming)
    private val serviceNameToPeerId = ConcurrentHashMap<String, String>()

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    override fun startAdvertising(
        deviceId: String,
        deviceName: String,
        port: Int,
        model: String,
        onSuccess: (() -> Unit)?,
        onError: ((Int) -> Unit)?
    ) {
        val nsd = nsdManager
        if (nsd == null) {
            safeLogW(TAG, "NsdManager unavailable for advertising")
            onError?.invoke(-1)
            return
        }

        if (isAdvertising) {
            stopAdvertising()
        }

        val cleanName = deviceName.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }.trim().take(32).ifBlank { "Sonic" }
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$cleanName-$deviceId"
            serviceType = SERVICE_TYPE_BASE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
            setAttribute("model", model)
            setAttribute("port", port.toString())
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                safeLogD(TAG, "mDNS service registered: ${registeredService.serviceName}")
                isAdvertising = true
                onSuccess?.invoke()
            }

            override fun onRegistrationFailed(failedService: NsdServiceInfo, errorCode: Int) {
                safeLogE(TAG, "mDNS registration failed for ${failedService.serviceName}: code $errorCode")
                isAdvertising = false
                onError?.invoke(errorCode)
            }

            override fun onServiceUnregistered(unregisteredService: NsdServiceInfo) {
                safeLogD(TAG, "mDNS service unregistered: ${unregisteredService.serviceName}")
                isAdvertising = false
            }

            override fun onUnregistrationFailed(failedService: NsdServiceInfo, errorCode: Int) {
                safeLogE(TAG, "mDNS unregistration failed for ${failedService.serviceName}: code $errorCode")
            }
        }

        registrationListener = listener
        try {
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            safeLogE(TAG, "Failed to register mDNS service", e)
            isAdvertising = false
            onError?.invoke(-1)
        }
    }

    override fun stopAdvertising() {
        val listener = registrationListener
        val nsd = nsdManager
        if (listener != null && nsd != null) {
            try {
                nsd.unregisterService(listener)
            } catch (e: Throwable) {
                safeLogW(TAG, "Error unregistering mDNS service", e)
            }
        }
        registrationListener = null
        isAdvertising = false
    }

    override fun startDiscovery(
        onPeerDiscovered: (PeerDevice) -> Unit,
        onPeerLost: (String) -> Unit,
        onError: ((Int) -> Unit)?
    ) {
        val nsd = nsdManager
        if (nsd == null) {
            safeLogW(TAG, "NsdManager unavailable for discovery")
            onError?.invoke(-1)
            return
        }

        if (isDiscovering) {
            stopDiscovery()
        }

        // Acquire Wi-Fi MulticastLock: Android drops mDNS multicasts (224.0.0.251) on physical hardware unless held
        try {
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("sonic_mdns_lock")?.apply {
                    setReferenceCounted(true)
                }
            }
            multicastLock?.acquire()
            safeLogD(TAG, "MulticastLock acquired for mDNS discovery")
        } catch (e: Throwable) {
            safeLogW(TAG, "Failed to acquire MulticastLock", e)
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                safeLogD(TAG, "mDNS discovery started for type $regType")
                isDiscovering = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                safeLogD(TAG, "mDNS service found: ${serviceInfo.serviceName}, type=${serviceInfo.serviceType}")
                val sType = serviceInfo.serviceType ?: ""
                if (sType.contains(SERVICE_TYPE_BASE) || sType.isEmpty()) {
                    enqueueResolve(serviceInfo, onPeerDiscovered)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                safeLogD(TAG, "mDNS service lost: ${serviceInfo.serviceName}")
                // Look up cached peerId first to handle Android name collisions cleanly
                val cachedId = serviceNameToPeerId.remove(serviceInfo.serviceName)
                val peerId = cachedId
                    ?: serviceInfo.attributes?.get("id")?.let { String(it, Charsets.UTF_8) }
                    ?: extractIdFromName(serviceInfo.serviceName)
                onPeerLost(peerId)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                safeLogD(TAG, "mDNS discovery stopped for type $serviceType")
                isDiscovering = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                safeLogE(TAG, "mDNS start discovery failed with code: $errorCode")
                isDiscovering = false
                onError?.invoke(errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                safeLogE(TAG, "mDNS stop discovery failed with code: $errorCode")
            }
        }

        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            safeLogE(TAG, "Failed to start mDNS service discovery", e)
            isDiscovering = false
            onError?.invoke(-1)
        }
    }

    override fun stopDiscovery() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                safeLogD(TAG, "MulticastLock released")
            }
        } catch (e: Throwable) {
            safeLogW(TAG, "Failed to release MulticastLock", e)
        }

        val listener = discoveryListener
        val nsd = nsdManager
        if (listener != null && nsd != null) {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (e: Throwable) {
                safeLogW(TAG, "Error stopping mDNS discovery", e)
            }
        }
        discoveryListener = null
        isDiscovering = false
        serviceNameToPeerId.clear()
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo, onResolved: (PeerDevice) -> Unit) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(serviceInfo)
            if (!isResolving) {
                processNextResolve(onResolved)
            }
        }
    }

    private fun processNextResolve(onResolved: (PeerDevice) -> Unit) {
        val next = synchronized(resolveQueue) {
            if (resolveQueue.isEmpty()) {
                isResolving = false
                null
            } else {
                isResolving = true
                resolveQueue.removeFirst()
            }
        } ?: return

        val nsd = nsdManager
        if (nsd == null) {
            synchronized(resolveQueue) {
                isResolving = false
            }
            return
        }

        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    safeLogW(TAG, "Resolve failed for ${serviceInfo?.serviceName}: error $errorCode")
                    synchronized(resolveQueue) {
                        processNextResolve(onResolved)
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    try {
                        if (resolved != null) {
                            val ip = resolved.host?.hostAddress
                            val port = resolved.port
                            if (!ip.isNullOrBlank() && port > 0) {
                                val attrs = resolved.attributes ?: emptyMap()
                                val id = attrs["id"]?.let { String(it, Charsets.UTF_8) }
                                    ?: extractIdFromName(resolved.serviceName)
                                val name = attrs["name"]?.let { String(it, Charsets.UTF_8) }
                                    ?: extractNameFromName(resolved.serviceName)
                                val model = attrs["model"]?.let { String(it, Charsets.UTF_8) }
                                    ?: "Android"

                                // Cache serviceName -> peerId mapping for accurate onServiceLost handling
                                serviceNameToPeerId[resolved.serviceName] = id

                                val peer = PeerDevice(
                                    id = id,
                                    name = name,
                                    model = model,
                                    ipAddress = ip,
                                    port = port,
                                    rssi = 0,
                                    discoveryType = DiscoveryType.MDNS
                                )
                                onResolved(peer)
                            }
                        }
                    } finally {
                        synchronized(resolveQueue) {
                            processNextResolve(onResolved)
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            safeLogE(TAG, "Exception resolving service ${next.serviceName}", e)
            synchronized(resolveQueue) {
                processNextResolve(onResolved)
            }
        }
    }

    private fun extractIdFromName(serviceName: String): String {
        val dashIndex = serviceName.lastIndexOf('-')
        return if (dashIndex != -1 && dashIndex < serviceName.length - 1) {
            serviceName.substring(dashIndex + 1)
        } else {
            serviceName
        }
    }

    private fun extractNameFromName(serviceName: String): String {
        val dashIndex = serviceName.lastIndexOf('-')
        return if (dashIndex != -1) {
            serviceName.substring(0, dashIndex)
        } else {
            serviceName
        }
    }

    private fun safeLogD(tag: String, msg: String) {
        try { Log.d(tag, msg) } catch (_: Throwable) { }
    }

    private fun safeLogW(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.w(tag, msg, tr) } catch (_: Throwable) { }
    }

    private fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
        try { Log.e(tag, msg, tr) } catch (_: Throwable) { }
    }
}
