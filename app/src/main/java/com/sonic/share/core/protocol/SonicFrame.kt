package com.sonic.share.core.protocol

/**
 * Binary wire frame type identifiers.
 */
enum class FrameType(val code: Byte) {
    HANDSHAKE(0x01),
    MANIFEST(0x02),
    DECISION(0x03),
    DATA_CHUNK(0x04),
    FILE_ACK(0x05),
    BATCH_COMPLETE(0x06);

    companion object {
        fun fromCode(code: Byte): FrameType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Base sealed class for all Sonic wire protocol frames.
 */
sealed class SonicFrame(val type: FrameType)
