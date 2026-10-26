package com.sonic.share.domain.model

enum class DiscoveryType {
    BLE,
    MDNS,
    WIFI_DIRECT
}

data class PeerDevice(
    val id: String,
    val name: String,
    val model: String,
    val ipAddress: String,
    val port: Int,
    val rssi: Int,
    val discoveryType: DiscoveryType
)
