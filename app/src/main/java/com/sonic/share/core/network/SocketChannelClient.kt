package com.sonic.share.core.network

import com.sonic.share.core.protocol.FrameCodec
import com.sonic.share.core.protocol.SonicFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * High-throughput Java NIO client for initiating outbound AirDrop P2P connections and frame streaming.
 *
 * Configured with 2 MB send/receive buffers, TCP_NODELAY = true, and non-blocking I/O.
 */
class SocketChannelClient(
    private val codec: FrameCodec = FrameCodec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5000L
    }

    /**
     * Connects to the remote peer at [host]:[port] with non-blocking NIO and tuned socket options.
     *
     * @param host Remote peer IP address or hostname
     * @param port Remote peer listening port
     * @param timeoutMs Maximum milliseconds to wait for connection completion
     * @return Fully connected [SocketChannel]
     * @throws SocketTimeoutException if connection is not established within [timeoutMs]
     * @throws IOException on connection failure
     */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS
    ): SocketChannel = withContext(dispatcher) {
        val channel = SocketChannel.open()
        try {
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_SIZE)
            channel.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER_SIZE)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true)

            val address = InetSocketAddress(host, port)
            val connectedImmediately = channel.connect(address)

            if (!connectedImmediately) {
                val selector = Selector.open()
                try {
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    val startTime = System.currentTimeMillis()
                    var connected = false

                    while (!connected) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val remaining = timeoutMs - elapsed
                        if (remaining <= 0) {
                            throw SocketTimeoutException("Connection to $host:$port timed out after ${timeoutMs}ms")
                        }

                        val ready = selector.select(remaining.coerceAtLeast(10))
                        if (ready > 0) {
                            val keys = selector.selectedKeys().iterator()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                keys.remove()
                                if (key.isValid && key.isConnectable) {
                                    if (channel.finishConnect()) {
                                        connected = true
                                        break
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    try { selector.close() } catch (_: Exception) {}
                }
            }
            channel
        } catch (e: Exception) {
            try { channel.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Serializes [frame] with [FrameCodec] and streams all bytes over [channel].
     * Handles partial NIO writes in non-blocking mode.
     */
    suspend fun sendFrame(channel: SocketChannel, frame: SonicFrame) = withContext(dispatcher) {
        val bytes = codec.encode(frame)
        val buffer = ByteBuffer.wrap(bytes)

        while (buffer.hasRemaining()) {
            if (!channel.isOpen) {
                throw ClosedChannelException()
            }
            val written = channel.write(buffer)
            if (written == 0) {
                delay(1)
            }
        }
    }

    /**
     * Convenience method to send multiple frames consecutively over [channel].
     */
    suspend fun sendFrames(channel: SocketChannel, frames: List<SonicFrame>) {
        for (frame in frames) {
            sendFrame(channel, frame)
        }
    }

    /**
     * Reads a single incoming [SonicFrame] from [channel] using an internal [StreamPipeline].
     * Suspends until a complete frame is received or [timeoutMs] elapses.
     */
    suspend fun receiveFrame(channel: SocketChannel, timeoutMs: Long = 5000L): SonicFrame? = withContext(dispatcher) {
        val pipeline = StreamPipeline(codec)
        val startTime = System.currentTimeMillis()

        if (channel.isBlocking) {
            while (channel.isOpen && !pipeline.isEndOfStream) {
                val frames = pipeline.readAvailable(channel)
                if (frames.isNotEmpty()) return@withContext frames.first()
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    throw SocketTimeoutException("Timed out waiting for frame")
                }
                delay(5)
            }
        } else {
            val selector = Selector.open()
            try {
                channel.register(selector, SelectionKey.OP_READ)
                while (channel.isOpen && !pipeline.isEndOfStream) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val remaining = timeoutMs - elapsed
                    if (remaining <= 0) {
                        throw SocketTimeoutException("Timed out waiting for frame")
                    }

                    val ready = selector.select(remaining.coerceAtLeast(10))
                    if (ready > 0) {
                        selector.selectedKeys().clear()
                        val frames = pipeline.readAvailable(channel)
                        if (frames.isNotEmpty()) {
                            return@withContext frames.first()
                        }
                    }
                }
            } finally {
                try { selector.close() } catch (_: Exception) {}
            }
        }
        null
    }
}
