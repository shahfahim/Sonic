package com.sonic.share.core.network

import com.sonic.share.core.protocol.FrameCodec
import com.sonic.share.core.protocol.SonicFrame
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-throughput Java NIO ServerSocketChannel engine for receiving incoming AirDrop P2P connections and frames.
 *
 * Features:
 * - Non-blocking NIO ServerSocketChannel with 2 MB SO_RCVBUF / SO_SNDBUF tuning and TCP_NODELAY = true.
 * - Concurrent multi-client connection management.
 * - StreamPipeline integration per connection for continuous chunk accumulation and frame dispatch.
 * - Thread-safe lifecycle control (start, stop).
 */
class SocketChannelServer(
    val port: Int,
    private val codec: FrameCodec = FrameCodec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val onFrameReceived: (SonicFrame, SocketChannel) -> Unit
) {
    /**
     * Secondary convenience constructor matching the primary brief interface.
     */
    constructor(
        port: Int,
        onFrameReceived: (SonicFrame, SocketChannel) -> Unit
    ) : this(port, FrameCodec(), Dispatchers.IO, onFrameReceived)

    companion object {
        const val SOCKET_BUFFER_SIZE = 2 * 1024 * 1024 // 2 MB
    }

    private var serverChannel: ServerSocketChannel? = null
    private var selector: Selector? = null
    private var serverScope: CoroutineScope? = null
    private val isRunning = AtomicBoolean(false)
    private val activeConnections = ConcurrentHashMap.newKeySet<SocketChannel>()

    val localPort: Int
        get() = (serverChannel?.localAddress as? InetSocketAddress)?.port ?: port

    val isServerRunning: Boolean
        get() = isRunning.get() && serverChannel?.isOpen == true

    /**
     * Binds the server socket and begins listening for incoming client connections.
     */
    @Synchronized
    fun start() {
        if (isRunning.get()) return

        val ssc = ServerSocketChannel.open()
        ssc.configureBlocking(false)
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        if (ssc.supportedOptions().contains(StandardSocketOptions.SO_RCVBUF)) {
            ssc.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_SIZE)
        }
        ssc.bind(InetSocketAddress(port))

        val sel = Selector.open()
        ssc.register(sel, SelectionKey.OP_ACCEPT)

        serverChannel = ssc
        selector = sel
        isRunning.set(true)

        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        serverScope = scope

        scope.launch {
            acceptLoop(ssc, sel)
        }
    }

    /**
     * Closes all active connections and stops the server.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return

        selector?.wakeup()

        for (client in activeConnections) {
            try {
                client.close()
            } catch (_: IOException) {}
        }
        activeConnections.clear()

        try {
            selector?.close()
        } catch (_: IOException) {}
        selector = null

        try {
            serverChannel?.close()
        } catch (_: IOException) {}
        serverChannel = null

        serverScope?.cancel()
        serverScope = null
    }

    /**
     * Sends a [SonicFrame] to a specific connected [clientChannel].
     */
    suspend fun sendFrame(clientChannel: SocketChannel, frame: SonicFrame) = withContext(dispatcher) {
        val encoded = codec.encode(frame)
        val buffer = ByteBuffer.wrap(encoded)
        while (buffer.hasRemaining()) {
            if (!clientChannel.isOpen) {
                throw ClosedChannelException()
            }
            val written = clientChannel.write(buffer)
            if (written == 0) {
                delay(1)
            }
        }
    }

    private suspend fun acceptLoop(server: ServerSocketChannel, sel: Selector) = withContext(dispatcher) {
        while (isRunning.get() && server.isOpen) {
            val readyCount = try {
                sel.select(250)
            } catch (e: Exception) {
                break
            }

            if (!isRunning.get()) break
            if (readyCount == 0) continue

            val selectedKeys = sel.selectedKeys()
            val iterator = selectedKeys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()

                if (!key.isValid) continue

                if (key.isAcceptable) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        null
                    } ?: continue

                    handleNewConnection(client)
                }
            }
        }
    }

    private fun handleNewConnection(client: SocketChannel) {
        try {
            client.configureBlocking(false)
            client.setOption(StandardSocketOptions.SO_RCVBUF, SOCKET_BUFFER_SIZE)
            client.setOption(StandardSocketOptions.SO_SNDBUF, SOCKET_BUFFER_SIZE)
            client.setOption(StandardSocketOptions.TCP_NODELAY, true)
        } catch (e: IOException) {
            try { client.close() } catch (_: IOException) {}
            return
        }

        activeConnections.add(client)

        serverScope?.launch {
            val pipeline = StreamPipeline(codec)
            try {
                pipeline.readFrames(client) { frame ->
                    onFrameReceived(frame, client)
                }
            } catch (_: Exception) {
                // Client disconnected or read error
            } finally {
                activeConnections.remove(client)
                try { client.close() } catch (_: IOException) {}
            }
        }
    }
}
