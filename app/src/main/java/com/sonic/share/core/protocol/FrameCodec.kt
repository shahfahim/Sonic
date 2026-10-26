package com.sonic.share.core.protocol

import com.sonic.share.domain.model.TransferPayload
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * Binary wire codec for Sonic P2P protocol frames.
 *
 * Wire Framing Format:
 * +--------------------+------------------+------------------------------+---------------------------+
 * | Magic Bytes (4B)   | Frame Type (1B)  | Payload Length (4B, UInt32)  | Body Payload (N Bytes)    |
 * | 0x53 0x4F 0x4E 0x43| 0x01 .. 0x06     | Big-Endian Byte Length       | JSON or Raw Binary Chunks |
 * +--------------------+------------------+------------------------------+---------------------------+
 */
class FrameCodec {

    companion object {
        const val MAGIC = 0x534F4E43 // "SONC"
        const val HEADER_SIZE = 9
        const val DATA_CHUNK_HEADER_SIZE = 14 // fileIndex (2B) + chunkOffset (8B) + chunkLength (4B)
    }

    /**
     * Serializes a [SonicFrame] into its wire binary representation.
     */
    fun encode(frame: SonicFrame): ByteArray {
        return when (frame) {
            is DataChunkFrame -> encodeDataChunkFrame(frame)
            is HandshakeFrame -> encodeControlFrame(frame.type, encodeHandshakeBody(frame))
            is ManifestFrame -> encodeControlFrame(frame.type, encodeManifestBody(frame))
            is DecisionFrame -> encodeControlFrame(frame.type, encodeDecisionBody(frame))
            is FileAckFrame -> encodeControlFrame(frame.type, encodeFileAckBody(frame))
            is BatchCompleteFrame -> encodeControlFrame(frame.type, encodeBatchCompleteBody(frame))
        }
    }

    /**
     * Decodes a [SonicFrame] from the incoming [buffer].
     *
     * Returns the decoded frame, or `null` if the buffer contains insufficient data or an invalid header.
     * If decoding fails or is incomplete, the buffer position is left unchanged.
     */
    fun decode(buffer: ByteBuffer): SonicFrame? {
        if (buffer.remaining() < HEADER_SIZE) return null

        val startPos = buffer.position()
        val originalOrder = buffer.order()
        buffer.order(ByteOrder.BIG_ENDIAN)

        try {
            val magic = buffer.int
            if (magic != MAGIC) {
                buffer.position(startPos)
                return null
            }

            val typeCode = buffer.get()
            val frameType = FrameType.fromCode(typeCode) ?: run {
                buffer.position(startPos)
                return null
            }

            val payloadLength = buffer.int
            if (payloadLength < 0 || buffer.remaining() < payloadLength) {
                buffer.position(startPos)
                return null
            }

            return when (frameType) {
                FrameType.DATA_CHUNK -> {
                    if (payloadLength < DATA_CHUNK_HEADER_SIZE) {
                        buffer.position(startPos)
                        return null
                    }
                    val fileIndex = buffer.short.toInt() and 0xFFFF
                    val chunkOffset = buffer.long
                    val chunkLength = buffer.int
                    if (chunkLength < 0 || chunkLength > payloadLength - DATA_CHUNK_HEADER_SIZE || buffer.remaining() < chunkLength) {
                        buffer.position(startPos)
                        return null
                    }
                    val chunkData = ByteArray(chunkLength)
                    buffer.get(chunkData)
                    val remainingInPayload = (payloadLength - DATA_CHUNK_HEADER_SIZE) - chunkLength
                    if (remainingInPayload > 0) {
                        buffer.position(buffer.position() + remainingInPayload)
                    }
                    DataChunkFrame(fileIndex, chunkOffset, chunkData)
                }

                FrameType.HANDSHAKE,
                FrameType.MANIFEST,
                FrameType.DECISION,
                FrameType.FILE_ACK,
                FrameType.BATCH_COMPLETE -> {
                    val payloadBytes = ByteArray(payloadLength)
                    buffer.get(payloadBytes)
                    val decoded = decodeControlFrame(frameType, payloadBytes)
                    if (decoded == null) {
                        buffer.position(startPos)
                    }
                    decoded
                }
            }
        } catch (e: Exception) {
            buffer.position(startPos)
            return null
        } finally {
            buffer.order(originalOrder)
        }
    }

    private fun encodeDataChunkFrame(frame: DataChunkFrame): ByteArray {
        val payloadLength = DATA_CHUNK_HEADER_SIZE + frame.data.size
        val totalSize = HEADER_SIZE + payloadLength
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Header
        buffer.putInt(MAGIC)
        buffer.put(FrameType.DATA_CHUNK.code)
        buffer.putInt(payloadLength)

        // Body
        buffer.putShort(frame.fileIndex.toShort())
        buffer.putLong(frame.chunkOffset)
        buffer.putInt(frame.data.size)
        buffer.put(frame.data)

        return buffer.array()
    }

    private fun encodeControlFrame(type: FrameType, bodyBytes: ByteArray): ByteArray {
        val totalSize = HEADER_SIZE + bodyBytes.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(MAGIC)
        buffer.put(type.code)
        buffer.putInt(bodyBytes.size)
        buffer.put(bodyBytes)

        return buffer.array()
    }

    private fun encodeHandshakeBody(frame: HandshakeFrame): ByteArray {
        val map = mutableMapOf<String, Any?>(
            "deviceId" to frame.deviceId,
            "deviceName" to frame.deviceName,
            "version" to frame.version,
            "publicKey" to Base64.getEncoder().encodeToString(frame.publicKey),
            "deviceModel" to frame.deviceModel,
            "preferredPort" to frame.preferredPort
        )
        return MiniJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    private fun encodeManifestBody(frame: ManifestFrame): ByteArray {
        val files = frame.files.map { file ->
            val fileMap = mutableMapOf<String, Any?>(
                "id" to file.id,
                "name" to file.name,
                "size" to file.size,
                "mimeType" to file.mimeType,
                "sha256" to file.sha256
            )
            file.uriString?.let { fileMap["uriString"] = it }
            fileMap
        }
        val map = mapOf(
            "batchId" to frame.batchId,
            "files" to files
        )
        return MiniJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    private fun encodeDecisionBody(frame: DecisionFrame): ByteArray {
        val map = mutableMapOf<String, Any?>(
            "batchId" to frame.batchId,
            "accepted" to frame.accepted
        )
        frame.reasonCode?.let { map["reasonCode"] = it }
        return MiniJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    private fun encodeFileAckBody(frame: FileAckFrame): ByteArray {
        val map = mapOf(
            "fileIndex" to frame.fileIndex,
            "sha256" to frame.sha256
        )
        return MiniJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    private fun encodeBatchCompleteBody(frame: BatchCompleteFrame): ByteArray {
        val map = mapOf("batchId" to frame.batchId)
        return MiniJson.stringify(map).toByteArray(Charsets.UTF_8)
    }

    @Suppress("UNCHECKED_CAST")
    private fun decodeControlFrame(type: FrameType, payloadBytes: ByteArray): SonicFrame? {
        val jsonString = String(payloadBytes, Charsets.UTF_8)
        val parsed = MiniJson.parse(jsonString) as? Map<String, Any?> ?: return null

        return when (type) {
            FrameType.HANDSHAKE -> {
                val deviceId = parsed["deviceId"] as? String ?: return null
                val deviceName = parsed["deviceName"] as? String ?: return null
                val version = (parsed["version"] as? Number)?.toInt() ?: 1
                val publicKeyB64 = parsed["publicKey"] as? String ?: return null
                val publicKey = try {
                    Base64.getDecoder().decode(publicKeyB64)
                } catch (e: Exception) {
                    return null
                }
                val deviceModel = (parsed["deviceModel"] as? String) ?: ""
                val preferredPort = (parsed["preferredPort"] as? Number)?.toInt() ?: 8989
                HandshakeFrame(deviceId, deviceName, version, publicKey, deviceModel, preferredPort)
            }

            FrameType.MANIFEST -> {
                val batchId = parsed["batchId"] as? String ?: return null
                val filesList = parsed["files"] as? List<*> ?: emptyList<Any?>()
                val files = filesList.mapNotNull { item ->
                    val m = item as? Map<String, Any?> ?: return@mapNotNull null
                    val id = m["id"] as? String ?: return@mapNotNull null
                    val name = m["name"] as? String ?: return@mapNotNull null
                    val size = (m["size"] as? Number)?.toLong() ?: 0L
                    val mimeType = m["mimeType"] as? String ?: "application/octet-stream"
                    val sha256 = m["sha256"] as? String ?: ""
                    val uriString = m["uriString"] as? String
                    TransferPayload(id, name, size, mimeType, sha256, uriString)
                }
                ManifestFrame(batchId, files)
            }

            FrameType.DECISION -> {
                val batchId = parsed["batchId"] as? String ?: return null
                val accepted = parsed["accepted"] as? Boolean ?: false
                val reasonCode = parsed["reasonCode"] as? String
                DecisionFrame(batchId, accepted, reasonCode)
            }

            FrameType.FILE_ACK -> {
                val fileIndex = (parsed["fileIndex"] as? Number)?.toInt() ?: return null
                val sha256 = parsed["sha256"] as? String ?: return null
                FileAckFrame(fileIndex, sha256)
            }

            FrameType.BATCH_COMPLETE -> {
                val batchId = parsed["batchId"] as? String ?: return null
                BatchCompleteFrame(batchId)
            }

            FrameType.DATA_CHUNK -> null
        }
    }
}

/**
 * Lightweight, zero-dependency JSON serializer and parser.
 */
internal object MiniJson {

    fun stringify(value: Any?): String = buildString { appendValue(this, value) }

    private fun appendValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Number -> sb.append(value)
            is String -> {
                sb.append('"')
                for (c in value) {
                    when (c) {
                        '\\' -> sb.append("\\\\")
                        '"' -> sb.append("\\\"")
                        '\b' -> sb.append("\\b")
                        '\u000C' -> sb.append("\\f")
                        '\n' -> sb.append("\\n")
                        '\r' -> sb.append("\\r")
                        '\t' -> sb.append("\\t")
                        else -> {
                            if (c.code < 0x20) {
                                sb.append(String.format("\\u%04x", c.code))
                            } else {
                                sb.append(c)
                            }
                        }
                    }
                }
                sb.append('"')
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (v == null) continue
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, k.toString())
                    sb.append(':')
                    appendValue(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    appendValue(sb, item)
                }
                sb.append(']')
            }
            else -> appendValue(sb, value.toString())
        }
    }

    fun parse(json: String): Any? {
        val parser = Parser(json)
        val result = parser.parseValue()
        parser.skipWhitespace()
        return result
    }

    private class Parser(private val src: String) {
        private var idx = 0

        fun skipWhitespace() {
            while (idx < src.length && src[idx].isWhitespace()) {
                idx++
            }
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (idx >= src.length) return null
            return when (val ch = src[idx]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> {
                    if (ch == '-' || ch in '0'..'9') parseNumber()
                    else throw IllegalArgumentException("Unexpected char '$ch' at index $idx")
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            idx++ // consume '{'
            val map = mutableMapOf<String, Any?>()
            skipWhitespace()
            if (idx < src.length && src[idx] == '}') {
                idx++
                return map
            }
            while (idx < src.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (idx >= src.length || src[idx] != ':') throw IllegalArgumentException("Expected ':' at $idx")
                idx++ // consume ':'
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (idx < src.length && src[idx] == ',') {
                    idx++
                } else if (idx < src.length && src[idx] == '}') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or '}' at $idx")
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            idx++ // consume '['
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (idx < src.length && src[idx] == ']') {
                idx++
                return list
            }
            while (idx < src.length) {
                val value = parseValue()
                list.add(value)
                skipWhitespace()
                if (idx < src.length && src[idx] == ',') {
                    idx++
                } else if (idx < src.length && src[idx] == ']') {
                    idx++
                    break
                } else {
                    throw IllegalArgumentException("Expected ',' or ']' at $idx")
                }
            }
            return list
        }

        private fun parseString(): String {
            if (src[idx] != '"') throw IllegalArgumentException("Expected '\"' at $idx")
            idx++ // consume opening quote
            val sb = StringBuilder()
            while (idx < src.length) {
                val c = src[idx++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (idx >= src.length) throw IllegalArgumentException("Unterminated escape")
                    when (val esc = src[idx++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (idx + 4 > src.length) throw IllegalArgumentException("Malformed unicode escape")
                            val hex = src.substring(idx, idx + 4)
                            sb.append(hex.toInt(16).toChar())
                            idx += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseBoolean(): Boolean {
            if (src.startsWith("true", idx)) {
                idx += 4
                return true
            }
            if (src.startsWith("false", idx)) {
                idx += 5
                return false
            }
            throw IllegalArgumentException("Invalid boolean at $idx")
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", idx)) {
                idx += 4
                return null
            }
            throw IllegalArgumentException("Invalid null at $idx")
        }

        private fun parseNumber(): Number {
            val start = idx
            if (src[idx] == '-') idx++
            while (idx < src.length && src[idx] in '0'..'9') idx++
            var isFloating = false
            if (idx < src.length && src[idx] == '.') {
                isFloating = true
                idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            if (idx < src.length && (src[idx] == 'e' || src[idx] == 'E')) {
                isFloating = true
                idx++
                if (idx < src.length && (src[idx] == '+' || src[idx] == '-')) idx++
                while (idx < src.length && src[idx] in '0'..'9') idx++
            }
            val numStr = src.substring(start, idx)
            return if (isFloating) {
                numStr.toDouble()
            } else {
                numStr.toLongOrNull() ?: numStr.toDouble()
            }
        }
    }
}
