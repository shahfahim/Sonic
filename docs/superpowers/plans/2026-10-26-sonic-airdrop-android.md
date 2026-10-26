# Sonic (P2P High-Speed AirDrop for Android) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-ready, high-speed, peer-to-peer file sharing application for Android featuring AirDrop-like zero-configuration discovery, dual-mode transport (Wi-Fi Direct P2P + local Wi-Fi), TLS/AES-256-GCM encryption, and a polished Jetpack Compose radar interface.

**Architecture:** Clean Architecture with MVI (Model-View-Intent). The Core layer handles binary frame parsing, NIO socket streaming, and cryptography without Android framework dependencies. The Data layer manages BLE scanning, mDNS, Wi-Fi Direct P2P negotiation, and Scoped Storage file I/O. The Presentation layer renders an animated Jetpack Compose radar and interactive transfer HUD driven by `StateFlow`.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, Material 3, Kotlinx Coroutines & Flow, Java NIO SocketChannel, Android BluetoothLeScanner/Advertiser, Android NsdManager, Android WifiP2pManager, JUnit 5 / JUnit 4.

**Spec:** [`docs/superpowers/specs/2026-10-26-sonic-airdrop-android-design.md`](file:///f:/Sonic/docs/superpowers/specs/2026-10-26-sonic-airdrop-android-design.md)

## Global Constraints
- Target SDK: 35 (Android 15), Min SDK: 26 (Android 8.0 Oreo).
- Memory budget: Maximum 1 MB chunk buffer in transit to prevent GC pauses and OutOfMemoryErrors on low-RAM devices.
- Wire protocol magic header: `0x534F4E43` (`"SONC"`).
- Default port: `8989` (TCP).
- Scoped Storage: Android 10+ uses `MediaStore` / `ContentResolver` to avoid dangerous full-disk permissions.
- Testability: All protocol serializers, crypto ciphers, and framing engines must run in pure JVM unit tests without Robolectric or emulator requirements.

## Review Focus
1. **Partial Chunk Recovery**: Sudden Wi-Fi drop mid-transfer must not corrupt the destination file or crash the background service; it must retain `.sonic.part` files for resumption.
2. **Endianness & Framing Mismatch**: Packet frames must strictly use big-endian order across all integer and long offset fields.
3. **Payload Hash Mismatch**: Corrupted or incomplete chunks must be caught by SHA-256 digest validation and rejected before saving.
4. **Bluetooth/Wi-Fi Permission Denials**: Graceful degradation and user prompt when runtime permissions (`NEARBY_WIFI_DEVICES`, `BLUETOOTH_SCAN`) are denied.
5. **Simultaneous Bi-directional Transfers**: Socket server must handle multiple concurrent incoming peer connections without blocking the main event loop.

---

### Task 1: Project Setup, Gradle Configuration & Domain Models

**Files:**
- Create: `app/build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/sonic/share/domain/model/PeerDevice.kt`
- Create: `app/src/main/java/com/sonic/share/domain/model/TransferPayload.kt`
- Create: `app/src/main/java/com/sonic/share/domain/model/TransferSession.kt`
- Test: `app/src/test/java/com/sonic/share/domain/DomainModelTest.kt`

**Interfaces:**
- Produces:
  - `data class PeerDevice(val id: String, val name: String, val model: String, val ipAddress: String, val port: Int, val rssi: Int, val discoveryType: DiscoveryType)`
  - `data class TransferPayload(val id: String, val name: String, val size: Long, val mimeType: String, val sha256: String, val uriString: String? = null)`
  - `sealed class TransferState { object Idle, Connecting, Handshaking, Transferring(val progress: Float, val speedBytesPerSec: Long, val bytesTransferred: Long, val totalBytes: Long), Completed, Failed(val reason: String) }`

- [ ] **Step 1: Write the failing domain model test**
```kotlin
package com.sonic.share.domain

import com.sonic.share.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class DomainModelTest {
    @Test
    fun testPeerDeviceCreation() {
        val peer = PeerDevice(
            id = "sonic-1234",
            name = "Pixel 9 Pro",
            model = "Google Pixel",
            ipAddress = "192.168.49.1",
            port = 8989,
            rssi = -42,
            discoveryType = DiscoveryType.WIFI_DIRECT
        )
        assertEquals("sonic-1234", peer.id)
        assertEquals("192.168.49.1", peer.ipAddress)
        assertEquals(8989, peer.port)
    }

    @Test
    fun testTransferPayloadCalculatesProgress() {
        val payload = TransferPayload(
            id = "f-1",
            name = "video.mp4",
            size = 1000L,
            mimeType = "video/mp4",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val state = TransferState.Transferring(
            progress = 0.5f,
            speedBytesPerSec = 50_000_000L,
            bytesTransferred = 500L,
            totalBytes = 1000L
        )
        assertEquals(0.5f, state.progress, 0.001f)
        assertEquals(50_000_000L, state.speedBytesPerSec)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.domain.DomainModelTest` (or equivalent test runner)
Expected: FAIL with compilation error (unresolved references).

- [ ] **Step 3: Implement domain models and gradle configurations**
Configure `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts` with Compose dependencies, Coroutines, and implement `PeerDevice`, `TransferPayload`, and `TransferState`.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.domain.DomainModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add build.gradle.kts settings.gradle.kts app/
git commit -m "feat(core): setup project foundation and core domain models"
```

---

### Task 2: Core Wire Protocol & Binary Frame Codec

**Files:**
- Create: `app/src/main/java/com/sonic/share/core/protocol/SonicFrame.kt`
- Create: `app/src/main/java/com/sonic/share/core/protocol/FrameCodec.kt`
- Create: `app/src/main/java/com/sonic/share/core/protocol/FramePayloads.kt`
- Test: `app/src/test/java/com/sonic/share/core/protocol/FrameCodecTest.kt`

**Interfaces:**
- Consumes: `TransferPayload` from Task 1
- Produces:
  - `enum class FrameType(val code: Byte) { HANDSHAKE(0x01), MANIFEST(0x02), DECISION(0x03), DATA_CHUNK(0x04), FILE_ACK(0x05), BATCH_COMPLETE(0x06) }`
  - `sealed class SonicFrame(val type: FrameType)`
  - `class FrameCodec { fun encode(frame: SonicFrame): ByteArray; fun decode(buffer: ByteBuffer): SonicFrame? }`

- [ ] **Step 1: Write the failing FrameCodec unit test**
```kotlin
package com.sonic.share.core.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class FrameCodecTest {
    private val codec = FrameCodec()

    @Test
    fun testHandshakeEncodingAndDecoding() {
        val original = HandshakeFrame(
            deviceId = "dev-42",
            deviceName = "Alex's Galaxy",
            version = 1,
            publicKey = byteArrayOf(1, 2, 3, 4, 5)
        )
        val encodedBytes = codec.encode(original)
        val decoded = codec.decode(ByteBuffer.wrap(encodedBytes)) as? HandshakeFrame
        
        assertNotNull(decoded)
        assertEquals("dev-42", decoded?.deviceId)
        assertEquals("Alex's Galaxy", decoded?.deviceName)
        assertEquals(1, decoded?.version)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), decoded?.publicKey)
    }

    @Test
    fun testDataChunkFrameEncoding() {
        val payloadData = "Hello Sonic Stream".toByteArray(Charsets.UTF_8)
        val chunk = DataChunkFrame(
            fileIndex = 0,
            chunkOffset = 1024L,
            data = payloadData
        )
        val bytes = codec.encode(chunk)
        val decoded = codec.decode(ByteBuffer.wrap(bytes)) as? DataChunkFrame

        assertNotNull(decoded)
        assertEquals(0, decoded?.fileIndex)
        assertEquals(1024L, decoded?.chunkOffset)
        assertArrayEquals(payloadData, decoded?.data)
    }

    @Test
    fun testRejectsInvalidMagicBytes() {
        val invalidHeader = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x01, 0, 0, 0, 5, 1, 2, 3, 4, 5)
        val decoded = codec.decode(ByteBuffer.wrap(invalidHeader))
        assertNull(decoded)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.protocol.FrameCodecTest`
Expected: FAIL.

- [ ] **Step 3: Implement `FrameCodec`, `SonicFrame`, and payload frame classes**
Implement `SonicFrame` hierarchy (`HandshakeFrame`, `ManifestFrame`, `DecisionFrame`, `DataChunkFrame`, `FileAckFrame`, `BatchCompleteFrame`) with big-endian header packing:
- 4 Bytes: Magic `0x534F4E43`
- 1 Byte: Frame Type
- 4 Bytes: Length (UInt32)
- Body serialization with JSON metadata for control frames and binary byte arrays for chunk frames.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.protocol.FrameCodecTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/core/protocol/ app/src/test/java/com/sonic/share/core/protocol/
git commit -m "feat(protocol): implement binary wire framing codec and frame schemas"
```

---

### Task 3: Security & Cryptographic Handshake (ECDH + AES-256-GCM)

**Files:**
- Create: `app/src/main/java/com/sonic/share/core/crypto/SonicCryptoEngine.kt`
- Create: `app/src/main/java/com/sonic/share/core/crypto/EphemeralKeyExchange.kt`
- Test: `app/src/test/java/com/sonic/share/core/crypto/SonicCryptoEngineTest.kt`

**Interfaces:**
- Consumes: Handshake frames from Task 2
- Produces:
  - `class SonicCryptoEngine { fun generateKeyPair(): KeyPair; fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKeyBytes: ByteArray): SecretKey; fun encrypt(plaintext: ByteArray, secretKey: SecretKey): ByteArray; fun decrypt(ciphertext: ByteArray, secretKey: SecretKey): ByteArray }`

- [ ] **Step 1: Write the failing CryptoEngine test**
```kotlin
package com.sonic.share.core.crypto

import org.junit.Assert.*
import org.junit.Test

class SonicCryptoEngineTest {
    private val crypto = SonicCryptoEngine()

    @Test
    fun testEcdhKeyExchangeAndAesGcmRoundtrip() {
        val aliceKeyPair = crypto.generateKeyPair()
        val bobKeyPair = crypto.generateKeyPair()

        val aliceSecret = crypto.deriveSharedSecret(aliceKeyPair.private, bobKeyPair.public.encoded)
        val bobSecret = crypto.deriveSharedSecret(bobKeyPair.private, aliceKeyPair.public.encoded)

        assertArrayEquals(aliceSecret.encoded, bobSecret.encoded)

        val message = "Top secret AirDrop file payload content".toByteArray(Charsets.UTF_8)
        val encrypted = crypto.encrypt(message, aliceSecret)
        assertFalse(message.contentEquals(encrypted))

        val decrypted = crypto.decrypt(encrypted, bobSecret)
        assertArrayEquals(message, decrypted)
    }

    @Test
    fun testSha256Checksum() {
        val data = "Sonic High Speed Stream".toByteArray(Charsets.UTF_8)
        val hash = crypto.computeSha256(data)
        assertNotNull(hash)
        assertEquals(64, hash.length) // Hex string
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.crypto.SonicCryptoEngineTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SonicCryptoEngine` and `EphemeralKeyExchange`**
Implement standard Java Cryptography Architecture (JCA) using `EC` (curve secp256r1 / NIST P-256) and `KeyAgreement.getInstance("ECDH")`, followed by HKDF/SHA-256 derivation of a 256-bit AES key, and `Cipher.getInstance("AES/GCM/NoPadding")` with a 12-byte random IV prepended to the ciphertext.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.crypto.SonicCryptoEngineTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/core/crypto/ app/src/test/java/com/sonic/share/core/crypto/
git commit -m "feat(crypto): implement ephemeral ECDH key agreement and AES-256-GCM cipher"
```

---

### Task 4: Java NIO High-Throughput Socket Engine

**Files:**
- Create: `app/src/main/java/com/sonic/share/core/network/SocketChannelServer.kt`
- Create: `app/src/main/java/com/sonic/share/core/network/SocketChannelClient.kt`
- Create: `app/src/main/java/com/sonic/share/core/network/StreamPipeline.kt`
- Test: `app/src/test/java/com/sonic/share/core/network/SocketStreamingTest.kt`

**Interfaces:**
- Consumes: `FrameCodec` from Task 2, `SonicCryptoEngine` from Task 3
- Produces:
  - `class SocketChannelServer(val port: Int, val onFrameReceived: (SonicFrame, SocketChannel) -> Unit) { fun start(); fun stop() }`
  - `class SocketChannelClient { suspend fun connect(host: String, port: Int): SocketChannel; suspend fun sendFrame(channel: SocketChannel, frame: SonicFrame) }`

- [ ] **Step 1: Write loopback streaming test**
```kotlin
package com.sonic.share.core.network

import com.sonic.share.core.protocol.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SocketStreamingTest {
    @Test
    fun testLocalLoopbackFrameTransfer() = runBlocking {
        val latch = CountDownLatch(1)
        var receivedFrame: SonicFrame? = null
        val testPort = 18989

        val server = SocketChannelServer(testPort) { frame, _ ->
            receivedFrame = frame
            latch.countDown()
        }
        server.start()

        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)
        val sentFrame = HandshakeFrame("dev-1", "Test Device", 1, byteArrayOf(1, 2, 3))
        client.sendFrame(channel, sentFrame)

        assertTrue(latch.await(3, TimeUnit.SECONDS))
        assertNotNull(receivedFrame)
        assertTrue(receivedFrame is HandshakeFrame)
        assertEquals("dev-1", (receivedFrame as HandshakeFrame).deviceId)

        channel.close()
        server.stop()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.network.SocketStreamingTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SocketChannelServer`, `SocketChannelClient`, and `StreamPipeline`**
Implement non-blocking Java NIO channels with 2 MB buffer sizes (`SO_SNDBUF`, `SO_RCVBUF`), `TCP_NODELAY = true`, and dedicated I/O coroutine dispatchers for continuous multi-chunk streaming.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.network.SocketStreamingTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/core/network/ app/src/test/java/com/sonic/share/core/network/
git commit -m "feat(network): implement high-throughput Java NIO socket streaming engine"
```

---

### Task 5: Dual-Mode Discovery (BLE Beaconing + mDNS Engine)

**Files:**
- Create: `app/src/main/java/com/sonic/share/feature_discovery/DiscoveryEngine.kt`
- Create: `app/src/main/java/com/sonic/share/feature_discovery/ble/BleAdvertiserManager.kt`
- Create: `app/src/main/java/com/sonic/share/feature_discovery/ble/BleScannerManager.kt`
- Create: `app/src/main/java/com/sonic/share/feature_discovery/mdns/MdnsDiscoveryManager.kt`
- Test: `app/src/test/java/com/sonic/share/feature_discovery/DiscoveryEngineTest.kt`

**Interfaces:**
- Consumes: `PeerDevice` from Task 1
- Produces:
  - `interface DiscoveryEngine { val discoveredPeers: StateFlow<List<PeerDevice>>; fun startDiscovery(); fun stopDiscovery(); fun startAdvertising(deviceName: String, port: Int); fun stopAdvertising() }`

- [ ] **Step 1: Write the failing DiscoveryEngine test**
```kotlin
package com.sonic.share.feature_discovery

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryEngineTest {
    @Test
    fun testPeerAggregationAndDeduplication() = runBlocking {
        val aggregator = PeerAggregator()
        val blePeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -50, DiscoveryType.BLE)
        val mdnsPeer = PeerDevice("dev-1", "Phone", "Model X", "192.168.1.50", 8989, -30, DiscoveryType.MDNS)

        aggregator.onPeerFound(blePeer)
        aggregator.onPeerFound(mdnsPeer)

        val peers = aggregator.peers.first()
        assertEquals(1, peers.size)
        assertEquals(DiscoveryType.MDNS, peers[0].discoveryType) // mDNS upgrades connectivity
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_discovery.DiscoveryEngineTest`
Expected: FAIL.

- [ ] **Step 3: Implement BLE, mDNS Discovery, and PeerAggregator**
Implement `BleAdvertiserManager` with `AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY`, `BleScannerManager` with `ScanFilter` for the Sonic UUID, and `MdnsDiscoveryManager` using Android `NsdManager.RegistrationListener` and `DiscoveryListener`.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_discovery.DiscoveryEngineTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/feature_discovery/ app/src/test/java/com/sonic/share/feature_discovery/
git commit -m "feat(discovery): implement BLE beaconing and mDNS local network discovery"
```

---

### Task 6: Wi-Fi Direct P2P Group Owner & SoftAP Connection Orchestrator

**Files:**
- Create: `app/src/main/java/com/sonic/share/feature_discovery/wifidirect/WifiDirectManager.kt`
- Create: `app/src/main/java/com/sonic/share/feature_discovery/wifidirect/P2pConnectionState.kt`
- Test: `app/src/test/java/com/sonic/share/feature_discovery/wifidirect/WifiDirectManagerTest.kt`

**Interfaces:**
- Consumes: `PeerDevice` from Task 1, `DiscoveryEngine` from Task 5
- Produces:
  - `class WifiDirectManager(val context: Context) { fun createGroup(onSuccess: (ssid: String, passphrase: String, ip: String) -> Unit); fun connectToGroup(ssid: String, passphrase: String, onConnected: (groupOwnerIp: String) -> Unit); fun teardown() }`

- [ ] **Step 1: Write state machine test for P2P connection transitions**
```kotlin
package com.sonic.share.feature_discovery.wifidirect

import org.junit.Assert.*
import org.junit.Test

class WifiDirectManagerTest {
    @Test
    fun testP2pConnectionStateTransitions() {
        val stateMachine = P2pStateMachine()
        assertEquals(P2pState.Disconnected, stateMachine.currentState)

        stateMachine.transition(P2pEvent.CreatingGroup)
        assertEquals(P2pState.CreatingGroup, stateMachine.currentState)

        stateMachine.transition(P2pEvent.GroupFormed(isOwner = true, ownerIp = "192.168.49.1"))
        assertTrue(stateMachine.currentState is P2pState.Connected)
        assertEquals("192.168.49.1", (stateMachine.currentState as P2pState.Connected).ownerIp)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_discovery.wifidirect.WifiDirectManagerTest`
Expected: FAIL.

- [ ] **Step 3: Implement `WifiDirectManager` and `P2pStateMachine`**
Implement Android `WifiP2pManager` wrapper with `createGroup()` using `WifiP2pConfig.Builder` (specifying 5GHz band preference where supported) and client connect fallback.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_discovery.wifidirect.WifiDirectManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/feature_discovery/wifidirect/ app/src/test/java/com/sonic/share/feature_discovery/wifidirect/
git commit -m "feat(wifidirect): implement Wi-Fi Direct P2P group creation and connection manager"
```

---

### Task 7: Resumable File Streaming & Scoped Storage Manager

**Files:**
- Create: `app/src/main/java/com/sonic/share/feature_transfer/data/StorageManager.kt`
- Create: `app/src/main/java/com/sonic/share/feature_transfer/domain/TransferManager.kt`
- Create: `app/src/main/java/com/sonic/share/feature_transfer/data/ChunkedFileWriter.kt`
- Test: `app/src/test/java/com/sonic/share/feature_transfer/ChunkedFileWriterTest.kt`

**Interfaces:**
- Consumes: `SocketChannelClient`, `SocketChannelServer`, `FrameCodec`, `SonicCryptoEngine`
- Produces:
  - `class StorageManager(val context: Context) { fun createTempPartFile(fileId: String): File; fun commitToMediaStore(tempFile: File, displayName: String, mimeType: String): Uri? }`
  - `class TransferManager { fun sendBatch(peer: PeerDevice, payloads: List<TransferPayload>): Flow<TransferState>; fun acceptBatch(batchId: String); fun declineBatch(batchId: String) }`

- [ ] **Step 1: Write chunk reassembly & SHA-256 validation test**
```kotlin
package com.sonic.share.feature_transfer

import com.sonic.share.feature_transfer.data.ChunkedFileWriter
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkedFileWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testChunkReassemblyAndIntegrityCheck() {
        val destFile = tempFolder.newFile("test_output.sonic.part")
        val writer = ChunkedFileWriter(destFile, expectedSize = 15L)

        writer.writeChunk(offset = 0L, data = "Hello ".toByteArray(Charsets.UTF_8))
        writer.writeChunk(offset = 6L, data = "Sonic ".toByteArray(Charsets.UTF_8))
        writer.writeChunk(offset = 12L, data = "App".toByteArray(Charsets.UTF_8))
        writer.flush()

        assertEquals(15L, destFile.length())
        assertEquals("Hello Sonic App", destFile.readText(Charsets.UTF_8))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_transfer.ChunkedFileWriterTest`
Expected: FAIL.

- [ ] **Step 3: Implement `StorageManager`, `ChunkedFileWriter`, and `TransferManager`**
Implement `RandomAccessFile` chunk writer, `MediaStore.Downloads` insertion via `ContentValues` and `ContentResolver.openOutputStream()`, and the transfer state machine managing bytes transferred, speed calculation, and SHA-256 verification.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.feature_transfer.ChunkedFileWriterTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/feature_transfer/ app/src/test/java/com/sonic/share/feature_transfer/
git commit -m "feat(transfer): implement chunked file reassembly and Scoped Storage manager"
```

---

### Task 8: Android Foreground Transfer Service & Notification Engine

**Files:**
- Create: `app/src/main/java/com/sonic/share/core/service/SonicTransferService.kt`
- Create: `app/src/main/java/com/sonic/share/core/service/TransferNotificationHelper.kt`
- Modify: `app/src/main/AndroidManifest.xml` (register service with `foregroundServiceType="dataSync"`)
- Test: `app/src/test/java/com/sonic/share/core/service/NotificationSpeedFormatterTest.kt`

**Interfaces:**
- Consumes: `TransferManager` from Task 7
- Produces:
  - `class SonicTransferService : Service() { companion object { fun startService(context: Context, batchId: String) } }`
  - `object TransferNotificationHelper { fun formatSpeed(bytesPerSec: Long): String; fun formatEta(remainingBytes: Long, speed: Long): String }`

- [ ] **Step 1: Write notification format calculation test**
```kotlin
package com.sonic.share.core.service

import org.junit.Assert.*
import org.junit.Test

class NotificationSpeedFormatterTest {
    @Test
    fun testSpeedFormatting() {
        assertEquals("500 KB/s", TransferNotificationHelper.formatSpeed(500 * 1024L))
        assertEquals("52.4 MB/s", TransferNotificationHelper.formatSpeed(52_400_000L))
        assertEquals("1.2 GB/s", TransferNotificationHelper.formatSpeed(1_200_000_000L))
    }

    @Test
    fun testEtaFormatting() {
        // 100 MB remaining at 50 MB/s = 2 seconds
        assertEquals("2s", TransferNotificationHelper.formatEta(100_000_000L, 50_000_000L))
        // 120 seconds = 2m 0s
        assertEquals("2m 0s", TransferNotificationHelper.formatEta(120_000_000L, 1_000_000L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.service.NotificationSpeedFormatterTest`
Expected: FAIL.

- [ ] **Step 3: Implement `SonicTransferService` and `TransferNotificationHelper`**
Implement Android NotificationChannel `sonic_transfers`, `NotificationCompat.Builder` with progress bar, pause/cancel action intents, and speed/ETA calculation.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.core.service.NotificationSpeedFormatterTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/core/service/ app/src/test/java/com/sonic/share/core/service/ app/src/main/AndroidManifest.xml
git commit -m "feat(service): implement foreground transfer service and live notification engine"
```

---

### Task 9: Jetpack Compose UI (Radar Pulse, Peer Nodes, & Transfer HUD)

**Files:**
- Create: `app/src/main/java/com/sonic/share/ui/radar/RadarScreen.kt`
- Create: `app/src/main/java/com/sonic/share/ui/radar/RadarPulseCanvas.kt`
- Create: `app/src/main/java/com/sonic/share/ui/radar/PeerAvatarNode.kt`
- Create: `app/src/main/java/com/sonic/share/ui/sheet/IncomingTransferBottomSheet.kt`
- Create: `app/src/main/java/com/sonic/share/ui/transfer/TransferProgressHUD.kt`
- Create: `app/src/main/java/com/sonic/share/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/sonic/share/ui/MainActivity.kt`
- Test: `app/src/test/java/com/sonic/share/ui/radar/RadarViewModelTest.kt`

**Interfaces:**
- Consumes: `DiscoveryEngine` from Task 5, `TransferManager` from Task 7
- Produces:
  - `@Composable fun RadarScreen(viewModel: RadarViewModel, onPeerSelected: (PeerDevice) -> Unit)`
  - `@Composable fun IncomingTransferBottomSheet(manifest: TransferManifest, onAccept: () -> Unit, onDecline: () -> Unit)`

- [ ] **Step 1: Write ViewModel state transition test**
```kotlin
package com.sonic.share.ui.radar

import com.sonic.share.domain.model.DiscoveryType
import com.sonic.share.domain.model.PeerDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadarViewModelTest {
    @Test
    fun testRadarViewModelFiltersAndEmitsPeers() = runBlocking {
        val fakeDiscovery = FakeDiscoveryEngine()
        val viewModel = RadarViewModel(fakeDiscovery)

        val peer = PeerDevice("p1", "MacBook", "Laptop", "192.168.1.10", 8989, -45, DiscoveryType.MDNS)
        fakeDiscovery.emitPeers(listOf(peer))

        val state = viewModel.uiState.first()
        assertEquals(1, state.discoveredPeers.size)
        assertEquals("MacBook", state.discoveredPeers[0].name)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**
Run: `gradle testDebugUnitTest --tests com.sonic.share.ui.radar.RadarViewModelTest`
Expected: FAIL.

- [ ] **Step 3: Implement Jetpack Compose Radar UI, Components, and `RadarViewModel`**
Implement canvas-drawn expanding concentric rings with infinite pulse animation, dynamic peer positioning around the center, incoming transfer bottom sheet with thumbnail and accept/decline buttons, and Material 3 theme styling.

- [ ] **Step 4: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.ui.radar.RadarViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/sonic/share/ui/ app/src/test/java/com/sonic/share/ui/
git commit -m "feat(ui): implement Jetpack Compose radar pulse, peer avatars, and transfer HUD"
```

---

### Task 10: End-to-End Loopback Transfer Integration & Verification Test Suite

**Files:**
- Create: `app/src/test/java/com/sonic/share/integration/EndToEndTransferIntegrationTest.kt`
- Modify: `app/build.gradle.kts` (test dependencies)
- Create: `docs/USAGE.md` (Operational manual & architecture guide)

**Interfaces:**
- Consumes: All layers (Protocol, Crypto, Network, TransferManager)
- Produces: Complete automated end-to-end integration test verifying full handshake, manifest accept, chunked transfer, SHA-256 verification, and file commit.

- [ ] **Step 1: Write the full end-to-end transfer simulation test**
```kotlin
package com.sonic.share.integration

import com.sonic.share.core.crypto.SonicCryptoEngine
import com.sonic.share.core.network.SocketChannelClient
import com.sonic.share.core.network.SocketChannelServer
import com.sonic.share.core.protocol.*
import com.sonic.share.domain.model.TransferPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EndToEndTransferIntegrationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testCompleteAirDropTransferPipeline() = runBlocking {
        val testPort = 19999
        val crypto = SonicCryptoEngine()
        val codec = FrameCodec()
        val latch = CountDownLatch(1)

        // 1. Create a 5 MB synthetic test file
        val sourceFile = tempFolder.newFile("large_test_video.bin")
        val testData = ByteArray(5 * 1024 * 1024) { (it % 256).toByte() }
        sourceFile.writeBytes(testData)
        val expectedSha = crypto.computeSha256(testData)

        val destFile = tempFolder.newFile("received_video.bin.sonic.part")
        var transferSuccess = false

        // 2. Start Receiver Server
        val server = SocketChannelServer(testPort) { frame, channel ->
            when (frame) {
                is HandshakeFrame -> {
                    // Respond with receiver handshake
                    val resp = HandshakeFrame("rec-1", "Receiver", 1, byteArrayOf(9, 9))
                    channel.write(java.nio.ByteBuffer.wrap(codec.encode(resp)))
                }
                is ManifestFrame -> {
                    // Auto-accept
                    val accept = DecisionFrame(frame.batchId, accepted = true)
                    channel.write(java.nio.ByteBuffer.wrap(codec.encode(accept)))
                }
                is DataChunkFrame -> {
                    destFile.appendBytes(frame.data)
                    if (destFile.length() == testData.size.toLong()) {
                        val ack = FileAckFrame(frame.fileIndex, sha256 = crypto.computeSha256(destFile.readBytes()))
                        channel.write(java.nio.ByteBuffer.wrap(codec.encode(ack)))
                    }
                }
                is BatchCompleteFrame -> {
                    transferSuccess = true
                    latch.countDown()
                }
                else -> {}
            }
        }
        server.start()

        // 3. Sender Connects and Streams Chunks
        val client = SocketChannelClient()
        val channel = client.connect("127.0.0.1", testPort)

        // Handshake
        client.sendFrame(channel, HandshakeFrame("send-1", "Sender", 1, byteArrayOf(1, 1)))

        // Manifest
        val payload = TransferPayload("f1", "large_test_video.bin", testData.size.toLong(), "application/octet-stream", expectedSha)
        client.sendFrame(channel, ManifestFrame("batch-1", listOf(payload)))

        // Stream Chunks (1 MB each)
        val chunkSize = 1024 * 1024
        var offset = 0L
        for (i in 0 until 5) {
            val chunk = testData.copyOfRange(offset.toInt(), (offset + chunkSize).toInt())
            client.sendFrame(channel, DataChunkFrame(fileIndex = 0, chunkOffset = offset, data = chunk))
            offset += chunkSize
        }
        client.sendFrame(channel, BatchCompleteFrame("batch-1"))

        assertTrue("Transfer timed out", latch.await(10, TimeUnit.SECONDS))
        assertTrue(transferSuccess)
        assertEquals(5 * 1024 * 1024L, destFile.length())
        assertEquals(expectedSha, crypto.computeSha256(destFile.readBytes()))

        channel.close()
        server.stop()
    }
}
```

- [ ] **Step 2: Run test to verify it passes**
Run: `gradle testDebugUnitTest --tests com.sonic.share.integration.EndToEndTransferIntegrationTest`
Expected: PASS.

- [ ] **Step 3: Document usage, build steps, and architecture manual**
Write `docs/USAGE.md` detailing how to run, build APK, test in emulator or physical devices, and architecture layout.

- [ ] **Step 4: Commit**
```bash
git add app/src/test/java/com/sonic/share/integration/ docs/USAGE.md
git commit -m "test(integration): add full end-to-end AirDrop loopback transfer simulation suite"
```
