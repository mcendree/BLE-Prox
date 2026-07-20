# Pixel Proximity (Wiliot)

Android app for the Pixel 8 Pro that detects **Wiliot Pixel** BLE tags, resolves
their real **Pixel IDs** via the Wiliot cloud, and tells you how close you are to
a specific Pixel using on-device RSSI. Built for environments with many Pixels
in range — search by Pixel ID and track one.

## Why this is different from a plain BLE scanner

Wiliot Pixels don't broadcast a readable ID. Their advertising payload is
**encrypted and rotating** for privacy, so the MAC address / raw bytes you'd see
in a generic BLE scanner are useless as an identifier. To get a stable Pixel ID
you must send the encrypted payload to the **Wiliot cloud** to be resolved. This
app uses the official [Wiliot Android SDK](https://github.com/OpenAmbientIoT/wiliot-android-sdk)
to do exactly that.

## Privacy: what leaves the device

- **Sent to Wiliot cloud (REST):** only the encrypted payload + tag id needed to
  resolve a Pixel ID. This is done by the `wiliot-network-meta` module's
  `/v1/owner/{ownerId}/resolve` call. **RSSI is NOT part of that request.**
- **Stays on the device:** RSSI and all distance / closer-further math. The app
  also disables the SDK's telemetry pipeline (`pixelsTrafficEnabled = false`,
  `edgeTrafficEnabled = false`) so RSSI/packet-count telemetry is never published
  to Wiliot's MQTT broker.

> Caveat: the SDK's foreground-service/queue module may still open an MQTT
> connection for service heartbeat/capabilities, but with pixel + edge traffic
> disabled it does not publish your RSSI or packet data. ID resolution itself is
> REST-only and never includes RSSI.

## Credentials

On first launch the app asks for your **Wiliot owner ID** and **API key**
(entered at runtime, stored in app-private storage — no hardcoding). Get these
from the Wiliot management console. Use the "clear credentials" (logout) icon in
the top bar to change them.

## Features

- Live list of nearby Wiliot Pixels, sorted by signal strength (closest first).
- Each row shows the resolved **Pixel ID**, a `resolving…/resolved` state, an
  estimated distance, and raw RSSI.
- **Search Pixel ID** — filter the list as you type.
- **Tap a Pixel** to open a big proximity meter that grows as you approach.
- **Calibration** panel (RSSI-at-1m + path-loss) to tune distance to your space.

---

## ⚠️ Read this before you build: SDK symbol reconciliation

This project was written against the Wiliot SDK **source** (v3.9.0). Because the
SDK is a fast-moving binary dependency, a few symbol names may differ in the
version you actually pull from Maven Central. Everything Wiliot-specific is
isolated in **three files**, so if the first build reports unresolved references,
these are the only places to fix (the compiler error points right at the line):

1. `WiliotController.kt` — SDK init + config. Watch these symbols:
   - `WiliotAppConfigurationSource.DefaultSdkPreferenceSource` and its override
     methods: `ownerId()`, `resolveEnabled()`, `pixelsTrafficEnabled()`,
     `edgeTrafficEnabled()`, `isServicePhoenixEnabled()`.
   - The module init extensions: `initQueue()`, `initUpstream()`,
     `initMetaNetwork()`, `initDataResolver()` (and their import packages).
   - `Wiliot.init { … }` scope: `contextProviderBy`, `setApiKey(...)`,
     `frameworkDelegateBy`, `locationManagerBy`.
2. `LocationManagerImpl.kt` — must match `LocationManagerContract`. If method
   signatures differ, copy the canonical file from the SDK sample:
   `app/src/main/java/com/wiliot/wiliotandroidsdk/utils/LocationManagerImpl.kt`.
3. `PixelScanViewModel.kt` — the data Flows and model fields:
   - `WiliotDataResolver.beaconsFlow()` → `List<PacketData>`
   - `WiliotDataResolver.resolveInfoFlow()` → resolved identities
   - `PacketData.rssi`, `PacketData.deviceMAC`, `PacketData.name`
     (`name` = raw endpoint id until resolved, then the resolved Pixel ID).

Also confirm the current BOM version and set it in `app/build.gradle.kts`
(`val wiliotBom`): <https://central.sonatype.com/artifact/com.wiliot/wiliot-bom>

If any symbol truly moved, search the SDK source and adjust — or send me the
build error and I'll patch it.

---

## Getting the APK

The Wiliot SDK is on Maven Central, which the cloud build can reach.

### Option A — GitHub Actions (no Mac setup)

1. Create a GitHub repo and upload this `WiliotPixelProximity` folder (include the
   hidden `.github/workflows/build.yml`).
2. Open the **Actions** tab — the **Build APK** workflow runs automatically.
3. Download the **`pixel-proximity-debug-apk`** artifact, unzip, get
   `app-debug.apk`, transfer to your phone, install.

### Option B — Android Studio (local, works on your Mac)

1. Install Android Studio, **File → Open** this folder, let Gradle sync.
2. **Build → Build APK(s)**, or press **Run** with your phone connected
   (developer mode + USB debugging on).

## Installing on the Pixel 8 Pro

Open `app-debug.apk` from the Files app, allow "install from unknown apps" for
that source, install, launch. Grant **Nearby devices** + **Location** when asked
(the Wiliot SDK requires location for BLE scanning), then enter credentials and
press **Scan**.

## Notes

- **Package/app id**: `com.example.pixelproximity` — change in
  `app/build.gradle.kts` + folder if you want your own.
- **minSdk 29** (Wiliot requirement); your Pixel 8 Pro is far above that.
- This is a debug build (self-signed) — fine for sideloading to your own phone.
```
