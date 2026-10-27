# ⚡ Sonic: AirDrop for Android

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Platform: Android" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Language: Kotlin" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_Material_3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="UI: Jetpack Compose" />
  <img src="https://img.shields.io/badge/Speed-40--80+_MB/s-00E5FF?style=for-the-badge" alt="Transfer Speed" />
  <img src="https://img.shields.io/badge/Security-ECDH_%2B_AES--256--GCM-critical?style=for-the-badge" alt="Security: ECDH + AES-256-GCM" />
</p>

**Sonic** is an instant, ultra-high-speed, peer-to-peer (P2P) file sharing application for Android modeled after Apple AirDrop. It allows nearby Android devices to discover each other with zero configuration, preview incoming file transfers with rich metadata, and stream arbitrary files and media over an authenticated, encrypted transport achieving **40–80+ MB/s** over 5GHz Wi-Fi Direct and local subnets.

---

## ✨ Features

- **🚀 Instant Zero-Config Discovery**: Seamless dual-mode discovery combining low-power Bluetooth Low Energy (BLE) background advertising & scanning with mDNS / DNS-SD on local Wi-Fi.
- **⚡ Superfast Wi-Fi Direct P2P Transport**: Automatically spins up an Autonomous Wi-Fi Direct Group Owner (SoftAP) on 5GHz (80MHz channel width) for completely offline, high-throughput field transfers.
- **🔒 End-to-End Cryptography**: Ephemeral Elliptic Curve Diffie-Hellman (ECDH on NIST P-256 / `secp256r1`) key agreement paired with HKDF-SHA256 and authenticated AES-256-GCM chunk encryption.
- **📦 Bounded Non-Blocking Streaming Engine**: Built with Java NIO `SocketChannel`, 2 MB socket buffers, `TCP_NODELAY`, and bounded 1 MB streaming chunks to guarantee predictable memory footprint and prevent OOM.
- **📂 Resumable Scoped Storage Reassembly**: Out-of-order chunk assembly writing into `.sonic.part` files with SHA-256 validation and atomic publishing to Android `MediaStore.Downloads` (API 29+ with `IS_PENDING` isolation).
- **🔔 Foreground Service & Real-Time Notifications**: Android `dataSync` Foreground Service with live speed indicators (`MB/s`), remaining time (ETA), and cancel actions.
- **🎨 Jetpack Compose AirDrop Radar UI**: Apple AirDrop-style radar canvas with animated glowing concentric ripples, polar coordinate ($r, \theta$) radial peer placement based on RSSI, modal incoming transfer bottom sheets, and floating HUD.

---

## 🏗️ Architecture & Design Patterns

Sonic follows strict **Clean Architecture** principles and **MVI / MVVM** UI patterns:

```
app/
 ├── src/main/java/com/sonic/share/
 │    ├── core/
 │    │    ├── crypto/       # EphemeralKeyExchange (ECDH), SonicCryptoEngine (AES-256-GCM)
 │    │    ├── network/      # Java NIO SocketChannelServer, SocketChannelClient, StreamPipeline
 │    │    ├── protocol/     # FrameCodec (SONC magic 0x534F4E43), Binary Frame Schemas
 │    │    └── service/      # SonicTransferService (dataSync), TransferNotificationHelper
 │    ├── domain/model/      # PeerDevice, TransferPayload, TransferSession, TransferState
 │    ├── feature_discovery/ # BleAdvertiserManager, BleScannerManager, MdnsDiscoveryManager
 │    │    └── wifidirect/   # WifiDirectManager (P2pStateMachine, 5GHz Autonomous SoftAP)
 │    ├── feature_transfer/  # ChunkedFileWriter, StorageManager (MediaStore), TransferManager
 │    └── ui/                # MainActivity, Jetpack Compose RadarScreen, Theme, HUD, Sheets
 └── src/test/java/          # 76 Unit & End-to-End Integration Tests (100% pure JVM runnable)
```

---

## ⚡ Quickstart & Build Instructions

### Prerequisites
- JDK 17 (Eclipse Adoptium OpenJDK 17 recommended)
- Android SDK (API 35 compileSdk, minSdk 26)

### Running Unit & Integration Tests
```powershell
# Run the complete test suite (76 tests across 12 suites)
.\gradlew.bat testDebugUnitTest
```

### Building Debug APK
```powershell
.\gradlew.bat assembleDebug
# Generated output: app/build/outputs/apk/debug/app-debug.apk
```

### Installing on Device via ADB
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📖 Documentation

For detailed wire protocol specifications, cryptographic formulations, multi-device testing setups, and troubleshooting guides, please refer to the complete operational manual:

👉 **[docs/USAGE.md](docs/USAGE.md)**

---

## 📄 License

This project is open-source and available under the [Apache License 2.0](LICENSE).
