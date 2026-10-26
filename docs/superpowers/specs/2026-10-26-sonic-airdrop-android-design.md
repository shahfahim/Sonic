# Sonic: High-Speed P2P Android File Sharing System
## Architectural Design Specification

**Date**: 2026-10-26  
**Status**: Approved for Implementation Planning  
**Target Platform**: Android (API Level 26+ / Android 8.0 Oreo through Android 15+)  
**Primary Language & UI**: Kotlin 2.x, Jetpack Compose, Material 3, Kotlin Coroutines & Flow  

---

## 1. Executive Summary & Purpose

**Sonic** is an instant, high-speed, peer-to-peer file sharing application for Android modeled after Apple AirDrop. It allows nearby devices to discover one another without manual pairing, preview incoming payloads, and stream arbitrary files, media, folders, and clipboard content directly at high speeds (40–80+ MB/s over 5GHz Wi-Fi Direct / Local Wi-Fi).

### Core Goals
- **Instant Zero-Config Discovery**: BLE advertising/scanning paired with mDNS for zero-friction peer detection.
- **Dual-Mode Transport**: Seamlessly utilizes existing local Wi-Fi router connectivity when available, falling back automatically to Wi-Fi Direct (P2P Group Owner / SoftAP) for 100% offline field transfers.
- **Maximized Throughput**: Java NIO `SocketChannel` chunked streaming using 1 MB memory buffers and tuned TCP socket options.
- **End-to-End Security**: TLS 1.3 encryption with ephemeral ECDH key exchange and SHA-256 integrity verification.
- **AirDrop Polish**: Radar pulse visualizer, interactive receiver accept/decline modal with rich media thumbnails, and foreground service notification showing live transfer speed and ETA.

---

## 2. System Architecture & Module Boundaries

The project adopts **Clean Architecture** combined with **MVI (Model-View-Intent)** to ensure strict separation of concerns, testability, and UI decoupling.

```
┌─────────────────────────────────────────────────────────────┐
│                      Presentation Layer                     │
│  - Jetpack Compose Screens (RadarScreen, TransferScreen)   │
│  - MVI ViewModels (RadarViewModel, TransferViewModel)       │
│  - StateFlow<UiState>, UiEvent channels, UiEffect handlers  │
└──────────────────────────────┬──────────────────────────────┘
                               │ Dispatches Actions / Observes State
┌──────────────────────────────▼──────────────────────────────┐
│                         Domain Layer                        │
│  - Use Cases (DiscoverPeersUseCase, SendPayloadUseCase, etc)│
│  - Domain Models (PeerDevice, TransferManifest, TransferItem)│
│  - Repository Interfaces (DiscoveryRepository, TransferRepo)│
└──────────────────────────────▲──────────────────────────────┘
                               │ Implemented By
┌──────────────────────────────┴──────────────────────────────┐
│                          Data Layer                         │
│  - Discovery Engine (BleDiscoveryEngine, MdnsEngine, P2P)   │
│  - Socket & Wire Protocol (NioSocketEngine, WireFramer)     │
│  - Security & Crypto (TlsEngine, EphemeralKeyManager)       │
│  - Local Database (Room DB: TransferHistoryDao)             │
│  - Android System Services (SonicTransferForegroundService) │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. Wire Protocol & Transmission Specification

### 3.1 Packet Framing
Communication over TCP/TLS uses a binary frame header followed by payload bytes:

```
+--------------------+------------------+------------------------------+---------------------------+
| Magic Bytes (4B)   | Frame Type (1B)  | Payload Length (4B, UInt32)  | Body Payload (N Bytes)    |
| 0x53 0x4F 0x4E 0x43| 0x01 .. 0x06     | Big-Endian Byte Length       | JSON or Raw Binary Chunks |
+--------------------+------------------+------------------------------+---------------------------+
```

### 3.2 Frame Types
1. `0x01 HANDSHAKE`:
   - Exchanges device metadata: `deviceName`, `deviceModel`, `sonicVersion`, `publicKeyHash`, `preferredPort`.
2. `0x02 MANIFEST`:
   - Batch metadata: `batchId`, `totalFiles`, `totalBytes`, and array of `FileInfo` (`fileId`, `name`, `size`, `mimeType`, `sha256Checksum`).
3. `0x03 DECISION`:
   - Response from receiver: `batchId`, `accepted: Boolean`, `reasonCode` (e.g., `USER_DECLINED`, `INSUFFICIENT_STORAGE`).
4. `0x04 DATA_CHUNK`:
   - Binary transfer frame:
     - `fileIndex`: 2 Bytes (UInt16)
     - `chunkOffset`: 8 Bytes (UInt64)
     - `chunkLength`: 4 Bytes (UInt32)
     - `chunkBytes`: raw payload bytes (1 MB chunks).
5. `0x05 FILE_ACK`:
   - Confirmation sent by receiver when a file's received SHA-256 hash matches the manifest.
6. `0x06 BATCH_COMPLETE`:
   - Final bidirectional termination frame signaling successful transfer of all items.

---

## 4. Discovery & Connection Workflow

1. **Passive Beaconing**:
   - Both devices continuously run BLE scanning and advertising when the app or share sheet is active.
   - BLE advertisement payload contains: `[SonicUUID (16B)][DeviceType (1B)][ShortFingerprint (4B)]`.
2. **Local Wi-Fi Resolution**:
   - Devices also broadcast an mDNS service: `_sonic._tcp.local.` with TXT records specifying `name`, `port=8989`, and `fingerprint`.
   - If both devices find each other on the same subnet via mDNS, they establish a direct TCP connection immediately.
3. **Wi-Fi Direct Fallback (Offline Mode)**:
   - If devices discover each other over BLE but are on different networks or completely offline:
     - Sender prompts transfer.
     - Receiver initializes `WifiP2pManager` and creates a P2P Group (acting as Autonomous Group Owner / SoftAP).
     - Receiver sends P2P network SSID and pre-shared key over BLE GATT back to sender.
     - Sender connects its Wi-Fi adapter to the receiver's P2P group.
     - High-speed TCP socket opens over `192.168.49.1:8989`.

---

## 5. Security & Privacy Guarantees

1. **Mutual TLS 1.3 / Ephemeral ECDH**:
   - Every session generates an ephemeral X25519 keypair.
   - Derives symmetric AES-256-GCM session keys via HKDF (HMAC Key Derivation Function).
   - Zero persistent identity tracking; device fingerprints randomize periodically to prevent location tracking.
2. **Integrity Validation**:
   - Receiver verifies streaming file chunks on the fly and performs full SHA-256 digest validation before moving files from temporary storage (`.sonic.part`) to the public Downloads / Media directory.

---

## 6. UI/UX Specification (Jetpack Compose)

1. **Radar Screen**:
   - Concentric expanding rings animation simulating radar search.
   - Centered user avatar.
   - Dynamically animated peer bubbles orbiting the center based on signal strength.
   - Smooth entry/exit animations via Compose `AnimatedVisibility`.
2. **Accept/Decline Bottom Sheet**:
   - Slide-up bottom sheet on incoming transfer.
   - Displays sender device avatar, sender name, total file count, human-readable size, and thumbnail preview.
   - Distinct "Decline" (Outlined) and "Accept" (Filled Primary) action buttons.
3. **Transfer Screen / HUD**:
   - Circular and linear progress indicators.
   - Real-time throughput calculation: `MB/s` calculated using a moving average window over the last 3 seconds.
   - ETA countdown: `Time Remaining: Xm Ys`.
   - Backgrounding automatically moves progress to the system notification bar.

---

## 7. Storage & Android Permissions

- **Scoped Storage & SAF**:
  - Writes received files directly to `MediaStore.Downloads` or `MediaStore.Images/Video` on Android 10+ using `ContentResolver`, requiring no broad `MANAGE_EXTERNAL_STORAGE` permission.
- **Required Manifest Permissions**:
  - `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+)
  - `NEARBY_WIFI_DEVICES` (Android 13+)
  - `ACCESS_FINE_LOCATION` (Android 11 and below fallback for Wi-Fi P2P)
  - `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+)
  - `POST_NOTIFICATIONS` (Android 13+)

---

## 8. Verification & Testing Strategy

1. **Protocol Unit Tests**:
   - Serialize and deserialize binary frames (`Handshake`, `Manifest`, `DataChunk`, `FileAck`) to verify endianness, buffer bounds, and magic bytes.
2. **Crypto Unit Tests**:
   - ECDH key exchange simulation and AES-GCM 256 encryption/decryption round-trip.
3. **Loopback Transfer Tests**:
   - Run in-memory socket server and client to stream 50 MB synthetic files and verify chunk reassembly, speed calculation, and SHA-256 hash match.
