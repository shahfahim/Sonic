package com.sonic.share.feature_transfer.domain

import android.content.Context
import android.net.Uri
import com.sonic.share.core.crypto.SonicCryptoEngine
import com.sonic.share.core.network.SocketChannelClient
import com.sonic.share.core.network.SocketChannelServer
import com.sonic.share.core.protocol.*
import com.sonic.share.domain.model.*
import com.sonic.share.feature_transfer.data.ChunkedFileWriter
import com.sonic.share.feature_transfer.data.StorageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * End-to-end transfer coordinator for Sonic AirDrop-style P2P file transfers.
 *
 * Coordinates:
 * - Handshake and ephemeral ECDH key derivation via [SonicCryptoEngine].
 * - Manifest exchange and transfer decision negotiation ([acceptBatch], [declineBatch]).
 * - High-speed 1 MB bounded chunk streaming encrypted with AES-256-GCM.
 * - Outbound and inbound write serialization via per-channel [Mutex] to eliminate socket write interleaving.
 * - Real-time moving average speed and progress tracking.
 * - Resumable temporary file storage and Scoped Storage commitment via [StorageManager].
 * - SHA-256 end-to-end integrity verification and [FileAckFrame] acknowledgments.
 */
class TransferManager(
    private val context: Context? = null,
    private val client: SocketChannelClient = SocketChannelClient(),
    private var server: SocketChannelServer? = null,
    private val cryptoEngine: SonicCryptoEngine = SonicCryptoEngine(),
    private val storageManager: StorageManager? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val localDeviceId: String = "sonic-${UUID.randomUUID().toString().take(8)}",
    private val localDeviceName: String = "Sonic Device",
    private val localDeviceModel: String = "Android",
    private val localPort: Int = 8989,
    private val autoAccept: Boolean = false,
    private val payloadStreamProvider: ((TransferPayload) -> InputStream?)? = null
) {
    companion object {
        const val CHUNK_SIZE = 1024 * 1024 // 1 MB streaming chunks
        const val HANDSHAKE_TIMEOUT_MS = 5000L
        const val DECISION_TIMEOUT_MS = 30000L
        const val ACK_TIMEOUT_MS = 15000L
    }

    private val transferScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channelMutexes = ConcurrentHashMap<SocketChannel, Mutex>()
    private val channelFrameQueues = ConcurrentHashMap<SocketChannel, kotlinx.coroutines.channels.Channel<SonicFrame>>()

    // Channel states for incoming transfers
    private val channelSecrets = ConcurrentHashMap<SocketChannel, SecretKey>()
    private val deviceHandshakes = ConcurrentHashMap<SocketChannel, HandshakeFrame>()
    private val pendingBatches = ConcurrentHashMap<String, PendingBatch>()
    private val activeIncomingTransfers = ConcurrentHashMap<String, ActiveIncomingTransfer>()
    private val channelToBatchId = ConcurrentHashMap<SocketChannel, String>()

    private val _incomingSession = MutableStateFlow<TransferSession?>(null)
    val incomingSession: StateFlow<TransferSession?> = _incomingSession.asStateFlow()

    private val _incomingManifest = MutableStateFlow<ManifestFrame?>(null)
    val incomingManifest: StateFlow<ManifestFrame?> = _incomingManifest.asStateFlow()

    init {
        // If an existing server was passed in constructor, we keep reference
    }

    /**
     * Binds and starts an internal [SocketChannelServer] listening on [port],
     * routing incoming frames to [handleIncomingFrame].
     */
    fun startServer(port: Int = localPort): SocketChannelServer {
        val s = SocketChannelServer(port) { frame, channel ->
            handleIncomingFrame(frame, channel)
        }
        s.start()
        this.server = s
        return s
    }

    /**
     * Stops the running server socket engine.
     */
    fun stopServer() {
        server?.stop()
        server = null
    }

    /**
     * Streams a batch of file payloads to [peer].
     *
     * Flow emissions:
     * - [TransferState.Connecting]: Opening TCP NIO channel to peer.
     * - [TransferState.Handshaking]: Exchanging ECDH public keys & deriving AES-256 secret.
     * - [TransferState.Transferring]: Streaming encrypted chunks with live progress and moving avg speed.
     * - [TransferState.Completed]: All files acknowledged by receiver and batch completed.
     * - [TransferState.Failed]: Connection lost, transfer declined, or SHA-256 verification failed.
     */
    fun sendBatch(
        peer: PeerDevice,
        payloads: List<TransferPayload>
    ): Flow<TransferState> = flow {
        emit(TransferState.Connecting)

        val channel: SocketChannel
        try {
            channel = client.connect(peer.ipAddress, peer.port)
        } catch (e: Exception) {
            emit(TransferState.Failed("Connection failed: ${e.message}"))
            return@flow
        }

        try {
            emit(TransferState.Handshaking)

            // 1. ECDH Ephemeral Key Exchange
            val localKeyPair = cryptoEngine.generateKeyPair()
            val handshake = HandshakeFrame(
                deviceId = localDeviceId,
                deviceName = localDeviceName,
                version = 1,
                publicKey = localKeyPair.public.encoded,
                deviceModel = localDeviceModel,
                preferredPort = localPort
            )
            sendFrame(channel, handshake)

            val peerHandshake = client.receiveFrame(channel, HANDSHAKE_TIMEOUT_MS) as? HandshakeFrame
                ?: throw IOException("Peer did not return valid handshake frame")

            val sharedSecret = cryptoEngine.deriveSharedSecret(localKeyPair.private, peerHandshake.publicKey)

            // 2. Send Manifest & Await Recipient Decision
            val batchId = UUID.randomUUID().toString()
            val manifest = ManifestFrame(batchId = batchId, files = payloads)
            sendFrame(channel, manifest)

            val decision = client.receiveFrame(channel, DECISION_TIMEOUT_MS) as? DecisionFrame
                ?: throw IOException("Timed out waiting for recipient decision")

            if (!decision.accepted) {
                emit(TransferState.Failed("Transfer declined: ${decision.reasonCode ?: "Recipient declined"}"))
                return@flow
            }

            // 3. Stream File Chunks with Bounded Buffers & Live Speed Calculation
            val totalBytes = payloads.sumOf { it.size }
            var totalBytesSent = 0L
            val speedCalculator = SpeedCalculator()
            emit(TransferState.Transferring(0f, 0L, 0L, totalBytes))

            for (fileIndex in payloads.indices) {
                val payload = payloads[fileIndex]
                val inStream = openPayloadStream(payload)
                    ?: throw IOException("Cannot read payload stream for: ${payload.name}")

                if (payload.size == 0L) {
                    val encryptedZeroChunk = cryptoEngine.encrypt(ByteArray(0), sharedSecret)
                    val frame = DataChunkFrame(fileIndex = fileIndex, chunkOffset = 0L, data = encryptedZeroChunk)
                    sendFrame(channel, frame)
                } else {
                    var offset = 0L
                    val buffer = ByteArray(CHUNK_SIZE)
                    var read: Int

                    inStream.use { stream ->
                        while (stream.read(buffer).also { read = it } != -1) {
                            val rawChunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            val encryptedChunk = cryptoEngine.encrypt(rawChunk, sharedSecret)
                            val frame = DataChunkFrame(
                                fileIndex = fileIndex,
                                chunkOffset = offset,
                                data = encryptedChunk
                            )
                            sendFrame(channel, frame)

                            offset += read
                            totalBytesSent += read
                            val speed = speedCalculator.update(totalBytesSent)
                            val progress = if (totalBytes > 0) {
                                (totalBytesSent.toFloat() / totalBytes).coerceIn(0f, 1f)
                            } else 1f
                            emit(TransferState.Transferring(progress, speed, totalBytesSent, totalBytes))
                        }
                    }
                }

                // Wait for recipient's SHA-256 verification FileAckFrame
                val ack = client.receiveFrame(channel, ACK_TIMEOUT_MS) as? FileAckFrame
                    ?: throw IOException("Recipient did not send acknowledgment for ${payload.name}")

                if (ack.fileIndex != fileIndex) {
                    throw IOException("FileAck mismatch: expected index $fileIndex, got ${ack.fileIndex}")
                }

                if (payload.sha256.isNotBlank() && !ack.sha256.equals(payload.sha256, ignoreCase = true)) {
                    throw IOException("Recipient reported SHA-256 integrity check failure for ${payload.name}")
                }
            }

            // 4. Conclude Batch Transfer
            val batchComplete = BatchCompleteFrame(batchId = batchId)
            sendFrame(channel, batchComplete)
            emit(TransferState.Completed)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(TransferState.Failed(e.message ?: "Transfer encountered an error"))
        } finally {
            try { channel.close() } catch (_: Exception) {}
            channelMutexes.remove(channel)
        }
    }.flowOn(dispatcher)

    /**
     * Accepts a pending incoming transfer batch by ID, sending a positive [DecisionFrame]
     * to the peer and starting chunk reassembly.
     */
    fun acceptBatch(batchId: String) {
        transferScope.launch {
            val pending = pendingBatches.remove(batchId) ?: return@launch
            val decision = DecisionFrame(batchId = batchId, accepted = true)
            sendFrame(pending.channel, decision)

            val totalBytes = pending.files.sumOf { it.size }
            val active = ActiveIncomingTransfer(
                batchId = batchId,
                files = pending.files,
                channel = pending.channel,
                secret = pending.secret,
                totalBytes = totalBytes
            )
            activeIncomingTransfers[batchId] = active
            channelToBatchId[pending.channel] = batchId

            _incomingSession.value = _incomingSession.value?.copy(
                state = TransferState.Transferring(
                    progress = 0f,
                    speedBytesPerSec = 0L,
                    bytesTransferred = 0L,
                    totalBytes = totalBytes
                )
            )
        }
    }

    /**
     * Declines a pending incoming transfer batch by ID, sending a negative [DecisionFrame]
     * to the peer and cleaning up session state.
     */
    fun declineBatch(batchId: String) {
        transferScope.launch {
            val pending = pendingBatches.remove(batchId) ?: return@launch
            val decision = DecisionFrame(batchId = batchId, accepted = false, reasonCode = "USER_DECLINED")
            sendFrame(pending.channel, decision)

            _incomingSession.value = null
            _incomingManifest.value = null
        }
    }

    /**
     * Handles an incoming [SonicFrame] received over a client connection [channel].
     * Public to allow external [SocketChannelServer] instances to delegate events here.
     *
     * Inbound frames are dispatched into a per-channel sequential queue to prevent
     * frame interleaving, counter race conditions, and out-of-order execution.
     */
    fun handleIncomingFrame(frame: SonicFrame, channel: SocketChannel) {
        val queue = channelFrameQueues.computeIfAbsent(channel) { ch ->
            val q = kotlinx.coroutines.channels.Channel<SonicFrame>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            transferScope.launch {
                try {
                    for (f in q) {
                        processIncomingFrame(f, ch)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    channelFrameQueues.remove(ch)
                }
            }
            q
        }
        queue.trySend(frame)
    }

    private suspend fun processIncomingFrame(frame: SonicFrame, channel: SocketChannel) {
        when (frame) {
            is HandshakeFrame -> handleHandshake(frame, channel)
            is ManifestFrame -> handleManifest(frame, channel)
            is DataChunkFrame -> handleDataChunk(frame, channel)
            is BatchCompleteFrame -> handleBatchComplete(frame, channel)
            is DecisionFrame -> { /* Receiver does not expect incoming DecisionFrame */ }
            is FileAckFrame -> { /* Receiver sends FileAckFrames, does not consume them */ }
        }
    }

    /**
     * Shuts down all active transfers, releases writer handles, and stops background tasks.
     */
    fun teardown() {
        stopServer()
        channelFrameQueues.values.forEach { it.close() }
        channelFrameQueues.clear()
        transferScope.cancel()
        activeIncomingTransfers.values.forEach { it.closeAll() }
        activeIncomingTransfers.clear()
        pendingBatches.clear()
        channelMutexes.clear()
    }

    private suspend fun handleHandshake(frame: HandshakeFrame, channel: SocketChannel) {
        val localKeyPair = cryptoEngine.generateKeyPair()
        val sharedSecret = cryptoEngine.deriveSharedSecret(localKeyPair.private, frame.publicKey)

        channelSecrets[channel] = sharedSecret
        deviceHandshakes[channel] = frame

        val reply = HandshakeFrame(
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            version = 1,
            publicKey = localKeyPair.public.encoded,
            deviceModel = localDeviceModel,
            preferredPort = localPort
        )
        sendFrame(channel, reply)
    }

    private fun handleManifest(frame: ManifestFrame, channel: SocketChannel) {
        val peer = peerFromChannel(channel)
        val session = TransferSession(
            sessionId = frame.batchId,
            peer = peer,
            payloads = frame.files,
            state = TransferState.Idle,
            isIncoming = true
        )

        pendingBatches[frame.batchId] = PendingBatch(
            batchId = frame.batchId,
            files = frame.files,
            channel = channel,
            secret = channelSecrets[channel]
        )

        _incomingSession.value = session
        _incomingManifest.value = frame

        if (autoAccept) {
            acceptBatch(frame.batchId)
        }
    }

    private suspend fun handleDataChunk(frame: DataChunkFrame, channel: SocketChannel) {
        val batchId = channelToBatchId[channel] ?: return
        val active = activeIncomingTransfers[batchId] ?: return
        val payload = active.files.getOrNull(frame.fileIndex) ?: return

        // Decrypt data chunk; Sonic requires end-to-end encryption for all transfers
        if (active.secret == null) {
            _incomingSession.value = _incomingSession.value?.copy(
                state = TransferState.Failed("Unencrypted data chunk rejected for ${payload.name}")
            )
            val nack = FileAckFrame(fileIndex = frame.fileIndex, sha256 = "UNENCRYPTED_REJECTED")
            sendFrame(channel, nack)
            return
        }

        val rawData = try {
            cryptoEngine.decrypt(frame.data, active.secret)
        } catch (e: Exception) {
            _incomingSession.value = _incomingSession.value?.copy(
                state = TransferState.Failed("Decryption failed for chunk in ${payload.name}")
            )
            return
        }

        // Reassemble chunk into destination .sonic.part file
        val writer = active.getWriter(frame.fileIndex, payload, storageManager, context)
        writer.writeChunk(frame.chunkOffset, rawData)

        // Update progress sequentially (guaranteed single thread per channel)
        active.bytesTransferred += rawData.size
        val receivedForFile = (active.fileBytesReceived[frame.fileIndex] ?: 0L) + rawData.size
        active.fileBytesReceived[frame.fileIndex] = receivedForFile

        val speed = active.speedCalculator.update(active.bytesTransferred)
        val progress = if (active.totalBytes > 0) {
            (active.bytesTransferred.toFloat() / active.totalBytes).coerceIn(0f, 1f)
        } else 1f

        _incomingSession.value = _incomingSession.value?.copy(
            state = TransferState.Transferring(
                progress = progress,
                speedBytesPerSec = speed,
                bytesTransferred = active.bytesTransferred,
                totalBytes = active.totalBytes
            )
        )

        // If file assembly is complete, verify integrity and commit to storage
        if (receivedForFile >= payload.size && active.markFileCompleted(frame.fileIndex)) {
            writer.flush()
            val computedSha256 = writer.computeSha256()
            val isIntegrityValid = writer.verifyIntegrity(payload.sha256)

            if (!isIntegrityValid) {
                // Reject corrupted file: do NOT commit to MediaStore, delete partial file
                writer.close()
                writer.file.delete()
                _incomingSession.value = _incomingSession.value?.copy(
                    state = TransferState.Failed("Integrity verification failed for ${payload.name}")
                )
                val nack = FileAckFrame(fileIndex = frame.fileIndex, sha256 = "CORRUPTED_HASH_MISMATCH")
                sendFrame(channel, nack)
                return
            }

            // Commit verified temp part to MediaStore
            storageManager?.commitToMediaStore(
                tempFile = writer.file,
                displayName = payload.name,
                mimeType = payload.mimeType
            )

            writer.close()

            // Send acknowledgment frame with computed SHA-256
            val ack = FileAckFrame(fileIndex = frame.fileIndex, sha256 = computedSha256)
            sendFrame(channel, ack)
        }
    }

    private fun handleBatchComplete(frame: BatchCompleteFrame, channel: SocketChannel) {
        val batchId = frame.batchId
        val active = activeIncomingTransfers.remove(batchId)
        channelToBatchId.remove(channel)

        active?.closeAll()
        _incomingSession.value = _incomingSession.value?.copy(state = TransferState.Completed)
    }

    private suspend fun sendFrame(channel: SocketChannel, frame: SonicFrame) {
        val mutex = channelMutexes.computeIfAbsent(channel) { Mutex() }
        mutex.withLock {
            client.sendFrame(channel, frame)
        }
    }

    private fun peerFromChannel(channel: SocketChannel): PeerDevice {
        val address = try {
            (channel.remoteAddress as? InetSocketAddress)
        } catch (_: Exception) {
            null
        }
        val ip = address?.hostString ?: "127.0.0.1"
        val port = address?.port ?: 8989
        val handshake = deviceHandshakes[channel]

        return PeerDevice(
            id = handshake?.deviceId ?: ip,
            name = handshake?.deviceName ?: "Peer ($ip)",
            model = handshake?.deviceModel ?: "Android Device",
            ipAddress = ip,
            port = handshake?.preferredPort ?: port,
            rssi = 0,
            discoveryType = DiscoveryType.MDNS
        )
    }

    private fun openPayloadStream(payload: TransferPayload): InputStream? {
        payloadStreamProvider?.let { provider ->
            val stream = provider(payload)
            if (stream != null) return stream
        }

        if (payload.uriString != null && context != null) {
            try {
                val uri = Uri.parse(payload.uriString)
                val stream = context.contentResolver?.openInputStream(uri)
                if (stream != null) return stream
            } catch (_: Exception) {}
        }

        if (payload.uriString != null) {
            val file = File(payload.uriString)
            if (file.exists() && file.isFile) {
                return FileInputStream(file)
            }
        }

        val namedFile = File(payload.name)
        if (namedFile.exists() && namedFile.isFile) {
            return FileInputStream(namedFile)
        }

        return null
    }

    private data class PendingBatch(
        val batchId: String,
        val files: List<TransferPayload>,
        val channel: SocketChannel,
        val secret: SecretKey?
    )

    private class ActiveIncomingTransfer(
        val batchId: String,
        val files: List<TransferPayload>,
        val channel: SocketChannel,
        val secret: SecretKey?,
        val totalBytes: Long,
        val speedCalculator: SpeedCalculator = SpeedCalculator()
    ) {
        var bytesTransferred: Long = 0L
        val writers = ConcurrentHashMap<Int, ChunkedFileWriter>()
        val fileBytesReceived = ConcurrentHashMap<Int, Long>()
        val completedFiles = ConcurrentHashMap.newKeySet<Int>()

        fun markFileCompleted(fileIndex: Int): Boolean = completedFiles.add(fileIndex)

        fun getWriter(
            fileIndex: Int,
            payload: TransferPayload,
            storageManager: StorageManager?,
            context: Context?
        ): ChunkedFileWriter {
            return writers.computeIfAbsent(fileIndex) {
                val partFile = storageManager?.createTempPartFile(payload.id)
                    ?: File.createTempFile("sonic_${payload.id}_", ".part")
                ChunkedFileWriter(partFile, expectedSize = payload.size)
            }
        }

        fun closeAll() {
            writers.values.forEach { writer ->
                try {
                    writer.close()
                } catch (_: Exception) {}
            }
            writers.clear()
        }
    }

    /**
     * Computes moving average transmission speed across recent time window.
     */
    class SpeedCalculator(private val windowDurationMs: Long = 1500L) {
        private val samples = ArrayDeque<Pair<Long, Long>>()

        @Synchronized
        fun update(bytesTransferred: Long, nowMs: Long = System.currentTimeMillis()): Long {
            samples.addLast(nowMs to bytesTransferred)
            val cutoff = nowMs - windowDurationMs
            while (samples.size > 1 && samples.first().first < cutoff) {
                samples.removeFirst()
            }
            if (samples.size < 2) return 0L
            val dtSec = (samples.last().first - samples.first().first) / 1000.0
            val dbBytes = samples.last().second - samples.first().second
            return if (dtSec > 0.005) (dbBytes / dtSec).toLong() else 0L
        }

        @Synchronized
        fun reset() {
            samples.clear()
        }
    }
}
