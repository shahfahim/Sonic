package com.sonic.share.domain.model

sealed class TransferState {
    data object Idle : TransferState()
    data object Connecting : TransferState()
    data object Handshaking : TransferState()
    data class Transferring(
        val progress: Float,
        val speedBytesPerSec: Long,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : TransferState()
    data object Completed : TransferState()
    data class Failed(val reason: String) : TransferState()
}

data class TransferSession(
    val sessionId: String,
    val peer: PeerDevice,
    val payloads: List<TransferPayload>,
    val state: TransferState = TransferState.Idle,
    val isIncoming: Boolean = false,
    val startTimeMs: Long = System.currentTimeMillis()
)
