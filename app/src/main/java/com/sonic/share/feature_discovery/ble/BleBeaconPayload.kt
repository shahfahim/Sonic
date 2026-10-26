package com.sonic.share.feature_discovery.ble

import java.nio.ByteBuffer

/**
 * Sonic BLE advertisement beacon payload format.
 * Ultra-compact binary format designed strictly to fit within the 31-byte limit
 * of legacy BLE advertising packets:
 * - 2 bytes: Magic ('S', 'N' -> 0x53, 0x4E)
 * - 2 bytes: Port (UInt16, Big Endian)
 * - 1 byte: Device ID length (max 8 bytes)
 * - N bytes: Short Device ID / Fingerprint (UTF-8, max 8 bytes)
 *
 * Optional scan response / extended fields:
 * - 1 byte: Device Name length
 * - M bytes: Device Name (UTF-8)
 * - 1 byte: Device Model length
 * - K bytes: Device Model (UTF-8)
 */
data class BleBeaconPayload(
    val deviceId: String,
    val deviceName: String = "",
    val model: String = "Android",
    val port: Int
) {
    fun toByteArray(): ByteArray {
        val idRaw = deviceId.toByteArray(Charsets.UTF_8)
        val idBytes = if (idRaw.size > MAX_ID_BYTES) idRaw.copyOf(MAX_ID_BYTES) else idRaw

        // Core primary beacon payload: 2 (magic) + 2 (port) + 1 (idLen) + idBytes.size <= 13 bytes
        val totalSize = 2 + 2 + 1 + idBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.put(MAGIC_1)
        buffer.put(MAGIC_2)
        buffer.putShort(port.toShort())
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        return buffer.array()
    }

    companion object {
        const val MAGIC_1: Byte = 0x53 // 'S'
        const val MAGIC_2: Byte = 0x4E // 'N'
        const val MAX_ID_BYTES: Int = 8

        fun parse(bytes: ByteArray?): BleBeaconPayload? {
            if (bytes == null || bytes.size < 5) return null
            val buffer = ByteBuffer.wrap(bytes)
            val b1 = buffer.get()
            val b2 = buffer.get()
            if (b1 != MAGIC_1 || b2 != MAGIC_2) return null
            if (buffer.remaining() < 2) return null
            val port = buffer.short.toInt() and 0xFFFF

            if (!buffer.hasRemaining()) return null
            val idLen = buffer.get().toInt() and 0xFF
            if (buffer.remaining() < idLen) return null // Desynchronization fix: return null immediately
            val idBytes = ByteArray(idLen)
            buffer.get(idBytes)
            val id = String(idBytes, Charsets.UTF_8)

            // Optional extended name field
            val name = if (buffer.hasRemaining()) {
                val nameLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < nameLen) return null // Truncated -> return null
                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                String(nameBytes, Charsets.UTF_8)
            } else ""

            // Optional extended model field
            val model = if (buffer.hasRemaining()) {
                val modelLen = buffer.get().toInt() and 0xFF
                if (buffer.remaining() < modelLen) return null // Truncated -> return null
                val modelBytes = ByteArray(modelLen)
                buffer.get(modelBytes)
                String(modelBytes, Charsets.UTF_8)
            } else "Android"

            return BleBeaconPayload(
                deviceId = id,
                deviceName = name,
                model = if (model.isNotBlank()) model else "Android",
                port = port
            )
        }
    }
}
