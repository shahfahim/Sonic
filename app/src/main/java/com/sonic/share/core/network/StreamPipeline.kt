package com.sonic.share.core.network

import com.sonic.share.core.protocol.FrameCodec
import com.sonic.share.core.protocol.SonicFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * High-performance streaming pipeline for continuous chunk accumulation and frame decoding.
 *
 * Designed to handle the streaming nature of TCP/NIO:
 * - Accumulates incoming bytes across arbitrary read boundaries.
 * - Extracts and dispatches multiple back-to-back frames from a single buffer.
 * - Safely buffers partial frames until all bytes are received.
 * - Detects and resynchronizes past corrupted/noise bytes before valid MAGIC headers.
 * - Automatically expands its internal buffer to accommodate large frames (e.g. 2 MB DataChunkFrames).
 */
class StreamPipeline(
    private val codec: FrameCodec = FrameCodec(),
    initialCapacity: Int = DEFAULT_BUFFER_CAPACITY
) {
    companion object {
        const val DEFAULT_BUFFER_CAPACITY = 2 * 1024 * 1024 // 2 MB
        const val READ_CHUNK_SIZE = 64 * 1024 // 64 KB read buffer
    }

    private var buffer: ByteBuffer = ByteBuffer.allocate(initialCapacity).apply {
        order(ByteOrder.BIG_ENDIAN)
    }

    var isEndOfStream: Boolean = false
        private set

    val bufferedBytes: Int
        @Synchronized
        get() = buffer.position()

    val capacity: Int
        @Synchronized
        get() = buffer.capacity()

    /**
     * Appends [length] bytes from [data] starting at [offset] into the pipeline
     * and decodes all available complete [SonicFrame]s.
     */
    @Synchronized
    fun push(data: ByteArray, offset: Int = 0, length: Int = data.size): List<SonicFrame> {
        if (length <= 0) return emptyList()
        ensureCapacity(length)
        buffer.put(data, offset, length)
        return decodeAvailableFrames()
    }

    /**
     * Appends remaining bytes from [source] into the pipeline and decodes all available complete frames.
     */
    @Synchronized
    fun push(source: ByteBuffer): List<SonicFrame> {
        val remaining = source.remaining()
        if (remaining <= 0) return emptyList()
        ensureCapacity(remaining)
        buffer.put(source)
        return decodeAvailableFrames()
    }

    /**
     * Reads available bytes from [channel] into the accumulation buffer and decodes
     * any completed [SonicFrame]s.
     *
     * In non-blocking mode, returns immediately with any decoded frames.
     * Returns an empty list if no bytes were available or only partial frames were read.
     * Sets [isEndOfStream] to true if the channel has reached end-of-stream (-1).
     */
    @Synchronized
    fun readAvailable(channel: SocketChannel): List<SonicFrame> {
        if (!channel.isOpen) return emptyList()
        ensureCapacity(READ_CHUNK_SIZE)
        val bytesRead: Int
        try {
            bytesRead = channel.read(buffer)
        } catch (e: ClosedChannelException) {
            isEndOfStream = true
            return emptyList()
        }

        if (bytesRead == -1) {
            isEndOfStream = true
            return emptyList()
        }

        if (bytesRead == 0) {
            return emptyList()
        }

        return decodeAvailableFrames()
    }

    /**
     * Continuously reads frames from [channel] on [Dispatchers.IO] and passes each decoded frame
     * to [onFrame]. Suspends until the channel is closed or [isEndOfStream] is reached.
     */
    suspend fun readFrames(
        channel: SocketChannel,
        onFrame: suspend (SonicFrame) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (channel.isBlocking) {
            while (channel.isOpen && !isEndOfStream) {
                val frames = readAvailable(channel)
                for (frame in frames) {
                    onFrame(frame)
                }
            }
        } else {
            val selector = Selector.open()
            try {
                channel.register(selector, SelectionKey.OP_READ)
                while (channel.isOpen && !isEndOfStream) {
                    val count = selector.select(250)
                    if (count > 0) {
                        val keys = selector.selectedKeys().iterator()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            keys.remove()
                            if (key.isValid && key.isReadable) {
                                val frames = readAvailable(channel)
                                for (frame in frames) {
                                    onFrame(frame)
                                }
                            }
                        }
                    }
                }
            } finally {
                try { selector.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Returns a cold [Flow] that emits [SonicFrame]s as they are decoded from [channel].
     */
    fun frameFlow(channel: SocketChannel): Flow<SonicFrame> = flow {
        readFrames(channel) { frame ->
            emit(frame)
        }
    }

    /**
     * Clears all accumulated data in the buffer.
     */
    @Synchronized
    fun clear() {
        buffer.clear()
        isEndOfStream = false
    }

    /**
     * Ensures that [buffer] has at least [requiredSpace] bytes available for writing.
     * If not, allocates a larger buffer and transfers existing unread bytes.
     */
    private fun ensureCapacity(requiredSpace: Int) {
        if (buffer.remaining() < requiredSpace) {
            val currentPos = buffer.position()
            val newCapacity = maxOf(buffer.capacity() * 2, currentPos + requiredSpace)
            val newBuffer = ByteBuffer.allocate(newCapacity).apply {
                order(ByteOrder.BIG_ENDIAN)
            }
            buffer.flip()
            newBuffer.put(buffer)
            buffer = newBuffer
        }
    }

    /**
     * Decodes all complete frames currently available in [buffer].
     * Compacts remaining unconsumed bytes to the start of the buffer.
     */
    private fun decodeAvailableFrames(): List<SonicFrame> {
        buffer.flip()
        val decodedFrames = mutableListOf<SonicFrame>()

        try {
            while (buffer.remaining() >= FrameCodec.HEADER_SIZE) {
                val startPos = buffer.position()
                val magic = buffer.getInt(startPos)
                if (magic != FrameCodec.MAGIC) {
                    // Stream noise or corruption: discard 1 byte and resynchronize
                    buffer.get()
                    continue
                }

                val frame = codec.decode(buffer)
                if (frame != null) {
                    decodedFrames.add(frame)
                } else {
                    // Frame is incomplete; wait for additional bytes
                    break
                }
            }
        } finally {
            buffer.compact()
        }

        return decodedFrames
    }
}
