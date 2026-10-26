package com.sonic.share.core.protocol

import com.sonic.share.domain.model.TransferPayload

/**
 * Handshake frame exchanged at session initialization.
 * Contains device identity, protocol version, and ephemeral public key for ECDH.
 */
data class HandshakeFrame(
    val deviceId: String,
    val deviceName: String,
    val version: Int = 1,
    val publicKey: ByteArray,
    val deviceModel: String = "",
    val preferredPort: Int = 8989
) : SonicFrame(FrameType.HANDSHAKE) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HandshakeFrame) return false
        return deviceId == other.deviceId &&
            deviceName == other.deviceName &&
            version == other.version &&
            publicKey.contentEquals(other.publicKey) &&
            deviceModel == other.deviceModel &&
            preferredPort == other.preferredPort
    }

    override fun hashCode(): Int {
        var result = deviceId.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + version
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + deviceModel.hashCode()
        result = 31 * result + preferredPort
        return result
    }
}

/**
 * Manifest frame containing metadata for a batch of files to be transferred.
 */
data class ManifestFrame(
    val batchId: String,
    val files: List<TransferPayload>
) : SonicFrame(FrameType.MANIFEST) {
    val payloads: List<TransferPayload> get() = files
}

/**
 * Decision frame sent by the receiver to accept or decline an incoming transfer.
 */
data class DecisionFrame(
    val batchId: String,
    val accepted: Boolean,
    val reasonCode: String? = null
) : SonicFrame(FrameType.DECISION)

/**
 * High-speed binary chunk frame carrying a segment of file data.
 * Wire layout in body:
 * - fileIndex: 2 Bytes (UInt16, Big-Endian)
 * - chunkOffset: 8 Bytes (UInt64, Big-Endian)
 * - chunkLength: 4 Bytes (UInt32, Big-Endian)
 * - data: chunkLength Bytes
 */
data class DataChunkFrame(
    val fileIndex: Int,
    val chunkOffset: Long,
    val data: ByteArray
) : SonicFrame(FrameType.DATA_CHUNK) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DataChunkFrame) return false
        return fileIndex == other.fileIndex &&
            chunkOffset == other.chunkOffset &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = fileIndex
        result = 31 * result + chunkOffset.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Acknowledgment frame sent when a file's integrity (SHA-256) has been verified.
 */
data class FileAckFrame(
    val fileIndex: Int,
    val sha256: String
) : SonicFrame(FrameType.FILE_ACK)

/**
 * Terminal frame signaling the completion of the entire batch transfer.
 */
data class BatchCompleteFrame(
    val batchId: String
) : SonicFrame(FrameType.BATCH_COMPLETE)
