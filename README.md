<p align="center">
  <img src="art/morselink-banner.png" alt="Morselink" width="640">
</p>

# Morselink

Offline, peer-to-peer file transfer for Android. Photos, videos, music, apps and
files move directly between devices over a local network — **no internet, no
cloud, no accounts, no ads**.

**Download the APK:** [`app/release/morse-link.apk`](app/release/morse-link.apk)
(minSdk 21, Android 5.0+) — signed, so allow *install from unknown sources*.

---

## What ships

| | |
| --- | --- |
| **Connect** | Radar dashboard, device identity, Send / Receive pills, WebShare entry |
| **Send** | Five category tabs — Photos, Videos, Music, Apps, Files — with search, sort (date/size/name), multi-select and smart categories (Documents, Ebooks, APKs, Archives, Large files) |
| **Receive** | QR scanner with a manual code/IP entry fallback that is always reachable |
| **Transfer** | Two independent lists — Sending and Receiving — with progress, speed, ETA, cancel, and a label showing the active transport |
| **Files** | Standalone file manager: storage bar, smart categories, folder browsing, and share / delete / rename / move / copy / compress / properties |
| **History** | Local log of past transfers, Received / Sent, grouped by date, with Open / Install |
| **WebShare** | Phone-hosted HTTP server for phone ↔ PC in a browser: QR + plain address, hotspot control, mDNS, drag-and-drop uploads, Range-capable downloads |
| **Settings** | Device name, avatar, theme (light/dark/system), download location, sounds, notifications, and an Advanced section for timeout / reconnect knobs |

## Transports

Everything sits behind one abstraction, so the UI never knows which medium is active:

```kotlin
interface TransportProvider {
    val id: TransportType
    fun isAvailable(context: Context): Boolean
    suspend fun startDiscovery(): Flow<DiscoveredPeer>
    suspend fun connect(peer: DiscoveredPeer): TransportSession
    suspend fun stopDiscovery()
}
```

- **Nearby Connections** (primary on GMS devices) — `P2P_STAR`, BLE discovery, `Payload.fromFile()`.
- **Legacy Wi-Fi Direct** (non-GMS, API 21+) — hand-rolled: JSON control channel, 64 KB chunks with
  `magic | sequence | length | payload | crc32`, retransmission requests and byte-offset resume.
- **WebShare HTTP** — NanoHTTPD on port 33455, Range-based downloads, multipart uploads.

If discovery finds nothing within 15 seconds, the UI offers the WebShare fallback instead of hanging.

## Architecture

```
app/                      application shell, navigation, permissions, foreground service
core/
  core-ui/                design tokens, theme, RadarView, dialogs, device tier (§7)
  core-data/              Room (transfer history) + DataStore (preferences)
  core-media/             MediaStore queries, app scanner, file browser, file ops
  core-transfer/          TransferEngine, progress models, legacy chunk protocol
  core-network/           TransportProvider implementations, transport selection
feature/
  feature-dashboard/      home, radar, entry points
  feature-send/           category tabs, selection
  feature-receive/        QR scanning, manual pairing
  feature-transfer-ui/    sending/receiving progress
  feature-filemanager/    file browser
  feature-webshare/       HTTP server, hotspot controller, mDNS, PC pairing
  feature-settings/       preferences + transfer history
```

Kotlin, View system + ViewBinding, Hilt, Room, DataStore, Glide, CameraX, ZXing, NanoHTTPD, coroutines.

## Two deliberate deviations from the spec

1. **ZXing instead of ML Kit for QR scanning.** ML Kit's barcode model is fetched through Play
   services, which would break the non-GMS requirement (§3). ZXing is pure Java, offline, API 21+.
2. **CameraX only, with a guarded Camera1 fallback.** CameraX already covers API 21+, so the legacy
   path exists purely as an error branch for devices where CameraX fails to initialise.

## Building

```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug             # install on a connected device
./gradlew test                     # unit tests
```

JDK 17, Android SDK, compile/target 35, min 21. GitHub Actions builds and signs the APK on every
push and refreshes `app/release/morse-link.apk` (see `.github/workflows/apk.yml`).

The demo signing key lives in `keystore/morse-link.jks` (alias and password `morselink`) so clones
stay installable — replace it before publishing anywhere.

## Privacy

Offline by design. No analytics, no telemetry, no ad SDKs. Files only ever travel to a device you
explicitly pair with. Location is requested only where the OS requires it for BLE/Wi-Fi discovery
(pre-API 31) and is never stored or transmitted.

## Logo

Two interlocking links on brand green (`#1FA36B`) — the "link" in Morselink:

| | |
| --- | --- |
| Launcher icon | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (adaptive) + legacy PNGs |
| Mark | `art/morselink-mark-1024.png` |
| Store icon | `art/morselink-play-icon-512.png` |
| Banner | `art/morselink-banner.png` |

Everything is generated by `tools/make_logo.py` (`pip install pillow`), so the mark is
reproducible and always crisp.
