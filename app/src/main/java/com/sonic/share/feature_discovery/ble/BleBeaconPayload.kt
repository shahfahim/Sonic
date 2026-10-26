package com.sonic.share.feature_discovery.ble

import java.nio.ByteBuffer

/**
 * Sonic BLE advertisement beacon payload format.
 * Compact binary format designed to fit within the 24-byte payload limit
 * of legacy BLE advertising packets:
 * - 2 bytes: Magic ('S', 'N' -> 0x53, 0x4E)
 * - 1 byte: Version (1)
 * - 2 bytes: Port (UInt16, Big Endian)
 * - 1 byte: Device ID length
 * - N bytes: Device ID (UTF-8, max 16 bytes)
 * - 1 byte: Device Name length
 * - M bytes: Device Name (UTF-8, max 16 bytes)
 * - 1 byte: Device Model length
 * - K bytes: Device Model (UTF-8, max 16 bytes)
 */
data class BleBeaconPayload(
    val deviceId: String,
    val deviceName: String,
    val model: String = "Android",
    val port: Int
) {
    fun toByteArray(): ByteArray {
        val idRaw = deviceId.toByteArray(Charsets.UTF_8)
        val idBytes = if (idRaw.size > 16) idRaw.copyOf(16) else idRaw

        val nameRaw = deviceName.toByteArray(Charsets.UTF_8)
        val nameBytes = if (nameRaw.size > 16) nameRaw.copyOf(16) else nameRaw

        val modelRaw = model.toByteArray(Charsets.UTF_8)
        val modelBytes = if (modelRaw.size > 16) modelRaw.copyOf(16) else modelRaw

        val totalSize = 3 + 2 + 1 + idBytes.size + 1 + nameBytes.size + 1 + modelBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(MAGIC_1)
        buffer.put(MAGIC_2)
        buffer.put(VERSION)
        buffer.putShort(port.toShort())
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        buffer.put(modelBytes.size.toByte())
        buffer.put(modelBytes)
        return buffer.array()
    }

    companion object {
        const val MAGIC_1: Byte = 0x53 // 'S'
        const val MAGIC_2: Byte = 0x4E // 'N'
        const val VERSION: Byte = 1

        fun parse(bytes: ByteArray?): BleBeaconPayload? {
            if (bytes == null || bytes.size < 5) return null
            val buffer = ByteBuffer.wrap(bytes)
            val b1 = buffer.get()
            val b2 = buffer.get()
            if (b1 != MAGIC_1 || b2 != MAGIC_2) return null
            val version = buffer.get()
            if (version != VERSION) return null
            if (buffer.remaining() < 2) return null
            val port = buffer.short.toInt() and 0xFFFF

            val id = if (buffer.hasRemaining()) {
                val len = buffer.get().toInt() and 0xFF
                if (buffer.remaining() >= len) {
                    val arr = ByteArray(len)
                    buffer.get(arr)
                    String(arr, Charsets.UTF_8)
                } else ""
            } else ""

            val name = if (buffer.hasRemaining()) {
                val len = buffer.get().toInt() and 0xFF
                if (buffer.remaining() >= len) {
                    val arr = ByteArray(len)
                    buffer.get(arr)
                    String(arr, Charsets.UTF_8)
                } else ""
            } else ""

            val model = if (buffer.hasRemaining()) {
                val len = buffer.get().toInt() and 0xFF
                if (buffer.remaining() >= len) {
                    val arr = ByteArray(len)
                    buffer.get(arr)
                    String(arr, Charsets.UTF_8)
                } else ""
            } else ""

            return BleBeaconPayload(
                deviceId = id,
                deviceName = name,
                model = if (model.isNotBlank()) model else "Android",
                port = port
            )
        }
    }
}
