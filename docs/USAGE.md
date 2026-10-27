# Sonic: AirDrop for Android — Operational Manual & Architecture Guide

## 1. System Overview & Executive Summary

**Sonic** is an instant, high-speed, peer-to-peer (P2P) file sharing system engineered for Android (API 26+ / Android 8.0 through Android 15+). Modeled after Apple AirDrop, Sonic enables nearby Android devices to discover each other with zero configuration, preview incoming file transfers with rich metadata, and stream arbitrary files and media over an encrypted, high-throughput transport achieving **40–80+ MB/s** over 5GHz Wi-Fi Direct and local Wi-Fi networks.

### Core Capabilities
* **Instant Zero-Config Discovery**: Combines low-power Bluetooth Low Energy (BLE) background advertising and scanning with local area network mDNS/DNS-SD discovery.
* **Dual-Mode Transport Engine**: Transparently utilizes existing LAN infrastructure (mDNS) when devices share a Wi-Fi subnet; automatically forms an Autonomous Wi-Fi Direct Group Owner (SoftAP) on 5GHz for 100% offline field transfers.
* **Non-Blocking High-Throughput Wire Engine**: Built on Java NIO `SocketChannel` using 2 MB socket buffers, `TCP_NODELAY`, and bounded 1 MB streaming chunks.
* **End-to-End Cryptography**: Ephemeral Elliptic Curve Diffie-Hellman (ECDH on NIST P-256 / `secp256r1`) key exchange paired with HKDF-SHA256 and authenticated AES-256-GCM chunk encryption.
* **Resumable Scoped Storage Pipeline**: Temporary out-of-order reassembly in `.sonic.part` files with SHA-256 verification and atomic commit to Android `MediaStore.Downloads` (API 29+ with `IS_PENDING` isolation).
* **AirDrop-Style User Experience**: Native Jetpack Compose UI featuring a circular radar canvas with polar coordinate peer mapping, interactive incoming transfer bottom sheets, real-time throughput HUD, and resilient Android foreground services.

---

## 2. System Architecture & Modular Organization

Sonic follows **Clean Architecture** combined with **Unidirectional Data Flow (MVI)** to maintain strict separation of concerns, testability, and UI decoupling.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Presentation Layer (UI)                         │
│  - Jetpack Compose Screens: RadarScreen, TransferProgressHUD          │
│  - Canvas Visualizers: RadarPulseCanvas (polar coordinate projection)   │
│  - Interaction Overlays: PeerAvatarNode, IncomingTransferBottomSheet   │
│  - Architecture: RadarViewModel (StateFlow<RadarUiState>, UiEvents)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Observes State / Dispatches Intents
┌───────────────────────────────────▼────────────────────────────────────┐
│                             Domain Layer                               │
│  - TransferManager: High-level transfer coordinator & state engine     │
│  - Domain Models: PeerDevice, TransferPayload, TransferSession         │
│  - State Models: TransferState (Idle, Connecting, Handshaking, etc.)   │
│  - Enums: DiscoveryType (BLE, MDNS, WIFI_DIRECT)                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Implements & Drives
┌───────────────────────────────────▼────────────────────────────────────┐
│                              Core Engines                              │
│  ┌───────────────────────┐ ┌──────────────────────┐ ┌────────────────┐ │
│  │     Core Protocol     │ │     Core Network     │ │  Core Crypto   │ │
│  │ FrameCodec (SONC wire)│ │ SocketChannelServer  │ │ SonicCrypto    │ │
│  │ 6 Binary Frame Types  │ │ SocketChannelClient  │ │ Ephemeral ECDH │ │
│  │ StreamPipeline        │ │ 2 MB Buffers / NIO   │ │ AES-256-GCM    │ │
│  └───────────────────────┘ └──────────────────────┘ └────────────────┘ │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                          Feature Discovery                        │ │
│  │ DiscoveryEngine: BleAdvertiserManager, BleScannerManager          │ │
│  │ MdnsDiscoveryManager (NsdManager), WifiDirectManager (P2P GO)     │ │
│  └───────────────────────────────────────────────────────────────────┘ │
│  ┌───────────────────────────────────────────────────────────────────┐ │
│  │                          Feature Transfer                         │ │
│  │ ChunkedFileWriter (.sonic.part), StorageManager (MediaStore Q+)   │ │
│  │ SonicTransferService (dataSync foreground service + Notifications)│ │
│  └───────────────────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

### Module Responsibilities

| Package | Key Classes | Core Responsibility |
| :--- | :--- | :--- |
| `core.protocol` | `FrameCodec`, `SonicFrame`, `FramePayloads` | Binary frame serialization/deserialization with `SONC` magic header and 1 MB chunk bounds. |
| `core.network` | `SocketChannelServer`, `SocketChannelClient`, `StreamPipeline` | Java NIO non-blocking socket engines, channel buffer tuning, dynamic slice reassembly. |
| `core.crypto` | `SonicCryptoEngine`, `EphemeralKeyExchange` | NIST P-256 ECDH ephemeral key agreement, HKDF key derivation, AES-256-GCM authenticated encryption. |
| `core.service` | `SonicTransferService`, `TransferNotificationHelper` | Android foreground service (`dataSync`), notification channel lifecycle, moving speed formatting. |
| `domain.model` | `PeerDevice`, `TransferPayload`, `TransferSession` | Immutable domain entities, transfer progress tracking, peer identity records. |
| `feature_discovery` | `DiscoveryEngine`, `BleAdvertiserManager`, `BleScannerManager`, `MdnsDiscoveryManager`, `WifiDirectManager` | Hybrid discovery orchestration: BLE beacons (<31B payload), mDNS service resolution, Wi-Fi Direct Group Owner. |
| `feature_transfer` | `TransferManager`, `ChunkedFileWriter`, `StorageManager` | Multi-file streaming coordination, per-channel mutex locks, `.sonic.part` preallocation, Scoped Storage commit. |
| `ui.radar` | `RadarScreen`, `RadarViewModel`, `RadarPulseCanvas`, `PeerAvatarNode` | Dynamic polar-coordinate radar visualizer, orbital peer nodes, smooth transition animations. |
| `ui.sheet` | `IncomingTransferBottomSheet` | Modal transfer approval sheet showing sender info, file count, total size, and thumbnail previews. |
| `ui.transfer` | `TransferProgressHUD` | Centered transfer status card, circular and linear progress bars, live throughput (MB/s), and ETA calculation. |

---

## 3. Protocol Specification

Sonic implements a custom, high-efficiency binary wire protocol designed for maximum throughput and minimal parsing overhead over TCP streaming sockets.

### 3.1 Binary Frame Layout

All frames transmitted over the wire follow an 9-byte header followed by variable payload bytes:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic: 'S' 'O' 'N' 'C' (0x53, 0x4F, 0x4E, 0x43)        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   Type (1B)   |          Payload Length (4B, Big-Endian)      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         Payload Data                          |
|                     (Length bytes...)                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* **Magic Bytes (4 Bytes)**: `0x53 0x4F 0x4E 0x43` (ASCII `"SONC"`). Frames without this magic prefix are rejected; the `StreamPipeline` automatically resynchronizes on byte streams containing noise.
* **Frame Type (1 Byte)**: Identifies the message type (`0x01` through `0x06`).
* **Payload Length (4 Bytes)**: 32-bit big-endian unsigned integer indicating payload body size (maximum bounded to 16 MB).
* **Payload Data**: Frame-specific structured body (JSON for metadata, raw binary for data chunks).

### 3.2 Frame Types

```
  Sender                                                 Receiver
    |                                                       |
    | ------------ 0x01 HANDSHAKE (ECDH Public Key) ------> |
    | <----------- 0x01 HANDSHAKE (ECDH Public Key) ------- |
    |                                                       |  (Derive Shared Key)
    | ------------ 0x02 MANIFEST (File metadata) ---------> |
    | <----------- 0x03 DECISION (Accepted: true) --------- |
    |                                                       |
    | ------------ 0x04 DATA_CHUNK (File 0, 0-1MB, AES) --> |  (Write .sonic.part)
    | ------------ 0x04 DATA_CHUNK (File 0, 1-2MB, AES) --> |
    | <----------- 0x05 FILE_ACK (File 0, SHA-256) -------- |  (Commit to MediaStore)
    |                                                       |
    | ------------ 0x06 BATCH_COMPLETE -------------------> |
    |                                                       |
```

1. **`0x01 HANDSHAKE`**:
   - Exchanges device identifiers, friendly name, protocol version, preferred listening port, device model, and ephemeral ECDH public key (X.509 format).
2. **`0x02 MANIFEST`**:
   - Serialized JSON containing `batchId` (UUID) and an array of `TransferPayload` descriptors (`id`, `name`, `size`, `mimeType`, `sha256`).
3. **`0x03 DECISION`**:
   - Sent by receiver: `batchId`, `accepted: Boolean`, and optional `reasonCode` (e.g. `"USER_DECLINED"`, `"STORAGE_FULL"`).
4. **`0x04 DATA_CHUNK`**:
   - High-performance binary frame:
     - `fileIndex`: 2 Bytes (Big-Endian UInt16)
     - `chunkOffset`: 8 Bytes (Big-Endian UInt64)
     - `chunkData`: Encrypted ciphertext chunk (maximum 1 MB plaintext + 12B IV + 16B GCM tag).
5. **`0x05 FILE_ACK`**:
   - Confirmation sent by receiver when a file is assembled and its computed SHA-256 matches the manifest: `fileIndex: UInt16`, `sha256: String`.
6. **`0x06 BATCH_COMPLETE`**:
   - Final signaling frame sent after all payloads have been acknowledged, releasing active channel writers.

---

## 4. Cryptographic Security Model

Sonic mandates strict end-to-end encryption for every byte transmitted over the wire. No plaintext file chunks are ever accepted or emitted.

### 4.1 Ephemeral ECDH Key Exchange
* **Curve**: NIST P-256 (`secp256r1`).
* **Generation**: At the start of every connection, each device generates a fresh ephemeral `KeyPairGenerator.getInstance("EC")` keypair.
* **Agreement**: `KeyAgreement.getInstance("ECDH")` derives a 32-byte shared secret from the local private key and remote peer's public key.
* **Forward Secrecy**: Ephemeral keys are discarded immediately upon session termination; past session data cannot be decrypted even if a device is subsequently compromised.

### 4.2 Key Derivation (HKDF-SHA256)
The raw ECDH shared secret is expanded into a symmetric session key via RFC 5869 HKDF using SHA-256:
$$\text{PRK} = \text{HMAC-SHA256}(\text{salt}, \text{SharedSecret})$$
$$\text{AESKey} = \text{HMAC-SHA256}(\text{PRK}, \text{"Sonic-P2P-Transfer-Key"} \parallel 0x01)$$

### 4.3 Authenticated Chunk Encryption (AES-256-GCM)
* **Cipher**: `AES/GCM/NoPadding` with a 256-bit key.
* **Initialization Vector (IV)**: Fresh 12-byte cryptographically secure random IV (`SecureRandom`) generated for every 1 MB chunk.
* **Authentication Tag**: 128-bit authentication tag guarantees confidentiality and tamper-evident chunk integrity.
* **Ciphertext Layout**: `[12-byte IV][Ciphertext Bytes + 16-byte Tag]`.
* **Zero Overhead**: In-memory decryption operates with direct byte arrays to avoid JVM GC pressure.

### 4.4 End-to-End SHA-256 Checksum Verification
In addition to per-chunk GCM authentication, the sender calculates a full SHA-256 checksum over each complete file payload before transmission. The receiver recomputes the SHA-256 hash directly from disk (`RandomAccessFile`) upon receiving all chunks. If the hash fails to match the manifest, the partial file is wiped immediately and an integrity failure `FileAckFrame` is sent.

---

## 5. Network & Transport Engine

### 5.1 Java NIO Socket Architecture
* **`SocketChannelServer`**: Non-blocking `ServerSocketChannel` registered with a `Selector` running on `Dispatchers.IO`. Accepts incoming connections and delegates read loops to `StreamPipeline`.
* **`SocketChannelClient`**: Non-blocking outbound client utilizing selector-based connect and read timeouts.
* **Buffer Tuning**:
  - `SO_RCVBUF`: Set to **2 MB** (`2,097,152` bytes) to allow high TCP window scaling over 802.11ac/ax Wi-Fi.
  - `SO_SNDBUF`: Set to **2 MB**.
  - `TCP_NODELAY`: Enabled (`true`) to disable Nagle's algorithm and ensure instant frame dispatch without 40ms buffering delays.
* **Thread-Safe Serialization**: Each active `SocketChannel` is protected by a Kotlin Coroutines `Mutex` to eliminate socket write interleaving between concurrent frame emissions.
* **Inbound Frame Queueing**: Inbound frames are dispatched into per-channel `Channel<SonicFrame>(UNLIMITED)` queues to prevent race conditions during rapid chunk reception.

---

## 6. Peer Discovery Engine

Sonic implements a zero-configuration, dual-layer discovery system combining Bluetooth Low Energy (BLE) and Local Area Network mDNS.

```
                  ┌──────────────────────────────┐
                  │    DiscoveryEngine State     │
                  └──────────────┬───────────────┘
                                 │
                 ┌───────────────┴───────────────┐
                 │                               │
        ┌────────▼────────┐             ┌────────▼────────┐
        │ BLE Subsystem   │             │ mDNS Subsystem  │
        │ - BleAdvertiser │             │ - NsdManager    │
        │ - BleScanner    │             │ - _sonic._tcp   │
        └────────┬────────┘             └────────┬────────┘
                 │                               │
                 └───────────────┬───────────────┘
                                 │
                         ┌───────▼───────┐
                         │ Peer Registry │
                         │ (Deduplication│
                         │  & RSSI merge)│
                         └───────────────┘
```

### 6.1 BLE Beaconing (< 31 Bytes Payload)
To comply with legacy Bluetooth 4.0/4.2 advertising packet constraints (31-byte limit), Sonic encodes peer discovery data into a ultra-compact manufacturer data payload:
* **Manufacturer ID**: `0x534F` (ASCII `'S' 'O'`)
* **Header / Version**: 1 Byte (`0x01`)
* **Device ID Hash**: 4 Bytes (CRC32 of unique device ID)
* **Device Type**: 1 Byte (`0x01` Phone, `0x02` Tablet, `0x03` PC)
* **Preferred Port**: 2 Bytes (Big-Endian UInt16)
* **Friendly Name**: Truncated UTF-8 string (up to 12 Bytes)
* **Total Size**: Under 22 Bytes, well within the 31-byte advertising PDU limit.

### 6.2 mDNS / DNS-SD Service Discovery
When devices are connected to the same Wi-Fi router:
* **Service Type**: `_sonic._tcp`
* **NsdManager**: Utilizes Android's native Network Service Discovery API.
* **TXT Records**:
  - `dev_id`: Full unique device ID
  - `dev_name`: Friendly device name (e.g., "Pixel 9 Pro")
  - `model`: Hardware model string
  - `ver`: Protocol version (`"1"`)

### 6.3 Wi-Fi Direct Autonomous Group Owner (SoftAP)
When devices are out of range of a shared Wi-Fi network:
1. The receiving device creates an Autonomous Wi-Fi Direct Group using `WifiP2pManager.createGroup()`.
2. The receiver assumes the role of **Group Owner (GO)** at fixed IP `192.168.49.1`.
3. SoftAP network credentials (SSID `DIRECT-xy-Sonic_...` and WPA2 Passphrase) are conveyed to the sender over BLE or local negotiation.
4. The sender connects its Wi-Fi adapter to the GO network and initiates high-speed socket streaming on port `8989`.

---

## 7. Resumable Storage & Android Scoped Storage

### 7.1 ChunkedFileWriter
* **Resumable Streaming**: Writes incoming chunks at arbitrary byte offsets using `RandomAccessFile`.
* **Zero Overhead Preallocation**: When file size is declared in the manifest, `raf.setLength(expectedSize)` preallocates contiguous disk blocks to avoid fragmentation.
* **Integrity Flush**: Flushes OS file system page cache via `raf.channel.force(true)` and `raf.fd.sync()` before computing the final SHA-256 digest.

### 7.2 Scoped Storage Integration (Android 10+ / API 29+)
Received files are committed to Android Scoped Storage without requiring broad `MANAGE_EXTERNAL_STORAGE` permissions:
1. **Isolation**: Inbound chunks are written to temporary files named `<id>.sonic.part` inside the private application cache (`context.cacheDir/sonic_transfers/`).
2. **MediaStore Insertion**: Once integrity is verified, a new record is created in `MediaStore.Downloads` with:
   - `RELATIVE_PATH`: `Download/Sonic/`
   - `IS_PENDING`: `1` (prevents other apps and media scanners from reading incomplete files).
3. **Data Streaming**: Temp file bytes are copied directly into the `ContentResolver.openOutputStream(uri)`.
4. **Publishing**: `IS_PENDING` is updated to `0`, making the file instantly available in the user's Downloads directory and system file managers.
5. **Cleanup**: The `.sonic.part` temporary file is deleted.
6. **Legacy / Test Fallback**: For API < 29 or headless unit test environments, files are cleanly committed to `context.getExternalFilesDir("Download")/Sonic/`.

---

## 8. Foreground Service & Notification Engine

File transfers continue uninterrupted when the user switches apps or locks the screen thanks to `SonicTransferService`:
* **Service Type**: Configured with `android:foregroundServiceType="dataSync"` (mandated on Android 14+ / API 34).
* **Low-Priority Notification Channel**: Notification channel `sonic_transfer_channel` uses `IMPORTANCE_LOW` so progress updates do not emit annoying audio/vibration alerts.
* **Real-Time Moving Average Speed**: Computed across a 1500 ms sliding window, formatted into human-readable throughput (`KB/s`, `MB/s`, or `GB/s`).
* **Interactive Actions**: Action intents allow the user to accept, decline, or cancel transfers directly from the notification shade.

---

## 9. Jetpack Compose UI & Visual Architecture

### 9.1 Radar Visualizer (`RadarScreen`)
* **Polar Coordinate Mapping**: Nearby peers are projected onto circular radar rings based on signal strength (RSSI) and device hash angle:
  $$x = \text{centerX} + r \cdot \cos(\theta), \quad y = \text{centerY} + r \cdot \sin(\theta)$$
* **Animated Waveform Pulse**: Canvas drawing loops render expanding concentric circles with dynamic alpha falloff simulating radar scanning.
* **Peer Node Orbiting**: Smooth animated transitions (`animateFloatAsState`, `animateDpAsState`) smoothly animate peers as they enter or change distance.

### 9.2 Heads-Up Display (`TransferProgressHUD`)
* **Dual Gauges**: Combines a circular progress ring (0% to 100%) with a linear detail bar.
* **Metrics**: Displays current file name, transferred bytes / total size, live throughput (e.g. `64.2 MB/s`), and dynamic estimated time of arrival (ETA).

---

## 10. Building & Running

### 10.1 Prerequisites
* **JDK**: OpenJDK 17 or Eclipse Temurin 17.
* **Android SDK**: Compile SDK `35`, Min SDK `26` (Android 8.0+), Target SDK `35` (Android 15).
* **Gradle**: 8.13 (bundled via `gradlew.bat`).
* **Android Studio**: Android Studio Ladybug (2024.2.1+) or newer.

### 10.2 Command-Line Build Instructions

#### Windows PowerShell:
```powershell
# 1. Clean and compile the debug APK
.\gradlew.bat assembleDebug

# 2. Run all unit and integration tests (zero-device JVM suite)
.\gradlew.bat testDebugUnitTest

# 3. Run specific integration tests
.\gradlew.bat testDebugUnitTest --tests com.sonic.share.integration.EndToEndTransferIntegrationTest

# 4. Install debug APK on connected device or emulator
.\gradlew.bat installDebug
```

#### macOS / Linux:
```bash
# 1. Assemble APK
./gradlew assembleDebug

# 2. Execute unit and integration tests
./gradlew testDebugUnitTest

# 3. Install to target
./gradlew installDebug
```

---

## 11. Testing Guide

### 11.1 Host JVM Loopback Integration Suite
Sonic includes a comprehensive loopback simulation test suite (`EndToEndTransferIntegrationTest.kt`) that runs in pure JVM environments without needing Android emulators or hardware:
* **`testCompleteAirDropTransferPipeline`**: Simulates a complete 5 MB binary data transfer over loopback `127.0.0.1` with ephemeral ECDH key agreement, AES-256-GCM chunk encryption across five 1 MB chunks, SHA-256 digest validation, and byte-for-byte exact equality verification.
* **`testTransferManagerHighLevelEndToEndLoopback`**: Tests high-level `TransferManager` sender and receiver coordination, moving average speed calculation, and Scoped Storage file commitment.
* **`testMultiFileEncryptedTransfer`**: Tests batch manifests containing multiple distinct file types (text document and binary image) with sequential streaming and individual `FileAckFrame` roundtrips.

Run with:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.sonic.share.integration.EndToEndTransferIntegrationTest
```

### 11.2 Android Virtual Device (AVD) Emulators
1. Launch two Android Virtual Devices (API 30+ recommended) in Android Studio.
2. When testing over the local network:
   - AVDs share the host loopback network. Use `adb -s emulator-5554 forward tcp:8989 tcp:8989` and `adb -s emulator-5556 reverse tcp:8989 tcp:8989` if loopback forwarding is needed.
   - Alternatively, connect both AVDs to a simulated Wi-Fi access point in the emulator extended settings.
3. Verify radar discovery and send test images from the AVD gallery.

### 11.3 Physical Hardware Setup
1. **Local Wi-Fi Mode**:
   - Connect two Android devices to the same 5GHz Wi-Fi router.
   - Launch Sonic on both devices; granting nearby permissions will immediately display each device on the radar canvas.
2. **Offline Field Mode (Wi-Fi Direct)**:
   - Disconnect both devices from any Wi-Fi network.
   - Turn on Wi-Fi and Bluetooth on both phones.
   - Initiate a transfer from Phone A to Phone B; Phone B creates an Autonomous Group Owner and Phone A associates automatically to stream files at high speed.

---

## 12. Permission Checklist

Sonic follows the principle of least privilege, leveraging modern Android 13+ permission models to avoid unnecessary location tracking.

| Permission | Android Version | Purpose |
| :--- | :--- | :--- |
| `android.permission.NEARBY_WIFI_DEVICES` | Android 13+ (API 33+) | Wi-Fi Direct P2P discovery without location access (`android:usesPermissionFlags="neverForLocation"`). |
| `android.permission.BLUETOOTH_SCAN` | Android 12+ (API 31+) | Discovers nearby BLE advertising beacons (`neverForLocation`). |
| `android.permission.BLUETOOTH_ADVERTISE`| Android 12+ (API 31+) | Broadcasts local device beacon for discovery. |
| `android.permission.BLUETOOTH_CONNECT`  | Android 12+ (API 31+) | Reads Bluetooth adapter state and device friendly name. |
| `android.permission.POST_NOTIFICATIONS` | Android 13+ (API 33+) | Displays transfer progress and speed in foreground notification. |
| `android.permission.FOREGROUND_SERVICE` | Android 9+ (API 28+)  | Keeps transfers active when the app is in the background. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Android 14+ (API 34+) | Declares foreground service workload type as data synchronization. |
| `android.permission.ACCESS_FINE_LOCATION`| Android 8.0 - 12 (API 26-31)| Required by legacy Android for Wi-Fi Direct and BLE discovery. |

---

## 13. Throughput Tuning & Performance Guide

To consistently achieve **40–80+ MB/s** throughput, Sonic applies the following optimizations:

1. **Socket Buffer Sizing**: Both sender and receiver channels configure `SO_RCVBUF` and `SO_SNDBUF` to `2 * 1024 * 1024` (2 MB), expanding the TCP receive window to prevent network stalls on high-bandwidth Wi-Fi links.
2. **Disabled Nagle Algorithm**: Setting `TCP_NODELAY = true` eliminates artificial packet aggregation delays, providing instant response times for handshake and acknowledgment frames.
3. **1 MB Chunk Bounding**: Slicing payloads into bounded 1 MB chunks ensures predictable AES-256-GCM memory usage, prevents Android GC spikes, and enables real-time progress calculations.
4. **Direct Channel Transfer & Disk Preallocation**: `ChunkedFileWriter` invokes `raf.setLength()` upon file creation, prompting underlying file systems (ext4, f2fs) to allocate contiguous disk clusters.
5. **5GHz Channel Preference**: Wi-Fi Direct Autonomous Group Owner selects 5GHz (80MHz channel width) whenever hardware supports it, avoiding saturated 2.4GHz bands.

---

## 14. Troubleshooting & FAQ

#### Q: The radar screen does not discover nearby devices.
* Ensure Bluetooth and Wi-Fi are turned on on both devices.
* On Android 13+, confirm the `NEARBY_WIFI_DEVICES` and `BLUETOOTH_SCAN` runtime permissions have been granted.
* On Android 11 and older, Location Services (GPS) must be enabled for BLE scanning to function.
* Check if a VPN or corporate firewall is active; some VPN profiles block local mDNS multicast packets (`224.0.0.251`).

#### Q: Wi-Fi Direct fails with `BUSY` or error code `2`.
* Another application (e.g. Android Nearby Share or Wi-Fi Display / Miracast) may be holding the Wi-Fi P2P hardware lock. Toggle Wi-Fi off and on, or restart the app.

#### Q: Received files are not visible in Google Photos or gallery apps.
* On Android 10+ (API 29+), Sonic commits files to `Download/Sonic/`. Use the system "Files" app to access downloaded documents and media. If image files do not appear immediately in third-party galleries, allow Android's `MediaScanner` a few moments to index the newly published `MediaStore` entry.

#### Q: Why is transfer speed low (< 5 MB/s)?
* Ensure both devices are connected to a 5GHz Wi-Fi band rather than 2.4GHz. 2.4GHz channels frequently suffer from Bluetooth coexistence interference.
* Move devices within 5 meters of each other to optimize Wi-Fi Direct MIMO link rates.
